// Copyright (C) 2026 Pilipala. SPDX-License-Identifier: GPL-3.0-only
'use strict';
// Data-only interoperability reader. Never loads a JAR, DEX or native library.
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const MAX = 16 * 1024 * 1024;
const REVIEWED = 'e7375830bcd065b264239e1f0e7da4c99d75abf71e1b6edf50e225ae8b0f4b7e';
const fail = () => { throw Error('invalid_artifact'); };
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  crcTable[n] = c >>> 0;
}
function crc32(bytes) {
  let c = 0xffffffff;
  for (const byte of bytes) c = crcTable[(c ^ byte) & 255] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function adler32(bytes) {
  let a = 1, b = 0;
  for (let i = 0; i < bytes.length; i++) { a = (a + bytes[i]) % 65521; b = (b + a) % 65521; }
  return ((b << 16) | a) >>> 0;
}
function bounds(bytes, off, size) {
  if (!Number.isSafeInteger(off) || !Number.isSafeInteger(size) || off < 0 || size < 0 || off + size > bytes.length) fail();
}
function zipMember(bytes, target, budget = { bytes: 0, entries: 0 }) {
  if (!Buffer.isBuffer(bytes) || bytes.length > MAX || bytes.length < 22) fail();
  let end = -1;
  for (let p = bytes.length - 22; p >= Math.max(0, bytes.length - 65557); p--) {
    if (bytes.readUInt32LE(p) === 0x06054b50 && p + 22 + bytes.readUInt16LE(p + 20) === bytes.length) { end = p; break; }
  }
  if (end < 0 || bytes.readUInt32LE(end + 4) !== 0) fail();
  const count = bytes.readUInt16LE(end + 10), centralSize = bytes.readUInt32LE(end + 12), central = bytes.readUInt32LE(end + 16);
  if (count !== bytes.readUInt16LE(end + 8) || !count || count > 512 || central + centralSize !== end) fail();
  if ((budget.entries += count) > 512) fail();
  let p = central, chosen;
  const names = new Set(), regions = [];
  for (let i = 0; i < count; i++) {
    bounds(bytes, p, 46);
    if (bytes.readUInt32LE(p) !== 0x02014b50) fail();
    const flags = bytes.readUInt16LE(p + 8), method = bytes.readUInt16LE(p + 10), crc = bytes.readUInt32LE(p + 16);
    const packed = bytes.readUInt32LE(p + 20), size = bytes.readUInt32LE(p + 24);
    const nameLength = bytes.readUInt16LE(p + 28), extra = bytes.readUInt16LE(p + 30), comment = bytes.readUInt16LE(p + 32);
    const local = bytes.readUInt32LE(p + 42);
    bounds(bytes, p + 46, nameLength + extra + comment);
    if (flags & ~0x0808 || ![0, 8].includes(method) || bytes.readUInt16LE(p + 34) || size > MAX || packed > MAX) fail();
    if ((budget.bytes += size) > 32 * 1024 * 1024) fail();
    const rawName = bytes.subarray(p + 46, p + 46 + nameLength);
    const name = rawName.toString('utf8');
    if (!name || !Buffer.from(name).equals(rawName) || name.includes('\\') || name.includes('\0') || name.startsWith('/') || name.split('/').some(s => s === '..' || s === '.')) fail();
    if (names.has(name)) fail();
    names.add(name);
    bounds(bytes, local, 30);
    if (local >= central || bytes.readUInt32LE(local) !== 0x04034b50 || bytes.readUInt16LE(local + 6) !== flags || bytes.readUInt16LE(local + 8) !== method) fail();
    const localName = bytes.readUInt16LE(local + 26), localExtra = bytes.readUInt16LE(local + 28), data = local + 30 + localName + localExtra;
    bounds(bytes, local + 30, localName + localExtra);
    if (!bytes.subarray(local + 30, local + 30 + localName).equals(rawName) || data + packed > central) fail();
    if (!(flags & 8) && (bytes.readUInt32LE(local + 14) !== crc || bytes.readUInt32LE(local + 18) !== packed || bytes.readUInt32LE(local + 22) !== size)) fail();
    let finish = data + packed;
    if (flags & 8) {
      bounds(bytes, finish, 12);
      if (bytes.readUInt32LE(finish) === 0x08074b50) finish += 4;
      bounds(bytes, finish, 12);
      if (bytes.readUInt32LE(finish) !== crc || bytes.readUInt32LE(finish + 4) !== packed || bytes.readUInt32LE(finish + 8) !== size) fail();
      finish += 12;
    }
    if (finish > central) fail();
    regions.push([local, finish]);
    if (name === target) chosen = { data, packed, size, method, crc };
    p += 46 + nameLength + extra + comment;
  }
  if (p !== end || !chosen) fail();
  regions.sort((a, b) => a[0] - b[0]);
  for (let i = 1; i < regions.length; i++) if (regions[i][0] < regions[i - 1][1]) fail();
  const c = chosen, input = bytes.subarray(c.data, c.data + c.packed);
  let output;
  try { output = c.method === 0 ? Buffer.from(input) : zlib.inflateRawSync(input, { maxOutputLength: Math.max(1, c.size) }); }
  catch (_) { fail(); }
  if (output.length !== c.size || crc32(output) !== c.crc) fail();
  return output;
}
function unwrapGuard(bytes) {
  const result = Buffer.from(bytes);
  for (let i = 0; i < result.length; i++) if ([1, 2, 5, 6].includes(result[i])) result[i] ^= 7;
  return result;
}
class Dex {
  constructor(bytes) {
    this.bytes = bytes;
    bounds(bytes, 0, 112);
    if (bytes.length > MAX || !bytes.subarray(0, 8).equals(Buffer.from('dex\n035\0')) || this.u32(32) !== bytes.length || this.u32(36) !== 112 || this.u32(40) !== 0x12345678) fail();
    if (!crypto.createHash('sha1').update(bytes.subarray(32)).digest().equals(bytes.subarray(12, 32)) || adler32(bytes.subarray(12)) !== this.u32(8)) fail();
    this.strings = this.table(56, 4).map(p => this.string(this.u32(p)));
    this.types = this.table(64, 4).map(p => this.at(this.strings, this.u32(p)));
    this.protos = this.table(72, 12).map(p => [this.at(this.types, this.u32(p + 4)), this.u32(p + 8)]);
    this.fields = this.table(80, 8).map(p => [this.at(this.types, this.u16(p)), this.at(this.types, this.u16(p + 2)), this.at(this.strings, this.u32(p + 4))]);
    this.methods = this.table(88, 8).map(p => [this.at(this.types, this.u16(p)), this.at(this.protos, this.u16(p + 2)), this.at(this.strings, this.u32(p + 4))]);
  }
  at(values, index) { if (!Number.isInteger(index) || index < 0 || index >= values.length) fail(); return values[index]; }
  u16(p) { bounds(this.bytes, p, 2); return this.bytes.readUInt16LE(p); }
  u32(p) { bounds(this.bytes, p, 4); return this.bytes.readUInt32LE(p); }
  uleb(p) {
    let value = 0;
    for (let i = 0; i < 5; i++) {
      bounds(this.bytes, p + i, 1); const byte = this.bytes[p + i];
      if (i === 4 && byte > 15) fail();
      value += (byte & 127) * 2 ** (i * 7);
      if (!(byte & 128)) return [value, p + i + 1];
    }
    fail();
  }
  table(header, stride) {
    const count = this.u32(header), offset = this.u32(header + 4);
    if (count > 262144) fail();
    bounds(this.bytes, offset, count * stride);
    return Array.from({ length: count }, (_, i) => offset + i * stride);
  }
  string(p) {
    let length; [length, p] = this.uleb(p);
    if (length > 1024 * 1024) fail();
    const chars = [];
    while (chars.length < length) {
      bounds(this.bytes, p, 1); const first = this.bytes[p++];
      if (first >= 1 && first < 128) chars.push(first);
      else if ((first & 224) === 192) {
        bounds(this.bytes, p, 1); const next = this.bytes[p++];
        const value = ((first & 31) << 6) | (next & 63);
        if ((next & 192) !== 128 || value < 128 && value !== 0) fail();
        chars.push(value);
      } else if ((first & 240) === 224) {
        bounds(this.bytes, p, 2); const a = this.bytes[p++], b = this.bytes[p++];
        const value = ((first & 15) << 12) | ((a & 63) << 6) | (b & 63);
        if ((a & 192) !== 128 || (b & 192) !== 128 || value < 2048) fail();
        chars.push(value);
      } else fail();
    }
    bounds(this.bytes, p, 1); if (this.bytes[p] !== 0) fail();
    let result = '';
    for (let i = 0; i < chars.length; i += 4096) result += String.fromCharCode(...chars.slice(i, i + 4096));
    return result;
  }
  reviewedCode(className, methodName, expectedHash) {
    const classes = this.table(96, 32).filter(p => this.at(this.types, this.u32(p)) === className);
    if (classes.length !== 1) fail();
    let p = this.u32(classes[0] + 24), counts = [], selected = null;
    if (!p) fail();
    for (let i = 0; i < 4; i++) { let n; [n, p] = this.uleb(p); if (n > 65536) fail(); counts.push(n); }
    for (let i = 0; i < counts[0] + counts[1]; i++) { [, p] = this.uleb(p); [, p] = this.uleb(p); }
    for (const count of counts.slice(2)) {
      let index = 0;
      for (let i = 0; i < count; i++) {
        let delta, code; [delta, p] = this.uleb(p); [, p] = this.uleb(p); [code, p] = this.uleb(p); index += delta;
        const [owner, , name] = this.at(this.methods, index);
        if (owner === className && name === methodName) { if (selected !== null || !code) fail(); selected = code; }
      }
    }
    if (selected === null) fail();
    const size = this.u32(selected + 12);
    if (!size || size > 65536) fail();
    bounds(this.bytes, selected, 16 + size * 2);
    if (sha(this.bytes.subarray(selected, selected + 16 + size * 2)) !== expectedHash) fail();
    return { offset: selected, size };
  }
  reviewedShortArray(className, expectedHash, fieldName, payloadUnit, count) {
    const { offset, size } = this.reviewedCode(className, '<clinit>', expectedHash);
    if (!Number.isInteger(payloadUnit) || payloadUnit < 10 || !Number.isInteger(count) || count < 1 || count > 4096 || payloadUnit + 4 + count > size || this.u16(offset + 2) !== 0) fail();
    const start = offset + 16;
    // The reviewed initializer starts by constructing v0:[S, filling it from a
    // literal payload, then storing v0 in this class's exact static short field.
    // Read those data bytes only; never evaluate any other initializer code.
    if (this.u16(start) !== 0x0013 || this.u16(start + 2) !== count || this.u16(start + 4) !== 0x0023 ||
        this.at(this.types, this.u16(start + 6)) !== '[S' || this.u16(start + 8) !== 0x0026 ||
        4 + this.u32(start + 10) !== payloadUnit || this.u16(start + 14) !== 0x0069) fail();
    const field = this.at(this.fields, this.u16(start + 16));
    if (field[0] !== className || field[1] !== '[S' || field[2] !== fieldName) fail();
    const payload = start + payloadUnit * 2;
    if (this.u16(payload) !== 0x0300 || this.u16(payload + 2) !== 2 || this.u32(payload + 4) !== count) fail();
    return Array.from({ length: count }, (_, i) => this.u16(payload + 8 + i * 2));
  }
  reviewedLiteral(className, methodName, expectedHash, unit, fieldName) {
    const { offset: selected, size } = this.reviewedCode(className, methodName, expectedHash);
    if (!Number.isInteger(unit) || unit < 0 || unit + 1 >= size) fail();
    // Exact reviewed code position, not an interpreter or a search for URL-like strings.
    const instruction = selected + 16 + unit * 2, word = this.u16(instruction);
    if ((word & 255) !== 0x1a) fail();
    if (fieldName) {
      if (unit + 3 >= size) fail();
      const write = this.u16(instruction + 4), field = this.at(this.fields, this.u16(instruction + 6));
      if ((write & 255) !== 0x5b || ((write >>> 8) & 15) !== word >>> 8 ||
          write >>> 12 !== this.u16(selected) - this.u16(selected + 2) ||
          field[0] !== className || field[1] !== 'Ljava/lang/String;' || field[2] !== fieldName) fail();
    }
    return this.at(this.strings, this.u16(instruction + 2));
  }
  literalFields(className) {
    const classes = this.table(96, 32).filter(p => this.at(this.types, this.u32(p)) === className);
    if (classes.length !== 1) fail();
    let p = this.u32(classes[0] + 24), count = [];
    if (!p) fail();
    for (let i = 0; i < 4; i++) { let value; [value, p] = this.uleb(p); if (value > 65536) fail(); count.push(value); }
    for (let i = 0; i < count[0] + count[1]; i++) { [, p] = this.uleb(p); [, p] = this.uleb(p); }
    let method = 0, code = null;
    for (let i = 0; i < count[2]; i++) {
      let delta, offset; [delta, p] = this.uleb(p); [, p] = this.uleb(p); [offset, p] = this.uleb(p); method += delta;
      const [owner, proto, name] = this.at(this.methods, method);
      if (owner === className && name === '<init>' && proto[0] === 'V' && proto[1] === 0) { if (code !== null) fail(); code = offset; }
    }
    if (!code) fail();
    const registers = this.u16(code), inputs = this.u16(code + 2), size = this.u32(code + 12);
    if (inputs !== 1 || this.u16(code + 6) || !size || size > 256) fail();
    bounds(this.bytes, code + 16, size * 2);
    const words = Array.from({ length: size }, (_, i) => this.u16(code + 16 + i * 2));
    if (words[size - 1] !== 0x000e) fail();
    const values = new Map(), fields = Object.create(null);
    let index = 0;
    while (index < size) {
      const word = words[index], op = word & 255;
      if (op === 0x70 && index === 0 && index + 2 < size) {
        const [owner, proto, name] = this.at(this.methods, words[index + 1]);
        if (!['Ljava/lang/Object;', 'Lcom/github/catvod/crawler/Spider;'].includes(owner) || name !== '<init>' || proto[0] !== 'V' || proto[1] !== 0 || word >>> 12 !== 1 || (words[index + 2] & 15) !== registers - 1) fail();
        index += 3;
      } else if ([0x1a, 0x1b].includes(op) && index + (op === 0x1b ? 2 : 1) < size) {
        const n = words[index + 1] + (op === 0x1b ? words[index + 2] * 65536 : 0);
        const register = word >>> 8;
        if (register >= registers) fail();
        values.set(register, this.at(this.strings, n)); index += op === 0x1b ? 3 : 2;
      } else if (op === 0x5b && index + 1 < size) {
        const source = (word >>> 8) & 15, object = word >>> 12;
        const [owner, type, name] = this.at(this.fields, words[index + 1]);
        if (owner !== className || type !== 'Ljava/lang/String;' || object !== registers - 1 || !values.has(source) || Object.hasOwn(fields, name)) fail();
        fields[name] = values.get(source); index += 2;
      } else if (word === 0x000e && index === size - 1) index++;
      else fail();
    }
    return fields;
  }
}
function extractKnownPlugin(bytes, adapter = 'dm84-v1') {
  if (!Buffer.isBuffer(bytes) || bytes.length > MAX) fail();
  if (sha(bytes) !== REVIEWED) throw Error('guard_artifact_unsupported');
  const budget = { bytes: 0, entries: 0 };
  const payload = zipMember(bytes, 'assets/ftyshinidie.guard', budget);
  const dex = zipMember(unwrapGuard(payload), 'classes.dex', budget);
  const reader = new Dex(dex);
  function endpoint(value) {
    if (typeof value !== 'string' || value.length > 2048) fail();
    let url; try { url = new URL(value); } catch (_) { fail(); }
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password || url.hash) fail();
    return url;
  }
  if (adapter === 'auete-v1') {
    const className = 'Lcom/github/catvod/spider/Auete;';
    const origin = endpoint(reader.reviewedLiteral(className, '<init>',
      '3841e63ceeae29689e3b5d5b7f92b4d8177278dad5084818c2476df489128c17', 3, 'yq'));
    const mediaBase = endpoint(reader.reviewedLiteral(className, 'playerContent',
      '80e9d52800eae7b12c2278b2dd00e1d4677252d677e9185b594ef23ecd53b6b8', 299));
    if (origin.pathname !== '/' || origin.search || mediaBase.search || !mediaBase.pathname.endsWith('/')) fail();
    return { origin: origin.origin, mediaBase: mediaBase.href,
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36' };
  }
  if (adapter === 'appgz-v1') {
    const className = 'Lcom/github/catvod/spider/Appgz;';
    for (const [method, hash] of Object.entries({
      '<init>':'e2ba5a4a37b1589bab71e7901d71d012f27e9c31230b39229752158d90907b4a',
      'N':'2cb95ba3ee3db3f7d155ae38bdd39d8b2d4173889c8a1ee090ad9f243d620942',
      'tF':'b0a670647f413a02a78fc1629ae8bfe0b909da50978e7dc1d843f91f0e7a7210',
      'init':'e03aceb447f2bf177f591f8faa58665755fa1e93788d04f1bfcee0f8ac56c9c3',
      'playerContent':'627d2eb2e82b2533258475479ed3757463116f4bf1283fe9f26da5bef49ee48a'
    })) reader.reviewedCode(className, method, hash);
    const values = reader.reviewedShortArray(className, '73ceb72abb62d4326e733c8d9659e17aef072981e30718f346bf2f657bb83e6e', 'short', 38, 3104);
    const decode = (start, length, mask) => String.fromCharCode(...values.slice(start, start + length).map(value => value ^ mask));
    const origin = endpoint(decode(1206,24,2160));
    if (origin.pathname !== '/' || origin.search) fail();
    const config = {origin:origin.origin,privateKey:decode(16,848,1726),publicKey:decode(864,216,1351),
      signatureSuffix:decode(1508,36,604),utdidKey:decode(1857,44,3163),headers:{
        'User-Agent':decode(1090,13,2573),'Version':decode(1110,7,2770),'PackageName':decode(1128,44,1606),
        'Ver':decode(1175,7,1803),'api-ver':decode(1175,7,1803),'code':decode(1193,6,2818)}};
    if (![config.privateKey,config.publicKey].every(v=>/^[A-Za-z0-9+/]+={0,2}$/.test(v)) ||
        Object.values(config.headers).some(v=>!v||/[\r\n\0]/.test(v))) fail();
    return config;
  }
  if (adapter === 'bttwoo-v1') {
    const className = 'Lcom/github/catvod/spider/Bttwoo;';
    reader.reviewedCode(className, '<init>', 'c47d9c1c569855288c1bf15c852120bb3c99c9bee1ccba4d9c8d93088f61094b');
    reader.reviewedCode(className, 'encodePlayKey', '5b7309680b6db8a307e62cb99b055d4130e71a278cbdf092c830542cad3cdb8e');
    const values = reader.reviewedShortArray(className, 'b4511e08ff36b2c803be31a2dd8021f2a049fbabc587e805919e15b45ca7c284', 'short', 10, 686);
    const headerValues = reader.reviewedShortArray('Lcom/github/catvod/spider/merge/ME;', '3dc0b269b870de31f4fdfa39e54778e8cc5d9cfb6d83c8903dd80e935ae1227e', 'short', 316, 1667);
    const decode = (array, start, length, mask) => String.fromCharCode(...array.slice(start, start + length).map(value => value ^ mask));
    const origin = endpoint(decode(values, 0, 23, 2303)), playKey = decode(values, 24, 20, 3066), userAgent = decode(headerValues, 1048, 117, 1121);
    if (origin.pathname !== '/' || origin.search || !/^[\x21-\x7e]{20}$/.test(playKey) || /[\r\n]/.test(userAgent)) fail();
    return { origin: origin.href, playKey, userAgent };
  }
  if (adapter !== 'dm84-v1') fail();
  const fields = reader.literalFields('Lcom/github/catvod/spider/Dm84;');
  if (Object.keys(fields).length !== 2) fail();
  const origin = endpoint(fields.yq), parser = endpoint(fields.SN);
  if (origin.pathname !== '/' || origin.search) fail();
  return { origin: origin.origin, parser: parser.href, parserOrigin: parser.origin,
    userAgent: 'Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36' };
}
module.exports = { extractKnownPlugin, zipMember, unwrapGuard, Dex, crc32, adler32, MAX };
