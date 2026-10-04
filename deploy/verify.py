"""Fail-closed deployment configuration and live-route verification."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_FILES = (
    'deploy/compose.yaml', 'deploy/java.Dockerfile', 'deploy/ai.Dockerfile',
    'deploy/frontend.Dockerfile', 'deploy/nginx.conf', 'frontend/package-lock.json',
)
MODEL_FILES = (
    'faster-whisper-base/config.json', 'faster-whisper-base/model.bin',
    'faster-whisper-base/tokenizer.json', 'diarization/segmentation.onnx',
    'diarization/embedding.onnx', 'diarization/manifest.json',
)
PLACEHOLDER = re.compile(r'(replace|change[-_ ]?me|example|your[-_ ]|xxxx)', re.I)


class VerificationError(ValueError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def read_env(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding='utf-8-sig').splitlines()
    except (OSError, UnicodeError):
        raise VerificationError('cannot_read_env_file') from None
    values: dict[str, str] = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue
        key, separator, value = stripped.partition('=')
        if (not separator or not re.fullmatch(r'[A-Z][A-Z0-9_]*', key)
                or key in values):
            raise VerificationError('invalid_env_file')
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def verify_config(values: dict[str, str], model_root: Path) -> None:
    rules = {'DB_ROOT_PASSWORD': 16, 'DB_PASSWORD': 16, 'JWT_SECRET': 32,
             'AICAP_LLM_API_KEY': 8}
    for key, minimum in rules.items():
        value = values.get(key, '')
        if len(value) < minimum or PLACEHOLDER.search(value):
            raise VerificationError(f'{key.lower()}_missing_or_weak')
    if values['DB_ROOT_PASSWORD'] == values['DB_PASSWORD']:
        raise VerificationError('database_passwords_must_differ')
    base_url = values.get('AICAP_LLM_BASE_URL', 'https://api.deepseek.com')
    parsed = urlsplit(base_url)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment):
        raise VerificationError('aicap_llm_base_url_invalid')
    port = values.get('AICAP_HTTP_PORT', '8088')
    if not port.isdigit() or not 1 <= int(port) <= 65535:
        raise VerificationError('aicap_http_port_invalid')
    if values.get('AICAP_BIND_ADDRESS', '127.0.0.1') not in ('127.0.0.1', '0.0.0.0'):
        raise VerificationError('aicap_bind_address_invalid')
    for relative in REQUIRED_FILES:
        if not (ROOT / relative).is_file():
            raise VerificationError('deployment_file_missing')
    for relative in MODEL_FILES:
        path = model_root / relative
        if not path.is_file() or path.stat().st_size == 0:
            raise VerificationError(f'model_file_missing:{relative}')


def get(origin: str, path: str, *, json_response: bool):
    parsed = urlsplit(origin)
    if (parsed.scheme not in ('http', 'https') or not parsed.hostname
            or parsed.username or parsed.password or parsed.path not in ('', '/')
            or parsed.query or parsed.fragment):
        raise VerificationError('live_url_invalid')
    opener = build_opener(ProxyHandler({}), NoRedirect())
    try:
        with opener.open(Request(origin.rstrip('/') + path), timeout=5) as response:
            content = response.read(65537)
            if response.status != 200 or len(content) > 65536:
                raise ValueError()
    except Exception:
        raise VerificationError(f'live_route_failed:{path}') from None
    if json_response:
        try:
            return json.loads(content)
        except (UnicodeError, json.JSONDecodeError):
            raise VerificationError(f'live_route_invalid:{path}') from None
    return content


def verify_live(origin: str) -> None:
    page = get(origin, '/', json_response=False)
    java = get(origin, '/api/health', json_response=True)
    ai = get(origin, '/meeting-ai/health', json_response=True)
    if b'<div id="app"></div>' not in page:
        raise VerificationError('frontend_invalid')
    if not isinstance(java, dict) or java.get('status') != 'ok' or java.get('db') is not True:
        raise VerificationError('java_or_database_unhealthy')
    if ai != {'status': 'ok'}:
        raise VerificationError('meeting_ai_unhealthy')


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / 'deploy/.env')
    parser.add_argument('--model-root', type=Path, default=ROOT / 'ai-service/.models')
    parser.add_argument('--live-url', help='Also verify routes exposed by the running web container.')
    args = parser.parse_args(argv)
    try:
        verify_config(read_env(args.env_file), args.model_root)
        if args.live_url:
            verify_live(args.live_url)
    except VerificationError as error:
        print(f'Deployment verification failed: {error}', file=sys.stderr)
        return 1
    print('Deployment verification passed: config, artifacts, models'
          + (', frontend, Java/database, meeting AI.' if args.live_url else '.'))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
