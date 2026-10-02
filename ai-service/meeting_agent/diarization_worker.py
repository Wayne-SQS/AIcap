"""Offline local sherpa inference in a disposable process. Labels are recording-local."""
import json
from pathlib import Path
import sys


def diarize(path, model_path, num_speakers):
    try:
        import av
        import numpy as np
        import sherpa_onnx as so
    except ImportError:
        return {'error':'diarization_dependency_missing'}
    try:
        pieces=[]; samples=0
        with av.open(str(path)) as container:
            resampler=av.AudioResampler(format='s16',layout='mono',rate=16000)
            def add(frames):
                nonlocal samples
                for frame in frames:
                    values=frame.to_ndarray().flatten(); samples+=values.size
                    if samples>16000*600: raise OverflowError()
                    pieces.append(values)
            for frame in container.decode(audio=0): add(resampler.resample(frame))
            add(resampler.resample(None))
        if not samples: return {'error':'invalid_audio'}
        waveform=np.concatenate(pieces).astype(np.float32)/32768.0
        duration=round(samples/16)
    except OverflowError:
        return {'error':'audio_duration_exceeded'}
    except Exception:
        return {'error':'invalid_audio'}
    try:
        config=so.OfflineSpeakerDiarizationConfig(
            segmentation=so.OfflineSpeakerSegmentationModelConfig(
                pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(model_path/'segmentation.onnx')),
                num_threads=2,provider='cpu'),
            embedding=so.SpeakerEmbeddingExtractorConfig(model=str(model_path/'embedding.onnx'),num_threads=2,provider='cpu'),
            clustering=so.FastClusteringConfig(num_clusters=num_speakers,threshold=0.5),
            min_duration_on=0.3,min_duration_off=0.5)
        if not config.validate(): return {'error':'diarization_failed'}
        model=so.OfflineSpeakerDiarization(config)
        if model.sample_rate!=16000: return {'error':'diarization_failed'}
        turns=[]; labels={}
        for turn in model.process(waveform).sort_by_start_time():
            start=max(0,round(turn.start*1000)); end=min(duration,round(turn.end*1000))
            if end<=start: continue
            if turn.speaker not in labels: labels[turn.speaker]=f'SPK{len(labels)+1}'
            if len(labels)>32 or len(turns)>=4000: return {'error':'diarization_result_too_large'}
            turns.append(dict(start_ms=start,end_ms=end,speaker_id=labels[turn.speaker]))
        return dict(duration_ms=duration,speaker_count=len(labels),turns=turns,status='draft' if turns else 'no_speech')
    except Exception:
        return {'error':'diarization_failed'}


if __name__=='__main__':
    result=diarize(Path(sys.argv[1]),Path(sys.argv[3]),int(sys.argv[4]))
    Path(sys.argv[2]).write_text(json.dumps(result),encoding='utf-8')
