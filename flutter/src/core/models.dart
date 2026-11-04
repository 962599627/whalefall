/// Core domain models shared across Windows, macOS, iOS, iPadOS, and Android backup schemas.
library;

class CmsSite {
  final String key;
  final String name;
  final int type;
  final String api;
  final String playUrl;
  final bool searchable;
  final Map<String, String> headers;

  const CmsSite({
    required this.key,
    required this.name,
    required this.type,
    required this.api,
    this.playUrl = '',
    this.searchable = true,
    this.headers = const {},
  });

  bool get canBrowse =>
      (type == 1 || type == 0) &&
      (api.startsWith('http://') || api.startsWith('https://'));

  bool get requiresPluginRuntime =>
      type == 3 ||
      api.startsWith('csp_') ||
      api.toLowerCase().endsWith('.js');

  bool get supported => canBrowse && !requiresPluginRuntime;
}

class LiveSource {
  final String name;
  final String url;
  final Map<String, String> headers;

  const LiveSource({
    required this.name,
    required this.url,
    this.headers = const {},
  });
}

class CmsConfig {
  final List<CmsSite> sites;
  final List<LiveSource> lives;

  const CmsConfig({
    this.sites = const [],
    this.lives = const [],
  });

  List<CmsSite> get supportedSites =>
      sites.where((s) => s.supported).toList(growable: false);
}

class CmsCategory {
  final String id;
  final String name;

  const CmsCategory({required this.id, required this.name});
}

class CmsEpisode {
  final String name;
  final String url;
  final Map<String, String> headers;

  const CmsEpisode({
    required this.name,
    required this.url,
    this.headers = const {},
  });

  bool get isDirectPlayable {
    final u = url.trim().toLowerCase();
    if (!u.startsWith('http://') &&
        !u.startsWith('https://') &&
        !u.startsWith('rtmp://') &&
        !u.startsWith('rtsp://')) {
      return false;
    }
    final pathOnly = u.split('?').first.split('#').first;
    if (pathOnly.endsWith('.html') || pathOnly.endsWith('.htm')) {
      return false;
    }
    return true;
  }
}

class CmsLine {
  final String name;
  final List<CmsEpisode> episodes;

  const CmsLine({required this.name, required this.episodes});
}

class CmsVideo {
  final String id;
  final String title;
  final String poster;
  final String remarks;
  final String year;
  final String area;
  final String actor;
  final String description;
  final String tag;
  final List<CmsLine> lines;

  const CmsVideo({
    required this.id,
    required this.title,
    this.poster = '',
    this.remarks = '',
    this.year = '',
    this.area = '',
    this.actor = '',
    this.description = '',
    this.tag = '',
    this.lines = const [],
  });
}

class CmsListing {
  final int page;
  final int pageCount;
  final List<CmsVideo> videos;

  const CmsListing({
    this.page = 1,
    this.pageCount = 1,
    this.videos = const [],
  });
}

class LiveChannel {
  final String name;
  final String group;
  final List<String> urls;

  const LiveChannel({
    required this.name,
    required this.group,
    required this.urls,
  });
}

class LiveGroup {
  final String name;
  final List<LiveChannel> channels;

  const LiveGroup({required this.name, required this.channels});
}

class WatchHistoryEntry {
  final String site;
  final String id;
  final String title;
  final String poster;
  final String line;
  final String episode;
  final int index;
  final int position;
  final int duration;
  final int updated;

  const WatchHistoryEntry({
    required this.site,
    required this.id,
    required this.title,
    this.poster = '',
    this.line = '',
    this.episode = '',
    this.index = 0,
    this.position = 0,
    this.duration = 0,
    this.updated = 0,
  });

  String get key => '$site\n$id';

  bool get complete =>
      duration >= 60000 &&
      position >= duration - (duration * 0.05).round().clamp(15000, 120000);

  int get resumePosition => complete ? 0 : (position < 0 ? 0 : position);

  Map<String, dynamic> toJson() => {
        'site': site,
        'id': id,
        'title': title,
        'poster': poster,
        'line': line,
        'episode': episode,
        'index': index,
        'position': position < 0 ? 0 : position,
        'duration': duration < 0 ? 0 : duration,
        'updated': updated,
      };

  factory WatchHistoryEntry.fromJson(Map<String, dynamic> json) {
    final pos = (json['position'] as num?)?.toInt() ?? 0;
    final dur = (json['duration'] as num?)?.toInt() ?? 0;
    return WatchHistoryEntry(
      site: (json['site'] ?? '').toString(),
      id: (json['id'] ?? '').toString(),
      title: (json['title'] ?? '').toString(),
      poster: (json['poster'] ?? '').toString(),
      line: (json['line'] ?? '').toString(),
      episode: (json['episode'] ?? '').toString(),
      index: (json['index'] as num?)?.toInt() ?? 0,
      position: pos < 0 ? 0 : pos,
      duration: dur < 0 ? 0 : dur,
      updated: (json['updated'] as num?)?.toInt() ?? 0,
    );
  }
}

class FavoriteEntry {
  final String site;
  final String id;
  final String title;
  final String poster;
  final String remarks;
  final int updated;

  const FavoriteEntry({
    required this.site,
    required this.id,
    required this.title,
    this.poster = '',
    this.remarks = '',
    this.updated = 0,
  });

  String get key => '$site\n$id';

  Map<String, dynamic> toJson() => {
        'site': site,
        'id': id,
        'title': title,
        'poster': poster,
        'remarks': remarks,
        'updated': updated,
      };

  factory FavoriteEntry.fromJson(Map<String, dynamic> json) {
    return FavoriteEntry(
      site: (json['site'] ?? '').toString(),
      id: (json['id'] ?? '').toString(),
      title: (json['title'] ?? '').toString(),
      poster: (json['poster'] ?? '').toString(),
      remarks: (json['remarks'] ?? '').toString(),
      updated: (json['updated'] as num?)?.toInt() ?? 0,
    );
  }
}
