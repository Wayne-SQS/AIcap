"""Download only official sherpa release models; never runs during an audio request."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
BASE = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/'
SEGMENTATION = BASE + 'speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2'
EMBEDDING = BASE + 'speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx'
SAMPLE = BASE + 'speaker-segmentation-models/0-four-speakers-zh.wav'

def fetch(url, opener):
    with opener.open(url, timeout=60) as response:
        content = response.read(256*1024*1024+1)
    if len(content)>256*1024*1024: raise ValueError('Download exceeds 256 MiB')
    return content

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proxy'); parser.add_argument('--sample',action='store_true')
    args=parser.parse_args()
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({'http':args.proxy,'https':args.proxy})) if args.proxy else urllib.request.build_opener()
    folder=ROOT/'.models/diarization'; folder.mkdir(parents=True,exist_ok=True)
    archive=fetch(SEGMENTATION,opener)
    records=[]
    with tarfile.open(fileobj=io.BytesIO(archive),mode='r:bz2') as tar:
        for source,target in [('model.onnx','segmentation.onnx'),('LICENSE','segmentation.LICENSE'),('README.md','segmentation.README.md')]:
            member=tar.getmember('sherpa-onnx-pyannote-segmentation-3-0/'+source)
            if not member.isfile() or member.size>32*1024*1024: raise ValueError('Unexpected archive member')
            content=tar.extractfile(member).read()
            (folder/target).write_bytes(content)
            records.append(dict(file=target,url=SEGMENTATION,sha256=hashlib.sha256(content).hexdigest()))
    for url,target in [(EMBEDDING,'embedding.onnx'),('https://raw.githubusercontent.com/modelscope/3D-Speaker/main/LICENSE','embedding.LICENSE')]:
        content=fetch(url,opener); (folder/target).write_bytes(content)
        records.append(dict(file=target,url=url,sha256=hashlib.sha256(content).hexdigest()))
    (folder/'manifest.json').write_text(json.dumps(records,indent=2),encoding='utf-8')
    if args.sample:
        sample=ROOT/'.stt-eval/four-speakers-zh.wav'; sample.parent.mkdir(parents=True,exist_ok=True)
        sample.write_bytes(fetch(SAMPLE,opener)); print(f'Sample: {sample}')
    print(f'Models ready: {folder}')

if __name__=='__main__': main()
