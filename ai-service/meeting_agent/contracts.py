"""Daily Scrum status-proposal boundary. No network, persistence or write tools."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator


NonBlank = Annotated[str, StringConstraints(min_length=1, pattern=r"\S")]
Identifier = Annotated[NonBlank, Field(max_length=80)]
StoryId = Annotated[str, StringConstraints(pattern=r"^US[0-9]+$", max_length=80)]
StoryStatus = Annotated[int, Field(ge=0, le=2)]
MeetingType = Literal[
    "sprint_planning", "daily_scrum", "sprint_review",
    "sprint_retrospective", "backlog_refinement", "other",
]


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


class TranscriptSegment(Contract):
    segment_id: Identifier
    text: Annotated[NonBlank, Field(max_length=16000)]


class StorySnapshot(Contract):
    """Read projection from GET /api/stories; status keeps the existing 0/1/2 encoding."""

    id: StoryId
    title: Annotated[NonBlank, Field(max_length=200)]
    status: StoryStatus
    sprint: Annotated[int, Field(ge=1, le=4)]
    owner_id: Annotated[int, Field(gt=0)] | None


class DailyScrumInput(Contract):
    schema_version: Literal["1.0"] = "1.0"
    meeting_id: Identifier
    meeting_type: Literal["daily_scrum"] = "daily_scrum"
    # Explicitly supplied by the caller; there is no current-Sprint API yet.
    current_sprint: Annotated[int, Field(ge=1, le=4)] | None = None
    transcript_segments: Annotated[list[TranscriptSegment], Field(min_length=1, max_length=100)]
    stories: Annotated[list[StorySnapshot], Field(max_length=1000)]

    @model_validator(mode="after")
    def unique_context(self):
        for label, ids in (
            ("segment_id", [s.segment_id for s in self.transcript_segments]),
            ("story id", [s.id for s in self.stories]),
        ):
            if len(ids) != len(set(ids)):
                raise ValueError(f"duplicate {label}")
        if sum(len(s.text) for s in self.transcript_segments) > 16000:
            raise ValueError("transcript exceeds the existing 16000-character meeting limit")
        return self


class Evidence(Contract):
    segment_id: Identifier
    # Preserve whitespace exactly: this must be a continuous original quote.
    quote: Annotated[NonBlank, Field(max_length=2000)]


class StatusChange(Contract):
    status: StoryStatus


class StoryStatusProposal(Contract):
    proposal_id: Identifier
    action: Literal["update_story_status"]
    story_id: StoryId
    expected: StatusChange
    changes: StatusChange
    reason: Annotated[NonBlank, Field(max_length=1000)]
    evidence: Annotated[list[Evidence], Field(min_length=1, max_length=10)]

    @model_validator(mode="after")
    def status_must_change(self):
        if self.expected.status == self.changes.status:
            raise ValueError("proposal must change status")
        return self


class DailyScrumOutput(Contract):
    schema_version: Literal["1.0"] = "1.0"
    meeting_id: Identifier
    meeting_type: Literal["daily_scrum"] = "daily_scrum"
    summary: Annotated[NonBlank, Field(max_length=2000)]
    proposed_actions: Annotated[list[StoryStatusProposal], Field(max_length=20)]
    open_questions: Annotated[list[Annotated[NonBlank, Field(max_length=500)]], Field(max_length=20)]


class ResultContractError(ValueError):
    """Static diagnostic metadata, with no model-provided values."""

    def __init__(self, message: str, code: str, path: tuple):
        super().__init__(message)
        self.code = code
        self.path = path


def validate_daily_result(context: DailyScrumInput, payload: dict) -> DailyScrumOutput:
    """Validate model output against the supplied snapshot, never authorize execution.

    The future Java approval transaction must re-read live state. Evidence matching
    proves provenance only; whether a quote supports completion needs Skill/HITL review.
    """
    # Revalidate even model instances: nested lists are not deeply immutable.
    context = DailyScrumInput.model_validate(context.model_dump())
    result = DailyScrumOutput.model_validate(payload)
    validate_status_provenance(context, result)
    return result


def validate_status_provenance(context, result) -> None:
    """Shared snapshot/evidence checks after each meeting contract is validated."""
    if result.meeting_id != context.meeting_id:
        raise ResultContractError("meeting_id does not match input", "meeting_mismatch", ("meeting_id",))
    segments = {s.segment_id: s.text for s in context.transcript_segments}
    stories = {s.id: s for s in context.stories}
    proposal_ids, targets = set(), set()
    for index, proposal in enumerate(result.proposed_actions):
        path = ("proposed_actions", index)
        if proposal.proposal_id in proposal_ids:
            raise ResultContractError("duplicate proposal_id", "duplicate_proposal_id", path + ("proposal_id",))
        proposal_ids.add(proposal.proposal_id)
        if proposal.story_id in targets:
            raise ResultContractError("multiple status proposals for one story require clarification", "duplicate_story", path + ("story_id",))
        targets.add(proposal.story_id)
        story = stories.get(proposal.story_id)
        if story is None:
            raise ResultContractError("story is absent from project context", "unknown_story", path + ("story_id",))
        if proposal.expected.status != story.status:
            raise ResultContractError("expected status does not match project snapshot", "snapshot_mismatch", path + ("expected", "status"))
        for evidence_index, evidence in enumerate(proposal.evidence):
            text = segments.get(evidence.segment_id)
            if text is None or evidence.quote not in text:
                field = "segment_id" if text is None else "quote"
                code = "unknown_segment" if text is None else "quote_mismatch"
                raise ResultContractError("evidence must quote the referenced segment exactly", code, path + ("evidence", evidence_index, field))
