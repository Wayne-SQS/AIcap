"""同会话历史纪要（选项 2）：第二轮 /chat 必须记得第一轮。

修的 bug 是真机实测出来的：每轮 messages 只有 system + 当前 utterance，模型连
「我上一轮派过 #ee9c6376」都不知道，被追问进展时 0 次工具调用、给出与事实相反
的答复。这组用例钉住纪要拼装的四条边界：注入位置（system 之后、当前 utterance
之前）、自身事件排除、未终局 run 排除、读取失败降级。
"""

from __future__ import annotations

import uuid
from typing import Any

from app import config
from app import loop as loop_module
from app.java_client import JavaClient
from app.runtime import RunRegistry
from app.schemas import PlatformError

from fake_platform import FakePlatform, ScriptedModel, final as make_final, tool_call

TOKEN = "tok-ok"
BASE = "http://java.test"


class RecordingModel:
    """记下第一次（非 final）调用看到的 messages，然后按循环三发形状走完：
    要工具 → 给结论 → 收尾。若每一轮非 final 都回同一个 tool_call（同 id 重复），
    循环会以协议违规判 run.error —— 「跑完一轮」本身就验不过了。

    `ModelProtocol.complete` 的返回是 `(response, usage)` 元组 —— 与 ScriptedModel 同形。"""

    def __init__(self) -> None:
        self.first_messages: list[dict[str, Any]] | None = None
        self._non_final_calls = 0

    async def complete(self, messages, tools, *, final: bool = False):
        if final:
            return make_final('{"answer": "ok"}'), {}
        self._non_final_calls += 1
        if self._non_final_calls == 1:
            self.first_messages = [dict(m) for m in messages]
            return tool_call("c1", "query_tasks", {}), {}
        return make_final("已查到结果"), {}


async def _run_turn(platform: FakePlatform, session_id: str, utterance: str, model: Any,
                    *, run_id: str | None = None) -> str:
    run_id = run_id or str(uuid.uuid4())
    async with JavaClient(BASE, transport=platform.transport()) as client:
        await loop_module.run(
            session_id=session_id,
            run_id=run_id,
            utterance=utterance,
            jwt=TOKEN,
            client=client,
            registry=RunRegistry(),
            model=model,
        )
    return run_id


def _end_of(platform: FakePlatform, run_id: str) -> dict[str, Any]:
    ends = [e for e in platform.events if e["run_id"] == run_id and e["type"] == "run.end"]
    assert ends, f"没有 run.end，实际事件：{platform.event_types(run_id)}"
    return ends[-1]["detail"]


def _history_of(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """system 之后、最后一条 user（当前 utterance）之前的那一段。"""
    assert messages[0]["role"] == "system"
    assert messages[-1]["role"] == "user"
    return messages[1:-1]


async def test_second_turn_remembers_first_turn_with_dispatch_note():
    """第二轮 messages 里能看到第一轮的原话、答复与派活注记 —— 修「它不知道自己派过活」。"""
    platform = FakePlatform()
    session_id = platform.make_session()

    first_run = await _run_turn(
        platform, session_id, "把 US01 挪到第 3 个 Sprint",
        # 三发脚本：要工具 → 中间结论 → 收尾。`run.end.output` 取的是**最后一发**
        # （final=True）的内容 —— 真实链路里那一发看到的才是面向用户的答复。
        ScriptedModel(tool_call("c1", "query_tasks", {}), make_final("中间结论"),
                      make_final("已生成草案初稿，待确认")),
    )
    platform.add_event(session_id, first_run, "agent.dispatched", {
        "agent": "start_plan_draft", "job_id": "job-1", "status": "queued", "deduplicated": False,
    })

    model = RecordingModel()
    await _run_turn(platform, session_id, "刚才那份草案好了吗", model)

    history = _history_of(model.first_messages)
    assert [m["role"] for m in history] == ["user", "assistant"], history
    assert history[0]["content"] == "把 US01 挪到第 3 个 Sprint"
    assert "已生成草案初稿，待确认" in history[1]["content"]
    assert "[已派活: start_plan_draft → run_id job-1, 派发时状态 queued]" in history[1]["content"]


async def test_current_run_own_events_are_not_history():
    """本 run 的 session.start 在拼 messages 前已落库（on_claim 先跑）—— 不得把自己算进历史。"""
    platform = FakePlatform()
    session_id = platform.make_session()

    model = RecordingModel()
    await _run_turn(platform, session_id, "这是当前这一轮", model)

    assert _history_of(model.first_messages) == [], model.first_messages


async def test_unterminated_prior_run_is_skipped():
    """没有终局事件的 run 不进纪要：它还没有"说过的话"，算进来等于编造历史。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-pending", "session.start", {"utterance": "还没跑完的一轮"})
    platform.add_event(session_id, "run-pending", "model.step", {"step": 1})

    model = RecordingModel()
    await _run_turn(platform, session_id, "新的一轮", model)

    assert _history_of(model.first_messages) == [], model.first_messages


async def test_clarify_turn_is_recorded_as_the_question():
    """反问轮的"答复"就是那个问题（run.end{status:clarify} 没有 output）。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-clarify", "session.start", {"utterance": "帮我分析一下"})
    platform.add_event(session_id, "run-clarify", "clarify.request",
                       {"question": "你想分析哪个项目？", "options": ["P001"], "logged": True})
    platform.add_event(session_id, "run-clarify", "run.end", {"status": "clarify"})

    model = RecordingModel()
    await _run_turn(platform, session_id, "P001", model)

    history = _history_of(model.first_messages)
    assert len(history) == 2, history
    assert history[0]["content"] == "帮我分析一下"
    assert "（主管反问）你想分析哪个项目？" in history[1]["content"]


async def test_events_read_failure_degrades_to_empty_history():
    """读事件失败（Java 抖动/端点异常）→ 空纪要，本轮照常跑完 —— 记忆是增强不是依赖。"""
    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-old", "session.start", {"utterance": "上一轮"})
    platform.add_event(session_id, "run-old", "run.end", {"status": "ok", "output": "上一轮结论"})

    model = RecordingModel()
    run_id = str(uuid.uuid4())
    async with JavaClient(BASE, transport=platform.transport()) as client:
        async def broken(*, session_id: str, after_seq: int, jwt: str):
            raise PlatformError("platform_error", "读事件失败")

        client.list_events = broken  # type: ignore[method-assign]
        await loop_module.run(
            session_id=session_id, run_id=run_id, utterance="新的一轮",
            jwt=TOKEN, client=client, registry=RunRegistry(), model=model,
        )

    assert _history_of(model.first_messages) == [], model.first_messages
    assert _end_of(platform, run_id)["status"] == "ok"


async def test_history_is_capped_to_last_runs_and_truncated(monkeypatch):
    """只取最近 N 轮、单条截断 —— 纪要负责"记得"，不负责存档。"""
    monkeypatch.setattr(config, "HISTORY_MAX_RUNS", 1)
    monkeypatch.setattr(config, "HISTORY_OUTPUT_CHARS", 10)

    platform = FakePlatform()
    session_id = platform.make_session()
    platform.add_event(session_id, "run-a", "session.start", {"utterance": "A" * 50})
    platform.add_event(session_id, "run-a", "run.end", {"status": "ok", "output": "A" * 50})
    platform.add_event(session_id, "run-b", "session.start", {"utterance": "B" * 50})
    platform.add_event(session_id, "run-b", "agent.dispatched",
                       {"agent": "start_plan_draft", "job_id": "job-b", "status": "running"})
    platform.add_event(session_id, "run-b", "run.end", {"status": "ok", "output": "B" * 50})

    model = RecordingModel()
    await _run_turn(platform, session_id, "第三轮", model)

    history = _history_of(model.first_messages)
    assert [m["role"] for m in history] == ["user", "assistant"], history
    assert history[0]["content"] == "B" * 10, "应取最近一轮且截断到 10 字"
    assert all("A" not in m["content"] for m in history), "更早的轮次不该出现"
    assert "[已派活: start_plan_draft → run_id job-b" in history[1]["content"]
