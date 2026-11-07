"""Update independently released desktop/Apple packages without rendering the site.

The existing page is the template: only package URLs, sizes and checksums inside
matching download cards change. Android's existing update channels stay intact.
"""
import argparse
import html
import json
from pathlib import Path
import re

from sync_movie_mirror import BASE, REPO, fetch, platform_asset

SUFFIXES = {
    'ios': ('ios-unsigned.ipa',),
    'windows': ('windows-setup.exe', 'windows-x64.zip'),
    'macos': ('macos.zip',),
}


def version_key(version):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Invalid platform version')
    return tuple(map(int, version.split('.')))


def choose_platform(releases, platform):
    candidates = []
    for release in releases:
        match = re.fullmatch(re.escape(platform) + r'-v(\d+\.\d+\.\d+)', release.get('tag_name', ''))
        if match and not release.get('draft') and not release.get('prerelease') and release.get('published_at'):
            candidates.append((version_key(match[1]), release))
    return max(candidates, key=lambda item: item[0])[1] if candidates else None


def patch_card(page, suffix, package):
    name_pattern = r'MarcoMovie-\d+\.\d+\.\d+-' + re.escape(suffix)
    cards = list(re.finditer(r'<div class="dl">.*?</div>', page, re.S))
    matches = [card for card in cards if re.search(name_pattern, card[0])]
    if len(matches) != 1:
        raise ValueError('Expected exactly one existing download card: ' + suffix)
    card = matches[0]
    replacement, links = re.subn(
        r'href="[^"<>]*' + name_pattern + r'(\.sha256)?"',
        lambda match: 'href="' + html.escape(package['url'], quote=True) + (match[1] or '') + '"',
        card[0])
    if links != 2:
        raise ValueError('Expected package and checksum links: ' + suffix)
    replacement, hashes = re.subn(r'(SHA-256：)[a-f0-9]{64}',
                                 lambda match: match[1] + package['sha256'], replacement)
    replacement, sizes = re.subn(r'\d+(?:\.\d+)? MB', f'{package["size"] / 1024 / 1024:.1f} MB', replacement)
    if hashes != 1 or sizes != 1:
        raise ValueError('Missing existing checksum or size: ' + suffix)
    return page[:card.start()] + replacement + page[card.end():]


def sync(output, releases=None):
    output = Path(output)
    original = (output / 'index.html').read_bytes()
    page = original.decode('utf-8')
    if releases is None:
        releases = json.loads(fetch(f'https://api.github.com/repos/{REPO}/releases?per_page=100', 4 * 1024 * 1024))
    files, versions = {}, {}
    for platform, suffixes in SUFFIXES.items():
        release = choose_platform(releases, platform)
        if release is None:
            continue
        version = release['tag_name'].split('-v', 1)[1]
        packages = {}
        for suffix in suffixes:
            name = f'MarcoMovie-{version}-{suffix}'
            href, size, digest, body = platform_asset(release, name, output)
            package = dict(size=size, sha256=digest, url=href if href.startswith('https://') else BASE + name)
            packages[name] = package
            if body is not None:
                files[name] = body
                files[name + '.sha256'] = (digest + '  ' + name + '\n').encode('ascii')
            page = patch_card(page, suffix, package)
        feed = dict(schema=1, platform=platform, versionName=version, prerelease=False,
                    notes=release.get('body') or '', packages=packages,
                    release_url=f'https://github.com/{REPO}/releases/tag/{release["tag_name"]}')
        if platform == 'windows':
            feed['windows'] = packages[f'MarcoMovie-{version}-windows-setup.exe']
        path = f'updates/{platform}.json'
        previous_path = output / path
        if previous_path.exists():
            previous = json.loads(previous_path.read_text(encoding='utf-8'))
            if version_key(previous['versionName']) > version_key(version):
                raise ValueError('Refusing platform rollback: ' + platform)
            if previous['versionName'] == version and previous['packages'] != packages:
                raise ValueError('Refusing to replace a published package: ' + platform)
        files[path] = (json.dumps(feed, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
        versions[platform] = version
    files['index.html'] = page.encode('utf-8')
    # Nothing is written until every release/card has validated. Git publishes
    # the complete snapshot; never rewrite a previously published binary.
    for name, body in files.items():
        target = output / name
        if target.exists() and target.suffix in ('.ipa', '.exe', '.zip') and target.read_bytes() != body:
            raise ValueError('Immutable package differs: ' + name)
    for name, body in files.items():
        target = output / name
        if not target.exists() or target.read_bytes() != body:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
    return versions


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(sync(args.output), ensure_ascii=False))
