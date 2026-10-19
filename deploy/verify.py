"""Fail-closed deployment configuration and live-route verification."""
from __future__ import annotations

import argparse
import json
import os
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

COMPOSE_FILE = ROOT / 'deploy/compose.yaml'
NGINX_FILE = ROOT / 'deploy/nginx.conf'
# Containers the deployment cannot run without. Each must keep a healthcheck, since
# `depends_on: condition: service_healthy` in the same file has nothing to gate on
# otherwise. Deleting one of those healthchecks used to pass verification silently.
COMPOSE_SERVICES = ('mysql', 'java', 'ai')
# A missing value must stop the stack, never fall back to a weak built-in default.
FAIL_CLOSED_ENV = ('DB_ROOT_PASSWORD', 'DB_PASSWORD', 'JWT_SECRET', 'AICAP_LLM_API_KEY')
SEED_SWITCH = 'AICAP_SEED_ON_START'
# Streaming plumbing nginx needs before any text/event-stream route is proxied through it.
SSE_MARKERS = ('proxy_buffering off', 'x-accel-buffering', 'text/event-stream')
STRUCTURE_HINTS = {
    'compose_service_missing': 'deploy/compose.yaml must define this service',
    'compose_healthcheck_missing': 'this service must declare a healthcheck',
    'compose_env_not_fail_closed': 'keep ${VAR:?message} so a missing value refuses to start',
    'compose_seed_default_true': 'seeded 123456 demo accounts must not be the default',
    'compose_seed_switch_missing': 'keep the switch visible in deploy/compose.yaml',
}


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


def read_text(path: Path, code: str) -> str:
    try:
        return path.read_text(encoding='utf-8-sig')
    except (OSError, UnicodeError):
        raise VerificationError(code) from None


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(' '))


def compose_services(text: str) -> dict[str, str]:
    """Split the `services:` block into one source chunk per top-level service.

    Deliberately stdlib-only and indentation-based: this checker must stay runnable
    with a bare Python 3.12 venv, without pulling in a YAML parser.
    """
    blocks: dict[str, list[str]] = {}
    current = None
    inside = False
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            if current is not None:
                blocks[current].append(line)
            continue
        depth = indent_of(line)
        if depth == 0:
            inside = stripped == 'services:'
            current = None
            continue
        if not inside:
            continue
        if depth == 2 and stripped.endswith(':'):
            current = stripped[:-1].strip()
            blocks[current] = [line]
            continue
        if current is not None:
            blocks[current].append(line)
    return {service: '\n'.join(lines) for service, lines in blocks.items()}


def verify_compose(text: str) -> None:
    """Structural assertions on deploy/compose.yaml; offline, no daemon needed."""
    services = compose_services(text)
    for service in COMPOSE_SERVICES:
        if service not in services:
            raise VerificationError(f'compose_service_missing:{service}')
        if not re.search(r'^[ ]+healthcheck:', services[service], re.M):
            raise VerificationError(f'compose_healthcheck_missing:{service}')
    # 只约束"没有它起不来"的服务(mysql/java/ai)。agent-orchestrator 刻意允许
    # AICAP_LLM_API_KEY=${...:-}(没有模型也能起,并如实上报 llm_configured=false);
    # 把规则套到整份文件,会把这条有意设计误判成"弱默认值回潮"。
    required = '\n'.join(services[service] for service in COMPOSE_SERVICES)
    for key in FAIL_CLOSED_ENV:
        # 必需服务里每一处都必须 fail-closed:一处 `${VAR:-default}` 就会重新打开弱默认值
        # (DB_PASSWORD 在 mysql 与 java 各出现一次)。
        if not re.search(rf'\$\{{{key}:\?', required) or re.search(rf'\$\{{{key}:-', required):
            raise VerificationError(f'compose_env_not_fail_closed:{key}')
    if re.search(rf'^[ ]*{SEED_SWITCH}:[ ]*["\']?true["\']?[ ]*$', text, re.M):
        raise VerificationError('compose_seed_default_true')
    switch = re.search(rf'{SEED_SWITCH}:[ ]*["\']?\$\{{{SEED_SWITCH}:-([^}}]*)\}}', text)
    if switch is None:
        raise VerificationError('compose_seed_switch_missing')
    if switch.group(1).strip().lower() == 'true':
        raise VerificationError('compose_seed_default_true')


def nginx_gaps(text: str) -> list[str]:
    """Report SSE plumbing as a gap instead of asserting it.

    The only `text/event-stream` endpoint in this repository lives in
    agent-orchestrator, which this compose does not deploy (the stack is four
    containers: MySQL, Java, meeting Agent, Nginx). So nginx.conf carries no
    streaming settings today and none are required yet. It is recorded as a
    non-fatal gap rather than fixed here: changing nginx behaviour the deployment
    does not need is a functional change, not a hardening one.
    """
    lowered = text.lower()
    if any(marker in lowered for marker in SSE_MARKERS):
        return []
    return ['nginx_sse_not_configured: no proxy_buffering / X-Accel-Buffering / text/event-stream']


def verify_config(values: dict[str, str], model_root: Path) -> list[str]:
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
    verify_compose(read_text(COMPOSE_FILE, 'compose_file_unreadable'))
    gaps = nginx_gaps(read_text(NGINX_FILE, 'nginx_config_unreadable'))
    for relative in MODEL_FILES:
        path = model_root / relative
        if not path.is_file() or path.stat().st_size == 0:
            raise VerificationError(f'model_file_missing:{relative}')
    return gaps


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


def authenticated_smoke(origin: str, username: str, password: str) -> None:
    if not username or not password:
        raise VerificationError('smoke_credentials_missing')
    opener = build_opener(ProxyHandler({}), NoRedirect())
    try:
        payload = json.dumps({'username': username, 'password': password}).encode()
        with opener.open(Request(origin.rstrip('/') + '/api/auth/login', data=payload,
                                 headers={'Content-Type': 'application/json'}, method='POST'),
                         timeout=5) as response:
            login = json.loads(response.read(65537))
        token = login.get('access_token') if isinstance(login, dict) else None
        if not isinstance(token, str) or not token:
            raise ValueError()
        headers = {'Authorization': 'Bearer ' + token}
        with opener.open(Request(origin.rstrip('/') + '/api/auth/me', headers=headers),
                         timeout=5) as response:
            me = json.loads(response.read(65537))
        with opener.open(Request(origin.rstrip('/') + '/api/meetings', headers=headers),
                         timeout=5) as response:
            meetings = json.loads(response.read(65537))
        if (not isinstance(me, dict) or type(me.get('id')) is not int
                or me.get('role') not in ('admin', 'owner', 'member', 'viewer')
                or not isinstance(meetings, list)):
            raise ValueError()
    except Exception:
        raise VerificationError('authenticated_smoke_failed') from None


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


def failure_hint(error: VerificationError) -> str:
    note = STRUCTURE_HINTS.get(str(error).split(':', 1)[0])
    return f' ({note})' if note else ''


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / 'deploy/.env')
    parser.add_argument('--model-root', type=Path, default=ROOT / 'ai-service/.models')
    parser.add_argument('--live-url', help='Also verify routes exposed by the running web container.')
    parser.add_argument('--smoke-login', action='store_true',
                        help='Use AICAP_SMOKE_USERNAME/PASSWORD for an authenticated read-only smoke test.')
    args = parser.parse_args(argv)
    try:
        gaps = verify_config(read_env(args.env_file), args.model_root)
        if args.live_url:
            verify_live(args.live_url)
        if args.smoke_login:
            if not args.live_url:
                raise VerificationError('smoke_login_requires_live_url')
            authenticated_smoke(args.live_url, os.environ.get('AICAP_SMOKE_USERNAME', ''),
                                os.environ.get('AICAP_SMOKE_PASSWORD', ''))
    except VerificationError as error:
        print(f'Deployment verification failed: {error}{failure_hint(error)}', file=sys.stderr)
        return 1
    for gap in gaps:
        print(f'Deployment verification gap (non-fatal): {gap}')
    checked = 'Deployment verification passed: config, artifacts, models, compose structure'
    if args.live_url:
        checked += ', frontend, Java/database, meeting AI'
    if args.smoke_login:
        checked += ', authenticated read-only flow'
    print(checked + '.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
