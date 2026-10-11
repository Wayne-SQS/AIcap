import json
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

import data_backup


class BackupManifestTests(unittest.TestCase):
    def fixture(self, root: Path):
        (root / 'database.sql').write_bytes(b'CREATE TABLE fixture(id INT);\n')
        with tarfile.open(root / 'audio.tar.gz', mode='w:gz') as archive:
            content = b'fixture-audio'
            member = tarfile.TarInfo('fixture.mp3'); member.size = len(content)
            archive.addfile(member, io.BytesIO(content))
        manifest = {
            'schema_version': 1, 'created_at': '2026-10-04T00:00:00+00:00',
            'database': 'AIcap', 'files': {},
        }
        for name in data_backup.BACKUP_FILES:
            path = root / name
            manifest['files'][name] = {'size': path.stat().st_size,
                                        'sha256': data_backup.sha256(path)}
        (root / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')

    def test_manifest_verifies_database_and_audio_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            self.assertEqual('AIcap', data_backup.verify_backup(root)['database'])

    def test_changed_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            (root / 'database.sql').write_bytes(b'changed')
            with self.assertRaisesRegex(data_backup.BackupError, 'backup_invalid'):
                data_backup.verify_backup(root)

    def test_archive_path_traversal_is_rejected_even_with_matching_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            with tarfile.open(root / 'audio.tar.gz', mode='w:gz') as archive:
                content = b'escape'
                member = tarfile.TarInfo('../escape.mp3'); member.size = len(content)
                archive.addfile(member, io.BytesIO(content))
            manifest = json.loads((root / 'manifest.json').read_text())
            path = root / 'audio.tar.gz'
            manifest['files']['audio.tar.gz'] = {
                'size': path.stat().st_size, 'sha256': data_backup.sha256(path)}
            (root / 'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaisesRegex(data_backup.BackupError, 'backup_invalid'):
                data_backup.verify_backup(root)


if __name__ == '__main__':
    unittest.main()
