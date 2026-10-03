import json
from pathlib import Path
import tempfile
import unittest

from meeting_agent.speech_evaluation import (
    SpeechEvaluationError,
    diarization_error_rate,
    evaluate,
    main,
    tokenize_transcript,
    word_error_rate,
)


class SpeechEvaluationTest(unittest.TestCase):
    def artifacts(self):
        reference = {
            'sample_id': 'fixture-two-speakers', 'duration_ms': 4000,
            'text': '今天 review API',
            'turns': [
                {'start_ms': 0, 'end_ms': 2000, 'speaker_id': 'ALICE'},
                {'start_ms': 2000, 'end_ms': 4000, 'speaker_id': 'BOB'},
            ],
        }
        transcription = {
            'duration_ms': 4000, 'text': '今天 review API',
            'segments': [{'words': [
                {'word_id': 'w1', 'start_ms': 100, 'end_ms': 900, 'text': '今天'},
                {'word_id': 'w2', 'start_ms': 2100, 'end_ms': 3000, 'text': 'review API'},
            ]}],
        }
        diarization = {
            'duration_ms': 4000,
            'turns': [
                {'start_ms': 0, 'end_ms': 2000, 'speaker_id': 'SPK2'},
                {'start_ms': 2000, 'end_ms': 4000, 'speaker_id': 'SPK1'},
            ],
        }
        return reference, transcription, diarization

    def test_mixed_tokenization_normalizes_width_case_and_punctuation(self):
        self.assertEqual(['今', '天', 'review', 'api', '2'],
                         tokenize_transcript('今天，ＲＥＶＩＥＷ API-2！'))

    def test_wer_reports_known_chinese_deletion(self):
        result = word_error_rate('今天开会', '今天会')
        self.assertEqual((0, 1, 0),
                         (result['substitutions'], result['deletions'], result['insertions']))
        self.assertEqual(0.25, result['error_rate'])

    def test_empty_reference_tokens_are_rejected(self):
        with self.assertRaisesRegex(SpeechEvaluationError, 'reference_transcript_has_no_tokens'):
            word_error_rate('……', 'anything')

    def test_der_maps_anonymous_speakers_before_scoring(self):
        reference, _, diarization = self.artifacts()
        result = diarization_error_rate(reference['turns'], diarization['turns'], 4000)
        self.assertEqual(0, result['error_rate'])
        self.assertEqual({'SPK1': 'BOB', 'SPK2': 'ALICE'}, result['speaker_mapping'])

    def test_der_counts_miss_and_false_alarm(self):
        reference = [
            {'start_ms': 0, 'end_ms': 1000, 'speaker_id': 'A'},
            {'start_ms': 1000, 'end_ms': 2000, 'speaker_id': 'B'},
        ]
        hypothesis = [
            {'start_ms': 0, 'end_ms': 500, 'speaker_id': 'X'},
            {'start_ms': 1000, 'end_ms': 2500, 'speaker_id': 'Y'},
        ]
        result = diarization_error_rate(reference, hypothesis, 3000)
        self.assertEqual(500, result['miss_ms'])
        self.assertEqual(500, result['false_alarm_ms'])
        self.assertEqual(0, result['confusion_ms'])
        self.assertEqual(0.5, result['error_rate'])

    def test_der_counts_speaker_confusion_after_optimal_mapping(self):
        reference = [
            {'start_ms': 0, 'end_ms': 1000, 'speaker_id': 'A'},
            {'start_ms': 1000, 'end_ms': 2000, 'speaker_id': 'B'},
        ]
        hypothesis = [
            {'start_ms': 0, 'end_ms': 1500, 'speaker_id': 'X'},
            {'start_ms': 1500, 'end_ms': 2000, 'speaker_id': 'Y'},
        ]
        result = diarization_error_rate(reference, hypothesis, 2000)
        self.assertEqual(500, result['confusion_ms'])
        self.assertEqual(0.25, result['error_rate'])

    def test_der_mapping_remains_bounded_at_api_speaker_limit(self):
        reference = [{'start_ms': index * 10, 'end_ms': (index + 1) * 10,
                      'speaker_id': f'R{index}'} for index in range(32)]
        hypothesis = [{'start_ms': index * 10, 'end_ms': (index + 1) * 10,
                       'speaker_id': f'H{31 - index}'} for index in range(32)]
        result = diarization_error_rate(reference, hypothesis, 320)
        self.assertEqual(0, result['error_rate'])
        self.assertEqual(32, len(result['speaker_mapping']))

    def test_evaluate_reports_timestamp_and_speaker_overlap_coverage(self):
        reference, transcription, diarization = self.artifacts()
        transcription['segments'][0]['words'].append(
            {'word_id': 'bad', 'start_ms': 4100, 'end_ms': 4200, 'text': 'bad'})
        result = evaluate(reference, transcription, diarization)
        self.assertEqual(0, result['wer']['error_rate'])
        self.assertEqual(2 / 3, result['word_alignment']['word_timestamp_coverage'])
        self.assertEqual(2 / 3, result['word_alignment']['speaker_overlap_coverage'])
        self.assertEqual(1700, result['word_alignment']['audio_time_covered_ms'])

    def test_duration_mismatch_is_rejected(self):
        reference, transcription, diarization = self.artifacts()
        diarization['duration_ms'] = 3999
        with self.assertRaisesRegex(SpeechEvaluationError, 'artifact_duration_mismatch'):
            evaluate(reference, transcription, diarization)

    def test_cli_writes_machine_readable_report(self):
        reference, transcription, diarization = self.artifacts()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = [root / name for name in ('reference.json', 'transcription.json', 'diarization.json')]
            for path, value in zip(paths, (reference, transcription, diarization)):
                path.write_text(json.dumps(value), encoding='utf-8')
            output = root / 'report.json'
            self.assertEqual(0, main([
                '--reference', str(paths[0]), '--transcription', str(paths[1]),
                '--diarization', str(paths[2]), '--output', str(output),
            ]))
            report = json.loads(output.read_text(encoding='utf-8'))
            self.assertEqual('explicit_ground_truth', report['data_classification'])
            self.assertEqual('fixture-two-speakers', report['sample_id'])
            self.assertIn('created_at', report)


if __name__ == '__main__':
    unittest.main()
