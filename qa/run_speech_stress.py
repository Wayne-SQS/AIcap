"""Repeat a public sample to exercise near-limit STT and diarization locally."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ai-service'))

from meeting_agent.audio_tool import PreparedAudio
from meeting_agent.diarization import LocalDiarizer
from meeting_agent.speech_runtime import SPEECH_JOB_TIMEOUT_SECONDS
from meeting_agent.transcription import LocalTranscriber


def repeated_mp3(source: Path, target: Path, repeat: int) -> None:
    import av
    with av.open(str(target), 'w') as output:
        stream = output.add_stream('libmp3lame', rate=16000)
        stream.layout = 'mono'
        stream.bit_rate = 64000
        for _ in range(repeat):
            with av.open(str(source)) as recording:
                resampler = av.AudioResampler(format='s16p', layout='mono', rate=16000)
                for frame in recording.decode(audio=0):
                    for converted in resampler.resample(frame):
                        converted.pts = None
                        for packet in stream.encode(converted):
                            output.mux(packet)
                for converted in resampler.resample(None):
                    converted.pts = None
                    for packet in stream.encode(converted):
                        output.mux(packet)
        for packet in stream.encode(None):
            output.mux(packet)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path,
                        default=ROOT / 'ai-service/.stt-eval/four-speakers-zh.wav')
    parser.add_argument('--repeat', type=int, choices=range(1, 11), default=10)
    parser.add_argument('--speaker-count', type=int, choices=range(1, 9), default=4)
    parser.add_argument('--skip-stt', action='store_true')
    parser.add_argument('--skip-diarization', action='store_true')
    args = parser.parse_args(argv)
    if args.skip_stt and args.skip_diarization:
        parser.error('at least one speech operation is required')
    if not args.source.is_file():
        parser.error('source audio does not exist')

    folder = ROOT / 'ai-service/.stt-eval' / uuid.uuid4().hex
    folder.mkdir(parents=True)
    audio_path = folder / 'near-limit.mp3'
    repeated_mp3(args.source.resolve(), audio_path, args.repeat)
    content = audio_path.read_bytes()
    metadata = PreparedAudio(
        meeting_id='public-stress-eval', audio_id='near-limit',
        sha256=hashlib.sha256(content).hexdigest(), byte_size=len(content),
        reported_duration_ms=None)
    report = {
        'schema_version': 1,
        'source': args.source.name,
        'repeat': args.repeat,
        'byte_size': len(content),
        'worker_timeout_seconds': SPEECH_JOB_TIMEOUT_SECONDS,
        'python': sys.version.split()[0],
        'operations': {},
    }

    if not args.skip_stt:
        started = time.monotonic()
        result = LocalTranscriber().run(metadata, content, 'zh')
        elapsed = time.monotonic() - started
        report['operations']['transcription'] = {
            'elapsed_seconds': round(elapsed, 2), 'duration_ms': result.duration_ms,
            'status': result.status, 'segments': len(result.segments),
            'words': sum(len(segment.words or []) for segment in result.segments),
        }
        (folder / 'transcription-draft.json').write_text(
            result.model_dump_json(indent=2), encoding='utf-8')
    if not args.skip_diarization:
        started = time.monotonic()
        result = LocalDiarizer().run(metadata, content, args.speaker_count)
        elapsed = time.monotonic() - started
        report['operations']['diarization'] = {
            'elapsed_seconds': round(elapsed, 2), 'duration_ms': result.duration_ms,
            'status': result.status, 'speaker_count': result.speaker_count,
            'turns': len(result.turns),
        }
        (folder / 'diarization-preview.json').write_text(
            result.model_dump_json(indent=2), encoding='utf-8')

    durations = {operation['duration_ms'] for operation in report['operations'].values()}
    if len(durations) != 1:
        raise RuntimeError('speech workers decoded different durations')
    duration = durations.pop()
    if args.repeat == 10 and not 540_000 <= duration <= 600_000:
        raise RuntimeError('default stress audio is outside the near-limit range')
    report['duration_ms'] = duration
    report['passed'] = True
    (folder / 'stress-result.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'artifacts': str(folder), **report}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    raise SystemExit(main())
