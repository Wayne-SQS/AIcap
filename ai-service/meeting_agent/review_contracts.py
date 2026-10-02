"""Sprint Review completion candidates; no independent acceptance record or writes."""
from typing import Annotated, Literal

from pydantic import Field

from .contracts import (Contract, DailyScrumInput, DailyScrumOutput,
                        StoryStatusProposal, validate_status_provenance)
from .story_tool import ReviewStorySnapshot


class SprintReviewInput(DailyScrumInput):
    meeting_type: Literal['sprint_review'] = 'sprint_review'
    stories: Annotated[list[ReviewStorySnapshot], Field(max_length=1000)]
    # No authoritative source exists for these. Never infer from Story.status.
    acceptance_results: None = None
    sprint_goal: None = None


class CompletionChange(Contract):
    status: Annotated[int, Field(ge=2, le=2)]


class ReviewCompletionProposal(StoryStatusProposal):
    changes: CompletionChange


class SprintReviewOutput(DailyScrumOutput):
    meeting_type: Literal['sprint_review'] = 'sprint_review'
    proposed_actions: Annotated[list[ReviewCompletionProposal], Field(max_length=20)]


def validate_review_result(context: SprintReviewInput, payload: dict) -> SprintReviewOutput:
    context = SprintReviewInput.model_validate(context.model_dump())
    result = SprintReviewOutput.model_validate(payload)
    validate_status_provenance(context, result)
    return result
