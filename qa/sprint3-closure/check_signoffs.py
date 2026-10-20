"""Check the Experiment 3 AI review ledger before declaring signoff complete."""

import csv
from datetime import datetime
from pathlib import Path
import sys


LEDGER = Path(__file__).with_name('review-signoff-pending.csv')
EXPECTED = {
    'dbed29d65f22', '21fc3475f0f9', '1705b9271ea4', 'call-04',
    '9ef631270e93', 'b7a57915db7c', 'e3808f5a25d2', '24d49ed1c77b',
    '7d687ce6423b', '8760e6aec0f6', '57bfd97594a2', 'c8d018da3649',
    '3e44fcc342b3',
}


def main():
    with LEDGER.open(encoding='utf-8-sig', newline='') as stream:
        rows = list(csv.DictReader(stream))
    errors = []
    ids = [row['source_record_id'].strip() for row in rows]
    if len(rows) != 13 or set(ids) != EXPECTED or len(set(ids)) != 13:
        errors.append('source records must match all 13 original interactions exactly once')
    pending = []
    for row in rows:
        record = row['source_record_id']
        if not all(row[field].strip() for field in ('source_time', 'ai_role', 'task_scene', 'team_disposition_from_source', 'source_location')):
            errors.append(f'{record}: original context or team disposition missing')
        status = row['status'].strip()
        if status == 'pending':
            pending.append(record)
            if any(row[field].strip() for field in ('reviewer_name', 'signed_at', 'signoff_evidence')):
                errors.append(f'{record}: partial signoff fields; complete the row before changing status')
        elif status == 'signed':
            if not all(row[field].strip() for field in ('reviewer_name', 'signed_at', 'signoff_evidence')):
                errors.append(f'{record}: signed row needs reviewer, time and evidence')
                continue
            try:
                signed_at = datetime.fromisoformat(row['signed_at'])
                if signed_at.tzinfo is None:
                    raise ValueError('timezone required')
            except ValueError:
                errors.append(f'{record}: signed_at must be ISO 8601 with timezone')
            evidence = (LEDGER.parent / row['signoff_evidence']).resolve()
            if not evidence.is_file():
                errors.append(f'{record}: signoff evidence file not found')
        else:
            errors.append(f'{record}: status must be pending or signed')
    for error in errors:
        print('INVALID:', error)
    print(f'Original interactions: {len(rows)}; pending human signoffs: {len(pending)}')
    if pending:
        print('PENDING:', ', '.join(pending))
    return 1 if errors or pending else 0


if __name__ == '__main__':
    sys.exit(main())
