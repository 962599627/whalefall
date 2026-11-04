import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:marco_movie/core/cms_parser.dart';
import 'package:marco_movie/core/models.dart';
void main() {
  test('parses CMS config with relative URLs and strips JSON comments', () {
    const raw = '''
    {
      // sample comment
      "sites": [
        {"key": "demo", "name": "Demo VOD", "type": 1, "api": "/api.php/provide/vod/"}
      ],
      "lives": [
        {"name": "Demo Live", "url": "live/channels.m3u"}
      ]
    }
    ''';
    final config =
        CmsParser.parseConfig(raw, origin: 'https://config.example/root/tv.json');
    expect(config.supportedSites.length, 1);
    expect(
      config.supportedSites.first.api,
      'https://config.example/api.php/provide/vod/',
    );
    expect(config.lives.length, 1);
    expect(config.lives.first.url, 'https://config.example/root/live/channels.m3u');
  });
  test('handles trailing commas, string/bool fields, Base64 steganography, IDN URLs, and multi-depot configs', () {
    const lenientJson = '''
    \uFEFF{
      "urls": [
        {"name": "主力仓", "url": "https://depot.example/main.json",},
      ],
      "sites": [
        {
          "key": "s1",
          "name": "站点一",
          "type": "1",
          "searchable": true,
          "api": "https://cms.example/api.php",
          "header": "{\\"Referer\\":\\"https://cms.example\\"}",
        },
      ],
    }
    ''';
    final parsed = CmsParser.parseConfig(lenientJson);
    expect(parsed.depots.length, 1);
    expect(parsed.depots.first.name, '主力仓');
    expect(parsed.supportedSites.length, 1);
    expect(parsed.supportedSites.first.type, 1);
    expect(parsed.supportedSites.first.searchable, isTrue);
    expect(parsed.supportedSites.first.headers['Referer'], 'https://cms.example');
    // Test JPEG + ** + Base64 steganography
    final b64Payload = base64Encode(utf8.encode(lenientJson));
    final fakeJpegBytes = <int>[
      0xFF, 0xD8, 0xFF, 0xD9, 0x2A, 0x2A, // **
      ...ascii.encode(b64Payload),
    ];
    final unpackedDoc = CmsParser.decodeConfigDocument(fakeJpegBytes);
    final fromStego = CmsParser.parseConfig(unpackedDoc);
    expect(fromStego.supportedSites.length, 1);
    expect(fromStego.supportedSites.first.key, 's1');
    // Test Chinese IDN domain normalization and messy text URL extraction
    final normalizedIdn = CmsParser.extractAndNormalizeUrl('最新接口：http://肥猫.example/tv 欢迎使用');
    expect(normalizedIdn, 'http://xn--z7x900a.example/tv');
  });
  test('parses multi-line and multi-episode vod_play_url', () {
    const raw = '''
    {
      "page": 1,
      "pagecount": 2,
      "list": [
        {
          "vod_id": "101",
          "vod_name": "测试剧集",
          "vod_play_from": "线路A\$\$\$线路B",
          "vod_play_url": "第1集\$https://media.test/a1.m3u8#第2集\$https://media.test/a2.m3u8\$\$\$正片\$https://media.test/b1.mp4"
        }
      ]
    }
    ''';
    final listing = CmsParser.parseListing(raw);
    expect(listing.videos.length, 1);
    final video = listing.videos.first;
    expect(video.lines.length, 2);
    expect(video.lines[0].name, '线路A');
    expect(video.lines[0].episodes.length, 2);
    expect(video.lines[0].episodes[1].url, 'https://media.test/a2.m3u8');
    expect(video.lines[1].name, '线路B');
  });
  test('parses M3U and TXT live playlists', () {
    const playlist = '''
    #EXTM3U
    #EXTINF:-1 group-title="新闻",综合台
    https://media.test/live1.m3u8
    电影频道,#genre#
    经典剧场,https://media.test/movie1.m3u8#https://media.test/movie2.m3u8
    ''';
    final groups = CmsParser.parseLivePlaylist(playlist);
    expect(groups.length, 2);
    expect(groups[0].name, '新闻');
    expect(groups[0].channels.first.name, '综合台');
    expect(groups[1].name, '电影频道');
    expect(groups[1].channels.first.urls.length, 2);
  });
  test('enforces 60-entry watch history cap and 95% completion reset', () {
    var history = <WatchHistoryEntry>[];
    for (int i = 0; i < 65; i++) {
      history = CmsParser.upsertHistory(
        history,
        WatchHistoryEntry(
          site: 's',
          id: 'id_$i',
          title: 'Title $i',
          position: 30000,
          duration: 120000,
        ),
      );
    }
    expect(history.length, 60);
    expect(history.first.id, 'id_64');
    const completed = WatchHistoryEntry(
      site: 's',
      id: 'done',
      title: 'Done',
      position: 118000,
      duration: 120000,
    );
    expect(completed.complete, isTrue);
    expect(completed.resumePosition, 0);
  });
  test('parses XML CMS type 0 categories and video listings and checks direct playability', () {
    const xmlRaw = '''<?xml version="1.0" encoding="utf-8"?>
    <rss version="5.1">
      <class>
        <ty id="1">电影</ty>
        <ty id="2">剧集</ty>
      </class>
      <list page="1" pagecount="3" total="2">
        <video>
          <id>201</id>
          <name><![CDATA[XML测试片]]></name>
          <type>电影</type>
          <pic>https://img.test/p.jpg</pic>
          <note><![CDATA[HD]]></note>
          <dl>
            <dd flag="m3u8"><![CDATA[正片\$https://media.test/play.m3u8#花絮\$jx://custom/id]]></dd>
          </dl>
        </video>
      </list>
    </rss>''';
    final categories = CmsParser.parseCategories(xmlRaw);
    expect(categories.length, 2);
    expect(categories.first.name, '电影');
    final listing = CmsParser.parseListing(xmlRaw);
    expect(listing.pageCount, 3);
    expect(listing.videos.length, 1);
    final video = listing.videos.first;
    expect(video.title, 'XML测试片');
    expect(video.lines.first.episodes.length, 2);
    expect(video.lines.first.episodes[0].isDirectPlayable, isTrue);
    expect(video.lines.first.episodes[1].isDirectPlayable, isFalse);
  });
  test('supports bidirectional Android and Flutter backup formats and rejects invalid backups', () {
    const androidBackup = '''
    {
      "format": "MarcoMovie-1",
      "exported": 1700000000000,
      "origin": "https://mirror.example/config.json",
      "config": {
        "sites": [
          {"key": "vod1", "name": "VOD 1", "type": 1, "api": "https://vod.example/api"}
        ]
      },
      "settings": {
        "selected_site": "vod1",
        "play_speed": 1.25,
        "scale_mode": "fit",
        "auto_next": true,
        "watch_history": [
          {"site": "vod1", "id": "99", "title": "旧番", "position": 15000, "duration": 90000}
        ],
        "favorites": [
          {"site": "vod1", "id": "99", "title": "旧番"}
        ],
        "global_search_history": ["星际", "流浪"]
      }
    }
    ''';
    final decoded = CmsParser.decodeBackupPayload(androidBackup);
    expect(decoded.origin, 'https://mirror.example/config.json');
    expect(decoded.parsedConfig, isNotNull);
    expect(decoded.parsedConfig!.sites.first.key, 'vod1');
    expect(decoded.settings['selected_site'], 'vod1');
    expect(decoded.settings['play_speed'], 1.25);
    expect((decoded.settings['watch_history'] as List).length, 1);
    expect((decoded.settings['favorites'] as List).length, 1);
    expect(decoded.settings['global_search_history'], ['星际', '流浪']);
    final reEncoded = CmsParser.encodeBackupPayload(
      origin: decoded.origin,
      rawConfig: decoded.rawConfig,
      settings: decoded.settings,
      exportedAt: 1700000001000,
    );
    final roundTrip = CmsParser.decodeBackupPayload(reEncoded);
    expect(roundTrip.origin, decoded.origin);
    expect(roundTrip.parsedConfig!.sites.first.key, 'vod1');
    expect(roundTrip.settings['selected_site'], 'vod1');
    expect((roundTrip.settings['watch_history'] as List).first['id'], '99');
    expect((roundTrip.settings['favorites'] as List).first['id'], '99');
    expect(
      () => CmsParser.decodeBackupPayload('{"format":"unknown","settings":{}}'),
      throwsFormatException,
    );
  });
}
