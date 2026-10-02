import copy
import json
from pathlib import Path
import unittest

from meeting_agent.review_evaluation import assess, context_for, validate_cases


class ReviewEvaluationTests(unittest.TestCase):
    def setUp(self):
        self.cases = json.loads((Path(__file__).parents[1] / 'evals/review_quality.json').read_text(encoding='utf-8-sig'))

    def fixture(self, case):
        context = context_for(case)
        segments = {s.segment_id: s.text for s in context.transcript_segments}
        actions = []
        for sid, before, after in case['expected_actions']:
            segment = case['supporting_segments'][sid][0]
            actions.append(dict(proposal_id='p1', action='update_story_status', story_id=sid,
                expected={'status': before}, changes={'status': after}, reason='离线预期',
                evidence=[dict(segment_id=segment, quote=segments[segment])]))
        return dict(meeting_id=case['id'], meeting_type='sprint_review', summary='离线夹具，不是模型输出',
                    proposed_actions=actions, open_questions=['请确认验收结论'] if case['needs_question'] else [])

    def test_all_oracles_validate_and_positive_case_prevents_all_empty_success(self):
        validate_cases(self.cases)
        self.assertEqual(8, len(self.cases))
        self.assertEqual(1, sum(bool(c['expected_actions']) for c in self.cases))
        for case in self.cases:
            payload = self.fixture(case)
            self.assertEqual([], assess(case, payload)[0])
            if case['expected_actions']:
                payload['proposed_actions'] = []
                self.assertIn('unexpected_status_actions', assess(case, payload)[0])

    def test_questions_and_forged_completion_fail(self):
        case = self.cases[3]
        payload = self.fixture(case)
        payload['open_questions'] = []
        self.assertIn('missing_follow_up_question', assess(case, payload)[0])
        payload['proposed_actions'] = [dict(proposal_id='p1', action='update_story_status', story_id='US09',
            expected={'status': 1}, changes={'status': 2}, reason='错误候选',
            evidence=[dict(segment_id='S1', quote=context_for(case).transcript_segments[0].text)])]
        self.assertIn('unexpected_status_actions', assess(case, payload)[0])

    def test_invalid_oracles_rejected_before_calls(self):
        for action in (['US07', 1, 1], ['US07', 1, True], ['US99', 1, 2], ['US07', 0, 2]):
            cases = copy.deepcopy(self.cases)
            cases[2]['expected_actions'] = [action]
            with self.assertRaises(ValueError):
                validate_cases(cases)

    def test_semantic_counterexamples_include_resolved_positive_action(self):
        cases = json.loads((Path(__file__).parents[1] / 'evals/review_semantics.json').read_text(encoding='utf-8-sig'))
        validate_cases(cases)
        self.assertEqual(4, len(cases))
        self.assertEqual(1, sum(bool(c['expected_actions']) for c in cases))
        for case in cases:
            self.assertEqual([], assess(case, self.fixture(case))[0])
        payload = self.fixture(cases[-1])
        payload['proposed_actions'][0]['evidence'] = [{'segment_id': 'S1', 'quote': 'US13已验收通过。'}]
        self.assertIn('missing_supporting_evidence', assess(cases[-1], payload)[0])
