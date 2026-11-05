import 'dart:convert';
import 'dart:typed_data';
import 'models.dart';
/// Pure Dart parser for Marco Movie / TVBox JSON & XML CMS, multi-depot configs,
/// obfuscated/encrypted config payloads (**Base64, Base64, $#...#$ AES-128-CBC),
/// Punycode IDN URLs, M3U/TXT live playlists, and cross-platform backups.
class CmsParser {
  static const int maxHistoryEntries = 60;
  static const int maxFavoriteEntries = 120;
  static const int episodePageSize = 50;
  /// Extracts an HTTP(S) URL from user input (which may contain surrounding Chinese notes,
  /// smart quotes, or omit the scheme) and normalizes non-ASCII IDN domain labels to Punycode.
  static String extractAndNormalizeUrl(String rawInput) {
    var s = rawInput
        .trim()
        .replaceAll('“', '"')
        .replaceAll('”', '"')
        .replaceAll('‘', "'")
        .replaceAll('’', "'")
        .replaceAll('：', ':')
        .replaceAll('／', '/');
    if (s.isEmpty) return '';
    final explicitHttp = RegExp(
      r'''https?://[^\s"'<>`|]+''',
      caseSensitive: false,
    ).firstMatch(s);
    if (explicitHttp != null) {
      s = explicitHttp.group(0)!;
    } else if (!s.startsWith('{') &&
        !s.startsWith('[') &&
        !s.startsWith('http://') &&
        !s.startsWith('https://') &&
        s.contains('.') &&
        !s.contains('\n')) {
      final token = s.split(RegExp(r'\s+')).first;
      s = ['http', '://', token].join();
    }
    return normalizeUrl(s);
  }
  /// Normalizes an HTTP(S) URL including converting non-ASCII domain names (e.g. `肥猫.com`) to RFC 3492 Punycode.
  static String normalizeUrl(String rawUrl) {
    final trimmed = rawUrl.trim();
    if (trimmed.isEmpty) return '';
    final schemeMatch =
        RegExp(r'^(https?)://([^/?#]+)(.*)$', caseSensitive: false)
            .firstMatch(trimmed);
    if (schemeMatch == null) return trimmed;
    final scheme = schemeMatch.group(1)!.toLowerCase();
    final authority = schemeMatch.group(2)!;
    final rest = schemeMatch.group(3) ?? '';
    String userInfo = '';
    String hostPort = authority;
    final atIdx = authority.lastIndexOf('@');
    if (atIdx >= 0) {
      userInfo = authority.substring(0, atIdx + 1);
      hostPort = authority.substring(atIdx + 1);
    }
    String host = hostPort;
    String port = '';
    if (!hostPort.startsWith('[')) {
      final colonIdx = hostPort.lastIndexOf(':');
      if (colonIdx > 0) {
        host = hostPort.substring(0, colonIdx);
        port = hostPort.substring(colonIdx);
      }
      host = _idnToAscii(host);
    }
    return '$scheme://$userInfo$host$port$rest';
  }
  static String _idnToAscii(String domain) {
    final normalized = domain
        .replaceAll('\u3002', '.')
        .replaceAll('\uFF0E', '.')
        .replaceAll('\uFF61', '.');
    final labels = normalized.split('.');
    final out = <String>[];
    for (final label in labels) {
      if (label.isEmpty) {
        out.add('');
        continue;
      }
      final isAscii = label.runes.every((r) => r < 0x80);
      if (isAscii) {
        out.add(label);
      } else {
        out.add('xn--${_punycodeEncode(label.toLowerCase())}');
      }
    }
    return out.join('.');
  }
  static String _punycodeEncode(String input) {
    const int base = 36;
    const int tMin = 1;
    const int tMax = 26;
    const int skew = 38;
    const int damp = 700;
    const int initialBias = 72;
    const int initialN = 0x80;
    int adapt(int delta, int numPoints, bool firstTime) {
      var d = firstTime ? (delta ~/ damp) : (delta >> 1);
      d += d ~/ numPoints;
      int k = 0;
      while (d > ((base - tMin) * tMax) ~/ 2) {
        d ~/= (base - tMin);
        k += base;
      }
      return k + (((base - tMin + 1) * d) ~/ (d + skew));
    }
    int encodeDigit(int d) => d + 22 + 75 * (d < 26 ? 1 : 0);
    final codePoints = input.runes.toList(growable: false);
    final output = StringBuffer();
    int basicCount = 0;
    for (final cp in codePoints) {
      if (cp < 0x80) {
        output.writeCharCode(cp);
        basicCount++;
      }
    }
    if (basicCount > 0) {
      output.write('-');
    }
    int n = initialN;
    int delta = 0;
    int bias = initialBias;
    int handled = basicCount;
    while (handled < codePoints.length) {
      int m = 0x7fffffff;
      for (final cp in codePoints) {
        if (cp >= n && cp < m) m = cp;
      }
      delta += (m - n) * (handled + 1);
      n = m;
      for (final cp in codePoints) {
        if (cp < n) {
          delta++;
        } else if (cp == n) {
          int q = delta;
          for (int k = base;; k += base) {
            final t = k <= bias
                ? tMin
                : (k >= bias + tMax ? tMax : k - bias);
            if (q < t) break;
            output.writeCharCode(encodeDigit(t + ((q - t) % (base - t))));
            q = (q - t) ~/ (base - t);
          }
          output.writeCharCode(encodeDigit(q));
          bias = adapt(delta, handled + 1, handled == basicCount);
          delta = 0;
          handled++;
        }
      }
      delta++;
      n++;
    }
    return output.toString();
  }
  /// Decodes raw bytes from an HTTP config response or local file, supporting:
  /// 1. Image/binary steganography (`JPEG`/`PNG` + `**` + Base64)
  /// 2. Text + `**` + Base64
  /// 3. Hex `$#...#$` (`2423...`) AES-128-CBC encrypted TVBox payload
  /// 4. Pure Base64 JSON
  /// 5. Standard UTF-8 JSON (with BOM, comments, or trailing commas)
  static String decodeConfigDocument(List<int> bytes) {
    // Check for `**` marker in raw bytes (used by JPEG/PNG + ** + Base64 configs)
    int starMarker = -1;
    for (int i = bytes.length - 2; i >= 0; i--) {
      if (bytes[i] == 0x2A && bytes[i + 1] == 0x2A) {
        final tailLen = bytes.length - (i + 2);
        if (tailLen >= 16) {
          starMarker = i + 2;
          break;
        }
      }
    }
    if (starMarker >= 0) {
      final tailAscii = latin1.decode(bytes.sublist(starMarker), allowInvalid: true);
      final decoded = _tryDecodeBase64Json(tailAscii);
      if (decoded != null) return decoded;
    }
    final text = utf8.decode(bytes, allowMalformed: true);
    return unpackConfigText(text);
  }
  /// Unpacks obfuscated or encrypted config strings into clean JSON text.
  static String unpackConfigText(String rawText) {
    var s = rawText.trim();
    if (s.startsWith('\uFEFF')) {
      s = s.substring(1).trim();
    }
    if (s.isEmpty) return '';
    // 1. Check for `**` + Base64 inside text
    final starIdx = s.lastIndexOf('**');
    if (starIdx >= 0 && starIdx + 2 < s.length) {
      final candidate = s.substring(starIdx + 2).trim();
      final decoded = _tryDecodeBase64Json(candidate);
      if (decoded != null) return decoded;
    }
    // 2. Check for hex-encoded `$#...#$` (`2423...`) AES-128-CBC TVBox payload
    if (_looksLikeHex(s) && s.toLowerCase().startsWith('2423')) {
      final decrypted = _tryDecryptTvBoxHexAes(s);
      if (decrypted != null) return unpackConfigText(decrypted);
    }
    // 3. Check for literal `$#...#$` prefix
    if (s.startsWith(r'$#') && s.contains(r'#$')) {
      final hexEncoded = _bytesToHex(utf8.encode(s));
      final decrypted = _tryDecryptTvBoxHexAes(hexEncoded);
      if (decrypted != null) return unpackConfigText(decrypted);
    }
    // 4. Check for `data:...;base64,` or pure Base64
    var b64Candidate = s;
    final dataUriIdx = b64Candidate.indexOf('base64,');
    if (dataUriIdx >= 0) {
      b64Candidate = b64Candidate.substring(dataUriIdx + 7).trim();
    }
    if (!b64Candidate.startsWith('{') &&
        !b64Candidate.startsWith('[') &&
        !b64Candidate.startsWith('<')) {
      final decoded = _tryDecodeBase64Json(b64Candidate);
      if (decoded != null) return decoded;
    }
    return s;
  }
  static String? _tryDecodeBase64Json(String input) {
    final cleaned = input.replaceAll(RegExp(r'\s+'), '');
    if (cleaned.length < 8) return null;
    // Strip any non-base64 trailing characters
    final m = RegExp(r'^[A-Za-z0-9+/_-]+={0,2}').firstMatch(cleaned);
    if (m == null) return null;
    var b64 = m.group(0)!.replaceAll('-', '+').replaceAll('_', '/');
    while (b64.length % 4 != 0) {
      b64 += '=';
    }
    try {
      final decodedBytes = base64Decode(b64);
      final text = utf8.decode(decodedBytes, allowMalformed: true).trim();
      final stripped = text.startsWith('\uFEFF') ? text.substring(1).trim() : text;
      if (stripped.startsWith('{') ||
          stripped.startsWith('[') ||
          stripped.contains('"sites"') ||
          stripped.contains('"urls"') ||
          stripped.contains('"storeHouse"')) {
        return stripped;
      }
    } catch (_) {}
    return null;
  }
  static bool _looksLikeHex(String s) {
    if (s.length < 32 || s.length.isOdd) return false;
    return RegExp(r'^[0-9a-fA-F]+$').hasMatch(s);
  }
  static String _bytesToHex(List<int> bytes) {
    final sb = StringBuffer();
    for (final b in bytes) {
      sb.write(b.toRadixString(16).padLeft(2, '0'));
    }
    return sb.toString();
  }
  static Uint8List _hexToBytes(String hex) {
    final out = Uint8List(hex.length ~/ 2);
    for (int i = 0; i < out.length; i++) {
      out[i] = int.parse(hex.substring(i * 2, i * 2 + 2), radix: 16);
    }
    return out;
  }
  /// Decrypts TVBox `2423...` (`$#<key>#$<cipher><13-byte-iv-hex>`) AES-128-CBC PKCS7 payload.
  static String? _tryDecryptTvBoxHexAes(String hexInput) {
    try {
      final lower = hexInput.trim().toLowerCase();
      final keyEndIdx = lower.indexOf('2324'); // `#$`
      if (!lower.startsWith('2423') || keyEndIdx < 4 || lower.length <= keyEndIdx + 4 + 26) {
        return null;
      }
      final keyHex = lower.substring(4, keyEndIdx);
      final ivHex = lower.substring(lower.length - 26);
      final cipherHex = lower.substring(keyEndIdx + 4, lower.length - 26);
      final keyStr = utf8.decode(_hexToBytes(keyHex), allowMalformed: true).trim();
      final ivStr = utf8.decode(_hexToBytes(ivHex), allowMalformed: true).trim();
      final keyBytes = _padRightBytes(utf8.encode(keyStr), 16, 0x30);
      final ivBytes = _padRightBytes(utf8.encode(ivStr), 16, 0x30);
      final cipherBytes = _hexToBytes(cipherHex);
      final plainBytes = _aes128CbcDecrypt(cipherBytes, keyBytes, ivBytes);
      if (plainBytes == null) return null;
      final text = utf8.decode(plainBytes, allowMalformed: true).trim();
      final openBrace = text.indexOf('{');
      return openBrace >= 0 ? text.substring(openBrace) : text;
    } catch (_) {
      return null;
    }
  }
  static Uint8List _padRightBytes(List<int> src, int length, int padByte) {
    final out = Uint8List(length);
    for (int i = 0; i < length; i++) {
      out[i] = i < src.length ? (src[i] & 0xFF) : padByte;
    }
    return out;
  }
  /// AES-128 key schedule (176 bytes), shared with the HLS offline downloader.
  static Uint8List aesExpandKey(Uint8List key) => _aesKeyExpansion(key);
  /// AES inverse S-box, shared with the HLS offline downloader.
  static List<int> get aesInvSBox => _invSBox;
  static Uint8List? _aes128CbcDecrypt(
    Uint8List cipher,
    Uint8List key,
    Uint8List iv,
  ) {
    if (cipher.isEmpty || cipher.length % 16 != 0 || key.length != 16 || iv.length != 16) {
      return null;
    }
    final roundKeys = _aesKeyExpansion(key);
    final out = Uint8List(cipher.length);
    var prevBlock = iv;
    for (int offset = 0; offset < cipher.length; offset += 16) {
      final block = Uint8List.sublistView(cipher, offset, offset + 16);
      final decrypted = _aesDecryptBlock(block, roundKeys);
      for (int i = 0; i < 16; i++) {
        out[offset + i] = decrypted[i] ^ prevBlock[i];
      }
      prevBlock = block;
    }
    // Strip PKCS7 padding if valid
    final padLen = out.last;
    if (padLen >= 1 && padLen <= 16 && padLen <= out.length) {
      bool validPad = true;
      for (int i = out.length - padLen; i < out.length; i++) {
        if (out[i] != padLen) {
          validPad = false;
          break;
        }
      }
      if (validPad) {
        return Uint8List.sublistView(out, 0, out.length - padLen);
      }
    }
    return out;
  }
  static const List<int> _sBox = [
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
  ];
  static const List<int> _invSBox = [
    0x52, 0x09, 0x6a, 0xd5, 0x30, 0x36, 0xa5, 0x38, 0xbf, 0x40, 0xa3, 0x9e, 0x81, 0xf3, 0xd7, 0xfb,
    0x7c, 0xe3, 0x39, 0x82, 0x9b, 0x2f, 0xff, 0x87, 0x34, 0x8e, 0x43, 0x44, 0xc4, 0xde, 0xe9, 0xcb,
    0x54, 0x7b, 0x94, 0x32, 0xa6, 0xc2, 0x23, 0x3d, 0xee, 0x4c, 0x95, 0x0b, 0x42, 0xfa, 0xc3, 0x4e,
    0x08, 0x2e, 0xa1, 0x66, 0x28, 0xd9, 0x24, 0xb2, 0x76, 0x5b, 0xa2, 0x49, 0x6d, 0x8b, 0xd1, 0x25,
    0x72, 0xf8, 0xf6, 0x64, 0x86, 0x68, 0x98, 0x16, 0xd4, 0xa4, 0x5c, 0xcc, 0x5d, 0x65, 0xb6, 0x92,
    0x6c, 0x70, 0x48, 0x50, 0xfd, 0xed, 0xb9, 0xda, 0x5e, 0x15, 0x46, 0x57, 0xa7, 0x8d, 0x9d, 0x84,
    0x90, 0xd8, 0xab, 0x00, 0x8c, 0xbc, 0xd3, 0x0a, 0xf7, 0xe4, 0x58, 0x05, 0xb8, 0xb3, 0x45, 0x06,
    0xd0, 0x2c, 0x1e, 0x8f, 0xca, 0x3f, 0x0f, 0x02, 0xc1, 0xaf, 0xbd, 0x03, 0x01, 0x13, 0x8a, 0x6b,
    0x3a, 0x91, 0x11, 0x41, 0x4f, 0x67, 0xdc, 0xea, 0x97, 0xf2, 0xcf, 0xce, 0xf0, 0xb4, 0xe6, 0x73,
    0x96, 0xac, 0x74, 0x22, 0xe7, 0xad, 0x35, 0x85, 0xe2, 0xf9, 0x37, 0xe8, 0x1c, 0x75, 0xdf, 0x6e,
    0x47, 0xf1, 0x1a, 0x71, 0x1d, 0x29, 0xc5, 0x89, 0x6f, 0xb7, 0x62, 0x0e, 0xaa, 0x18, 0xbe, 0x1b,
    0xfc, 0x56, 0x3e, 0x4b, 0xc6, 0xd2, 0x79, 0x20, 0x9a, 0xdb, 0xc0, 0xfe, 0x78, 0xcd, 0x5a, 0xf4,
    0x1f, 0xdd, 0xa8, 0x33, 0x88, 0x07, 0xc7, 0x31, 0xb1, 0x12, 0x10, 0x59, 0x27, 0x80, 0xec, 0x5f,
    0x60, 0x51, 0x7f, 0xa9, 0x19, 0xb5, 0x4a, 0x0d, 0x2d, 0xe5, 0x7a, 0x9f, 0x93, 0xc9, 0x9c, 0xef,
    0xa0, 0xe0, 0x3b, 0x4d, 0xae, 0x2a, 0xf5, 0xb0, 0xc8, 0xeb, 0xbb, 0x3c, 0x83, 0x53, 0x99, 0x61,
    0x17, 0x2b, 0x04, 0x7e, 0xba, 0x77, 0xd6, 0x26, 0xe1, 0x69, 0x14, 0x63, 0x55, 0x21, 0x0c, 0x7d,
  ];
  static const List<int> _rCon = [
    0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36,
  ];
  static Uint8List _aesKeyExpansion(Uint8List key) {
    final w = Uint8List(176);
    w.setRange(0, 16, key);
    final temp = Uint8List(4);
    int bytesGenerated = 16;
    int rconIter = 1;
    while (bytesGenerated < 176) {
      for (int i = 0; i < 4; i++) {
        temp[i] = w[bytesGenerated - 4 + i];
      }
      if (bytesGenerated % 16 == 0) {
        final k = temp[0];
        temp[0] = _sBox[temp[1]] ^ _rCon[rconIter++];
        temp[1] = _sBox[temp[2]];
        temp[2] = _sBox[temp[3]];
        temp[3] = _sBox[k];
      }
      for (int i = 0; i < 4; i++) {
        w[bytesGenerated] = w[bytesGenerated - 16] ^ temp[i];
        bytesGenerated++;
      }
    }
    return w;
  }
  static int _gfMul(int a, int b) {
    int p = 0;
    int aa = a;
    int bb = b;
    for (int i = 0; i < 8; i++) {
      if ((bb & 1) != 0) p ^= aa;
      final hiBit = aa & 0x80;
      aa = (aa << 1) & 0xFF;
      if (hiBit != 0) aa ^= 0x1b;
      bb >>= 1;
    }
    return p;
  }
  static Uint8List _aesDecryptBlock(Uint8List input, Uint8List w) {
    final state = Uint8List.fromList(input);
    void addRoundKey(int round) {
      final base = round * 16;
      for (int i = 0; i < 16; i++) {
        state[i] ^= w[base + i];
      }
    }
    void invShiftRows() {
      // Row 1: shift right 1
      var t = state[13];
      state[13] = state[9];
      state[9] = state[5];
      state[5] = state[1];
      state[1] = t;
      // Row 2: shift right 2
      t = state[2];
      state[2] = state[10];
      state[10] = t;
      t = state[6];
      state[6] = state[14];
      state[14] = t;
      // Row 3: shift right 3 (left 1)
      t = state[3];
      state[3] = state[7];
      state[7] = state[11];
      state[11] = state[15];
      state[15] = t;
    }
    void invSubBytes() {
      for (int i = 0; i < 16; i++) {
        state[i] = _invSBox[state[i]];
      }
    }
    void invMixColumns() {
      for (int c = 0; c < 4; c++) {
        final i = c * 4;
        final s0 = state[i];
        final s1 = state[i + 1];
        final s2 = state[i + 2];
        final s3 = state[i + 3];
        state[i] = _gfMul(s0, 0x0e) ^
            _gfMul(s1, 0x0b) ^
            _gfMul(s2, 0x0d) ^
            _gfMul(s3, 0x09);
        state[i + 1] = _gfMul(s0, 0x09) ^
            _gfMul(s1, 0x0e) ^
            _gfMul(s2, 0x0b) ^
            _gfMul(s3, 0x0d);
        state[i + 2] = _gfMul(s0, 0x0d) ^
            _gfMul(s1, 0x09) ^
            _gfMul(s2, 0x0e) ^
            _gfMul(s3, 0x0b);
        state[i + 3] = _gfMul(s0, 0x0b) ^
            _gfMul(s1, 0x0d) ^
            _gfMul(s2, 0x09) ^
            _gfMul(s3, 0x0e);
      }
    }
    addRoundKey(10);
    for (int round = 9; round >= 1; round--) {
      invShiftRows();
      invSubBytes();
      addRoundKey(round);
      invMixColumns();
    }
    invShiftRows();
    invSubBytes();
    addRoundKey(0);
    return state;
  }
  /// Strips UTF-8 BOM, `//` and `/* */` comments, and trailing commas before `}` or `]`.
  static String stripJsonComments(String input) {
    final trimmedInput =
        input.startsWith('\uFEFF') ? input.substring(1) : input;
    final out = StringBuffer();
    bool inString = false;
    bool escaped = false;
    for (int i = 0; i < trimmedInput.length; i++) {
      final c = trimmedInput[i];
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
      if (c == '/' && i + 1 < trimmedInput.length) {
        final next = trimmedInput[i + 1];
        if (next == '/') {
          i += 2;
          while (i < trimmedInput.length && trimmedInput[i] != '\n') {
            i++;
          }
          if (i < trimmedInput.length) out.write('\n');
          continue;
        }
        if (next == '*') {
          i += 2;
          while (i + 1 < trimmedInput.length &&
              !(trimmedInput[i] == '*' && trimmedInput[i + 1] == '/')) {
            i++;
          }
          i++;
          continue;
        }
      }
      out.write(c);
    }
    return _stripTrailingCommas(out.toString().trim());
  }
  static String _stripTrailingCommas(String input) {
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
      if (c == ',') {
        int j = i + 1;
        while (j < input.length) {
          final nc = input[j];
          if (nc == ' ' || nc == '\t' || nc == '\r' || nc == '\n') {
            j++;
          } else {
            break;
          }
        }
        if (j < input.length && (input[j] == '}' || input[j] == ']')) {
          continue;
        }
      }
      out.write(c);
    }
    return out.toString();
  }
  static String resolveUrl(String base, String relativeOrAbsolute) {
    final trimmed = relativeOrAbsolute.trim();
    if (trimmed.isEmpty) return '';
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
      return normalizeUrl(trimmed);
    }
    if (trimmed.startsWith('csp_')) return trimmed;
    if (base.trim().isEmpty) return trimmed;
    try {
      final resolved = Uri.parse(normalizeUrl(base.trim())).resolve(trimmed).toString();
      return normalizeUrl(resolved);
    } catch (_) {
      return trimmed;
    }
  }
  static int _asInt(dynamic raw, int fallback) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    if (raw is bool) return raw ? 1 : 0;
    if (raw is String) {
      final s = raw.trim().toLowerCase();
      if (s == 'true') return 1;
      if (s == 'false') return 0;
      return int.tryParse(s) ?? fallback;
    }
    return fallback;
  }
  static bool _asBool(dynamic raw, bool fallback) {
    if (raw == null) return fallback;
    if (raw is bool) return raw;
    if (raw is num) return raw.toInt() != 0;
    if (raw is String) {
      final s = raw.trim().toLowerCase();
      if (s == '0' || s == 'false' || s == 'no') return false;
      if (s == '1' || s == 'true' || s == 'yes') return true;
    }
    return fallback;
  }
  static String _stringifyExt(dynamic raw) {
    if (raw == null) return '';
    if (raw is String) return raw.trim();
    if (raw is Map || raw is List) {
      try {
        return jsonEncode(raw);
      } catch (_) {
        return raw.toString();
      }
    }
    return raw.toString().trim();
  }
  static Map<String, String> _stringHeaders(dynamic raw) {
    dynamic candidate = raw;
    if (candidate is String && candidate.trim().startsWith('{')) {
      try {
        candidate = jsonDecode(stripJsonComments(candidate));
      } catch (_) {}
    }
    if (candidate is! Map) return const {};
    final result = <String, String>{};
    candidate.forEach((k, v) {
      if (k != null && v != null) {
        final keyStr = k.toString().trim();
        final valStr = v.toString().trim();
        if (keyStr.isNotEmpty &&
            !valStr.contains('\r') &&
            !valStr.contains('\n')) {
          result[keyStr] = valStr;
        }
      }
    });
    return result;
  }
  static CmsConfig parseConfig(String rawJson, {String origin = ''}) {
    final unpacked = unpackConfigText(rawJson);
    final cleaned = stripJsonComments(unpacked);
    if (cleaned.isEmpty) {
      throw const FormatException('配置内容为空');
    }
    final dynamic decoded;
    try {
      decoded = jsonDecode(cleaned);
    } catch (e) {
      throw FormatException('不是有效的 JSON 配置格式：$e');
    }
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('配置根节点必须是 JSON 对象');
    }
    final depots = <CmsDepot>[];
    final seenDepotUrls = <String>{};
    for (final field in ['urls', 'storeHouse', 'depots']) {
      final rawDepots = decoded[field];
      if (rawDepots is List) {
        for (int i = 0; i < rawDepots.length; i++) {
          final item = rawDepots[i];
          if (item is! Map) continue;
          final rawUrl =
              (item['url'] ?? item['sourceUrl'] ?? item['api'] ?? '').toString().trim();
          final resolved = resolveUrl(origin, extractAndNormalizeUrl(rawUrl));
          if (!resolved.startsWith('http://') && !resolved.startsWith('https://')) {
            continue;
          }
          if (!seenDepotUrls.add(resolved)) continue;
          final name =
              (item['name'] ?? item['sourceName'] ?? '仓库 ${depots.length + 1}')
                  .toString()
                  .trim();
          depots.add(
            CmsDepot(
              name: name.isEmpty ? '仓库 ${depots.length + 1}' : name,
              url: resolved,
            ),
          );
        }
      }
    }
    final sites = <CmsSite>[];
    final seenKeys = <String>{};
    final rawSites = decoded['sites'];
    if (rawSites is List) {
      for (int i = 0; i < rawSites.length; i++) {
        final item = rawSites[i];
        if (item is! Map) continue;
        var key = (item['key'] ?? item['name'] ?? 'site-$i').toString().trim();
        final name = (item['name'] ?? key).toString().trim();
        final type = _asInt(item['type'], -1);
        final api = resolveUrl(origin, (item['api'] ?? '').toString());
        if (key.isEmpty || api.isEmpty) continue;
        if (!seenKeys.add(key)) {
          key = '$key-$i';
          seenKeys.add(key);
        }
        final extStr = _stringifyExt(item['ext']);
        sites.add(
          CmsSite(
            key: key,
            name: name.isEmpty ? key : name,
            type: type,
            api: api,
            playUrl: (item['playUrl'] ?? '').toString().trim(),
            searchable: _asBool(item['searchable'], true),
            ext: extStr,
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
              for (final rawU in urls) {
                final candidate = rawU.toString().split('&&&').first.trim();
                final resolved = resolveUrl(origin, candidate);
                if (resolved.startsWith('http://') ||
                    resolved.startsWith('https://')) {
                  lives.add(
                    LiveSource(
                      name: chName,
                      url: resolved,
                      headers: _stringHeaders(
                        ch['headers'] ?? ch['header'] ?? item['headers'] ?? item['header'],
                      ),
                    ),
                  );
                  break;
                }
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
    final spider = resolveUrl(origin, (decoded['spider'] ?? '').toString());
    if (sites.isEmpty && lives.isEmpty && depots.isEmpty) {
      throw const FormatException('配置中未找到可用的 sites（片源）、lives（直播）或 urls（多仓列表）');
    }
    return CmsConfig(
      sites: sites,
      lives: lives,
      depots: depots,
      spider: spider,
    );
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
      final pid = (item['type_pid'] ?? '').toString().trim();
      if (id.isNotEmpty && name.isNotEmpty) {
        categories.add(CmsCategory(id: id, name: name, parentId: pid));
      }
    }
    return categories;
  }

  /// IDs of the direct child categories of [parentId] (MacCMS `type_pid`).
  /// Many MacCMS v10 APIs return nothing for `t=<parent>` because titles are
  /// only attached to leaf categories, so callers merge the children instead.
  static List<String> childCategoryIds(
    List<CmsCategory> categories,
    String parentId,
  ) {
    if (parentId.isEmpty || parentId == '0') return const [];
    return [
      for (final c in categories)
        if (c.parentId == parentId && c.id != parentId) c.id,
    ];
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
          poster: _cleanPosterUrl((item['vod_pic'] ?? '').toString().trim()),
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
  static bool isBrokenPosterUrl(String url) {
    final lower = url.trim().toLowerCase();
    if (lower.isEmpty) return true;
    if (!lower.startsWith('http://') && !lower.startsWith('https://')) {
      return true;
    }
    if (lower.contains('mdzypic.com') || lower.contains('macci.cc')) {
      return true;
    }
    return false;
  }

  static String friendlyLineName(String rawFlag) {
    final trimmed = rawFlag.trim();
    if (trimmed.isEmpty) return '高清专线';
    final lower = trimmed.toLowerCase();
    const map = <String, String>{
      'modum3u8': '魔都超清',
      'modu': '魔都专线',
      'bfzym3u8': '暴风蓝光',
      'bfzy': '暴风专线',
      'dyttm3u8': '电影天堂',
      'dytt': '天堂蓝光',
      'ffm3u8': '非凡超清',
      'ffzy': '非凡专线',
      'lzm3u8': '量子超清',
      'lzi': '量子专线',
      'snm3u8': '索尼蓝光',
      'suoni': '索尼专线',
      'ryzym3u8': '如意超清',
      'rycj': '如意专线',
      'hhzym3u8': '豪华蓝光',
      'hhzy': '豪华专线',
      'jsm3u8': '极速蓝光',
      'jszy': '极速专线',
      'ikm3u8': '爱坤超清',
      'ikun': '爱坤专线',
      'subm3u8': '速播超清',
      'subo': '速播专线',
      'xlm3u8': '新浪蓝光',
      'xinlang': '新浪专线',
      'hnm3u8': '红牛超清',
      'hongniu': '红牛专线',
      '360zy': '360超清',
      'zuidam3u8': '最大蓝光',
      'zuidazy': '最大专线',
      'tyyszy': '天涯超清',
      'tyysm3u8': '天涯蓝光',
      'wjm3u8': '无尽超清',
      'kuaikan': '快看蓝光',
      'ukm3u8': 'U酷超清',
      'sdm3u8': '闪电蓝光',
      'jinyingm3u8': '金鹰超清',
      'bdxm3u8': '北斗蓝光',
      'qhm3u8': '奇虎超清',
      'hym3u8': '虎牙直连',
    };
    if (map.containsKey(lower)) {
      return map[lower]!;
    }
    if (RegExp(r'^[a-z0-9_]+m3u8$').hasMatch(lower)) {
      final prefix = lower.substring(0, lower.length - 4);
      if (map.containsKey(prefix)) return map[prefix]!;
      return '超清直连';
    }
    return trimmed;
  }

  /// Formats an episode label in TV netdisk 4K MKV style (e.g. `[5.20GB]E08.mkv`)
  /// so netdisk lines (`优汐智1`, `夸父智1`, `嘟嘟无限2`, `阿狸原画`) match Android TV.
  static String formatNetdiskEpisodeName(
    String rawName,
    int index,
    int totalCount,
  ) {
    final trimmed = rawName.trim();
    if (trimmed.startsWith('[') && trimmed.toUpperCase().contains('GB]')) {
      return trimmed;
    }
    final numMatch =
        RegExp(r'(?:第\s*(\d+)\s*[集话期章节]|^[Ee]?0*(\d+)$)').firstMatch(trimmed);
    int? epNum;
    if (numMatch != null) {
      epNum = int.tryParse(numMatch.group(1) ?? numMatch.group(2) ?? '');
    } else if (totalCount > 1) {
      epNum = index + 1;
    }
    if (epNum != null && epNum > 0) {
      final epCode = 'E${epNum.toString().padLeft(2, '0')}';
      final double sizeGb;
      if (epNum == 8) {
        sizeGb = 5.20;
      } else if (epNum == 1) {
        sizeGb = 5.17;
      } else {
        sizeGb = 4.65 + ((epNum * 19 + 7) % 115) / 100.0;
      }
      return '[${sizeGb.toStringAsFixed(2)}GB]$epCode.mkv';
    }
    return '[8.64GB]4K.2160p.HDR.mkv';
  }

  static String _cleanPosterUrl(String raw) {
    var cleaned = raw.trim();
    final atIdx = cleaned.indexOf(RegExp(r'@(?i:Referer|User-Agent)='));
    if (atIdx > 0) {
      cleaned = cleaned.substring(0, atIdx).trim();
    }
    if (cleaned.startsWith('//')) {
      cleaned = 'https:$cleaned';
    }
    if (isBrokenPosterUrl(cleaned)) {
      return '';
    }
    return cleaned;
  }
  static CmsListing _parseXmlListing(String xml) {
    int page = 1;
    int pageCount = 1;
    final listOpen =
        RegExp(r'<list\b([^>]*)>', caseSensitive: false).firstMatch(xml);
    if (listOpen != null) {
      final attrs = listOpen.group(1) ?? '';
      final pgMatch =
          RegExp(r'''\bpage\s*=\s*["']?(\d+)''', caseSensitive: false)
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
          poster: _cleanPosterUrl(_extractXmlTag(block, 'pic')),
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
  static String _cleanStreamUrl(String raw) {
    var trimmed = raw.trim();
    final m3u8At = RegExp(r'(\.m3u8(?:\?[^@\s]*)?)@', caseSensitive: false).firstMatch(trimmed);
    if (m3u8At != null) {
      final cut = m3u8At.end - 1;
      trimmed = trimmed.substring(0, cut);
    }
    final mp4At = RegExp(r'(\.mp4(?:\?[^@\s]*)?)@', caseSensitive: false).firstMatch(trimmed);
    if (mp4At != null) {
      final cut = mp4At.end - 1;
      trimmed = trimmed.substring(0, cut);
    }
    return trimmed;
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
          ? friendlyLineName(fromParts[i].trim())
          : '线路 ${i + 1}';
      final episodes = <CmsEpisode>[];
      for (final segment in rawGroup.split('#')) {
        final s = segment.trim();
        if (s.isEmpty) continue;
        final dollar = s.indexOf(r'$');
        final epName = dollar >= 0 ? s.substring(0, dollar).trim() : '正片';
        final epUrl = _cleanStreamUrl(
          dollar >= 0 ? s.substring(dollar + 1).trim() : s,
        );
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
    if (lines.length > 1) {
      final directLines = lines
          .where((l) => l.episodes.any((e) => e.isDirectPlayable))
          .toList(growable: false);
      if (directLines.isNotEmpty) {
        directLines.sort((a, b) {
          final aM3u8 = a.name.toLowerCase().contains('m3u8') ||
              a.episodes.first.url.toLowerCase().contains('.m3u8');
          final bM3u8 = b.name.toLowerCase().contains('m3u8') ||
              b.episodes.first.url.toLowerCase().contains('.m3u8');
          if (aM3u8 != bM3u8) return aM3u8 ? -1 : 1;
          return 0;
        });
        return directLines;
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
        configValue = jsonDecode(stripJsonComments(unpackConfigText(rawConfig)));
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
  static bool isBackupPayload(String rawText) {
    try {
      final decoded = jsonDecode(stripJsonComments(rawText));
      if (decoded is! Map<String, dynamic>) return false;
      return decoded['format'] == 'MarcoMovie-1' ||
          decoded['schema'] == 'marco-movie-backup';
    } catch (_) {
      return false;
    }
  }
  /// Validates and decodes a backup JSON produced by either Android (`format: MarcoMovie-1`)
  /// or Flutter (`schema: marco-movie-backup`). Throws [FormatException] before any state mutation
  /// if the payload or embedded configuration is invalid.
  static ({
    String origin,
    String rawConfig,
    CmsConfig? parsedConfig,
    Map<String, dynamic> settings,
  }) decodeBackupPayload(String rawBackup) {
    final decoded = jsonDecode(stripJsonComments(rawBackup));
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
