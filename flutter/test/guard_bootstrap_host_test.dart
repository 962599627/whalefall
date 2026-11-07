import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:marco_movie/core/catpaw_host.dart';

import 'drpy_host_test.dart' as fixture;

const _rule = "var rule={title:'Fixture',host:'https://fixture.invalid'};";

Future<String> _bootstrap(Directory directory) async {
  final script = await fixture.fixtureBootstrap(directory);
  await File(script).writeAsString(r'''
const fs=require('node:fs'),http=require('node:http'),path=require('node:path'),crypto=require('node:crypto');
const root=process.argv[2],ready=process.argv[3],secret='b'.repeat(64);
const busyCounts=new Map();
const server=http.createServer(async(req,res)=>{
  let raw='';for await(const chunk of req)raw+=chunk;
  const value=JSON.parse(raw),data=fs.readFileSync(path.join(root,'imports',value.artifactHash+'.jar'));
  fs.appendFileSync(path.join(root,'requests.txt'),JSON.stringify({url:req.url,method:req.method,
    contentType:req.headers['content-type'],authorization:req.headers.authorization||'',body:value,
    hash:crypto.createHash('sha256').update(data).digest('hex')})+'\n');
  res.setHeader('Content-Type','application/json');
  const mode=data.toString();
  if(mode==='hold'){
    res.on('close',()=>{if(!res.writableEnded)fs.writeFileSync(path.join(root,'aborted.txt'),'1');});
    fs.writeFileSync(path.join(root,'started.txt'),'1');
    await new Promise(resolve=>setTimeout(resolve,200));
  }
  if(res.destroyed)return;
  let result={version:1,rule:"var rule={title:'Fixture',host:'https://fixture.invalid'};",origin:'https://fixture.invalid'};
  if(mode.startsWith('busy')){
    const count=(busyCounts.get(mode)||0)+1;busyCounts.set(mode,count);
    fs.writeFileSync(path.join(root,'busy.txt'),String(count));
    if(mode!=='busy'||count<3){res.statusCode=429;result={error:'bootstrap_busy'};}
  }
  if(mode==='userinfo')result.origin='https://user:secret@fixture.invalid';
  if(mode==='file')result.origin='file:///private/source';
  if(mode==='fragment')result.origin+='\u0023fragment';
  if(mode==='wrongversion')result.version=2;
  if(mode==='extra')result.extra='unsupported';
  if(mode==='empty')result.rule='';
  if(mode==='unsupported'){res.statusCode=422;result={error:'guard_artifact_unsupported'};}
  res.end(JSON.stringify(result));
});
server.listen(0,'127.0.0.1',()=>fs.writeFileSync(ready,JSON.stringify({port:server.address().port,secret})));
process.stdin.resume();process.stdin.on('end',()=>server.close());
''');
  return script;
}

Future<void> _waitFor(File file) async {
  final deadline = DateTime.now().add(const Duration(seconds: 5));
  while (!await file.exists()) {
    if (DateTime.now().isAfter(deadline)) throw StateError('fixture deadline');
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
}

void main() {
  late Directory directory;
  late CatPawHost host;
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('marco-guard-host-');
    final node = (await Process.run('node', [
      '-p',
      'process.execPath',
    ])).stdout.toString().trim();
    host = CatPawHost(
      directory: () async => Directory('${directory.path}/state'),
      executable: node,
      bootstrap: await _bootstrap(directory),
    );
  });
  tearDown(() async {
    await host.close();
    await directory.delete(recursive: true);
  });

  test(
    'stages exact bytes and sends only authenticated artifact identity',
    () async {
      final bytes = [0, 1, 127, 128, 255];
      final hash = sha256.convert(bytes).toString();
      expect(await host.bootstrapGuard(bytes), {
        'version': 1,
        'rule': _rule,
        'origin': 'https://fixture.invalid',
      });
      expect(
        await File('${directory.path}/state/imports/$hash.jar').readAsBytes(),
        bytes,
      );
      final observed =
          jsonDecode(
                (await File(
                  '${directory.path}/state/requests.txt',
                ).readAsLines()).single,
              )
              as Map;
      expect(observed['url'], '/${'b' * 64}/_port-bootstrap');
      expect(observed['method'], 'POST');
      expect(observed['contentType'], startsWith('application/json'));
      expect(observed['authorization'], isEmpty);
      expect(observed['hash'], hash);
      expect(observed['body'], {
        'version': 1,
        'adapter': 'dm84-v1',
        'artifactHash': hash,
      });
      await host.bootstrapGuard(bytes);
      await File('${directory.path}/state/imports/$hash.jar').writeAsBytes([1]);
      await expectLater(host.bootstrapGuard(bytes), throwsFormatException);
      expect(
        await File('${directory.path}/state/requests.txt').readAsLines(),
        hasLength(2),
      );
    },
  );

  test(
    'invalid adapter and input are rejected before starting the host',
    () async {
      for (final bytes in [
        <int>[],
        [-1],
        [256],
        List<int>.filled(16 * 1024 * 1024 + 1, 0),
      ]) {
        await expectLater(host.bootstrapGuard(bytes), throwsFormatException);
      }
      await expectLater(
        host.bootstrapGuard([1], adapter: '../other'),
        throwsFormatException,
      );
      expect(await Directory('${directory.path}/state').exists(), isFalse);
    },
  );

  test(
    'rejects malformed outputs and reports an unsupported artifact explicitly',
    () async {
      for (final mode in [
        'userinfo',
        'file',
        'fragment',
        'wrongversion',
        'extra',
        'empty',
      ]) {
        await expectLater(
          host.bootstrapGuard(utf8.encode(mode)),
          throwsFormatException,
          reason: mode,
        );
      }
      await expectLater(
        host.bootstrapGuard(utf8.encode('unsupported')),
        throwsA(
          isA<GuardBootstrapException>().having(
            (e) => e.code,
            'code',
            'guard_artifact_unsupported',
          ),
        ),
      );
    },
  );

  test(
    'busy bootstrap retries are bounded and only retry the transient code',
    () async {
      expect((await host.bootstrapGuard(utf8.encode('busy')))['rule'], _rule);
      expect(
        await File('${directory.path}/state/requests.txt').readAsLines(),
        hasLength(3),
      );
      final timer = Stopwatch()..start();
      await expectLater(
        host.bootstrapGuard(utf8.encode('busyforever')),
        throwsA(
          isA<GuardBootstrapException>().having(
            (e) => e.code,
            'code',
            'bootstrap_busy',
          ),
        ),
      );
      expect(timer.elapsed, greaterThanOrEqualTo(const Duration(seconds: 2)));
      expect(timer.elapsed, lessThan(const Duration(seconds: 5)));
      expect(
        (await File(
          '${directory.path}/state/requests.txt',
        ).readAsLines()).length,
        lessThan(20),
      );
    },
  );

  test('cancellation interrupts busy backoff without a late retry', () async {
    final cancellation = Completer<void>();
    final call = host.bootstrapGuard(
      utf8.encode('busyforever'),
      cancellation: cancellation,
    );
    final check = expectLater(call, throwsFormatException);
    await _waitFor(File('${directory.path}/state/busy.txt'));
    cancellation.complete();
    await check;
    await Future<void>.delayed(const Duration(milliseconds: 300));
    expect(
      await File('${directory.path}/state/requests.txt').readAsLines(),
      hasLength(1),
    );
  });

  test(
    'cancellation before or during staging never invokes the extractor',
    () async {
      final cancelled = Completer<void>()..complete();
      await expectLater(
        host.bootstrapGuard([1], cancellation: cancelled),
        throwsFormatException,
      );
      expect(await Directory('${directory.path}/state').exists(), isFalse);
      final waiting = Completer<Directory>();
      final started = Completer<void>();
      final delayed = CatPawHost(
        directory: () {
          started.complete();
          return waiting.future;
        },
      );
      final cancellation = Completer<void>();
      final call = delayed.bootstrapGuard([1], cancellation: cancellation);
      final check = expectLater(call, throwsFormatException);
      await started.future;
      cancellation.complete();
      await check;
      waiting.complete(Directory('${directory.path}/delayed'));
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(
        await File('${directory.path}/delayed/ready.json').exists(),
        isFalse,
      );
      expect(
        await Directory('${directory.path}/delayed/imports').exists(),
        isFalse,
      );
      await delayed.close();
    },
  );

  test(
    'cancellation closes the in-flight request and subsequent calls work',
    () async {
      final cancellation = Completer<void>();
      final call = host.bootstrapGuard(
        utf8.encode('hold'),
        cancellation: cancellation,
      );
      final check = expectLater(call, throwsFormatException);
      await _waitFor(File('${directory.path}/state/started.txt'));
      cancellation.complete();
      await check;
      await _waitFor(File('${directory.path}/state/aborted.txt'));
      expect((await host.bootstrapGuard([42]))['rule'], _rule);
    },
  );

  test(
    'pinned real router rejects an unreviewed artifact with the typed code',
    () async {
      await host.close();
      final node = (await Process.run('node', [
        '-p',
        'process.execPath',
      ])).stdout.toString().trim();
      host = CatPawHost(
        directory: () async => Directory('${directory.path}/real'),
        executable: node,
        bootstrap: '${Directory.current.path}/assets/catpaw/host.cjs',
      );
      final bytes = utf8.encode('Synthetic unknown plugin, never executed.');
      for (var attempt = 0; attempt < 2; attempt++) {
        await expectLater(
          host.bootstrapGuard(bytes),
          throwsA(
            isA<GuardBootstrapException>().having(
              (e) => e.code,
              'code',
              'guard_artifact_unsupported',
            ),
          ),
        );
      }
      final hash = sha256.convert(bytes).toString();
      expect(
        await File('${directory.path}/real/imports/$hash.jar').readAsBytes(),
        bytes,
      );
      expect(
        await File('${directory.path}/real/drpy-bootstrap-worker.cjs').exists(),
        isTrue,
      );
    },
  );
}
