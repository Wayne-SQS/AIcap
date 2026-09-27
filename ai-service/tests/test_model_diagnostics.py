import json
import unittest

import httpx

from meeting_agent.model_client import ChatModelClient, ModelError, ModelSettings


def envelope(content):
    return json.dumps({'choices': [{'finish_reason': 'stop',
        'message': {'role': 'assistant', 'content': content}}]}).encode()


class ModelDiagnosticsTests(unittest.TestCase):
    def test_static_diagnostics_cover_response_layers_without_exposing_values(self):
        samples = [
            (b'private invalid response', 'envelope_json_syntax'),
            (b'{"secret-key":1,"secret-key":2}', 'envelope_duplicate_key'),
            (b'\xff', 'envelope_invalid_encoding'),
            (b'{}', 'choices_structure'),
            (b'{"choices":[{}]}', 'finish_reason_missing'),
            (b'{"choices":[{"finish_reason":"stop","message":null}]}', 'message_structure'),
            (b'{"choices":[{"finish_reason":"stop","message":{"role":"tool"}}]}', 'unexpected_message'),
            (envelope(None), 'content_not_text'),
            (envelope('private invalid JSON'), 'content_json_syntax'),
            (envelope('{"secret-key":1,"secret-key":2}'), 'content_duplicate_key'),
            (envelope('[]'), 'content_structure'),
        ]
        for raw, diagnostic in samples:
            with self.subTest(diagnostic=diagnostic):
                client = ChatModelClient(ModelSettings(api_key='secret-token'),
                    transport=httpx.MockTransport(lambda request: httpx.Response(200, content=raw)))
                with self.assertRaises(ModelError) as caught:
                    client.complete([])
                self.assertEqual('invalid_model_response', str(caught.exception))
                self.assertEqual(diagnostic, caught.exception.diagnostic)
                self.assertNotIn('secret', repr(caught.exception.__dict__))

    def test_unknown_diagnostic_is_discarded_and_valid_response_unchanged(self):
        self.assertIsNone(ModelError('invalid_model_response', diagnostic='secret-key').diagnostic)
        client = ChatModelClient(ModelSettings(api_key='secret-token'),
            transport=httpx.MockTransport(lambda request: httpx.Response(200, content=envelope('{"ok":true}'))))
        self.assertEqual({'ok': True}, client.complete([]))
