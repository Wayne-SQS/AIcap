"""Bounded, read-only production Retro skill evaluation on synthetic cases."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path

from .retro_contracts import SprintRetroInput, validate_retro_result
from .retro_skill import SprintRetroSkill
from .model_client import ChatModelClient, ModelError, ModelSettings
from .review_evaluation import load_model_env, contract_diagnostics


def context_for(case):
    return SprintRetroInput.model_validate(dict(meeting_id=case['id'], current_sprint=None,
        members=case['members'], transcript_segments=[dict(segment_id=f'S{i}', text=line)
            for i, line in enumerate(case['transcript'].splitlines(), 1) if line.strip()]))


def assess(case, payload):
    result = validate_retro_result(context_for(case), payload)
    failures = []
    actions = result.proposed_actions
    if len(actions) != len(case['expected_actions']):
        failures.append('unexpected_action_count')
    # Each bounded case has at most one action; titles remain semantic/manual checks.
    if len(actions) == len(case['expected_actions']) == 1:
        action, expected = actions[0], case['expected_actions'][0]
        if action.changes.owner_id != expected['owner_id']:
            failures.append('unexpected_owner')
        if action.changes.deadline_text != expected['deadline_text']:
            failures.append('unexpected_deadline')
        if not any(e.segment_id in expected['supporting_segments'] for e in action.evidence):
            failures.append('missing_supporting_evidence')
    if case['needs_question'] and not result.open_questions:
        failures.append('missing_follow_up_question')
    return failures, result.model_dump()


def validate_cases(cases):
    if not isinstance(cases, list) or not 1 <= len(cases) <= 20:
        raise ValueError('invalid_case_list')
    ids = set()
    for case in cases:
        context = context_for(case)
        SprintRetroSkill().messages(context)
        if context.meeting_id in ids:
            raise ValueError('duplicate_case_id')
        ids.add(context.meeting_id)
        if not isinstance(case.get('rubric'), str) or not case['rubric'].strip() or type(case.get('needs_question')) is not bool:
            raise ValueError('invalid_rubric')
        expected = case['expected_actions']
        if not isinstance(expected, list) or len(expected) > 1:
            raise ValueError('invalid_action_oracle')
        if expected:
            action = expected[0]
            segments = {s.segment_id: s.text for s in context.transcript_segments}
            support = action['supporting_segments']
            if not isinstance(support, list) or not support or any(s not in segments for s in support):
                raise ValueError('invalid_evidence_oracle')
            # Exercise the production constraints on the expected owner/time before any calls.
            validate_retro_result(context, dict(meeting_id=case['id'], meeting_type='sprint_retrospective',
                summary='离线预期校验', decisions=[], open_questions=[], proposed_actions=[dict(
                    proposal_id='oracle', action='create_action_item', reason='离线预期校验',
                    changes=dict(title='预期行动', description='', owner_id=action['owner_id'], deadline_text=action['deadline_text']),
                    evidence=[dict(segment_id=s, quote=segments[s]) for s in support])]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path)
    parser.add_argument('--cases', type=Path, default=Path(__file__).parents[1] / 'evals/retro_quality.json')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding='utf-8-sig'))
    validate_cases(cases)
    if args.output.exists():
        raise ValueError('output_already_exists')
    load_model_env(args.env_file)
    settings = ModelSettings.from_env()
    skill = SprintRetroSkill()
    report = dict(created_at=datetime.now(timezone.utc).isoformat(), skill_version=skill.version,
        model=settings.model, data='synthetic_only', planned=len(cases), cases=[], manual_review_required=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        report['passed'] = sum(c['status'] == 'passed' for c in report['cases'])
        report['automated_acceptance_passed'] = report['passed'] == len(cases)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    try:
        model = ChatModelClient(settings)
        for case in cases:
            item = dict(id=case['id'], rubric=case['rubric'])
            try:
                payload = model.complete(skill.messages(context_for(case)))
                item['candidate'] = payload  # Synthetic response retained even on contract failure.
                failures, result = assess(case, payload)
                item.update(status='failed' if failures else 'passed', failures=failures, result=result)
            except ModelError as error:
                if error.code not in {'invalid_model_response', 'incomplete_model_output', 'provider_response_too_large'}:
                    raise
                item.update(status='failed', failures=[error.code])
            except ValueError as error:
                item.update(status='failed', failures=['invalid_model_proposal'], contract_errors=contract_diagnostics(error))
            report['cases'].append(item)
            save()
            print(f"{case['id']}: {item['status']}", flush=True)
    except ModelError as error:
        report['provider_error'] = error.code
        save()
        print(f'Provider stopped: {error.code}', flush=True)
        return 2
    print(f"Passed {report['passed']}/{report['planned']}; manual review required.", flush=True)
    return 0 if report['automated_acceptance_passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
