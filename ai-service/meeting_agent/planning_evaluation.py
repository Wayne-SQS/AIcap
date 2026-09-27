"""Read-only quality acceptance of the production SprintPlanningSkill against synthetic cases."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path

from pydantic import ValidationError

from .contracts import ResultContractError
from .planning_context import SprintPlanningInput
from .planning_contracts import validate_planning_result
from .planning_skill import SprintPlanningSkill
from .model_client import ChatModelClient, ModelError, ModelSettings


def context_for(case):
    return SprintPlanningInput.model_validate({
        'meeting_id': case['id'], 'current_sprint': case['current_sprint'], 'target_sprint': case['target_sprint'],
        'transcript_segments': [{'segment_id': f'S{i}', 'text': text}
            for i, text in enumerate(case['transcript'].splitlines(), 1) if text.strip()],
        'stories': case['stories'], 'members': case['members'], 'tasks': case['tasks'],
    })


def action_difference(case, result):
    """Compare validated actions to the synthetic oracle, without guessing semantics."""
    actual = {(p['story_id'], p['expected']['sprint'], p['changes']['sprint'])
              for p in result['proposed_actions']}
    expected = {tuple(item) for item in case['expected_actions']}
    return {'missing': [list(item) for item in sorted(expected - actual)],
            'unexpected': [list(item) for item in sorted(actual - expected)]}


def assess(case, payload):
    """Contract + exact business outcomes; narrative truth still requires human inspection."""
    result = validate_planning_result(context_for(case), payload)
    actual = sorted((p.story_id, p.expected.sprint, p.changes.sprint) for p in result.proposed_actions)
    expected = sorted(tuple(item) for item in case['expected_actions'])
    failures = []
    if actual != expected:
        failures.append('unexpected_sprint_actions')
    if case.get('needs_question') and not result.open_questions:
        failures.append('missing_follow_up_question')
    for proposal in result.proposed_actions:
        supporting = case.get('supporting_segments', {}).get(proposal.story_id)
        if supporting and not any(e.segment_id in supporting for e in proposal.evidence):
            failures.append('missing_supporting_evidence')
    return failures, result.model_dump()


def validate_cases(cases):
    """Fail before any provider call when the synthetic oracle is malformed."""
    if not isinstance(cases, list) or not 1 <= len(cases) <= 100:
        raise ValueError('invalid_case_list')
    ids = set()
    skill = SprintPlanningSkill()
    for case in cases:
        context = context_for(case)
        skill.messages(context)  # Check input size before spending model calls.
        if context.meeting_id in ids:
            raise ValueError('duplicate_case_id')
        ids.add(context.meeting_id)
        if not isinstance(case.get('rubric'), str) or not case['rubric'].strip():
            raise ValueError('missing_rubric')
        if type(case.get('needs_question', False)) is not bool:
            raise ValueError('invalid_question_oracle')
        stories = {s.id: s for s in context.stories}
        segments = {s.segment_id for s in context.transcript_segments}
        seen = set()
        if not isinstance(case['expected_actions'], list):
            raise ValueError('invalid_action_oracle')
        for action in case['expected_actions']:
            if not isinstance(action, list) or len(action) != 3:
                raise ValueError('invalid_action_oracle')
            sid, before, after = action
            if (not isinstance(sid, str) or sid not in stories or sid in seen
                    or type(before) is not int or type(after) is not int
                    or before != stories[sid].sprint or not 1 <= after <= 4 or before == after):
                raise ValueError('invalid_action_oracle')
            seen.add(sid)
            support = case.get('supporting_segments', {}).get(sid)
            if (not isinstance(support, list) or not support
                    or any(not isinstance(s, str) or s not in segments for s in support)):
                raise ValueError('invalid_evidence_oracle')


def contract_diagnostics(error):
    """Never serialize input, exception messages, context or unknown field names."""
    if isinstance(error, ResultContractError):
        return [{'type': error.code, 'path': list(error.path)}]
    if isinstance(error, ValidationError):
        fields = {'schema_version', 'meeting_id', 'meeting_type', 'summary', 'proposed_actions',
                  'open_questions', 'proposal_id', 'action', 'story_id', 'expected', 'changes',
                  'sprint', 'reason', 'evidence', 'segment_id', 'quote'}
        diagnostics = []
        for item in error.errors(include_url=False, include_context=False, include_input=False)[:20]:
            path = [part if type(part) is int or part in fields else '<unknown_field>'
                    for part in item['loc']]
            code = item['type']
            # This validator has a fixed message; never expose arbitrary ValueError text.
            if code == 'value_error' and item['msg'] == 'Value error, proposal must change sprint':
                code = 'unchanged_sprint'
            diagnostics.append({'type': code, 'path': path})
        return diagnostics
    return [{'type': 'unclassified_contract_error', 'path': []}]


def load_model_env(path):
    # Explicit file only; no global dotenv discovery, interpolation or secret logging.
    if path is None:
        return
    allowed = {'AICAP_LLM_API_KEY', 'AICAP_LLM_BASE_URL', 'AICAP_LLM_MODEL'}
    for line in Path(path).read_text(encoding='utf-8-sig').splitlines():
        key, separator, value = line.partition('=')
        key = key.strip()
        if separator and key in allowed:
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            else:
                value = value.split(' #', 1)[0].rstrip()
            os.environ.setdefault(key, value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path)
    parser.add_argument('--cases', type=Path, default=Path(__file__).parents[1] / 'evals/planning_quality.json')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repeat', type=int, choices=range(1, 4), default=1)
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding='utf-8-sig'))
    validate_cases(cases)
    load_model_env(args.env_file)
    skill = SprintPlanningSkill()
    report = {'created_at': datetime.now(timezone.utc).isoformat(), 'skill_version': skill.version,
              'model': os.environ.get('AICAP_LLM_MODEL', 'deepseek-v4-flash'),
              'data': 'synthetic_only', 'repeat': args.repeat, 'cases': [],
              'manual_review_required': True}
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        report['passed'] = sum(item['status'] == 'passed' for item in report['cases'])
        report['planned'] = len(cases) * args.repeat
        report['automated_acceptance_passed'] = report['passed'] == report['planned']
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    try:
        model = ChatModelClient(ModelSettings.from_env())
        for repetition in range(1, args.repeat + 1):
            for case in cases:
                item = {'id': case['id'], 'repetition': repetition, 'rubric': case['rubric']}
                try:
                    payload = model.complete(skill.messages(context_for(case)))
                    failures, result = assess(case, payload)
                    item.update(status='failed' if failures else 'passed', failures=failures, result=result)
                    if 'unexpected_sprint_actions' in failures:
                        item['action_difference'] = action_difference(case, result)
                except ModelError as error:
                    if error.code not in {'invalid_model_response', 'incomplete_model_output', 'provider_response_too_large'}:
                        raise
                    item.update(status='failed', failures=[error.code])
                    if error.diagnostic is not None:
                        item['model_diagnostic'] = error.diagnostic
                except ValueError as error:
                    item.update(status='failed', failures=['invalid_model_proposal'],
                                contract_errors=contract_diagnostics(error))
                report['cases'].append(item)
                save()
                print(f"{case['id']} [{repetition}]: {item['status']}", flush=True)
    except ModelError as error:
        # Stop provider failures rather than repeatedly consuming requests; never log raw HTTP payloads.
        report['provider_error'] = error.code
        save()
        print(f'Provider stopped: {error.code}', flush=True)
        return 2
    print(f"Passed {report['passed']}/{report['planned']}; narrative review still required.", flush=True)
    return 0 if report['automated_acceptance_passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())


