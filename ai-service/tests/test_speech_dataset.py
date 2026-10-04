import unittest

from meeting_agent.speech_dataset import parse_rttm, parse_textgrid
from meeting_agent.speech_evaluation import SpeechEvaluationError


TEXTGRID = '''File type = "ooTextFile"
Object class = "TextGrid"
    item [1]:
        class = "IntervalTier"
        name = "PERSON_A"
        intervals: size = 2
        intervals [1]:
            xmin = 0
            xmax = 1.25
            text = "你好<sil>"
        intervals [2]:
            xmin = 1.25
            xmax = 2
            text = ""
    item [2]:
        class = "IntervalTier"
        name = "PERSON_B"
        intervals: size = 1
        intervals [1]:
            xmin = 0.5
            xmax = 2.5
            text = "&开会&<%>"
'''


class SpeechDatasetTest(unittest.TestCase):
    def test_textgrid_orders_speech_and_removes_annotation_tags(self):
        text, rows = parse_textgrid(TEXTGRID, 2000)
        self.assertEqual('你好\n开会', text)
        self.assertEqual([
            {'start_ms': 0, 'end_ms': 1250, 'speaker_id': 'PERSON_A', 'text': '你好'},
            {'start_ms': 500, 'end_ms': 2000, 'speaker_id': 'PERSON_B', 'text': '开会'},
        ], rows)

    def test_rttm_crops_and_sorts_turns(self):
        value = '\n'.join([
            'SPEAKER sample 1 1.500 1.000 <NA> <NA> B <NA> <NA>',
            'SPEAKER sample 1 0.000 1.000 <NA> <NA> A <NA> <NA>',
        ])
        self.assertEqual([
            {'start_ms': 0, 'end_ms': 1000, 'speaker_id': 'A'},
            {'start_ms': 1500, 'end_ms': 2000, 'speaker_id': 'B'},
        ], parse_rttm(value, 2000, 'sample'))

    def test_nonzero_window_is_cropped_and_rebased(self):
        text, rows = parse_textgrid(TEXTGRID, 1000, 1000)
        self.assertEqual('你好\n开会', text)
        self.assertEqual((0,250), (rows[0]['start_ms'],rows[0]['end_ms']))
        self.assertEqual((0,1000), (rows[1]['start_ms'],rows[1]['end_ms']))
        value = 'SPEAKER sample 1 0.500 1.000 <NA> <NA> A <NA> <NA>'
        self.assertEqual([{'start_ms':0,'end_ms':500,'speaker_id':'A'}],
                         parse_rttm(value,1000,'sample',1000))

    def test_invalid_annotation_formats_fail_closed(self):
        with self.assertRaisesRegex(SpeechEvaluationError, 'textgrid_interval_invalid'):
            parse_textgrid(TEXTGRID.replace('            text = "你好<sil>"\n', ''), 2000)
        with self.assertRaisesRegex(SpeechEvaluationError, 'rttm_recording_mismatch'):
            parse_rttm('SPEAKER other 1 0 1 <NA> <NA> A <NA> <NA>', 1000, 'sample')


if __name__ == '__main__':
    unittest.main()
