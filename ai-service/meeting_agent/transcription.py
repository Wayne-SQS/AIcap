"""Bounded local CPU transcription in a disposable worker; never changes meeting text."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from typing import Annotated, Literal
from pydantic import Field, model_validator
from .contracts import Contract
from .audio_tool import AudioInputRequest, PreparedAudio

ROOT = Path(__file__).resolve().parents[1]
MAX_DURATION_MS = 600000
_SLOT = threading.BoundedSemaphore(1)


class TranscriptionRequest(AudioInputRequest):
    language: Literal['zh', 'en'] | None = None


class TranscriptWord(Contract):
    word_id: str
    start_ms: Annotated[int, Field(ge=0)]
    end_ms: Annotated[int, Field(gt=0)]
    text: Annotated[str, Field(min_length=1, max_length=200)]


class TranscriptSegment(Contract):
    segment_id: str
    start_ms: Annotated[int, Field(ge=0)]
    end_ms: Annotated[int, Field(gt=0)]
    text: Annotated[str, Field(min_length=1, max_length=4000)]
    speaker_id: None = None
    words: Annotated[list[TranscriptWord], Field(max_length=1000)] | None = None


class TranscriptionDraft(Contract):
    audio: PreparedAudio
    engine: Literal['faster-whisper-cpu-int8'] = 'faster-whisper-cpu-int8'
    language: str
    duration_ms: Annotated[int, Field(gt=0, le=MAX_DURATION_MS)]
    segments: Annotated[list[TranscriptSegment], Field(max_length=2000)]
    text: Annotated[str, Field(max_length=16000)]
    status: Literal['draft', 'no_speech']
    diarization_status: Literal['not_available'] = 'not_available'
    requires_human_review: Literal[True] = True
    storage_status: Literal['not_saved'] = 'not_saved'
    @model_validator(mode='after')
    def consistent(self):
        previous = 0
        for i, segment in enumerate(self.segments):
            if (segment.segment_id != f'S{i+1}' or not segment.text.strip()
                    or segment.start_ms < previous or not segment.start_ms < segment.end_ms <= self.duration_ms):
                raise ValueError('invalid transcript timeline')
            if segment.words is not None:
                if not segment.words: raise ValueError('empty word timestamps')
                word_previous=segment.start_ms
                for j,word in enumerate(segment.words):
                    if (word.word_id != f'{segment.segment_id}W{j+1}' or not word.text.strip()
                            or word.start_ms < word_previous or word.start_ms < segment.start_ms
                            or not word.start_ms < word.end_ms <= segment.end_ms):
                        raise ValueError('invalid word timeline')
                    word_previous=word.start_ms
                if ''.join(word.text for word in segment.words).strip() != segment.text:
                    raise ValueError('word text mismatch')
            previous = segment.start_ms
        if self.text != '\n'.join(s.text for s in self.segments) or self.status != ('draft' if self.segments else 'no_speech'):
            raise ValueError('transcript mismatch')
        return self


class TranscriptionError(Exception):
    def __init__(self, code, status):
        self.code, self.status = code, status
        super().__init__(code)


class LocalTranscriber:
    def run(self, prepared, content, language=None):
        model = Path(os.environ.get('AICAP_STT_MODEL_DIR', str(ROOT / '.models/faster-whisper-base'))).resolve()
        if not all((model / name).is_file() for name in ('model.bin', 'config.json', 'tokenizer.json')):
            raise TranscriptionError('stt_model_not_installed', 503)
        if not _SLOT.acquire(blocking=False):
            raise TranscriptionError('stt_busy', 429)
        try:
            work = ROOT / '.stt-work'; work.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(dir=work) as directory:
                folder = Path(directory)
                audio = folder / 'audio.mp3'; output = folder / 'result.json'
                audio.write_bytes(content)
                env = os.environ.copy()
                env.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1')
                try:
                    completed = subprocess.run([sys.executable, '-B', '-m', 'meeting_agent.transcription_worker',
                        str(audio), str(output), str(model), language or 'auto'], cwd=ROOT, env=env,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180,
                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                except subprocess.TimeoutExpired:
                    raise TranscriptionError('stt_timeout', 504) from None
                if completed.returncode or not output.is_file() or output.stat().st_size > 256*1024:
                    raise TranscriptionError('stt_worker_failed', 502)
                data = json.loads(output.read_text(encoding='utf-8'))
                if 'error' in data:
                    codes = {'stt_dependency_missing': 503, 'invalid_audio': 422,
                             'audio_duration_exceeded': 413, 'transcript_too_large': 422}
                    raise TranscriptionError(data['error'] if data['error'] in codes else 'stt_failed', codes.get(data['error'], 502))
                return TranscriptionDraft(audio=prepared, **data)
        except (ValueError, OSError):
            raise TranscriptionError('invalid_stt_result', 502) from None
        finally:
            _SLOT.release()
