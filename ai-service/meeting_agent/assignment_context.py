"""Assignment preparation: authenticated facts and gaps, without a ranking formula."""
from typing import Annotated, Literal
from pydantic import Field, model_validator
from .contracts import Contract, Identifier, StoryId
from .member_tool import MemberProfileSnapshot
from .story_tool import PlanningStorySnapshot
from .task_tool import TaskSnapshot
from .planning_context import SprintPlanningInput


class AssignmentContextRequest(Contract):
    story_ids: Annotated[list[StoryId], Field(min_length=1, max_length=100)]
    target_sprint: Annotated[int, Field(ge=1, le=4)] | None

    @model_validator(mode='after')
    def unique_targets(self):
        if len(self.story_ids) != len(set(self.story_ids)):
            raise ValueError('duplicate story target')
        return self


class MemberWorkload(Contract):
    profile: MemberProfileSnapshot
    owned_story_ids: list[StoryId]
    active_task_ids: list[Identifier]
    # Includes a task overlapping the target Sprint; not prorated work or capacity.
    target_sprint_task_ids: list[Identifier] | None
    sprint_available_hours: None = None
    sprint_remaining_hours: None = None


class AssignmentGap(Contract):
    code: Literal['target_sprint_unknown', 'story_estimates_unavailable',
        'sprint_capacity_unavailable', 'members_empty',
        'profile_dimensions_empty', 'story_owner_unresolved', 'task_owner_unresolved',
        'task_story_unresolved', 'target_sprint_task_schedule_unavailable']
    story_ids: list[StoryId] = Field(default_factory=list)
    task_ids: list[Identifier] = Field(default_factory=list)
    member_ids: list[Annotated[int, Field(gt=0)]] = Field(default_factory=list)


class AssignmentContextReport(Contract):
    meeting_id: Identifier
    target_sprint: Annotated[int, Field(ge=1, le=4)] | None
    status: Literal['not_ready'] = 'not_ready'
    selected_stories: list[PlanningStorySnapshot]
    story_estimates: None = None
    members: list[MemberWorkload]
    tasks: list[TaskSnapshot]
    gaps: list[AssignmentGap]
    snapshot_consistency: Literal['sequential_reads'] = 'sequential_reads'
    scope: Literal['read_only_preparation'] = 'read_only_preparation'


class AssignmentContextError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def prepare_assignment(context: SprintPlanningInput, request: AssignmentContextRequest):
    # Revalidate nested mutable data; no caller-controlled profile or estimate bypass.
    context = SprintPlanningInput.model_validate(context.model_dump())
    request = AssignmentContextRequest.model_validate(request.model_dump())
    stories = {s.id: s for s in context.stories}
    member_ids = {m.user_id for m in context.members}
    if any(s not in stories for s in request.story_ids):
        raise AssignmentContextError('assignment_story_not_found')
    target = request.target_sprint
    gaps = [AssignmentGap(code='story_estimates_unavailable', story_ids=request.story_ids),
            AssignmentGap(code='sprint_capacity_unavailable', member_ids=sorted(member_ids))]
    if target is None:
        gaps.append(AssignmentGap(code='target_sprint_unknown'))
    if target == 4:
        gaps.append(AssignmentGap(code='target_sprint_task_schedule_unavailable'))
    if not context.members:
        gaps.append(AssignmentGap(code='members_empty'))
    workloads = []
    for member in context.members:
        owned = [s.id for s in context.stories if s.owner_id == member.user_id]
        active = [t for t in context.tasks if t.owner_id == member.user_id and t.status in (0, 1)]
        workloads.append(MemberWorkload(profile=member, owned_story_ids=owned,
            active_task_ids=[t.id for t in active], target_sprint_task_ids=None if target in (None, 4)
            else [t.id for t in active if target in t.sprints]))
        if not member.tech_stack or not member.capabilities or not member.process_domains:
            gaps.append(AssignmentGap(code='profile_dimensions_empty', member_ids=[member.user_id]))
    for story in context.stories:
        if story.owner_id is not None and story.owner_id not in member_ids:
            gaps.append(AssignmentGap(code='story_owner_unresolved', story_ids=[story.id], member_ids=[story.owner_id]))
    for task in context.tasks:
        if task.owner_id is not None and task.owner_id not in member_ids:
            gaps.append(AssignmentGap(code='task_owner_unresolved', task_ids=[task.id], member_ids=[task.owner_id]))
        if task.kanban_card_id is not None and task.kanban_card_id not in stories:
            gaps.append(AssignmentGap(code='task_story_unresolved', task_ids=[task.id]))
    # IDs are associations, not suitability rankings. Keep original input ordering.
    return AssignmentContextReport(meeting_id=context.meeting_id, target_sprint=target,
        selected_stories=[stories[s] for s in request.story_ids], members=workloads,
        tasks=context.tasks, gaps=gaps)
