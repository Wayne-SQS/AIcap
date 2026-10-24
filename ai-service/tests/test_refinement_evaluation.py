import copy
import json
from pathlib import Path
import unittest
from meeting_agent.refinement_evaluation import assess, context_for, validate_cases


class RefinementEvaluationTests(unittest.TestCase):
    def setUp(self):
        self.cases = json.loads((Path(__file__).parents[1] / 'evals/refinement_quality.json').read_text(encoding='utf-8'))
        self.prd_cases = json.loads((Path(__file__).parents[1] / 'evals/refinement_prd.json').read_text(encoding='utf-8'))

    def fixture(self, case):
        segments = {s.segment_id: s.text for s in context_for(case).transcript_segments}
        return dict(meeting_id=case['id'], summary='测试', open_questions=['请确认'] if case['needs_question'] else [],
            proposed_actions=[dict(proposal_id=f'p{index}', action='create_story', reason='测试', changes=copy.deepcopy(e['changes']),
                evidence=[dict(segment_id=s, quote=segments[s]) for s in e['supporting_segments']],
                effort_estimate=dict(min_hours=8, max_hours=16, basis='按明确范围粗估，待拆任务确认', uncertainty='接口和数据结构尚未确认')
                    if e.get('estimate') == 'required' else None)
                for index, e in enumerate(case['expected_actions'], 1)])

    def test_prd_multi_story_and_estimate_oracles(self):
        validate_cases(self.prd_cases)
        for case in self.prd_cases:
            self.assertEqual([], assess(case, self.fixture(case))[0])
        payload = self.fixture(self.prd_cases[0])
        payload['proposed_actions'][0]['effort_estimate'] = None
        self.assertIn('missing_effort_estimate', assess(self.prd_cases[0], payload)[0])
        payload = self.fixture(self.prd_cases[2])
        payload['proposed_actions'][0]['effort_estimate'] = dict(min_hours=8, max_hours=16,
            basis='编造依据', uncertainty='不明确')
        self.assertIn('unexpected_effort_estimate', assess(self.prd_cases[2], payload)[0])
        payload = self.fixture(self.prd_cases[0])
        payload['proposed_actions'][0]['effort_estimate']['min_hours'] = 20
        with self.assertRaises(ValueError):
            assess(self.prd_cases[0], payload)

    def test_oracles_and_all_empty_cannot_pass(self):
        validate_cases(self.cases)
        self.assertEqual(12, len(self.cases))
        self.assertEqual(4, sum(bool(c['expected_actions']) for c in self.cases))
        for case in self.cases:
            payload = self.fixture(case)
            self.assertEqual([], assess(case, payload)[0])
            if case['expected_actions']:
                payload['proposed_actions'] = []
                self.assertIn('unexpected_action_count', assess(case, payload)[0])

    def test_invented_fields_and_missing_questions_fail(self):
        case = self.cases[1]
        for field, value in [('description', '编造描述'), ('acceptance', '编造指标'), ('priority', 'Must'), ('sprint', 2), ('activity', 2)]:
            payload = self.fixture(case)
            payload['proposed_actions'][0]['changes'][field] = value
            self.assertIn('unexpected_' + field, assess(case, payload)[0])
        payload = self.fixture(case)
        payload['open_questions'] = []
        self.assertIn('missing_follow_up_question', assess(case, payload)[0])

    def test_old_statement_is_not_corrected_decision_evidence(self):
        case = self.cases[9]
        payload = self.fixture(case)
        payload['proposed_actions'][0]['evidence'] = [dict(segment_id='S1', quote=context_for(case).transcript_segments[0].text)]
        self.assertIn('missing_supporting_evidence', assess(case, payload)[0])

    def test_bad_oracles_fail_before_network(self):
        for key, value in [('priority', 'High'), ('sprint', True), ('activity', 6)]:
            cases = copy.deepcopy(self.cases)
            cases[0]['expected_actions'][0]['changes'][key] = value
            with self.assertRaises(ValueError):
                validate_cases(cases)
        cases = copy.deepcopy(self.cases)
        cases[0]['expected_actions'][0]['supporting_segments'] = ['S99']
        with self.assertRaises(ValueError):
            validate_cases(cases)
        with self.assertRaises(ValueError):
            validate_cases([self.cases[0], self.cases[0]])

    def test_explicit_fields_cannot_disappear(self):
        case = self.cases[0]
        for field in ('description', 'acceptance', 'priority', 'sprint', 'activity'):
            payload = self.fixture(case)
            payload['proposed_actions'][0]['changes'][field] = None
            self.assertTrue(assess(case, payload)[0])
