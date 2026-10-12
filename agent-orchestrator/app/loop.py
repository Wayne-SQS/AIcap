"""有界工具循环。**实现属 S4′.2** —— 从归档 `backend/app/meeting_agent/runner.py`
复活，剥掉 `session_factory`、改为经 `java_client` 调平台工具。

剥离点已核实（`backend/app/meeting_agent/runner.py`）：直连库的只有两处
`execute_tool(...)` 调用 —— L49（取 context）与 L78（每轮工具调用）。其余部分
（`segments_for` L12-16 / `validate_analysis` L19-39 / 循环骨架 L51-109）完全不碰 DB。

本模块只落阈值常量，让 S4′.2 有唯一出处。
"""

from __future__ import annotations

from typing import Any

# 四道闸的阈值（沿用归档 backend/app/config.py:17-20 的取值）。
MAX_STEPS = 6
MAX_SECONDS = 180
MAX_TOOL_CALLS = 24
MAX_CALLS_PER_TURN = 8


async def run(**kwargs: Any) -> None:
    """有界循环：模型单步 → 工具调用 → 校验 → 直到收敛或撞闸。"""
    raise NotImplementedError("有界循环复活属 S4′.2")
