"""Explicit optional model download. Serving requests never downloads a model."""
import json
from pathlib import Path
from huggingface_hub import HfApi, snapshot_download

if __name__ == '__main__':
    repo = 'Systran/faster-whisper-base'
    root = Path(__file__).resolve().parent / '.models' / 'faster-whisper-base'
    revision = HfApi().model_info(repo).sha
    snapshot_download(repo, revision=revision, local_dir=root,
        allow_patterns=['config.json', 'model.bin', 'tokenizer.json', 'vocabulary.*', 'preprocessor_config.json', 'README.md'])
    (root / 'aicap-model.json').write_text(json.dumps({'repository': repo, 'revision': revision}, indent=2), encoding='utf-8')
    print(f'Model ready: {root}\nRevision: {revision}')
