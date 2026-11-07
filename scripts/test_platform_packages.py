import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import sync_platform_packages as m


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name)
        cards = []
        for suffix in sum(m.SUFFIXES.values(), ()):
            name = 'MarcoMovie-0.5.0-' + suffix
            cards.append(f'<div class="dl"><h3>{suffix}</h3><p>Existing copy · 12.0 MB</p>'
                         f'<a href="{name}">下载</a><a href="{name}.sha256">校验文件</a>'
                         f'<code>SHA-256：{"a" * 64}</code></div>')
        self.page = '<style>existing CSS</style><header>Existing layout</header>' + ''.join(cards) + '<footer>Existing footer</footer>'
        (self.out / 'index.html').write_text(self.page, encoding='utf-8')
        (self.out / 'updates').mkdir()
        (self.out / 'updates/stable.json').write_text('{"versionName":"0.5.0"}', encoding='utf-8')
        self.release = dict(tag_name='ios-v0.5.1', published_at='date', draft=False, prerelease=False, body='iOS fixes')

    def sync(self, releases=None):
        with patch.object(m, 'platform_asset', return_value=('https://github.com/Pilipala9919/MarcoMovie/releases/download/ios-v0.5.1/MarcoMovie-0.5.1-ios-unsigned.ipa', 123456, 'b' * 64, None)):
            return m.sync(self.out, [self.release] if releases is None else releases)

    def test_independent_package_preserves_style_other_cards_and_legacy_channel(self):
        self.assertEqual(self.sync(), {'ios': '0.5.1'})
        result = (self.out / 'index.html').read_text(encoding='utf-8')
        package = dict(url='https://github.com/Pilipala9919/MarcoMovie/releases/download/ios-v0.5.1/MarcoMovie-0.5.1-ios-unsigned.ipa', size=123456, sha256='b' * 64)
        self.assertEqual(result, m.patch_card(self.page, 'ios-unsigned.ipa', package))
        self.assertEqual((self.out / 'updates/stable.json').read_text(), '{"versionName":"0.5.0"}')
        self.assertFalse((self.out / 'updates/windows.json').exists())
        self.sync()
        self.assertEqual(result, (self.out / 'index.html').read_text(encoding='utf-8'))

    def test_drafts_prereleases_other_platform_and_no_release_leave_page_unchanged(self):
        self.sync([dict(self.release, draft=True), dict(self.release, prerelease=True), dict(self.release, tag_name='v0.5.0-preview')])
        self.assertEqual((self.out / 'index.html').read_text(encoding='utf-8'), self.page)
        self.assertFalse((self.out / 'updates/ios.json').exists())

    def test_missing_card_fails_without_writes(self):
        (self.out / 'index.html').write_text('<style>unchanged</style>')
        with self.assertRaises(ValueError): self.sync()
        self.assertFalse((self.out / 'updates/ios.json').exists())
        self.assertEqual((self.out / 'index.html').read_text(), '<style>unchanged</style>')

    def test_rollback_and_same_version_replacement_rejected(self):
        self.sync()
        path = self.out / 'updates/ios.json'
        original = path.read_bytes()
        with self.assertRaises(ValueError): self.sync([dict(self.release, tag_name='ios-v0.4.99')])
        self.assertEqual(path.read_bytes(), original)
        feed = json.loads(original)
        feed['packages']['MarcoMovie-0.5.1-ios-unsigned.ipa']['sha256'] = 'c' * 64
        path.write_text(json.dumps(feed))
        with self.assertRaises(ValueError): self.sync()

    def test_missing_second_windows_asset_does_not_publish_partial_feed(self):
        with patch.object(m, 'platform_asset', side_effect=[('first', 10, 'b' * 64, b'installer'), ValueError('missing ZIP')]):
            with self.assertRaises(ValueError): m.sync(self.out, [dict(self.release, tag_name='windows-v0.5.1')])
        self.assertFalse((self.out / 'updates/windows.json').exists())
        self.assertFalse((self.out / 'MarcoMovie-0.5.1-windows-setup.exe').exists())
        self.assertEqual((self.out / 'index.html').read_text(encoding='utf-8'), self.page)


if __name__ == '__main__':
    unittest.main()
