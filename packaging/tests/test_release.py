import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('mongodesk_release', Path(__file__).parents[1] / 'release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='mongodesk-release-test-')
        self.root = Path(self.temp.name)
        (self.root / 'VERSION').write_text('1.1.0\n')
        (self.root / 'CHANGELOG.md').write_text('# Changelog\n\n## [1.1.0]\n\n- Tested release.\n\n## [1.0.0]\n- Old.\n')
        self.assets = self.root / 'assets'
        self.assets.mkdir()
    def tearDown(self):
        self.temp.cleanup()
    def fake_installers(self):
        for key in release.PLATFORMS:
            (self.assets / release.filename('1.1.0', key)).write_bytes(('fake test installer: ' + key).encode())
    def keys(self):
        private = self.root / 'private.pem'
        public = self.root / 'public.pem'
        release.run_openssl('genpkey', '-algorithm', 'ED25519', '-out', private)
        release.run_openssl('pkey', '-in', private, '-pubout', '-out', public)
        return private, public
    def test_version_and_notes_are_required_and_tags_match(self):
        self.assertEqual('1.1.0', release.validate(root=self.root))
        self.assertEqual('- Tested release.\n', release.notes('1.1.0', self.root))
        with self.assertRaises(ValueError): release.validate('v1.2.0', self.root)
        for bad in ['v1.1.0', '1.1', '1.1.0-beta', '01.1.0', '256.0.0']:
            (self.root / 'VERSION').write_text(bad)
            with self.assertRaises(ValueError): release.version(self.root)
    def test_tag_requires_matching_commit_and_successful_main_build(self):
        head = 'a' * 40
        green = json.dumps([{'headSha': head, 'headBranch': 'main', 'conclusion': 'success'}])
        with patch.object(release.subprocess, 'run'), patch.object(release.subprocess, 'check_output', side_effect=[head, head, green]):
            self.assertEqual('1.1.0', release.validate('v1.1.0', self.root, True))
        with patch.object(release.subprocess, 'run'), patch.object(release.subprocess, 'check_output', side_effect=[head, head, '[]']):
            with self.assertRaises(ValueError): release.validate('v1.1.0', self.root, True)
        with patch.object(release.subprocess, 'run'), patch.object(release.subprocess, 'check_output', side_effect=[head, 'b' * 40]):
            with self.assertRaises(ValueError): release.validate('v1.1.0', self.root, True)
    def test_staging_names_are_versioned_and_platform_specific(self):
        for key, (_, suffix) in release.PLATFORMS.items():
            incoming = self.root / (key + suffix)
            incoming.write_bytes(b'installer')
            path = release.stage(key, incoming.name, self.assets, self.root)
            self.assertEqual(release.filename('1.1.0', key), path.name)
            self.assertEqual(b'installer', path.read_bytes())
        with self.assertRaises(ValueError): release.stage('mongodesk-linux-x64', 'missing*', self.assets, self.root)
    def test_signed_manifest_covers_all_platforms_and_detects_tampering(self):
        self.fake_installers()
        private, public = self.keys()
        data = release.manifest(self.assets, 'v1.1.0', private.read_text(), public, self.root)
        self.assertEqual(4, len(data['files']))
        for item in data['files']:
            self.assertEqual(release.sha256(self.assets / item['name']), item['sha256'])
            self.assertTrue(item['url'].startswith('https://github.com/ideazLab/MongoDesk/releases/download/v1.1.0/'))
        signature = self.root / 'signature.bin'
        signature.write_bytes(base64.b64decode((self.assets / 'update-manifest.json.sig').read_bytes()))
        self.assertEqual(64, len(signature.read_bytes()))
        manifest = self.assets / 'update-manifest.json'
        release.run_openssl('pkeyutl', '-verify', '-rawin', '-pubin', '-inkey', public, '-in', manifest, '-sigfile', signature)
        manifest.write_bytes(manifest.read_bytes() + b' ')
        with self.assertRaises(subprocess.CalledProcessError):
            release.run_openssl('pkeyutl', '-verify', '-rawin', '-pubin', '-inkey', public, '-in', manifest, '-sigfile', signature)
        self.assertEqual(6, len((self.assets / 'SHA256SUMS').read_text().splitlines()))
    def test_missing_platforms_and_wrong_signing_key_are_rejected(self):
        private, public = self.keys()
        with self.assertRaises(ValueError): release.manifest(self.assets, 'v1.1.0', private.read_text(), public, self.root)
        self.fake_installers()
        other = self.root / 'other.pem'
        release.run_openssl('genpkey', '-algorithm', 'ED25519', '-out', other)
        with self.assertRaises(ValueError): release.manifest(self.assets, 'v1.1.0', other.read_text(), public, self.root)
    def test_published_releases_are_never_overwritten(self):
        self.fake_installers()
        private, public = self.keys()
        release.manifest(self.assets, 'v1.1.0', private.read_text(), public, self.root)
        response = subprocess.CompletedProcess([], 0, stdout=json.dumps({'isDraft': False}), stderr='')
        with patch.object(release.subprocess, 'run', return_value=response) as run:
            with self.assertRaises(ValueError): release.publish_draft(self.assets, 'v1.1.0', self.root)
            self.assertEqual(1, run.call_count)

    def test_new_release_is_always_created_as_a_draft(self):
        self.fake_installers()
        private, public = self.keys()
        release.manifest(self.assets, 'v1.1.0', private.read_text(), public, self.root)
        missing = subprocess.CompletedProcess([], 1, stdout='', stderr='not found')
        ok = subprocess.CompletedProcess([], 0, stdout='', stderr='')
        with patch.object(release.subprocess, 'run', side_effect=[missing, ok]) as run:
            release.publish_draft(self.assets, 'v1.1.0', self.root)
            command = run.call_args_list[-1].args[0]
            self.assertIn('--draft', command)
            self.assertIn('--verify-tag', command)
            self.assertNotIn('--latest', command)

if __name__ == '__main__': unittest.main()
