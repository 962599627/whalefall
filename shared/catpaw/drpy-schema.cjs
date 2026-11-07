// Copyright (C) 2026 Pilipala. SPDX-License-Identifier: GPL-3.0-only
'use strict';
const HASH = /^[a-f0-9]{64}$/;
const RUNTIME = 'drpy2-ca23ffdb-1';
const MAX_MANIFEST = 16 * 1024 * 1024;
const MAX_RESULT = 6 * 1024 * 1024;
const plain = value => value && typeof value === 'object' && !Array.isArray(value);
function validateManifest(value) {
  const allowed = new Set(['kind','version','runtime','sourceId','origin','rule','headers','requestTimeoutMs']);
  if (!plain(value) || Object.keys(value).some(key => !allowed.has(key)) ||
      value.kind !== 'drpy' || value.version !== 1 || value.runtime !== RUNTIME ||
      !HASH.test(value.sourceId) || typeof value.rule !== 'string' || !value.rule.trim() ||
      Buffer.byteLength(value.rule) > MAX_RESULT) throw Error('drpy_manifest');
  let origin;
  try { origin = new URL(value.origin); } catch (_) { throw Error('drpy_origin'); }
  if (!['http:','https:','pkg:'].includes(origin.protocol) || !origin.hostname || origin.username ||
      origin.password || origin.hash) throw Error('drpy_origin');
  if (!plain(value.headers) || Object.keys(value.headers).length > 64) throw Error('drpy_headers');
  for (const [key,item] of Object.entries(value.headers)) {
    if (!/^[!#$%&'*+.^_`|~0-9a-z-]+$/i.test(key) || typeof item !== 'string' ||
        item.length > 8192 || /[\r\n\0]/.test(item) || /^(host|content-length|connection|transfer-encoding)$/i.test(key)) {
      throw Error('drpy_headers');
    }
  }
  const timeout = value.requestTimeoutMs ?? 10000;
  if (!Number.isInteger(timeout) || timeout < 100 || timeout > 15000) throw Error('drpy_timeout');
  return {...value, requestTimeoutMs:timeout};
}
function validateCall(value) {
  if (!plain(value) || Object.keys(value).some(key => !['version','method','args'].includes(key)) ||
      value.version !== 1 || !Array.isArray(value.args)) throw Error('drpy_call');
  const a = value.args;
  const text = v => typeof v === 'string' && v.length <= 256 * 1024;
  const page = v => Number.isSafeInteger(v) && v > 0 && v < 1000000;
  let ok = false;
  switch (value.method) {
    case 'home': ok = a.length === 1 && typeof a[0] === 'boolean'; break;
    case 'homeVod': ok = a.length === 0; break;
    case 'category': ok = a.length === 4 && text(a[0]) && page(a[1]) && typeof a[2] === 'boolean' && plain(a[3]); break;
    case 'search': ok = a.length === 3 && text(a[0]) && typeof a[1] === 'boolean' && page(a[2]); break;
    case 'detail': ok = a.length === 1 && text(a[0]); break;
    case 'play': ok = a.length === 3 && text(a[0]) && text(a[1]) && Array.isArray(a[2]) && a[2].every(text); break;
  }
  if (!ok) throw Error('drpy_method_arguments');
  return value;
}
module.exports = {HASH,RUNTIME,MAX_MANIFEST,MAX_RESULT,validateManifest,validateCall};
