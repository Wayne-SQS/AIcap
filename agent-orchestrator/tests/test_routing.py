"""规划相接进 `POST /chat`（S5′）：反问、派活、留痕、事件顺序，以及隐私那条线。

一次 run 的事件序列（契约 §3.4）：

```
session.start            ← loop 的 on_claim（规划相之前）
intent.routed            ← 派活时；**必须先于任何 tool.call / agent.dispatched**
agent.dispatched         ← 触发型工具回执（run_id / deduplicated / status）
model.step / tool.call … ← 有界循环
run.end                  ← 终局
```

反问那一轮则是 `clarify.request` → `run.end{status: "clarify"}`，**不进循环、不派活**。

隐私线：`utterance` 是用户原话，**只允许进 `routing_logs` 这一个端点**。
（`session.start` 的 `detail.utterance` 是 S4′.2 的既有设计、也是这轮对话的在场证明，
本次不动它 —— 用例把"新事件一个都不带"和"唯一既有载体"分开断言，见
`test_utterance_only_travels_through_the_routing_log`。）
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Callable

import httpx
import pytest

from app import orchestrator
from app.main import create_app
from app.model_client import ModelClient
from app.runtime import RunRegistry

from fake_platform import (
    MEETING_ROUTE,
    PLAN_DRAFT_ROUTE,
    FakePlatform,
    ScriptedModel,
    UTTERANCE,
    clarify_plan,
    final,
    plan_json,
    plan_reply,
    seed_crashed,
    tool_call,
)
from sse import SSEStream, events_only

TOKEN = "tok-ok"
AUTH = {"Authorization": f"Bearer {TOKEN}"}

ROUTING_LOG_KEYS = {
    "session_id",
    "run_id",
    "utterance",
    "intent",
    "route",
    "confidence",
    "reason",
    "asked_clarify",
    "candidates",
}


def _app(platform: FakePlatform, model: Any, registry: RunRegistry | None = None):
    return create_app(
        registry=registry if registry is not None else RunRegistry(),
        java_transport=platform.transport(),
        model=model,
    )


def _client(app: Any) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://orchestrator.test"
    )


async def _wait_for(predicate: Callable[[], bool], *, timeout: float = 2.0) -> bool:
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while loop.time() < deadline:
        if predicate():
            return True
        await asyncio.sleep(0.01)
    return predicate()


async def run_turn(platform: FakePlatform, model: Any, utterance: str = UTTERANCE) -> tuple[str, str]:
    """一次 `/chat`，等它终局，返回 `(session_id, run_id)`。"""
    app = _app(platform, model)
    async with _client(app) as client:
        response = await client.post("/chat", json={"utterance": utterance}, headers=AUTH)
        assert response.status_code == 200, response.text
        body = response.json()
        assert await _wait_for(
            lambda: any(
                event["type"] in ("run.end", "run.error") for event in platform.events
            )
        ), f"run 没有终局，事件：{platform.event_types(body['run_id'])}"
    return body["session_id"], body["run_id"]


def events_of(platform: FakePlatform, run_id: str) -> list[dict[str, Any]]:
    return [event for event in platform.events if event["run_id"] == run_id]


def types_of(platform: FakePlatform, run_id: str) -> list[str]:
    return [event["type"] for event in events_of(platform, run_id)]


def detail_of(platform: FakePlatform, run_id: str, type_: str) -> dict[str, Any]:
    rows = [event for event in events_of(platform, run_id) if event["type"] == type_]
    assert rows, f"没有 {type_} 事件，实际：{types_of(platform, run_id)}"
    return rows[-1]["detail"]


DISPATCH_SCRIPT = (plan_reply(), tool_call("c1", "query_tasks", {}), final("看到了"), final('{"answer": "ok"}'))


# ==========================================================================
# 派活：事件顺序与派发回执
# ==========================================================================


async def test_dispatch_turn_emits_intent_routed_before_tool_call_and_dispatch():
    """契约 §3.4 第 2 条：`intent.routed` **先于**任何 `tool.call` / `agent.dispatched`。"""
    platform = FakePlatform()
    _, run_id = await run_turn(platform, ScriptedModel(*DISPATCH_SCRIPT))

    types = types_of(platform, run_id)
    assert types[0] == "session.start"
    assert types[-1] == "run.end"
    assert types.index("intent.routed") < types.index("agent.dispatched")
    assert types.index("intent.routed") < types.index("tool.call")
    # 派活发生在循环之前：先派，再跑循环里的工具
    assert types.index("agent.dispatched") < types.index("tool.call")


async def test_intent_routed_carries_the_contract_payload():
    platform = FakePlatform()
    _, run_id = await run_turn(platform, ScriptedModel(*DISPATCH_SCRIPT))

    detail = detail_of(platform, run_id, "intent.routed")

    assert detail["intent"] == "start_meeting"
    assert detail["route"] == MEETING_ROUTE
    assert detail["confidence"] == 0.92
    assert detail["reason"] == "用户要求分析这场会议"
    assert detail["logged"] is True, "留痕写进去了要说清楚（写不进去时是 false）"


async def test_agent_dispatched_carries_the_trigger_receipt():
    """触发型工具回执（契约 §2.4）：`run_id` / `deduplicated` / `status` 都要能看见。"""
    platform = FakePlatform()
    platform.trigger_results[MEETING_ROUTE] = {
        "run_id": "run-meeting-77",
        "deduplicated": True,
        "status": "queued",
    }
    _, run_id = await run_turn(platform, ScriptedModel(*DISPATCH_SCRIPT))

    detail = detail_of(platform, run_id, "agent.dispatched")

    assert detail["agent"] == MEETING_ROUTE
    assert detail["job_id"] == "run-meeting-77"
    assert detail["mode"] == "trigger"
    assert detail["status"] == "queued"
    assert detail["deduplicated"] is True

    # 派活真的调了那枚工具，参数就是 plan() 给的 args（循环里那次 query_tasks 在它之后）
    assert platform.calls[0] == {"name": MEETING_ROUTE, "arguments": {"meeting_id": "m-1"}}


async def test_a_dispatch_turn_writes_exactly_one_routing_log_row():
    platform = FakePlatform()
    session_id, run_id = await run_turn(platform, ScriptedModel(*DISPATCH_SCRIPT))

    assert len(platform.routing_logs) == 1
    row = platform.routing_log_for(run_id)
    assert row is not None
    assert set(row) == ROUTING_LOG_KEYS, "载荷字段一个不多一个不少（契约 §2.7）"
    assert row["session_id"] == session_id
    assert row["utterance"] == UTTERANCE
    assert row["intent"] == "start_meeting"
    assert row["route"] == MEETING_ROUTE
    assert row["confidence"] == 0.92
    assert row["reason"] == "用户要求分析这场会议"
    assert row["asked_clarify"] is False
    assert row["candidates"] == [
        {"route": MEETING_ROUTE, "required": ["meeting_id"], "chosen": True},
        {"route": PLAN_DRAFT_ROUTE, "required": ["request"], "chosen": False},
    ]


async def test_routing_log_is_written_after_planning_and_before_dispatch():
    """契约 §2.7：留痕在 `plan()` 之后、**派活之前**。

    用请求到达顺序断言（`FakePlatform.trace`）：拉清单 → 写留痕 → 调触发型工具。
    """
    platform = FakePlatform()
    _, run_id = await run_turn(platform, ScriptedModel(*DISPATCH_SCRIPT))

    trace = platform.trace
    catalog = trace.index("GET /api/agents/catalog")
    logged = trace.index("POST /api/agents/routing-logs")
    dispatched = trace.index(f"POST /api/agents/tools/{MEETING_ROUTE}")

    assert catalog < logged < dispatched
    # 而且 intent.routed 那一发事件也在派活之前
    assert trace.index("POST /api/agents/events") < dispatched
    assert detail_of(platform, run_id, "intent.routed")["logged"] is True


async def test_clarify_turn_asks_writes_the_sample_and_never_dispatches():
    """反问：`clarify.request` + `run.end{status:clarify}`，**不派活、不进循环**。"""
    platform = FakePlatform()
    model = ScriptedModel(clarify_plan())
    session_id, run_id = await run_turn(platform, model)

    types = types_of(platform, run_id)
    assert types == ["session.start", "clarify.request", "run.end"]
    assert detail_of(platform, run_id, "run.end")["status"] == "clarify"

    clarify = detail_of(platform, run_id, "clarify.request")
    assert clarify["question"] == "要分析哪一场会议？"
    assert clarify["options"] == ["最近一次周会", "上一次评审会"]
    assert clarify["logged"] is True

    assert platform.calls == [], "反问不该调任何工具"
    assert platform.checkpoint_state(run_id) is not None, "会话仍要有 #0 检查点"

    # 反问样本也要留痕：S7 的"澄清率"没有它就只剩分母的一半
    row = platform.routing_log_for(run_id)
    assert row is not None and row["asked_clarify"] is True
    assert row["route"] is None and row["session_id"] == session_id


def _finished(platform: FakePlatform, run_id: str) -> bool:
    return any(
        event["run_id"] == run_id and event["type"] in ("run.end", "run.error")
        for event in platform.events
    )


async def test_second_turn_plan_gets_the_previous_clarify_question():
    """同会话第二轮：规划相要看到上一轮的反问，「回答反问」才不至于从零开始。

    2026-10-27 真机实测的回归：第 2 轮补「第 5 周，写实验报告」时又问负责人（第 1 轮
    已经给过李锐铭），第 3 轮补「负责人就是李锐铭」时反而问「你想执行哪个动作」。
    这里钉住的是「历史纪要真的进了规划那一发模型请求」。
    """
    platform = FakePlatform()
    model = ScriptedModel(
        clarify_plan(question="请补齐任务名称与第几周？"),
        clarify_plan(question="还缺负责人，请补充"),
    )
    app = _app(platform, model)
    async with _client(app) as client:
        first = await client.post(
            "/chat", json={"utterance": "帮我给李锐铭加一个任务"}, headers=AUTH
        )
        assert first.status_code == 200, first.text
        session_id = first.json()["session_id"]
        assert await _wait_for(lambda: _finished(platform, first.json()["run_id"])), "第一轮没有终局"

        second = await client.post(
            "/chat",
            json={"utterance": "第 5 周，写实验报告", "session_id": session_id},
            headers=AUTH,
        )
        assert second.status_code == 200, second.text
        assert await _wait_for(lambda: _finished(platform, second.json()["run_id"])), "第二轮没有终局"

    # 第一轮是反问、不进循环 → 只调一次模型；第二轮规划那一发是 seen[1]
    plan_messages = model.seen[1]
    assert plan_messages[1] == {"role": "user", "content": "帮我给李锐铭加一个任务"}
    assert "（主管反问）请补齐任务名称与第几周？" in plan_messages[2]["content"]
    assert plan_messages[-1] == {"role": "user", "content": "第 5 周，写实验报告"}


async def test_a_question_turn_routes_then_lets_the_loop_answer_it():
    """问问题的回合：`intent.routed{route: null}` → 循环自己调只读工具作答。

    这一条同时钉住契约 §3.4 第 2 条在**没有派活**的回合里也成立：`intent.routed`
    必须先于紧随其后的 `tool.call`（S4′.2 把这条顺序验收留给了 S5′）。
    """
    platform = FakePlatform()
    model = ScriptedModel(
        plan_reply(intent="query_tasks", route=None, confidence=0.9, args={}, clarify=None),
        tool_call("c1", "query_tasks", {}),
        final("3 个任务在推进"),
        final('{"answer": "3 个"}'),
    )
    session_id, run_id = await run_turn(platform, model)

    types = types_of(platform, run_id)
    assert "agent.dispatched" not in types, "没有子智能体可派"
    assert "clarify.request" not in types, "问问题不需要追问"
    assert types.index("intent.routed") < types.index("tool.call")
    assert types[-1] == "run.end"
    assert platform.calls == [{"name": "query_tasks", "arguments": {}}], "循环照常调只读工具"

    routed = detail_of(platform, run_id, "intent.routed")
    assert routed["route"] is None and routed["logged"] is True

    row = platform.routing_log_for(run_id)
    assert row["route"] is None and row["asked_clarify"] is False
    assert row["session_id"] == session_id


async def test_low_confidence_turn_asks_even_though_the_model_named_a_route():
    """阈值说了算：模型选了路由、参数也齐，confidence 0.41 仍要反问。"""
    platform = FakePlatform()
    session_id, run_id = await run_turn(
        platform, ScriptedModel(plan_reply(confidence=0.41, clarify=None))
    )

    assert types_of(platform, run_id) == ["session.start", "clarify.request", "run.end"]
    question = detail_of(platform, run_id, "clarify.request")["question"]
    assert question, "模型没给追问内容时要有机械兜底，不能发一条没有问题的反问"
    assert platform.calls == []
    assert platform.routing_log_for(run_id)["asked_clarify"] is True


async def test_missing_required_arg_asks_with_high_confidence():
    """缺必填参数（模型很有把握也没用）→ 反问，问出那个缺的参数名。"""
    platform = FakePlatform()
    _, run_id = await run_turn(
        platform, ScriptedModel(plan_reply(confidence=0.97, args={}, clarify=None))
    )

    assert "meeting_id" in detail_of(platform, run_id, "clarify.request")["question"]
    assert platform.calls == []
    assert "intent.routed" not in types_of(platform, run_id)


async def test_write_action_dispatches_without_asking_for_confirmation():
    """"写库/开启采集"类动作：模型想确认也不问 —— 产出走审批队列，由人确认。"""
    platform = FakePlatform()
    model = ScriptedModel(
        plan_reply(
            intent="update_story",
            route=PLAN_DRAFT_ROUTE,
            args={"request": "把 US12 的验收标准改成三条"},
            clarify={"question": "确认要生成草案吗？", "options": ["确认", "取消"]},
        ),
        final("已派发"),
        final('{"answer": "ok"}'),
    )
    _, run_id = await run_turn(platform, model)

    types = types_of(platform, run_id)
    assert "clarify.request" not in types
    assert detail_of(platform, run_id, "agent.dispatched")["agent"] == PLAN_DRAFT_ROUTE
    assert platform.calls[0]["name"] == PLAN_DRAFT_ROUTE
    assert platform.routing_log_for(run_id)["asked_clarify"] is False


async def test_a_route_the_platform_does_not_offer_is_asked_back():
    """catalog 里没有那枚工具 → 路由不可用 → 反问（不做别名兜底）。"""
    platform = FakePlatform()
    platform.include_triggers = False
    _, run_id = await run_turn(platform, ScriptedModel(plan_reply()))

    types = types_of(platform, run_id)
    assert "clarify.request" in types and "agent.dispatched" not in types
    assert platform.calls == []


async def test_a_failed_trigger_tool_does_not_pretend_to_be_dispatched():
    """触发型工具业务失败（200 + ok=false）→ `run.error`，**不**假装已派发。"""
    platform = FakePlatform()
    platform.tool_failures[MEETING_ROUTE] = {
        "error_code": "meeting_not_found",
        "error_message": "会议不存在",
    }
    _, run_id = await run_turn(platform, ScriptedModel(plan_reply()))

    types = types_of(platform, run_id)
    assert "agent.dispatched" not in types
    assert types[-1] == "run.error"
    error = detail_of(platform, run_id, "run.error")
    assert error["code"] == "meeting_not_found"
    assert error["message"] == "会议不存在"
    assert "intent.routed" in types, "路由事实照发 —— 失败的是派发，不是路由"


async def test_a_dispatch_failure_without_an_error_code_still_reports_honestly():
    """工具失败但没有 `error_code`（画像域那种藏在 `result.error` 里的失败）→ 兜底码。"""
    platform = FakePlatform()
    platform.tool_failures[MEETING_ROUTE] = {"error_code": None, "error_message": None}
    _, run_id = await run_turn(platform, ScriptedModel(plan_reply()))

    error = detail_of(platform, run_id, "run.error")
    assert error["code"] == "dispatch_failed"
    assert error["message"], "不能给一句空消息"


# ==========================================================================
# 如实失败 / 降级留痕
# ==========================================================================


async def test_unconfigured_model_reports_not_configured_and_never_guesses(
    monkeypatch: pytest.MonkeyPatch,
):
    """没配密钥 → `run.error{not_configured}`，**不猜路由、不硬凑 confidence**。

    （`/chat` 的 503 是另一条路径：没注入模型时在建会话之前就拒了。这里摆的是
    "模型对象在、但服务端没配置" —— 规划相必须如实失败。）
    """
    from app import config

    monkeypatch.setattr(config, "AICAP_LLM_API_KEY", "")
    monkeypatch.setattr(config, "AICAP_LLM_MODEL", "")
    platform = FakePlatform()
    model = ModelClient()  # 注进 app.state.model：不是 None，但服务端没配置

    app = _app(platform, model)
    async with _client(app) as client:
        response = await client.post("/chat", json={"utterance": UTTERANCE}, headers=AUTH)
        assert response.status_code == 200, "门禁放行（注入的模型不是 None）"
        body = response.json()
        assert await _wait_for(
            lambda: any(e["type"] == "run.error" for e in platform.events)
        )

    run_id = body["run_id"]
    types = types_of(platform, run_id)
    assert types == ["session.start", "run.error"]
    assert detail_of(platform, run_id, "run.error")["code"] == "not_configured"
    assert platform.routing_logs == [], "没有路由决策就没有留痕 —— 不编一行出来"
    assert platform.calls == []


async def test_invalid_model_output_becomes_run_error_not_a_crash():
    """模型返回的不是合法 JSON → `run.error{invalid_response}`（既有错误语义，不抛裸异常）。"""
    platform = FakePlatform()
    _, run_id = await run_turn(
        platform, ScriptedModel({"role": "assistant", "content": "我觉得你想开会"})
    )

    types = types_of(platform, run_id)
    assert types[-1] == "run.error"
    assert detail_of(platform, run_id, "run.error")["code"] == "invalid_response"
    assert platform.routing_logs == [] and platform.calls == []


async def test_routing_log_failure_does_not_break_the_turn(caplog: pytest.LogCaptureFixture):
    """留痕写不进去：**不打断本轮**，但降级必须留痕（`logged=false` + 一条 WARNING）。"""
    platform = FakePlatform()
    platform.routing_logs_status = 500

    with caplog.at_level(logging.WARNING, logger="app.main"):
        _, run_id = await run_turn(platform, ScriptedModel(*DISPATCH_SCRIPT))

    types = types_of(platform, run_id)
    assert types[-1] == "run.end", "留痕失败不该把对话框打断"
    assert "agent.dispatched" in types, "派活照旧发生"
    assert detail_of(platform, run_id, "intent.routed")["logged"] is False
    assert platform.routing_logs == []

    warnings = [record for record in caplog.records if record.levelno == logging.WARNING]
    assert warnings, "降级必须留痕：服务端要有一条 WARNING"
    rendered = " ".join(record.getMessage() for record in warnings)
    assert "routing_log" in rendered
    assert UTTERANCE not in rendered, "日志是另一条侧路，用户原话不许从这里漏出去"


async def test_clarify_turn_also_marks_a_failed_routing_log():
    platform = FakePlatform()
    platform.routing_logs_status = 404

    _, run_id = await run_turn(platform, ScriptedModel(clarify_plan()))

    assert detail_of(platform, run_id, "clarify.request")["logged"] is False
    assert detail_of(platform, run_id, "run.end")["status"] == "clarify"


# ==========================================================================
# 隐私：utterance 只走留痕那一条路
# ==========================================================================


async def test_utterance_only_travels_through_the_routing_log():
    """用户原话**只允许**进 `routing_logs` 一个端点。

    `session.start` 的 `detail.utterance` 是 S4′.2 既有设计（它是这轮对话的在场证明，
    也是循环 messages 里那条 user 消息的来源），本次不动 —— 所以这里把它单列出来，
    断言**其余每一个事件**都不带原话：新加的 `intent.routed` / `agent.dispatched` /
    `clarify.request` 一个都不带。
    """
    platform = FakePlatform()
    _, run_id = await run_turn(platform, ScriptedModel(*DISPATCH_SCRIPT))

    carriers = {
        event["type"]
        for event in events_of(platform, run_id)
        if UTTERANCE in json.dumps(event["detail"], ensure_ascii=False)
    }
    assert carriers == {"session.start"}, f"只有既有载体允许带原话，实际：{carriers}"

    # 留痕那一条正是"允许"的那一条
    assert platform.routing_log_for(run_id)["utterance"] == UTTERANCE


async def test_utterance_never_leaks_into_an_error_message():
    """模型把原话回显在一段非法输出里 → `run.error` 的文本里也不许出现它。"""
    platform = FakePlatform()
    _, run_id = await run_turn(
        platform,
        ScriptedModel({"role": "assistant", "content": f"抱歉，{UTTERANCE} 我看不懂"}),
    )

    error = detail_of(platform, run_id, "run.error")
    assert UTTERANCE not in json.dumps(error, ensure_ascii=False)


async def test_plan_outcome_does_not_ride_in_the_checkpoint():
    """规划结果**不重复落检查点**：路由事实有自己的落点（`agent_events` + `routing_logs`）。

    检查点里只有循环要的那份对话状态（S4′.2），规划相不往里塞第二份 ——
    "同一件事有两个来源"正是恢复路径最容易读歪的地方。
    """
    platform = FakePlatform()
    _, run_id = await run_turn(platform, ScriptedModel(*DISPATCH_SCRIPT))

    state = platform.checkpoint_state(run_id)
    assert state["utterance"] == UTTERANCE
    assert state["messages"][1] == {"role": "user", "content": UTTERANCE}

    blob = json.dumps(state, ensure_ascii=False)
    assert MEETING_ROUTE not in blob
    assert "confidence" not in blob


# ==========================================================================
# 恢复：规划相只在新 run 上跑
# ==========================================================================


async def test_resume_never_replans_nor_rewrites_the_routing_log():
    """崩溃恢复是"接着跑"，不是重新规划：不重发 `intent.routed`、不再写一行留痕。"""
    platform = FakePlatform()
    session_id, run_id = seed_crashed(platform)
    model = ScriptedModel(final("接着看"), final('{"answer": "ok"}'))
    app = _app(platform, model, registry=RunRegistry())  # 空登记表 = 进程刚起

    async with SSEStream() as stream:
        await stream.open(
            app,
            "/chat/stream",
            query={"session_id": session_id},
            headers=AUTH,
        )
        frames = await stream.collect("run.end")

    assert [f for f in events_only(frames) if f["event"] == "run.end"]
    assert platform.routing_logs == [], "恢复不重跑规划相，就别留第二行痕"
    assert "intent.routed" not in types_of(platform, run_id)
    assert types_of(platform, run_id).count("session.start") == 1


# ==========================================================================
# 阈值可配置（端到端）
# ==========================================================================


async def test_threshold_change_flips_the_same_turn(monkeypatch: pytest.MonkeyPatch):
    """同一个模型回复：默认阈值下反问，把阈值调到 0.3 就派活。"""
    proposal = plan_reply(confidence=0.41)

    asking = FakePlatform()
    _, ask_run = await run_turn(asking, ScriptedModel(proposal))
    assert "clarify.request" in types_of(asking, ask_run)

    monkeypatch.setattr(orchestrator, "CLARIFY_CONFIDENCE", 0.3)
    dispatching = FakePlatform()
    _, go_run = await run_turn(
        dispatching, ScriptedModel(proposal, final("好"), final('{"answer": "ok"}'))
    )

    assert "agent.dispatched" in types_of(dispatching, go_run)
    assert "clarify.request" not in types_of(dispatching, go_run)


async def test_plan_json_helper_stays_in_sync_with_the_contract():
    """`plan_json` 摆的是契约那六个字段（用例自己的地基，坏了要先发现）。"""
    assert set(json.loads(plan_json())) == {
        "intent",
        "route",
        "confidence",
        "reason",
        "args",
        "clarify",
    }
