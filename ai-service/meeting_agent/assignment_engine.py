"""Explainable v1 skill matching; capacity unknown never means available."""
from typing import Annotated, Literal
from pydantic import Field, model_validator
from .contracts import Contract, NonBlank, StoryId
from .assignment_context import AssignmentContextRequest, AssignmentContextReport


class SkillRequirement(Contract):
    dimension: Literal['tech_stack', 'capabilities', 'process_domains']
    name: Annotated[NonBlank, Field(max_length=50)]
    minimum_level: Annotated[int, Field(ge=1, le=5)]


class AssignmentRecommendationRequest(AssignmentContextRequest):
    story_ids: Annotated[list[StoryId], Field(min_length=1, max_length=1)]
    requirements: Annotated[list[SkillRequirement], Field(max_length=20)]

    @model_validator(mode='after')
    def unique_requirements(self):
        keys = [(r.dimension, r.name.strip().casefold()) for r in self.requirements]
        if len(keys) != len(set(keys)):
            raise ValueError('duplicate requirement')
        return self


class SkillMatch(Contract):
    requirement: SkillRequirement
    recorded_level: int | None
    meets_requirement: bool


class AssignmentCandidate(Contract):
    member_id: int
    display_name: str
    rank: int
    matched_requirements: int
    total_requirements: int
    matches: list[SkillMatch]
    capacity_check: Literal['unknown'] = 'unknown'
    suitability: Literal['requires_human_review'] = 'requires_human_review'


class AssignmentRecommendations(Contract):
    rule_version: Literal['assignment-skills-v1'] = 'assignment-skills-v1'
    status: Literal['provisional', 'requirements_needed', 'no_eligible_members', 'no_recorded_skill_match']
    requirements_source: Literal['caller_supplied'] = 'caller_supplied'
    context: AssignmentContextReport
    candidates: list[AssignmentCandidate]
    excluded_viewer_ids: list[int]
    writes_performed: Literal[False] = False


def recommend(context: AssignmentContextReport, request: AssignmentRecommendationRequest):
    context = AssignmentContextReport.model_validate(context.model_dump())
    request = AssignmentRecommendationRequest.model_validate(request.model_dump())
    if [s.id for s in context.selected_stories] != request.story_ids or context.target_sprint != request.target_sprint:
        raise ValueError('assignment context mismatch')
    excluded = [m.profile.user_id for m in context.members if m.profile.role == 'viewer']
    if not request.requirements:
        return AssignmentRecommendations(status='requirements_needed', context=context, candidates=[], excluded_viewer_ids=excluded)
    candidates = []
    for workload in context.members:
        member = workload.profile
        if member.role == 'viewer':
            continue
        matches = []
        for requirement in request.requirements:
            levels = {s.name.strip().casefold(): s.level for s in getattr(member, requirement.dimension)}
            level = levels.get(requirement.name.strip().casefold())
            matches.append(SkillMatch(requirement=requirement, recorded_level=level,
                meets_requirement=level is not None and level >= requirement.minimum_level))
        candidates.append(AssignmentCandidate(member_id=member.user_id, display_name=member.display_name,
            rank=1, matched_requirements=sum(m.meets_requirement for m in matches),
            total_requirements=len(matches), matches=matches))
    candidates.sort(key=lambda c: (-c.matched_requirements, c.member_id))
    for i, candidate in enumerate(candidates):
        rank = i + 1 if i == 0 or candidate.matched_requirements != candidates[i-1].matched_requirements else candidates[i-1].rank
        candidates[i] = candidate.model_copy(update={'rank': rank})
    status = 'no_eligible_members' if not candidates else 'no_recorded_skill_match' if not candidates[0].matched_requirements else 'provisional'
    return AssignmentRecommendations(status=status, context=context, candidates=candidates, excluded_viewer_ids=excluded)
