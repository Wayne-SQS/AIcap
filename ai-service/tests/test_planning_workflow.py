import json
import unittest
from unittest.mock import Mock

import httpx

from meeting_agent.model_client import ChatModelClient, ModelError, ModelSettings
from meeting_agent.planning_context import PlanningContextError, PlanningContextReader
from meeting_agent.planning_workflow import SprintPlanningWorkflow
from meeting_agent.story_tool import PlanningStorySnapshot
from meeting_agent.task_tool import TaskToolError
from meeting_agent.workflow import WorkflowError


class PlanningWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.stories, self.members, self.tasks = Mock(), Mock(), Mock()
        self.stories.get_planning_stories.return_value = [PlanningStorySnapshot(
            id='US13', title='登录', status=0, sprint=2, owner_id=None,
            description='', acceptance=None, priority='Must', activity=2)]
        self.members.get_member_profiles.return_value = []
        self.tasks.get_tasks.return_value = []
        self.payload = {'meeting_id': 'p1', 'summary': '调整登录排期。', 'open_questions': [],
            'proposed_actions': [{'proposal_id': 'P1', 'action': 'update_story_sprint', 'story_id': 'US13',
                'expected': {'sprint': 2}, 'changes': {'sprint': 3}, 'reason': '会议明确决定。',
                'evidence': [{'segment_id': 'S1', 'quote': '决定US13移到Sprint 3。'}]}]}
        self.requests = []
        self.mode = 'ok'
        def respond(request):
            self.requests.append(request)
            if self.mode == 'timeout':
                raise httpx.ReadTimeout('provider secret')
            content = 'not json' if self.mode == 'bad_json' else json.dumps(self.payload)
            return httpx.Response(200, json={'choices': [{'finish_reason': 'stop',
                'message': {'role': 'assistant', 'content': content}}]})
        client = ChatModelClient(ModelSettings(api_key='model-secret', base_url='https://model.invalid'),
                                 transport=httpx.MockTransport(respond))
        self.flow = SprintPlanningWorkflow(PlanningContextReader(self.stories, self.members, self.tasks), client)

    def run_flow(self, **changes):
        return self.flow.run(**dict(dict(meeting_id='p1', transcript='决定US13移到Sprint 3。',
                                        access_token='business-secret', target_sprint=3), **changes))

    def test_real_adapter_with_mock_http_returns_pending_proposal(self):
        result = self.run_flow()
        self.assertEqual(3, result.proposed_actions[0].changes.sprint)
        self.assertEqual(1, len(self.requests))
        body = json.loads(self.requests[0].content)
        self.assertNotIn('business-secret', str(body))
        self.assertNotIn('model-secret', str(body))
        self.assertEqual('Bearer model-secret', self.requests[0].headers['Authorization'])
        self.assertEqual('sprint_planning', json.loads(body['messages'][1]['content'])['meeting_type'])
        self.tasks.get_tasks.assert_called_once_with(access_token='business-secret')

    def test_input_and_tool_failures_stop_before_model(self):
        with self.assertRaises(PlanningContextError):
            self.run_flow(transcript=' ')
        self.stories.get_planning_stories.assert_not_called()
        self.tasks.get_tasks.side_effect = TaskToolError('permission_denied')
        with self.assertRaises(TaskToolError):
            self.run_flow()
        self.assertEqual([], self.requests)

    def test_budget_rejection_before_model(self):
        story = self.stories.get_planning_stories.return_value[0].model_dump()
        story['description'] = '中' * 50000
        self.stories.get_planning_stories.return_value = [PlanningStorySnapshot.model_validate(story)]
        with self.assertRaises(WorkflowError) as caught:
            self.run_flow()
        self.assertEqual('invalid_planning_context', caught.exception.code)
        self.assertEqual([], self.requests)

    def test_invalid_model_result_never_returned_or_retried(self):
        self.payload['proposed_actions'][0]['expected']['sprint'] = 1
        with self.assertRaises(WorkflowError) as caught:
            self.run_flow()
        self.assertEqual('invalid_model_proposal', str(caught.exception))
        self.assertEqual(1, len(self.requests))

    def test_provider_errors_propagate_without_retry(self):
        for mode, code in (('timeout', 'provider_timeout'), ('bad_json', 'invalid_model_response')):
            self.mode = mode
            with self.assertRaises(ModelError) as caught:
                self.run_flow()
            self.assertEqual(code, str(caught.exception))
        self.assertEqual(2, len(self.requests))

    def test_repeated_calls_refresh_context_and_do_not_reuse_token_or_result(self):
        self.run_flow()
        self.payload.update(meeting_id='p2', proposed_actions=[])
        self.stories.get_planning_stories.return_value = []
        result = self.run_flow(meeting_id='p2', access_token='second-user')
        self.assertEqual([], result.proposed_actions)
        self.assertEqual(2, len(self.requests))
        context = json.loads(json.loads(self.requests[-1].content)['messages'][1]['content'])
        self.assertEqual([], context['stories'])
        self.stories.get_planning_stories.assert_called_with(access_token='second-user')
