"""Authenticated audio diarization preview, separate from text attribution and identity."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Annotated, Literal
from pydantic import Field, model_validator
from .audio_tool import AudioInputRequest, PreparedAudio
from .contracts import Contract
from .transcription import ROOT, _SLOT, TranscriptionError


class DiarizationRequest(AudioInputRequest):
    num_speakers: Annotated[int, Field(strict=True, ge=1, le=8)] | None = None


class SpeakerTurn(Contract):
    start_ms: Annotated[int, Field(strict=True, ge=0)]
    end_ms: Annotated[int, Field(strict=True, gt=0)]
    speaker_id: Annotated[str, Field(pattern=r'^SPK(?:[1-9]|[12][0-9]|3[0-2])$')]


class DiarizationPreview(Contract):
    audio: PreparedAudio
    engine: Literal['sherpa-onnx-pyannote3-eres2net'] = 'sherpa-onnx-pyannote3-eres2net'
    duration_ms: Annotated[int, Field(strict=True, gt=0, le=600000)]
    requested_num_speakers: Annotated[int, Field(strict=True, ge=1, le=8)] | None
    speaker_count: Annotated[int, Field(strict=True, ge=0, le=32)]
    turns: Annotated[list[SpeakerTurn], Field(max_length=4000)]
    status: Literal['draft', 'no_speech']
    identity_status: Literal['anonymous_only'] = 'anonymous_only'
    storage_status: Literal['not_saved'] = 'not_saved'
    requires_human_review: Literal[True] = True

    @model_validator(mode='after')
    def consistent(self):
        seen = set(); previous = 0
        for turn in self.turns:
            if not previous <= turn.start_ms < turn.end_ms <= self.duration_ms:
                raise ValueError('invalid speaker timeline')
            if turn.speaker_id not in seen:
                if turn.speaker_id != f'SPK{len(seen)+1}': raise ValueError('invalid speaker sequence')
                seen.add(turn.speaker_id)
            previous = turn.start_ms
        if self.speaker_count != len(seen) or self.status != ('draft' if seen else 'no_speech'):
            raise ValueError('invalid speaker summary')
        return self


class LocalDiarizer:
    def run(self, prepared, content, num_speakers=None):
        model = Path(os.environ.get('AICAP_DIARIZATION_MODEL_DIR', str(ROOT / '.models/diarization'))).resolve()
        if not all((model / name).is_file() for name in ('segmentation.onnx', 'embedding.onnx')):
            raise TranscriptionError('diarization_model_not_installed', 503)
        if not _SLOT.acquire(blocking=False): raise TranscriptionError('stt_busy', 429)
        try:
            work = ROOT / '.stt-work'; work.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(dir=work) as directory:
                folder=Path(directory); audio=folder/'audio.mp3'; output=folder/'result.json'
                audio.write_bytes(content)
                try:
                    result=subprocess.run([sys.executable,'-B','-m','meeting_agent.diarization_worker',str(audio),str(output),str(model),str(num_speakers or -1)],
                        cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180,
                        creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
                except subprocess.TimeoutExpired:
                    raise TranscriptionError('diarization_timeout',504) from None
                if result.returncode or not output.is_file() or output.stat().st_size>512*1024:
                    raise TranscriptionError('diarization_worker_failed',502)
                data=json.loads(output.read_text(encoding='utf-8'))
                if not isinstance(data,dict): raise ValueError('invalid worker response')
                if 'error' in data:
                    codes={'diarization_dependency_missing':503,'invalid_audio':422,'audio_duration_exceeded':413,'diarization_result_too_large':422}
                    code=data['error']
                    raise TranscriptionError(code if isinstance(code,str) and code in codes else 'diarization_failed',codes.get(code,502) if isinstance(code,str) else 502)
                return DiarizationPreview(audio=prepared,requested_num_speakers=num_speakers,**data)
        except (ValueError,TypeError,OSError):
            raise TranscriptionError('invalid_diarization_result',502) from None
        finally:
            _SLOT.release()
