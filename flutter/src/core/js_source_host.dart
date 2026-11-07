import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'js_source_syntax.dart';

import 'package:flutter/services.dart';
import 'package:quickjs_engine/quickjs_engine.dart';

/// A request-local ES module / Cat spider host. It deliberately has no filesystem,
/// process, Android, or account APIs. Synchronous DRPY request() is a separate ABI.
class JsSourceHost {
  static const maxBytes = 6 * 1024 * 1024;

  static bool isCommonJs(String source) =>
      JsSourceSyntax(source).hasCommonJsExports;

  static bool isEsModule(String source) => JsSourceSyntax(source).isModule;

  static bool hasSpiderExports(String source) =>
      JsSourceSyntax(source).hasSpiderExports;

  static bool hasDrpyRule(String source) => JsSourceSyntax(source).hasDrpyRule;

  static bool isCatSpider(String source) => JsSourceSyntax(source).hasCatSpider;

  /// Static dependencies only: dynamic import() needs a separately loaded module.
  static Iterable<String> esModuleReferences(String source) =>
      JsSourceSyntax(source).references;

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
        List<int>? payload;
        if (options.containsKey('bodyBase64')) {
          final encoded = options['bodyBase64'];
          if (options.containsKey('data') || options.containsKey('body') ||
              encoded is! String) {
            throw const FormatException('脚本二进制请求体参数无效');
          }
          if (encoded.length > ((maxBytes + 2) ~/ 3) * 4) {
            throw const FormatException('脚本请求体过大');
          }
          if (encoded.length % 4 != 0 ||
              RegExp(r'[^A-Za-z0-9+/=]').hasMatch(encoded)) {
            throw const FormatException('脚本二进制请求体 Base64 无效');
          }
          payload = base64Decode(encoded);
          if (base64Encode(payload) != encoded) {
            throw const FormatException('脚本二进制请求体 Base64 无效');
          }
        } else {
          payload = body == null
              ? null
              : utf8.encode(body is String ? body : jsonEncode(body));
        }
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
      } else if (isCatSpider(source) && !isEsModule(source)) {
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
