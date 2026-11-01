"""Offline mirror contract tests; no release upload or network access."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import sync_movie_mirror as m


class MirrorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name) / 'movie'
        self.release = dict(tag_name='v0.4.12-preview', published_at='2026-11-01', prerelease=True,
                            draft=False, body='<script>example</script>', assets=[])
        self.data = {}
        source = io.BytesIO()
        with zipfile.ZipFile(source, 'w') as z:
            z.writestr('MarcoMovie-0.4.12/LICENSE', 'GPL-3.0-only')
            z.writestr('MarcoMovie-0.4.12/NOTICE', 'Pilipala')
        for name, body in [('MarcoMovie-0.4.12-preview.apk', b'apk fixture'), ('MarcoMovie-0.4.12-source.zip', source.getvalue())]:
            url = f'https://github.com/{m.REPO}/releases/download/v0.4.12-preview/{name}'
            sha = hashlib.sha256(body).hexdigest()
            for suffix, payload in [('', body), ('.sha256', (sha + '  ' + name).encode())]:
                self.release['assets'].append(dict(name=name+suffix, state='uploaded', browser_download_url=url+suffix,
                                                   size=len(payload), digest='sha256:'+hashlib.sha256(payload).hexdigest()))
                self.data[url+suffix] = payload
        self.identity = dict(package='com.pilipala.marcomovie', versionName='0.4.12', versionCode=25, minSdk=23)

    def fetch(self, url, limit=m.LIMIT):
        if url.startswith('https://api.github.com/'):
            return json.dumps([self.release]).encode()
        return self.data[url]

    def sync(self):
        with patch.object(m, 'fetch', self.fetch), patch.object(m, 'apk_identity', return_value=self.identity):
            return m.sync(self.out)

    def test_latest_numeric_preview_only(self):
        older = dict(self.release, tag_name='v0.4.9-preview')
        draft = dict(self.release, tag_name='v99.0.0-preview', draft=True)
        stable = dict(self.release, tag_name='v99.0.0', prerelease=False)
        self.assertEqual(m.choose([older, self.release, draft, stable])['tag_name'], 'v0.4.12-preview')
        with self.assertRaises(ValueError): m.choose([draft, stable])

    def test_complete_snapshot_and_repeat(self):
        unrelated = self.out.parent/'index.html'
        unrelated.write_text('Marco TV')
        feed = self.sync()
        self.assertEqual(feed['versionCode'], 25)
        self.assertEqual(feed['url'], m.BASE+'MarcoMovie-0.4.12-preview.apk')
        self.assertEqual(json.loads((self.out/'updates/preview.json').read_text(encoding='utf-8')), feed)
        self.assertIn('&lt;script&gt;', (self.out/'index.html').read_text(encoding='utf-8'))
        self.assertEqual(unrelated.read_text(), 'Marco TV')
        self.assertEqual(self.sync(), feed)

    def test_wrong_release_checksum_leaves_feed_unchanged(self):
        self.sync()
        before = (self.out/'updates/preview.json').read_bytes()
        name='MarcoMovie-0.4.12-preview.apk'
        (self.out/name).write_bytes(b'bad')
        with self.assertRaises(ValueError): self.sync()
        self.assertEqual((self.out/'updates/preview.json').read_bytes(), before)

    def test_wrong_asset_host(self):
        self.release['assets'][0]['browser_download_url']='https://example.invalid/file.apk'
        with self.assertRaises(ValueError): self.sync()
        self.assertFalse(self.out.exists())

    def test_wrong_apk_version(self):
        self.identity['versionName']='0.4.13'
        with self.assertRaises(ValueError): self.sync()
        self.assertFalse(self.out.exists())

    def test_reject_rollback(self):
        self.sync()
        self.identity['versionCode']=24
        with self.assertRaises(ValueError): self.sync()
        self.assertEqual(json.loads((self.out/'updates/preview.json').read_text(encoding='utf-8'))['versionCode'],25)

    def test_pages_size_limit(self):
        self.release['assets'][0]['size']=m.LIMIT+1
        with self.assertRaises(ValueError): self.sync()

    def test_invalid_manifest_rejected(self):
        source=io.BytesIO()
        with zipfile.ZipFile(source,'w') as z: z.writestr('AndroidManifest.xml',b'<manifest/>')
        with self.assertRaises(ValueError): m.apk_identity(source.getvalue())

if __name__ == '__main__': unittest.main()
