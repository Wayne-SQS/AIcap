import unittest
from pydantic import ValidationError
from meeting_agent.assignment_context import AssignmentContextRequest, prepare_assignment, AssignmentContextError
from meeting_agent.planning_context import SprintPlanningInput
from test_task_tool import task_row
import test_api as fixtures


def story(**changes):
    return dict(dict(id='US13', title='登录', status=0, sprint=2, owner_id=7,
        description='', acceptance='', priority='Must', activity=2), **changes)


def member(**changes):
    return dict(dict(user_id=7, display_name='成员甲', role='member', six_week_capacity_hours=60,
        title='', tech_stack=[], capabilities=[], process_domains=[], summary='', years_experience=0), **changes)


class AssignmentContextTests(unittest.TestCase):
    def context(self, **changes):
        return SprintPlanningInput.model_validate(dict(dict(meeting_id='m1',
            transcript_segments=[dict(segment_id='S1', text='讨论分工')], stories=[story()],
            members=[member()], tasks=[task_row(status=1)]), **changes))

    def report(self, context=None, sprint=2):
        return prepare_assignment(context or self.context(), AssignmentContextRequest(story_ids=['US13'], target_sprint=sprint))

    def test_raw_units_preserved_without_remaining_capacity_or_ranking(self):
        result = self.report()
        self.assertEqual('not_ready', result.status)
        self.assertEqual(60, result.members[0].profile.six_week_capacity_hours)
        self.assertIsNone(result.members[0].sprint_remaining_hours)
        self.assertIsNone(result.story_estimates)
        self.assertEqual((8, 13), (result.tasks[0].hours, result.tasks[0].estimated_hours))
        self.assertEqual(['T01'], result.members[0].target_sprint_task_ids)
        self.assertIn('sprint_capacity_unavailable', [g.code for g in result.gaps])

    def test_completed_cancelled_excluded_from_active_but_retained_as_facts(self):
        context = self.context(tasks=[task_row(id=f'T0{i}',status=i) for i in range(4)])
        result = self.report(context)
        self.assertEqual(['T00','T01'], result.members[0].active_task_ids)
        self.assertEqual(4,len(result.tasks))

    def test_unknown_scope_and_sprint_four_are_not_empty_availability(self):
        for sprint in (None,4):
            self.assertIsNone(self.report(sprint=sprint).members[0].target_sprint_task_ids)
        self.assertEqual([], self.report(self.context(tasks=[])).members[0].target_sprint_task_ids)

    def test_unresolved_links_and_empty_profiles_are_reported(self):
        result = self.report(self.context(stories=[story(owner_id=99)], tasks=[task_row(owner_id=88,kanban_card_id='US99')]))
        codes={g.code for g in result.gaps}
        self.assertTrue({'story_owner_unresolved','task_owner_unresolved','task_story_unresolved','profile_dimensions_empty'} <= codes)
        self.assertEqual('US99',result.tasks[0].kanban_card_id)
        self.assertIn('members_empty',{g.code for g in self.report(self.context(members=[])).gaps})

    def test_request_validation_missing_target_and_unknown_story(self):
        for body in ({'story_ids':['US13']}, {'story_ids':['US13','US13'],'target_sprint':2},
                     {'story_ids':['US13'],'target_sprint':True}, {'story_ids':[],'target_sprint':None},
                     {'story_ids':['US13'],'target_sprint':2,'approved':True}):
            with self.assertRaises(ValidationError):
                AssignmentContextRequest.model_validate(body)
        with self.assertRaises(AssignmentContextError):
            self.report(self.context(stories=[]))

    def test_multiple_owned_stories_viewer_profile_and_zero_capacity_are_not_reinterpreted(self):
        result = self.report(self.context(stories=[story(),story(id='US14')],members=[member(role='viewer',six_week_capacity_hours=0)]))
        self.assertEqual(['US13','US14'],result.members[0].owned_story_ids)
        self.assertEqual('viewer',result.members[0].profile.role)
        self.assertEqual(0,result.members[0].profile.six_week_capacity_hours)
        self.assertIsNone(result.members[0].sprint_available_hours)


class AssignmentContextApiTests(unittest.TestCase):
    stop_server = fixtures.ApiTests.stop_server
    def setUp(self):
        fixtures.ApiTests.setUp(self)
        profile=member()
        profile['capacity_hours']=profile.pop('six_week_capacity_hours')
        self.overrides.update({'/api/stories':(200,[story()]), '/api/members/profiles':(200,[profile]),
            '/api/tasks':(200,[task_row(status=1)])})

    def post(self, **kwargs):
        return self.client.post('/api/meetings/m1/assignment/context', **dict(dict(
            headers={'Authorization':'Bearer fixture-token'}, json={'story_ids':['US13'],'target_sprint':2}), **kwargs))

    def test_authenticated_read_sequence_never_models_or_saves(self):
        response=self.post()
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual('read_only_preparation',response.json()['scope'])
        self.assertEqual(['/api/auth/me','/api/meetings/m1','/api/stories','/api/members/profiles','/api/tasks'],[p for p,t in self.calls])
        self.assertTrue(all(t=='Bearer fixture-token' for p,t in self.calls))
        self.assertNotIn('fixture-token',response.text)
        self.assertEqual([],self.model_calls)
        self.assertEqual([],self.save_calls)

    def test_permissions_and_unknown_targets(self):
        self.assertEqual(401,self.post(headers={}).status_code)
        self.role='viewer'
        self.assertEqual(403,self.post().status_code)
        self.role='member'
        self.overrides['/api/meetings/m1']=(403,{})
        self.assertEqual(403,self.post().status_code)
        del self.overrides['/api/meetings/m1']
        self.assertEqual(422,self.post(json={'story_ids':['US99'],'target_sprint':2}).status_code)
        self.assertEqual([],self.model_calls)

    def test_invalid_input_prevents_reads_and_does_not_echo(self):
        response=self.post(json={'story_ids':['US13'],'target_sprint':2,'api_key':'private-value'})
        self.assertEqual(422,response.status_code)
        self.assertNotIn('private-value',response.text)
        self.assertEqual([],self.calls)

    def test_reader_failure_cannot_return_partial_context(self):
        self.overrides['/api/tasks']=(503,{})
        response=self.post()
        self.assertEqual(502,response.status_code)
        self.assertNotIn('members',response.json())
        self.assertEqual([],self.model_calls)
        self.assertEqual([],self.save_calls)

    def test_recommendations_are_authenticated_read_only_and_explain_unknowns(self):
        body={'story_ids':['US13'],'target_sprint':2,'requirements':[{'dimension':'tech_stack','name':'Python','minimum_level':3}]}
        path='/api/meetings/m1/assignment/recommendations'
        response=self.client.post(path,json=body,headers={'Authorization':'Bearer fixture-token'})
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual('no_recorded_skill_match',response.json()['status'])
        self.assertEqual('unknown',response.json()['candidates'][0]['capacity_check'])
        self.assertEqual('caller_supplied',response.json()['requirements_source'])
        self.assertEqual([],self.model_calls)
        self.assertEqual([],self.save_calls)
        self.assertEqual(401,self.client.post(path,json=body).status_code)
        self.role='viewer'
        self.assertEqual(403,self.client.post(path,json=body,headers={'Authorization':'Bearer fixture-token'}).status_code)
