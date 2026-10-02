"""Verify the opt-in provider gateway's one-attempt budget without provider access."""
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
from http.server import ThreadingHTTPServer
import httpx
import run_daily_live as runner


class ModelGateTests(unittest.TestCase):
    def exercise(self, failure):
        client = Mock()
        if failure:
            client.complete.side_effect = RuntimeError('private-provider-detail')
        else:
            client.complete.return_value = {'summary': '合成候选'}
        with tempfile.TemporaryDirectory() as directory, patch.object(runner, 'RUN', Path(directory)), \
                patch.object(runner, 'real_client', client), patch.object(runner, 'model_calls', 0):
            server = ThreadingHTTPServer(('127.0.0.1', 0), runner.Model)
            thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01})
            thread.start()
            try:
                with httpx.Client(trust_env=False, timeout=3) as http:
                    url = f'http://127.0.0.1:{server.server_port}/chat/completions'
                    first = http.post(url, json={'messages': []})
                    second = http.post(url, json={'messages': []})
                self.assertEqual(502 if failure else 200, first.status_code)
                self.assertEqual(429, second.status_code)
                self.assertEqual(1, runner.model_calls)
                client.complete.assert_called_once_with([])
                self.assertNotIn('private-provider-detail', first.text)
                if failure:
                    self.assertEqual({'error': 'real_model_request_failed'}, json.loads((Path(directory) / 'model-error.json').read_text()))
                else:
                    self.assertEqual({'summary': '合成候选'}, json.loads((Path(directory) / 'model-candidate.json').read_text(encoding='utf-8')))
            finally:
                server.shutdown()
                server.server_close()
                thread.join()

    def test_success_cannot_trigger_second_provider_call(self):
        self.exercise(False)

    def test_failure_consumes_budget_and_does_not_echo_provider_error(self):
        self.exercise(True)
