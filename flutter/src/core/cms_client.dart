import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:http/http.dart' as http;
import 'package:http/io_client.dart';
import 'cms_parser.dart';
import 'csp_bridge.dart';
import 'models.dart';
/// HTTP CMS client with TVBox `okhttp/3.15` User-Agent parity, tolerant TLS for third-party
/// CMS hosts, multi-depot (`urls`/`storeHouse`) resolution, and bounded concurrent search.
class CmsClient {
  static const int maxResponseBytes = 6 * 1024 * 1024;
  static const int defaultSearchConcurrency = 6;
  static const String tvBoxUserAgent = 'okhttp/3.15';
  static const String safariUserAgent =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.15';
  final http.Client _http;
  final Duration timeout;
  CmsClient({
    http.Client? client,
    this.timeout = const Duration(seconds: 15),
  }) : _http = client ?? _createDefaultClient();
  static http.Client _createDefaultClient() {
    final ioHttp = HttpClient()
      ..connectionTimeout = const Duration(seconds: 12)
      ..badCertificateCallback = (_, __, ___) => true;
    return IOClient(ioHttp);
  }
  Map<String, String> _buildHeaders(
    Map<String, String> custom, {
    String defaultUa = tvBoxUserAgent,
  }) {
    final headers = <String, String>{
      'User-Agent': defaultUa,
      'Accept': 'application/json, text/plain, */*',
    };
    custom.forEach((k, v) {
      if (k.toLowerCase() == 'user-agent') {
        headers['User-Agent'] = v;
      } else {
        headers[k] = v;
      }
    });
    return headers;
  }
  Future<Uint8List> _getBytes(
    String url, {
    Map<String, String> headers = const {},
    Duration? requestTimeout,
    String defaultUa = tvBoxUserAgent,
  }) async {
    final normalized = CmsParser.normalizeUrl(url);
    final uri = Uri.parse(normalized);
    final response = await _http
        .get(uri, headers: _buildHeaders(headers, defaultUa: defaultUa))
        .timeout(requestTimeout ?? timeout);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw Exception('HTTP ${response.statusCode}');
    }
    if (response.bodyBytes.length > maxResponseBytes) {
      throw Exception('响应体超过大小上限（6 MB）');
    }
    return response.bodyBytes;
  }
  Future<String> _getText(
    String url, {
    Map<String, String> headers = const {},
    Duration? requestTimeout,
  }) async {
    final bytes = await _getBytes(
      url,
      headers: headers,
      requestTimeout: requestTimeout,
      defaultUa: tvBoxUserAgent,
    );
    return utf8.decode(bytes, allowMalformed: true);
  }
  Uri _appendQuery(
    String baseUrl,
    Map<String, String> params, {
    String ext = '',
  }) {
    final normalized = CmsParser.normalizeUrl(baseUrl);
    final base = Uri.parse(normalized);
    final merged = <String, String>{...base.queryParameters};
    if (ext.isNotEmpty && !merged.containsKey('extend')) {
      merged['extend'] = ext;
    }
    merged.addAll(params);
    return base.replace(queryParameters: merged);
  }
  /// Fetches and unpacks a remote TVBox / Marco Movie config (supporting `okhttp/3.15` UA,
  /// Safari UA fallback, `**`+Base64 steganography, `$#...#$` AES-128-CBC, and Base64).
  Future<String> fetchRawConfig(String url) async {
    final normalized = CmsParser.extractAndNormalizeUrl(url);
    if (normalized.isEmpty) {
      throw const FormatException('请输入有效的 HTTP(S) 配置地址');
    }
    Exception? firstError;
    for (final ua in [tvBoxUserAgent, safariUserAgent]) {
      try {
        final bytes = await _getBytes(normalized, defaultUa: ua);
        final unpacked = CmsParser.decodeConfigDocument(bytes);
        final trimmed = unpacked.trimLeft();
        if (trimmed.startsWith('<!DOCTYPE') ||
            trimmed.toLowerCase().startsWith('<html')) {
          firstError = const FormatException('服务器返回了网页而非 JSON 配置');
          continue;
        }
        return unpacked;
      } catch (e) {
        firstError = e is Exception ? e : Exception(e.toString());
      }
    }
    throw firstError ?? Exception('无法连接配置地址');
  }
  /// Resolves either a remote URL, multi-depot URL, or pasted JSON/Base64 config into
  /// a ready-to-use `(rawConfig, origin, activeDepotUrl, parsedConfig)` tuple.
  Future<({
    String rawConfig,
    String origin,
    String activeDepotName,
    List<CmsDepot> depots,
    CmsConfig config,
  })> resolveConfigInput({
    String urlInput = '',
    String rawTextInput = '',
  }) async {
    var trimmedText = rawTextInput.trim();
    var trimmedUrl = urlInput.trim();
    // If user pasted JSON into the URL field or a URL into the JSON field, handle gracefully
    if (trimmedText.isEmpty &&
        (trimmedUrl.startsWith('{') || trimmedUrl.startsWith('['))) {
      trimmedText = trimmedUrl;
      trimmedUrl = '';
    } else if (trimmedUrl.isEmpty &&
        !trimmedText.startsWith('{') &&
        !trimmedText.startsWith('[') &&
        (trimmedText.contains('http://') ||
            trimmedText.contains('https://') ||
            (trimmedText.contains('.') && !trimmedText.contains('\n')))) {
      trimmedUrl = trimmedText;
      trimmedText = '';
    }
    String origin = '';
    String rawConfig = '';
    if (trimmedText.isNotEmpty) {
      rawConfig = CmsParser.unpackConfigText(trimmedText);
      origin = CmsParser.extractAndNormalizeUrl(trimmedUrl);
    } else {
      origin = CmsParser.extractAndNormalizeUrl(trimmedUrl);
      if (origin.isEmpty) {
        throw const FormatException('请输入配置地址 (HTTP/HTTPS) 或粘贴配置内容');
      }
      rawConfig = await fetchRawConfig(origin);
    }
    final initial = CmsParser.parseConfig(rawConfig, origin: origin);
    if (!initial.isMultiDepotOnly) {
      return (
        rawConfig: rawConfig,
        origin: origin,
        activeDepotName: '',
        depots: initial.depots,
        config: initial,
      );
    }
    // Multi-depot config (`urls` / `storeHouse`): probe depots in order, preferring one with HTTP sites
    ({
      String rawConfig,
      String origin,
      String activeDepotName,
      List<CmsDepot> depots,
      CmsConfig config,
    })? fallbackResolved;
    Exception? lastError;
    final maxProbe = initial.depots.length.clamp(1, 8);
    for (int i = 0; i < maxProbe; i++) {
      final depot = initial.depots[i];
      try {
        final subRaw = await fetchRawConfig(depot.url);
        final subParsed = CmsParser.parseConfig(subRaw, origin: depot.url);
        final merged = CmsConfig(
          sites: subParsed.sites,
          lives: subParsed.lives,
          depots: initial.depots,
          spider: subParsed.spider,
        );
        final candidate = (
          rawConfig: subRaw,
          origin: depot.url,
          activeDepotName: depot.name,
          depots: initial.depots,
          config: merged,
        );
        if (merged.supportedSites.isNotEmpty || merged.lives.isNotEmpty) {
          return candidate;
        }
        fallbackResolved ??= candidate;
      } catch (e) {
        lastError = e is Exception ? e : Exception(e.toString());
      }
    }
    if (fallbackResolved != null) {
      return fallbackResolved;
    }
    throw lastError ?? const FormatException('多仓列表中的所有子仓库均无法读取');
  }
  Future<(List<CmsCategory>, CmsListing)> fetchHome(CmsSite site) async {
    if (CspBridge.canHandleSite(site)) {
      return CspBridge.fetchHome(site, _getText);
    }
    List<CmsCategory> categories = const [];
    CmsListing listing = const CmsListing();
    // Step 1: Query `ac=list` first (standard MacCMS endpoint for categories)
    try {
      final listRaw = await _getText(
        _appendQuery(site.api, {'ac': 'list'}, ext: site.ext).toString(),
        headers: site.headers,
      );
      categories = CmsParser.parseCategories(listRaw);
      listing = CmsParser.parseListing(listRaw);
    } catch (_) {}
    // Step 2: Query `ac=detail` or `ac=videolist` for full video cards with posters
    if (listing.videos.isEmpty ||
        listing.videos.every((v) => v.poster.isEmpty)) {
      try {
        final detailRaw = await _getText(
          _appendQuery(site.api, {'ac': 'detail'}, ext: site.ext).toString(),
          headers: site.headers,
        );
        if (categories.isEmpty) {
          categories = CmsParser.parseCategories(detailRaw);
        }
        final detailListing = CmsParser.parseListing(detailRaw);
        if (detailListing.videos.isNotEmpty) {
          listing = detailListing;
        }
      } catch (_) {
        if (categories.isEmpty && listing.videos.isEmpty) rethrow;
      }
    }
    if (listing.videos.isEmpty && categories.isNotEmpty) {
      listing = await fetchCategory(site, categories.first.id, 1);
    }
    return (categories, listing);
  }
  Future<CmsListing> fetchCategory(
    CmsSite site,
    String categoryId,
    int page,
  ) async {
    if (CspBridge.canHandleSite(site)) {
      return CspBridge.fetchCategory(site, categoryId, page, _getText);
    }
    final params = <String, String>{
      'ac': 'detail',
      'pg': page.toString(),
    };
    if (categoryId.isNotEmpty) {
      params['t'] = categoryId;
    }
    final raw = await _getText(
      _appendQuery(site.api, params, ext: site.ext).toString(),
      headers: site.headers,
    );
    return CmsParser.parseListing(raw);
  }
  Future<CmsListing> fetchSearch(
    CmsSite site,
    String keyword, {
    int page = 1,
  }) async {
    if (CspBridge.canHandleSite(site)) {
      return CspBridge.fetchSearch(site, keyword, page, _getText);
    }
    final raw = await _getText(
      _appendQuery(
        site.api,
        {
          'ac': 'detail',
          'wd': keyword.trim(),
          'pg': page.toString(),
        },
        ext: site.ext,
      ).toString(),
      headers: site.headers,
    );
    return CmsParser.parseListing(raw);
  }
  Future<CmsVideo> fetchDetail(CmsSite site, CmsVideo fallback) async {
    if (CspBridge.canHandleSite(site)) {
      return CspBridge.fetchDetail(site, fallback, _getText);
    }
    if (fallback.lines.isNotEmpty) return fallback;
    try {
      final raw = await _getText(
        _appendQuery(
          site.api,
          {
            'ac': 'detail',
            'ids': fallback.id,
          },
          ext: site.ext,
        ).toString(),
        headers: site.headers,
      );
      final listing = CmsParser.parseListing(raw);
      if (listing.videos.isNotEmpty) {
        final detailed = listing.videos.first;
        if (detailed.lines.isNotEmpty) {
          return CmsVideo(
            id: detailed.id.isEmpty ? fallback.id : detailed.id,
            title: detailed.title.isEmpty ? fallback.title : detailed.title,
            poster: CmsParser.isBrokenPosterUrl(detailed.poster)
                ? fallback.poster
                : detailed.poster,
            remarks: detailed.remarks.isEmpty ? fallback.remarks : detailed.remarks,
            year: detailed.year.isEmpty ? fallback.year : detailed.year,
            area: detailed.area.isEmpty ? fallback.area : detailed.area,
            actor: detailed.actor.isEmpty ? fallback.actor : detailed.actor,
            description: detailed.description.isEmpty
                ? fallback.description
                : detailed.description,
            tag: detailed.tag,
            lines: detailed.lines,
          );
        }
      }
    } catch (_) {}
    if (fallback.title.trim().isNotEmpty) {
      return CspBridge.resolveCrossSourceVideo(
        fallback,
        fallback.title.trim(),
        _getText,
      );
    }
    return fallback;
  }
  Future<CmsEpisode> resolveEpisodeForPlayback(
    CmsSite site,
    CmsEpisode episode,
  ) {
    return CspBridge.resolveEpisodeForPlayback(site, episode, _getText);
  }
  Future<List<LiveGroup>> fetchLiveGroups(LiveSource source) async {
    final raw = await _getText(source.url, headers: source.headers);
    return CmsParser.parseLivePlaylist(raw);
  }
  Stream<(CmsSite, List<CmsVideo>)> searchAcrossSites(
    List<CmsSite> sites,
    String keyword, {
    int maxConcurrency = defaultSearchConcurrency,
  }) {
    late final StreamController<(CmsSite, List<CmsVideo>)> controller;
    bool cancelled = false;
    controller = StreamController<(CmsSite, List<CmsVideo>)>(
      onCancel: () {
        cancelled = true;
      },
    );
    final seenBackends = <String>{};
    final runnableSites = <CmsSite>[];
    for (final s in sites) {
      if (!s.supported || !s.searchable) continue;
      if (CspBridge.canHandleSite(s)) {
        if (CspBridge.isDoubanSite(s) ||
            CspBridge.isMyDriveSite(s) ||
            CspBridge.isHuyaSite(s) ||
            CspBridge.isDouyuSite(s)) {
          continue;
        }
        final sig = CspBridge.isBiliSite(s)
            ? 'bili'
            : CspBridge.backendsForSite(s).first.api;
        if (!seenBackends.add(sig)) continue;
      } else {
        if (!seenBackends.add(s.api)) continue;
      }
      runnableSites.add(s);
    }
    if (runnableSites.isEmpty || keyword.trim().isEmpty) {
      controller.close();
      return controller.stream;
    }
    final concurrency = maxConcurrency.clamp(1, 12);
    int nextIndex = 0;
    int inFlight = 0;
    void pump() {
      if (cancelled || controller.isClosed) return;
      while (!cancelled &&
          !controller.isClosed &&
          inFlight < concurrency &&
          nextIndex < runnableSites.length) {
        final site = runnableSites[nextIndex++];
        inFlight++;
        fetchSearch(site, keyword)
            .timeout(const Duration(seconds: 12))
            .then((listing) {
          if (!cancelled && !controller.isClosed && listing.videos.isNotEmpty) {
            controller.add((site, listing.videos));
          }
        }).catchError((_) {}).whenComplete(() {
          inFlight--;
          if (cancelled || controller.isClosed) return;
          if (nextIndex < runnableSites.length) {
            pump();
          } else if (inFlight <= 0 && !controller.isClosed) {
            controller.close();
          }
        });
      }
    }
    pump();
    return controller.stream;
  }
}
