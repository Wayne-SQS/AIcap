"""Windows synthetic speech -> real MP3 decode -> local Whisper. No database or provider."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ai-service'))
from meeting_agent.audio_tool import PreparedAudio
from meeting_agent.transcription import LocalTranscriber


def main():
    folder = ROOT / 'ai-service/.stt-eval' / uuid.uuid4().hex
    folder.mkdir(parents=True)
    script = """
Add-Type -AssemblyName System.Speech
$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
$voice.SelectVoice('Microsoft Huihui Desktop')
$voice.SetOutputToWaveFile((Join-Path $env:AICAP_STT_EVAL_DIR 'zh.wav'))
$voice.Speak('今天的会议决定先完成登录接口，再补充自动化测试。负责人和截止时间需要人工确认。')
$voice.SetOutputToNull()
$voice.SelectVoice('Microsoft Zira Desktop')
$voice.SetOutputToWaveFile((Join-Path $env:AICAP_STT_EVAL_DIR 'en.wav'))
$voice.Speak('The team will finish the login feature and add automated tests. The owner and deadline still need human confirmation.')
$voice.Dispose()
"""
    env = dict(os.environ, AICAP_STT_EVAL_DIR=str(folder))
    subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-EncodedCommand', base64.b64encode(script.encode('utf-16le')).decode()],
        env=env, check=True, creationflags=subprocess.CREATE_NO_WINDOW, timeout=60)
    import av
    reports = []
    for language in ('zh','en'):
        mp3 = folder / (language+'.mp3')
        with av.open(str(folder / (language+'.wav'))) as source, av.open(str(mp3), 'w') as target:
            stream = target.add_stream('libmp3lame', rate=16000); stream.layout = 'mono'; stream.bit_rate = 64000
            resampler = av.AudioResampler(format='s16p', layout='mono', rate=16000)
            for frame in source.decode(audio=0):
                for converted in resampler.resample(frame):
                    for packet in stream.encode(converted): target.mux(packet)
            for converted in resampler.resample(None):
                for packet in stream.encode(converted): target.mux(packet)
            for packet in stream.encode(None): target.mux(packet)
        content = mp3.read_bytes()
        audio = PreparedAudio(meeting_id='synthetic-eval',audio_id=language,sha256=hashlib.sha256(content).hexdigest(),byte_size=len(content),reported_duration_ms=None)
        start = time.monotonic()
        result = LocalTranscriber().run(audio,content,language)
        assert result.status == 'draft' and result.segments
        report = dict(result.model_dump(), elapsed_seconds=round(time.monotonic()-start,2))
        reports.append(report)
    (folder/'results.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'artifacts':str(folder),'results':[{'language':r['language'],'text':r['text'],'elapsed_seconds':r['elapsed_seconds']} for r in reports]},ensure_ascii=False))

if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
