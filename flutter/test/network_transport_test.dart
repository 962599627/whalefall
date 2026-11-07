import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:marco_movie/core/cms_client.dart';
import 'package:marco_movie/core/cms_parser.dart';
void main() {
  test('cancelled search stops its actual HTTP response', () async {
    final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close());
    final started = Completer<void>();
    final disconnected = Completer<void>();
    server.listen((socket) {
      Timer? chunks;
      socket.listen((data) {
        if (started.isCompleted) return;
        socket.add(ascii.encode('HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 5000000\r\n\r\n{"list":['));
        started.complete();
        chunks = Timer.periodic(const Duration(milliseconds: 10), (_) => socket.add(List.filled(1024, 32)));
      }, onDone: () {
        chunks?.cancel();
        socket.destroy();
        if (!disconnected.isCompleted) disconnected.complete();
      }, onError: (Object _) {
        chunks?.cancel();
        socket.destroy();
        if (!disconnected.isCompleted) disconnected.complete();
      });
      addTearDown(() { chunks?.cancel(); socket.destroy(); });
    });
    final site = CmsParser.parseConfig('{"sites":[{"key":"slow","name":"Slow","type":1,'
        '"api":"http://127.0.0.1:${server.port}/api"}]}').sites.single;
    final subscription = CmsClient().searchAcrossSites([site], 'Example').listen((_) {});
    await started.future.timeout(const Duration(seconds: 3), onTimeout: () => throw StateError('request never started'));
    await Future<void>.delayed(const Duration(milliseconds: 40));
    await subscription.cancel();
    await disconnected.future.timeout(const Duration(seconds: 3), onTimeout: () => throw StateError('request socket stayed open after cancel'));
  });
}
