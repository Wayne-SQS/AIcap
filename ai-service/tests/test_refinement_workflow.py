"""Real LangGraph, read/model fixtures; semantic quality is not inferred from these tests."""
import copy
import json
import unittest
from unittest.mock import Mock
from meeting_agent.refinement_workflow import BacklogRefinementWorkflow
from meeting_agent.refinement_contracts import BacklogRefinementInput, validate_refinement_result
from meeting_agent.model_client import ModelError
from meeting_agent.story_tool import StoryToolError
from meeting_agent.workflow import WorkflowError


class RefinementWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.stories, self.model = Mock(), Mock()
        self.stories.get_refinement_stories.return_value = [dict(id='US13', title='登录接口', description=None,
            acceptance='', status=1, sprint=2, owner_id=None, priority='Must', activity=2)]
        self.quote = '决定新增周报导出，验收要求导出包含本周已完成故事的CSV。'
        self.payload = dict(meeting_id='f1', meeting_type='backlog_refinement', summary='确认新增周报导出。',
            proposed_actions=[dict(proposal_id='p1', action='create_story',
                changes=dict(title='周报导出', description=None, acceptance='导出包含本周已完成故事的CSV', priority=None, sprint=None, activity=None),
                reason='会议明确决定新增', evidence=[dict(segment_id='S3', quote=self.quote)])],
            open_questions=['请确认优先级、Sprint及活动编号。'])
        self.model.complete.side_effect = lambda messages: copy.deepcopy(self.payload)
        self.flow = BacklogRefinementWorkflow(self.stories, self.model)

    def run_flow(self, **overrides):
        return self.flow.run(**dict(dict(meeting_id='f1', transcript='需求细化。\n\n'+self.quote,
            access_token='private-token', current_sprint=2), **overrides))

    def test_candidate_unknown_fields_snapshot_and_token_isolation(self):
        result = self.run_flow()
        changes = result.proposed_actions[0].changes
        self.assertIsNone(changes.sprint)
        self.assertIsNone(changes.priority)
        self.assertIsNone(changes.activity)
        self.assertIsNone(changes.description)
        self.assertEqual('导出包含本周已完成故事的CSV', changes.acceptance)
        self.stories.get_refinement_stories.assert_called_once_with(access_token='private-token')
        messages = self.model.complete.call_args.args[0]
        context = json.loads(messages[1]['content'])
        self.assertEqual(['S1', 'S3'], [s['segment_id'] for s in context['transcript_segments']])
        self.assertIsNone(context['stories'][0]['description'])
        self.assertEqual('', context['stories'][0]['acceptance'])
        self.assertNotIn('private-token', str(messages))
        self.assertEqual(1, self.model.complete.call_count)

    def test_empty_output_and_unknown_acceptance_supported(self):
        self.payload['proposed_actions'][0]['changes']['acceptance'] = None
        self.assertIsNone(self.run_flow().proposed_actions[0].changes.acceptance)
        self.payload.update(proposed_actions=[], summary='尚在讨论，未决定新增。')
        self.assertEqual([], self.run_flow().proposed_actions)

    def test_invalid_inputs_stop_before_tools(self):
        for override in ({'transcript': ''}, {'transcript': 'x'*16001}, {'transcript': 'x\n'*101}, {'current_sprint': True}):
            with self.assertRaises(WorkflowError): self.run_flow(**override)
        self.stories.get_refinement_stories.assert_not_called()
        self.model.complete.assert_not_called()

    def test_invalid_snapshot_and_repeated_invocations_are_isolated(self):
        self.run_flow()
        self.stories.get_refinement_stories.return_value *= 2
        with self.assertRaises(WorkflowError): self.run_flow()
        self.assertEqual(1, self.model.complete.call_count)
        self.stories.get_refinement_stories.return_value = []
        self.payload.update(meeting_id='f2', proposed_actions=[])
        self.run_flow(meeting_id='f2', access_token='second-token')
        self.assertEqual([], json.loads(self.model.complete.call_args.args[0][1]['content'])['stories'])
        self.stories.get_refinement_stories.assert_called_with(access_token='second-token')

    def test_tool_and_provider_errors_stop_without_retry(self):
        self.stories.get_refinement_stories.side_effect = StoryToolError('permission_denied')
        with self.assertRaises(StoryToolError): self.run_flow()
        self.model.complete.assert_not_called()
        self.stories.get_refinement_stories.side_effect = None
        self.model.complete.side_effect = ModelError('provider_timeout')
        with self.assertRaises(ModelError): self.run_flow()
        self.assertEqual(1, self.model.complete.call_count)

    def test_unsupported_write_fields_forged_evidence_and_types_rejected(self):
        original = copy.deepcopy(self.payload)
        for mutation in ('meeting', 'quote', 'segment', 'id', 'owner', 'status', 'estimate', 'approved', 'action', 'bool', 'range', 'missing', 'blank'):
            self.payload = copy.deepcopy(original)
            proposal = self.payload['proposed_actions'][0]
            changes = proposal['changes']
            if mutation == 'meeting': self.payload['meeting_id'] = 'other'
            if mutation == 'quote': proposal['evidence'][0]['quote'] = '不存在的决定'
            if mutation == 'segment': proposal['evidence'][0]['segment_id'] = 'S2'
            if mutation == 'id': proposal['story_id'] = 'US99'
            if mutation == 'owner': changes['owner_id'] = 1
            if mutation == 'status': changes['status'] = 2
            if mutation == 'estimate': changes['estimated_hours'] = 8
            if mutation == 'approved': proposal['approved'] = True
            if mutation == 'action': proposal['action'] = 'update_story_status'
            if mutation == 'bool': changes['sprint'] = True
            if mutation == 'range': changes['activity'] = 6
            if mutation == 'missing': del changes['priority']
            if mutation == 'blank': changes['acceptance'] = ' '
            with self.subTest(mutation=mutation), self.assertRaises(WorkflowError) as caught: self.run_flow()
            self.assertEqual('invalid_model_proposal', caught.exception.code)

    def test_duplicate_existing_title_candidate_title_and_ids_rejected(self):
        original = copy.deepcopy(self.payload)
        for mode in ('existing', 'title', 'id'):
            self.payload = copy.deepcopy(original)
            if mode == 'existing': self.payload['proposed_actions'][0]['changes']['title'] = ' 登录接口 '
            else:
                second = copy.deepcopy(self.payload['proposed_actions'][0])
                if mode == 'title': second['proposal_id'] = 'p2'
                else: second['changes']['title'] = '另一个需求'
                self.payload['proposed_actions'].append(second)
            with self.subTest(mode=mode), self.assertRaises(WorkflowError): self.run_flow()

    def test_schema_roundtrip_and_explicit_business_fields(self):
        self.payload['proposed_actions'][0]['changes'].update(priority='Should', sprint=3, activity=4)
        result = self.run_flow()
        context = BacklogRefinementInput.model_validate(json.loads(self.model.complete.call_args.args[0][1]['content']))
        self.assertEqual(result, validate_refinement_result(context, json.loads(result.model_dump_json())))
        self.assertEqual(3, result.proposed_actions[0].changes.sprint)
