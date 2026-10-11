"""First Planning output slice: Sprint changes awaiting human review."""
from typing import Annotated, Literal

from pydantic import Field, model_validator

from .contracts import Contract, Evidence, Identifier, NonBlank, ResultContractError, StoryId
from .planning_context import SprintPlanningInput


class SprintChange(Contract):
    sprint: Annotated[int, Field(ge=1, le=4)]


class StorySprintProposal(Contract):
    proposal_id: Identifier
    action: Literal['update_story_sprint']
    story_id: StoryId
    expected: SprintChange
    changes: SprintChange
    reason: Annotated[NonBlank, Field(max_length=1000)]
    evidence: Annotated[list[Evidence], Field(min_length=1, max_length=10)]

    @model_validator(mode='after')
    def must_change(self):
        if self.expected.sprint == self.changes.sprint:
            raise ValueError('proposal must change sprint')
        return self


class SprintPlanningOutput(Contract):
    schema_version: Literal['1.0'] = '1.0'
    meeting_id: Identifier
    meeting_type: Literal['sprint_planning'] = 'sprint_planning'
    summary: Annotated[NonBlank, Field(max_length=2000)]
    proposed_actions: Annotated[list[StorySprintProposal], Field(max_length=20)]
    open_questions: Annotated[list[Annotated[NonBlank, Field(max_length=500)]], Field(max_length=20)]


def validate_planning_result(context: SprintPlanningInput, payload: dict) -> SprintPlanningOutput:
    """Check snapshot/provenance, not semantic support, approval or live state.

    An explicit transcript can discuss moving a story out of target_sprint, so
    target_sprint is context, not a constraint forcing every destination to it.
    """
    context = SprintPlanningInput.model_validate(context.model_dump(warnings=False))
    result = SprintPlanningOutput.model_validate(payload)
    if result.meeting_id != context.meeting_id:
        raise ResultContractError('meeting mismatch', 'meeting_mismatch', ('meeting_id',))
    stories = {s.id: s for s in context.stories}
    segments = {s.segment_id: s.text for s in context.transcript_segments}
    ids, targets = set(), set()
    for index, proposal in enumerate(result.proposed_actions):
        path = ('proposed_actions', index)
        if proposal.proposal_id in ids:
            raise ResultContractError('duplicate proposal', 'duplicate_proposal_id', path + ('proposal_id',))
        ids.add(proposal.proposal_id)
        if proposal.story_id in targets:
            raise ResultContractError('duplicate story', 'duplicate_story', path + ('story_id',))
        targets.add(proposal.story_id)
        story = stories.get(proposal.story_id)
        if story is None:
            raise ResultContractError('unknown story', 'unknown_story', path + ('story_id',))
        if proposal.expected.sprint != story.sprint:
            raise ResultContractError('snapshot mismatch', 'snapshot_mismatch', path + ('expected', 'sprint'))
        for ei, evidence in enumerate(proposal.evidence):
            text = segments.get(evidence.segment_id)
            if text is None or evidence.quote not in text:
                field = 'segment_id' if text is None else 'quote'
                code = 'unknown_segment' if text is None else 'quote_mismatch'
                raise ResultContractError('invalid evidence', code, path + ('evidence', ei, field))
    return result
