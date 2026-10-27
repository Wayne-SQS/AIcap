"""有界循环：事件序列、检查点写入，以及**四道闸各一发**。

闸门测试的价值全在"它真的会拦"上 —— 每条都断言两件事：`run.error` 的 `code`
正确，**以及拦在正确的时刻**（该只调一次模型的就别调第二次）。
"""

from __future__ import annotations

import asyncio
import json
import time
import uuid
from typing import Any

import pytest

from app import loop as loop_module
from app.java_client import JavaClient
from app.loop import MAX_CALLS_PER_TURN, MAX_STEPS, MAX_TOOL_CALLS, MAX_SECONDS, function_schemas
from app.runtime import RunRegistry
from app.schemas import Catalog, CatalogTool, ModelError

from fake_platform import UTTERANCE, FakePlatform, ScriptedModel, final, tool_call

TOKEN = "tok-ok"
BASE = "http://java.test"


# --------------------------------------------------------------------------
# 循环只拿只读工具
# --------------------------------------------------------------------------


def test_function_schemas_skip_trigger_tools():
    """触发型工具不进循环：派活只发生在规划相（`main._plan_phase`）。

    放进循环的后果真机实测过 —— 一句"生成规划草案"被派出两个 run（第二个是模型在
    循环里自发重派，自己编了段更长的 request 绕过幂等），第二个以"无法解析的响应"失败。"""
    catalog = Catalog(
        platform_version="1.0",
        tools=[
            CatalogTool(name="query_tasks", agent="profile", description="查询任务"),
            CatalogTool(name="start_plan_draft", agent="platform", description="派活",
                        read_only=False),
        ],
        limitations=[],
    )

    names = [schema["function"]["name"] for schema in function_schemas(catalog)]

    assert names == ["query_tasks"], f"触发型工具不该出现在循环的 tools 里: {names}"


# --------------------------------------------------------------------------
# 假模型
# --------------------------------------------------------------------------


def many_calls(prefix: str, count: int) -> dict[str, Any]:
    return {
        "role": "assistant",
        "content": None,
        "tool_calls": [
            {
                "id": f"{prefix}-{i}",
                "type": "function",
                "function": {"name": "query_tasks", "arguments": "{}"},
            }
            for i in range(count)
        ],
    }


def raw_call(call_id: str, arguments: str) -> dict[str, Any]:
    return {
        "role": "assistant",
        "content": None,
        "tool_calls": [
            {
                "id": call_id,
                "type": "function",
                "function": {"name": "query_tasks", "arguments": arguments},
            }
        ],
    }


def finishing(*responses: dict[str, Any]) -> ScriptedModel:
    """脚本化模型，**自动补上收尾那一发**（`complete(final=True)`）。

    循环的形状决定了"一次工具调用"要三次模型调用：`complete(final=False)` 要工具 →
    再一次 `complete(final=False)` 给出结论 → `complete(final=True)` 收尾（S5′ 的结构化
    输出契约会挂在这一发上）。少写一发不会让测试报错，只会静默落成
    `run.error{internal}`（脚本用完）—— 所以统一在这里补最后一发。
    """
    return ScriptedModel(*responses, final('{"answer": "ok"}'))


def tool_then_answer(call: dict[str, Any], draft: str = "初步结论") -> ScriptedModel:
    """最常见的脚本：调一次工具 → 给出结论 → 收尾。"""
    return finishing(call, final(draft))


class SleepingModel:
    def __init__(self, seconds: float) -> None:
        self.seconds = seconds
        self.calls = 0

    async def complete(self, messages, tools, *, final: bool = False):
        self.calls += 1
        await asyncio.sleep(self.seconds)
        return final("太慢了"), {}


class BoomModel:
    def __init__(self, error: Exception) -> None:
        self.error = error
        self.calls = 0

    async def complete(self, messages, tools, *, final: bool = False):
        self.calls += 1
        raise self.error


# --------------------------------------------------------------------------
# 驱动
# --------------------------------------------------------------------------


async def _drive(
    platform: FakePlatform,
    model: Any,
    *,
    required_tools: frozenset[str] = frozenset(),
    registry: RunRegistry | None = None,
    project_id: str | None = None,
) -> tuple[str, str]:
    session_id = platform.make_session()
    run_id = str(uuid.uuid4())
    async with JavaClient(BASE, transport=platform.transport()) as client:
        await loop_module.run(
            session_id=session_id,
            run_id=run_id,
            utterance=UTTERANCE,
            jwt=TOKEN,
            client=client,
            registry=registry if registry is not None else RunRegistry(),
            model=model,
            required_tools=required_tools,
            project_id=project_id,
        )
    return session_id, run_id


async def test_project_id_is_not_injected_into_tools_that_do_not_declare_it():
    """兜底注入只给**schema 里声明了 project_id** 的工具。

    会议域的 `list_members` 严格校验未知参数：`{}` 通过、多塞 `project_id` 就被判
    `invalid_tool_arguments`（真机实测的回归）。假清单里的 `query_tasks` 也是
    "schema 有 properties 但没有 project_id"，正好钉住这条边界。"""
    platform = FakePlatform()
    model = tool_then_answer(tool_call("c1", "query_tasks", {}))
    await _drive(platform, model, project_id="P007")

    assert platform.calls[-1]["arguments"] == {}, platform.calls[-1]


async def test_project_id_is_injected_into_profile_queries_without_a_schema():
    platform = FakePlatform()
    model = tool_then_answer(tool_call("c1", "query_member_profile", {"userId": 4}))
    await _drive(platform, model, project_id="P007")

    assert platform.calls[-1] == {
        "name": "query_member_profile",
        "arguments": {"userId": 4, "project_id": "P007"},
    }


def _error_of(platform: FakePlatform, run_id: str) -> dict[str, Any]:
    errors = [e for e in platform.events if e["run_id"] == run_id and e["type"] == "run.error"]
    assert errors, f"没有 run.error，实际事件：{platform.event_types(run_id)}"
    return errors[-1]["detail"]


# --------------------------------------------------------------------------
# 事件序列与检查点
# --------------------------------------------------------------------------


async def test_emits_expected_sequence_with_monotonic_seq():
    platform = FakePlatform()
    model = tool_then_answer(tool_call("c1", "query_tasks", {}))
    _, run_id = await _drive(platform, model)

    assert platform.event_types(run_id) == [
        "session.start",
        "model.step",  # step 1：模型要调工具
        "tool.call",
        "tool.result",
        "model.step",  # step 2：模型给出结论
        "model.step",  # 收尾那一次（final）
        "run.end",
    ]
    # seq 就是 agent_events.id：严格递增，且全系统只有这一处产生
    seqs = [e["id"] for e in platform.events if e["run_id"] == run_id]
    assert seqs == sorted(seqs)
    assert len(set(seqs)) == len(seqs)


async def test_every_event_is_persisted_before_it_could_be_streamed():
    """一帧都不能只在内存里飘 —— 事件数 == `POST /events` 的次数。

    落库才算数：SSE 端点是**从 Java 读事件**的，只在内存里发过的帧前端根本收不到。
    """
    platform = FakePlatform()
    model = tool_then_answer(tool_call("c1", "query_tasks", {}))
    await _drive(platform, model)

    assert len(platform.events) == 7
    assert platform.event_types().count("run.error") == 0


async def test_checkpoint_written_at_start_and_after_each_step():
    platform = FakePlatform()
    # 让工具真的耗时一下：`elapsed_seconds` 是跨重启守恒的**时间预算**，
    # 全零的话这条断言证明不了它真的在计量。
    platform.tools["query_tasks"] = lambda args: (time.sleep(0.02), {"items": []})[1]
    model = tool_then_answer(tool_call("c1", "query_tasks", {}))
    _, run_id = await _drive(platform, model)

    state = platform.checkpoint_state(run_id)
    assert state is not None
    # 检查点停在**最后一次已提交的步**，不是终局那一步：终局由 `run.end` 标记，
    # 终局的步不写检查点 —— 写了就等于宣告"这步已经做完"，而它正是那一步。
    assert state["step"] == 1
    assert state["called"] == ["query_tasks"]
    assert state["tool_call_count"] == 1
    # `messages` 里带着已完成的工具结果 —— 这就是"恢复不重跑"的全部依据
    assert any(m.get("role") == "tool" for m in state["messages"])
    assert state["elapsed_seconds"] > 0


async def test_checkpoint_advances_step_by_step_until_the_gate():
    """每步循环末尾都写一次：撞 `step_limit` 的 run，检查点该停在最后一步。"""
    platform = FakePlatform()
    model = ScriptedModel(*[tool_call(f"c{i}", "query_tasks", {}) for i in range(MAX_STEPS)])
    _, run_id = await _drive(platform, model)

    assert platform.checkpoint_state(run_id)["step"] == MAX_STEPS
    assert len(platform.calls) == MAX_STEPS


async def test_checkpoint_exists_even_if_the_very_first_step_fails():
    """第一步就崩的 run 也必须能被 `runs/unfinished` 查到，否则永远不会被恢复。"""
    platform = FakePlatform()
    model = BoomModel(ModelError("provider_unreachable", "连不上模型"))
    _, run_id = await _drive(platform, model)

    assert platform.checkpoint_state(run_id) is not None
    assert _error_of(platform, run_id)["code"] == "provider_unreachable"


async def test_checkpoint_never_carries_the_jwt():
    """JWT 绝不进 `state_json`/`messages`（与红线⑤共享的那条硬约束）。

    状态里带了令牌，就等于把凭据写进了一张按 run 长期保留的表。
    """
    platform = FakePlatform()
    model = tool_then_answer(tool_call("c1", "query_tasks", {}))
    _, run_id = await _drive(platform, model)

    rendered = json.dumps(platform.checkpoint_state(run_id), ensure_ascii=False)
    assert TOKEN not in rendered
    assert "Authorization" not in rendered


async def test_system_prompt_carries_platform_limitations():
    """平台能力边界必须进 prompt（契约 §2.2）—— 不进的话模型会假设平台有它没有的能力。"""
    platform = FakePlatform()
    platform.limitations = ["没有排期概念", "任务无状态字段"]
    model = ScriptedModel(final("ok"))
    await _drive(platform, model)

    system = model.seen[0][0]
    assert system["role"] == "system"
    assert "没有排期概念" in system["content"]
    assert "任务无状态字段" in system["content"]


async def test_conflicting_tool_call_ids_are_still_sent_as_two_frames():
    """工具调用拆成 `tool.call` + `tool.result` 两帧，前端才能画出"进行中"。"""
    platform = FakePlatform()
    model = tool_then_answer(tool_call("c1", "query_member_profile", {"userId": 3}))
    await _drive(platform, model)

    call = next(e for e in platform.events if e["type"] == "tool.call")
    result = next(e for e in platform.events if e["type"] == "tool.result")
    assert call["detail"]["name"] == "query_member_profile"
    assert call["detail"]["arguments"] == {"userId": 3}
    assert result["detail"]["ok"] is True
    assert call["id"] < result["id"]


async def test_oversized_tool_observation_is_truncated_before_entering_messages():
    """超长工具观测必须截断再进 `messages`：不截的话每步 upsert 一次检查点就是写放大。"""
    platform = FakePlatform()
    platform.tools["query_tasks"] = lambda args: {"blob": "长" * 50000}
    model = tool_then_answer(tool_call("c1", "query_tasks", {}))
    _, run_id = await _drive(platform, model)

    tool_message = next(m for m in platform.checkpoint_state(run_id)["messages"] if m.get("role") == "tool")
    assert len(tool_message["content"]) <= 8000 + len("…（已截断）")
    assert tool_message["content"].endswith("…（已截断）")


# --------------------------------------------------------------------------
# 接地闸
# --------------------------------------------------------------------------


async def test_grounding_gate_nudges_before_accepting_an_ungrounded_answer():
    """要求的工具一次都没调过就想给结论 → 先催一次，不接受没有事实依据的结论。"""
    platform = FakePlatform()
    model = finishing(final("我觉得还行"), tool_call("c1", "query_tasks", {}), final("结论"))
    await _drive(platform, model, required_tools=frozenset({"query_tasks"}))

    nudged = any(
        m.get("role") == "user" and "query_tasks" in (m.get("content") or "")
        for m in model.seen[1]
    )
    assert nudged, "第二轮该带上催调工具的提醒"
    assert platform.calls == [{"name": "query_tasks", "arguments": {}}]


async def test_grounding_gate_stays_out_of_the_way_when_tools_were_called():
    platform = FakePlatform()
    model = tool_then_answer(tool_call("c1", "query_tasks", {}))
    await _drive(platform, model, required_tools=frozenset({"query_tasks"}))

    assert platform.event_types().count("run.error") == 0


async def test_without_required_tools_the_gate_is_inactive():
    """默认空集 —— 主管的"该查什么"属 S5′，这里只留机制。"""
    platform = FakePlatform()
    model = finishing(final("直接给结论"))
    await _drive(platform, model)

    assert platform.event_types().count("run.error") == 0
    assert model.calls == 2, "一次给结论、一次收尾，中间不该被催"


# --------------------------------------------------------------------------
# 四道闸
# --------------------------------------------------------------------------


async def test_gate_step_limit():
    platform = FakePlatform()
    model = ScriptedModel(*[tool_call(f"c{i}", "query_tasks", {}) for i in range(MAX_STEPS)])
    await _drive(platform, model)

    # 撞闸前把每一轮都真的跑完了
    assert model.calls == MAX_STEPS


async def test_gate_tool_calls_per_turn():
    platform = FakePlatform()
    model = ScriptedModel(many_calls("c", MAX_CALLS_PER_TURN + 1))
    _, run_id = await _drive(platform, model)

    assert _error_of(platform, run_id)["code"] == "tool_limit"
    assert platform.calls == [], "单轮过多时应该一个工具都还没执行"


async def test_gate_total_tool_calls():
    platform = FakePlatform()
    model = ScriptedModel(*[many_calls(f"r{i}", 5) for i in range(MAX_STEPS)])
    _, run_id = await _drive(platform, model)

    assert _error_of(platform, run_id)["code"] == "tool_limit"
    assert len(platform.calls) <= MAX_TOOL_CALLS


async def test_gate_duplicate_tool_call_id():
    platform = FakePlatform()
    model = ScriptedModel(tool_call("c1", "query_tasks", {}), tool_call("c1", "query_tasks", {}))
    _, run_id = await _drive(platform, model)

    assert _error_of(platform, run_id)["code"] == "invalid_tool_call"
    assert len(platform.calls) == 1, "重复编号的那一次不该被执行"


async def test_gate_tool_arguments_not_json():
    platform = FakePlatform()
    model = ScriptedModel(raw_call("c1", "{不是 JSON"))
    _, run_id = await _drive(platform, model)

    assert _error_of(platform, run_id)["code"] == "invalid_tool_arguments"
    assert platform.calls == []


async def test_gate_tool_arguments_not_an_object():
    """`"[]"` 是合法 JSON 但不是对象 —— 也得拦，不能塞给 Java 的 @RequestBody。"""
    platform = FakePlatform()
    model = ScriptedModel(raw_call("c1", "[]"))
    _, run_id = await _drive(platform, model)

    assert _error_of(platform, run_id)["code"] == "invalid_tool_arguments"


async def test_gate_run_timeout_interrupts_the_model_call(monkeypatch: pytest.MonkeyPatch):
    """超时必须**真能打断**在跑的调用。

    只做调用前检查的话，一次卡住的模型请求就能把整个预算耗光而循环毫不知情。
    """
    monkeypatch.setattr(loop_module, "MAX_SECONDS", 0.05)
    platform = FakePlatform()
    model = SleepingModel(seconds=5.0)
    _, run_id = await _drive(platform, model)

    assert _error_of(platform, run_id)["code"] == "run_timeout"
    assert model.calls == 1


async def test_step_limit_message_is_reported():
    platform = FakePlatform()
    model = ScriptedModel(*[tool_call(f"c{i}", "query_tasks", {}) for i in range(MAX_STEPS)])
    _, run_id = await _drive(platform, model)

    error = _error_of(platform, run_id)
    assert error["code"] == "step_limit"
    assert str(MAX_STEPS) in error["message"]


# --------------------------------------------------------------------------
# 失败面
# --------------------------------------------------------------------------


async def test_model_error_becomes_run_error_with_its_own_code():
    platform = FakePlatform()
    model = BoomModel(ModelError("provider_timeout", "模型请求超时"))
    _, run_id = await _drive(platform, model)

    error = _error_of(platform, run_id)
    assert error["code"] == "provider_timeout"
    assert platform.event_types(run_id)[-1] == "run.error"


async def test_unexpected_exception_becomes_internal_without_leaking_its_text():
    """兜底 except 也要发 `run.error`：后台任务不能把异常抛出去让 asyncio 吞掉。

    而且**只带异常类型名**，不带 `str(error)` —— 那里面可能混着上游内容。
    """
    platform = FakePlatform()
    model = BoomModel(RuntimeError("SECRET-IN-THE-MESSAGE"))
    _, run_id = await _drive(platform, model)

    error = _error_of(platform, run_id)
    assert error["code"] == "internal"
    assert error["message"] == "RuntimeError"
    assert "SECRET-IN-THE-MESSAGE" not in json.dumps(platform.events, ensure_ascii=False)


async def test_claim_failure_makes_the_run_a_noop():
    """抢不到所有权就什么都不做 —— 两个客户端同时重连也只跑一次。"""
    platform = FakePlatform()
    registry = RunRegistry()
    session_id = platform.make_session()
    assert registry.claim(session_id, "run-other") is True

    async with JavaClient(BASE, transport=platform.transport()) as client:
        await loop_module.run(
            session_id=session_id,
            run_id="run-mine",
            utterance=UTTERANCE,
            jwt=TOKEN,
            client=client,
            registry=registry,
            model=ScriptedModel(final("不该跑到")),
        )

    assert platform.events == [], "抢不到所有权就不该落任何事件"


async def test_finish_releases_the_session():
    platform = FakePlatform()
    registry = RunRegistry()
    await _drive(platform, ScriptedModel(final("ok")), registry=registry)

    assert registry.active_run(platform.events[0]["session_id"]) is None
