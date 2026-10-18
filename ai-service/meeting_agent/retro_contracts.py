"""Retrospective action-item candidates. No task, story or action-item writes."""
from typing import Annotated, Literal
from pydantic import Field, model_validator

from .contracts import (Contract, Evidence, Identifier, NonBlank, ResultContractError, TranscriptSegment,
                        require_quoted_evidence, require_segment_coverage)
from .evidence_checks import require_grounded_assignment


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


def _without_unverifiable_owners(context: SprintRetroInput, payload: dict) -> dict:
    """在构造结果之前，把**核实不了的负责人**从提案里摘掉（2026-10-15 降级口径）。

    为什么必须在构造前做:``SprintRetroOutput`` 是 pydantic 模型，构造后再改会被
    ``validate_candidate`` 的重校验判成"模型产出非法"（实测报 ``invalid_model_proposal``），
    而且重校验比的是原始 payload、改了也无法自洽。所以先归一化字面量，再交给模型解析。

    "核实不了"有两种，**都走降级**（原来的做法是整场分析以 422 收场，连 summary 与
    decisions 一起丢 —— 真机实测同一场会同一入参时好时坏，5 次里 3 次 422）：

    1. ``owner_id`` 指向的成员存在，但它的 display_name 在名册里**出现不止一次** ——
       "到底是谁"核不出来；
    2. 名字唯一，但**没有出现在该提案自己的引文里** —— 判不出这个人是不是这段原文说的
       那个人（``evidence_checks.require_grounded_assignment`` 抛的就是这个，
       错误码默认也叫 ``unresolved_owner``）。

    仍然硬失败的是 ``unknown_owner``：``owner_id`` 在名册里根本不存在 —— 那是真编造，
    摘掉负责人也掩盖不了，交给校验层如实拒。

    判据只依赖"名册 + 该条自己的引文"，不碰模型给的其它字段，因此是纯归一化、可重放。
    """
    members = context.members
    counters: dict[str, int] = {}
    for member in members:
        counters[member.display_name] = counters.get(member.display_name, 0) + 1
    by_id = {member.user_id: member for member in members}

    actions = payload.get('proposed_actions')
    if not isinstance(actions, list):
        return payload
    normalized = []
    for item in actions:
        if not isinstance(item, dict):
            normalized.append(item)
            continue
        changes = item.get('changes')
        owner_id = changes.get('owner_id') if isinstance(changes, dict) else None
        member = by_id.get(owner_id) if isinstance(owner_id, int) and not isinstance(owner_id, bool) else None
        unverifiable = False
        if member is not None:
            # 名字在名册里不唯一 → 无论引文怎么写都判不出"是哪一位"，一律置空
            if counters.get(member.display_name, 0) != 1:
                unverifiable = True
            else:
                quotes = [e.get('quote', '') for e in (item.get('evidence') or [])
                          if isinstance(e, dict)]
                if not any(member.display_name in q for q in quotes if isinstance(q, str)):
                    unverifiable = True
        if unverifiable:
            item = {**item, 'changes': {**changes, 'owner_id': None}}
        normalized.append(item)
    return {**payload, 'proposed_actions': normalized}


def validate_retro_result(context: SprintRetroInput, payload: dict) -> SprintRetroOutput:
    context = SprintRetroInput.model_validate(context.model_dump())
    result = SprintRetroOutput.model_validate(_without_unverifiable_owners(context, payload))
    if result.meeting_id != context.meeting_id:
        raise ResultContractError('meeting mismatch', 'meeting_mismatch', ('meeting_id',))
    segments = {s.segment_id: s.text for s in context.transcript_segments}
    members = {m.user_id: m for m in context.members}
    ids = set()
    contents = set()
    cited = []
    for group, items in (('decisions', result.decisions), ('proposed_actions', result.proposed_actions)):
        for index, item in enumerate(items):
            path = (group, index)
            require_quoted_evidence(item.evidence, segments, path)
            cited.extend(item.evidence)
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
            owner = members.get(item.changes.owner_id) if item.changes.owner_id is not None else None
            if item.changes.owner_id is not None and owner is None:
                raise ResultContractError('unknown owner', 'unknown_owner', path + ('changes', 'owner_id'))
            # 到这里 owner 要么为空、要么唯一可解析：重名已在 `_without_ambiguous_owners` 里摘掉，
            # 所以这一层只核对"唯一解析出的那个人，名字必须出现在引文里"。
            require_grounded_assignment(item.evidence, path,
                                        owner_display_name=None if owner is None else owner.display_name,
                                        deadline_text=item.changes.deadline_text)
    require_segment_coverage(segments, cited)
    # Quotes establish provenance, not whether a suggestion was agreed or assigned.
    return result
