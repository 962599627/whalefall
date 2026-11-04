import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'cms_parser.dart';
import 'models.dart';

/// HTTP CMS client with timeout isolation, response size bounds, and bounded concurrent multi-site search.
class CmsClient {
  static const int maxResponseBytes = 4 * 1024 * 1024;
  static const int defaultSearchConcurrency = 6;
  final http.Client _http;
  final Duration timeout;

  CmsClient({
    http.Client? client,
    this.timeout = const Duration(seconds: 15),
  }) : _http = client ?? http.Client();

  Map<String, String> _buildHeaders(Map<String, String> custom) {
    return {
      'User-Agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
      'Accept': 'application/json, text/plain, */*',
      ...custom,
    };
  }

  Future<String> _getText(
    String url, {
    Map<String, String> headers = const {},
    Duration? requestTimeout,
  }) async {
    final uri = Uri.parse(url);
    final response = await _http
        .get(uri, headers: _buildHeaders(headers))
        .timeout(requestTimeout ?? timeout);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw Exception('HTTP ${response.statusCode}');
    }
    if (response.bodyBytes.length > maxResponseBytes) {
      throw Exception('响应体超过大小上限（4 MB）');
    }
    return utf8.decode(response.bodyBytes, allowMalformed: true);
  }

  Uri _appendQuery(String baseUrl, Map<String, String> params) {
    final base = Uri.parse(baseUrl);
    final merged = <String, String>{...base.queryParameters, ...params};
    return base.replace(queryParameters: merged);
  }

  Future<String> fetchRawConfig(String url) async {
    return _getText(url.trim());
  }

  Future<(List<CmsCategory>, CmsListing)> fetchHome(CmsSite site) async {
    final raw = await _getText(
      _appendQuery(site.api, {'ac': 'detail'}).toString(),
      headers: site.headers,
    );
    final categories = CmsParser.parseCategories(raw);
    var listing = CmsParser.parseListing(raw);
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
    final params = <String, String>{
      'ac': 'detail',
      'pg': page.toString(),
    };
    if (categoryId.isNotEmpty) {
      params['t'] = categoryId;
    }
    final raw = await _getText(
      _appendQuery(site.api, params).toString(),
      headers: site.headers,
    );
    return CmsParser.parseListing(raw);
  }

  Future<CmsListing> fetchSearch(
    CmsSite site,
    String keyword, {
    int page = 1,
  }) async {
    final raw = await _getText(
      _appendQuery(site.api, {
        'ac': 'detail',
        'wd': keyword.trim(),
        'pg': page.toString(),
      }).toString(),
      headers: site.headers,
    );
    return CmsParser.parseListing(raw);
  }

  Future<CmsVideo> fetchDetail(CmsSite site, CmsVideo fallback) async {
    if (fallback.lines.isNotEmpty) return fallback;
    final raw = await _getText(
      _appendQuery(site.api, {
        'ac': 'detail',
        'ids': fallback.id,
      }).toString(),
      headers: site.headers,
    );
    final listing = CmsParser.parseListing(raw);
    if (listing.videos.isEmpty) return fallback;
    final detailed = listing.videos.first;
    return CmsVideo(
      id: detailed.id.isEmpty ? fallback.id : detailed.id,
      title: detailed.title.isEmpty ? fallback.title : detailed.title,
      poster: detailed.poster.isEmpty ? fallback.poster : detailed.poster,
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
    final runnableSites =
        sites.where((s) => s.supported && s.searchable).toList(growable: false);
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
