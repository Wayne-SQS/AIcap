"""运行时证据校验：三条检查的唯一实现处。

三条检查的判据其实是同一条 —— **模型说的每句话，都要能在会议原文的字面里站得住**：

* ``invalid_evidence``：证据引用必须逐字出现在它所引用的片段里
  （细分 ``unknown_segment`` / ``quote_mismatch``）；
* ``invented_assignment``：负责人与日期必须出现在该条目自己引用的原文里
  （细分 ``unresolved_owner`` / ``deadline_not_quoted``）；
* ``incomplete_analysis``：每个会议片段都必须被至少一条证据引用。

为什么收拢到一处：这三条原先散在三个地方 —— 引用核对在 ``contracts.py``、
负责人/日期核对**只有** retro 链路有、片段覆盖率随已退役的
``backend/app/meeting_agent/runner.py`` 一起被删掉（P0 缺口 #1：静默降级，
测试不会变红）。分散的后果是"改一条漏两条"：契约文档把它们当一组，
代码里却是三份互不知情的实现。

为什么不放在 ``contracts.py``：那个模块是"输入/输出的数据结构 + 结构性校验"，
而本模块是"模型产出 vs 会议原文"的**运行时**核对 —— 前者不需要知道片段文本，
后者全部围绕片段文本。
"""

from __future__ import annotations

import os


class ResultContractError(ValueError):
    """Static diagnostic metadata, with no model-provided values.

    ``code`` 是给调用方分类用的稳定枚举；``path`` 指出出错的字段，
    两者都**不含**模型给的任何文本 —— 诊断信息不该把模型输出回显出去。
    """

    def __init__(self, message: str, code: str, path: tuple):
        super().__init__(message)
        self.code = code
        self.path = path


def require_quoted_evidence(evidence_items, segments, path) -> None:
    """invalid_evidence：每条证据的 quote 必须逐字出现在它引用的 segment 里。

    ``path`` 指向携带该证据的条目；出错的索引与字段名会追加在后面。
    """
    for index, evidence in enumerate(evidence_items):
        text = segments.get(evidence.segment_id)
        if text is None or evidence.quote not in text:
            field = "segment_id" if text is None else "quote"
            code = "unknown_segment" if text is None else "quote_mismatch"
            raise ResultContractError("evidence must quote the referenced segment exactly",
                                      code, path + ("evidence", index, field))


def require_grounded_assignment(evidence_items, path, *, owner_display_name=None,
                                deadline_text=None, owner_code="unresolved_owner",
                                deadline_code="deadline_not_quoted") -> None:
    """invented_assignment：负责人与日期必须是被引用的原文里出现过的字面片段。

    为什么只做"字面出现"而不是"语义一致"：**没有可靠的说话人 → 成员映射**，
    模型完全可以把"这件事应该归谁"的推断写成 ``owner_id``。唯一能机械核对的，
    是这条提案自己引用的原文里有没有这个人的名字、有没有这句时间原话 ——
    查不到就说明这个值是从会议外来的，也就是编造的。

    ``owner_id 必须是已知成员``这类检查不在这里：它核对的是快照而不是原文，
    属于调用方的数据校验（retro 链路在调用本函数前自己判）。
    """
    quotes = [e.quote for e in evidence_items]
    if owner_display_name is not None and not any(owner_display_name in q for q in quotes):
        raise ResultContractError("owner is not present in the cited original text",
                                  owner_code, path + ("changes", "owner_id"))
    if deadline_text is not None and not any(deadline_text in q for q in quotes):
        raise ResultContractError("deadline is not present in the cited original text",
                                  deadline_code, path + ("changes", "deadline_text"))


def segment_coverage_enabled() -> bool:
    """incomplete_analysis 是否启用（默认关，理由见下）。

    环境变量 ``AICAP_REQUIRE_SEGMENT_COVERAGE=1`` 打开。
    """
    return os.getenv("AICAP_REQUIRE_SEGMENT_COVERAGE", "").strip().lower() in ("1", "true", "yes")


def require_segment_coverage(segments, evidence_items, path=()) -> None:
    """incomplete_analysis：每个片段都要被至少一条证据引用。

    **默认不启用**（``segment_coverage_enabled()``），这不是偷懒，是当前输出
    schema 的硬约束：五条链路的产出都是"提案/决策**候选**"，一个片段"读完了但
    没有产出提案"是最正常的结果 —— 而 ``open_questions`` / ``summary`` 都是自由文本、
    **没有 evidence 字段**，模型没有任何位置可以为"这段我看过了"提供证据。
    强制全覆盖的实际效果是：合法输出被判失败 → 触发重试 → 重试也无法满足 → 整轮跑挂。

    老 runner（``backend/app/meeting_agent/runner.py``）之所以能强制，是因为它的
    ``Analysis`` 里还有 ``source_notes`` / ``risks`` / ``coordination_items`` 这些
    兜底条目专门承载"这段没有动作但有信息"。**要让这条默认打开，先补这类
    带证据的兜底条目**，否则它只会把正确结果判成错误。

    实现保持完整并可测：给了兜底条目之后，把开关翻过来即可，无需再写一遍。
    """
    if not segment_coverage_enabled():
        return
    covered = {e.segment_id for e in evidence_items}
    missing = [sid for sid in segments if sid not in covered]
    if missing:
        raise ResultContractError("transcript segments were not analyzed: " + ", ".join(missing),
                                  "incomplete_analysis", path + ("evidence",))
