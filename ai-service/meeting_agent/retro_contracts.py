"""Retrospective action-item candidates. No task, story or action-item writes."""
from typing import Annotated, Literal
from pydantic import Field, model_validator

from .contracts import Contract, Evidence, Identifier, NonBlank, TranscriptSegment, ResultContractError


class RetroMember(Contract):
    user_id: Annotated[int, Field(gt=0)]
    display_name: Annotated[NonBlank, Field(max_length=50)]


class SprintRetroInput(Contract):
    schema_version: Literal['1.0'] = '1.0'
    meeting_id: Identifier
    meeting_type: Literal['sprint_retrospective'] = 'sprint_retrospective'
    current_sprint: Annotated[int, Field(ge=1, le=4)] | None = None
    transcript_segments: Annotated[list[TranscriptSegment], Field(min_length=1, max_length=100)]
    members: Annotated[list[RetroMember], Field(max_length=1000)]

    @model_validator(mode='after')
    def validate_context(self):
        if len({m.user_id for m in self.members}) != len(self.members):
            raise ValueError('duplicate member')
        if len({s.segment_id for s in self.transcript_segments}) != len(self.transcript_segments):
            raise ValueError('duplicate segment')
        if sum(len(s.text) for s in self.transcript_segments) > 16000:
            raise ValueError('transcript too large')
        return self


class Decision(Contract):
    text: Annotated[NonBlank, Field(max_length=1000)]
    evidence: Annotated[list[Evidence], Field(min_length=1, max_length=10)]


class ActionItemChanges(Contract):
    title: Annotated[NonBlank, Field(max_length=200)]
    description: Annotated[str, Field(max_length=2000)]
    owner_id: Annotated[int, Field(gt=0)] | None
    # Verbatim date/time phrase, not a normalized date inferred from today's clock.
    deadline_text: Annotated[NonBlank, Field(max_length=200)] | None


class ActionItemProposal(Contract):
    proposal_id: Identifier
    action: Literal['create_action_item']
    changes: ActionItemChanges
    reason: Annotated[NonBlank, Field(max_length=1000)]
    evidence: Annotated[list[Evidence], Field(min_length=1, max_length=10)]


class SprintRetroOutput(Contract):
    schema_version: Literal['1.0'] = '1.0'
    meeting_id: Identifier
    meeting_type: Literal['sprint_retrospective'] = 'sprint_retrospective'
    summary: Annotated[NonBlank, Field(max_length=2000)]
    decisions: Annotated[list[Decision], Field(max_length=20)]
    proposed_actions: Annotated[list[ActionItemProposal], Field(max_length=20)]
    open_questions: Annotated[list[Annotated[NonBlank, Field(max_length=500)]], Field(max_length=20)]


def validate_retro_result(context: SprintRetroInput, payload: dict) -> SprintRetroOutput:
    context = SprintRetroInput.model_validate(context.model_dump())
    result = SprintRetroOutput.model_validate(payload)
    if result.meeting_id != context.meeting_id:
        raise ResultContractError('meeting mismatch', 'meeting_mismatch', ('meeting_id',))
    segments = {s.segment_id: s.text for s in context.transcript_segments}
    members = {m.user_id: m for m in context.members}
    ids = set()
    contents = set()
    for group, items in (('decisions', result.decisions), ('proposed_actions', result.proposed_actions)):
        for index, item in enumerate(items):
            path = (group, index)
            for ei, evidence in enumerate(item.evidence):
                text = segments.get(evidence.segment_id)
                if text is None or evidence.quote not in text:
                    raise ResultContractError('invalid evidence', 'invalid_evidence', path + ('evidence', ei))
            if group == 'decisions':
                continue
            if item.proposal_id in ids:
                raise ResultContractError('duplicate proposal', 'duplicate_proposal_id', path + ('proposal_id',))
            ids.add(item.proposal_id)
            signature = (item.changes.title.strip(), item.changes.description.strip(),
                         item.changes.owner_id, item.changes.deadline_text)
            if signature in contents:
                raise ResultContractError('duplicate action item', 'duplicate_action_item', path)
            contents.add(signature)
            if item.changes.owner_id is not None:
                owner = members.get(item.changes.owner_id)
                if owner is None:
                    raise ResultContractError('unknown owner', 'unknown_owner', path + ('changes', 'owner_id'))
                # No reliable speaker-to-user mapping exists. Require an explicit,
                # unambiguous display name in the proposal's cited original text.
                if (sum(m.display_name == owner.display_name for m in context.members) != 1
                        or not any(owner.display_name in e.quote for e in item.evidence)):
                    raise ResultContractError('unresolved owner', 'unresolved_owner', path + ('changes', 'owner_id'))
            deadline = item.changes.deadline_text
            if deadline is not None and not any(deadline in e.quote for e in item.evidence):
                raise ResultContractError('deadline not quoted', 'deadline_not_quoted', path + ('changes', 'deadline_text'))
    # Quotes establish provenance, not whether a suggestion was agreed or assigned.
    return result
