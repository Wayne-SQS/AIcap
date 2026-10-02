import contextlib
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.request import urlopen

import start_service


class StartupTests(unittest.TestCase):
    def setUp(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"status":"ok"}')
            def log_message(self, *args):
                pass
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .01})
        self.thread.start()
        self.addCleanup(self.stop)
        self.origin = f'http://127.0.0.1:{self.server.server_port}'

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def free_port(self):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            return sock.getsockname()[1]

    def test_config_only_loads_allowlisted_literal_values(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            path = Path(directory) / 'fixture.env'
            path.write_text('AICAP_LLM_API_KEY="fixture-secret"\nDB_PASSWORD=private\nAICAP_LLM_MODEL=literal-$value\n', encoding='utf-8')
            start_service.load_config(path)
            self.assertEqual('fixture-secret', os.environ['AICAP_LLM_API_KEY'])
            self.assertEqual('literal-$value', os.environ['AICAP_LLM_MODEL'])
            self.assertNotIn('DB_PASSWORD', os.environ)

    def test_java_health_and_invalid_origin(self):
        start_service.check_java(self.origin)
        for url in ('file:///etc', 'http://user:secret@localhost', self.origin + '/api', self.origin + '?secret=x'):
            with self.assertRaises(start_service.StartupError):
                start_service.check_java(url)
        with self.assertRaises(start_service.StartupError):
            start_service.check_java(f'http://127.0.0.1:{self.free_port()}')

    def test_busy_port_is_not_reused(self):
        with start_service.reserve_port(self.free_port()) as sock:
            with self.assertRaises(start_service.StartupError):
                start_service.reserve_port(sock.getsockname()[1])

    def test_missing_secret_blocks_without_leaking_config(self):
        output = io.StringIO()
        with patch.dict(os.environ, {'AICAP_LLM_API_KEY': ''}), contextlib.redirect_stderr(output):
            self.assertEqual(1, start_service.main(['--environment-only', '--check-only']))
        self.assertIn('Model configuration missing', output.getvalue())

    def test_check_only_never_calls_model_or_starts_server(self):
        from meeting_agent.model_client import ChatModelClient
        output = io.StringIO()
        with patch.dict(os.environ, {'AICAP_LLM_API_KEY': 'fixture-secret', 'AICAP_LLM_BASE_URL': self.origin}), \
                patch.object(ChatModelClient, 'complete', side_effect=AssertionError('must not call')) as call, \
                patch('uvicorn.Server.run', side_effect=AssertionError('must not serve')) as serve, contextlib.redirect_stdout(output):
            self.assertEqual(0, start_service.main(['--environment-only', '--java-url', self.origin, '--port', str(self.free_port()), '--check-only']))
            call.assert_not_called()
            serve.assert_not_called()
        self.assertNotIn('fixture-secret', output.getvalue())

    def test_real_launcher_serves_health_from_other_directory(self):
        port = self.free_port()
        env = dict(os.environ, AICAP_LLM_API_KEY='fixture-secret', AICAP_LLM_BASE_URL=self.origin)
        with tempfile.TemporaryDirectory() as directory:
            process = subprocess.Popen([sys.executable, '-B', str(start_service.ROOT / 'start_service.py'),
                '--environment-only', '--java-url', self.origin, '--port', str(port)], cwd=directory,
                env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        self.fail('launcher exited before readiness')
                    try:
                        with urlopen(f'http://127.0.0.1:{port}/health', timeout=.5) as response:
                            self.assertEqual({'status': 'ok'}, json.load(response))
                            break
                    except OSError:
                        time.sleep(.1)
                else:
                    self.fail('launcher did not serve health')
            finally:
                process.terminate()
                output, _ = process.communicate(timeout=10)
                self.assertNotIn(b'fixture-secret', output)
