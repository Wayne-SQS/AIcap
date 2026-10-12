import copy
import json
from pathlib import Path
import unittest
from meeting_agent.retro_evaluation import assess, context_for, validate_cases


class RetroEvaluationTests(unittest.TestCase):
    def setUp(self):
        self.cases = json.loads((Path(__file__).parents[1] / 'evals/retro_quality.json').read_text(encoding='utf-8'))

    def fixture(self, case):
        segments = {s.segment_id: s.text for s in context_for(case).transcript_segments}
        actions = [dict(proposal_id='p1', action='create_action_item', reason='测试',
            changes=dict(title='完善发布检查表', description='', owner_id=e['owner_id'], deadline_text=e['deadline_text']),
            evidence=[dict(segment_id=s, quote=segments[s]) for s in e['supporting_segments']]) for e in case['expected_actions']]
        return dict(meeting_id=case['id'], meeting_type='sprint_retrospective', summary='测试', decisions=[],
            proposed_actions=actions, open_questions=['请确认'] if case['needs_question'] else [])

    def test_oracles_and_all_empty_cannot_pass(self):
        validate_cases(self.cases)
        self.assertEqual(8, len(self.cases))
        self.assertEqual(4, sum(bool(c['expected_actions']) for c in self.cases))
        for case in self.cases:
            payload = self.fixture(case)
            self.assertEqual([], assess(case, payload)[0])
            if case['expected_actions']:
                payload['proposed_actions'] = []
                self.assertIn('unexpected_action_count', assess(case, payload)[0])

    def test_mentioned_name_is_not_assignment_even_when_contract_accepts_quote(self):
        case = self.cases[6]
        payload = self.fixture(case)
        payload['proposed_actions'][0]['changes']['owner_id'] = 1
        self.assertIn('unexpected_owner', assess(case, payload)[0])
        payload = self.fixture(case)
        payload['open_questions'] = []
        self.assertIn('missing_follow_up_question', assess(case, payload)[0])

    def test_invalid_oracles_fail_before_calls(self):
        for field, value in [('owner_id', 99), ('owner_id', True), ('deadline_text', '明年'), ('supporting_segments', ['S9'])]:
            cases = copy.deepcopy(self.cases)
            cases[0]['expected_actions'][0][field] = value
            with self.assertRaises(ValueError):
                validate_cases(cases)
        with self.assertRaises(ValueError):
            validate_cases([self.cases[0], self.cases[0]])

    def test_semantic_pairs_keep_positive_actions_and_require_support(self):
        cases = json.loads((Path(__file__).parents[1] / 'evals/retro_semantics.json').read_text(encoding='utf-8'))
        validate_cases(cases)
        self.assertEqual(4, len(cases))
        self.assertEqual(3, sum(bool(c['expected_actions']) for c in cases))
        for case in cases:
            self.assertEqual([], assess(case, self.fixture(case))[0])
        case = copy.deepcopy(cases[1])
        case['transcript'] = '张敏：之前讨论过检查表。\n' + case['transcript']
        case['expected_actions'][0]['supporting_segments'] = ['S2']
        payload = self.fixture(case)
        payload['proposed_actions'][0]['changes']['deadline_text'] = None
        payload['proposed_actions'][0]['evidence'] = [{'segment_id': 'S1', 'quote': '张敏：之前讨论过检查表。'}]
        failures, _ = assess(case, payload)
        self.assertIn('missing_supporting_evidence', failures)
        self.assertIn('unexpected_deadline', failures)
