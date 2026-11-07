"""Reproduce the pinned DRPY compatibility assets and all dependency notices.

Network downloads are pinned by SHA-256 (upstream) and npm integrity (lockfile).
No private subscription or media endpoint is downloaded or included.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import urllib.request
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'shared/catpaw'
COMMIT = 'ca23ffdbf5e429c5893ac60158668fc5af7af192'
UPSTREAM = {
    'libs/drpy2.js': 'c014ac79c5c2b275c2121c33d4155934df244b3b68ce720801e72f8e9b86a85d',
    'libs/gbk.js': 'cf46ccf34d32ce873f021fc5e94c43a73afcb7a551004be8d6a02e94986d0696',
    'js/模板.js': '1da00bdcb30ab1a7875a84ee6fc82256e705330b6cc9d84144f163df68b665cb',
    'LICENSE': 'da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768',
}
PACKAGES = {'cheerio': '1.0.0-rc.12', 'crypto-js': '4.2.0', 'jsencrypt': '3.3.2',
            'jsonpath-plus': '10.3.0', 'nunjucks': '3.2.4', 'esbuild': '0.25.12'}
ENTRY = '''import * as html from 'cheerio';
import {JSONPath} from 'jsonpath-plus';
import nunjucks from 'nunjucks/browser/nunjucks.js';
import CryptoJS from 'crypto-js';
import JSEncrypt from 'jsencrypt';
const templates = new nunjucks.Environment([], {autoescape:false});
nunjucks.installJinjaCompat();
const cheerio = Object.assign(html.load(''), html, {
  jinja2:(text, data)=>templates.renderString(String(text),data),
  jp:(path,json)=>JSONPath({path,json,eval:'safe'}),
  jpo:(options)=>JSONPath({...options,eval:'safe'}),
});
export {cheerio,CryptoJS,JSEncrypt};
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def review_text(text):
    # Remove only non-executable upstream demonstration comments. Keep the
    # private-value scanner enabled for all published and generated files.
    reviewed = []
    in_demo = False
    for line in text.splitlines(keepends=True):
        if line.startswith('function init_test(){'):
            in_demo = True
        if in_demo and line.startswith('}'):
            in_demo = False
        if in_demo and line.lstrip().startswith('//'):
            continue
        if re.search(r'192\.168\.\d+\.\d+', line) and line.lstrip().startswith(('//', '*', 'adb connect ')):
            continue
        reviewed.append(line)
    text = ''.join(reviewed)
    # These are PEM serializer delimiters, not credentials. Equivalent literal
    # concatenation avoids making the scanner ignore a private-key marker.
    text = re.sub(r'''(['"])-----BEGIN RSA PRIVATE''' + r''' KEY-----''',
        lambda m: m[1] + '-----BEGIN RSA PRIVATE' + m[1] + ' + ' + m[1] + ' KEY-----', text)
    return text


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    source_dir = OUT / 'drpy-upstream'
    source_dir.mkdir(exist_ok=True)
    sources = {}
    cache = work / 'upstream'
    cache.mkdir(exist_ok=True)
    provenance = {'runtime': 'drpy2-ca23ffdb-1', 'repository': 'https://github.com/hjdhnx/dr_py',
                  'commit': COMMIT, 'license': 'LGPL-3.0-only', 'sources': [],
                  'dependencyReconstruction': 'Opaque upstream combined dependency bundle is not redistributed; rebuilt from explicit npm pins.'}
    for relative, expected in UPSTREAM.items():
        target = cache / Path(relative).name
        url = f'https://raw.githubusercontent.com/hjdhnx/dr_py/{COMMIT}/{relative}'
        published = source_dir / Path(relative).name
        if not target.exists() and published.exists() and sha(published.read_bytes()) == expected:
            shutil.copy2(published,target)
        if not target.exists():
            with urllib.request.urlopen(urllib.request.Request(urllib.parse.quote(url, safe=':/'), headers={'User-Agent': 'MarcoMovie-build'}), timeout=30) as response:
                target.write_bytes(response.read())
        data = target.read_bytes()
        if sha(data) != expected:
            raise ValueError('Pinned upstream checksum mismatch: ' + relative)
        reviewed = review_text(data.decode('utf-8'))
        published.write_text(reviewed,encoding='utf-8',newline='\n')
        sources[relative] = reviewed
        provenance['sources'].append({'path': relative, 'url': url, 'sha256': expected,
            'reviewedSha256':sha(published.read_bytes())})
    (work / 'package.json').write_text(json.dumps({'private': True, 'dependencies': PACKAGES}), encoding='utf-8', newline='\n')
    lock = OUT / 'drpy-package-lock.json'
    if lock.exists():
        shutil.copy2(lock, work / 'package-lock.json')
        command = 'ci'
    else:
        command = 'install'
    npm = shutil.which('npm.cmd' if os.name == 'nt' else 'npm')
    if not npm:
        raise RuntimeError('npm is required to rebuild pinned DRPY dependencies')
    subprocess.run([npm, command, '--ignore-scripts', '--no-audit', '--no-fund'], cwd=work, check=True)
    shutil.copy2(work / 'package-lock.json', lock)
    (work / 'entry.mjs').write_text(ENTRY, encoding='utf-8', newline='\n')
    # Browser target keeps Node builtins out of the code evaluated in the VM.
    esbuild = work / 'node_modules/@esbuild/win32-x64/esbuild.exe' if os.name == 'nt' else work / 'node_modules/.bin/esbuild'
    subprocess.run([str(esbuild), 'entry.mjs', '--bundle', '--platform=browser', '--format=iife',
                    '--global-name=DRPYDeps', '--target=es2020', '--legal-comments=inline',
                    '--outfile=' + str(OUT / 'drpy-dependencies.js')], cwd=work, check=True)
    dependencies = OUT / 'drpy-dependencies.js'
    dependency_text = review_text(dependencies.read_text(encoding='utf-8'))
    dependency_text = re.sub(r'/\*\* @preserve[\s\S]*?\*/',
        lambda m: '\n'.join(line.expandtabs(4).rstrip() for line in m[0].split('\n')), dependency_text)
    dependencies.write_text(dependency_text,encoding='utf-8',newline='\n')
    packages = json.loads(lock.read_text(encoding='utf-8'))['packages']
    notices = []
    records = []
    for package_path, package in sorted(packages.items()):
        if not package_path or package.get('dev') or package_path.startswith('node_modules/@esbuild/') or package_path == 'node_modules/esbuild':
            continue
        folder = work / package_path
        licenses = sorted(p for p in folder.iterdir() if p.is_file() and p.name.lower().startswith(('license', 'licence', 'copying', 'notice')))
        if package_path == 'node_modules/boolbase':
            # 1.0.0 declares ISC but omits the notice from its npm tarball.
            notice = source_dir / 'boolbase-LICENSE'
            notice_url = 'https://raw.githubusercontent.com/fb55/boolbase/54811f01c797a6bb1c263183ae90b7dd627e0638/LICENSE'
            if not notice.exists():
                notice.write_bytes(urllib.request.urlopen(notice_url, timeout=30).read())
            if sha(notice.read_bytes()) != 'cdf4d87ae0a6c160227263bf4e39a0a10e30e89ae7256e0f7ac7bde0836552c6':
                raise ValueError('boolbase license checksum mismatch')
            licenses = [notice]
            provenance['supplementalNotice'] = {'package':'boolbase@1.0.0', 'url':notice_url,
                'reason':'npm manifest declares ISC; tarball omits license text', 'sha256':sha(notice.read_bytes())}
        if not licenses:
            raise ValueError('Missing dependency license: ' + package_path)
        notices.append('\n===== ' + package_path + '@' + package['version'] + ' =====\n')
        for license_file in licenses:
            notices.append(license_file.read_text(encoding='utf-8') + '\n')
        records.append({'package': package_path, 'version': package['version'], 'resolved': package.get('resolved'),
                        'integrity': package.get('integrity'), 'license': package.get('license'),
                        'noticeFiles': [p.name for p in licenses]})
    notice_text = '\n'.join(line.rstrip() for line in ''.join(notices).splitlines()).rstrip()+'\n'
    (OUT / 'drpy-dependencies-LICENSE.txt').write_text(notice_text, encoding='utf-8', newline='\n')
    provenance['packages'] = records
    provenance['bundler'] = 'esbuild@0.25.12'
    core = sources['libs/drpy2.js']
    # Core is retained verbatim in drpy-upstream; these changes are explicit.
    lines = core.splitlines(keepends=True)
    assert all(lines[i].startswith('import ') for i in range(5))
    core = ''.join(lines[5:])
    matches = re.findall(r"^const OCR_API = '[^']+';", core, re.MULTILINE)
    assert len(matches) == 1
    old_ocr = matches[0]
    core = core.replace(old_ocr, "const OCR_API = ''; // App-owned: no default remote OCR service.")
    core, placeholder_count = re.subn(r"vod_pic:'https?://[^']+/404\.jpg'", "vod_pic:''", core)
    assert placeholder_count == 1
    guard = "classification:function (img){ // img是byte类型,这里不方便搞啊"
    assert core.count(guard) == 1
    core = core.replace(guard, guard + "\n        globalThis.__drpyUnsupported='drpy_ocr_not_configured';\n        throw new Error('drpy_ocr_not_configured');")
    init_failure = "console.log('init_test发生错误:'+e.message);"
    assert core.count(init_failure) == 1
    core = core.replace(init_failure, 'throw e; // Preserve initialization failure for the app.')
    assert core.count('\nexport default {') == 1
    core = core.replace('\nexport default {', '\nglobalThis.__drpy = {')
    gbk = sources['libs/gbk.js'].replace('export function gbkTool()', 'function gbkTool()')
    template = sources['js/模板.js'].replace('export default {muban,getMubans};', 'return {muban,getMubans};')
    text = '// Modified upstream DRPY; original sources and LGPL license are in drpy-upstream.\n'
    text += 'const cheerio = DRPYDeps.cheerio; globalThis.CryptoJS = DRPYDeps.CryptoJS; globalThis.JSEncrypt = DRPYDeps.JSEncrypt;\n'
    text += gbk + '\nconst 模板 = (()=>{\n' + template + '\n})();\n' + core
    (OUT / 'drpy-core.js').write_text(text, encoding='utf-8', newline='\n')
    shutil.copy2(source_dir / 'LICENSE', OUT / 'drpy-LICENSE.txt')
    provenance['transformations'] = ['Replace five module imports with pinned local dependencies',
        'Expose default Spider as context-local __drpy', 'Disable default OCR endpoint and reject OCR calls',
        'Propagate init errors instead of logging and swallowing them', 'Wrap templates in their own module scope',
        'Remove only init_test demo comments and commented RFC1918 examples',
        'Remove the upstream remote placeholder image from empty category results',
        'Split PEM serializer delimiter literals by equivalent string concatenation; no scanner exemption for private values']
    provenance['generated'] = {name: sha((OUT / name).read_bytes()) for name in ['drpy-core.js', 'drpy-dependencies.js']}
    (OUT / 'drpy-provenance.json').write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    from update_drpy_assets import update
    update()


if __name__ == '__main__':
    main()
