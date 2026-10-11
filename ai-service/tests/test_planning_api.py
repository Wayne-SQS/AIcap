import json
import unittest
import test_api as fixtures


class PlanningApiTests(unittest.TestCase):
    stop_server = fixtures.ApiTests.stop_server

    def setUp(self):
        fixtures.ApiTests.setUp(self)
        self.transcript = '决定US13从Sprint 2移至Sprint 3。'
        self.result = {'meeting_id': 'm1', 'meeting_type': 'sprint_planning',
            'summary': '调整登录故事排期。', 'open_questions': [], 'proposed_actions': [
                {'proposal_id': 'p1', 'action': 'update_story_sprint', 'story_id': 'US13',
                 'expected': {'sprint': 2}, 'changes': {'sprint': 3}, 'reason': '会议明确决定。',
                 'evidence': [{'segment_id': 'S1', 'quote': self.transcript}]}]}
        self.overrides.update({
            '/api/stories': (200, [{'id': 'US13', 'title': '登录', 'status': 1, 'sprint': 2,
                'owner_id': None, 'description': '', 'acceptance': '', 'priority': 'Must', 'activity': 2}]),
            '/api/members/profiles': (200, []), '/api/tasks': (200, [])})

    def post(self, **changes):
        return self.client.post('/api/meetings/m1/planning/analyze', **dict({
            'json': {'meeting_type': 'sprint_planning', 'client_request_id': 'plan-1', 'target_sprint': 3},
            'headers': {'Authorization': 'Bearer fixture-token'}}, **changes))

    def test_authorized_analysis_saved_and_retry_skips_model(self):
        response = self.post()
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual('pending', response.json()['storage_status'])
        self.assertEqual('saved-1', response.json()['analysis_id'])
        self.assertEqual('/api/meetings/m1/planning-analyses', self.calls[-1][0])
        self.assertTrue(all(token == 'Bearer fixture-token' for _, token in self.calls))
        context = json.loads(self.model_calls[0][1]['content'])
        self.assertEqual(3, context['target_sprint'])
        self.assertNotIn('fixture-token', str(self.model_calls))
        self.assertEqual(response.json(), self.post().json())
        self.assertEqual(1, len(self.model_calls))

    def test_viewer_and_invalid_request_never_call_model(self):
        self.role = 'viewer'
        self.assertEqual(403, self.post().status_code)
        self.role = 'member'
        self.assertEqual(422, self.post(json={'meeting_type': 'sprint_planning',
            'client_request_id': 'plan-1', 'target_sprint': True}).status_code)
        self.assertEqual([], self.model_calls)

    def test_read_failure_or_bad_proposal_never_saves(self):
        self.overrides['/api/tasks'] = (403, {})
        self.assertEqual(403, self.post().status_code)
        self.assertEqual([], self.model_calls)
        self.overrides['/api/tasks'] = (200, [])
        self.result['proposed_actions'][0]['expected']['sprint'] = 1
        self.assertEqual(502, self.post().status_code)
        self.assertEqual([], self.save_calls)

    def test_storage_outcome_unknown_retries_exact_payload_without_new_model(self):
        self.save_status = 503
        response = self.post()
        self.assertEqual(503, response.status_code)
        self.assertEqual('storage_outcome_unknown', response.json()['detail'])
        self.assertEqual('plan-1', response.json()['client_request_id'])
        self.assertEqual(2, len(self.save_calls))
        self.assertEqual(self.save_calls[0], self.save_calls[1])
        self.assertEqual(1, len(self.model_calls))

