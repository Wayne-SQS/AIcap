"""Local meeting service launcher. Preflight never calls the model or writes business data."""
import argparse
import importlib
import json
import os
from pathlib import Path
import socket
import sys
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parent
MODEL_KEYS = {'AICAP_LLM_API_KEY', 'AICAP_LLM_BASE_URL', 'AICAP_LLM_MODEL'}


class StartupError(Exception):
    pass


def load_config(path):
    """Read only model settings; literal values, no interpolation or secret output."""
    if path is None:
        return
    try:
        lines = path.read_text(encoding='utf-8-sig').splitlines()
    except (OSError, UnicodeError):
        raise StartupError('Cannot read config file; check --env-file.') from None
    values = {}
    for line in lines:
        key, separator, value = line.partition('=')
        key, value = key.strip(), value.strip()
        if separator and key in MODEL_KEYS:
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
                value = value[1:-1]
            values[key] = value
    os.environ.update(values)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def check_java(origin):
    try:
        url = urlsplit(origin)
        if (url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password
                or url.path not in ('', '/') or url.query or url.fragment
                or any(c.isspace() or ord(c) < 32 for c in origin)):
            raise ValueError()
        url.port
    except ValueError:
        raise StartupError('Invalid Java origin; use --java-url with an http(s) origin only.') from None
    try:
        opener = build_opener(ProxyHandler({}), NoRedirect())
        with opener.open(Request(origin.rstrip('/') + '/api/health'), timeout=3) as response:
            raw = response.read(4097)
            if response.status != 200 or len(raw) > 4096 or json.loads(raw).get('status') != 'ok':
                raise ValueError()
    except Exception:
        raise StartupError('Java health check failed; start Java or correct --java-url. No service was stopped.') from None


def reserve_port(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        sock.bind(('127.0.0.1', port))
        sock.listen(128)
        return sock
    except OSError:
        sock.close()
        raise StartupError('Listen port unavailable; identify the existing service or choose --port. No process was stopped.') from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group()
    source.add_argument('--env-file', type=Path, help='Explicit file overrides the three model environment values.')
    source.add_argument('--environment-only', action='store_true', help='Do not read any config file.')
    parser.add_argument('--java-url', default=os.environ.get('AICAP_JAVA_BASE_URL', 'http://127.0.0.1:8080'))
    parser.add_argument('--port', type=int, default=8090)
    parser.add_argument('--check-only', action='store_true', help='Validate startup prerequisites without serving or model calls.')
    args = parser.parse_args(argv)
    try:
        if not 1 <= args.port <= 65535:
            raise StartupError('Port must be between 1 and 65535.')
        default_file = ROOT.parent / 'backend/.env'
        config = None if args.environment_only else (args.env_file or (default_file if default_file.exists() else None))
        load_config(config)
        try:
            uvicorn = importlib.import_module('uvicorn')
            api = importlib.import_module('meeting_agent.api')
            model = importlib.import_module('meeting_agent.model_client')
        except ImportError:
            raise StartupError('Missing dependencies; install ai-service/requirements.txt using this Python interpreter.') from None
        try:
            model.ChatModelClient(model.ModelSettings.from_env())
        except model.ModelError:
            raise StartupError('Model configuration missing or invalid; check AICAP_LLM_API_KEY/BASE_URL/MODEL and restart.') from None
        check_java(args.java_url)
        app = api.create_app(backend_origin=args.java_url.rstrip('/'))
        with reserve_port(args.port) as sock:
            print('Preflight passed: dependencies, model configuration format, Java health, local port.', flush=True)
            print('Model credentials and authenticated analysis are NOT verified; no model request was made.', flush=True)
            if args.check_only:
                return 0
            print(f'Starting at http://127.0.0.1:{args.port}; wait for Uvicorn startup confirmation. Ctrl+C stops this service.', flush=True)
            server = uvicorn.Server(uvicorn.Config(app, host='127.0.0.1', port=args.port))
            server.run(sockets=[sock])
            return 0 if server.started else 1
    except StartupError as error:
        print('Startup blocked: ' + str(error), file=sys.stderr, flush=True)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
