"""Create, verify, and restore AIcap MySQL/audio backups through Compose."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from pathlib import PurePosixPath
import shutil
import subprocess
import sys
import tarfile
import uuid


ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / 'deploy/compose.yaml'
BACKUP_FILES = ('database.sql', 'audio.tar.gz')


class BackupError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def compose_command(env_file: Path, *args: str) -> list[str]:
    return ['docker', 'compose', '--env-file', str(env_file), '-f', str(COMPOSE), *args]


def run(command: list[str], *, stdin=None, stdout=None) -> None:
    try:
        result = subprocess.run(command, stdin=stdin, stdout=stdout, check=False)
    except OSError:
        raise BackupError('docker_command_unavailable') from None
    if result.returncode != 0:
        raise BackupError('docker_command_failed')


def verify_backup(folder: Path) -> dict:
    try:
        manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
        if (manifest.get('schema_version') != 1 or manifest.get('database') != 'AIcap'
                or set(manifest.get('files', {})) != set(BACKUP_FILES)):
            raise ValueError()
        for name in BACKUP_FILES:
            path = folder / name
            record = manifest['files'][name]
            if (not path.is_file() or path.stat().st_size <= 0
                    or record.get('size') != path.stat().st_size
                    or record.get('sha256') != sha256(path)):
                raise ValueError()
        with tarfile.open(folder / 'audio.tar.gz', mode='r:gz') as archive:
            for member in archive.getmembers():
                path = PurePosixPath(member.name)
                if (path.is_absolute() or '..' in path.parts
                        or not (member.isfile() or member.isdir())):
                    raise ValueError()
    except (OSError, UnicodeError, json.JSONDecodeError, tarfile.TarError,
            TypeError, ValueError):
        raise BackupError('backup_invalid') from None
    return manifest


def create_backup(env_file: Path, output: Path) -> None:
    if output.exists():
        raise BackupError('backup_output_exists')
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name('.' + output.name + '.tmp-' + uuid.uuid4().hex)
    temporary.mkdir()
    try:
        database = temporary / 'database.sql'
        with database.open('wb') as stream:
            run(compose_command(env_file, 'exec', '-T', 'mysql', 'sh', '-c',
                'exec mysqldump --single-transaction --routines --triggers --hex-blob '
                '-uroot -p"$MYSQL_ROOT_PASSWORD" AIcap'), stdout=stream)
        audio = temporary / 'audio.tar.gz'
        with audio.open('wb') as stream:
            run(compose_command(env_file, 'exec', '-T', 'java',
                                'tar', '-C', '/data/audio', '-czf', '-', '.'), stdout=stream)
        manifest = {
            'schema_version': 1,
            'created_at': datetime.now(timezone.utc).isoformat(),
            'database': 'AIcap',
            'files': {name: {'size': (temporary / name).stat().st_size,
                             'sha256': sha256(temporary / name)} for name in BACKUP_FILES},
        }
        (temporary / 'manifest.json').write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        verify_backup(temporary)
        temporary.replace(output)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def restore_backup(env_file: Path, folder: Path) -> None:
    verify_backup(folder)
    stopped = False
    try:
        run(compose_command(env_file, 'stop', 'web', 'ai', 'java'))
        stopped = True
        run(compose_command(env_file, 'exec', '-T', 'mysql', 'sh', '-c',
            'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e '
            '"DROP DATABASE IF EXISTS AIcap; CREATE DATABASE AIcap CHARACTER SET utf8mb4 '
            'COLLATE utf8mb4_unicode_ci;"'))
        with (folder / 'database.sql').open('rb') as stream:
            run(compose_command(env_file, 'exec', '-T', 'mysql', 'sh', '-c',
                                'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" AIcap'), stdin=stream)
        with (folder / 'audio.tar.gz').open('rb') as stream:
            run(compose_command(env_file, 'run', '--rm', '--no-deps', '--entrypoint', 'sh',
                'java', '-c', 'find /data/audio -mindepth 1 -delete && '
                'tar -xzf - -C /data/audio'), stdin=stream)
    finally:
        if stopped:
            run(compose_command(env_file, 'up', '-d', 'java', 'ai', 'web'))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / 'deploy/.env')
    commands = parser.add_subparsers(dest='command', required=True)
    backup = commands.add_parser('backup')
    backup.add_argument('--output', type=Path, required=True)
    verify = commands.add_parser('verify')
    verify.add_argument('--input', type=Path, required=True)
    restore = commands.add_parser('restore')
    restore.add_argument('--input', type=Path, required=True)
    restore.add_argument('--confirm-data-loss', action='store_true')
    args = parser.parse_args(argv)
    try:
        if not args.env_file.is_file():
            raise BackupError('env_file_missing')
        if args.command == 'backup':
            create_backup(args.env_file.resolve(), args.output.resolve())
            print(f'Backup created and verified: {args.output}')
        elif args.command == 'verify':
            verify_backup(args.input.resolve())
            print(f'Backup verified: {args.input}')
        else:
            if not args.confirm_data_loss:
                raise BackupError('restore_requires_confirm_data_loss')
            restore_backup(args.env_file.resolve(), args.input.resolve())
            print(f'Backup restored: {args.input}')
    except BackupError as error:
        print(f'Backup operation failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
