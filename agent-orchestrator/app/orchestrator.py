"""主管智能体：只 `plan()`，不 `execute()`（契约 §5.4 / v1.1 §4 冲突 #1）。

执行权在编排层，这个边界要同时体现在 system prompt 和代码结构里 ——
所以本模块**只有 `plan()`，没有 `execute()`**。

S4′.1 只固定契约形态；实现属 S5′。
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

# 反问阈值：可配置，**不写死在 prompt 里**（契约 §5.4）。
CLARIFY_CONFIDENCE = 0.6


class Plan(BaseModel):
    """`plan()` 的返回（契约 §5.4）。

    派活时 `route` 有值、`clarify` 为 `None`；反问时反过来。
    """

    intent: str
    route: str | None = None
    confidence: float = 0.0
    reason: str = ""
    args: dict[str, Any] = Field(default_factory=dict)
    clarify: dict[str, Any] | None = None


async def plan(utterance: str, user_jwt: str) -> Plan:
    """把一句自然语言路由成 `Plan`。

    判定规则（契约 §5.4）：`confidence < CLARIFY_CONFIDENCE` 或必填参数缺失
    且无法从会话上下文补 → 反问；命中"写库/开启采集"类动作 → 不反问，直接走
    审批队列并告知用户；其余 → 派活。

    `user_jwt` 用于拉取该调用者可见的工具清单 —— 权限永远来自 JWT。
    """
    raise NotImplementedError("plan() 属 S5′，本步只固定契约形态")
