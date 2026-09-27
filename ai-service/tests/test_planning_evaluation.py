import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from meeting_agent.planning_evaluation import assess, context_for, main, validate_cases
from meeting_agent.model_client import ModelError


CASES = Path(__file__).parents[1] / 'evals' / 'planning_quality.json'


def fixture(case):
    context = context_for(case)
    segments = {s.segment_id: s.text for s in context.transcript_segments}
    actions = []
    for i, (sid, before, after) in enumerate(case['expected_actions']):
        segment = case['supporting_segments'][sid][0]
        actions.append(dict(proposal_id=f'P{i}', action='update_story_sprint', story_id=sid,
            expected={'sprint': before}, changes={'sprint': after}, reason='离线预期样例，非模型输出。',
            evidence=[{'segment_id': segment, 'quote': segments[segment]}]))
    return dict(meeting_id=case['id'], summary='离线样例。', proposed_actions=actions,
                open_questions=['请确认计划。'] if case['needs_question'] else [])


class PlanningEvaluationTests(unittest.TestCase):
    def setUp(self):
        self.cases = json.loads(CASES.read_text(encoding='utf-8-sig'))

    def test_dataset_and_oracle_fixtures(self):
        validate_cases(self.cases)
        self.assertEqual(8, len(self.cases))
        for case in self.cases:
            self.assertEqual([], assess(case, fixture(case))[0])
        self.assertIsNone(context_for(self.cases[0]).current_sprint)

    def test_abstention_and_missing_questions_cannot_pass(self):
        for case in self.cases:
            data = fixture(case)
            if case['expected_actions']:
                data['proposed_actions'] = []
                self.assertIn('unexpected_sprint_actions', assess(case, data)[0])
            if case['needs_question']:
                data['open_questions'] = []
                self.assertIn('missing_follow_up_question', assess(case, data)[0])

    def test_dependency_cases_preserve_positive_and_negative_oracles(self):
        cases = json.loads(CASES.with_name('planning_dependency.json').read_text(encoding='utf-8-sig'))
        validate_cases(cases)
        self.assertEqual(4, len(cases))
        self.assertEqual(2, sum(bool(c['expected_actions']) for c in cases))
        for case in cases:
            self.assertEqual([], assess(case, fixture(case))[0])
            if case['expected_actions']:
                data = fixture(case)
                data['proposed_actions'] = []
                self.assertIn('unexpected_sprint_actions', assess(case, data)[0])

    def test_preflight_rejects_bad_oracles(self):
        for actions in ([['US99', 2, 3]], [['US61', 1, 3]], [['US61', 2, True]], [['US61', 2, 2]]):
            cases = copy.deepcopy(self.cases)
            cases[0]['expected_actions'] = actions
            with self.assertRaises(ValueError):
                validate_cases(cases)
        with self.assertRaises(ValueError):
            validate_cases([])
        with self.assertRaises(ValueError):
            validate_cases([self.cases[0], self.cases[0]])
        self.cases[0]['supporting_segments']['US61'] = ['S99']
        with self.assertRaises(ValueError):
            validate_cases(self.cases)

    def test_cli_report_with_fake_model_and_provider_stop(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'report.json'
            model = Mock()
            model.complete.side_effect = [fixture(c) for c in self.cases]
            args = ['eval', '--cases', str(CASES), '--output', str(output)]
            with patch('sys.argv', args), patch('meeting_agent.planning_evaluation.ChatModelClient', return_value=model):
                self.assertEqual(0, main())
            report = json.loads(output.read_text(encoding='utf-8'))
            self.assertEqual(8, report['passed'])
            self.assertTrue(report['manual_review_required'])
            model.complete.side_effect = ModelError('invalid_model_response', diagnostic='content_duplicate_key')
            with patch('sys.argv', args), patch('meeting_agent.planning_evaluation.ChatModelClient', return_value=model):
                self.assertEqual(1, main())
            report = json.loads(output.read_text(encoding='utf-8'))
            self.assertTrue(all(c['model_diagnostic'] == 'content_duplicate_key' for c in report['cases']))
            self.assertTrue(all('result' not in c for c in report['cases']))
            model.complete.side_effect = ModelError('provider_timeout')
            with patch('sys.argv', args), patch('meeting_agent.planning_evaluation.ChatModelClient', return_value=model):
                self.assertEqual(2, main())
            report = json.loads(output.read_text(encoding='utf-8'))
            self.assertFalse(report['automated_acceptance_passed'])
            self.assertEqual([], report['cases'])

    def test_bad_dataset_stops_before_client_construction(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'bad.json'
            path.write_text('[]', encoding='utf-8')
            with patch('sys.argv', ['eval', '--cases', str(path), '--output', str(Path(folder) / 'out.json')]), \
                    patch('meeting_agent.planning_evaluation.ChatModelClient') as client:
                with self.assertRaises(ValueError):
                    main()
                client.assert_not_called()
