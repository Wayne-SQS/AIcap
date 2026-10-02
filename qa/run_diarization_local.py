"""Actual CPU diarization on sherpa's public four-speaker sample; no personal audio."""
import hashlib
import json
from pathlib import Path
import sys
import time
import uuid
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ai-service'))
from meeting_agent.audio_tool import PreparedAudio
from meeting_agent.diarization import LocalDiarizer

def main():
    import av
    folder=ROOT/'ai-service/.stt-eval'/uuid.uuid4().hex; folder.mkdir(parents=True)
    mp3=folder/'four-speakers.mp3'
    with av.open(str(ROOT/'ai-service/.stt-eval/four-speakers-zh.wav')) as source, av.open(str(mp3),'w') as target:
        stream=target.add_stream('libmp3lame',rate=16000); stream.layout='mono'; stream.bit_rate=64000
        resampler=av.AudioResampler(format='s16p',layout='mono',rate=16000)
        for frame in source.decode(audio=0):
            for converted in resampler.resample(frame):
                for packet in stream.encode(converted): target.mux(packet)
        for converted in resampler.resample(None):
            for packet in stream.encode(converted): target.mux(packet)
        for packet in stream.encode(None): target.mux(packet)
    content=mp3.read_bytes()
    metadata=PreparedAudio(meeting_id='public-eval',audio_id='four-speakers',sha256=hashlib.sha256(content).hexdigest(),byte_size=len(content),reported_duration_ms=None)
    reports=[]
    for hint in (None,4):
        start=time.monotonic(); result=LocalDiarizer().run(metadata,content,hint)
        assert result.status=='draft' and result.turns
        reports.append(dict(result.model_dump(),elapsed_seconds=round(time.monotonic()-start,2)))
    (folder/'results.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'artifacts':str(folder),'results':[{'hint':r['requested_num_speakers'],'detected':r['speaker_count'],'turns':len(r['turns']),'seconds':r['elapsed_seconds']} for r in reports]}))

if __name__=='__main__': main()
