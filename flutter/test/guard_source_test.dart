import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:marco_movie/core/catpaw_host.dart';
import 'package:marco_movie/core/cms_client.dart';
import 'package:marco_movie/core/drpy_source.dart';
import 'package:marco_movie/core/guard_source.dart';
import 'package:marco_movie/core/models.dart';

class PortHost extends CatPawHost {
  int bootstraps = 0;
  String? failure;
  Map<String, Object?>? playback;
  Map<String, dynamic>? manifest;
  @override
  Future<Map<String, dynamic>> bootstrapGuard(
    List<int> bytes, {
    String adapter = 'dm84-v1',
    Completer<void>? cancellation,
  }) async {
    bootstraps++;
    if (failure != null)
      throw GuardBootstrapException(failure!, 'Synthetic bootstrap failure');
    return {
      'version': 1,
      'origin': 'https://catalogue.invalid/',
      'rule':
          'var rule={title:"Local port fixture",host:"https://catalogue.invalid"};',
    };
  }

  @override
  Future<String> installDrpy(Object value) async {
    manifest = Map<String, dynamic>.from(value as Map);
    return 'catpaw://${'a' * 64}/api/drpy';
  }

  @override
  Future<String> callDrpy(
    String address,
    String method,
    List<Object?> args, {
    Completer<void>? cancellation,
  }) async => jsonEncode(
    method == 'play' && playback != null
        ? playback
        : {'method': method, 'list': []},
  );
}

void main() {
  final bytes = Uint8List.fromList([0x50, 0x4b, 3, 4, 1, 2, 3]);
  final digest = md5.convert(bytes).toString();
  CmsSite site(String reference) => CmsSite(
    key: 'dm84',
    name: 'Imported rule',
    type: 3,
    api: 'csp_Dm84Guard',
    jar: reference,
    subscriptionId: 'one',
    headers: {
      'Authorization': 'import credential',
      'Cookie': 'import=fixture',
      'User-Agent': 'Fixture',
    },
  );

  test(
    'artifact integrity is verified before local bootstrap and download auth stays scoped',
    () async {
      final host = PortHost();
      var downloads = 0;
      final drpy = DrpySource(
        host: host,
        read: (_, __) async => throw StateError('rule is already local'),
      );
      final guard = GuardSource(
        host: host,
        drpy: drpy,
        read: (url, headers) async {
          downloads++;
          expect(url, 'https://import.invalid/plugin.jar');
          expect(headers['Authorization'], 'import credential');
          return bytes;
        },
      );
      final source = site('https://import.invalid/plugin.jar;md5;$digest');
      expect(GuardSource.supports(source), isTrue);
      expect(source.canBrowse, isTrue);
      expect(
        GuardSource.supports(source.copyWith(api: 'csp_UnknownGuard')),
        isFalse,
      );
      expect(GuardSource.supports(source.copyWith(jar: '')), isFalse);
      await guard.invoke(source, 'home', [false]);
      await guard.invoke(source, 'search', ['影片', false, 1]);
      expect(downloads, 1);
      expect(host.bootstraps, 1);
      expect(host.manifest!['origin'], 'https://catalogue.invalid/');
      expect(host.manifest!['headers'], {'User-Agent': 'Fixture'});
    },
  );

  test(
    'malformed and mismatched explicit MD5 never fall back to another plugin',
    () async {
      final host = PortHost();
      var downloads = 0;
      final guard = GuardSource(
        host: host,
        drpy: DrpySource(host: host, read: (_, __) async => ''),
        read: (_, __) async {
          downloads++;
          return bytes;
        },
      );
      await expectLater(
        guard.invoke(
          site('https://import.invalid/plugin.jar;md5;broken'),
          'home',
          [false],
        ),
        throwsFormatException,
      );
      expect(downloads, 0);
      await expectLater(
        guard.invoke(
          site('https://import.invalid/plugin.jar;md5;${'0' * 32}'),
          'home',
          [false],
        ),
        throwsFormatException,
      );
      expect(downloads, 1);
      expect(host.bootstraps, 0);
      final cancelled = Completer<void>()..complete();
      await expectLater(
        guard.invoke(site('https://import.invalid/plugin.jar'), 'home', [
          false,
        ], cancellation: cancelled),
        throwsFormatException,
      );
      expect(downloads, 1);
    },
  );

  test('unsupported plugin version remains an explicit failure', () async {
    final host = PortHost()..failure = 'guard_artifact_unsupported';
    final guard = GuardSource(
      host: host,
      drpy: DrpySource(host: host, read: (_, __) async => ''),
      read: (_, __) async => bytes,
    );
    await expectLater(
      guard.invoke(site('https://import.invalid/plugin.jar'), 'home', [false]),
      throwsA(
        isA<GuardBootstrapException>().having(
          (e) => e.code,
          'code',
          'guard_artifact_unsupported',
        ),
      ),
    );
    expect(host.manifest, isNull);
  });

  test(
    'plugin import credentials never become implicit playback headers',
    () async {
      final host = PortHost()
        ..playback = {
          'parse': 0,
          'url': 'https://import.invalid/video.mp4',
          'header': {
            'User-Agent': 'Adapter playback',
            'X-Playback': 'explicit',
          },
        };
      final client = CmsClient(
        scriptHost: host,
        client: MockClient((request) async => http.Response.bytes(bytes, 200)),
      );
      final source = site(
        'https://import.invalid/plugin.jar;md5;$digest',
      ).copyWith(ext: 'https://import.invalid/');
      final resolved = await client.resolveEpisodeForPlayback(
        source,
        const CmsEpisode(
          name: 'Episode',
          url: 'mmjs://play?id=episode&flag=main',
        ),
      );
      expect(resolved.headers, {
        'User-Agent': 'Adapter playback',
        'X-Playback': 'explicit',
      });
    },
  );
}
