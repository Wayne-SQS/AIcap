"""Real graph/model adapter with HTTP fixture; not a semantic model evaluation."""
import copy
import json
import unittest
from unittest.mock import Mock

import httpx

from meeting_agent.model_client import ChatModelClient, ModelError, ModelSettings
from meeting_agent.review_contracts import SprintReviewInput, validate_review_result
from meeting_agent.review_workflow import SprintReviewWorkflow
from meeting_agent.story_tool import ReviewStorySnapshot, StoryToolError
from meeting_agent.workflow import WorkflowError


class ReviewWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.stories = Mock()
        self.story = dict(id='US13', title='UML', status=1, sprint=2, owner_id=None,
                          description='UML生成', acceptance='图可编辑并通过保存验证')
        self.stories.get_review_stories.return_value = [ReviewStorySnapshot(**self.story)]
        self.payload = dict(meeting_id='r1', meeting_type='sprint_review', summary='US13已验收通过。',
            open_questions=[], proposed_actions=[dict(proposal_id='p1', action='update_story_status',
                story_id='US13', expected={'status': 1}, changes={'status': 2}, reason='会议确认验收通过',
                evidence=[dict(segment_id='S3', quote='US13已验收通过。')])])
        self.requests = []
        self.mode = 'ok'

        def respond(request):
            self.requests.append(request)
            if self.mode == 'timeout':
                raise httpx.ReadTimeout('provider-private-detail')
            content = 'not json' if self.mode == 'bad_json' else json.dumps(self.payload)
            return httpx.Response(200, json={'choices': [{'finish_reason': 'stop',
                'message': {'role': 'assistant', 'content': content}}]})

        model = ChatModelClient(ModelSettings(api_key='model-secret', base_url='https://model.invalid'),
                                transport=httpx.MockTransport(respond))
        self.flow = SprintReviewWorkflow(self.stories, model)

    def run_flow(self, **overrides):
        return self.flow.run(**dict(dict(meeting_id='r1', transcript='先展示效果。\n\nUS13已验收通过。',
            access_token='business-secret'), **overrides))

    def context(self):
        return json.loads(json.loads(self.requests[-1].content)['messages'][1]['content'])

    def test_acceptance_proposal_preserves_evidence_and_unknowns_without_tokens(self):
        result = self.run_flow()
        self.assertEqual('sprint_review', result.meeting_type)
        self.assertEqual(2, result.proposed_actions[0].changes.status)
        context = self.context()
        self.assertEqual(['S1', 'S3'], [s['segment_id'] for s in context['transcript_segments']])
        self.assertEqual(self.story, context['stories'][0])
        for key in ('current_sprint', 'acceptance_results', 'sprint_goal'):
            self.assertIsNone(context[key])
        body = self.requests[0].content.decode()
        self.assertNotIn('business-secret', body)
        self.assertNotIn('model-secret', body)
        self.stories.get_review_stories.assert_called_once_with(access_token='business-secret')

    def test_empty_proposals_are_valid_and_no_sprint_is_inferred(self):
        self.payload.update(summary='演示不代表验收。', proposed_actions=[], open_questions=['验收结论是什么？'])
        result = self.run_flow(transcript='US13已演示。')
        self.assertEqual([], result.proposed_actions)
        self.assertIsNone(self.context()['current_sprint'])

    def test_invalid_input_stops_before_read_or_model(self):
        for changes in ({'transcript': ''}, {'transcript': 'x' * 16001},
                        {'transcript': 'x\n' * 101}, {'current_sprint': True}, {'current_sprint': 5}):
            with self.subTest(changes=str(changes)[:80]), self.assertRaises(WorkflowError):
                self.run_flow(**changes)
        self.stories.get_review_stories.assert_not_called()
        self.assertEqual([], self.requests)

    def test_read_failure_stops_before_model(self):
        self.stories.get_review_stories.side_effect = StoryToolError('permission_denied')
        with self.assertRaises(StoryToolError):
            self.run_flow()
        self.assertEqual([], self.requests)

    def test_context_budget_and_duplicates_stop_before_model(self):
        for stories in ([ReviewStorySnapshot(**{**self.story, 'description': '中' * 50000})],
                        [ReviewStorySnapshot(**self.story)] * 2):
            self.stories.get_review_stories.return_value = stories
            with self.assertRaises(WorkflowError) as caught:
                self.run_flow()
            self.assertEqual('invalid_review_context', caught.exception.code)
        self.assertEqual([], self.requests)

    def test_bad_provenance_and_unapproved_fields_never_returned(self):
        original = copy.deepcopy(self.payload)
        for mutation in ('meeting', 'type', 'target', 'old', 'quote', 'segment', 'duplicate', 'approved', 'action'):
            self.payload = copy.deepcopy(original)
            proposal = self.payload['proposed_actions'][0]
            if mutation == 'meeting': self.payload['meeting_id'] = 'other'
            if mutation == 'type': self.payload['meeting_type'] = 'daily_scrum'
            if mutation == 'target': proposal['story_id'] = 'US99'
            if mutation == 'old': proposal['expected']['status'] = 0
            if mutation == 'quote': proposal['evidence'][0]['quote'] = '伪造证据'
            if mutation == 'segment': proposal['evidence'][0]['segment_id'] = 'S2'
            if mutation == 'duplicate': self.payload['proposed_actions'].append(copy.deepcopy(proposal))
            if mutation == 'approved': proposal['approved'] = True
            if mutation == 'action': proposal['action'] = 'update_story_sprint'
            with self.subTest(mutation=mutation), self.assertRaises(WorkflowError) as caught:
                self.run_flow()
            self.assertEqual('invalid_model_proposal', caught.exception.code)
        self.assertEqual(9, len(self.requests))

    def test_only_strict_integer_completion_allowed(self):
        for status in (0, 1, 3, True, 2.0, '2'):
            self.payload['proposed_actions'][0]['changes']['status'] = status
            with self.subTest(status=status), self.assertRaises(WorkflowError):
                self.run_flow()

    def test_already_complete_is_not_a_change(self):
        self.stories.get_review_stories.return_value = [ReviewStorySnapshot(**{**self.story, 'status': 2})]
        self.payload['proposed_actions'][0]['expected']['status'] = 2
        with self.assertRaises(WorkflowError):
            self.run_flow()

    def test_provider_errors_are_not_retried(self):
        for mode, code in (('timeout', 'provider_timeout'), ('bad_json', 'invalid_model_response')):
            self.mode = mode
            with self.assertRaises(ModelError) as caught:
                self.run_flow()
            self.assertEqual(code, caught.exception.code)
        self.assertEqual(2, len(self.requests))

    def test_subsequent_run_reads_fresh_context_and_token(self):
        self.run_flow()
        self.payload.update(meeting_id='r2', proposed_actions=[])
        self.stories.get_review_stories.return_value = []
        self.run_flow(meeting_id='r2', access_token='second-user', current_sprint=3)
        self.assertEqual([], self.context()['stories'])
        self.assertEqual(3, self.context()['current_sprint'])
        self.stories.get_review_stories.assert_called_with(access_token='second-user')

    def test_contract_checks_provenance_not_semantic_acceptance(self):
        # Deliberately valid quotation with insufficient semantics: only model/HITL
        # can assess it. Do not turn fixture tests into a false quality claim.
        context = SprintReviewInput(meeting_id='r1', stories=[ReviewStorySnapshot(**self.story)],
            transcript_segments=[{'segment_id': 'S3', 'text': 'US13已演示。'}])
        self.payload['proposed_actions'][0]['evidence'][0]['quote'] = 'US13已演示。'
        self.assertEqual(1, len(validate_review_result(context, self.payload).proposed_actions))
        with self.assertRaises(ValueError):
            SprintReviewInput.model_validate({**context.model_dump(), 'acceptance_results': [{'passed': True}]})
