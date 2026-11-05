import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

/// Robust cross-platform poster loader (Windows / macOS / iOS / iPadOS).
///
/// Key resilience mechanisms:
/// 1. Replaces `Image.network` + `ResizeImage` error-caching pitfalls by loading
///    and verifying image magic bytes in memory before handing `MemoryImage` to
///    Flutter's `ImageCache`, so transient network failures never stick in
///    `ImageCache` for minutes.
/// 2. Uses a LIFO concurrency queue (`_maxConcurrent = 16`) so newly visible
///    posters on the current screen load immediately ahead of stale requests
///    from previous tabs or sites.
/// 3. Aborts timed-out `HttpClientRequest` sockets immediately (`request.abort()`)
///    so slow overseas image hosts never exhaust per-host socket pools.
/// 4. Rewrites `img9.doubanio.com` (which serves an HTML JS challenge) directly
///    to `img3.doubanio.com` / `img1.doubanio.com` / `img2.doubanio.com`.
/// 5. Provides automatic domestic high-speed cover fallback by video `title` via
///    `movie.douban.com/j/subject_suggest` when a CMS poster URL is empty,
///    404, or stalls on overseas CDNs.
class PosterLoader {
  PosterLoader._();

  static const String _mobileSafariUa =
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';
  static const String _desktopSafariUa =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15';
  static const String _tvBoxUa = 'okhttp/3.15';
  static const int _maxImageBytes = 10 * 1024 * 1024;
  static const int _maxMemoryBytes = 64 * 1024 * 1024;
  static const int _maxConcurrent = 16;

  static final HttpClient _client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 4)
    ..idleTimeout = const Duration(seconds: 25)
    ..maxConnectionsPerHost = 12
    ..userAgent = null
    ..autoUncompress = true
    ..badCertificateCallback = (_, __, ___) => true;

  static final LinkedHashMap<String, Uint8List> _memory =
      LinkedHashMap<String, Uint8List>();
  static int _memoryBytes = 0;
  static final Map<String, Future<Uint8List>> _inflight = {};
  static final Map<String, String> _titleCoverUrlCache = {};
  static int _active = 0;
  static final Queue<Completer<void>> _waiters = Queue<Completer<void>>();
  static Directory? _diskDir;
  static bool _diskReady = false;

  static String _cleanTitle(String rawTitle) {
    return rawTitle
        .replaceAll(RegExp(r'[\(（\[【].*?[\)）\]】]'), '')
        .replaceAll(RegExp(r'第[0-9一二三四五六七八九十]+季'), '')
        .trim();
  }

  static String _cacheKeyFor(String rawUrl, String title) {
    final u = rawUrl.trim();
    if (u.isNotEmpty) return u;
    final t = _cleanTitle(title);
    return t.isEmpty ? '' : 'title:$t';
  }

  /// Synchronously peeks in-memory LRU cache so already-loaded posters render
  /// on the very first frame without any async gap.
  static Uint8List? peekMemory(String rawUrl, {String title = ''}) {
    final u = rawUrl.trim();
    if (u.isNotEmpty) {
      final hit = _memory.remove(u);
      if (hit != null) {
        _memory[u] = hit;
        return hit;
      }
    }
    final clean = _cleanTitle(title);
    if (clean.isNotEmpty) {
      final tKey = 'title:$clean';
      final hit = _memory.remove(tKey);
      if (hit != null) {
        _memory[tKey] = hit;
        return hit;
      }
    }
    return null;
  }

  /// Loads poster bytes, de-duplicating concurrent requests and falling back
  /// to domestic Douban cover resolution by [title] when needed.
  static Future<Uint8List> load(String raw, {String title = ''}) {
    final immediate = peekMemory(raw, title: title);
    if (immediate != null) {
      return SynchronousFuture<Uint8List>(immediate);
    }
    final key = _cacheKeyFor(raw, title);
    if (key.isEmpty) {
      return Future<Uint8List>.error(const FormatException('空海报地址'));
    }
    final pending = _inflight[key];
    if (pending != null) return pending;
    final future = _loadUncached(raw.trim(), title.trim(), key)
        .whenComplete(() => _inflight.remove(key));
    _inflight[key] = future;
    return future;
  }

  static bool _isKnownDeadPosterHost(String url) {
    final lower = url.toLowerCase();
    return lower.contains('mdzypic.com:888') ||
        lower.contains('pic.ry-pic.com');
  }

  static Future<Uint8List> _loadUncached(
    String rawUrl,
    String rawTitle,
    String cacheKey,
  ) async {
    final cleanTitle = _cleanTitle(rawTitle);
    final titleCacheKey = cleanTitle.isNotEmpty ? 'title:$cleanTitle' : '';

    // 1. Check disk cache for either the URL or the title key
    final diskFile = await _diskFileFor(cacheKey);
    if (diskFile != null) {
      try {
        if (await diskFile.exists()) {
          final bytes = await diskFile.readAsBytes();
          if (_looksLikeImage(bytes)) {
            _remember(cacheKey, bytes);
            if (titleCacheKey.isNotEmpty) _remember(titleCacheKey, bytes);
            return bytes;
          }
        }
      } catch (_) {}
    }
    if (titleCacheKey.isNotEmpty && titleCacheKey != cacheKey) {
      final titleDiskFile = await _diskFileFor(titleCacheKey);
      if (titleDiskFile != null) {
        try {
          if (await titleDiskFile.exists()) {
            final bytes = await titleDiskFile.readAsBytes();
            if (_looksLikeImage(bytes)) {
              _remember(cacheKey, bytes);
              _remember(titleCacheKey, bytes);
              return bytes;
            }
          }
        } catch (_) {}
      }
    }

    await _acquire();
    try {
      Object? lastError;

      // 2. Try primary URL attempts (unless empty or known dead host)
      if (rawUrl.isNotEmpty && !_isKnownDeadPosterHost(rawUrl)) {
        final attempts = _attemptsFor(rawUrl);
        // Use a tight per-attempt timeout when we have a title fallback available
        final perAttemptTimeout = cleanTitle.isNotEmpty
            ? const Duration(milliseconds: 3200)
            : const Duration(seconds: 6);
        final maxPrimaryAttempts =
            cleanTitle.isNotEmpty ? (attempts.length > 2 ? 2 : attempts.length) : attempts.length;

        for (int i = 0; i < maxPrimaryAttempts; i++) {
          final attempt = attempts[i];
          try {
            final bytes = await _fetch(
              attempt.$1,
              attempt.$2,
              timeout: perAttemptTimeout,
            );
            _remember(cacheKey, bytes);
            if (titleCacheKey.isNotEmpty) _remember(titleCacheKey, bytes);
            if (diskFile != null) {
              unawaited(_writeDisk(diskFile, bytes));
            }
            return bytes;
          } catch (e) {
            lastError = e;
          }
        }
      }

      // 3. Fallback: resolve high-speed domestic Douban poster by video title
      if (cleanTitle.isNotEmpty) {
        try {
          final fallbackUrl = await _resolveDoubanCoverUrlByTitle(cleanTitle);
          if (fallbackUrl.isNotEmpty) {
            for (final attempt in _attemptsFor(fallbackUrl)) {
              try {
                final bytes = await _fetch(
                  attempt.$1,
                  attempt.$2,
                  timeout: const Duration(seconds: 5),
                );
                _remember(cacheKey, bytes);
                if (titleCacheKey.isNotEmpty) _remember(titleCacheKey, bytes);
                if (diskFile != null) {
                  unawaited(_writeDisk(diskFile, bytes));
                }
                if (titleCacheKey.isNotEmpty && titleCacheKey != cacheKey) {
                  final titleDisk = await _diskFileFor(titleCacheKey);
                  if (titleDisk != null) {
                    unawaited(_writeDisk(titleDisk, bytes));
                  }
                }
                return bytes;
              } catch (e) {
                lastError = e;
              }
            }
          }
        } catch (e) {
          lastError = e;
        }
      }

      // 4. If title fallback was unavailable or failed, try remaining URL attempts
      if (rawUrl.isNotEmpty && !_isKnownDeadPosterHost(rawUrl)) {
        final attempts = _attemptsFor(rawUrl);
        for (int i = 2; i < attempts.length; i++) {
          final attempt = attempts[i];
          try {
            final bytes = await _fetch(
              attempt.$1,
              attempt.$2,
              timeout: const Duration(seconds: 6),
            );
            _remember(cacheKey, bytes);
            if (diskFile != null) {
              unawaited(_writeDisk(diskFile, bytes));
            }
            return bytes;
          } catch (e) {
            lastError = e;
          }
        }
      }

      throw Exception('海报加载失败：${lastError ?? cacheKey}');
    } finally {
      _release();
    }
  }

  static Future<String> _resolveDoubanCoverUrlByTitle(String cleanTitle) async {
    final cached = _titleCoverUrlCache[cleanTitle];
    if (cached != null) return cached;

    HttpClientRequest? req;
    try {
      final encoded = Uri.encodeQueryComponent(cleanTitle);
      final uri = Uri.parse(
        'https://movie.douban.com/j/subject_suggest?q=$encoded',
      );
      req = await _client.getUrl(uri).timeout(const Duration(seconds: 3));
      req.headers.set('User-Agent', _desktopSafariUa);
      req.headers.set('Referer', 'https://movie.douban.com/');
      req.headers.set('Accept', 'application/json, text/plain, */*');
      final resp = await req.close().timeout(const Duration(seconds: 3));
      if (resp.statusCode >= 200 && resp.statusCode < 300) {
        final body = await utf8.decoder
            .bind(resp)
            .join()
            .timeout(const Duration(seconds: 3));
        final decoded = jsonDecode(body);
        if (decoded is List && decoded.isNotEmpty) {
          for (final item in decoded) {
            if (item is Map) {
              var img = (item['img'] ?? '').toString().trim();
              if (img.startsWith('http')) {
                img = img
                    .replaceFirst('img9.doubanio.com', 'img3.doubanio.com')
                    .replaceFirst('/s_ratio_poster/', '/m_ratio_poster/');
                _titleCoverUrlCache[cleanTitle] = img;
                return img;
              }
            }
          }
        }
      } else {
        await resp.drain<void>();
      }
    } catch (_) {
      try {
        req?.abort();
      } catch (_) {}
    }
    return '';
  }

  static Future<void> _acquire() async {
    if (_active < _maxConcurrent) {
      _active++;
      return;
    }
    final waiter = Completer<void>();
    _waiters.add(waiter);
    await waiter.future;
    _active++;
  }

  static void _release() {
    _active--;
    if (_waiters.isNotEmpty) {
      // LIFO: prioritize the most recently requested visible posters on screen
      _waiters.removeLast().complete();
    }
  }

  static void _remember(String key, Uint8List bytes) {
    if (key.isEmpty) return;
    final old = _memory.remove(key);
    if (old != null) _memoryBytes -= old.length;
    _memory[key] = bytes;
    _memoryBytes += bytes.length;
    while (_memoryBytes > _maxMemoryBytes && _memory.isNotEmpty) {
      final oldestKey = _memory.keys.first;
      final removed = _memory.remove(oldestKey);
      if (removed != null) _memoryBytes -= removed.length;
    }
  }

  /// Builds an ordered list of `(url, headers)` attempts for one poster.
  static List<(String, Map<String, String>)> _attemptsFor(String raw) {
    var url = raw;
    final embedded = <String, String>{};
    final marker = RegExp(r'@(Referer|User-Agent|referer|user-agent)=');
    final matches = marker.allMatches(raw).toList();
    if (matches.isNotEmpty) {
      url = raw.substring(0, matches.first.start);
      for (int i = 0; i < matches.length; i++) {
        final m = matches[i];
        final end = i + 1 < matches.length ? matches[i + 1].start : raw.length;
        final name =
            m.group(1)!.toLowerCase() == 'referer' ? 'Referer' : 'User-Agent';
        final value = raw.substring(m.end, end).trim();
        if (value.isNotEmpty && !value.contains('\n')) embedded[name] = value;
      }
    }
    url = url.trim();
    if (url.startsWith('//')) url = 'https:$url';
    final uri = Uri.tryParse(url);
    if (uri == null ||
        !(uri.scheme == 'http' || uri.scheme == 'https') ||
        uri.host.isEmpty) {
      return const [];
    }
    final host = uri.host.toLowerCase();
    final attempts = <(String, Map<String, String>)>[];
    final seen = <String>{};
    void add(String u, Map<String, String> h) {
      final sig = '$u|${h['User-Agent']}|${h['Referer']}';
      if (seen.add(sig)) attempts.add((u, h));
    }

    if (host.endsWith('doubanio.com')) {
      // `img9.doubanio.com` frequently returns an HTML `<script>` anti-bot challenge;
      // always prioritize `img3`, `img1`, `img2` domestic nodes first.
      final nodes = <String>[
        if (host != 'img9.doubanio.com') host,
        'img3.doubanio.com',
        'img1.doubanio.com',
        'img2.doubanio.com',
        if (host == 'img9.doubanio.com') host,
      ];
      for (final node in nodes) {
        final swapped = uri.replace(host: node, scheme: 'https').toString();
        add(swapped, const {
          'User-Agent': _mobileSafariUa,
          'Referer': 'https://m.douban.com/',
          'Accept': 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
        });
      }
      return attempts;
    }
    if (host.endsWith('hdslb.com') ||
        host.endsWith('bilibili.com') ||
        host.endsWith('biliimg.com')) {
      add(uri.replace(scheme: 'https').toString(), const {
        'User-Agent': _desktopSafariUa,
        'Referer': 'https://www.bilibili.com/',
      });
    }
    const accept = 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8';
    final origin =
        '${uri.scheme}://${uri.host}${uri.hasPort ? ':${uri.port}' : ''}/';
    if (embedded.isNotEmpty) {
      add(url, {
        'User-Agent': embedded['User-Agent'] ?? _tvBoxUa,
        if (embedded['Referer'] != null) 'Referer': embedded['Referer']!,
        'Accept': accept,
      });
    }
    add(url, {'User-Agent': _mobileSafariUa, 'Referer': origin, 'Accept': accept});
    add(url, const {'User-Agent': _tvBoxUa, 'Accept': accept});
    add(url, const {'User-Agent': _desktopSafariUa, 'Accept': accept});
    final altScheme = uri.scheme == 'https' ? 'http' : 'https';
    add(uri.replace(scheme: altScheme).toString(), const {
      'User-Agent': _tvBoxUa,
      'Accept': accept,
    });
    return attempts;
  }

  static Future<Uint8List> _fetch(
    String url,
    Map<String, String> headers, {
    Duration timeout = const Duration(seconds: 6),
  }) async {
    HttpClientRequest? request;
    try {
      request = await _client.getUrl(Uri.parse(url)).timeout(timeout);
      request.followRedirects = true;
      request.maxRedirects = 6;
      headers.forEach((k, v) => request!.headers.set(k, v));
      final response = await request.close().timeout(timeout);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        await response.drain<void>();
        throw HttpException(
          'HTTP ${response.statusCode}',
          uri: Uri.tryParse(url),
        );
      }
      final builder = BytesBuilder(copy: false);
      await for (final chunk in response.timeout(timeout)) {
        builder.add(chunk);
        if (builder.length > _maxImageBytes) {
          throw const HttpException('图片超过大小上限');
        }
      }
      final bytes = builder.takeBytes();
      if (!_looksLikeImage(bytes)) {
        throw const FormatException('服务器返回的不是图片');
      }
      return bytes;
    } catch (e) {
      try {
        request?.abort();
      } catch (_) {}
      rethrow;
    }
  }

  static bool _looksLikeImage(Uint8List b) {
    if (b.length < 12) return false;
    if (b[0] == 0xFF && b[1] == 0xD8) return true; // JPEG
    if (b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
      return true; // PNG
    }
    if (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46) return true; // GIF
    if (b[0] == 0x52 &&
        b[1] == 0x49 &&
        b[2] == 0x46 &&
        b[3] == 0x46 &&
        b[8] == 0x57 &&
        b[9] == 0x45 &&
        b[10] == 0x42 &&
        b[11] == 0x50) {
      return true; // WEBP
    }
    if (b[0] == 0x42 && b[1] == 0x4D) return true; // BMP
    if (b[4] == 0x66 && b[5] == 0x74 && b[6] == 0x79 && b[7] == 0x70) {
      return true; // AVIF/HEIC
    }
    return false;
  }

  static Future<File?> _diskFileFor(String key) async {
    if (!_diskReady) {
      _diskReady = true;
      try {
        final dir = Directory(
          '${Directory.systemTemp.path}${Platform.pathSeparator}marco_poster_cache',
        );
        if (!await dir.exists()) await dir.create(recursive: true);
        _diskDir = dir;
      } catch (_) {
        _diskDir = null;
      }
    }
    final dir = _diskDir;
    if (dir == null) return null;
    return File('${dir.path}${Platform.pathSeparator}${_fnv1a64(key)}.img');
  }

  static Future<void> _writeDisk(File file, Uint8List bytes) async {
    try {
      final tmp = File('${file.path}.tmp');
      await tmp.writeAsBytes(bytes, flush: true);
      await tmp.rename(file.path);
    } catch (_) {}
  }

  static String _fnv1a64(String input) {
    int h1 = 0x811C9DC5;
    int h2 = 0x050C5D1F;
    for (final unit in input.codeUnits) {
      h1 = ((h1 ^ unit) * 0x01000193) & 0xFFFFFFFF;
      h2 = ((h2 ^ (unit + 31)) * 0x01000193) & 0xFFFFFFFF;
    }
    return '${h1.toRadixString(16).padLeft(8, '0')}${h2.toRadixString(16).padLeft(8, '0')}';
  }
}

/// [ImageProvider] backed by [PosterLoader] for direct provider consumers.
@immutable
class MarcoPosterProvider extends ImageProvider<MarcoPosterProvider> {
  final String url;
  final String title;
  const MarcoPosterProvider(this.url, {this.title = ''});

  @override
  Future<MarcoPosterProvider> obtainKey(ImageConfiguration configuration) {
    return SynchronousFuture<MarcoPosterProvider>(this);
  }

  @override
  ImageStreamCompleter loadImage(
    MarcoPosterProvider key,
    ImageDecoderCallback decode,
  ) {
    return MultiFrameImageStreamCompleter(
      codec: _loadCodec(key, decode),
      scale: 1.0,
      debugLabel: key.url,
    );
  }

  Future<ui.Codec> _loadCodec(
    MarcoPosterProvider key,
    ImageDecoderCallback decode,
  ) async {
    try {
      final bytes = await PosterLoader.load(key.url, title: key.title);
      final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
      return decode(buffer);
    } catch (e) {
      scheduleMicrotask(() => PaintingBinding.instance.imageCache.evict(key));
      rethrow;
    }
  }

  @override
  bool operator ==(Object other) =>
      other is MarcoPosterProvider &&
      other.url == url &&
      other.title == title;

  @override
  int get hashCode => Object.hash(url, title);

  @override
  String toString() => 'MarcoPosterProvider("$url", title: "$title")';
}

/// Stateful poster widget that loads verified image bytes via [PosterLoader],
/// automatically retries transient network failures, falls back to domestic
/// Douban cover resolution by [title], and never caches failed image states.
class PosterImage extends StatefulWidget {
  final String url;
  final String title;
  final BoxFit fit;
  final Widget placeholder;
  final bool fadeIn;

  const PosterImage({
    super.key,
    required this.url,
    required this.placeholder,
    this.title = '',
    this.fit = BoxFit.cover,
    this.fadeIn = true,
  });

  @override
  State<PosterImage> createState() => _PosterImageState();
}

class _PosterImageState extends State<PosterImage> {
  Uint8List? _bytes;
  bool _loadedSync = false;
  int _retryCount = 0;
  Timer? _retryTimer;

  @override
  void initState() {
    super.initState();
    _initLoad();
  }

  @override
  void didUpdateWidget(covariant PosterImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url.trim() != widget.url.trim() ||
        oldWidget.title.trim() != widget.title.trim()) {
      _retryTimer?.cancel();
      _retryCount = 0;
      _initLoad();
    }
  }

  @override
  void dispose() {
    _retryTimer?.cancel();
    super.dispose();
  }

  void _initLoad() {
    final immediate = PosterLoader.peekMemory(widget.url, title: widget.title);
    if (immediate != null) {
      _bytes = immediate;
      _loadedSync = true;
      return;
    }
    _bytes = null;
    _loadedSync = false;
    _fetchBytes();
  }

  Future<void> _fetchBytes() async {
    if (widget.url.trim().isEmpty && widget.title.trim().isEmpty) return;
    final requestedUrl = widget.url.trim();
    final requestedTitle = widget.title.trim();
    try {
      final data = await PosterLoader.load(
        requestedUrl,
        title: requestedTitle,
      );
      if (!mounted) return;
      if (widget.url.trim() != requestedUrl ||
          widget.title.trim() != requestedTitle) {
        return;
      }
      setState(() {
        _bytes = data;
      });
    } catch (_) {
      if (!mounted) return;
      if (_retryCount < 2) {
        _retryCount++;
        final delayMs = _retryCount == 1 ? 1200 : 3200;
        _retryTimer?.cancel();
        _retryTimer = Timer(Duration(milliseconds: delayMs), () {
          if (mounted) _fetchBytes();
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final bytes = _bytes;
    if (bytes == null) {
      return widget.placeholder;
    }
    return LayoutBuilder(
      builder: (context, constraints) {
        final dpr = MediaQuery.maybeDevicePixelRatioOf(context) ?? 2.0;
        int? cacheWidth;
        if (constraints.maxWidth.isFinite && constraints.maxWidth > 0) {
          final px = (constraints.maxWidth * dpr).ceil();
          cacheWidth = ((px + 159) ~/ 160) * 160;
        }
        final ImageProvider provider = ResizeImage.resizeIfNeeded(
          cacheWidth,
          null,
          MemoryImage(bytes),
        );
        final img = Image(
          image: provider,
          fit: widget.fit,
          width: constraints.maxWidth.isFinite ? constraints.maxWidth : null,
          height: constraints.maxHeight.isFinite ? constraints.maxHeight : null,
          gaplessPlayback: true,
          filterQuality: FilterQuality.medium,
          errorBuilder: (_, __, ___) => widget.placeholder,
        );
        if (_loadedSync || !widget.fadeIn) {
          return img;
        }
        return TweenAnimationBuilder<double>(
          tween: Tween<double>(begin: 0.0, end: 1.0),
          duration: const Duration(milliseconds: 260),
          curve: Curves.easeOutCubic,
          builder: (context, opacity, child) => Stack(
            fit: StackFit.passthrough,
            children: [
              if (opacity < 0.99) Positioned.fill(child: widget.placeholder),
              Opacity(opacity: opacity, child: child),
            ],
          ),
          child: img,
        );
      },
    );
  }
}
