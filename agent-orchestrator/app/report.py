"""复盘报告：fan-out 取片段 → **确定性拼装**。**实现属 S8′**。

硬约束（v1.1 §2.1，六条）：碎片结构化 + 模板拼装（**不让模型重写一遍**）、
区分「数据支持」与「数据不足」、部分失败出「缺一块」的报告、时间口径来自
SQL 聚合（`GET /api/agents/reports/project-facts`，该端点**当前尚不存在**，
属 S8′ 前置）、provenance 抬头、触发权 ≠ 事实权。

> 80% 的复盘是 SQL 聚合，不是写文档 —— 这是红线④「结构化条件走 SQL」的直接落地。
"""

from __future__ import annotations

from typing import Any

# 复盘 fan-out 的并发上限（契约 §8 待拍板 #5 的建议值：避免撞上游限流）
MAX_CONCURRENT_FANOUT = 2


async def build_report(**kwargs: Any) -> None:
    """fan-out 各子智能体产出 → 模板拼装成报告。"""
    raise NotImplementedError("复盘报告属 S8′")
