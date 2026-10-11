"""事件信封：`seq` 的来源、`ts` 的两种形态、以及 SSE 帧的字节形状。

这一层是**纯组装**，但它有两条容易走样、走了就难查的语义：

- 实时帧的 `ts` 取"现在"，重放帧的 `ts` 必须取行里的 `created_at` —— 混了的话
  重连后历史事件全带上重连时刻，前端按 ts 排序会整片乱掉；
- Java 写的是**裸的本地时间**（`LocalDateTime.now()`），不归一成带偏移的 ISO
  字符串，它和实时帧就是两种格式（字符串排序下 `2026-10-11T09:00:00` 与
  `2026-10-11T09:00:00+08:00` 的相对次序毫无意义）。
"""

from __future__ import annotations

import json
from datetime import datetime
from types import SimpleNamespace

import pytest

from app import events
from app.events import EVENT_TYPES, POST_TERMINAL_TYPES, TERMINAL_TYPES
from app.schemas import AgentEventRow


def _row(**overrides: object) -> AgentEventRow:
    base = {
        "id": 7,
        "session_id": "s-1",
        "run_id": "r-1",
        "type": "model.step",
        "detail": {"step": 1},
        "created_at": "2026-10-11T09:30:00",
    }
    base.update(overrides)
    return AgentEventRow(**base)  # type: ignore[arg-type]


# --------------------------------------------------------------------------
# 词汇表
# --------------------------------------------------------------------------


def test_event_types_cover_the_contract_vocabulary():
    """契约 §3.3 的 11 个类型一个不多一个不少 —— 白名单在 Java 那边没有，
    所以这份集合就是**全系统唯一的词汇表**。"""
    assert EVENT_TYPES == {
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


def test_terminal_and_post_terminal_are_disjoint():
    """`agent.report` 是**终局之后**还能到的，不能同时被算作终局类型 ——
    它要是掉进 `TERMINAL_TYPES`，前端就会在收到汇报前先把通道关了。"""
    assert TERMINAL_TYPES == {"run.end", "run.error"}
    assert POST_TERMINAL_TYPES == {"agent.report"}
    assert not (TERMINAL_TYPES & POST_TERMINAL_TYPES)
    assert POST_TERMINAL_TYPES <= EVENT_TYPES


# --------------------------------------------------------------------------
# envelope
# --------------------------------------------------------------------------


def test_envelope_rejects_unknown_type():
    with pytest.raises(ValueError):
        events.envelope(seq=1, run_id="r", session_id="s", type="nope.nope", data={})


def test_envelope_defaults_ts_to_now_with_offset():
    event = events.envelope(seq=1, run_id="r", session_id="s", type="run.end", data={})

    parsed = datetime.fromisoformat(event["ts"])
    assert parsed.tzinfo is not None, "实时帧的 ts 必须带偏移，不能是裸时间"
    assert abs((datetime.now().astimezone() - parsed).total_seconds()) < 5


def test_envelope_uses_the_given_ts_verbatim():
    """重放路径：调用方给了就照用，`envelope` 不自作主张改成"现在"。"""
    event = events.envelope(
        seq=3, run_id="r", session_id="s", type="model.step", data={}, ts="2020-01-01T00:00:00+08:00"
    )

    assert event["ts"] == "2020-01-01T00:00:00+08:00"


def test_envelope_never_invents_a_seq():
    """`seq` 是必填位置参数 —— 没有默认值，就不会有"重启后回退的本地计数"。"""
    with pytest.raises(TypeError):
        events.envelope(run_id="r", session_id="s", type="run.end", data={})  # type: ignore[call-arg]


# --------------------------------------------------------------------------
# envelope_from_row（重放路径的三处映射）
# --------------------------------------------------------------------------


def test_envelope_from_row_maps_id_detail_and_created_at():
    event = events.envelope_from_row(_row())

    assert event["seq"] == 7
    assert event["type"] == "model.step"
    assert event["run_id"] == "r-1"
    assert event["session_id"] == "s-1"
    assert event["data"] == {"step": 1}
    # created_at 是裸本地时间 → 归一成带偏移，且**墙上时间不变**
    parsed = datetime.fromisoformat(event["ts"])
    assert parsed.tzinfo is not None
    assert parsed.strftime("%Y-%m-%dT%H:%M:%S") == "2026-10-11T09:30:00"


def test_envelope_from_row_treats_a_missing_detail_as_empty_object():
    """`detail` 缺省要是 `{}` 而不是 `None`：前端 `data.step` 直接取字段，
    收到 `null` 就得多一层判空。"""
    row = SimpleNamespace(
        id=1, session_id="s", run_id="r", type="run.end", detail=None, created_at=None
    )

    event = events.envelope_from_row(row)

    assert event["data"] == {}
    assert event["ts"] is not None, "脏时间戳退回「现在」也比给 None 强"


def test_from_java_ts_normalises_a_naive_timestamp():
    out = events.from_java_ts("2026-10-11T09:30:00")

    assert out is not None
    assert datetime.fromisoformat(out).tzinfo is not None
    assert out.startswith("2026-10-11T09:30:00")


def test_from_java_ts_returns_none_for_garbage_and_empty():
    assert events.from_java_ts("") is None
    assert events.from_java_ts(None) is None
    assert events.from_java_ts("昨天下午") is None


# --------------------------------------------------------------------------
# sse_frame / heartbeat
# --------------------------------------------------------------------------


def test_sse_frame_has_id_event_data_and_a_blank_terminator():
    frame = events.sse_frame(
        events.envelope(seq=12, run_id="r", session_id="s", type="tool.call", data={"name": "t"})
    )

    lines = frame.split("\n")
    assert lines[0] == "id: 12", "`id:` 就是 seq —— 浏览器靠它带 Last-Event-ID 回来"
    assert lines[1] == "event: tool.call"
    assert lines[2].startswith("data: ")
    assert frame.endswith("\n\n"), "SSE 帧必须以空行收尾，否则事件不会 flush 给客户端"


def test_sse_frame_data_is_the_whole_envelope():
    """`data:` 里是**整个信封**（带 seq/ts），不是只有业务字段 ——
    前端要拿它去更新自己的续传锚点。"""
    event = events.envelope(seq=5, run_id="r", session_id="s", type="run.end", data={"status": "ok"})

    payload = json.loads(events.sse_frame(event).split("data: ", 1)[1].strip())

    assert payload == event


def test_sse_frame_does_not_escape_non_ascii():
    """中文原样出（`ensure_ascii=False`）：转义成 \\uXXXX 白白涨三倍体积。"""
    frame = events.sse_frame(
        events.envelope(seq=1, run_id="r", session_id="s", type="agent.report", data={"摘要": "已完成"})
    )

    assert "已完成" in frame


def test_heartbeat_is_a_comment_frame():
    assert events.heartbeat() == ": ping\n\n"
