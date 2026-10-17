"""Assignment preparation: authenticated facts and gaps, without a ranking formula."""
from typing import Annotated, Literal
from pydantic import Field, model_validator
from .contracts import Contract, Identifier, StoryId, TranscriptSegment
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
    sprint_available_hours: Annotated[float, Field(ge=0)] | None = None
    sprint_remaining_hours: float | None = None


class AssignmentGap(Contract):
    code: Literal['target_sprint_unknown', 'story_estimates_unavailable',
        'sprint_capacity_unavailable', 'members_empty',
        'profile_dimensions_empty', 'story_owner_unresolved', 'task_owner_unresolved',
        'task_story_unresolved', 'target_sprint_task_schedule_unavailable',
        'task_dependency_pending', 'task_blocked', 'sprint_capacity_overloaded']
    story_ids: list[StoryId] = Field(default_factory=list)
    task_ids: list[Identifier] = Field(default_factory=list)
    member_ids: list[Annotated[int, Field(gt=0)]] = Field(default_factory=list)


class AssignmentContextReport(Contract):
    meeting_id: Identifier
    meeting_evidence: list[TranscriptSegment] = Field(default_factory=list)
    target_sprint: Annotated[int, Field(ge=1, le=4)] | None
    status: Literal['not_ready'] = 'not_ready'
    selected_stories: list[PlanningStorySnapshot]
    story_estimates: None = None
    members: list[MemberWorkload]
    tasks: list[TaskSnapshot]
    # Completed tasks linked to the requested stories are review candidates, not write actions.
    completed_task_ids: list[Identifier] = Field(default_factory=list)
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
    gaps = [AssignmentGap(code='story_estimates_unavailable', story_ids=request.story_ids)]
    if target is None:
        gaps.append(AssignmentGap(code='target_sprint_unknown'))
    if target == 4:
        gaps.append(AssignmentGap(code='target_sprint_task_schedule_unavailable'))
    if target is None or target == 4 or any(m.weekly_capacity_hours is None for m in context.members):
        gaps.append(AssignmentGap(code='sprint_capacity_unavailable', member_ids=sorted(member_ids)))
    if not context.members:
        gaps.append(AssignmentGap(code='members_empty'))
    workloads = []
    for member in context.members:
        owned = [s.id for s in context.stories if s.owner_id == member.user_id]
        active = [t for t in context.tasks if t.owner_id == member.user_id and t.status in (0, 1)]
        available = remaining = None
        if target in (1, 2, 3) and member.weekly_capacity_hours is not None:
            available = round(member.weekly_capacity_hours * 2, 2)
            planned = 0.0
            for task in active:
                overlap = sum((week - 1) // 2 + 1 == target
                              for week in range(task.week_start, task.week_end + 1))
                if not overlap:
                    continue
                estimate = task.estimated_hours or task.hours
                planned += estimate * (1 - task.progress / 100) * overlap / (task.week_end - task.week_start + 1)
            remaining = round(available - planned, 2)
            if remaining < 0:
                gaps.append(AssignmentGap(code='sprint_capacity_overloaded', member_ids=[member.user_id],
                    task_ids=[t.id for t in active if target in t.sprints]))
        workloads.append(MemberWorkload(profile=member, owned_story_ids=owned,
            active_task_ids=[t.id for t in active], target_sprint_task_ids=None if target in (None, 4)
            else [t.id for t in active if target in t.sprints],
            sprint_available_hours=available, sprint_remaining_hours=remaining))
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
        if target in (1, 2, 3) and target in task.sprints and task.status in (0, 1):
            if task.blocked:
                gaps.append(AssignmentGap(code='task_blocked', task_ids=[task.id]))
            predecessors = [code.strip() for code in (task.depends_on or '').split(',') if code.strip()]
            task_by_id = {item.id: item for item in context.tasks}
            if any(task_by_id.get(code) is None or task_by_id[code].status != 2 for code in predecessors):
                gaps.append(AssignmentGap(code='task_dependency_pending', task_ids=[task.id]))
    # A completed task is only a human closure/archival reminder; no task is changed here.
    selected = set(request.story_ids)
    completed = [task.id for task in context.tasks if task.status == 2 and (
        task.kanban_card_id in selected or selected.intersection(
            code.strip() for code in (task.story_ref or '').split(',') if code.strip()))]
    # IDs are associations, not suitability rankings. Keep original input ordering.
    return AssignmentContextReport(meeting_id=context.meeting_id,
        meeting_evidence=context.transcript_segments, target_sprint=target,
        selected_stories=[stories[s] for s in request.story_ids], members=workloads,
        tasks=context.tasks, completed_task_ids=completed, gaps=gaps)
