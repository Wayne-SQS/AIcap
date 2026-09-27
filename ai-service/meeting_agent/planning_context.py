"""Planning input: authorized meeting data plus story/member/task reads.

No Planning inference, capacity calculation or write actions are performed here.
Unsupported data is explicitly null, not an empty snapshot or a computed zero.
"""
from typing import Annotated, Literal, Protocol

from pydantic import Field, model_validator

from .contracts import Contract, Identifier, TranscriptSegment
from .story_tool import PlanningStorySnapshot
from .member_tool import MemberProfileSnapshot
from .task_tool import TaskSnapshot


class SprintPlanningInput(Contract):
    schema_version: Literal['1.0'] = '1.0'
    meeting_id: Identifier
    meeting_type: Literal['sprint_planning'] = 'sprint_planning'
    # Caller-provided scope, not inferred from stories or a local calendar.
    current_sprint: Annotated[int, Field(ge=1, le=4)] | None = None
    target_sprint: Annotated[int, Field(ge=1, le=4)] | None = None
    transcript_segments: Annotated[list[TranscriptSegment], Field(min_length=1, max_length=100)]
    stories: Annotated[list[PlanningStorySnapshot], Field(max_length=1000)]
    members: Annotated[list[MemberProfileSnapshot], Field(max_length=1000)]
    tasks: Annotated[list[TaskSnapshot], Field(max_length=1000)]
    # These sources are not implemented in this input slice. Reject invented values.
    sprint_goal: None = None
    sprint_dates: None = None
    story_estimates: None = None
    sprint_remaining_capacity: None = None

    @model_validator(mode='after')
    def unique_context(self):
        for label, ids in (
            ('segment', [s.segment_id for s in self.transcript_segments]),
            ('story', [s.id for s in self.stories]),
            ('member', [m.user_id for m in self.members]),
            ('task', [t.id for t in self.tasks]),
        ):
            if len(ids) != len(set(ids)):
                raise ValueError(f'duplicate {label}')
        if sum(len(s.text) for s in self.transcript_segments) > 16000:
            raise ValueError('transcript exceeds 16000 characters')
        # Missing owners are retained as unresolved references, not guessed/reassigned.
        return self


class StoryReader(Protocol):
    def get_planning_stories(self, *, access_token: str) -> list[PlanningStorySnapshot]: ...


class MemberReader(Protocol):
    def get_member_profiles(self, *, access_token: str) -> list[MemberProfileSnapshot]: ...


class PlanningContextError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class TaskReader(Protocol):
    def get_tasks(self, *, access_token: str) -> list[TaskSnapshot]: ...


class PlanningContextReader:
    def __init__(self, stories: StoryReader, members: MemberReader, tasks: TaskReader):
        self._stories = stories
        self._members = members
        self._tasks = tasks

    def load(self, *, meeting_id: str, transcript: str, access_token: str,
             current_sprint: int | None = None, target_sprint: int | None = None) -> SprintPlanningInput:
        """Caller must authorize/read the meeting first; no auth token enters output.

        Three consecutive Java reads are not an atomic database snapshot. A later
        approved write must recheck live values, as with the existing Daily flow.
        Tool failures propagate without returning partial or fabricated context.
        """
        try:
            if not isinstance(transcript, str) or not transcript.strip() or len(transcript) > 16000:
                raise ValueError('invalid transcript')
            context = SprintPlanningInput.model_validate({
                'meeting_id': meeting_id, 'current_sprint': current_sprint, 'target_sprint': target_sprint,
                'transcript_segments': [
                    {'segment_id': f'S{i}', 'text': line}
                    for i, line in enumerate(transcript.splitlines(), 1) if line.strip()
                ], 'stories': [], 'members': [], 'tasks': [],
            })
        except ValueError:
            raise PlanningContextError('invalid_planning_input') from None
        stories = self._stories.get_planning_stories(access_token=access_token)
        members = self._members.get_member_profiles(access_token=access_token)
        tasks = self._tasks.get_tasks(access_token=access_token)
        data = context.model_dump()
        try:
            # Revalidate nested model content instead of trusting mutable list members.
            data['stories'] = [s.model_dump(warnings=False) for s in stories]
            data['members'] = [m.model_dump(warnings=False) for m in members]
            data['tasks'] = [t.model_dump(warnings=False) for t in tasks]
            return SprintPlanningInput.model_validate(data)
        except (ValueError, TypeError, AttributeError):
            raise PlanningContextError('invalid_planning_context') from None
