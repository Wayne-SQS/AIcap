"""Offline speech-quality metrics against an explicitly annotated reference."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import unicodedata


class SpeechEvaluationError(ValueError):
    """A public, content-free validation error for evaluation artifacts."""


def _is_han(character: str) -> bool:
    code = ord(character)
    return (0x3400 <= code <= 0x4DBF or 0x4E00 <= code <= 0x9FFF
            or 0xF900 <= code <= 0xFAFF or 0x20000 <= code <= 0x3134F)


def tokenize_transcript(text: str) -> list[str]:
    """Tokenize zh/en text: one token per Han character, words for other alphanumerics."""
    if not isinstance(text, str):
        raise SpeechEvaluationError('transcript_text_must_be_string')
    tokens: list[str] = []
    word: list[str] = []
    for character in unicodedata.normalize('NFKC', text).lower():
        if _is_han(character):
            if word:
                tokens.append(''.join(word))
                word = []
            tokens.append(character)
        elif character.isalnum():
            word.append(character)
        elif word:
            tokens.append(''.join(word))
            word = []
    if word:
        tokens.append(''.join(word))
    return tokens


def word_error_rate(reference_text: str, hypothesis_text: str) -> dict:
    reference = tokenize_transcript(reference_text)
    hypothesis = tokenize_transcript(hypothesis_text)
    if not reference:
        raise SpeechEvaluationError('reference_transcript_has_no_tokens')

    # Each cell stores (edit distance, substitutions, deletions, insertions).
    previous = [(index, 0, 0, index) for index in range(len(hypothesis) + 1)]
    for row, expected in enumerate(reference, 1):
        current = [(row, 0, row, 0)]
        for column, actual in enumerate(hypothesis, 1):
            if expected == actual:
                diagonal = previous[column - 1]
            else:
                value = previous[column - 1]
                diagonal = (value[0] + 1, value[1] + 1, value[2], value[3])
            value = previous[column]
            deletion = (value[0] + 1, value[1], value[2] + 1, value[3])
            value = current[column - 1]
            insertion = (value[0] + 1, value[1], value[2], value[3] + 1)
            current.append(min(diagonal, deletion, insertion))
        previous = current
    edits, substitutions, deletions, insertions = previous[-1]
    return {
        'error_rate': edits / len(reference),
        'substitutions': substitutions,
        'deletions': deletions,
        'insertions': insertions,
        'reference_token_count': len(reference),
        'hypothesis_token_count': len(hypothesis),
        'tokenization': 'han_char_and_alphanumeric_word_v1',
    }


def _positive_int(value, code: str) -> int:
    if type(value) is not int or value <= 0:
        raise SpeechEvaluationError(code)
    return value


def _turns(value, duration_ms: int, source: str) -> list[dict]:
    if not isinstance(value, list):
        raise SpeechEvaluationError(f'{source}_turns_must_be_array')
    result: list[dict] = []
    by_speaker: dict[str, list[tuple[int, int]]] = {}
    for item in value:
        if not isinstance(item, dict):
            raise SpeechEvaluationError(f'{source}_turn_must_be_object')
        start, end, speaker = item.get('start_ms'), item.get('end_ms'), item.get('speaker_id')
        if (type(start) is not int or type(end) is not int or not 0 <= start < end <= duration_ms
                or not isinstance(speaker, str) or not speaker.strip()):
            raise SpeechEvaluationError(f'{source}_turn_invalid')
        result.append({'start_ms': start, 'end_ms': end, 'speaker_id': speaker})
        by_speaker.setdefault(speaker, []).append((start, end))
    for intervals in by_speaker.values():
        intervals.sort()
        if any(current[0] < previous[1] for previous, current in zip(intervals, intervals[1:])):
            raise SpeechEvaluationError(f'{source}_same_speaker_turns_overlap')
    return result


def _intersection_ms(left: list[dict], right: list[dict], left_id: str, right_id: str) -> int:
    return sum(max(0, min(a['end_ms'], b['end_ms']) - max(a['start_ms'], b['start_ms']))
               for a in left if a['speaker_id'] == left_id
               for b in right if b['speaker_id'] == right_id)


def _best_speaker_mapping(reference: list[dict], hypothesis: list[dict]) -> dict[str, str | None]:
    reference_ids = sorted({turn['speaker_id'] for turn in reference})
    hypothesis_ids = sorted({turn['speaker_id'] for turn in hypothesis})
    overlap = [[_intersection_ms(hypothesis, reference, hyp, ref) for ref in reference_ids]
               for hyp in hypothesis_ids]

    # Hungarian minimum-cost assignment on a padded square matrix. Unlike a bitmask
    # search, this remains bounded for the 32 anonymous speakers allowed by the API.
    size = max(len(reference_ids), len(hypothesis_ids))
    if not size:
        return {}
    maximum = max((value for row in overlap for value in row), default=0)
    costs = [[maximum - (overlap[row][column]
                         if row < len(hypothesis_ids) and column < len(reference_ids) else 0)
              for column in range(size)] for row in range(size)]
    row_potential = [0] * (size + 1)
    column_potential = [0] * (size + 1)
    matched_row = [0] * (size + 1)
    previous_column = [0] * (size + 1)
    for row in range(1, size + 1):
        matched_row[0] = row
        minimum = [float('inf')] * (size + 1)
        used = [False] * (size + 1)
        column = 0
        while True:
            used[column] = True
            current_row = matched_row[column]
            delta, next_column = float('inf'), 0
            for candidate in range(1, size + 1):
                if used[candidate]:
                    continue
                reduced = (costs[current_row - 1][candidate - 1]
                           - row_potential[current_row] - column_potential[candidate])
                if reduced < minimum[candidate]:
                    minimum[candidate] = reduced
                    previous_column[candidate] = column
                if minimum[candidate] < delta:
                    delta, next_column = minimum[candidate], candidate
            for candidate in range(size + 1):
                if used[candidate]:
                    row_potential[matched_row[candidate]] += delta
                    column_potential[candidate] -= delta
                else:
                    minimum[candidate] -= delta
            column = next_column
            if matched_row[column] == 0:
                break
        while True:
            prior = previous_column[column]
            matched_row[column] = matched_row[prior]
            column = prior
            if column == 0:
                break
    assigned_column = [-1] * size
    for column in range(1, size + 1):
        assigned_column[matched_row[column] - 1] = column - 1
    return {
        speaker: (reference_ids[column]
                  if column < len(reference_ids) and overlap[row][column] > 0 else None)
        for row, (speaker, column) in enumerate(zip(hypothesis_ids, assigned_column))
    }


def diarization_error_rate(reference_turns: list[dict], hypothesis_turns: list[dict],
                           duration_ms: int) -> dict:
    reference = _turns(reference_turns, duration_ms, 'reference')
    hypothesis = _turns(hypothesis_turns, duration_ms, 'hypothesis')
    reference_ids = sorted({turn['speaker_id'] for turn in reference})
    if not reference_ids:
        raise SpeechEvaluationError('reference_has_no_speaker_turns')
    mapping = _best_speaker_mapping(reference, hypothesis)
    boundaries = sorted({0, duration_ms, *(turn[key] for turn in reference + hypothesis
                                             for key in ('start_ms', 'end_ms'))})
    miss = false_alarm = confusion = denominator = 0
    for start, end in zip(boundaries, boundaries[1:]):
        if start == end:
            continue
        middle = (start + end) / 2
        expected = {turn['speaker_id'] for turn in reference
                    if turn['start_ms'] <= middle < turn['end_ms']}
        actual_raw = {turn['speaker_id'] for turn in hypothesis
                      if turn['start_ms'] <= middle < turn['end_ms']}
        actual = {mapping[speaker] if mapping[speaker] is not None else f'<unmapped:{speaker}>'
                  for speaker in actual_raw}
        elapsed = end - start
        denominator += len(expected) * elapsed
        miss += max(0, len(expected) - len(actual)) * elapsed
        false_alarm += max(0, len(actual) - len(expected)) * elapsed
        confusion += (min(len(expected), len(actual)) - len(expected & actual)) * elapsed
    if denominator == 0:
        raise SpeechEvaluationError('reference_has_no_scored_speaker_time')
    return {
        'error_rate': (miss + false_alarm + confusion) / denominator,
        'miss_ms': miss,
        'false_alarm_ms': false_alarm,
        'confusion_ms': confusion,
        'scored_reference_speaker_ms': denominator,
        'reference_speaker_count': len(reference_ids),
        'hypothesis_speaker_count': len(mapping),
        'speaker_mapping': mapping,
        'collar_ms': 0,
        'overlap_scored': True,
    }


def _flatten_words(transcription: dict) -> list[dict]:
    if isinstance(transcription.get('words'), list):
        return transcription['words']
    segments = transcription.get('segments')
    if not isinstance(segments, list):
        raise SpeechEvaluationError('transcription_segments_must_be_array')
    words: list[dict] = []
    for segment in segments:
        if not isinstance(segment, dict):
            raise SpeechEvaluationError('transcription_segment_must_be_object')
        value = segment.get('words')
        if value is None:
            continue
        if not isinstance(value, list):
            raise SpeechEvaluationError('transcription_words_must_be_array')
        words.extend(value)
    return words


def word_alignment_coverage(transcription: dict, diarization_turns: list[dict],
                            duration_ms: int) -> dict:
    words = _flatten_words(transcription)
    valid: list[tuple[int, int]] = []
    for word in words:
        if not isinstance(word, dict):
            continue
        start, end = word.get('start_ms'), word.get('end_ms')
        if type(start) is int and type(end) is int and 0 <= start < end <= duration_ms:
            valid.append((start, end))
    merged: list[list[int]] = []
    for start, end in sorted(valid):
        if not merged or start > merged[-1][1]:
            merged.append([start, end])
        else:
            merged[-1][1] = max(merged[-1][1], end)
    covered_ms = sum(end - start for start, end in merged)
    speaker_overlap = sum(any(start < turn['end_ms'] and turn['start_ms'] < end
                              for turn in diarization_turns) for start, end in valid)
    count = len(words)
    return {
        'word_count': count,
        'valid_timestamp_word_count': len(valid),
        'word_timestamp_coverage': len(valid) / count if count else 0.0,
        'audio_time_covered_ms': covered_ms,
        'audio_time_coverage': covered_ms / duration_ms,
        'words_overlapping_speaker_turns': speaker_overlap,
        'speaker_overlap_coverage': speaker_overlap / count if count else 0.0,
    }


def evaluate(reference: dict, transcription: dict, diarization: dict) -> dict:
    if not all(isinstance(item, dict) for item in (reference, transcription, diarization)):
        raise SpeechEvaluationError('artifacts_must_be_objects')
    duration_ms = _positive_int(reference.get('duration_ms'), 'reference_duration_invalid')
    sample_id = reference.get('sample_id')
    if not isinstance(sample_id, str) or not sample_id.strip() or len(sample_id) > 200:
        raise SpeechEvaluationError('sample_id_invalid')
    if transcription.get('duration_ms') != duration_ms or diarization.get('duration_ms') != duration_ms:
        raise SpeechEvaluationError('artifact_duration_mismatch')
    reference_text = reference.get('text')
    hypothesis_text = transcription.get('text')
    if not isinstance(reference_text, str) or not isinstance(hypothesis_text, str):
        raise SpeechEvaluationError('transcript_text_must_be_string')
    reference_turns = _turns(reference.get('turns'), duration_ms, 'reference')
    hypothesis_turns = _turns(diarization.get('turns'), duration_ms, 'hypothesis')
    return {
        'schema_version': 1,
        'data_classification': 'explicit_ground_truth',
        'sample_id': sample_id,
        'duration_ms': duration_ms,
        'wer': word_error_rate(reference_text, hypothesis_text),
        'der': diarization_error_rate(reference_turns, hypothesis_turns, duration_ms),
        'word_alignment': word_alignment_coverage(transcription, hypothesis_turns, duration_ms),
    }


def aggregate_reports(reports: list[dict]) -> dict:
    """Aggregate additive speech metrics without averaging per-sample percentages."""
    if not isinstance(reports, list) or not reports:
        raise SpeechEvaluationError('reports_must_be_nonempty_array')
    sample_ids, samples = set(), []
    provenance = None
    totals = {
        'duration_ms': 0, 'substitutions': 0, 'deletions': 0, 'insertions': 0,
        'reference_token_count': 0, 'hypothesis_token_count': 0,
        'miss_ms': 0, 'false_alarm_ms': 0, 'confusion_ms': 0,
        'scored_reference_speaker_ms': 0, 'word_count': 0,
        'valid_timestamp_word_count': 0, 'audio_time_covered_ms': 0,
        'words_overlapping_speaker_turns': 0,
    }
    tokenization = None
    for report in reports:
        try:
            sample_id = report['sample_id']
            duration_ms = report['duration_ms']
            wer, der, alignment = report['wer'], report['der'], report['word_alignment']
            if (report.get('schema_version') != 1
                    or report.get('data_classification') not in (None, 'explicit_ground_truth')
                    or not isinstance(sample_id, str) or not sample_id.strip()
                    or sample_id in sample_ids or type(duration_ms) is not int or duration_ms <= 0):
                raise ValueError()
            current_tokenization = wer['tokenization']
            if tokenization is None:
                tokenization = current_tokenization
            if (current_tokenization != tokenization or der['collar_ms'] != 0
                    or der['overlap_scored'] is not True):
                raise ValueError()
            fields = {
                'substitutions': wer['substitutions'], 'deletions': wer['deletions'],
                'insertions': wer['insertions'],
                'reference_token_count': wer['reference_token_count'],
                'hypothesis_token_count': wer['hypothesis_token_count'],
                'miss_ms': der['miss_ms'], 'false_alarm_ms': der['false_alarm_ms'],
                'confusion_ms': der['confusion_ms'],
                'scored_reference_speaker_ms': der['scored_reference_speaker_ms'],
                'word_count': alignment['word_count'],
                'valid_timestamp_word_count': alignment['valid_timestamp_word_count'],
                'audio_time_covered_ms': alignment['audio_time_covered_ms'],
                'words_overlapping_speaker_turns': alignment['words_overlapping_speaker_turns'],
            }
            if any(type(value) is not int or value < 0 for value in fields.values()):
                raise ValueError()
            if (fields['reference_token_count'] == 0
                    or fields['scored_reference_speaker_ms'] == 0
                    or fields['valid_timestamp_word_count'] > fields['word_count']
                    or fields['words_overlapping_speaker_turns'] > fields['word_count']
                    or fields['audio_time_covered_ms'] > duration_ms):
                raise ValueError()
            expected_wer = (fields['substitutions'] + fields['deletions']
                            + fields['insertions']) / fields['reference_token_count']
            expected_der = (fields['miss_ms'] + fields['false_alarm_ms']
                            + fields['confusion_ms']) / fields['scored_reference_speaker_ms']
            if (not isinstance(wer['error_rate'], (int, float))
                    or not isinstance(der['error_rate'], (int, float))
                    or not math.isfinite(wer['error_rate']) or not math.isfinite(der['error_rate'])
                    or not math.isclose(wer['error_rate'], expected_wer, rel_tol=1e-12)
                    or not math.isclose(der['error_rate'], expected_der, rel_tol=1e-12)):
                raise ValueError()
            current_provenance = tuple(report.get(name) for name in (
                'dataset', 'dataset_license', 'dataset_source', 'recording_id'))
            if provenance is None:
                provenance = current_provenance
            elif current_provenance != provenance:
                raise ValueError()
        except (KeyError, TypeError, ValueError):
            raise SpeechEvaluationError('speech_report_invalid') from None
        sample_ids.add(sample_id)
        totals['duration_ms'] += duration_ms
        for name, value in fields.items():
            totals[name] += value
        sample = {'sample_id': sample_id, 'duration_ms': duration_ms,
                  'wer': wer['error_rate'], 'der': der['error_rate']}
        if isinstance(report.get('source_clip'), dict):
            sample['source_clip'] = report['source_clip']
        samples.append(sample)
    edits = totals['substitutions'] + totals['deletions'] + totals['insertions']
    diarization_errors = totals['miss_ms'] + totals['false_alarm_ms'] + totals['confusion_ms']
    word_count = totals['word_count']
    result = {
        'schema_version': 1,
        'data_classification': 'explicit_ground_truth_suite',
        'sample_count': len(samples),
        'duration_ms': totals['duration_ms'],
        'samples': samples,
        'wer': {
            'error_rate': edits / totals['reference_token_count'],
            'substitutions': totals['substitutions'], 'deletions': totals['deletions'],
            'insertions': totals['insertions'],
            'reference_token_count': totals['reference_token_count'],
            'hypothesis_token_count': totals['hypothesis_token_count'],
            'tokenization': tokenization,
        },
        'der': {
            'error_rate': diarization_errors / totals['scored_reference_speaker_ms'],
            'miss_ms': totals['miss_ms'], 'false_alarm_ms': totals['false_alarm_ms'],
            'confusion_ms': totals['confusion_ms'],
            'scored_reference_speaker_ms': totals['scored_reference_speaker_ms'],
            'collar_ms': 0, 'overlap_scored': True,
        },
        'word_alignment': {
            'word_count': word_count,
            'valid_timestamp_word_count': totals['valid_timestamp_word_count'],
            'word_timestamp_coverage': (totals['valid_timestamp_word_count'] / word_count
                                        if word_count else 0.0),
            'audio_time_covered_ms': totals['audio_time_covered_ms'],
            'audio_time_coverage': totals['audio_time_covered_ms'] / totals['duration_ms'],
            'words_overlapping_speaker_turns': totals['words_overlapping_speaker_turns'],
            'speaker_overlap_coverage': (totals['words_overlapping_speaker_turns'] / word_count
                                         if word_count else 0.0),
        },
    }
    if provenance and all(value is not None for value in provenance):
        for name, value in zip(
                ('dataset', 'dataset_license', 'dataset_source', 'recording_id'), provenance):
            result[name] = value
    return result


def _read_object(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding='utf-8-sig'))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SpeechEvaluationError('artifact_unreadable') from error
    if not isinstance(value, dict):
        raise SpeechEvaluationError('artifact_must_be_object')
    return value


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--transcription', type=Path, required=True)
    parser.add_argument('--diarization', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        report = evaluate(_read_object(args.reference), _read_object(args.transcription),
                          _read_object(args.diarization))
    except SpeechEvaluationError as error:
        parser.error(str(error))
    report['created_at'] = datetime.now(timezone.utc).isoformat()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"WER={report['wer']['error_rate']:.4f} DER={report['der']['error_rate']:.4f} "
          f"word_timestamps={report['word_alignment']['word_timestamp_coverage']:.4f}")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
