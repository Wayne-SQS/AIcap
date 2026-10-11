"""Aggregate multiple ground-truth speech reports using their additive counts."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path

from meeting_agent.speech_evaluation import (
    SpeechEvaluationError, _read_object, aggregate_reports,
)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports', type=Path, nargs='+', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        report = aggregate_reports([_read_object(path) for path in args.reports])
    except SpeechEvaluationError as error:
        parser.error(str(error))
    report['created_at'] = datetime.now(timezone.utc).isoformat()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"Samples={report['sample_count']} WER={report['wer']['error_rate']:.4f} "
          f"DER={report['der']['error_rate']:.4f}")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
