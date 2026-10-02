import copy
import unittest
import test_assignment_context as fixtures
from meeting_agent.assignment_store import JavaAssignmentStore
from meeting_agent.analysis_store import StorageError


class AssignmentStoreTests(unittest.TestCase):
    setUp = fixtures.AssignmentContextApiTests.setUp
    stop_server = fixtures.AssignmentContextApiTests.stop_server

    def post(self, **kwargs):
        body = {'story_ids': ['US13'], 'target_sprint': 2, 'requirements': [
            {'dimension': 'tech_stack', 'name': 'Python', 'minimum_level': 3}], 'client_request_id': 'r1'}
        return self.client.post('/api/meetings/m1/assignment/suggestions', **dict(dict(
            headers={'Authorization': 'Bearer fixture-token'}, json=body), **kwargs))

    def test_save_then_retry_returns_original_without_reloading_changed_facts(self):
        first = self.post()
        self.assertEqual(200, first.status_code, first.text)
        self.assertIsNone(first.json()['review'])
        self.assertEqual('no_recorded_skill_match', first.json()['result']['status'])
        self.calls.clear()
        self.overrides['/api/stories'] = (503, {})
        self.assertEqual(first.json(), self.post().json())
        self.assertEqual(1, len(self.save_calls))
        self.assertEqual([], self.model_calls)
        self.assertFalse(any(p == '/api/stories' for p, t in self.calls))

    def test_request_id_reuse_with_different_requirements_conflicts(self):
        saved = self.post().json()
        body = dict(saved['input'], client_request_id='r1')
        body['requirements'][0]['minimum_level'] = 4
        self.assertEqual(409, self.post(json=body).status_code)
        self.assertEqual(1, len(self.save_calls))

    def test_transient_save_retries_exact_payload_and_reports_unknown_outcome(self):
        self.save_status = 503
        response = self.post()
        self.assertEqual(503, response.status_code)
        self.assertEqual('storage_outcome_unknown', response.json()['detail'])
        self.assertEqual(2, len(self.save_calls))
        self.assertEqual(self.save_calls[0], self.save_calls[1])
        self.assertEqual([], self.model_calls)

    def test_auth_and_unknown_story_never_save(self):
        self.assertEqual(401, self.post(headers={}).status_code)
        self.role = 'viewer'
        self.assertEqual(403, self.post().status_code)
        self.role = 'member'
        self.overrides['/api/stories'] = (200, [])
        self.assertEqual(422, self.post().status_code)
        self.assertEqual([], self.save_calls)

    def test_storage_response_recomputed_to_reject_forged_scores_and_wrong_scope(self):
        original = self.post().json()
        store = JavaAssignmentStore(self.origin)
        for fault in ('rank', 'meeting', 'input'):
            saved = copy.deepcopy(original)
            if fault == 'rank': saved['result']['candidates'][0]['rank'] = 9
            elif fault == 'meeting': saved['meeting_id'] = 'm2'
            else: saved['input']['target_sprint'] = 3
            with self.assertRaises(StorageError) as error:
                store._record(saved, 'm1', 'r1')
            self.assertEqual('invalid_storage_response', error.exception.code)
