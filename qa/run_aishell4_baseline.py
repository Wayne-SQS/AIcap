"""Prepare one licensed AISHELL-4 clip and produce a local WER/DER baseline."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'ai-service'
sys.path.insert(0, str(SERVICE))

from meeting_agent.audio_tool import PreparedAudio
from meeting_agent.diarization import LocalDiarizer
from meeting_agent.speech_dataset import parse_rttm, parse_textgrid
from meeting_agent.speech_evaluation import evaluate
from meeting_agent.transcription import LocalTranscriber


SAMPLE = 'L_R003S01C02'
FILES = {
    f'{SAMPLE}.flac': (
        f'https://huggingface.co/datasets/AISHELL/AISHELL-4/resolve/main/test/wav/{SAMPLE}.flac?download=true',
        '93125b2c8f9f73c042ccd63178d1fec6db2d3047af4078834da7c6c1aa118e72'),
    f'{SAMPLE}.TextGrid': (
        f'https://huggingface.co/datasets/AISHELL/AISHELL-4/resolve/main/test/TextGrid/{SAMPLE}.TextGrid?download=true',
        'a7e060c87f76f6ad51ca1bcd6ae7243942757f25bdcbfc9a628c552d03dcbc4c'),
    f'{SAMPLE}.rttm': (
        f'https://huggingface.co/datasets/AISHELL/AISHELL-4/resolve/main/test/TextGrid/{SAMPLE}.rttm?download=true',
        '9dfd301c5b96f60813aaddec2549edc007851699917d9326e4db7e13c6b05ed9'),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def prepare_sources(folder: Path, download: bool) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in FILES.items():
        target = folder / name
        if (not target.is_file() or sha256(target) != expected) and download:
            temporary = target.with_suffix(target.suffix + '.part')
            request = Request(url, headers={'User-Agent': 'AIcap-evaluation/1'})
            with urlopen(request, timeout=60) as response, temporary.open('wb') as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            if sha256(temporary) != expected:
                temporary.unlink(missing_ok=True)
                raise RuntimeError(f'checksum mismatch: {name}')
            temporary.replace(target)
        if not target.is_file():
            raise RuntimeError(f'missing source; rerun with --download: {name}')
        if sha256(target) != expected:
            raise RuntimeError(f'checksum mismatch: {name}')


def encode_clip(source: Path, target: Path, start_seconds: int, duration_seconds: int) -> None:
    import av
    import numpy as np
    first_sample = start_seconds * 16000
    last_sample = (start_seconds + duration_seconds) * 16000
    pieces, decoded_samples, selected_samples = [], 0, 0
    with av.open(str(source)) as recording:
        resampler = av.AudioResampler(format='s16', layout='mono', rate=16000)
        for frame in recording.decode(audio=0):
            for converted in resampler.resample(frame):
                values = converted.to_ndarray().reshape(-1)
                frame_start, frame_end = decoded_samples, decoded_samples + len(values)
                left, right = max(frame_start, first_sample), min(frame_end, last_sample)
                if left < right:
                    pieces.append(values[left-frame_start:right-frame_start].copy())
                    selected_samples += right-left
                decoded_samples = frame_end
                if decoded_samples >= last_sample:
                    break
            if decoded_samples >= last_sample:
                break
    if selected_samples != duration_seconds * 16000:
        raise RuntimeError('source is shorter than requested clip')
    audio = np.concatenate(pieces)
    with av.open(str(target), 'w') as output:
        stream = output.add_stream('libmp3lame', rate=16000)
        stream.layout = 'mono'
        stream.bit_rate = 64000
        for offset in range(0, len(audio), 16000):
            frame = av.AudioFrame.from_ndarray(
                audio[offset:offset + 16000].reshape(1, -1), format='s16', layout='mono')
            frame.sample_rate = 16000
            for packet in stream.encode(frame):
                output.mux(packet)
        for packet in stream.encode(None):
            output.mux(packet)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true',
                        help='Download the pinned 286 MB official source when absent.')
    parser.add_argument('--duration-seconds', type=int, choices=range(60, 601), default=300)
    parser.add_argument('--start-seconds', type=int, default=0)
    args = parser.parse_args(argv)
    if args.start_seconds < 0:
        parser.error('--start-seconds must be non-negative')
    source = SERVICE / '.stt-eval/aishell4-source'
    prepare_sources(source, args.download)
    run = SERVICE / '.stt-eval' / uuid.uuid4().hex
    run.mkdir(parents=True)
    mp3 = run / f'{SAMPLE}-{args.start_seconds}-{args.start_seconds + args.duration_seconds}s.mp3'
    encode_clip(source / f'{SAMPLE}.flac', mp3, args.start_seconds, args.duration_seconds)
    content = mp3.read_bytes()
    metadata = PreparedAudio(
        meeting_id='aishell4-evaluation', audio_id=f'{SAMPLE}-{args.start_seconds}',
        sha256=hashlib.sha256(content).hexdigest(), byte_size=len(content),
        reported_duration_ms=args.duration_seconds * 1000)

    textgrid = (source / f'{SAMPLE}.TextGrid').read_text(encoding='utf-8-sig')
    rttm = (source / f'{SAMPLE}.rttm').read_text(encoding='utf-8-sig')
    expected_duration_ms = args.duration_seconds * 1000
    source_start_ms = args.start_seconds * 1000
    reference_text, reference_intervals = parse_textgrid(
        textgrid, expected_duration_ms, source_start_ms)
    reference_turns = parse_rttm(rttm, expected_duration_ms, SAMPLE, source_start_ms)
    requested_speakers = len({turn['speaker_id'] for turn in reference_turns})
    if not 1 <= requested_speakers <= 8:
        raise RuntimeError('reference speaker count is outside product limits')

    started = time.monotonic()
    transcript = LocalTranscriber().run(metadata, content, 'zh')
    stt_seconds = round(time.monotonic() - started, 2)
    started = time.monotonic()
    diarization = LocalDiarizer().run(metadata, content, requested_speakers)
    diarization_seconds = round(time.monotonic() - started, 2)
    if transcript.duration_ms != diarization.duration_ms or transcript.duration_ms != expected_duration_ms:
        raise RuntimeError('speech workers decoded different durations')
    reference = {
        'sample_id': f'aishell4-{SAMPLE}-{args.start_seconds}-{args.start_seconds + args.duration_seconds}s',
        'duration_ms': transcript.duration_ms,
        'text': reference_text,
        'turns': reference_turns,
    }
    report = evaluate(reference, transcript.model_dump(), diarization.model_dump())
    report.update({
        'dataset': 'AISHELL-4',
        'dataset_license': 'CC BY-SA 4.0',
        'dataset_source': 'https://www.openslr.org/111/',
        'recording_id': SAMPLE,
        'source_clip': {'start_ms': source_start_ms,
                        'end_ms': source_start_ms + transcript.duration_ms},
        'reference_transcript_intervals': len(reference_intervals),
        'elapsed_seconds': {'transcription': stt_seconds,
                            'diarization': diarization_seconds},
        'model_configuration': {'stt': transcript.engine,
                                'diarization': diarization.engine,
                                'requested_speakers': requested_speakers},
        'limitations': [
            'single_stream_wer_orders_overlapping_reference_intervals_by_start_time',
            'one_session_window_does_not_establish_a_release_threshold',
        ],
    })
    (run / 'reference.json').write_text(
        json.dumps(reference, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (run / 'transcription-draft.json').write_text(
        transcript.model_dump_json(indent=2), encoding='utf-8')
    (run / 'diarization-preview.json').write_text(
        diarization.model_dump_json(indent=2), encoding='utf-8')
    (run / 'speech-quality-report.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'artifacts': str(run), 'wer': report['wer']['error_rate'],
                      'der': report['der']['error_rate'],
                      'word_timestamp_coverage': report['word_alignment']['word_timestamp_coverage'],
                      'elapsed_seconds': report['elapsed_seconds']}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    raise SystemExit(main())
