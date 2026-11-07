import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:flutter/services.dart';
import 'package:quickjs_engine/quickjs_engine.dart';

/// A request-local ES module / Cat spider host. It deliberately has no filesystem,
/// process, Android, or account APIs. Synchronous DRPY request() is a separate ABI.
class JsSourceHost {
  static const maxBytes = 6 * 1024 * 1024;

  static bool isCommonJs(String source) => RegExp(
    r'\b(?:module\s*\.\s*exports\b|exports\s*(?:\.|\[))',
  ).hasMatch(source);

  static bool isEsModule(String source) => _JsModuleSyntax(source).isModule;

  /// Static dependencies only: dynamic import() needs a separately loaded module.
  static Iterable<String> esModuleReferences(String source) =>
      _JsModuleSyntax(source).references;

  static Iterable<String> commonJsReferences(String source) => RegExp(
    r'''\brequire\s*\(\s*['"]([^'"]+)['"]\s*\)''',
  ).allMatches(source).map((match) => match.group(1)!);

  static Future<String> invoke({
    required String entry,
    required Map<String, String> modules,
    required String method,
    required List<Object?> arguments,
    Object? extension,
    Map<String, String> headers = const {},
    Duration budget = const Duration(seconds: 12),
    List<String> pythonPrelude = const [],
  }) async {
    final interop = await rootBundle.loadString(
      'assets/js-host/module-interop.js',
    );
    // Capture only transferable values, never a UI object or an HTTP client.
    return Isolate.run(
      () => _execute(
        entry,
        modules,
        method,
        arguments,
        extension,
        headers,
        budget,
        pythonPrelude,
        interop,
      ),
    );
  }

  static Future<String> _execute(
    String entry,
    Map<String, String> modules,
    String method,
    List<Object?> arguments,
    Object? extension,
    Map<String, String> headers,
    Duration budget,
    List<String> pythonPrelude,
    String interop,
  ) async {
    final watch = Stopwatch()..start();
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 4);
    final requests = <Future<dynamic>>[];
    final runtime = QuickJsRuntime2(
      memoryLimit: 48 * 1024 * 1024,
      stackSize: 512 * 1024,
      timeout: budget.inMilliseconds,
      moduleHandler: (name) {
        final module = modules[name];
        if (module == null) throw StateError('脚本依赖未加载：$name');
        return module;
      },
    );

    Future<String> request(dynamic raw) async {
      final args = raw as List;
      final uri = Uri.parse(args[0].toString());
      if (uri.scheme != 'http' && uri.scheme != 'https') {
        throw const FormatException('脚本网络请求只支持 HTTP/HTTPS');
      }
      final options = args[1] is Map ? args[1] as Map : const {};
      var remaining = budget - watch.elapsed;
      if (remaining <= Duration.zero) throw TimeoutException('脚本请求超时');
      final requestedTimeout = num.tryParse(
        (options['timeout'] ?? '').toString(),
      );
      if (requestedTimeout != null &&
          requestedTimeout.isFinite &&
          requestedTimeout > 0) {
        final requested = Duration(milliseconds: requestedTimeout.ceil());
        if (requested < remaining) remaining = requested;
      }
      HttpClientRequest? active;
      final timer = Timer(remaining, () {
        active?.abort(TimeoutException('脚本请求超时'));
        client.close(force: true);
      });
      try {
        var verb = (options['method'] ?? 'GET').toString().toUpperCase();
        if (!const [
          'GET',
          'POST',
          'HEAD',
          'PUT',
          'PATCH',
          'DELETE',
        ].contains(verb)) {
          throw const FormatException('脚本请求方法暂不支持');
        }
        final requestHeaders = <String, String>{'user-agent': 'okhttp/3.15'};
        final origin = Uri.tryParse(entry);
        bool sameOrigin(Uri a, Uri b) =>
            a.scheme == b.scheme &&
            a.host.toLowerCase() == b.host.toLowerCase() &&
            a.port == b.port;
        if (origin != null && sameOrigin(origin, uri)) {
          headers.forEach(
            (key, value) => requestHeaders[key.toLowerCase()] = value,
          );
        }
        if (options['headers'] is Map) {
          (options['headers'] as Map).forEach((k, v) {
            requestHeaders[k.toString().toLowerCase()] = v.toString();
          });
        }
        if (uri.userInfo.isNotEmpty) {
          requestHeaders.putIfAbsent(
            'authorization',
            () =>
                'Basic ${base64Encode(utf8.encode(Uri.decodeComponent(uri.userInfo)))}',
          );
        }
        final body = options['data'] ?? options['body'];
        var payload = body == null
            ? null
            : utf8.encode(body is String ? body : jsonEncode(body));
        if (payload != null && payload.length > maxBytes) {
          throw const FormatException('脚本请求体过大');
        }
        var current = uri.replace(userInfo: '').removeFragment();
        for (var hop = 0; hop <= 8; hop++) {
          final req = active = await client.openUrl(verb, current);
          req.followRedirects = false;
          requestHeaders.forEach(req.headers.set);
          if (payload != null) req.add(payload);
          final response = await req.close();
          if (response.contentLength > maxBytes) {
            throw const FormatException('脚本响应超过 6 MB');
          }
          final bytes = <int>[];
          await for (final chunk in response) {
            if (bytes.length + chunk.length > maxBytes) {
              throw const FormatException('脚本响应超过 6 MB');
            }
            bytes.addAll(chunk);
          }
          final code = response.statusCode;
          final location = response.headers.value(HttpHeaders.locationHeader);
          if (options['redirect'] != 0 &&
              location != null &&
              const [301, 302, 303, 307, 308].contains(code)) {
            if (hop == 8) throw const HttpException('脚本请求重定向过多');
            final next = current
                .resolve(location)
                .replace(userInfo: '')
                .removeFragment();
            if (next.scheme != 'http' && next.scheme != 'https') {
              throw const FormatException('脚本重定向只支持 HTTP/HTTPS');
            }
            if (!sameOrigin(current, next)) {
              for (final key in [
                'authorization',
                'cookie',
                'cookie2',
                'proxy-authorization',
                'host',
              ]) {
                requestHeaders.remove(key);
              }
            }
            if ((code == 303 && verb != 'HEAD') ||
                ((code == 301 || code == 302) && verb == 'POST')) {
              verb = 'GET';
              payload = null;
              for (final key in [
                'content-type',
                'content-length',
                'transfer-encoding',
              ]) {
                requestHeaders.remove(key);
              }
            }
            current = next;
            continue;
          }
          final responseHeaders = <String, String>{};
          response.headers.forEach((k, v) => responseHeaders[k] = v.join(', '));
          return jsonEncode({
            'code': code,
            'url': current.toString(),
            'headers': responseHeaders,
            'content': options['buffer'] == 2
                ? base64Encode(bytes)
                : utf8.decode(bytes, allowMalformed: true),
          });
        }
        throw const HttpException('脚本请求重定向过多');
      } finally {
        timer.cancel();
      }
    }

    Future<dynamic> checked(String code, {bool module = false}) async {
      final evaluated = runtime.evaluate(
        code,
        name: '$entry.host.mjs',
        evalFlags: module ? JSEvalFlag.MODULE : JSEvalFlag.GLOBAL,
      );
      if (evaluated.isError) throw StateError(evaluated.stringResult);
      final result = evaluated.rawResult;
      if (result is! Future) return result;
      Object? value;
      Object? failure;
      bool done = false;
      result.then(
        (v) {
          value = v;
          done = true;
        },
        onError: (Object e) {
          failure = e;
          done = true;
        },
      );
      while (!done) {
        if (watch.elapsed >= budget) throw TimeoutException('脚本请求超时');
        runtime.executePendingJob();
        await Future<void>.delayed(const Duration(milliseconds: 5));
      }
      if (failure != null) throw StateError(failure.toString());
      return value;
    }

    try {
      runtime.onMessage('request', (args) {
        if (requests.length >= 32) throw StateError('单次脚本操作的网络请求过多');
        final pending = request(args);
        // Attach the error handler immediately, including before JS awaits it.
        requests.add(pending.then<void>((_) {}, onError: (Object _) {}));
        return pending;
      });
      await checked(r'''
        globalThis.global = globalThis;
        globalThis.window = globalThis;
        globalThis.self = globalThis;
        globalThis.req = async (url, options = {}) =>
          JSON.parse(await sendMessage('request', JSON.stringify([url, options])));
        globalThis.console = {log(){}, info(){}, warn(){}, error(){}};
        globalThis.log = () => {};
        globalThis.print = log;
      ''');
      final source = modules[entry]!;
      await checked(interop);
      if (pythonPrelude.isNotEmpty) {
        for (final text in pythonPrelude) {
          await checked('$text\n;void 0;');
        }
        final directory = Uri.parse(entry).resolve('.').toString();
        final siblings = {
          for (final item in modules.entries)
            if (item.key != entry &&
                item.key.startsWith(directory) &&
                item.key.endsWith('.py'))
              item.key.substring(directory.length): item.value,
        };
        await checked(
          'globalThis.__movieSpider = createPythonSpider(${jsonEncode(source)},${jsonEncode(siblings)}); void 0;',
        );
      } else if (source.contains('__jsEvalReturn') && !isEsModule(source)) {
        await checked(source);
        await checked('globalThis.__movieSpider = __jsEvalReturn();');
      } else if (isEsModule(source)) {
        await checked(
          'import * as spider from ${jsonEncode(entry)}; '
          'globalThis.__movieSpider = __movieSelectSpider(spider);',
          module: true,
        );
      } else if (isCommonJs(source)) {
        final resolution = <String, Map<String, String>>{};
        for (final module in modules.entries) {
          final references = {module.key, ...commonJsReferences(module.value)};
          resolution[module.key] = {
            for (final ref in references)
              ref: _resolveCommonJsModule(module.key, ref, modules),
          };
        }
        await checked('''
          globalThis.__movieModules = ${jsonEncode(modules)};
          globalThis.__movieResolution = ${jsonEncode(resolution)};
          globalThis.__movieSpider = __movieSelectSpider(__movieLoadCommonJS(${jsonEncode(entry)}, (ref, parent) => {
            const name = __movieResolution[parent]?.[ref];
            if (!name || !Object.prototype.hasOwnProperty.call(__movieModules, name))
              throw new Error('Script dependency is unavailable: ' + ref);
            return {name, source: __movieModules[name]};
          })); void 0;
        ''');
      } else {
        throw const FormatException(
          '当前脚本宿主支持 ES module / CommonJS / __jsEvalReturn；DRPY rule 需要对应宿主适配',
        );
      }
      await checked(
        'Promise.resolve(__movieSpider.init?.(${jsonEncode(extension)}))',
      );
      final output = await checked(
        'Promise.resolve().then(() => { '
        'const fn = __movieSpider[${jsonEncode(method)}]; '
        'if (typeof fn !== "function") throw new Error("脚本缺少 $method 接口"); '
        'return fn.apply(__movieSpider, ${jsonEncode(arguments)}); '
        '}).then(v => typeof v === "string" ? v : JSON.stringify(v))',
      );
      final text = output?.toString() ?? '';
      if (utf8.encode(text).length > maxBytes) {
        throw const FormatException('脚本结果过大');
      }
      return text;
    } finally {
      client.close(force: true);
      await Future.wait(requests);
      // Complete any host promises before destroying their native context.
      runtime.executePendingJob();
      runtime.dispose();
    }
  }

  static String _resolveCommonJsModule(
    String parent,
    String ref,
    Map<String, String> modules,
  ) {
    if (!ref.startsWith('.') &&
        !ref.startsWith('http://') &&
        !ref.startsWith('https://') &&
        !ref.startsWith('pkg://') &&
        !ref.startsWith('file://')) {
      throw StateError('CommonJS 不提供 Node 内置模块：$ref');
    }
    final uri = Uri.parse(parent).resolve(ref);
    final resolved = uri.toString();
    for (final candidate in [
      resolved,
      uri.replace(path: '${uri.path}.js').toString(),
      uri.replace(path: '${uri.path}.json').toString(),
      uri.replace(path: '${uri.path}/index.js').toString(),
    ]) {
      if (modules.containsKey(candidate)) return candidate;
    }
    throw StateError('脚本依赖未加载：$ref');
  }
}

/// A lexical scan of static module declarations, not a rewrite of user code.
/// Literals/comments are consumed before looking for import/export keywords.
class _JsModuleSyntax {
  _JsModuleSyntax(String source) {
    final tokens = _JsModuleLexer(source).scan();
    for (var i = 0; i < tokens.length; i++) {
      final token = tokens[i];
      if (!token.topLevel || token.literal || i + 1 >= tokens.length) continue;
      final previous = i == 0 ? null : tokens[i - 1];
      if (previous?.text == '.' || previous?.text == '?.') continue;
      if (i > 0 &&
          !token.newLine &&
          previous!.text != ';' &&
          previous.text != '}')
        continue;
      final next = tokens[i + 1];
      if (token.text == 'import') {
        if (next.text == '(' || next.text == '.') continue;
        if (next.literal) {
          isModule = true;
          references.add(next.text);
          continue;
        }
        if (next.text != '{' && next.text != '*' && !next.identifier) continue;
        isModule = true;
      } else if (token.text == 'export') {
        if (!const {
          'default',
          'const',
          'let',
          'var',
          'function',
          'class',
          'async',
          '{',
          '*',
        }.contains(next.text))
          continue;
        isModule = true;
        if (next.text != '{' && next.text != '*') continue;
      } else {
        continue;
      }
      // Import/export clauses can span lines and contain a named binding list.
      for (var j = i + 1; j < tokens.length; j++) {
        final part = tokens[j];
        if (part.topLevel && part.text == ';') break;
        if (j > i + 1 &&
            part.topLevel &&
            const {'import', 'export'}.contains(part.text))
          break;
        if (part.topLevel &&
            !part.literal &&
            part.text == 'from' &&
            j + 1 < tokens.length &&
            tokens[j + 1].literal) {
          references.add(tokens[j + 1].text);
          break;
        }
      }
    }
  }

  bool isModule = false;
  final references = <String>[];
}

class _JsModuleToken {
  const _JsModuleToken(
    this.text,
    this.topLevel,
    this.newLine, {
    this.literal = false,
    this.identifier = false,
  });
  final String text;
  final bool topLevel, newLine, literal, identifier;
}

class _JsModuleLexer {
  _JsModuleLexer(this.source);
  final String source;
  int at = 0;
  bool newLine = false;
  String char([int offset = 0]) =>
      at + offset < source.length ? source[at + offset] : '';
  static final _word = RegExp(r'[\w$\u0080-\uffff]');
  bool word(String c) => c.isNotEmpty && _word.hasMatch(c);
  bool regexAfter(String token) => const {
    '',
    '(',
    '[',
    '{',
    ',',
    ';',
    ':',
    '=',
    '!',
    '?',
    '&',
    '|',
    '+',
    '-',
    '*',
    '%',
    '^',
    '~',
    '<',
    '>',
    'return',
    'throw',
    'case',
    'delete',
    'void',
    'typeof',
    'yield',
    'await',
    'else',
    'do',
    'in',
    'of',
  }.contains(token);

  List<_JsModuleToken> scan() {
    final tokens = <_JsModuleToken>[];
    var braces = 0, brackets = 0, parentheses = 0;
    var previous = '';
    while (at < source.length) {
      if (skipTrivia()) continue;
      final top = braces == 0 && brackets == 0 && parentheses == 0;
      final line = newLine;
      newLine = false;
      final c = char();
      late _JsModuleToken token;
      if (c == '"' || c == "'") {
        token = _JsModuleToken(quoted(), top, line, literal: true);
        previous = '<string>';
      } else if (c == '`') {
        template();
        token = _JsModuleToken('<template>', top, line);
        previous = token.text;
      } else if (c == '/' && regexAfter(previous) && regex()) {
        token = _JsModuleToken('<regex>', top, line);
        previous = token.text;
      } else if (word(c)) {
        final start = at++;
        while (word(char())) {
          at++;
        }
        final text = source.substring(start, at);
        token = _JsModuleToken(
          text,
          top,
          line,
          identifier: !RegExp(r'^\d').hasMatch(text),
        );
        previous = text;
      } else {
        at++;
        if (c == '{') braces++;
        if (c == '}') braces--;
        if (c == '[') brackets++;
        if (c == ']') brackets--;
        if (c == '(') parentheses++;
        if (c == ')') parentheses--;
        token = _JsModuleToken(c, top, line);
        previous = c;
      }
      tokens.add(token);
    }
    return tokens;
  }

  bool skipTrivia() {
    final c = char();
    if (c.isNotEmpty && c.trim().isEmpty) {
      if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029')
        newLine = true;
      at++;
      return true;
    }
    if ((c == '/' && char(1) == '/') ||
        (at == 0 && c == '#' && char(1) == '!')) {
      while (at < source.length && char() != '\n' && char() != '\r') {
        at++;
      }
      return true;
    }
    if (c == '/' && char(1) == '*') {
      at += 2;
      while (at < source.length && !(char() == '*' && char(1) == '/')) {
        if (char() == '\n' || char() == '\r') newLine = true;
        at++;
      }
      at = (at + 2).clamp(0, source.length);
      return true;
    }
    return false;
  }

  String quoted() {
    final quote = char();
    at++;
    final value = StringBuffer();
    while (at < source.length) {
      final c = char();
      at++;
      if (c == quote) break;
      if (c != r'\') {
        value.write(c);
        continue;
      }
      if (at >= source.length) break;
      final escaped = char();
      at++;
      if (escaped == '\n' || escaped == '\r') {
        if (escaped == '\r' && char() == '\n') at++;
        continue;
      }
      if (escaped == 'x' || escaped == 'u') {
        final brace = escaped == 'u' && char() == '{';
        if (brace) at++;
        final end = brace
            ? source.indexOf('}', at)
            : at + (escaped == 'x' ? 2 : 4);
        if (end >= at && end <= source.length) {
          final cp = int.tryParse(source.substring(at, end), radix: 16);
          if (cp != null && cp <= 0x10ffff) {
            value.writeCharCode(cp);
            at = end + (brace ? 1 : 0);
            continue;
          }
        }
      }
      value.write(
        const {
              'n': '\n',
              'r': '\r',
              't': '\t',
              'b': '\b',
              'f': '\f',
              'v': '\u000b',
              '0': '\u0000',
            }[escaped] ??
            escaped,
      );
    }
    return value.toString();
  }

  bool regex() {
    var cursor = at + 1;
    var inClass = false;
    while (cursor < source.length) {
      final c = source[cursor++];
      if (c == '\n' || c == '\r') return false;
      if (c == r'\') {
        cursor++;
        continue;
      }
      if (c == '[') inClass = true;
      if (c == ']') inClass = false;
      if (c == '/' && !inClass) {
        at = cursor;
        while (word(char())) {
          at++;
        }
        return true;
      }
    }
    return false;
  }

  void template() {
    at++;
    while (at < source.length) {
      final c = char();
      at++;
      if (c == r'\') {
        if (at < source.length) at++;
        continue;
      }
      if (c == '`') return;
      if (c != r'$' || char() != '{') continue;
      at++;
      var depth = 1;
      var previous = '';
      while (at < source.length && depth > 0) {
        if (skipTrivia()) continue;
        final inner = char();
        if (inner == '"' || inner == "'") {
          quoted();
          previous = '<string>';
        } else if (inner == '`') {
          template();
          previous = '<template>';
        } else if (inner == '/' && regexAfter(previous) && regex()) {
          previous = '<regex>';
        } else {
          at++;
          if (inner == '{') depth++;
          if (inner == '}') depth--;
          previous = inner;
        }
      }
    }
  }
}
