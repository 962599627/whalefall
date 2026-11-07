import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

import 'catpaw_host.dart';
import 'drpy_source.dart';
import 'models.dart';

/// Ports only explicitly reviewed plugin versions. Plugin bytes are treated as
/// data; the local bootstrap never executes the imported Android implementation.
class GuardSource {
  static const maxArtifactBytes = 16 * 1024 * 1024;
  final CatPawHost host;
  final DrpySource drpy;
  final Future<Uint8List> Function(String, Map<String, String>) read;
  final _cache = <String, ({String rule, String origin, DateTime expires})>{};

  GuardSource({required this.host, required this.drpy, required this.read});

  static bool supports(CmsSite site) =>
      site.type == 3 &&
      const ['csp_dm84guard', 'csp_dm84'].contains(site.api.toLowerCase()) &&
      site.pluginReference.trim().isNotEmpty;

  Future<String> invoke(
    CmsSite site,
    String method,
    List<Object?> args, {
    Completer<void>? cancellation,
  }) async {
    void checkCancelled() {
      if (cancellation?.isCompleted == true)
        throw const FormatException('搜索已取消');
    }

    checkCancelled();
    final key = sha256
        .convert(
          utf8.encode(
            jsonEncode([
              site.subscriptionId,
              site.sourceKey,
              site.isEffectivelyPrivate,
              site.pluginReference,
              site.headers,
            ]),
          ),
        )
        .toString();
    var item = _cache[key];
    final declaration = site.pluginReference.trim();
    final marker = RegExp(
      ';md5;',
      caseSensitive: false,
    ).firstMatch(declaration);
    final address = marker == null
        ? declaration
        : declaration.substring(0, marker.start);
    final expected = marker == null ? null : declaration.substring(marker.end);
    if (expected != null && !RegExp(r'^[a-fA-F0-9]{32}$').hasMatch(expected)) {
      throw const FormatException('插件摘要格式无效，请检查订阅中的完整地址');
    }
    final artifact = Uri.tryParse(address);
    if (artifact == null ||
        !['http', 'https'].contains(artifact.scheme) ||
        artifact.host.isEmpty) {
      throw const FormatException('此插件需要订阅提供完整的 HTTP(S) 插件地址');
    }
    if (item == null || DateTime.now().isAfter(item.expires)) {
      final bytes = await read(address, site.headers);
      checkCancelled();
      if (bytes.isEmpty || bytes.length > maxArtifactBytes) {
        throw const FormatException('插件包为空或超过 16 MB');
      }
      if (expected != null &&
          md5.convert(bytes).toString() != expected.toLowerCase()) {
        throw const FormatException('插件摘要校验失败，请刷新原订阅后重试');
      }
      final generated = await host.bootstrapGuard(
        bytes,
        cancellation: cancellation,
      );
      checkCancelled();
      item = (
        rule: generated['rule'] as String,
        origin: generated['origin'] as String,
        expires: DateTime.now().add(const Duration(minutes: 5)),
      );
      if (_cache.length >= 32) _cache.remove(_cache.keys.first);
      _cache[key] = item;
    }
    final target = Uri.parse(item.origin);
    final sameOrigin =
        artifact.scheme == target.scheme &&
        artifact.host == target.host &&
        artifact.port == target.port;
    final runtimeHeaders = Map<String, String>.fromEntries(
      site.headers.entries.where(
        (entry) =>
            sameOrigin ||
            !const [
              'authorization',
              'cookie',
              'cookie2',
              'proxy-authorization',
            ].contains(entry.key.toLowerCase()),
      ),
    );
    // Import/download credentials belong to the plugin origin, not to endpoints
    // extracted from its content. Explicit rule playback headers remain intact.
    return drpy.invoke(
      site.copyWith(
        headers: runtimeHeaders,
        ext: jsonEncode({
          'ruleHash': sha256.convert(utf8.encode(item.rule)).toString(),
          'originalExt': site.ext,
        }),
      ),
      method,
      args,
      rule: item.rule,
      origin: item.origin,
      cancellation: cancellation,
    );
  }
}
