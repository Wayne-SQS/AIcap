"""Refinement new-story candidates, never executable without human completion/review."""
from typing import Annotated, Literal
from pydantic import Field
from .contracts import Contract, DailyScrumInput, Evidence, Identifier, NonBlank, ResultContractError
from .story_tool import PlanningStorySnapshot


class BacklogRefinementInput(DailyScrumInput):
    meeting_type: Literal['backlog_refinement'] = 'backlog_refinement'
    stories: Annotated[list[PlanningStorySnapshot], Field(max_length=1000)]


class NewStoryChanges(Contract):
    title: Annotated[NonBlank, Field(max_length=200)]
    description: Annotated[NonBlank, Field(max_length=4000)] | None
    acceptance: Annotated[NonBlank, Field(max_length=4000)] | None
    priority: Literal['Must', 'Should', 'Could'] | None
    sprint: Annotated[int, Field(ge=1, le=4)] | None
    activity: Annotated[int, Field(ge=1, le=5)] | None


class NewStoryProposal(Contract):
    proposal_id: Identifier
    action: Literal['create_story']
    changes: NewStoryChanges
    reason: Annotated[NonBlank, Field(max_length=1000)]
    evidence: Annotated[list[Evidence], Field(min_length=1, max_length=10)]


class BacklogRefinementOutput(Contract):
    schema_version: Literal['1.0'] = '1.0'
    meeting_id: Identifier
    meeting_type: Literal['backlog_refinement'] = 'backlog_refinement'
    summary: Annotated[NonBlank, Field(max_length=2000)]
    proposed_actions: Annotated[list[NewStoryProposal], Field(max_length=20)]
    open_questions: Annotated[list[Annotated[NonBlank, Field(max_length=500)]], Field(max_length=20)]


def validate_refinement_result(context: BacklogRefinementInput, payload: dict) -> BacklogRefinementOutput:
    context = BacklogRefinementInput.model_validate(context.model_dump())
    result = BacklogRefinementOutput.model_validate(payload)
    if result.meeting_id != context.meeting_id:
        raise ResultContractError('meeting mismatch', 'meeting_mismatch', ('meeting_id',))
    segments = {s.segment_id: s.text for s in context.transcript_segments}
    titles = {s.title.strip().casefold() for s in context.stories}
    proposed_titles, ids = set(), set()
    for index, proposal in enumerate(result.proposed_actions):
        path = ('proposed_actions', index)
        if proposal.proposal_id in ids:
            raise ResultContractError('duplicate proposal', 'duplicate_proposal_id', path + ('proposal_id',))
        ids.add(proposal.proposal_id)
        title = proposal.changes.title.strip().casefold()
        if title in titles or title in proposed_titles:
            raise ResultContractError('duplicate story title', 'duplicate_story_title', path + ('changes', 'title'))
        proposed_titles.add(title)
        for ei, evidence in enumerate(proposal.evidence):
            if evidence.segment_id not in segments or evidence.quote not in segments[evidence.segment_id]:
                raise ResultContractError('invalid evidence', 'invalid_evidence', path + ('evidence', ei))
    # Exact-title checks are conservative, not semantic deduplication or approval.
    return result
