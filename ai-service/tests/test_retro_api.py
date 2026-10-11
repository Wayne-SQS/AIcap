import unittest
import test_api as fixtures


class RetroApiTests(unittest.TestCase):
    stop_server = fixtures.ApiTests.stop_server

    def setUp(self):
        fixtures.ApiTests.setUp(self)
        self.transcript = '决定补充发布检查表，负责人和截止时间待确认。'
        self.overrides['/api/members/profiles'] = (200, [])
        self.result = dict(meeting_id='m1', meeting_type='sprint_retrospective', summary='补充检查表',
            decisions=[], open_questions=['由谁负责？截止时间是什么？'], proposed_actions=[dict(
                proposal_id='p1', action='create_action_item', changes=dict(title='补充检查表',
                    description='整理步骤', owner_id=None, deadline_text=None), reason='会议决定',
                evidence=[dict(segment_id='S1',quote=self.transcript)])])

    def post(self, **changes):
        return self.client.post('/api/meetings/m1/retro/analyze', **dict({
            'headers': {'Authorization':'Bearer fixture-token'},
            'json': {'meeting_type':'sprint_retrospective','client_request_id':'retro-1'}}, **changes))

    def test_save_and_retry_return_existing_candidate_without_model(self):
        response=self.post()
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual('pending',response.json()['storage_status'])
        self.assertEqual('/api/meetings/m1/retro-analyses',self.calls[-1][0])
        self.assertIsNone(response.json()['proposed_actions'][0]['changes']['owner_id'])
        self.overrides['/api/members/profiles']=(503,{})
        self.assertEqual(response.json(),self.post().json())
        self.assertEqual(1,len(self.model_calls))
        self.assertTrue(all(token=='Bearer fixture-token' for _,token in self.calls))
        self.assertNotIn('fixture-token',str(self.model_calls))

    def test_authorization_rechecked_before_lookup_and_model(self):
        self.assertEqual(401,self.post(headers={}).status_code)
        self.assertEqual([],self.calls)
        self.role='viewer'; self.assertEqual(403,self.post().status_code)
        self.assertEqual([],self.model_calls)
        self.role='member'; self.assertEqual(200,self.post().status_code)
        self.role='viewer'; self.assertEqual(403,self.post().status_code)
        self.assertEqual(1,len(self.model_calls))

    def test_member_read_failure_and_invalid_candidate_never_saved(self):
        self.overrides['/api/members/profiles']=(403,{})
        self.assertEqual(403,self.post().status_code)
        self.assertEqual([],self.model_calls)
        self.overrides['/api/members/profiles']=(200,[])
        self.result['proposed_actions'][0]['changes']['owner_id']=999
        self.assertEqual(502,self.post().status_code)
        self.assertEqual([],self.save_calls)

    def test_unknown_outcome_retries_exact_payload(self):
        self.save_status=503
        response=self.post()
        self.assertEqual({'detail':'storage_outcome_unknown','client_request_id':'retro-1'},response.json())
        self.assertEqual(2,len(self.save_calls)); self.assertEqual(self.save_calls[0],self.save_calls[1])
        self.assertEqual(1,len(self.model_calls))

    def test_invalid_inputs_and_conflicts_do_not_get_retried(self):
        self.assertEqual(422,self.post(json={'meeting_type':'sprint_retrospective','client_request_id':'retro-1','current_sprint':True}).status_code)
        self.assertEqual([],self.calls)
        self.save_status=409; self.assertEqual(409,self.post().status_code)
        self.assertEqual(1,len(self.save_calls))

    def test_no_action_candidate_is_saved_as_no_changes(self):
        self.result['proposed_actions']=[]
        self.assertEqual('no_changes',self.post().json()['storage_status'])
