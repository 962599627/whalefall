import 'dart:async';
import 'dart:collection';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

/// Robust cross-platform poster loader (Windows / macOS / iOS / iPadOS).
///
/// Flutter's `Image.network` appends custom headers to the default
/// `Dart/x (dart:io)` User-Agent and rejects self-signed certificates, which
/// many CMS image CDNs (and Douban's anti-hotlink nodes) refuse. This loader
/// uses a dedicated tolerant [HttpClient], replaces the User-Agent outright,
/// honours TVBox `@Referer=` / `@User-Agent=` suffixes, rotates Douban image
/// nodes, validates image magic bytes (rejecting HTML challenge pages), and
/// keeps an in-memory LRU plus an on-disk cache.
class PosterLoader {
  PosterLoader._();

  static const String _mobileSafariUa =
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';
  static const String _desktopSafariUa =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15';
  static const String _tvBoxUa = 'okhttp/3.15';
  static const int _maxImageBytes = 10 * 1024 * 1024;
  static const int _maxMemoryBytes = 64 * 1024 * 1024;
  static const int _maxConcurrent = 8;

  static final HttpClient _client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 10)
    ..idleTimeout = const Duration(seconds: 30)
    ..maxConnectionsPerHost = 6
    ..userAgent = null
    ..autoUncompress = true
    ..badCertificateCallback = (_, __, ___) => true;

  static final LinkedHashMap<String, Uint8List> _memory =
      LinkedHashMap<String, Uint8List>();
  static int _memoryBytes = 0;
  static final Map<String, Future<Uint8List>> _inflight = {};
  static final Set<String> _failed = {};
  static int _active = 0;
  static final Queue<Completer<void>> _waiters = Queue<Completer<void>>();
  static Directory? _diskDir;
  static bool _diskReady = false;

  /// Loads poster bytes, de-duplicating concurrent requests for the same URL.
  static Future<Uint8List> load(String raw) {
    final key = raw.trim();
    final cached = _memory.remove(key);
    if (cached != null) {
      _memory[key] = cached;
      return SynchronousFuture<Uint8List>(cached);
    }
    final pending = _inflight[key];
    if (pending != null) return pending;
    final future = _loadUncached(key).whenComplete(() => _inflight.remove(key));
    _inflight[key] = future;
    return future;
  }

  static Future<Uint8List> _loadUncached(String key) async {
    final diskFile = await _diskFileFor(key);
    if (diskFile != null) {
      try {
        if (await diskFile.exists()) {
          final bytes = await diskFile.readAsBytes();
          if (_looksLikeImage(bytes)) {
            _remember(key, bytes);
            return bytes;
          }
        }
      } catch (_) {}
    }
    await _acquire();
    try {
      Object? lastError;
      for (final attempt in _attemptsFor(key)) {
        try {
          final bytes = await _fetch(attempt.$1, attempt.$2)
              .timeout(const Duration(seconds: 18));
          _remember(key, bytes);
          _failed.remove(key);
          if (diskFile != null) {
            unawaited(_writeDisk(diskFile, bytes));
          }
          return bytes;
        } catch (e) {
          lastError = e;
        }
      }
      _failed.add(key);
      throw Exception('海报加载失败：${lastError ?? key}');
    } finally {
      _release();
    }
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
      _waiters.removeFirst().complete();
    }
  }

  static void _remember(String key, Uint8List bytes) {
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
        final name = m.group(1)!.toLowerCase() == 'referer' ? 'Referer' : 'User-Agent';
        final value = raw.substring(m.end, end).trim();
        if (value.isNotEmpty && !value.contains('\n')) embedded[name] = value;
      }
    }
    url = url.trim();
    if (url.startsWith('//')) url = 'https:$url';
    final uri = Uri.tryParse(url);
    if (uri == null || !(uri.scheme == 'http' || uri.scheme == 'https') || uri.host.isEmpty) {
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
      // Douban image nodes: img9 et al. may answer with an HTML JS challenge,
      // while img1/img2/img3 serve the same path with a douban.com Referer.
      final nodes = <String>[host, 'img1.doubanio.com', 'img2.doubanio.com', 'img3.doubanio.com'];
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
    if (host.endsWith('hdslb.com') || host.endsWith('bilibili.com') || host.endsWith('biliimg.com')) {
      add(uri.replace(scheme: 'https').toString(), const {
        'User-Agent': _desktopSafariUa,
        'Referer': 'https://www.bilibili.com/',
      });
    }
    const accept = 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8';
    final origin = '${uri.scheme}://${uri.host}${uri.hasPort ? ':${uri.port}' : ''}/';
    if (embedded.isNotEmpty) {
      add(url, {
        'User-Agent': embedded['User-Agent'] ?? _tvBoxUa,
        if (embedded['Referer'] != null) 'Referer': embedded['Referer']!,
        'Accept': accept,
      });
    }
    add(url, const {'User-Agent': _tvBoxUa, 'Accept': accept});
    add(url, {'User-Agent': _mobileSafariUa, 'Referer': origin, 'Accept': accept});
    add(url, const {'User-Agent': _desktopSafariUa, 'Accept': accept});
    final altScheme = uri.scheme == 'https' ? 'http' : 'https';
    add(uri.replace(scheme: altScheme).toString(), const {
      'User-Agent': _tvBoxUa,
      'Accept': accept,
    });
    return attempts;
  }

  static Future<Uint8List> _fetch(String url, Map<String, String> headers) async {
    final request = await _client.getUrl(Uri.parse(url));
    request.followRedirects = true;
    request.maxRedirects = 6;
    headers.forEach((k, v) => request.headers.set(k, v));
    final response = await request.close();
    if (response.statusCode < 200 || response.statusCode >= 300) {
      await response.drain<void>();
      throw HttpException('HTTP ${response.statusCode}', uri: Uri.tryParse(url));
    }
    final builder = BytesBuilder(copy: false);
    await for (final chunk in response) {
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
  }

  static bool _looksLikeImage(Uint8List b) {
    if (b.length < 12) return false;
    if (b[0] == 0xFF && b[1] == 0xD8) return true; // JPEG
    if (b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return true; // PNG
    if (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46) return true; // GIF
    if (b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 &&
        b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) {
      return true; // WEBP
    }
    if (b[0] == 0x42 && b[1] == 0x4D) return true; // BMP
    if (b[4] == 0x66 && b[5] == 0x74 && b[6] == 0x79 && b[7] == 0x70) return true; // AVIF/HEIC
    return false;
  }

  static Future<File?> _diskFileFor(String key) async {
    if (!_diskReady) {
      _diskReady = true;
      try {
        final dir = Directory('${Directory.systemTemp.path}${Platform.pathSeparator}marco_poster_cache');
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
    // Two independent 32-bit FNV-1a hashes (safe on all Dart targets).
    int h1 = 0x811C9DC5;
    int h2 = 0x050C5D1F;
    for (final unit in input.codeUnits) {
      h1 = ((h1 ^ unit) * 0x01000193) & 0xFFFFFFFF;
      h2 = ((h2 ^ (unit + 31)) * 0x01000193) & 0xFFFFFFFF;
    }
    return '${h1.toRadixString(16).padLeft(8, '0')}${h2.toRadixString(16).padLeft(8, '0')}';
  }
}

/// [ImageProvider] backed by [PosterLoader] so posters participate in Flutter's image cache.
@immutable
class MarcoPosterProvider extends ImageProvider<MarcoPosterProvider> {
  final String url;
  const MarcoPosterProvider(this.url);

  @override
  Future<MarcoPosterProvider> obtainKey(ImageConfiguration configuration) {
    return SynchronousFuture<MarcoPosterProvider>(this);
  }

  @override
  ImageStreamCompleter loadImage(MarcoPosterProvider key, ImageDecoderCallback decode) {
    return MultiFrameImageStreamCompleter(
      codec: _loadCodec(key, decode),
      scale: 1.0,
      debugLabel: key.url,
    );
  }

  Future<ui.Codec> _loadCodec(MarcoPosterProvider key, ImageDecoderCallback decode) async {
    try {
      final bytes = await PosterLoader.load(key.url);
      final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
      return decode(buffer);
    } catch (e) {
      // Allow a later rebuild to retry instead of caching the failure forever.
      scheduleMicrotask(() => PaintingBinding.instance.imageCache.evict(key));
      rethrow;
    }
  }

  @override
  bool operator ==(Object other) =>
      other is MarcoPosterProvider && other.url == url;

  @override
  int get hashCode => url.hashCode;

  @override
  String toString() => 'MarcoPosterProvider("$url")';
}

/// Poster widget with a soft placeholder, smooth fade-in, and size-aware decoding.
class PosterImage extends StatelessWidget {
  final String url;
  final BoxFit fit;
  final Widget placeholder;
  final bool fadeIn;
  const PosterImage({
    super.key,
    required this.url,
    required this.placeholder,
    this.fit = BoxFit.cover,
    this.fadeIn = true,
  });

  @override
  Widget build(BuildContext context) {
    if (url.trim().isEmpty) return placeholder;
    return LayoutBuilder(
      builder: (context, constraints) {
        final dpr = MediaQuery.maybeDevicePixelRatioOf(context) ?? 2.0;
        int? cacheWidth;
        if (constraints.maxWidth.isFinite && constraints.maxWidth > 0) {
          // Bucket decode widths so the same poster at similar sizes shares one cache entry.
          final px = (constraints.maxWidth * dpr).ceil();
          cacheWidth = ((px + 159) ~/ 160) * 160;
        }
        final ImageProvider provider = ResizeImage.resizeIfNeeded(
          cacheWidth,
          null,
          MarcoPosterProvider(url.trim()),
        );
        return Image(
          image: provider,
          fit: fit,
          width: constraints.maxWidth.isFinite ? constraints.maxWidth : null,
          height: constraints.maxHeight.isFinite ? constraints.maxHeight : null,
          gaplessPlayback: true,
          filterQuality: FilterQuality.medium,
          frameBuilder: (context, child, frame, wasSynchronouslyLoaded) {
            if (wasSynchronouslyLoaded || !fadeIn) return child;
            return Stack(
              fit: StackFit.passthrough,
              children: [
                if (frame == null) Positioned.fill(child: placeholder),
                AnimatedOpacity(
                  opacity: frame == null ? 0 : 1,
                  duration: const Duration(milliseconds: 320),
                  curve: Curves.easeOutCubic,
                  child: child,
                ),
              ],
            );
          },
          errorBuilder: (_, __, ___) => placeholder,
        );
      },
    );
  }
}
