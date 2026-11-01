"""Sync published Marco Movie preview releases into an isolated static mirror directory.

Copyright (C) 2026 Pilipala. SPDX-License-Identifier: GPL-3.0-only.
Only official release assets are accepted; all downloads are checked before writing the feed.
"""
import argparse
import hashlib
import html
import io
import json
import os
from pathlib import Path
import re
import struct
import urllib.request
import zipfile

REPO = 'Pilipala9919/MarcoMovie'
BASE = 'https://marcotv.wlj9919.top/movie/'
LIMIT = 25 * 1024 * 1024


def fetch(url, limit=LIMIT):
    headers = {'User-Agent': 'MarcoMovie-Mirror', 'Accept-Encoding': 'identity'}
    if url.startswith('https://api.github.com/') and os.environ.get('GITHUB_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GITHUB_TOKEN']
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Download exceeds limit')
    return data


def apk_identity(apk):
    """Read literal package/version/minSdk from the actual binary APK manifest."""
    with zipfile.ZipFile(io.BytesIO(apk)) as z:
        entry = z.getinfo('AndroidManifest.xml')
        if entry.file_size > 1024 * 1024:
            raise ValueError('Manifest too large')
        data = z.read(entry)
    if struct.unpack_from('<H', data)[0] != 3:
        raise ValueError('Expected binary Android manifest')
    strings, values = [], {}
    offset = 8
    while offset < len(data):
        kind, header, size = struct.unpack_from('<HHI', data, offset)
        if size < header or header < 8 or offset + size > len(data):
            raise ValueError('Invalid XML chunk')
        if kind == 1:
            count, _, flags, start = struct.unpack_from('<IIII', data, offset + 8)
            if count > 65536 or header + count * 4 > size:
                raise ValueError('Invalid string pool')
            for i in range(count):
                pos = offset + start + struct.unpack_from('<I', data, offset + header + 4 * i)[0]
                if flags & 256:
                    def length8(p):
                        n = data[p]
                        return (((n & 127) << 8) | data[p + 1], p + 2) if n & 128 else (n, p + 1)
                    _, pos = length8(pos)
                    length, pos = length8(pos)
                    strings.append(data[pos:pos + length].decode('utf-8'))
                else:
                    length = struct.unpack_from('<H', data, pos)[0]; pos += 2
                    if length & 32768:
                        length = ((length & 32767) << 16) | struct.unpack_from('<H', data, pos)[0]; pos += 2
                    strings.append(data[pos:pos + length * 2].decode('utf-16le'))
        elif kind == 0x102:
            name = strings[struct.unpack_from('<I', data, offset + 20)[0]]
            start, stride, count = struct.unpack_from('<HHH', data, offset + 24)
            if stride < 20 or 16 + start + stride * count > size:
                raise ValueError('Invalid attributes')
            if name in ('manifest', 'uses-sdk'):
                for i in range(count):
                    pos = offset + 16 + start + i * stride
                    _, key, raw, _, _, value_type, value = struct.unpack_from('<IIIHBBI', data, pos)
                    values[(name, strings[key])] = strings[raw] if raw != 0xffffffff else strings[value] if value_type == 3 else value
        offset += size
    result = {'package': values[('manifest', 'package')], 'versionName': values[('manifest', 'versionName')],
              'versionCode': int(values[('manifest', 'versionCode')]), 'minSdk': int(values[('uses-sdk', 'minSdkVersion')])}
    if result['package'] != 'com.pilipala.marcomovie' or result['minSdk'] < 23 or result['versionCode'] <= 0:
        raise ValueError('Wrong application identity')
    return result


def choose(releases):
    valid = [r for r in releases if not r.get('draft') and r.get('published_at') and r.get('prerelease')
             and re.fullmatch(r'v\d+\.\d+\.\d+-preview', r.get('tag_name', ''))]
    if not valid:
        raise ValueError('No published preview release')
    return max(valid, key=lambda r: tuple(map(int, r['tag_name'][1:-8].split('.'))))


def asset(release, name, output):
    matches = [a for a in release['assets'] if a['name'] == name and a['state'] == 'uploaded']
    if len(matches) != 1:
        raise ValueError('Missing or duplicate asset: ' + name)
    info = matches[0]
    expected = f'https://github.com/{REPO}/releases/download/{release["tag_name"]}/{name}'
    if info['browser_download_url'] != expected or not 0 < info['size'] <= LIMIT:
        raise ValueError('Unexpected asset URL or size')
    sha_url = expected + '.sha256'
    checksums = [a for a in release['assets'] if a['name'] == name + '.sha256' and a['state'] == 'uploaded' and a['browser_download_url'] == sha_url]
    if len(checksums) != 1:
        raise ValueError('Missing official checksum')
    checksum = fetch(sha_url, 4096).decode('ascii').strip().split()
    if len(checksum) != 2 or checksum[1].lstrip('*') != name or not re.fullmatch('[0-9a-f]{64}', checksum[0]):
        raise ValueError('Invalid checksum file')
    local = output / name
    body = local.read_bytes() if local.exists() else fetch(expected)
    digest = hashlib.sha256(body).hexdigest()
    if len(body) != info['size'] or digest != checksum[0] or info.get('digest') not in (None, 'sha256:' + digest):
        raise ValueError('Release digest mismatch: ' + name)
    return body, digest


def sync(output):
    release = choose(json.loads(fetch(f'https://api.github.com/repos/{REPO}/releases?per_page=100', 4 * 1024 * 1024)))
    version = release['tag_name'][1:-8]
    apk_name, source_name = f'MarcoMovie-{version}-preview.apk', f'MarcoMovie-{version}-source.zip'
    apk, sha = asset(release, apk_name, output)
    source, source_sha = asset(release, source_name, output)
    identity = apk_identity(apk)
    if identity['versionName'] != version:
        raise ValueError('APK version does not match release')
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        if archive.testzip() is not None:
            raise ValueError('Source ZIP failed CRC')
        license_text = archive.read(f'MarcoMovie-{version}/LICENSE')
        notice = archive.read(f'MarcoMovie-{version}/NOTICE')
    feed = dict(schema=1, versionCode=identity['versionCode'], versionName=version, minSdk=identity['minSdk'],
                size=len(apk), sha256=sha, url=BASE + apk_name, prerelease=True, notes=(release.get('body') or '')[:8000])
    feed_path = output / 'updates/preview.json'
    if feed_path.exists():
        previous = json.loads(feed_path.read_text(encoding='utf-8'))
        if previous['versionCode'] > feed['versionCode']:
            raise ValueError('Refusing to roll the mirror back')
        if previous['versionCode'] == feed['versionCode'] and previous['sha256'] != sha:
            raise ValueError('Refusing to replace a published version')
    escaped = html.escape
    page = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Marco Movie · Pilipala</title><style>body{{margin:0;background:#101013;color:#f5f5f7;font:16px/1.8 system-ui,sans-serif}}main{{max-width:900px;margin:auto;padding:64px 24px}}h1{{font-size:48px;letter-spacing:-2px}}.muted{{color:#a1a1aa}}.card{{background:#232329;border:1px solid #37373f;border-radius:28px;padding:28px;margin:24px 0}}a{{color:#9ac6ff}}.button{{display:inline-block;background:#f5f5f7;color:#111;padding:12px 24px;border-radius:18px;text-decoration:none;margin:6px 12px 6px 0}}pre{{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}}code{{overflow-wrap:anywhere}}</style>
<main><p class="muted">PILIPALA · ANDROID TV</p><h1>Marco Movie</h1><p>为遥控器和大屏幕设计的播放器。</p>
<div class="card"><h2>{version} Preview</h2><p>Android 6.0+ · ARM 32 位 · {len(apk)/1024/1024:.1f} MB</p>
<a class="button" href="{apk_name}">下载安装包</a><a class="button" href="{source_name}">下载同版本源码</a>
<p><a href="{apk_name}.sha256">校验文件</a> · <a href="https://github.com/{REPO}/releases/tag/{release['tag_name']}">GitHub 发布页</a></p>
<p class="muted">SHA-256<br><code>{sha}</code></p></div>
<div class="card"><h2>本次更新</h2><pre>{escaped(feed['notes'])}</pre></div>
<p>本产品仅为工具，不内置、不提供、不托管任何影视资源或配置地址。内容与配置请自行合法获取。</p>
<p>作者：Pilipala · <a href="LICENSE">GPL-3.0-only</a> · <a href="NOTICE">署名与许可</a> · <a href="/">Marco TV</a></p></main></html>'''
    files = {apk_name: apk, apk_name + '.sha256': (sha + '  ' + apk_name + '\n').encode(),
             source_name: source, source_name + '.sha256': (source_sha + '  ' + source_name + '\n').encode(),
             'LICENSE': license_text, 'NOTICE': notice, 'index.html': page.encode()}
    # Versions are immutable and written before the channel pointer; Git/Pages publishes one complete snapshot.
    output.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        target = output / name
        if target.exists() and target.suffix in ('.apk', '.zip') and target.read_bytes() != data:
            raise ValueError('Immutable asset already differs')
        target.write_bytes(data)
    feed_path.parent.mkdir(exist_ok=True)
    temporary = feed_path.with_suffix('.tmp')
    temporary.write_text(json.dumps(feed, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    temporary.replace(feed_path)
    print(f'Synced {version}: {len(apk)} bytes, SHA-256 {sha}; deployment must be verified separately.')
    return feed


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sync(args.output)
