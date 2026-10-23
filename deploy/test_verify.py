import contextlib
import io
import json
from pathlib import Path
import re
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

    def test_authenticated_smoke_uses_token_for_read_only_routes(self):
        seen = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                seen.append((self.path, body, self.headers.get('Authorization')))
                self.send_response(200); self.end_headers()
                self.wfile.write(b'{"access_token":"fixture-token"}')
            def do_GET(self):
                seen.append((self.path, None, self.headers.get('Authorization')))
                body = (b'{"id":1,"role":"admin"}' if self.path.endswith('/auth/me')
                        else b'[]')
                self.send_response(200); self.end_headers(); self.wfile.write(body)
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever); thread.start()
        try:
            verify.authenticated_smoke(
                f'http://127.0.0.1:{server.server_port}', 'fixture-user', 'fixture-password')
        finally:
            server.shutdown(); server.server_close(); thread.join()
        self.assertEqual(['/api/auth/login', '/api/auth/me', '/api/meetings'],
                         [request[0] for request in seen])
        self.assertEqual('Bearer fixture-token', seen[1][2])

    def test_us19_journey_records_accepts_and_reconciles(self):
        seen = []

        class Handler(BaseHTTPRequestHandler):
            def reply(self, body, status=200):
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_POST(self):
                length = int(self.headers.get('Content-Length') or 0)
                payload = json.loads(self.rfile.read(length)) if length else None
                seen.append((self.path, payload))
                if self.path.endswith('/auth/login'):
                    return self.reply({'access_token': 'fixture-token'})
                if self.path.endswith('/decision'):
                    return self.reply({'id': 7, 'record_status': 'accepted'})
                return self.reply({'id': 7, 'record_status': 'proposed'})

            def do_GET(self):
                seen.append((self.path, None))
                if self.path.endswith('/progress-forecast'):
                    return self.reply({'status': 'forecast', 'basis': 'task_status_history', 'tasks_open': 4, 'tasks_done': 3, 'recent_completions': 3, 'assumptions': '按最近最多42天任务完成吞吐线性外推'})
                return self.reply({'records': [{'id': 7, 'record_status': 'accepted',
                                                'reconciliation': {'verdict': 'within_plan'}}]})

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            outcome = verify.us19_journey(f'http://127.0.0.1:{server.server_port}', 'u', 'p')
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
        self.assertEqual('forecast_accepted', outcome)
        self.assertEqual(['/api/auth/login', '/api/projects/P001/progress-forecast',
                          '/api/projects/P001/progress-forecast/records',
                          '/api/projects/P001/progress-forecast/records/7/decision',
                          '/api/projects/P001/progress-forecast/records'],
                         [path for path, _ in seen])
        self.assertEqual({'decision': 'accept'}, seen[3][1])

    def test_us19_journey_accepts_only_a_real_refusal_when_history_is_thin(self):
        class Handler(BaseHTTPRequestHandler):
            def reply(self, body, status=200):
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_POST(self):
                length = int(self.headers.get('Content-Length') or 0)
                if length:
                    self.rfile.read(length)
                if self.path.endswith('/auth/login'):
                    return self.reply({'access_token': 'fixture-token'})
                return self.reply({'detail': '当前预测未给出交付日期，不能固化为基线'}, status=422)

            def do_GET(self):
                return self.reply({'status': 'insufficient_data', 'basis': 'task_status_history', 'tasks_open': 4, 'tasks_done': 0, 'recent_completions': 0,
                                   'assumptions': '按最近最多42天任务完成吞吐线性外推'})

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            outcome = verify.us19_journey(f'http://127.0.0.1:{server.server_port}', 'u', 'p')
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
        self.assertEqual('insufficient_data_refused', outcome)

    def test_us19_journey_rejects_a_silent_success_when_history_is_thin(self):
        class Handler(BaseHTTPRequestHandler):
            def reply(self, body, status=200):
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_POST(self):
                length = int(self.headers.get('Content-Length') or 0)
                if length:
                    self.rfile.read(length)
                if self.path.endswith('/auth/login'):
                    return self.reply({'access_token': 'fixture-token'})
                return self.reply({'id': 1, 'record_status': 'proposed'})

            def do_GET(self):
                return self.reply({'status': 'insufficient_data', 'basis': 'task_status_history', 'tasks_open': 4, 'tasks_done': 0, 'recent_completions': 0,
                                   'assumptions': '按最近最多42天任务完成吞吐线性外推'})

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            with self.assertRaises(verify.VerificationError):
                verify.us19_journey(f'http://127.0.0.1:{server.server_port}', 'u', 'p')
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


class ComposeStructureTests(unittest.TestCase):
    """Structural gates: deleting a healthcheck must fail verification, not pass it."""

    @classmethod
    def setUpClass(cls):
        cls.compose = verify.COMPOSE_FILE.read_text(encoding='utf-8')

    def assertComposeFails(self, text, code):
        with self.assertRaises(verify.VerificationError) as caught:
            verify.verify_compose(text)
        self.assertEqual(code, str(caught.exception))

    def test_repository_compose_satisfies_every_structural_rule(self):
        verify.verify_compose(self.compose)

    def test_dropping_the_first_healthcheck_fails_with_its_service_name(self):
        stripped = re.sub(r'^ {4}healthcheck:\n(?:^ {6}.*\n)+', '', self.compose,
                          count=1, flags=re.M)
        self.assertNotEqual(self.compose, stripped)
        self.assertComposeFails(stripped, 'compose_healthcheck_missing:mysql')

    def test_removing_a_required_service_fails_with_its_name(self):
        stripped = re.sub(r'^ {2}ai:\n(?:^ {4,}.*\n|\n)*', '', self.compose, flags=re.M)
        self.assertComposeFails(stripped, 'compose_service_missing:ai')

    def test_required_env_with_a_default_is_rejected(self):
        weakened = self.compose.replace('${JWT_SECRET:?set JWT_SECRET in deploy/.env}',
                                        '${JWT_SECRET:-dev-secret-fallback}')
        self.assertNotEqual(self.compose, weakened)
        self.assertComposeFails(weakened, 'compose_env_not_fail_closed:JWT_SECRET')

    def test_one_defaulted_occurrence_of_a_shared_secret_is_enough_to_fail(self):
        # DB_PASSWORD is consumed by both mysql and java; weakening only the second
        # occurrence must still be caught.
        weakened = self.compose.replace(
            'DB_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in deploy/.env}',
            'DB_PASSWORD: ${DB_PASSWORD:-aicap}', 1)
        self.assertNotEqual(self.compose, weakened)
        self.assertComposeFails(weakened, 'compose_env_not_fail_closed:DB_PASSWORD')

    def test_optional_orchestrator_key_does_not_reopen_required_services(self):
        # 编排层刻意允许 AICAP_LLM_API_KEY=${...:-}(无模型也能起并如实降级);
        # 但必需服务(java/ai)里任何一处变成 :- 仍必须被拦住。
        weakened = self.compose.replace(
            'AICAP_LLM_API_KEY: ${AICAP_LLM_API_KEY:?set AICAP_LLM_API_KEY in deploy/.env}',
            'AICAP_LLM_API_KEY: ${AICAP_LLM_API_KEY:-}', 1)
        self.assertNotEqual(self.compose, weakened)
        self.assertComposeFails(weakened, 'compose_env_not_fail_closed:AICAP_LLM_API_KEY')

    def test_seed_switch_defaulting_to_true_is_rejected(self):
        weakened = self.compose.replace('${AICAP_SEED_ON_START:-false}',
                                        '${AICAP_SEED_ON_START:-true}')
        self.assertNotEqual(self.compose, weakened)
        self.assertComposeFails(weakened, 'compose_seed_default_true')

    def test_hardcoded_seed_true_is_rejected(self):
        weakened = self.compose.replace(
            'AICAP_SEED_ON_START: ${AICAP_SEED_ON_START:-false}',
            'AICAP_SEED_ON_START: true')
        self.assertComposeFails(weakened, 'compose_seed_default_true')

    def test_removing_the_seed_switch_is_rejected(self):
        stripped = self.compose.replace(
            'AICAP_SEED_ON_START: ${AICAP_SEED_ON_START:-false}\n', '')
        self.assertComposeFails(stripped, 'compose_seed_switch_missing')

    def test_nginx_sse_settings_are_a_reported_gap_never_a_failure(self):
        self.assertEqual([], verify.nginx_gaps('proxy_buffering off;'))
        self.assertEqual([], verify.nginx_gaps('add_header X-Accel-Buffering no;'))
        gaps = verify.nginx_gaps('server { listen 80; }')
        self.assertEqual(1, len(gaps))
        self.assertTrue(gaps[0].startswith('nginx_sse_not_configured'),
                        'the SSE gap must name itself for the report')

    def test_config_verification_reports_the_gap_and_keeps_exit_code_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            models = root / 'models'
            for relative in verify.MODEL_FILES:
                path = models / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture')
            env = root / '.env'
            env.write_text(
                'DB_ROOT_PASSWORD=root-password-123456\n'
                'DB_PASSWORD=app-password-1234567\n'
                'JWT_SECRET=jwt-secret-value-with-at-least-32-characters\n'
                'AICAP_LLM_API_KEY=provider-secret-value\n', encoding='utf-8')
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = verify.main(['--env-file', str(env), '--model-root', str(models)])
            self.assertEqual(0, code)
            # nginx 现已配置 SSE(proxy_buffering off / X-Accel-Buffering),
            # 所以本次 main() 应直接通过;gap 的"只报告不失败"由
            # test_nginx_sse_settings_are_a_reported_gap_never_a_failure 单独钉住。
            self.assertIn('Deployment verification passed', output.getvalue())


if __name__ == '__main__':
    unittest.main()
