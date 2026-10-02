"""One process per job bounds native decoder/model lifetime; stdout/stderr are not API output."""
import json
from pathlib import Path
import sys


def transcribe(path, model_path, language):
    try:
        import av
        import numpy as np
        from faster_whisper import WhisperModel
    except ImportError:
        return {'error': 'stt_dependency_missing'}
    pieces, samples = [], 0
    try:
        with av.open(str(path)) as container:
            resampler = av.AudioResampler(format='s16', layout='mono', rate=16000)
            for frame in container.decode(audio=0):
                for converted in resampler.resample(frame):
                    values = converted.to_ndarray().flatten()
                    samples += values.size
                    if samples > 16000*600: return {'error': 'audio_duration_exceeded'}
                    pieces.append(values)
            for converted in resampler.resample(None):
                values = converted.to_ndarray().flatten(); samples += values.size; pieces.append(values)
        if not samples: return {'error': 'invalid_audio'}
        if samples > 16000*600: return {'error': 'audio_duration_exceeded'}
        audio = np.concatenate(pieces).astype(np.float32) / 32768.0
        duration = round(samples / 16)
    except Exception:
        return {'error': 'invalid_audio'}
    try:
        model = WhisperModel(str(model_path), device='cpu', compute_type='int8', cpu_threads=4, local_files_only=True)
        segments, info = model.transcribe(audio, language=None if language == 'auto' else language,
            beam_size=5, vad_filter=True, condition_on_previous_text=False, word_timestamps=True)
        rows, length = [], 0
        for segment in segments:
            text = segment.text.strip()
            if not text: continue
            start, end = max(0, round(segment.start*1000)), min(duration, round(segment.end*1000))
            if end <= start: continue
            segment_id=f'S{len(rows)+1}'; words=[]
            for word in segment.words or []:
                if not word.word.strip(): continue
                word_start=max(start,round(word.start*1000)); word_end=min(end,round(word.end*1000))
                if word_end<=word_start: return {'error':'stt_failed'}
                words.append(dict(word_id=f'{segment_id}W{len(words)+1}',start_ms=word_start,end_ms=word_end,text=word.word))
            if not words or ''.join(word['text'] for word in words).strip()!=text: return {'error':'stt_failed'}
            length += len(text)+1
            if length > 16000 or len(rows) >= 2000 or len(text) > 4000: return {'error': 'transcript_too_large'}
            rows.append(dict(segment_id=segment_id, start_ms=start, end_ms=end, text=text, speaker_id=None,words=words))
        return dict(language=info.language, duration_ms=duration, segments=rows,
                    text='\n'.join(row['text'] for row in rows), status='draft' if rows else 'no_speech')
    except Exception:
        return {'error': 'stt_failed'}


if __name__ == '__main__':
    result = transcribe(Path(sys.argv[1]), Path(sys.argv[3]), sys.argv[4])
    Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False), encoding='utf-8')
