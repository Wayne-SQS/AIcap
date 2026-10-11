import unittest
import test_api as fixtures


class RefinementApiTests(unittest.TestCase):
    stop_server = fixtures.ApiTests.stop_server

    def setUp(self):
        fixtures.ApiTests.setUp(self)
        self.transcript = '决定新增周报导出需求，验收和排期待确认。'
        self.overrides['/api/stories'] = (200, [dict(id='US13', title='登录', status=1,
            sprint=2, owner_id=None, description=None, acceptance=None, priority='Must', activity=2)])
        self.result = dict(meeting_id='m1', meeting_type='backlog_refinement', summary='周报导出',
            open_questions=['验收要求、优先级、Sprint和活动编号是什么？'], proposed_actions=[dict(
                proposal_id='p1', action='create_story', changes=dict(title='周报导出',
                    description=None, acceptance=None, priority=None, sprint=None, activity=None), reason='会议决定',
                evidence=[dict(segment_id='S1',quote=self.transcript)])])

    def post(self, **changes):
        return self.client.post('/api/meetings/m1/refinement/analyze', **dict({
            'headers': {'Authorization':'Bearer fixture-token'},
            'json': {'meeting_type':'backlog_refinement','client_request_id':'refinement-1'}}, **changes))

    def test_save_and_retry_return_existing_candidate_without_model(self):
        response=self.post()
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual('pending',response.json()['storage_status'])
        self.assertEqual('/api/meetings/m1/refinement-analyses',self.calls[-1][0])
        self.assertIsNone(response.json()['proposed_actions'][0]['changes']['sprint'])
        self.overrides['/api/stories']=(503,{})
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

    def test_story_read_failure_and_invalid_candidate_never_saved(self):
        self.overrides['/api/stories']=(403,{})
        self.assertEqual(403,self.post().status_code)
        self.assertEqual([],self.model_calls)
        self.overrides['/api/stories']=(200,[])
        self.result['proposed_actions'][0]['changes']['sprint']=999
        self.assertEqual(502,self.post().status_code)
        self.assertEqual([],self.save_calls)

    def test_unknown_outcome_retries_exact_payload(self):
        self.save_status=503
        response=self.post()
        self.assertEqual({'detail':'storage_outcome_unknown','client_request_id':'refinement-1'},response.json())
        self.assertEqual(2,len(self.save_calls)); self.assertEqual(self.save_calls[0],self.save_calls[1])
        self.assertEqual(1,len(self.model_calls))

    def test_invalid_inputs_and_conflicts_do_not_get_retried(self):
        self.assertEqual(422,self.post(json={'meeting_type':'backlog_refinement','client_request_id':'refinement-1','current_sprint':True}).status_code)
        self.assertEqual([],self.calls)
        self.save_status=409; self.assertEqual(409,self.post().status_code)
        self.assertEqual(1,len(self.save_calls))

    def test_no_action_candidate_is_saved_as_no_changes(self):
        self.result['proposed_actions']=[]
        self.assertEqual('no_changes',self.post().json()['storage_status'])
