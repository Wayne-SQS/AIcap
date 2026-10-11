"""SSE 事件信封（契约 §3.2 / §3.3）。

**本模块不生成 `seq`。** `seq` 由 Java 侧 `agent_events.id`（自增主键）提供 ——
「事件先落库拿到自增 id，再推给前端」。用 Python 进程内计数会在重启后回退，
续传就错位（契约 §3.2 明确要求）。

信封只做组装，不碰网络、不碰库。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

# 契约 §3.3 的事件类型。七个直接来自归档 runner.py 的 emit（改名对齐）。
EVENT_TYPES = frozenset(
    {
        "session.start",
        "intent.routed",
        "clarify.request",
        "agent.dispatched",
        "tool.call",
        "tool.result",
        "model.step",
        "agent.report",
        "artifact.ready",
        "run.end",
        "run.error",
    }
)

# 终局事件：run.end / run.error 之后不再发（契约 §3.4 第 3 条）。
TERMINAL_TYPES = frozenset({"run.end", "run.error"})

# 但 agent.report 允许在 run.end **之后**到达（异步汇报，契约 §3.4 第 4 条）
# —— 前端不能因为 run 结束就关闭事件通道。
POST_TERMINAL_TYPES = frozenset({"agent.report"})


def _now() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def from_java_ts(value: str | None) -> str | None:
    """把 Java 的 `LocalDateTime.toString()` 归一成带时区偏移的 ISO 字符串。

    Java 那边写的是 `LocalDateTime.now()` —— **裸的本地时间，不带偏移**；
    实时帧（本模块 `_now()`）带 `+08:00`。不归一的话重放出来的历史帧和实时帧
    是两种格式，前端按 `ts` 做字符串排序会把它们乱序。
    """
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        # 裸时间按本机时区解释（Java 与编排层同机部署，这就是它的语义）
        parsed = parsed.astimezone()
    return parsed.isoformat(timespec="seconds")


def envelope(
    *,
    seq: int,
    run_id: str,
    session_id: str,
    type: str,
    data: dict[str, Any],
    ts: str | None = None,
) -> dict[str, Any]:
    """组装一条事件信封。

    `seq` 必须由调用方从 Java 侧拿到，本函数不做任何计数或兜底生成 ——
    宁可让调用方显式传，也不给一个会在重启后回退的默认值。

    `ts` 是**重放路径**的出口：实时帧不传（取当前时刻），重放帧必须传行里的
    `created_at`，否则历史事件全带上"重连那一刻"的时间戳。
    """
    if type not in EVENT_TYPES:
        raise ValueError(f"未知事件类型：{type}")
    return {
        "seq": seq,
        "run_id": run_id,
        "session_id": session_id,
        "ts": ts or _now(),
        "type": type,
        "data": data,
    }


def envelope_from_row(row: Any) -> dict[str, Any]:
    """把 Java 侧 `agent_events` 的一行还原成信封（`GET /chat/stream` 重放路径）。

    三处映射一个都不能省：`id → seq`（续传锚点）、`detail → data`、
    `created_at → ts`（时间戳必须是事件**发生**的时刻，不是重放时刻）。
    """
    return envelope(
        seq=row.id,
        run_id=row.run_id,
        session_id=row.session_id,
        type=row.type,
        data=row.detail or {},
        ts=from_java_ts(row.created_at),
    )


def sse_frame(event: dict[str, Any]) -> str:
    """把信封渲染成一条 SSE 帧（`id:` / `event:` / `data:`）。

    `id:` 就是 `seq`，供浏览器重连时以 `Last-Event-ID` 续传（契约 §3.1）。
    """
    import json

    return (
        f"id: {event['seq']}\n"
        f"event: {event['type']}\n"
        f"data: {json.dumps(event, ensure_ascii=False, separators=(',', ':'))}\n\n"
    )


def heartbeat() -> str:
    """15s 一条注释行，防反代超时断流（契约 §3.1）。"""
    return ": ping\n\n"
