"""Review API through real HTTP adapters against an isolated Java fixture."""
import json
import unittest
import test_api as fixtures


class ReviewApiTests(unittest.TestCase):
    stop_server = fixtures.ApiTests.stop_server

    def setUp(self):
        fixtures.ApiTests.setUp(self)
        self.transcript = 'US13已验收通过。'
        self.result = dict(meeting_id='m1', meeting_type='sprint_review', summary='US13验收通过。',
            open_questions=[], proposed_actions=[dict(proposal_id='p1', action='update_story_status',
                story_id='US13', expected={'status': 1}, changes={'status': 2}, reason='会议确认验收通过',
                evidence=[dict(segment_id='S1', quote=self.transcript)])])
        self.overrides['/api/stories'] = (200, [dict(id='US13', title='登录', status=1,
            sprint=2, owner_id=None, description='登录', acceptance='支持正确凭据登录')])

    def post(self, **changes):
        return self.client.post('/api/meetings/m1/review/analyze', **dict({
            'json': {'meeting_type': 'sprint_review', 'client_request_id': 'review-1'},
            'headers': {'Authorization': 'Bearer fixture-token'}}, **changes))

    def test_save_and_lookup_use_review_resource_and_retry_skips_model(self):
        response = self.post()
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual('sprint_review', response.json()['meeting_type'])
        self.assertEqual('pending', response.json()['storage_status'])
        self.assertEqual('saved-1', response.json()['analysis_id'])
        self.assertEqual(['/api/auth/me', '/api/meetings/m1',
            '/api/meetings/m1/review-analyses/by-request/review-1', '/api/stories',
            '/api/meetings/m1/review-analyses'], [path for path, _ in self.calls])
        self.assertTrue(all(token == 'Bearer fixture-token' for _, token in self.calls))
        self.assertNotIn('fixture-token', str(self.model_calls))
        self.assertIsNone(json.loads(self.model_calls[0][1]['content'])['current_sprint'])
        # Persisted request recovery must not depend on current story state/model availability.
        self.overrides['/api/stories'] = (503, {})
        self.assertEqual(response.json(), self.post().json())
        self.assertEqual(1, len(self.model_calls))
        self.assertEqual(1, len(self.save_calls))

    def test_auth_and_meeting_access_checked_before_model_and_on_retry(self):
        self.assertEqual(401, self.post(headers={}).status_code)
        self.assertEqual([], self.calls)
        self.role = 'viewer'
        self.assertEqual(403, self.post().status_code)
        self.role = 'member'
        self.overrides['/api/meetings/m1'] = (403, {})
        self.assertEqual(403, self.post().status_code)
        self.assertEqual([], self.model_calls)
        del self.overrides['/api/meetings/m1']
        self.assertEqual(200, self.post().status_code)
        self.role = 'viewer'
        self.assertEqual(403, self.post().status_code)
        self.assertEqual(1, len(self.model_calls))

    def test_invalid_request_rejected_without_echoing_fields(self):
        for change in ({'meeting_type': 'daily_scrum'}, {'current_sprint': True},
                       {'transcript': 'private-value'}, {'approved': True}, {'client_request_id': ''}):
            body = {'meeting_type': 'sprint_review', 'client_request_id': 'review-1', **change}
            response = self.post(json=body)
            self.assertEqual(422, response.status_code)
            self.assertNotIn('private-value', response.text)
        self.assertEqual([], self.calls)

    def test_read_or_model_validation_failure_never_saves(self):
        self.overrides['/api/stories'] = (403, {})
        self.assertEqual(403, self.post().status_code)
        self.assertEqual([], self.model_calls)
        del self.overrides['/api/stories']  # Default response lacks Review acceptance context.
        self.assertEqual(502, self.post().status_code)
        self.assertEqual([], self.save_calls)

    def test_non_completion_and_false_evidence_are_rejected(self):
        self.result['proposed_actions'][0]['changes']['status'] = 0
        self.assertEqual(502, self.post().status_code)
        self.result['proposed_actions'][0]['changes']['status'] = 2
        self.result['proposed_actions'][0]['evidence'][0]['quote'] = '伪造证据'
        self.assertEqual(502, self.post().status_code)
        self.assertEqual([], self.save_calls)

    def test_unknown_outcome_retries_exact_payload_then_reports_request_id(self):
        self.save_status = 503
        response = self.post()
        self.assertEqual(503, response.status_code)
        self.assertEqual({'detail': 'storage_outcome_unknown', 'client_request_id': 'review-1'}, response.json())
        self.assertEqual(2, len(self.save_calls))
        self.assertEqual(self.save_calls[0], self.save_calls[1])
        self.assertEqual(1, len(self.model_calls))

    def test_stale_save_conflict_is_not_retried(self):
        self.save_status = 409
        self.assertEqual(409, self.post().status_code)
        self.assertEqual(1, len(self.save_calls))

    def test_empty_review_is_persisted_as_no_changes(self):
        self.result['proposed_actions'] = []
        self.assertEqual('no_changes', self.post().json()['storage_status'])
