import hashlib
import unittest
from unittest.mock import patch
import httpx
from fastapi.testclient import TestClient
from meeting_agent.api import create_app
from meeting_agent.audio_tool import AudioReadTool, AudioReadError, MAX_AUDIO_BYTES


class AudioInputTests(unittest.TestCase):
    def setUp(self):
        self.content = b'ID3-test-content-not-a-decoder-test'
        self.sha = hashlib.sha256(self.content).hexdigest()
        self.row = dict(id='a1', meeting_id='m1', sha256=self.sha, byte_size=len(self.content), duration_ms=None,
                        url='https://must-not-follow.invalid/secret')
        self.user = dict(id=1, role='member')
        self.rows = [self.row]
        self.status = 200
        self.digest = self.sha
        self.content_type = 'audio/mpeg'
        self.calls = []
        def handler(request):
            self.calls.append((request.url.path, request.headers.get('authorization')))
            if request.url.path == '/api/auth/me': return httpx.Response(200, json=self.user)
            if request.url.path.endswith('/audio'): return httpx.Response(200, json=self.rows)
            return httpx.Response(self.status, content=self.content, headers={'content-type': self.content_type, 'x-audio-sha256': self.digest})
        self.tool = AudioReadTool('http://localhost:8080', transport=httpx.MockTransport(handler))

    def read(self): return self.tool.read(meeting_id='m1', audio_id='a1', token='fixture-token')
    def test_verified_identity_fixed_origin_and_unknown_duration(self):
        meta, content = self.read()
        self.assertEqual(self.content, content)
        self.assertEqual(self.sha, meta.sha256)
        self.assertIsNone(meta.reported_duration_ms)
        self.assertEqual('not_started', meta.transcription_status)
        self.assertEqual('not_started', meta.diarization_status)
        self.assertEqual([('/api/auth/me', 'Bearer fixture-token'), ('/api/meetings/m1/audio', 'Bearer fixture-token'), ('/api/audio/a1', 'Bearer fixture-token')], self.calls)
        self.assertNotIn('fixture-token', meta.model_dump_json())

    def test_viewer_denied_before_listing_or_downloading(self):
        self.user['role'] = 'viewer'
        with self.assertRaises(AudioReadError) as error: self.read()
        self.assertEqual(403, error.exception.status)
        self.assertEqual(1, len(self.calls))

    def test_audio_must_belong_to_meeting(self):
        self.rows = []
        with self.assertRaises(AudioReadError) as error: self.read()
        self.assertEqual(404, error.exception.status)
        self.assertEqual(2, len(self.calls))
        self.rows = [dict(self.row, meeting_id='other')]
        with self.assertRaises(AudioReadError) as error: self.read()
        self.assertEqual('invalid_audio_metadata', error.exception.code)

    def test_duplicate_and_oversized_metadata_prevent_download(self):
        for rows in ([self.row, self.row], [dict(self.row, byte_size=MAX_AUDIO_BYTES+1)], [dict(self.row, byte_size=True)]):
            self.rows = rows; self.calls.clear()
            with self.assertRaises(AudioReadError): self.read()
            self.assertEqual(2, len(self.calls))

    def test_duration_remains_reported_not_measured(self):
        self.row['duration_ms'] = 1234
        self.assertEqual('upload_metadata_unverified', self.read()[0].duration_source)
        self.row['duration_ms'] = -1
        with self.assertRaises(AudioReadError): self.read()

    def test_truncated_changed_and_overlong_bytes_are_rejected(self):
        for content in (self.content[:-1], b'X' * len(self.content), self.content + b'X'):
            self.content = content
            with self.assertRaises(AudioReadError): self.read()

    def test_wrong_hash_header_or_content_type_is_rejected(self):
        self.digest = '0' * 64
        with self.assertRaises(AudioReadError): self.read()
        self.digest = self.sha; self.content_type = 'text/html'
        with self.assertRaises(AudioReadError): self.read()

    def test_missing_redirect_and_backend_failure_are_not_success(self):
        for status, expected in ((404,404), (302,502), (503,503)):
            self.status = status
            with self.assertRaises(AudioReadError) as error: self.read()
            self.assertEqual(expected,error.exception.status)

    def test_api_auth_validation_and_safe_metadata_only_response(self):
        with patch('meeting_agent.api.AudioReadTool', return_value=self.tool), TestClient(create_app()) as client:
            path = '/api/meetings/m1/transcription/prepare'
            self.assertEqual(401,client.post(path,json={'audio_id':'a1'}).status_code)
            headers={'Authorization':'Bearer fixture-token'}
            bad=client.post(path,headers=headers,json={'audio_id':'a1','api_key':'private-value'})
            self.assertEqual(422,bad.status_code); self.assertNotIn('private-value',bad.text)
            response=client.post(path,headers=headers,json={'audio_id':'a1'})
            self.assertEqual(200,response.status_code,response.text)
            self.assertEqual('verified_bytes',response.json()['input_status'])
            self.assertNotIn('content',response.json())
            self.assertNotIn('url',response.json())
            self.user['role']='viewer'
            self.assertEqual(403,client.post(path,headers=headers,json={'audio_id':'a1'}).status_code)
