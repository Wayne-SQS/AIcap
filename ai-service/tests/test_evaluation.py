import copy
import json
from pathlib import Path
import unittest
import tempfile
import io
from contextlib import redirect_stdout
from unittest.mock import patch
from meeting_agent.evaluation import main
from meeting_agent.model_client import ModelError

from meeting_agent.evaluation import assess, context_for, contract_diagnostics, action_difference


class EvaluationTests(unittest.TestCase):
    def setUp(self):
        self.cases = json.loads((Path(__file__).parents[1] / 'evals/daily_quality.json').read_text(encoding='utf-8'))

    def output(self, case):
        return {'meeting_id': case['id'], 'summary': '合成测试摘要', 'proposed_actions': [], 'open_questions': []}

    def test_cases_are_valid_and_include_positive_negative_and_mixed_outcomes(self):
        for case in self.cases:
            context_for(case)
        self.assertEqual(10, len({case['id'] for case in self.cases}))
        self.assertEqual(3, sum(bool(case['expected_actions']) for case in self.cases))

    def test_always_abstaining_cannot_pass_positive_case(self):
        failures, _ = assess(self.cases[0], self.output(self.cases[0]))
        self.assertIn('unexpected_status_actions', failures)

    def test_extended_cases_have_consistent_targets_and_supporting_segments(self):
        cases = json.loads((Path(__file__).parents[1] / 'evals/daily_extended.json').read_text(encoding='utf-8'))
        self.assertEqual(6, len({case['id'] for case in cases}))
        self.assertFalse({case['id'] for case in cases} & {case['id'] for case in self.cases})
        self.assertGreaterEqual(max(len(case['transcript']) for case in cases), 1800)
        for case in cases:
            with self.subTest(case=case['id']):
                context = context_for(case)
                segments = {s.segment_id: s.text for s in context.transcript_segments}
                payload = self.output(case)
                if case.get('needs_question'):
                    payload['open_questions'] = ['请人工核对。']
                for index, (story_id, before, after) in enumerate(case['expected_actions']):
                    support = case['supporting_segments'][story_id]
                    self.assertTrue(support)
                    for segment in support:
                        self.assertIn(story_id, segments[segment])
                    payload['proposed_actions'].append({
                        'proposal_id': f'p{index}', 'action': 'update_story_status', 'story_id': story_id,
                        'expected': {'status': before}, 'changes': {'status': after}, 'reason': '验收集一致性检查',
                        'evidence': [{'segment_id': support[0], 'quote': segments[support[0]]}],
                    })
                self.assertEqual([], assess(case, payload)[0])
                if case['expected_actions']:
                    payload['proposed_actions'] = []
                    self.assertIn('unexpected_status_actions', assess(case, payload)[0])

    def test_extended_noise_preserves_blank_line_segment_ids_and_rejects_guessed_story(self):
        cases = json.loads((Path(__file__).parents[1] / 'evals/daily_extended.json').read_text(encoding='utf-8'))
        noisy = next(case for case in cases if case['id'] == 'noisy-correction')
        segments = {s.segment_id: s.text for s in context_for(noisy).transcript_segments}
        self.assertNotIn('S4', segments)
        self.assertIn('今、今天', segments['S5'])
        ambiguous = next(case for case in cases if case['id'] == 'noisy-unknown-id')
        payload = self.output(ambiguous)
        payload['open_questions'] = ['核对编号？']
        payload['proposed_actions'] = [{
            'proposal_id': 'p1', 'action': 'update_story_status', 'story_id': 'US13',
            'expected': {'status': 1}, 'changes': {'status': 2}, 'reason': '错误猜测',
            'evidence': [{'segment_id': 'S1', 'quote': ambiguous['transcript'].splitlines()[0]}],
        }]
        self.assertIn('unexpected_status_actions', assess(ambiguous, payload)[0])

    def test_false_completion_with_valid_quote_fails_semantic_expectation(self):
        case = self.cases[2]
        payload = self.output(case)
        payload['proposed_actions'] = [{'proposal_id': 'p1', 'action': 'update_story_status',
            'story_id': 'US13', 'expected': {'status': 1}, 'changes': {'status': 2}, 'reason': '错误推断',
            'evidence': [{'segment_id': 'S1', 'quote': case['transcript']}]}]
        failures, _ = assess(case, payload)
        self.assertIn('unexpected_status_actions', failures)
        payload['proposed_actions'][0]['evidence'][0]['quote'] = '伪造原文'
        with self.assertRaises(ValueError):
            assess(case, payload)

    def test_ambiguity_requires_follow_up_but_not_exact_wording(self):
        case = self.cases[6]
        payload = self.output(case)
        self.assertIn('missing_follow_up_question', assess(case, payload)[0])
        payload['open_questions'] = ['请核对已完成故事的编号。']
        self.assertEqual([], assess(case, payload)[0])

    def test_format_failure_is_counted_and_does_not_skip_remaining_cases(self):
        cases = self.cases[2:4]
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / 'cases.json', Path(directory) / 'report.json'
            source.write_text(json.dumps(cases), encoding='utf-8')
            with patch('sys.argv', ['evaluation', '--cases', str(source), '--output', str(output)]), \
                 patch('meeting_agent.evaluation.ChatModelClient') as client, redirect_stdout(io.StringIO()):
                client.return_value.complete.side_effect = [ModelError('invalid_model_response'), self.output(cases[1])]
                self.assertEqual(1, main())
            report = json.loads(output.read_text(encoding='utf-8'))
            self.assertEqual(2, len(report['cases']))
            self.assertEqual(1, report['passed'])
            self.assertEqual(['invalid_model_response'], report['cases'][0]['failures'])
            self.assertFalse(report['automated_acceptance_passed'])

    def test_supporting_segment_is_required_for_positive_action(self):
        case = copy.deepcopy(self.cases[0])
        case['transcript'] += '\nUS13 待确认。'
        payload = self.output(case)
        payload['proposed_actions'] = [{'proposal_id': 'p1', 'action': 'update_story_status',
            'story_id': 'US13', 'expected': {'status': 1}, 'changes': {'status': 2}, 'reason': '完成',
            'evidence': [{'segment_id': 'S2', 'quote': 'US13 待确认。'}]}]
        self.assertIn('missing_supporting_evidence', assess(case, payload)[0])

    def test_reason_rejecting_completion_does_not_hide_wrong_numeric_action(self):
        cases = json.loads((Path(__file__).parents[1] / 'evals/daily_extended.json').read_text(encoding='utf-8'))
        case = next(case for case in cases if case['id'] == 'noisy-correction')
        payload = self.output(case)
        payload['proposed_actions'] = [
            {'proposal_id': 'p-us13-in-progress', 'action': 'update_story_status', 'story_id': 'US13',
             'expected': {'status': 1}, 'changes': {'status': 2},
             'reason': '完整澄清明确否定了完成状态，不能据此提出已完成。',
             'evidence': [{'segment_id': 'S3', 'quote': case['transcript'].splitlines()[2]}]},
            {'proposal_id': 'p-us14-in-progress', 'action': 'update_story_status', 'story_id': 'US14',
             'expected': {'status': 0}, 'changes': {'status': 1}, 'reason': '已经开始开发。',
             'evidence': [{'segment_id': 'S5', 'quote': case['transcript'].splitlines()[4]}]},
        ]
        failures, result = assess(case, payload)
        self.assertIn('unexpected_status_actions', failures)
        self.assertEqual({'missing': [], 'unexpected': [['US13', 1, 2]]}, action_difference(case, result))
        payload['proposed_actions'] = []
        self.assertEqual({'missing': [['US14', 0, 1]], 'unexpected': []}, action_difference(case, payload))

    def test_action_difference_is_saved_in_failed_cli_report(self):
        case = self.cases[0]
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / 'cases.json', Path(directory) / 'report.json'
            source.write_text(json.dumps([case]), encoding='utf-8')
            with patch('sys.argv', ['evaluation', '--cases', str(source), '--output', str(output)]), \
                 patch('meeting_agent.evaluation.ChatModelClient') as client, redirect_stdout(io.StringIO()):
                client.return_value.complete.return_value = self.output(case)
                self.assertEqual(1, main())
            report = json.loads(output.read_text(encoding='utf-8'))
            self.assertEqual({'missing': [['US13', 1, 2]], 'unexpected': []},
                             report['cases'][0]['action_difference'])
            self.assertEqual(0, report['passed'])
            self.assertFalse(report['automated_acceptance_passed'])

    def test_consistency_cases_have_valid_oracles_and_evidence(self):
        cases = json.loads((Path(__file__).parents[1] / 'evals/daily_consistency.json').read_text(encoding='utf-8'))
        self.assertEqual(4, len({case['id'] for case in cases}))
        for case in cases:
            with self.subTest(case=case['id']):
                context = context_for(case)
                segments = {s.segment_id: s.text for s in context.transcript_segments}
                payload = self.output(case)
                for index, (story, before, after) in enumerate(case['expected_actions']):
                    segment = case['supporting_segments'][story][0]
                    payload['proposed_actions'].append({
                        'proposal_id': f'p{index}', 'action': 'update_story_status', 'story_id': story,
                        'expected': {'status': before}, 'changes': {'status': after}, 'reason': '场景一致性验证',
                        'evidence': [{'segment_id': segment, 'quote': segments[segment]}]})
                self.assertEqual([], assess(case, payload)[0])

    def test_schema_diagnostics_redact_values_and_unknown_keys(self):
        case = self.cases[2]
        payload = self.output(case)
        payload['summary'] = {'private-value': 'secret-value'}
        payload['secret-field-name'] = 'secret-value'
        try:
            assess(case, payload)
        except ValueError as error:
            diagnostics = contract_diagnostics(error)
        self.assertEqual([
            {'type': 'string_type', 'path': ['summary']},
            {'type': 'extra_forbidden', 'path': ['<unknown_field>']},
        ], diagnostics)
        self.assertNotIn('secret', json.dumps(diagnostics))
        self.assertEqual([{'type': 'unclassified_contract_error', 'path': []}],
                         contract_diagnostics(ValueError('secret-value')))

    def test_proposal_diagnostics_identify_invariant_and_evidence_paths(self):
        case = self.cases[0]
        payload = self.output(case)
        proposal = {'proposal_id': 'p1', 'action': 'update_story_status', 'story_id': 'US13',
                    'expected': {'status': 1}, 'changes': {'status': 2}, 'reason': '完成',
                    'evidence': [{'segment_id': 'S1', 'quote': case['transcript']}]}
        variants = [
            ({'changes': {'status': 1}}, 'unchanged_status', []),
            ({'expected': {'status': 0}}, 'snapshot_mismatch', ['expected', 'status']),
            ({'story_id': 'US999'}, 'unknown_story', ['story_id']),
            ({'evidence': [{'segment_id': 'S99', 'quote': 'private'}]},
             'unknown_segment', ['evidence', 0, 'segment_id']),
            ({'evidence': [{'segment_id': 'S1', 'quote': 'private'}]},
             'quote_mismatch', ['evidence', 0, 'quote']),
        ]
        for change, code, suffix in variants:
            with self.subTest(code=code):
                payload['proposed_actions'] = [dict(proposal, **change)]
                with self.assertRaises(ValueError) as caught:
                    assess(case, payload)
                self.assertEqual([{'type': code, 'path': ['proposed_actions', 0] + suffix}],
                                 contract_diagnostics(caught.exception))

    def test_contract_failure_report_retains_diagnostics_and_continues(self):
        cases = self.cases[2:4]
        invalid = self.output(cases[0])
        invalid['meeting_id'] = 'private-wrong-meeting'
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / 'cases.json', Path(directory) / 'report.json'
            source.write_text(json.dumps(cases), encoding='utf-8')
            with patch('sys.argv', ['evaluation', '--cases', str(source), '--output', str(output)]), \
                 patch('meeting_agent.evaluation.ChatModelClient') as client, redirect_stdout(io.StringIO()):
                client.return_value.complete.side_effect = [invalid, self.output(cases[1])]
                self.assertEqual(1, main())
            report = json.loads(output.read_text(encoding='utf-8'))
            self.assertEqual(2, len(report['cases']))
            self.assertEqual(1, report['passed'])
            self.assertEqual(['invalid_model_proposal'], report['cases'][0]['failures'])
            self.assertEqual([{'type': 'meeting_mismatch', 'path': ['meeting_id']}],
                             report['cases'][0]['contract_errors'])
            self.assertNotIn('result', report['cases'][0])
            self.assertNotIn('private-wrong-meeting', output.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
