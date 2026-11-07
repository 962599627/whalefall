import 'package:flutter_test/flutter_test.dart';
import 'package:marco_movie/core/models.dart';
import 'package:marco_movie/core/source_playback.dart';
import 'package:marco_movie/core/csp_bridge.dart';

void main() {
  test('cross-source results preserve real providers and episode names', () async {
    const original = CmsVideo(id: 'one', title: 'Fixture', lines: [
      CmsLine(name: '真实来源', episodes: [CmsEpisode(name: '第08集', url: 'https://media.test/e8.mp4')]),
    ]);
    final resolved = await CspBridge.resolveCrossSourceVideo(original, 'Fixture',
      (String url, {Map<String, String> headers = const {}, Duration? requestTimeout}) async => throw StateError('unexpected request'));
    expect(resolved.lines.length, 1);
    expect(resolved.lines.single.name, '真实来源');
    expect(resolved.lines.single.episodes.single.name, '第08集');
    expect(resolved.lines.single.episodes.single.url, original.lines.single.episodes.single.url);
  });
  test('HTML plugin website is not advertised as a JSON source adapter', () {
    const site = CmsSite(key: 'html', name: 'HTML', type: 3, api: 'csp_SixVGuard', ext: 'https://example.test/');
    expect(CspBridge.backendsForSite(site), isEmpty);
    expect(site.canBrowse, isFalse);
  });
  const episode=CmsEpisode(name:'Episode',url:'mmpaw://play?id=one');
  Future<Uri> local(String value) async => Uri.parse('http://127.0.0.1:1234/local.mp4');
  test('alternating qualities keep labels, headers and local playback routes',()async{
    final result=await SourcePlayback.decode({'parse':0,'url':['原画','catpaw://bundle/api/stream','高清','https://media.test/video.mp4'],
      'header':'{"Referer":"https://origin.test/"}', 'extra':{'audio':'https://media.test/audio.m4s'}},episode,localUrl:local,sourceHeaders:{'X-Source':'fixture'});
    expect(result.url,'http://127.0.0.1:1234/local.mp4');
    expect(result.variants.map((v)=>v.name),['原画','高清']);
    expect(result.variants.last.headers,{'X-Source':'fixture','Referer':'https://origin.test/'});
    expect(result.name,'Episode');
    expect(result.externalAudioUrl,'https://media.test/audio.m4s');
    expect(result.variants.last.externalAudioUrl,result.externalAudioUrl);
  });
  test('object alternatives select only network media and keep per-quality headers',()async{
    final result=await SourcePlayback.decode({'url':[
      {'name':'invalid','url':'file:///private/movie.mp4'},
      {'name':'HD','url':'https://media.test/hd.mp4','headers':{'X-Quality':'hd'}},
      {'name':'duplicate','url':'https://media.test/hd.mp4'},
    ]},episode,localUrl:local);
    expect(result.variants.length,1);
    expect(result.headers['X-Quality'],'hd');
    await expectLater(SourcePlayback.decode({'url':'file:///private/movie.mp4'},episode,localUrl:local),throwsFormatException);
    await expectLater(SourcePlayback.decode({'parse':1,'url':'https://media.test/page'},episode,localUrl:local),throwsFormatException);
  });
  test('inherited source credentials follow only the same playback origin', () async {
    final result = await SourcePlayback.decode({'url': [
      'https://source.test/video.mp4', 'https://source.test:8443/video.mp4',
      'http://source.test/video.mp4', 'https://cdn.test/video.mp4',
      {'url': 'https://cdn.test/explicit.mp4', 'header': {'Authorization': 'CDN fixture'}},
    ]}, episode, localUrl: local, sourceOrigin: Uri.parse('https://source.test/rule.js'),
        sourceHeaders: {'Authorization': 'Source fixture', 'Cookie': 'session=fixture',
          'Cookie2': 'legacy=fixture', 'Proxy-Authorization': 'Proxy fixture', 'User-Agent': 'Fixture'});
    expect(result.variants.first.headers['Authorization'], 'Source fixture');
    for (final choice in result.variants.skip(1).take(3)) {
      expect(choice.headers, {'User-Agent': 'Fixture'});
    }
    expect(result.variants.last.headers, {'User-Agent': 'Fixture', 'Authorization': 'CDN fixture'});
  });
}
