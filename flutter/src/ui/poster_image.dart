import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

/// Robust cross-platform HD poster loader (Windows / macOS / iOS / iPadOS).
///
/// Key resilience & HD quality mechanisms:
/// 1. Replaces `Image.network` + `ResizeImage` error-caching pitfalls by loading
///    and verifying image magic bytes in memory before handing `MemoryImage` to
///    Flutter's `ImageCache`, so transient network failures never stick in
///    `ImageCache`.
/// 2. Upgrades Douban (`/s_ratio_poster/`, `/m_ratio_poster/`, `/spic/`, `/mpic/`)
///    and Bilibili thumbnail URLs to 1080p HD (`/l_ratio_poster/`, `/lpic/`).
/// 3. Deduplicates in-flight title cover lookups (`_inflightTitleBytes`) so
///    multi-source search results for the same drama share a single domestic
///    HD cover lookup and populate all cards simultaneously.
/// 4. Races slow overseas MacCMS poster URLs against fast domestic cover lookup
///    (`movie.douban.com`, `m.douban.com`, and `cn.bing.com` `tse*-mm.cn.bing.net`
///    domestic CDN) so new drama searches never stay blank or wait for timeouts.
class PosterLoader {
  PosterLoader._();

  static const String _mobileSafariUa =
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';
  static const String _desktopSafariUa =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15';
  static const String _tvBoxUa = 'okhttp/3.15';
  static const int _maxImageBytes = 12 * 1024 * 1024;
  static const int _maxMemoryBytes = 80 * 1024 * 1024;
  static const int _maxConcurrent = 28;

  static final HttpClient _client = HttpClient()
    ..connectionTimeout = const Duration(milliseconds: 3200)
    ..idleTimeout = const Duration(seconds: 25)
    ..maxConnectionsPerHost = 16
    ..userAgent = null
    ..autoUncompress = true
    ..badCertificateCallback = (_, __, ___) => true;

  static final LinkedHashMap<String, Uint8List> _memory =
      LinkedHashMap<String, Uint8List>();
  static int _memoryBytes = 0;
  static final Map<String, Future<Uint8List>> _inflight = {};
  static final Map<String, Future<Uint8List>> _inflightTitleBytes = {};
  static final Map<String, List<String>> _titleCoverUrlsCache = {};
  static int _active = 0;
  static final Queue<Completer<void>> _waiters = Queue<Completer<void>>();
  static Directory? _diskDir;
  static bool _diskReady = false;

  /// Human-readable summary of in-memory poster cache usage for Settings UI.
  static String get memoryCacheSizeLabel {
    final mb = _memoryBytes / (1024 * 1024);
    if (_memory.isEmpty) return '0.0 MB (0 张)';
    return '${mb.toStringAsFixed(1)} MB (${_memory.length} 张)';
  }

  /// Clears both in-memory LRU cache and on-disk temporary poster files.
  static Future<void> clearCache() async {
    _memory.clear();
    _memoryBytes = 0;
    _titleCoverUrlsCache.clear();
    try {
      final dir = _diskDir ??
          Directory(
            '${Directory.systemTemp.path}${Platform.pathSeparator}marco_poster_cache',
          );
      if (await dir.exists()) {
        await for (final entity in dir.list()) {
          if (entity is File) {
            try {
              await entity.delete();
            } catch (_) {}
          }
        }
      }
    } catch (_) {}
  }

  /// Upgrades known thumbnail poster URLs to their 1080p HD counterparts.
  static String upgradeUrlToHd(String rawUrl) {
    var u = rawUrl.trim();
    if (u.isEmpty) return '';
    if (u.startsWith('//')) u = 'https:$u';
    if (u.contains('doubanio.com')) {
      u = u
          .replaceFirst('img9.doubanio.com', 'img3.doubanio.com')
          .replaceFirst('/s_ratio_poster/', '/l_ratio_poster/')
          .replaceFirst('/m_ratio_poster/', '/l_ratio_poster/')
          .replaceFirst('/view/photo/spic/', '/view/photo/lpic/')
          .replaceFirst('/view/photo/mpic/', '/view/photo/lpic/')
          .replaceFirst('/view/subject/s/', '/view/subject/l/')
          .replaceFirst('/view/subject/m/', '/view/subject/l/');
    } else if (u.contains('hdslb.com') || u.contains('biliimg.com')) {
      final atResize = RegExp(r'@\d+w_\d+h.*$').firstMatch(u);
      if (atResize != null) {
        u = u.substring(0, atResize.start);
      }
    }
    return u;
  }

  static String _cleanTitle(String rawTitle) {
    return rawTitle
        .replaceAll(RegExp(r'[\(（\[【].*?[\)）\]】]'), '')
        .replaceAll(RegExp(r'第[0-9一二三四五六七八九十]+季'), '')
        .replaceAll(RegExp(r'\b(4K|1080[pP]|2160[pP]|HD|BD|国语|粤语|中字)\b'), '')
        .trim();
  }

  static String _cacheKeyFor(String rawUrl, String title) {
    final u = upgradeUrlToHd(rawUrl);
    if (u.isNotEmpty) return u;
    final t = _cleanTitle(title);
    return t.isEmpty ? '' : 'title:$t';
  }

  /// Synchronously peeks in-memory LRU cache so already-loaded posters render
  /// on the very first frame without any async gap.
  static Uint8List? peekMemory(String rawUrl, {String title = ''}) {
    final u = upgradeUrlToHd(rawUrl);
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

  /// Loads poster bytes, de-duplicating concurrent requests and racing slow
  /// overseas URLs against fast domestic HD cover resolution by [title].
  static Future<Uint8List> load(String raw, {String title = ''}) {
    final immediate = peekMemory(raw, title: title);
    if (immediate != null) {
      return SynchronousFuture<Uint8List>(immediate);
    }
    final upgradedUrl = upgradeUrlToHd(raw);
    final key = _cacheKeyFor(upgradedUrl, title);
    if (key.isEmpty) {
      return Future<Uint8List>.error(const FormatException('空海报地址'));
    }
    final pending = _inflight[key];
    if (pending != null) return pending;
    final future = _loadUncached(upgradedUrl, title.trim(), key)
        .whenComplete(() => _inflight.remove(key));
    _inflight[key] = future;
    return future;
  }

  /// Explicitly resolves a high-definition domestic poster by [title], used to
  /// upgrade blurry low-res CMS thumbnails on immersive detail screens.
  static Future<Uint8List> loadHdByTitle(String rawTitle) {
    final clean = _cleanTitle(rawTitle);
    if (clean.isEmpty) {
      return Future<Uint8List>.error(const FormatException('空片名'));
    }
    final hdKey = 'title_hd:$clean';
    final hit = _memory[hdKey];
    if (hit != null) return SynchronousFuture<Uint8List>(hit);
    return _resolveTitleCoverBytes(clean, cacheKey: hdKey);
  }

  static bool _isKnownDeadPosterHost(String url) {
    final lower = url.toLowerCase();
    return lower.contains('mdzypic.com') ||
        lower.contains('pic.ry-pic.com') ||
        lower.contains('macci.cc');
  }

  static bool _isFastDomesticHost(String url) {
    final lower = url.toLowerCase();
    return lower.contains('doubanio.com') ||
        lower.contains('hdslb.com') ||
        lower.contains('biliimg.com') ||
        lower.contains('bing.net') ||
        lower.contains('tuxiaobei.com') ||
        lower.contains('huya.com') ||
        lower.contains('douyucdn.cn');
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
      final inMemTitle = _memory[titleCacheKey];
      if (inMemTitle != null) {
        _remember(cacheKey, inMemTitle);
        return inMemTitle;
      }
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

    // 2. If rawUrl is empty or a known dead host, resolve directly by title
    if ((rawUrl.isEmpty || _isKnownDeadPosterHost(rawUrl)) &&
        cleanTitle.isNotEmpty) {
      final bytes = await _resolveTitleCoverBytes(
        cleanTitle,
        cacheKey: titleCacheKey,
      );
      _remember(cacheKey, bytes);
      if (diskFile != null) {
        unawaited(_writeDisk(diskFile, bytes));
      }
      return bytes;
    }

    // 3. If rawUrl is a slow/unverified third-party host and we have a title,
    // race the primary URL against a fast domestic title lookup (starting after
    // 350ms if the primary URL hasn't finished yet) so search results never wait
    // 6-12 seconds for overseas CMS image servers.
    if (rawUrl.isNotEmpty &&
        cleanTitle.isNotEmpty &&
        !_isFastDomesticHost(rawUrl)) {
      final completer = Completer<Uint8List>();
      Object? lastErr;
      int remaining = 2;

      void handleSuccess(Uint8List bytes) {
        if (!completer.isCompleted) {
          _remember(cacheKey, bytes);
          if (titleCacheKey.isNotEmpty) _remember(titleCacheKey, bytes);
          if (diskFile != null) {
            unawaited(_writeDisk(diskFile, bytes));
          }
          completer.complete(bytes);
        }
      }

      void handleError(Object e) {
        lastErr = e;
        remaining--;
        if (remaining <= 0 && !completer.isCompleted) {
          completer.completeError(
            Exception('海报加载失败：${lastErr ?? cacheKey}'),
          );
        }
      }

      // Branch A: Primary CMS URL with tight timeout
      unawaited(
        _tryPrimaryUrlAttempts(
          rawUrl,
          maxAttempts: 2,
          perAttemptTimeout: const Duration(milliseconds: 2200),
        ).then(handleSuccess, onError: handleError),
      );

      // Branch B: Fast domestic HD cover lookup by title (head-start delay 320ms)
      unawaited(
        Future<void>.delayed(const Duration(milliseconds: 320)).then((_) async {
          if (completer.isCompleted) {
            remaining--;
            return;
          }
          try {
            final bytes = await _resolveTitleCoverBytes(
              cleanTitle,
              cacheKey: titleCacheKey,
            );
            handleSuccess(bytes);
          } catch (e) {
            handleError(e);
          }
        }),
      );

      return completer.future;
    }

    // 4. Standard path for fast domestic URLs or untitled URLs
    try {
      final bytes = await _tryPrimaryUrlAttempts(
        rawUrl,
        maxAttempts: cleanTitle.isNotEmpty ? 2 : 4,
        perAttemptTimeout: const Duration(milliseconds: 3600),
      );
      _remember(cacheKey, bytes);
      if (titleCacheKey.isNotEmpty) _remember(titleCacheKey, bytes);
      if (diskFile != null) {
        unawaited(_writeDisk(diskFile, bytes));
      }
      return bytes;
    } catch (e) {
      if (cleanTitle.isNotEmpty) {
        final bytes = await _resolveTitleCoverBytes(
          cleanTitle,
          cacheKey: titleCacheKey,
        );
        _remember(cacheKey, bytes);
        if (diskFile != null) {
          unawaited(_writeDisk(diskFile, bytes));
        }
        return bytes;
      }
      rethrow;
    }
  }

  static Future<Uint8List> _tryPrimaryUrlAttempts(
    String rawUrl, {
    required int maxAttempts,
    required Duration perAttemptTimeout,
  }) async {
    await _acquire();
    try {
      final attempts = _attemptsFor(rawUrl);
      if (attempts.isEmpty) {
        throw const FormatException('无效的海报地址');
      }
      Object? lastError;
      final count =
          attempts.length < maxAttempts ? attempts.length : maxAttempts;
      for (int i = 0; i < count; i++) {
        final attempt = attempts[i];
        try {
          return await _fetch(
            attempt.$1,
            attempt.$2,
            timeout: perAttemptTimeout,
          );
        } catch (e) {
          lastError = e;
        }
      }
      throw lastError ?? const HttpException('海报请求失败');
    } finally {
      _release();
    }
  }

  /// Deduplicated title-to-bytes resolver: if 20 search result cards share the
  /// same `cleanTitle`, only one network resolution runs and all 20 callers
  /// receive the resulting HD image bytes simultaneously.
  static Future<Uint8List> _resolveTitleCoverBytes(
    String cleanTitle, {
    required String cacheKey,
  }) {
    final existing = _inflightTitleBytes[cleanTitle];
    if (existing != null) return existing;

    final future = () async {
      final urls = await _resolveCoverUrlsByTitle(cleanTitle);
      Object? lastError;
      for (final candidateUrl in urls) {
        for (final attempt in _attemptsFor(candidateUrl)) {
          try {
            final bytes = await _fetch(
              attempt.$1,
              attempt.$2,
              timeout: const Duration(milliseconds: 3500),
            );
            final tKey = 'title:$cleanTitle';
            _remember(tKey, bytes);
            if (cacheKey.isNotEmpty) _remember(cacheKey, bytes);
            final titleDisk = await _diskFileFor(tKey);
            if (titleDisk != null) {
              unawaited(_writeDisk(titleDisk, bytes));
            }
            return bytes;
          } catch (e) {
            lastError = e;
          }
        }
      }
      throw lastError ?? Exception('未找到「$cleanTitle」封面');
    }()
        .whenComplete(() => _inflightTitleBytes.remove(cleanTitle));

    _inflightTitleBytes[cleanTitle] = future;
    return future;
  }

  /// Resolves candidate HD vertical poster URLs for [cleanTitle] across:
  /// 1. Douban `movie.douban.com/j/subject_suggest` (upgraded to `l_ratio_poster`)
  /// 2. Douban `m.douban.com/rexxar/api/v2/search` (upgraded to `l_ratio_poster`)
  /// 3. Bing China `cn.bing.com/images/async` (`tse*-mm.cn.bing.net` domestic CDN)
  static Future<List<String>> _resolveCoverUrlsByTitle(
    String cleanTitle,
  ) async {
    final cached = _titleCoverUrlsCache[cleanTitle];
    if (cached != null && cached.isNotEmpty) return cached;

    final results = <String>[];
    final seen = <String>{};
    void addCandidate(String raw) {
      final hd = upgradeUrlToHd(raw);
      if (hd.startsWith('http') && seen.add(hd)) {
        results.add(hd);
      }
    }

    final encoded = Uri.encodeQueryComponent(cleanTitle);

    // 1 & 2: Query Douban Suggest and Bing China Domestic Poster CDN in parallel
    await Future.wait([
      // Source A: Douban Subject Suggest (1080p l_ratio_poster)
      () async {
        HttpClientRequest? req;
        try {
          final uri = Uri.parse(
            'https://movie.douban.com/j/subject_suggest?q=$encoded',
          );
          req = await _client
              .getUrl(uri)
              .timeout(const Duration(milliseconds: 2200));
          req.headers.set('User-Agent', _desktopSafariUa);
          req.headers.set('Referer', 'https://movie.douban.com/');
          req.headers.set('Accept', 'application/json, text/plain, */*');
          final resp =
              await req.close().timeout(const Duration(milliseconds: 2200));
          if (resp.statusCode >= 200 && resp.statusCode < 300) {
            final body = await utf8.decoder
                .bind(resp)
                .join()
                .timeout(const Duration(milliseconds: 2200));
            final decoded = jsonDecode(body);
            if (decoded is List) {
              for (final item in decoded) {
                if (item is Map) {
                  final img = (item['img'] ?? '').toString().trim();
                  if (img.startsWith('http')) {
                    addCandidate(img);
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
      }(),

      // Source B: Bing China Domestic Image CDN (`tse*-mm.cn.bing.net`)
      () async {
        HttpClientRequest? req;
        try {
          final query = Uri.encodeQueryComponent('$cleanTitle 影视海报');
          final uri = Uri.parse(
            'https://cn.bing.com/images/async?q=$query&first=1&count=6&qft=+filterui:aspect-tall',
          );
          req = await _client
              .getUrl(uri)
              .timeout(const Duration(milliseconds: 2200));
          req.headers.set('User-Agent', _desktopSafariUa);
          req.headers.set('Referer', 'https://cn.bing.com/');
          req.headers.set('Accept', 'text/html,application/xhtml+xml,*/*;q=0.8');
          final resp =
              await req.close().timeout(const Duration(milliseconds: 2200));
          if (resp.statusCode >= 200 && resp.statusCode < 300) {
            final html = await utf8.decoder
                .bind(resp)
                .join()
                .timeout(const Duration(milliseconds: 2200));
            final matches = RegExp(
              r'OIP(?:-C)?\.[A-Za-z0-9_-]+',
            ).allMatches(html);
            for (final m in matches.take(3)) {
              final oipId = m.group(0)!;
              addCandidate(
                'https://tse1-mm.cn.bing.net/th/id/$oipId?w=540&h=800&c=7&r=0&o=5&pid=1.7',
              );
            }
          } else {
            await resp.drain<void>();
          }
        } catch (_) {
          try {
            req?.abort();
          } catch (_) {}
        }
      }(),
    ]);

    // Source C: Douban Mobile Rexxar Search fallback if both above returned empty
    if (results.isEmpty) {
      HttpClientRequest? req;
      try {
        final uri = Uri.parse(
          'https://m.douban.com/rexxar/api/v2/search?q=$encoded&type=movie',
        );
        req = await _client
            .getUrl(uri)
            .timeout(const Duration(milliseconds: 2400));
        req.headers.set('User-Agent', _mobileSafariUa);
        req.headers.set('Referer', 'https://m.douban.com/');
        req.headers.set('Accept', 'application/json, text/plain, */*');
        final resp =
            await req.close().timeout(const Duration(milliseconds: 2400));
        if (resp.statusCode >= 200 && resp.statusCode < 300) {
          final body = await utf8.decoder
              .bind(resp)
              .join()
              .timeout(const Duration(milliseconds: 2400));
          final decoded = jsonDecode(body);
          final subjects = decoded is Map
              ? (decoded['subjects'] is Map
                  ? decoded['subjects']['items']
                  : decoded['items'])
              : null;
          if (subjects is List) {
            for (final entry in subjects) {
              if (entry is! Map) continue;
              final target = entry['target'] is Map ? entry['target'] : entry;
              final cover = (target['cover_url'] ??
                      (target['pic'] is Map
                          ? (target['pic']['large'] ?? target['pic']['normal'])
                          : ''))
                  .toString()
                  .trim();
              if (cover.startsWith('http')) {
                addCandidate(cover);
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
    }

    if (results.isNotEmpty) {
      _titleCoverUrlsCache[cleanTitle] = results;
    }
    return results;
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
    url = upgradeUrlToHd(url);
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
      final nodes = <String>[
        if (host != 'img9.doubanio.com') host,
        'img3.doubanio.com',
        'img1.doubanio.com',
        'img2.doubanio.com',
      ];
      for (final node in nodes) {
        final swapped = uri.replace(host: node, scheme: 'https').toString();
        add(swapped, const {
          'User-Agent': _mobileSafariUa,
          'Referer': 'https://m.douban.com/',
          'Accept': 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
        });
      }
      // Also add m_ratio_poster fallback in case l_ratio_poster is 404 on an old title
      if (url.contains('/l_ratio_poster/')) {
        final mUrl = uri
            .replace(
              host: 'img3.doubanio.com',
              scheme: 'https',
              path: uri.path.replaceFirst('/l_ratio_poster/', '/m_ratio_poster/'),
            )
            .toString();
        add(mUrl, const {
          'User-Agent': _mobileSafariUa,
          'Referer': 'https://m.douban.com/',
          'Accept': 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
        });
      }
      return attempts;
    }
    if (host.endsWith('bing.net')) {
      add(uri.replace(scheme: 'https').toString(), const {
        'User-Agent': _desktopSafariUa,
        'Referer': 'https://cn.bing.com/',
        'Accept': 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
      });
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
    Duration timeout = const Duration(seconds: 5),
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
/// HD cover resolution by [title], and upgrades low-res thumbnails when
/// [highRes] is enabled (e.g. on immersive detail hero headers).
class PosterImage extends StatefulWidget {
  final String url;
  final String title;
  final BoxFit fit;
  final Widget placeholder;
  final bool fadeIn;
  final bool highRes;

  const PosterImage({
    super.key,
    required this.url,
    required this.placeholder,
    this.title = '',
    this.fit = BoxFit.cover,
    this.fadeIn = true,
    this.highRes = false,
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
        oldWidget.title.trim() != widget.title.trim() ||
        oldWidget.highRes != widget.highRes) {
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
      if (widget.highRes &&
          widget.title.trim().isNotEmpty &&
          immediate.length < 36 * 1024) {
        _tryUpgradeToHd(widget.title.trim(), immediate.length);
      }
      return;
    }
    _bytes = null;
    _loadedSync = false;
    _fetchBytes();
  }

  Future<void> _tryUpgradeToHd(String requestedTitle, int currentBytesLen) async {
    try {
      final hdBytes = await PosterLoader.loadHdByTitle(requestedTitle);
      if (!mounted || widget.title.trim() != requestedTitle) return;
      if (hdBytes.length > currentBytesLen) {
        setState(() {
          _bytes = hdBytes;
        });
      }
    } catch (_) {}
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
      if (widget.highRes &&
          requestedTitle.isNotEmpty &&
          data.length < 36 * 1024) {
        unawaited(_tryUpgradeToHd(requestedTitle, data.length));
      }
    } catch (_) {
      if (!mounted) return;
      if (_retryCount < 2) {
        _retryCount++;
        final delayMs = _retryCount == 1 ? 900 : 2400;
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
        final dpr = (MediaQuery.maybeDevicePixelRatioOf(context) ?? 2.0)
            .clamp(2.0, 3.5);
        final isLargeSurface = widget.highRes ||
            (constraints.maxWidth.isFinite && constraints.maxWidth >= 210) ||
            (constraints.maxHeight.isFinite && constraints.maxHeight >= 260);
        int? cacheWidth;
        if (!isLargeSurface &&
            constraints.maxWidth.isFinite &&
            constraints.maxWidth > 0) {
          // Account for 2:3 vertical aspect ratio under BoxFit.cover so width
          // is never downsampled below what vertical scaling requires.
          final effectiveWidth = constraints.maxHeight.isFinite &&
                  constraints.maxHeight * 0.72 > constraints.maxWidth
              ? constraints.maxHeight * 0.72
              : constraints.maxWidth;
          final px = (effectiveWidth * dpr).ceil();
          cacheWidth = ((px + 239) ~/ 240) * 240;
        }
        final ImageProvider provider = isLargeSurface
            ? MemoryImage(bytes)
            : ResizeImage.resizeIfNeeded(
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
          filterQuality:
              isLargeSurface ? FilterQuality.high : FilterQuality.medium,
          errorBuilder: (_, __, ___) => widget.placeholder,
        );
        if (_loadedSync || !widget.fadeIn) {
          return img;
        }
        return TweenAnimationBuilder<double>(
          tween: Tween<double>(begin: 0.0, end: 1.0),
          duration: const Duration(milliseconds: 240),
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
