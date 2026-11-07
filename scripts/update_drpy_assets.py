"""Refresh the checked asset list after changing shared DRPY runtime files."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1] / 'shared/catpaw'
NAMES = ['drpy-worker.cjs', 'drpy-http.cjs', 'drpy-schema.cjs', 'drpy-core.js',
         'drpy-dependencies.js', 'drpy-LICENSE.txt', 'drpy-dependencies-LICENSE.txt',
         'drpy-provenance.json', 'drpy-guard-extractor.cjs', 'drpy-dm84-adapter.cjs',
         'drpy-bootstrap-worker.cjs', 'drpy-auete-adapter.cjs', 'drpy-bttwoo-adapter.cjs',
         'drpy-appgz-adapter.cjs', 'drpy-rsa.cjs',
         'drpy-t4-extractor.cjs', 'drpy-t4-adapter.cjs']


def update():
    files = []
    for name in NAMES:
        data = (ROOT / name).read_bytes()
        files.append({'name': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    (ROOT / 'drpy-assets.json').write_text(json.dumps({'version': 1,
        'runtime': 'drpy2-ca23ffdb-1', 'files': files}, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    update()
