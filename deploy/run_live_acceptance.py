"""Run production-topology browser acceptance with a restored baseline per suite."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from urllib.parse import urlsplit
import uuid

from data_backup import BackupError, restore_backup, verify_backup


ROOT = Path(__file__).resolve().parents[1]
FRONTEND = ROOT / 'frontend'
SUITES = ('assignment', 'daily', 'planning', 'review', 'retro', 'refinement', 'transcription')
DEFAULT_AUDIO = ROOT / 'ai-service/.stt-eval/feb3b85c968746538023736e92ff9be0/four-speakers.mp3'


class AcceptanceError(RuntimeError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def validate_origin(origin: str) -> str:
    parsed = urlsplit(origin)
    if (parsed.scheme not in ('http', 'https') or not parsed.hostname
            or parsed.username or parsed.password or parsed.path not in ('', '/')
            or parsed.query or parsed.fragment):
        raise AcceptanceError('origin_invalid')
    return origin.rstrip('/')


def select_suites(selected: list[str] | None) -> list[str]:
    requested = selected or list(SUITES)
    return [name for name in SUITES if name in requested]


def run_command(command: list[str], *, cwd: Path, env: dict[str, str], timeout: int) -> tuple[int, str]:
    try:
        result = subprocess.run(command, cwd=cwd, env=env, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as error:
        output = (error.stdout or b'').decode('utf-8', errors='replace')
        return 124, output + '\nAcceptance command timed out.\n'
    except OSError:
        raise AcceptanceError('command_unavailable') from None
    return result.returncode, result.stdout.decode('utf-8', errors='replace')


def write_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def execute(args, *, restore=restore_backup, runner=run_command) -> tuple[int, Path]:
    if not args.confirm_data_loss:
        raise AcceptanceError('confirm_data_loss_required')
    env_file = args.env_file.resolve()
    backup = args.backup.resolve()
    if not env_file.is_file():
        raise AcceptanceError('env_file_missing')
    manifest = verify_backup(backup)
    origin = validate_origin(args.origin)
    suites = select_suites(args.suite)
    if not suites:
        raise AcceptanceError('suite_required')
    if args.timeout <= 0:
        raise AcceptanceError('timeout_invalid')
    audio = args.stt_audio.resolve()
    if 'transcription' in suites and (not audio.is_file() or audio.suffix.lower() != '.mp3'):
        raise AcceptanceError('transcription_audio_missing')
    node = shutil.which('node')
    playwright = FRONTEND / 'node_modules/@playwright/test/cli.js'
    if not node or not playwright.is_file():
        raise AcceptanceError('playwright_runtime_missing')

    output = (args.output or ROOT / 'deploy/acceptance-runs' / uuid.uuid4().hex).resolve()
    if output.exists():
        raise AcceptanceError('output_exists')
    output.mkdir(parents=True)
    report_path = output / 'report.json'
    report = {
        'schema_version': 1, 'started_at': utc_now(), 'completed_at': None,
        'origin': origin, 'backup_created_at': manifest.get('created_at'),
        'suites': [], 'final_restore': 'pending', 'status': 'running',
    }
    write_report(report_path, report)
    exit_code = 0
    try:
        for suite in suites:
            print(f'[{suite}] restoring baseline...', flush=True)
            restore(env_file, backup)
            suite_dir = output / suite
            suite_dir.mkdir()
            environment = os.environ.copy()
            environment.update(
                AICAP_LIVE_WEB_BASE=origin,
                AICAP_LIVE_JAVA_BASE=origin,
                AICAP_LIVE_AI_BASE=origin + '/meeting-ai',
                AICAP_LIVE_ARTIFACT_DIR=str(suite_dir),
                AICAP_LIVE_REAL_MODEL='1' if suite == 'refinement' else '0',
            )
            if suite == 'transcription':
                environment.update(AICAP_STT_EVAL_AUDIO=str(audio),
                    AICAP_STT_EVAL_LANGUAGE=args.stt_language,
                    AICAP_STT_EVAL_SPEAKERS=str(args.speaker_count))
            started = time.monotonic()
            code, log = runner([node, str(playwright), 'test',
                '--config=playwright.live.config.js', f'{suite}-flow.spec.js'],
                cwd=FRONTEND, env=environment, timeout=args.timeout)
            (suite_dir / 'browser.log').write_text(log, encoding='utf-8')
            elapsed = round(time.monotonic() - started, 3)
            result = {'name': suite, 'status': 'passed' if code == 0 else 'failed',
                'exit_code': code, 'duration_seconds': elapsed,
                'log': str((suite_dir / 'browser.log').relative_to(output))}
            report['suites'].append(result)
            if code:
                exit_code = 1
            print(log, end='' if log.endswith('\n') else '\n', flush=True)
            print(f'[{suite}] {result["status"]} in {elapsed:.3f}s', flush=True)
            write_report(report_path, report)
    except BaseException:
        exit_code = 1
        raise
    finally:
        print('[final] restoring baseline...', flush=True)
        try:
            restore(env_file, backup)
            report['final_restore'] = 'passed'
        except Exception:
            report['final_restore'] = 'failed'
            exit_code = 1
        report['completed_at'] = utc_now()
        report['status'] = 'passed' if exit_code == 0 else 'failed'
        write_report(report_path, report)
    return exit_code, report_path


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('--env-file', type=Path, default=ROOT / 'deploy/.env')
    result.add_argument('--backup', type=Path, required=True)
    result.add_argument('--suite', action='append', choices=SUITES,
                        help='Repeat to select suites; omission runs all seven.')
    result.add_argument('--origin', default='http://127.0.0.1:8088')
    result.add_argument('--output', type=Path)
    result.add_argument('--stt-audio', type=Path, default=DEFAULT_AUDIO)
    result.add_argument('--stt-language', choices=('zh', 'en'), default='zh')
    result.add_argument('--speaker-count', type=int, choices=range(1, 9), default=4)
    result.add_argument('--timeout', type=int, default=180)
    result.add_argument('--confirm-data-loss', action='store_true')
    return result


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    try:
        code, report = execute(args)
        print(f'Acceptance report: {report}')
        return code
    except (AcceptanceError, BackupError) as error:
        print(f'Acceptance failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
