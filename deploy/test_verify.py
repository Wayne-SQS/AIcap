import contextlib
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import verify


class DeploymentVerificationTests(unittest.TestCase):
    def fixture(self, root: Path, *, api_key='provider-secret-value'):
        env = root / '.env'
        env.write_text(
            'DB_ROOT_PASSWORD=root-password-123456\n'
            'DB_PASSWORD=app-password-1234567\n'
            'JWT_SECRET=jwt-secret-value-with-at-least-32-characters\n'
            f'AICAP_LLM_API_KEY={api_key}\n', encoding='utf-8')
        models = root / 'models'
        for relative in verify.MODEL_FILES:
            path = models / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture')
        return env, models

    def test_valid_config_and_models_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            env, models = self.fixture(Path(directory))
            self.assertEqual(0, verify.main(['--env-file', str(env), '--model-root', str(models)]))

    def test_placeholder_secret_fails_without_echoing_value(self):
        with tempfile.TemporaryDirectory() as directory:
            env, models = self.fixture(Path(directory), api_key='replace-with-secret')
            output = io.StringIO()
            with contextlib.redirect_stderr(output):
                self.assertEqual(1, verify.main(['--env-file', str(env), '--model-root', str(models)]))
            self.assertNotIn('replace-with-secret', output.getvalue())

    def test_live_routes_cover_frontend_java_database_and_ai(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                bodies = {
                    '/': (b'<div id="app"></div>', 'text/html'),
                    '/api/health': (json.dumps({'status': 'ok', 'db': True}).encode(), 'application/json'),
                    '/meeting-ai/health': (b'{"status":"ok"}', 'application/json'),
                }
                body, content_type = bodies[self.path]
                self.send_response(200)
                self.send_header('Content-Type', content_type)
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            verify.verify_live(f'http://127.0.0.1:{server.server_port}')
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
