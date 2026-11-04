import 'dart:convert';
import 'models.dart';

/// Pure Dart parser for Marco Movie / TVBox JSON CMS, M3U/TXT live playlists, and episode groups.
class CmsParser {
  static const int maxHistoryEntries = 60;
  static const int maxFavoriteEntries = 120;
  static const int episodePageSize = 50;

  static String stripJsonComments(String input) {
    final out = StringBuffer();
    bool inString = false;
    bool escaped = false;
    for (int i = 0; i < input.length; i++) {
      final c = input[i];
      if (inString) {
        out.write(c);
        if (escaped) {
          escaped = false;
        } else if (c == r'\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
        continue;
      }
      if (c == '"') {
        inString = true;
        out.write(c);
        continue;
      }
      if (c == '/' && i + 1 < input.length) {
        final next = input[i + 1];
        if (next == '/') {
          i += 2;
          while (i < input.length && input[i] != '\n') {
            i++;
          }
          if (i < input.length) out.write('\n');
          continue;
        }
        if (next == '*') {
          i += 2;
          while (i + 1 < input.length &&
              !(input[i] == '*' && input[i + 1] == '/')) {
            i++;
          }
          i++;
          continue;
        }
      }
      out.write(c);
    }
    return out.toString().trim();
  }

  static String resolveUrl(String base, String relativeOrAbsolute) {
    final trimmed = relativeOrAbsolute.trim();
    if (trimmed.isEmpty) return '';
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
      return trimmed;
    }
    if (base.trim().isEmpty) return trimmed;
    try {
      return Uri.parse(base.trim()).resolve(trimmed).toString();
    } catch (_) {
      return trimmed;
    }
  }

  static Map<String, String> _stringHeaders(dynamic raw) {
    if (raw is! Map) return const {};
    final result = <String, String>{};
    raw.forEach((k, v) {
      if (k != null && v != null) {
        result[k.toString()] = v.toString();
      }
    });
    return result;
  }

  static CmsConfig parseConfig(String rawJson, {String origin = ''}) {
    final cleaned = stripJsonComments(rawJson);
    final decoded = jsonDecode(cleaned);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('配置根节点必须是 JSON 对象');
    }
    final sites = <CmsSite>[];
    final seenKeys = <String>{};
    final rawSites = decoded['sites'];
    if (rawSites is List) {
      for (final item in rawSites) {
        if (item is! Map) continue;
        final key = (item['key'] ?? '').toString().trim();
        final name = (item['name'] ?? key).toString().trim();
        final type = (item['type'] as num?)?.toInt() ?? -1;
        final api = resolveUrl(origin, (item['api'] ?? '').toString());
        if (key.isEmpty || seenKeys.contains(key)) continue;
        seenKeys.add(key);
        sites.add(
          CmsSite(
            key: key,
            name: name.isEmpty ? key : name,
            type: type,
            api: api,
            playUrl: (item['playUrl'] ?? '').toString().trim(),
            searchable: (item['searchable'] as num?)?.toInt() != 0,
            headers: _stringHeaders(item['headers'] ?? item['header']),
          ),
        );
      }
    }

    final lives = <LiveSource>[];
    final rawLives = decoded['lives'];
    if (rawLives is List) {
      for (final item in rawLives) {
        if (item is! Map) continue;
        if (item['channels'] is List) {
          for (final ch in item['channels'] as List) {
            if (ch is! Map) continue;
            final chName = (ch['name'] ?? '直播源').toString().trim();
            final urls = ch['urls'];
            if (urls is List && urls.isNotEmpty) {
              final resolved = resolveUrl(origin, urls.first.toString());
              if (resolved.startsWith('http://') ||
                  resolved.startsWith('https://')) {
                lives.add(
                  LiveSource(
                    name: chName,
                    url: resolved,
                    headers: _stringHeaders(ch['headers'] ?? item['headers']),
                  ),
                );
              }
            }
          }
          continue;
        }
        final url = resolveUrl(origin, (item['url'] ?? '').toString());
        if (url.startsWith('http://') || url.startsWith('https://')) {
          lives.add(
            LiveSource(
              name: (item['name'] ?? '直播源').toString().trim(),
              url: url,
              headers: _stringHeaders(item['headers'] ?? item['header']),
            ),
          );
        }
      }
    }
    return CmsConfig(sites: sites, lives: lives);
  }

  static List<CmsCategory> parseCategories(String rawJson) {
    final trimmed = rawJson.trimLeft();
    if (trimmed.startsWith('<')) {
      return _parseXmlCategories(trimmed);
    }
    final decoded = jsonDecode(stripJsonComments(rawJson));
    if (decoded is! Map<String, dynamic>) return const [];
    final rawClass = decoded['class'];
    if (rawClass is! List) return const [];
    final categories = <CmsCategory>[];
    for (final item in rawClass) {
      if (item is! Map) continue;
      final id = (item['type_id'] ?? '').toString().trim();
      final name = (item['type_name'] ?? '').toString().trim();
      if (id.isNotEmpty && name.isNotEmpty) {
        categories.add(CmsCategory(id: id, name: name));
      }
    }
    return categories;
  }

  static List<CmsCategory> _parseXmlCategories(String xml) {
    final categories = <CmsCategory>[];
    final tagPattern = RegExp(
      r'''<ty\b[^>]*\bid\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</ty>''',
      caseSensitive: false,
    );
    for (final match in tagPattern.allMatches(xml)) {
      final id = (match.group(1) ?? '').trim();
      final name = _cleanXmlText(match.group(2) ?? '');
      if (id.isNotEmpty && name.isNotEmpty) {
        categories.add(CmsCategory(id: id, name: name));
      }
    }
    return categories;
  }

  static CmsListing parseListing(String rawJson) {
    final trimmed = rawJson.trimLeft();
    if (trimmed.startsWith('<')) {
      return _parseXmlListing(trimmed);
    }
    final decoded = jsonDecode(stripJsonComments(rawJson));
    if (decoded is! Map<String, dynamic>) return const CmsListing();
    final page = int.tryParse((decoded['page'] ?? '1').toString()) ?? 1;
    final pageCount =
        int.tryParse((decoded['pagecount'] ?? '1').toString()) ?? 1;
    final rawList = decoded['list'];
    if (rawList is! List) {
      return CmsListing(page: page, pageCount: pageCount);
    }
    final videos = <CmsVideo>[];
    for (final item in rawList) {
      if (item is! Map) continue;
      final id = (item['vod_id'] ?? '').toString().trim();
      final title = (item['vod_name'] ?? '').toString().trim();
      if (id.isEmpty && title.isEmpty) continue;
      videos.add(
        CmsVideo(
          id: id,
          title: title,
          poster: (item['vod_pic'] ?? '').toString().trim(),
          remarks: (item['vod_remarks'] ?? '').toString().trim(),
          year: (item['vod_year'] ?? '').toString().trim(),
          area: (item['vod_area'] ?? '').toString().trim(),
          actor: (item['vod_actor'] ?? '').toString().trim(),
          description: _cleanHtml((item['vod_content'] ?? '').toString()),
          tag: (item['vod_tag'] ?? '').toString().trim(),
          lines: _parseLines(
            (item['vod_play_from'] ?? '').toString(),
            (item['vod_play_url'] ?? '').toString(),
          ),
        ),
      );
    }
    return CmsListing(
      page: page < 1 ? 1 : page,
      pageCount: pageCount < 1 ? 1 : pageCount,
      videos: videos,
    );
  }

  static CmsListing _parseXmlListing(String xml) {
    int page = 1;
    int pageCount = 1;
    final listOpen = RegExp(r'<list\b([^>]*)>', caseSensitive: false).firstMatch(xml);
    if (listOpen != null) {
      final attrs = listOpen.group(1) ?? '';
      final pgMatch = RegExp(r'''\bpage\s*=\s*["']?(\d+)''', caseSensitive: false)
          .firstMatch(attrs);
      final pcMatch = RegExp(
        r'''\bpagecount\s*=\s*["']?(\d+)''',
        caseSensitive: false,
      ).firstMatch(attrs);
      if (pgMatch != null) page = int.tryParse(pgMatch.group(1) ?? '1') ?? 1;
      if (pcMatch != null) {
        pageCount = int.tryParse(pcMatch.group(1) ?? '1') ?? 1;
      }
    }
    final videos = <CmsVideo>[];
    final videoPattern = RegExp(
      r'<video\b[^>]*>([\s\S]*?)</video>',
      caseSensitive: false,
    );
    final ddPattern = RegExp(
      r'''<dd\b([^>]*)>([\s\S]*?)</dd>''',
      caseSensitive: false,
    );
    final flagPattern = RegExp(
      r'''\bflag\s*=\s*["']([^"']*)["']''',
      caseSensitive: false,
    );
    for (final vMatch in videoPattern.allMatches(xml)) {
      final block = vMatch.group(1) ?? '';
      final id = _extractXmlTag(block, 'id');
      final title = _extractXmlTag(block, 'name');
      if (id.isEmpty && title.isEmpty) continue;
      final fromParts = <String>[];
      final urlParts = <String>[];
      for (final dd in ddPattern.allMatches(block)) {
        final ddAttrs = dd.group(1) ?? '';
        final flagMatch = flagPattern.firstMatch(ddAttrs);
        final flag = (flagMatch?.group(1) ?? '').trim();
        final body = _cleanXmlText(dd.group(2) ?? '');
        if (body.isNotEmpty) {
          fromParts.add(flag.isEmpty ? '线路 ${fromParts.length + 1}' : flag);
          urlParts.add(body);
        }
      }
      videos.add(
        CmsVideo(
          id: id,
          title: title,
          poster: _extractXmlTag(block, 'pic'),
          remarks: _extractXmlTag(block, 'note'),
          year: _extractXmlTag(block, 'year'),
          area: _extractXmlTag(block, 'area'),
          actor: _extractXmlTag(block, 'actor'),
          description: _cleanHtml(_extractXmlTag(block, 'des')),
          lines: _parseLines(fromParts.join(r'$$$'), urlParts.join(r'$$$')),
        ),
      );
    }
    return CmsListing(
      page: page < 1 ? 1 : page,
      pageCount: pageCount < 1 ? 1 : pageCount,
      videos: videos,
    );
  }

  static String _extractXmlTag(String block, String tag) {
    final m = RegExp(
      '<$tag\\b[^>]*>([\\s\\S]*?)</$tag>',
      caseSensitive: false,
    ).firstMatch(block);
    return m == null ? '' : _cleanXmlText(m.group(1) ?? '');
  }

  static String _cleanXmlText(String raw) {
    var s = raw.trim();
    final cdata = RegExp(r'^<!\[CDATA\[([\s\S]*?)\]\]>$').firstMatch(s);
    if (cdata != null) {
      s = (cdata.group(1) ?? '').trim();
    }
    return s;
  }

  static String _cleanHtml(String html) {
    return html
        .replaceAll(RegExp(r'<[^>]*>'), '')
        .replaceAll('&nbsp;', ' ')
        .replaceAll('&amp;', '&')
        .replaceAll('&quot;', '"')
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .trim();
  }

  static List<CmsLine> _parseLines(String playFrom, String playUrl) {
    if (playUrl.trim().isEmpty) return const [];
    final fromParts = playFrom.split(r'$$$');
    final urlParts = playUrl.split(r'$$$');
    final lines = <CmsLine>[];
    for (int i = 0; i < urlParts.length; i++) {
      final rawGroup = urlParts[i].trim();
      if (rawGroup.isEmpty) continue;
      final lineName = i < fromParts.length && fromParts[i].trim().isNotEmpty
          ? fromParts[i].trim()
          : '线路 ${i + 1}';
      final episodes = <CmsEpisode>[];
      for (final segment in rawGroup.split('#')) {
        final s = segment.trim();
        if (s.isEmpty) continue;
        final dollar = s.indexOf(r'$');
        final epName = dollar >= 0 ? s.substring(0, dollar).trim() : '正片';
        final epUrl = dollar >= 0 ? s.substring(dollar + 1).trim() : s;
        if (epUrl.isNotEmpty) {
          episodes.add(
            CmsEpisode(
              name: epName.isEmpty ? '第 ${episodes.length + 1} 集' : epName,
              url: epUrl,
            ),
          );
        }
      }
      if (episodes.isNotEmpty) {
        lines.add(CmsLine(name: lineName, episodes: episodes));
      }
    }
    return lines;
  }

  static List<LiveGroup> parseLivePlaylist(String content) {
    final lines = const LineSplitter().convert(content);
    final groups = <String, Map<String, List<String>>>{};

    void addStream(String groupName, String channelName, String url) {
      final g = groupName.trim().isEmpty ? '默认频道' : groupName.trim();
      final c = channelName.trim().isEmpty ? '未命名频道' : channelName.trim();
      final u = url.trim();
      if (!u.startsWith('http://') &&
          !u.startsWith('https://') &&
          !u.startsWith('rtmp://') &&
          !u.startsWith('rtsp://')) {
        return;
      }
      final chMap = groups.putIfAbsent(g, () => <String, List<String>>{});
      final urlList = chMap.putIfAbsent(c, () => <String>[]);
      if (!urlList.contains(u)) {
        urlList.add(u);
      }
    }

    String currentGroup = '精选频道';
    String pendingM3uTitle = '';
    String pendingM3uGroup = '';

    for (final rawLine in lines) {
      final line = rawLine.trim();
      if (line.isEmpty) continue;
      if (line.startsWith('#EXTINF')) {
        final groupMatch = RegExp(r'group-title="([^"]*)"').firstMatch(line);
        pendingM3uGroup = groupMatch?.group(1)?.trim() ?? '直播频道';
        final comma = line.lastIndexOf(',');
        pendingM3uTitle = comma >= 0 ? line.substring(comma + 1).trim() : '频道';
        continue;
      }
      if (line.startsWith('#')) continue;
      if (pendingM3uTitle.isNotEmpty) {
        addStream(pendingM3uGroup, pendingM3uTitle, line);
        pendingM3uTitle = '';
        continue;
      }
      if (line.toLowerCase().endsWith(',#genre#')) {
        currentGroup = line.substring(0, line.length - 8).trim();
        if (currentGroup.isEmpty) currentGroup = '默认分组';
        continue;
      }
      final comma = line.indexOf(',');
      if (comma > 0) {
        final name = line.substring(0, comma).trim();
        final urlPart = line.substring(comma + 1).trim();
        for (final candidate in urlPart.split('#')) {
          addStream(currentGroup, name, candidate);
        }
      }
    }

    return groups.entries
        .map(
          (g) => LiveGroup(
            name: g.key,
            channels: g.value.entries
                .map(
                  (c) => LiveChannel(
                    name: c.key,
                    group: g.key,
                    urls: c.value,
                  ),
                )
                .toList(growable: false),
          ),
        )
        .where((g) => g.channels.isNotEmpty)
        .toList(growable: false);
  }

  static int pageForEpisode(int index, int totalCount, bool descending) {
    if (totalCount <= 0) return 0;
    final clamped = index.clamp(0, totalCount - 1);
    final visualIndex = descending ? (totalCount - 1 - clamped) : clamped;
    return visualIndex ~/ episodePageSize;
  }

  static List<WatchHistoryEntry> upsertHistory(
    List<WatchHistoryEntry> existing,
    WatchHistoryEntry entry,
  ) {
    final result = <WatchHistoryEntry>[entry];
    for (final item in existing) {
      if (item.key != entry.key && result.length < maxHistoryEntries) {
        result.add(item);
      }
    }
    return result;
  }

  /// Encodes a cross-platform backup JSON string compatible with both Android (`format: MarcoMovie-1`)
  /// and Flutter (`schema: marco-movie-backup`).
  static String encodeBackupPayload({
    required String origin,
    required String rawConfig,
    required Map<String, dynamic> settings,
    int? exportedAt,
  }) {
    dynamic configValue;
    if (rawConfig.trim().isNotEmpty) {
      try {
        configValue = jsonDecode(stripJsonComments(rawConfig));
      } catch (_) {
        configValue = rawConfig;
      }
    } else {
      configValue = null;
    }
    final payload = <String, dynamic>{
      'format': 'MarcoMovie-1',
      'schema': 'marco-movie-backup',
      'version': 1,
      'exportedAt': exportedAt ?? DateTime.now().millisecondsSinceEpoch,
      'origin': origin,
      'config': configValue,
      'settings': settings,
    };
    return const JsonEncoder.withIndent('  ').convert(payload);
  }

  /// Validates and decodes a backup JSON produced by either Android (`format: MarcoMovie-1`)
  /// or Flutter (`schema: marco-movie-backup`). Throws [FormatException] before any state mutation
  /// if the payload or embedded configuration is invalid.
  static ({String origin, String rawConfig, CmsConfig? parsedConfig, Map<String, dynamic> settings})
      decodeBackupPayload(String rawBackup) {
    final decoded = jsonDecode(rawBackup);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('不是有效的 Marco Movie 备份文件');
    }
    final isAndroidFormat = decoded['format'] == 'MarcoMovie-1';
    final isFlutterSchema = decoded['schema'] == 'marco-movie-backup';
    if (!isAndroidFormat && !isFlutterSchema) {
      throw const FormatException('不是有效的 Marco Movie 备份文件');
    }
    final origin = (decoded['origin'] ?? '').toString().trim();
    final rawCfgField = decoded['config'];
    String cfgText = '';
    if (rawCfgField == null) {
      cfgText = '';
    } else if (rawCfgField is Map) {
      cfgText = jsonEncode(rawCfgField);
    } else if (rawCfgField is String) {
      cfgText = rawCfgField.trim();
    } else {
      throw const FormatException('备份中的配置字段格式无效');
    }
    CmsConfig? parsedConfig;
    if (cfgText.isNotEmpty) {
      parsedConfig = parseConfig(cfgText, origin: origin);
    }
    final rawSettings = decoded['settings'];
    final settings = rawSettings is Map
        ? Map<String, dynamic>.from(rawSettings)
        : <String, dynamic>{};
    return (
      origin: origin,
      rawConfig: cfgText,
      parsedConfig: parsedConfig,
      settings: settings,
    );
  }
}
