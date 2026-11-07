import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'js_source_syntax.dart';

import 'package:flutter/services.dart';
import 'package:quickjs_engine/quickjs_engine.dart';

/// Source-scoped ES module / Cat / Python host. Each retained source owns one
/// isolated runtime; no filesystem, process, Android, or account APIs are exposed.
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

  static final _sessions = <String, _SourceWorker>{};
  static int _generation = 0;
  static int get generation => _generation;
  static const _maxSessions = 6;
  static Completer<void> _capacityChanged = Completer<void>();

  static void _notifyCapacity() {
    final changed = _capacityChanged;
    _capacityChanged = Completer<void>();
    changed.complete();
  }

  /// Removal is synchronous; teardown follows on each runtime's owning isolate.
  /// A loader started before this boundary cannot recreate a deleted session.
  static Future<void> retainSessions(Set<String> keys) {
    _generation++;
    final removed = _sessions.keys.where((key) => !keys.contains(key)).toList();
    final closing = <Future<void>>[];
    for (final key in removed) {
      closing.add(_sessions.remove(key)!.close());
    }
    _notifyCapacity();
    return Future.wait(closing);
  }

  static Future<void> closeAll() => retainSessions({});

  static Future<String?> callCached({
    required String sessionKey,
    required String method,
    required List<Object?> arguments,
    Duration budget = const Duration(seconds: 12),
    Completer<void>? cancellation,
  }) async {
    final worker = _sessions.remove(sessionKey);
    if (worker == null || worker._closed) return null;
    _sessions[sessionKey] = worker; // LRU: active workers are never evicted.
    return worker.invoke(method, arguments, budget, cancellation);
  }

  static Future<String> invoke({
    required String entry,
    required Map<String, String> modules,
    required String method,
    required List<Object?> arguments,
    Object? extension,
    Map<String, String> headers = const {},
    Duration budget = const Duration(seconds: 12),
    List<String> pythonPrelude = const [],
    String? sessionKey,
    int? expectedGeneration,
    bool catDetails = false,
    Completer<void>? cancellation,
  }) async {
    if (cancellation?.isCompleted == true) throw StateError('脚本操作已取消');
    final startedGeneration = expectedGeneration ?? _generation;
    final interop = await rootBundle.loadString(
      'assets/js-host/module-interop.js',
    );
    void check() {
      if (cancellation?.isCompleted == true ||
          startedGeneration != _generation) {
        throw StateError('脚本会话已失效');
      }
    }

    check();
    if (sessionKey != null) {
      final waiting = Stopwatch()..start();
      if (_sessions[sessionKey]?._closed == true) _sessions.remove(sessionKey);
      while (!_sessions.containsKey(sessionKey) &&
          _sessions.length >= _maxSessions) {
        final idle = _sessions.entries.where((item) => !item.value.busy);
        if (idle.isEmpty) {
          final remaining = budget - waiting.elapsed;
          if (remaining <= Duration.zero) throw TimeoutException('等待脚本运行环境超时');
          await Future.any([
            _capacityChanged.future,
            if (cancellation != null) cancellation.future,
          ]).timeout(remaining);
          check();
          continue;
        }
        await _sessions.remove(idle.first.key)!.close();
        check();
      }
    }
    final worker = sessionKey == null ? null : _sessions[sessionKey];
    final selected =
        worker ??
        _SourceWorker([
          entry,
          Map<String, String>.from(modules),
          extension,
          Map<String, String>.from(headers),
          List<String>.from(pythonPrelude),
          interop,
        ], catDetails: catDetails);
    if (sessionKey != null) _sessions[sessionKey] = selected;
    try {
      return await selected.invoke(method, arguments, budget, cancellation);
    } finally {
      if (sessionKey == null) await selected.close();
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

/// All commands for one source are serialized. Different sources run independently.
class _SourceWorker {
  final List<Object?> configuration;
  final bool catDetails;
  Future<SendPort>? _ready;
  Future<void> _tail = Future.value();
  Future<void>? _closing;
  final _exited = Completer<void>();
  final _exitEvents = StreamController<void>.broadcast(sync: true);
  bool _closed = false;
  int _pending = 0;
  int _nextId = 0;
  bool get busy => _pending > 0;

  _SourceWorker(this.configuration, {required this.catDetails});

  Future<SendPort> _start() => _ready ??= () async {
    final ready = ReceivePort();
    final lifecycle = ReceivePort();
    lifecycle.listen((_) {
      _closed = true;
      if (!_exited.isCompleted) _exited.complete();
      _exitEvents.add(null);
      unawaited(_exitEvents.close());
      JsSourceHost._notifyCapacity();
      lifecycle.close();
    });
    try {
      await Isolate.spawn(
        _sourceWorkerMain,
        [ready.sendPort, configuration],
        onExit: lifecycle.sendPort,
        onError: lifecycle.sendPort,
      );
      return await _whileAlive(ready.first) as SendPort;
    } catch (_) {
      _closed = true;
      if (!_exited.isCompleted) {
        _exited.complete();
        unawaited(_exitEvents.close());
      }
      lifecycle.close();
      JsSourceHost._notifyCapacity();
      rethrow;
    } finally {
      ready.close();
    }
  }();

  Future<dynamic> _whileAlive(Future<dynamic> result) async {
    if (_exited.isCompleted) throw StateError('脚本运行环境已退出');
    final ended = Completer<dynamic>();
    final listener = _exitEvents.stream.listen((_) {
      ended.completeError(StateError('脚本运行环境已退出'));
    });
    try {
      return await Future.any([result, ended.future]);
    } finally {
      // A Future listener cannot be detached. Keeping one on the worker's exit
      // future per call would retain completed results for the session's life.
      await listener.cancel();
    }
  }

  Future<String> invoke(
    String method,
    List<Object?> arguments,
    Duration budget,
    Completer<void>? cancellation,
  ) {
    if (_closed) return Future.error(StateError('脚本会话已关闭'));
    if (budget <= Duration.zero) return Future.error(ArgumentError('budget'));
    _pending++;
    final task = _tail.then((_) async {
      if (_closed || cancellation?.isCompleted == true) {
        throw StateError('脚本操作已取消');
      }
      final port = await _start();
      if (_closed || cancellation?.isCompleted == true) {
        throw StateError('脚本操作已取消');
      }
      final reply = ReceivePort();
      final id = ++_nextId;
      bool finished = false;
      cancellation?.future.then((_) {
        if (!finished) port.send(['cancel', id]);
      });
      try {
        port.send([
          'call',
          id,
          reply.sendPort,
          method,
          catDetails && method == 'detail' ? [arguments] : arguments,
          budget.inMilliseconds,
        ]);
        final result = await _whileAlive(reply.first) as List;
        if (cancellation?.isCompleted == true || _closed) {
          throw StateError('脚本操作已取消');
        }
        if (result[0] != true) throw StateError(result[1].toString());
        return result[1] as String;
      } finally {
        finished = true;
        reply.close();
      }
    });
    _tail = task.then<void>((_) {}, onError: (Object _) {}).whenComplete(() {
      _pending--;
      if (!busy) JsSourceHost._notifyCapacity();
    });
    return task;
  }

  Future<void> close() {
    _closed = true;
    return _closing ??= () async {
      final ready = _ready;
      if (ready == null) {
        if (!_exited.isCompleted) _exited.complete();
        await _exitEvents.close();
        return;
      }
      final reply = ReceivePort();
      try {
        SendPort port;
        try {
          port = await ready;
        } catch (_) {
          return;
        }
        if (_exited.isCompleted) return;
        port.send(['close', reply.sendPort]);
        await Future.any([reply.first, _exited.future]);
      } finally {
        reply.close();
      }
    }();
  }
}

void _sourceWorkerMain(List<Object?> startup) {
  final messages = ReceivePort();
  final configuration = startup[1] as List;
  (startup[0] as SendPort).send(messages.sendPort);
  _JsRuntimeSession? session;
  int? activeId;
  bool closing = false;
  Future<void> tail = Future.value();
  messages.listen((dynamic value) {
    final command = value as List;
    if (command[0] == 'cancel') {
      if (activeId == command[1]) session?.cancel();
    } else if (command[0] == 'close') {
      closing = true;
      session?.cancel();
      tail.then((_) {
        try {
          session?.dispose();
        } finally {
          session = null;
          messages.close();
          (command[1] as SendPort).send(true);
        }
      });
    } else if (command[0] == 'call') {
      tail = tail.then((_) async {
        final reply = command[2] as SendPort;
        try {
          if (closing) throw StateError('脚本会话已关闭');
          activeId = command[1] as int;
          session ??= _JsRuntimeSession(configuration);
          final result = await session!.invoke(
            command[3] as String,
            command[4] as List<Object?>,
            Duration(milliseconds: command[5] as int),
          );
          reply.send([true, result]);
        } catch (error) {
          // A partially executed or interrupted script is never reused.
          try {
            session?.dispose();
          } finally {
            session = null;
            reply.send([false, error.toString()]);
          }
        } finally {
          activeId = null;
        }
      });
    }
  });
}

class _JsRuntimeSession {
  static const maxBytes = JsSourceHost.maxBytes;
  final String entry;
  final Map<String, String> modules;
  final Object? extension;
  final Map<String, String> headers;
  final List<String> pythonPrelude;
  final String interop;
  late final QuickJsRuntime2 runtime;
  late HttpClient client;
  final watch = Stopwatch();
  late Duration budget;
  final requests = <Future<dynamic>>[];
  final _timers = <String, Timer>{};
  bool _initialized = false;
  bool _cancelled = false;
  bool _active = false;

  _JsRuntimeSession(List configuration)
    : entry = configuration[0] as String,
      modules = configuration[1] as Map<String, String>,
      extension = configuration[2],
      headers = configuration[3] as Map<String, String>,
      pythonPrelude = configuration[4] as List<String>,
      interop = configuration[5] as String {
    runtime = QuickJsRuntime2(
      memoryLimit: 48 * 1024 * 1024,
      stackSize: 512 * 1024,
      timeout: 12000,
      moduleHandler: (name) {
        final module = modules[name];
        if (module == null) throw StateError('脚本依赖未加载：$name');
        return module;
      },
    );
    runtime.onMessage('request', (args) {
      if (!_active || _cancelled) throw StateError('脚本操作已取消');
      if (requests.length >= 32) throw StateError('单次脚本操作的网络请求过多');
      final pending = request(args);
      requests.add(pending.then<void>((_) {}, onError: (Object _) {}));
      return pending;
    });
    // Override the runtime's untracked timers. No callback survives an operation.
    runtime.onMessage('SetTimeout', (args) {
      if (!_active || _cancelled) return;
      final id = args['timeoutIndex'].toString();
      if (_timers.length >= 128) throw StateError('脚本定时任务过多');
      _timers[id] = Timer(
        Duration(milliseconds: (args['timeout'] as num?)?.toInt() ?? 0),
        () {
          _timers.remove(id);
          if (!_active || _cancelled) return;
          runtime.evaluate("""(() => {
          const callbacks = __NATIVE_FLUTTER_JS__setTimeoutCallbacks;
          const fn = callbacks[${jsonEncode(id)}];
          delete callbacks[${jsonEncode(id)}];
          if (typeof fn === 'function') fn();
        })()""");
        },
      );
    });
  }

  void cancel() {
    _cancelled = true;
    if (_active) client.close(force: true);
  }

  void dispose() {
    cancel();
    for (final timer in _timers.values) {
      timer.cancel();
    }
    _timers.clear();
    runtime.dispose();
  }

  Future<String> invoke(
    String method,
    List<Object?> arguments,
    Duration limit,
  ) async {
    budget = limit;
    watch
      ..reset()
      ..start();
    _cancelled = false;
    _active = true;
    requests.clear();
    client = HttpClient()..connectionTimeout = const Duration(seconds: 4);
    runtime.setOperationTimeout(budget);
    try {
      if (!_initialized) {
        await initialize();
        _initialized = true;
      }
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
      _active = false;
      for (final timer in _timers.values) {
        timer.cancel();
      }
      _timers.clear();
      client.close(force: true);
      await Future.wait(requests);
      // Settle transport completions on the same isolate before the next call.
      runtime.executePendingJob();
      runtime.evaluate('__NATIVE_FLUTTER_JS__setTimeoutCallbacks = {};');
    }
  }

  Future<void> initialize() async {
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
    } else if (JsSourceHost.isCatSpider(source) &&
        !JsSourceHost.isEsModule(source)) {
      await checked(source);
      await checked('globalThis.__movieSpider = __jsEvalReturn();');
    } else if (JsSourceHost.isEsModule(source)) {
      await checked(
        'import * as spider from ${jsonEncode(entry)}; '
        'globalThis.__movieSpider = __movieSelectSpider(spider);',
        module: true,
      );
    } else if (JsSourceHost.isCommonJs(source)) {
      final resolution = <String, Map<String, String>>{};
      for (final module in modules.entries) {
        final references = {
          module.key,
          ...JsSourceHost.commonJsReferences(module.value),
        };
        resolution[module.key] = {
          for (final ref in references)
            ref: JsSourceHost._resolveCommonJsModule(module.key, ref, modules),
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
  }

  Future<String> request(dynamic raw) async {
    if (!_active || _cancelled) throw StateError('脚本操作已取消');
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
        if (options.containsKey('data') ||
            options.containsKey('body') ||
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
    if (_cancelled) throw StateError('脚本操作已取消');
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
      if (_cancelled) throw StateError('脚本操作已取消');
      if (watch.elapsed >= budget) throw TimeoutException('脚本请求超时');
      runtime.executePendingJob();
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    if (failure != null) throw StateError(failure.toString());
    return value;
  }
}
