import argparse
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import data_backup
import run_live_acceptance as acceptance


class LiveAcceptanceTests(unittest.TestCase):
    def fixture(self, root: Path):
        backup = root / 'backup'; backup.mkdir()
        (backup / 'database.sql').write_bytes(b'fixture database')
        with tarfile.open(backup / 'audio.tar.gz', mode='w:gz') as archive:
            content = b'audio'
            member = tarfile.TarInfo('fixture.mp3'); member.size = len(content)
            archive.addfile(member, io.BytesIO(content))
        manifest = {'schema_version': 1, 'created_at': '2026-10-05T00:00:00+00:00',
            'database': 'AIcap', 'files': {}}
        for name in data_backup.BACKUP_FILES:
            path = backup / name
            manifest['files'][name] = {'size': path.stat().st_size,
                'sha256': data_backup.sha256(path)}
        (backup / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
        env_file = root / '.env'; env_file.write_text('fixture=ignored\n', encoding='utf-8')
        return backup, env_file

    def args(self, root: Path, backup: Path, env_file: Path, **changes):
        values = dict(confirm_data_loss=True, env_file=env_file, backup=backup,
            suite=['daily', 'planning'], origin='http://127.0.0.1:8088',
            output=root / 'output', stt_audio=root / 'audio.mp3', stt_language='zh',
            speaker_count=4, timeout=180)
        values.update(changes)
        return argparse.Namespace(**values)

    @patch('run_live_acceptance.shutil.which', return_value='node')
    def test_failure_is_reported_and_baseline_is_always_restored(self, _which):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); backup, env_file = self.fixture(root)
            restores = []
            results = iter([(1, 'daily failed\n'), (0, 'planning passed\n')])
            code, report_path = acceptance.execute(self.args(root, backup, env_file),
                restore=lambda env, source: restores.append((env, source)),
                runner=lambda *args, **kwargs: next(results))
            report = json.loads(report_path.read_text(encoding='utf-8'))
            self.assertEqual(1, code)
            self.assertEqual(['failed', 'passed'], [item['status'] for item in report['suites']])
            self.assertEqual('passed', report['final_restore'])
            self.assertEqual(3, len(restores))

    def test_confirmation_is_required_before_any_restore(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); backup, env_file = self.fixture(root)
            with self.assertRaisesRegex(acceptance.AcceptanceError, 'confirm_data_loss_required'):
                acceptance.execute(self.args(root, backup, env_file, confirm_data_loss=False),
                    restore=lambda *_: self.fail('restore must not run'))

    @patch('run_live_acceptance.shutil.which', return_value='node')
    def test_restore_error_marks_report_failed_before_propagating(self, _which):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); backup, env_file = self.fixture(root)
            calls = 0
            def restore(*_):
                nonlocal calls
                calls += 1
                if calls == 1:
                    raise data_backup.BackupError('restore_failed')
            with self.assertRaisesRegex(data_backup.BackupError, 'restore_failed'):
                acceptance.execute(self.args(root, backup, env_file, suite=['daily']),
                    restore=restore, runner=lambda *_args, **_kwargs: (0, 'unused'))
            report = json.loads((root / 'output/report.json').read_text(encoding='utf-8'))
            self.assertEqual('failed', report['status'])
            self.assertEqual('passed', report['final_restore'])
            self.assertEqual(2, calls)


if __name__ == '__main__':
    unittest.main()
