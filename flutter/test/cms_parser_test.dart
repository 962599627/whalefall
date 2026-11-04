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
}
