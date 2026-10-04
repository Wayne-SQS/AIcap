"""Strict adapters for meeting speech ground-truth annotation formats."""
from __future__ import annotations

import re

from .speech_evaluation import SpeechEvaluationError


_TIER = re.compile(r'^    item \[\d+\]:\s*$')
_INTERVAL = re.compile(r'^        intervals \[\d+\]:\s*$')
_FIELD = re.compile(r'^            (xmin|xmax|text) = (.*)\s*$')
_NAME = re.compile(r'^        name = "(.*)"\s*$')
_ANNOTATION = re.compile(r'<[^>]*>')


def _praat_string(value: str) -> str:
    value = value.strip()
    if len(value) < 2 or value[0] != '"' or value[-1] != '"':
        raise SpeechEvaluationError('textgrid_string_invalid')
    return value[1:-1].replace('""', '"')


def _spoken_text(value: str) -> str:
    return _ANNOTATION.sub('', value).replace('&', '').strip()


def parse_textgrid(text: str, duration_ms: int, window_start_ms: int = 0) -> tuple[str, list[dict]]:
    """Return chronological transcript intervals from a long-form Praat TextGrid."""
    if (not isinstance(text, str) or type(duration_ms) is not int or duration_ms <= 0
            or type(window_start_ms) is not int or window_start_ms < 0):
        raise SpeechEvaluationError('textgrid_input_invalid')
    source_end_ms = window_start_ms + duration_ms
    tier = None
    interval = None
    rows: list[dict] = []

    def finish_interval():
        nonlocal interval
        if interval is None:
            return
        if set(interval) != {'xmin', 'xmax', 'text'} or tier is None:
            raise SpeechEvaluationError('textgrid_interval_invalid')
        try:
            raw_start_ms = round(float(interval['xmin']) * 1000)
            raw_end_ms = round(float(interval['xmax']) * 1000)
        except (TypeError, ValueError, OverflowError):
            raise SpeechEvaluationError('textgrid_interval_invalid') from None
        spoken = _spoken_text(interval['text'])
        interval_start = max(window_start_ms, raw_start_ms)
        interval_end = min(source_end_ms, raw_end_ms)
        if spoken and interval_start < interval_end:
            rows.append({'start_ms': interval_start - window_start_ms,
                         'end_ms': interval_end - window_start_ms,
                         'speaker_id': tier, 'text': spoken})
        interval = None

    for line in text.splitlines():
        if _TIER.match(line):
            finish_interval()
            tier = None
            continue
        name = _NAME.match(line)
        if name:
            if tier is not None:
                raise SpeechEvaluationError('textgrid_tier_invalid')
            tier = name.group(1).replace('""', '"').strip()
            if not tier:
                raise SpeechEvaluationError('textgrid_tier_invalid')
            continue
        if _INTERVAL.match(line):
            finish_interval()
            if tier is None:
                raise SpeechEvaluationError('textgrid_tier_invalid')
            interval = {}
            continue
        field = _FIELD.match(line)
        if field and interval is not None:
            key, value = field.groups()
            if key in interval:
                raise SpeechEvaluationError('textgrid_interval_invalid')
            interval[key] = _praat_string(value) if key == 'text' else value.strip()
    finish_interval()
    if not rows:
        raise SpeechEvaluationError('textgrid_has_no_speech')
    rows.sort(key=lambda row: (row['start_ms'], row['end_ms'], row['speaker_id']))
    return '\n'.join(row['text'] for row in rows), rows


def parse_rttm(text: str, duration_ms: int, recording_id: str | None = None,
               window_start_ms: int = 0) -> list[dict]:
    """Parse and crop NIST RTTM SPEAKER rows to the evaluation duration."""
    if (not isinstance(text, str) or type(duration_ms) is not int or duration_ms <= 0
            or type(window_start_ms) is not int or window_start_ms < 0):
        raise SpeechEvaluationError('rttm_input_invalid')
    source_end_ms = window_start_ms + duration_ms
    turns: list[dict] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        fields = line.split()
        if len(fields) != 10 or fields[0] != 'SPEAKER':
            raise SpeechEvaluationError('rttm_row_invalid')
        if recording_id is not None and fields[1] != recording_id:
            raise SpeechEvaluationError('rttm_recording_mismatch')
        try:
            raw_start_ms = round(float(fields[3]) * 1000)
            raw_end_ms = round((float(fields[3]) + float(fields[4])) * 1000)
        except (ValueError, OverflowError):
            raise SpeechEvaluationError('rttm_row_invalid') from None
        speaker = fields[7]
        turn_start = max(window_start_ms, raw_start_ms)
        turn_end = min(source_end_ms, raw_end_ms)
        if turn_start < turn_end:
            if not speaker or speaker == '<NA>':
                raise SpeechEvaluationError('rttm_speaker_invalid')
            turns.append({'start_ms': turn_start - window_start_ms,
                          'end_ms': turn_end - window_start_ms,
                          'speaker_id': speaker})
    if not turns:
        raise SpeechEvaluationError('rttm_has_no_speaker_turns')
    turns.sort(key=lambda turn: (turn['start_ms'], turn['end_ms'], turn['speaker_id']))
    return turns
