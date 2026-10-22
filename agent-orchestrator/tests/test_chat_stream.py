"""`GET /chat/stream` —— 重放、续传、心跳，以及**核心：进程重启后接着跑**。

S4′.2 的验收口径是「kill 进程重启，未完成的 run 被**接着跑完**」（不是只补发已落库
事件）。这里用一个共享 store、两个 app 实例把这件事摆出来：

- "崩之前"：往假平台的 store 里直接摆出「崩在 step-2」的现场（事件 1..5、一份停在
  `step=2` 的检查点、**没有 `run.end`**）；
- "重启后"：`create_app(registry=RunRegistry(), ...)` —— 空登记表等价于进程刚起；
- 断言恢复用它跑完，且**没有重跑已完成的工具调用**、**没有重发 `session.start`**。
"""

from __future__ import annotations

import asyncio
from typing import Any

import pytest

from app import config
from app.main import create_app
from app.runtime import RunRegistry

from fake_platform import FakePlatform, ScriptedModel, final, seed_crashed
from sse import SSEStream, event_names, events_only

TOKEN = "tok-ok"


@pytest.fixture(autouse=True)
def _fast_heartbeat(monkeypatch: pytest.MonkeyPatch) -> None:
    """心跳压到 50ms：否则每个"等下一帧"的断言都要真等 15 秒。"""
    monkeypatch.setattr(config, "SSE_HEARTBEAT_SECONDS", 0.05)


def _app(platform: FakePlatform, model: Any, registry: RunRegistry | None = None):
    return create_app(
        registry=registry if registry is not None else RunRegistry(),
        java_transport=platform.transport(),
        model=model,
    )


async def _open(
    stream: SSEStream,
    app: Any,
    session_id: str,
    *,
    jwt: str | None = TOKEN,
    after_seq: int | None = None,
    headers: dict[str, str] | None = None,
) -> SSEStream:
    query = {"session_id": session_id}
    if after_seq is not None:
        query["after_seq"] = str(after_seq)
    merged: dict[str, str] = {}
    if jwt is not None:
        merged["Authorization"] = f"Bearer {jwt}"
    merged.update(headers or {})
    return await stream.open(app, "/chat/stream", query=query, headers=merged)


class GatedModel:
    """第一次 `complete` 卡住直到测试放行 —— 让"run 正在进行中"成为一个稳定的状态。

    没有它，"两个连接同时重连"的断言就靠调度顺序，随时可能变成假绿。
    """

    def __init__(self, *responses: dict[str, Any]) -> None:
        self._inner = ScriptedModel(*responses)
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def complete(self, messages, tools, *, final: bool = False):
        self.started.set()
        await self.release.wait()
        return await self._inner.complete(messages, tools, final=final)

    @property
    def calls(self) -> int:
        return self._inner.calls


# ==========================================================================
# 核心：重启续传
# ==========================================================================


async def test_resume_continues_unfinished_run_from_checkpoint():
    platform = FakePlatform()
    session_id, run_id = seed_crashed(platform)
    before = platform.checkpoint_state(run_id)
    assert before is not None

    model = ScriptedModel(final("先看结论文本"), final('{"answer": "2 个任务在推进"}'))
    app = _app(platform, model)

    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        frames = await stream.collect("run.end")

    evs = events_only(frames)

    # 1) 未完成的 run 被接着跑完了
    assert evs[-1]["event"] == "run.end"
    assert evs[-1]["data"]["status"] == "ok"
    assert evs[-1]["data"]["output"] == '{"answer": "2 个任务在推进"}'

    # 2) **接着跑**而不是从头跑：恢复后第一次 complete 收到的 messages
    #    与检查点里的逐字相同（崩之前的工具结果原样带着）
    assert model.seen[0] == before["messages"]

    # 3) 已完成的工具调用没有被重跑
    assert platform.calls == [{"name": "query_tasks", "arguments": {}}]

    # 4) 重发 session.start 会让前端以为新开了一轮
    assert platform.event_types(run_id).count("session.start") == 1

    # 5) 重放 + 增量拼起来 seq 严格递增、无重复
    seqs = [f["seq"] for f in evs]
    assert seqs == sorted(seqs)
    assert len(set(seqs)) == len(seqs)
    # 崩之前的 5 条被重放出来了，续上的事件排在它们之后
    assert seqs[:5] == [1, 2, 3, 4, 5]
    assert seqs[5] > seqs[4]


async def test_resume_is_not_triggered_when_run_already_terminal():
    """有 `run.end` 就不是未完成 —— 恢复绝不能把跑完的 run 再跑一遍。"""
    platform = FakePlatform()
    session_id, run_id = seed_crashed(platform)
    platform.add_event(session_id, run_id, "run.end", {"status": "ok"})

    model = ScriptedModel(final("不该被调用"))
    app = _app(platform, model)

    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        for _ in range(4):
            await stream.frame()

    assert model.calls == 0, "已终局的 run 不该触发模型调用"


async def test_events_without_checkpoint_are_not_resumable():
    """只有事件、没有检查点的 run 不算未完成 —— 没有可恢复的状态，无从接着跑。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-nostate", "model.step", {"step": 1})

    model = ScriptedModel(final("不该被调用"))
    app = _app(platform, model)

    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        for _ in range(3):
            await stream.frame()

    assert model.calls == 0


async def test_resume_budget_exhausted_fails_without_calling_model():
    """`elapsed_seconds` 已超总预算 → 直接 run.error{run_timeout}，一次模型都不调。

    时间预算存的是**相对量**，所以跨进程守恒：崩溃前烧掉的时间不会被"重启"抹掉。
    """
    platform = FakePlatform()
    session_id, _ = seed_crashed(platform, elapsed=9999.0)

    model = ScriptedModel(final("不该被调用"))
    app = _app(platform, model)

    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        frames = await stream.collect("run.error")

    errors = [f for f in events_only(frames) if f["event"] == "run.error"]
    assert errors and errors[-1]["data"]["code"] == "run_timeout"
    assert model.calls == 0


async def test_concurrent_streams_resume_only_once():
    """两个连接同时重连，run 只被驱动一次。

    `RunRegistry.claim` 是同步的（内部无 `await`），asyncio 单线程下"检查 + 登记"
    天然原子 —— 这正是它不用 `asyncio.Lock` 的原因。第二条流挂上来时第一条已经把
    循环跑起来了，它只能重放事件，不会再起一个。
    """
    platform = FakePlatform()
    session_id, _ = seed_crashed(platform)
    model = GatedModel(final("A"), final("B"), final("C"))
    app = _app(platform, model)

    async with SSEStream() as first, SSEStream() as second:
        await _open(first, app, session_id)
        await asyncio.wait_for(model.started.wait(), 2.0)
        await _open(second, app, session_id)
        model.release.set()
        frames = await first.collect("run.end")
        await second.collect("run.end")

    assert platform.event_types().count("run.end") == 1
    assert len([f for f in events_only(frames) if f["event"] == "run.end"]) == 1


def test_registry_claim_is_exclusive_until_finish():
    """上一条依赖的不变量，在单元层也钉一遍。"""
    registry = RunRegistry()
    assert registry.claim("s", "r1") is True
    assert registry.claim("s", "r1") is False
    assert registry.claim("s", "r2") is False
    registry.finish("s", "r1")
    assert registry.claim("s", "r2") is True


# ==========================================================================
# 续传起点 / 心跳 / 终局之后
# ==========================================================================


async def test_last_event_id_header_wins_over_smaller_after_seq():
    """`Last-Event-ID` 与 `after_seq` 同时给出时取 **max**。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    for i in range(1, 5):
        platform.add_event(session_id, "run-x", "model.step", {"step": i})

    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, session_id, after_seq=1, headers={"Last-Event-ID": "3"})
        frame = await stream.frame()

    assert frame["seq"] == 4, "应取 max(1, 3) = 3 之后，即第 4 条"


async def test_after_seq_alone_is_honoured():
    platform = FakePlatform()
    session_id = platform.make_session()
    for i in range(1, 4):
        platform.add_event(session_id, "run-x", "model.step", {"step": i})

    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, session_id, after_seq=2)
        frame = await stream.frame()

    assert frame["seq"] == 3


async def test_garbage_last_event_id_falls_back_to_after_seq():
    """坏掉的 `Last-Event-ID` 不该把流断掉，退回 `after_seq` 即可。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-x", "model.step", {"step": 1})

    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, session_id, headers={"Last-Event-ID": "not-a-number"})
        frame = await stream.frame()

    assert frame["seq"] == 1


async def test_idle_stream_emits_heartbeat():
    """没有新事件时发注释心跳，防反代 60s 断流（契约 §3.1）。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-x", "model.step", {"step": 1})

    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        first = await stream.frame()
        second = await stream.frame()

    assert first["kind"] == "event"
    assert second["kind"] == "comment" and second["text"] == "ping"


async def test_stream_stays_open_after_terminal_event():
    """`agent.report` 允许在 `run.end` **之后**到达，所以终局不能关流（契约 §3.4#4）。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-x", "run.end", {"status": "ok"})

    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        await stream.collect("run.end")
        # 终局之后平台又推了一条异步汇报
        platform.add_event(session_id, "run-x", "agent.report", {"summary": "复盘已完成"})
        frames = await stream.collect("agent.report")

    assert "agent.report" in event_names(frames)


async def test_replayed_frames_keep_original_timestamp():
    """重放帧的 `ts` 必须取行里的 `created_at`,不能是"重放这一刻"。

    否则历史事件全带上重连时刻，前端按 ts 排序会全乱。
    """
    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-x", "model.step", {"step": 1})

    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        frame = await stream.frame()

    stored = platform.events[0]["created_at"]
    assert frame["ts"].startswith(stored[:16]), f"{frame['ts']} 应源自 {stored}"


async def test_events_from_every_run_in_the_session_are_streamed():
    """通道是**按会话**的：一个会话里多个 run 的事件都该推出去。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-old", "run.end", {"status": "ok"})
    platform.add_event(session_id, "run-new", "session.start", {"utterance": "再来一轮"})

    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        frames = await stream.collect("session.start")

    assert [f["event"] for f in events_only(frames)] == ["run.end", "session.start"]


async def test_event_data_is_the_stored_object_not_a_string():
    """`detail` 必须是 JSON 对象 —— 原样吐字符串的话前端得解析两次。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-x", "tool.result", {"name": "query_tasks", "ok": True})

    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        frame = await stream.frame()

    assert isinstance(frame["data"], dict)
    assert frame["data"]["name"] == "query_tasks"


# ==========================================================================
# 有界增量读（任务 C）
# ==========================================================================


async def test_backlog_larger_than_one_page_is_streamed_by_paging(monkeypatch: pytest.MonkeyPatch):
    """盘子里压着的事件多于**一页**时，分页取、逐页吐 —— 语义一字不变。

    这一条同时钉住三件事：

    1. **单次读有界**：每次 `GET /events` 只问 3 条（`config.EVENT_PAGE_LIMIT`），
       不会把积压整批搬进内存（Java 侧不传 limit 的默认值是 500，
       上限 1000 —— `AgentEventService.DEFAULT_LIMIT` / `MAX_LIMIT`）；
    2. **续读不漏**：8 条事件分成 3 页（3 + 3 + 2）全部推出去；
    3. **顺序与 seq 单调不变**：逐页拼起来仍是 1..8 升序、无重复 —— 分页最容易
       踩的坑就是"页边界丢一条"或"重复吐同一条"。
    """
    monkeypatch.setattr(config, "EVENT_PAGE_LIMIT", 3)
    platform = FakePlatform()
    session_id = platform.make_session()
    for step in range(1, 9):
        platform.add_event(session_id, "run-x", "model.step", {"step": step})

    app = _app(platform, ScriptedModel())

    frames: list[dict[str, Any]] = []
    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        while len([f for f in frames if f["kind"] == "event"]) < 8:
            frame = await stream.frame()
            assert frame is not None
            frames.append(frame)

    assert [f["seq"] for f in events_only(frames)] == list(range(1, 9))
    # 3 页：limit 3 → [3, 3, 2]；"每次只要 3 条"就是"单次读有界"。
    # 只断言前 3 次 —— 事件吐完之后生成器会继续空转读（空页 → 心跳），次数不确定。
    assert platform.event_read_limits[:3] == [3, 3, 3]
    assert set(platform.event_read_limits) == {3}, "任何一次读都不该越过页大小"
    assert platform.event_read_after[:3] == [0, 3, 6], "cursor 必须逐页严格前进"


async def test_backlog_twice_as_large_takes_twice_as_many_bounded_reads(monkeypatch: pytest.MonkeyPatch):
    """读取次数随积压**线性增长**，而每次读的条数恒定 —— 这就是"有界增量读"。

    对比 `test_backlog_larger_than_one_page_is_streamed_by_paging`：8 条要 3 页，
    10 条要 5 页（页大小 2）。若哪天有人把 `limit` 从调用里去掉了，
    假平台会按 Java 的默认值 500 一次全给，这两条断言会立刻变成 `[500]`。

    关于回压：本夹具（`tests/sse.py`）没有内核级的写回压（`send` 直接进无界队列），
    所以生成器会跑在测试读帧之前，把积压按页一次读完 —— 那是夹具的形态，
    不是被测代码绕过了分页。这里能钉住的是"每页有界 + cursor 逐页前进"这个形状；
    生产路径上的回压来自 uvicorn 传输层，`await send(...)` 会在写缓冲满时挂住。
    """
    monkeypatch.setattr(config, "EVENT_PAGE_LIMIT", 2)
    platform = FakePlatform()
    session_id = platform.make_session()
    for step in range(1, 11):
        platform.add_event(session_id, "run-x", "model.step", {"step": step})

    app = _app(platform, ScriptedModel())

    frames: list[dict[str, Any]] = []
    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        while len([f for f in frames if f["kind"] == "event"]) < 10:
            frame = await stream.frame()
            assert frame is not None
            frames.append(frame)

    assert [f["seq"] for f in events_only(frames)] == list(range(1, 11))
    assert platform.event_read_limits[:5] == [2, 2, 2, 2, 2]
    assert platform.event_read_after[:5] == [0, 2, 4, 6, 8]


async def test_paging_does_not_resend_an_already_delivered_event(monkeypatch: pytest.MonkeyPatch):
    """页边界上 `after_seq` 只前进不回退：不会因为分页把同一帧吐两次。

    6 条事件、页大小 4 → 两页（4 + 2）。第二次读的 `after_seq` 必须是**第 4 条的 id**
    （Java 侧语义是严格大于 `id > after_seq`）。
    """
    monkeypatch.setattr(config, "EVENT_PAGE_LIMIT", 4)
    platform = FakePlatform()
    session_id = platform.make_session()
    for step in range(1, 7):
        platform.add_event(session_id, "run-x", "model.step", {"step": step})

    app = _app(platform, ScriptedModel())

    frames: list[dict[str, Any]] = []
    async with SSEStream() as stream:
        await _open(stream, app, session_id)
        while len([f for f in frames if f["kind"] == "event"]) < 6:
            frame = await stream.frame()
            assert frame is not None
            frames.append(frame)

    seqs = [f["seq"] for f in events_only(frames)]
    assert seqs == [1, 2, 3, 4, 5, 6]
    assert len(set(seqs)) == 6, "分页把事件重发了"
    assert platform.event_read_after[:2] == [0, 4]


# ==========================================================================
# 门禁与响应头
# ==========================================================================


async def test_stream_requires_token():
    platform = FakePlatform()
    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, "whatever", jwt=None)

    assert stream.status == 401


async def test_stream_rejects_bad_token_before_opening_the_stream():
    """令牌不认 → 401 走 HTTP 状态码，**不能**塞进 SSE 流。

    塞进流里的话前端拿到的是 200，会以为连上了，实际上每一帧都是错的。
    """
    platform = FakePlatform()
    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, "whatever", jwt="wrong")

    assert stream.status == 401
    assert "text/event-stream" not in stream.headers.get("content-type", "")


async def test_stream_404s_before_opening_when_session_is_unknown():
    """会话不存在必须在**建流之前**走 HTTP 404，不能"先 200 再掐断"。

    建流之后状态码已经固定成 200，Java 的 404 只能表现为流被掐断 —— 前端看到的是
    "事件流读取失败"，既不知道原因，也不会清掉本地那个失效的会话句柄。
    实际症状：开发库重建后前端仍记着旧 session_id，于是「和本说不了话」，且刷新
    页面也不恢复（id 持久化在 localStorage 里）。
    """
    platform = FakePlatform()
    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, "no-such-session")

    assert stream.status == 404
    assert "text/event-stream" not in stream.headers.get("content-type", "")


async def test_stream_sets_no_buffering_header():
    """`X-Accel-Buffering: no`：反代缓冲会把 SSE 退化成一坨吐出（契约 §3.1）。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    app = _app(platform, ScriptedModel())

    async with SSEStream() as stream:
        await _open(stream, app, session_id)

    assert stream.headers.get("x-accel-buffering") == "no"
    assert stream.headers.get("cache-control") == "no-cache"
    assert stream.headers.get("content-type", "").startswith("text/event-stream")
