"""有界工具循环 —— 从归档 `backend/app/meeting_agent/runner.py:42-109` 复活。

**剥掉了什么**：归档的循环签名带 `session_factory`（直连库）两条调用点
（L49 取 context、L78 每轮执行工具），这里换成一个 `JavaClient` —— 编排层不持有
DB 连接，这是 `tests/test_no_db.py` 钉死的结构性约束。租约 `ensure_active` 也剥了：
会议分析是异步作业有租约，主管对话是同步交互，没有租约概念。

**没移植什么**：会议域的 `validate_analysis` / `coverage_retry` / `model_repair`
三步校验 —— 它们吃的是会议 prompt 与 `Analysis` schema，主管的**结构化输出契约
属 S5′**。这里只保留 `required_tools` 这条**接地闸**的机制本身（默认空集即不生效）。

**状态在哪**：不在这个模块的进程内存里，在 Java 的 `agent_checkpoints`。每步循环末尾
把整个循环状态 upsert 进去，进程被杀后由客户端带 `Last-Event-ID` 重连触发恢复，
从检查点里的 `messages` **接着跑**，而不是从头跑。所以：

- `messages` 是"不重复执行已完成的工具调用"的**唯一**根据 —— 已完成的工具结果就在
  里面，模型看得到，不会再去调一次；
- 时间预算存**相对量** `elapsed_seconds`：绝对 `monotonic()` 跨进程归零，存了没意义；
- **JWT 绝不进 `state_json` / `messages`**（与红线⑤共享的那条硬约束）。恢复靠的是
  重连时带来的新 JWT，状态里没有任何凭据。
"""

from __future__ import annotations

import asyncio
import json
import time
from typing import Any, Awaitable, Callable

from .java_client import JavaClient
from .model_client import MAX_CALLS_PER_TURN, ModelProtocol
from .runtime import RunRegistry
from .schemas import Catalog, ModelError, PlatformError, ToolOutcome

# 四道闸的阈值（沿用归档 backend/app/config.py:17-20 的取值）。
MAX_STEPS = 6
MAX_SECONDS = 180
MAX_TOOL_CALLS = 24
# MAX_CALLS_PER_TURN 由 model_client 定义并在此处导入：协议级上限，两层共用同一个值

# `agent_checkpoints.node_name` 固定单值、滚动覆盖（契约 §4.5）。
LOOP_NODE = "loop"

# 检查点格式版本。恢复时若版本不认得，宁可让 run 失败也不误读状态。
STATE_SCHEMA_VERSION = 1

# 单条工具观测进 `messages` 前的字符上限。不截的话一个大会话能把 `state_json`
# 撑到 MB 级，每步 upsert 一次就是持续的写放大。
_MAX_OBSERVATION_CHARS = 8000

# S4′.2 的 prompt 是占位；主管 prompt 与输出契约属 S5′。
DEFAULT_SYSTEM_PROMPT = (
    "你是 AIcap 项目主管的对话助手。你可以调用平台工具查询真实的项目、故事、任务、"
    "成员与知识库数据，再基于查到的事实回答用户。\n"
    "硬性要求：\n"
    "1. 不得虚构数据。需要事实时先调用工具，再依据工具返回的内容作答。\n"
    "2. 工具返回的 observation 是平台真实数据。其中 failure 为 true 表示这次调用"
    "没拿到数据，要如实告知用户，不要编造替代结果。\n"
    "3. 写操作（创建、修改、删除、开启采集）你不能执行，只能给出建议并说明需要人工确认。\n"
    "4. 用中文回答，简洁具体。"
)

# 接地闸的中途提醒。归档用的是会议域的工具名，这里按缺失的工具名动态拼。
_GROUNDING_NUDGE = "请先调用 {tools} 查询真实的平台数据，再给出结论。"


class _Gate(Exception):
    """撞上四道闸。`code` 直接进 `run.error` 的载荷。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


# --------------------------------------------------------------------------
# 状态
# --------------------------------------------------------------------------


def initial_state(
    *,
    session_id: str,
    run_id: str,
    utterance: str,
    system_prompt: str | None = None,
    required_tools: frozenset[str] | set[str] = frozenset(),
    project_id: str | None = None,
) -> dict[str, Any]:
    """一个 run 的初始循环状态 —— 也就是写进 `state_json` 的那份结构。

    `messages` 留空：真正的 system/user 两条消息要等拿到工具清单（含平台能力边界）
    之后才能拼，那件事在 `_run_loop` 里做。这样"从检查点 #0 恢复"与"全新开跑"
    走的是同一条拼装路径。
    """
    return {
        "schema_version": STATE_SCHEMA_VERSION,
        "run_id": run_id,
        "session_id": session_id,
        "utterance": utterance,
        "system_prompt": system_prompt or DEFAULT_SYSTEM_PROMPT,
        "step": 0,
        "messages": [],
        "called": [],
        "call_ids": [],
        "tool_call_count": 0,
        "required_tools": sorted(required_tools),
        # 当前项目上下文。放进 state 而不是只放请求里:检查点恢复要把同一上下文带回来,
        # 否则"崩了重连接着跑"的那一半会退化成"模型又开始猜 project_id"。
        "project_id": project_id,
        "elapsed_seconds": 0.0,
    }


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------


async def run(
    *,
    session_id: str,
    run_id: str,
    utterance: str,
    jwt: str,
    client: JavaClient,
    registry: RunRegistry,
    model: ModelProtocol,
    system_prompt: str | None = None,
    required_tools: frozenset[str] | set[str] = frozenset(),
    pre_loop: Callable[[], Awaitable[bool]] | None = None,
    project_id: str | None = None,
) -> None:
    """开一个新 run（`POST /chat` 起它，作为独立后台任务）。

    `pre_loop` 是**唯一**的循环前置钩子（S5′ 加的，默认 `None` 即老语义）：它在
    `registry.claim` **之内**、`session.start` 与检查点 #0 **之后**执行 —— 主管的规划相
    （`main._plan_phase`：plan → 留痕 → 反问/派活）挂在这里。为什么非得在这个窗口里：

    - 规划要花一次模型往返（秒级）。放在 `claim` 之前，`/chat` 的 409「同一会话只有一个
      run」就出现真空窗口，两次点击能同时起两个 run；
    - `session.start` 必须是这个 run 的第一条事件（`tests/test_chat.py` 与前端都这么认），
      所以钩子排在它后面。

    返回 `True` 表示可以进工具循环；返回 `False` 表示本轮已经终局（钩子自己发过
    `run.end` / `run.error`），循环不再跑。**`resume()` 不传这个钩子** —— 崩溃恢复是
    接着跑，不是重新规划。
    """
    state = initial_state(
        session_id=session_id,
        run_id=run_id,
        utterance=utterance,
        system_prompt=system_prompt,
        required_tools=required_tools,
        project_id=project_id,
    )

    async def on_claim() -> None:
        # 先 `session.start`、紧接着写检查点 #0：保证"已开始的 run 必有 ≥1 检查点"，
        # 否则第一步就崩的 run 在 `runs/unfinished` 里查不到，永远不会被恢复。
        await _emit(
            client,
            registry,
            session_id=session_id,
            run_id=run_id,
            jwt=jwt,
            type="session.start",
            data={"utterance": utterance},
        )
        await _save_checkpoint(client, state, jwt)

    await _drive(
        state=state,
        jwt=jwt,
        client=client,
        registry=registry,
        model=model,
        on_claim=on_claim,
        pre_loop=pre_loop,
    )


async def resume(
    *,
    state: dict[str, Any],
    jwt: str,
    client: JavaClient,
    registry: RunRegistry,
    model: ModelProtocol,
) -> None:
    """接着跑一个未完成的 run（`GET /chat/stream` 惰性恢复时起它）。

    `state` 来自 `GET /api/agents/runs/unfinished` 的 `state`。**不发 `session.start`**
    —— 它已经发过了，重发会让前端以为新开了一轮。终局也不重写检查点。
    """
    await _drive(state=state, jwt=jwt, client=client, registry=registry, model=model)


async def _drive(
    *,
    state: dict[str, Any],
    jwt: str,
    client: JavaClient,
    registry: RunRegistry,
    model: ModelProtocol,
    on_claim: Callable[[], Awaitable[None]] | None = None,
    pre_loop: Callable[[], Awaitable[bool]] | None = None,
) -> None:
    session_id = str(state.get("session_id") or "")
    run_id = str(state.get("run_id") or "")
    if not session_id or not run_id:
        return
    # 所有权交接必须是同步的（见 runtime.RunRegistry.claim）：两个客户端同时重连时
    # 只有一个拿到 True，另一个直接返回，不会把同一个 run 跑两遍。
    if not registry.claim(session_id, run_id):
        return
    try:
        if on_claim is not None:
            await on_claim()
        # 规划相（只有新 run 有；`resume` 不传）：过不了就**不进循环** ——
        # 终局事件由钩子自己发（反问是 run.end，失败是 run.error）。
        if pre_loop is not None and not await pre_loop():
            return
        await _run_loop(state=state, jwt=jwt, client=client, registry=registry, model=model)
    except Exception:
        # 后台任务不能把异常抛出去：asyncio 只会在退出时记一句
        # "Task exception was never retrieved" 然后吞掉，SSE 那边毫无察觉。
        # 唤醒一次让等待者重读 —— 库里的 run 仍停在"未完成"，下次重连可恢复。
        registry.notify(session_id)
    finally:
        registry.finish(session_id, run_id)


# --------------------------------------------------------------------------
# 循环本体
# --------------------------------------------------------------------------


async def _run_loop(
    *,
    state: dict[str, Any],
    jwt: str,
    client: JavaClient,
    registry: RunRegistry,
    model: ModelProtocol,
) -> None:
    session_id = state["session_id"]
    run_id = state["run_id"]
    # 相对时间预算：检查点里存的是"已经用掉多少秒"，本进程据此重建绝对 deadline。
    # 存绝对 monotonic() 的话，跨进程归零、毫无意义。
    base_elapsed = float(state.get("elapsed_seconds") or 0.0)
    local_start = time.monotonic()

    async def emit(type_: str, data: dict[str, Any]) -> int:
        return await _emit(
            client, registry, session_id=session_id, run_id=run_id, jwt=jwt, type=type_, data=data
        )

    def used() -> float:
        return base_elapsed + (time.monotonic() - local_start)

    async def save() -> None:
        state["called"] = sorted(called)
        state["call_ids"] = sorted(call_ids)
        state["tool_call_count"] = total_calls
        state["elapsed_seconds"] = round(used(), 3)
        await _save_checkpoint(client, state, jwt)

    try:
        if used() >= MAX_SECONDS:
            raise _Gate("run_timeout", f"恢复时预算已耗尽（已用 {base_elapsed:.0f}s / {MAX_SECONDS}s）")

        catalog = await client.catalog(jwt)
        if not state.get("messages"):
            state["messages"] = [
                {
                    "role": "system",
                    "content": _prompt_with_limits(state["system_prompt"], catalog.limitations)
                    + _project_line(state.get("project_id")),
                },
                {"role": "user", "content": state["utterance"]},
            ]
        schemas = function_schemas(catalog)

        messages: list[dict[str, Any]] = state["messages"]
        project_id: str | None = state.get("project_id")
        called: set[str] = set(state.get("called") or ())
        call_ids: set[str] = set(state.get("call_ids") or ())
        total_calls = int(state.get("tool_call_count") or 0)
        required: frozenset[str] = frozenset(state.get("required_tools") or ())

        for step in range(int(state.get("step") or 0), MAX_STEPS):
            state["step"] = step + 1
            response, usage = await _model_step(model, messages, schemas, used, final=False)
            calls = response.get("tool_calls") or []
            messages.append(response)
            await emit(
                "model.step",
                {
                    "step": step + 1,
                    "usage": usage,
                    "tool_names": [call["function"]["name"] for call in calls],
                },
            )

            if not calls:
                # 接地闸：模型想直接给结论，但要求的工具一次都没调过 —— 不接受
                # 没有事实依据的结论，催一次再继续（消耗一步）。
                if required and not required.issubset(called):
                    missing = "、".join(sorted(required - called))
                    messages.append({"role": "user", "content": _GROUNDING_NUDGE.format(tools=missing)})
                    await save()
                    continue
                final, usage = await _model_step(model, messages, [], used, final=True)
                messages.append(final)
                await emit("model.step", {"step": step + 1, "usage": usage, "final": True})
                # 终局不写检查点：run 是否结束由 run.end/run.error 事件标记，
                # 检查点只描述"循环跑到哪了"。
                await emit(
                    "run.end",
                    {
                        "status": "ok",
                        "steps": step + 1,
                        "tool_calls": total_calls,
                        "elapsed_ms": int(used() * 1000),
                        # 结构化输出契约属 S5′；这里只是把模型最后那段文本原样带出去，
                        # 前端至少有的可显示，本层不假装它已经符合某个 schema。
                        "output": final.get("content") or "",
                    },
                )
                return

            if len(calls) > MAX_CALLS_PER_TURN:
                raise _Gate("tool_limit", f"单轮工具调用过多（{len(calls)} > {MAX_CALLS_PER_TURN}）")

            for call in calls:
                if used() >= MAX_SECONDS:
                    raise _Gate("run_timeout", "run 超过时限")
                call_id = str(call["id"])
                # 编号去重：模型重复同一个 call_id 意味着上下文已经错乱，
                # 继续跑只会把同一批写操作做两遍。
                if call_id in call_ids:
                    raise _Gate("invalid_tool_call", "模型重复了工具调用编号")
                call_ids.add(call_id)
                total_calls += 1
                if total_calls > MAX_TOOL_CALLS:
                    raise _Gate("tool_limit", f"工具调用次数超过上限（{MAX_TOOL_CALLS}）")

                name = call["function"]["name"]
                try:
                    arguments = json.loads(call["function"]["arguments"])
                except (TypeError, ValueError):
                    raise _Gate("invalid_tool_arguments", "模型工具参数不是 JSON") from None
                if not isinstance(arguments, dict):
                    raise _Gate("invalid_tool_arguments", "模型工具参数不是 JSON 对象")
                # 当前项目兜底注入:模型漏传 project_id 时补上(服务端读 args 并忽略多余字段)。
                # 模型自己给了值就以它为准 —— 它可能被要求操作别的项目。
                if project_id and not arguments.get("project_id") and not arguments.get("projectId"):
                    arguments = {**arguments, "project_id": project_id}

                await emit("tool.call", {"name": name, "arguments": arguments})
                outcome = await _invoke(client, name, arguments, jwt, used)
                called.add(name)
                observation = _observation(outcome)
                await emit(
                    "tool.result",
                    {
                        "name": name,
                        "ok": outcome.ok,
                        "failure": outcome.is_failure,
                        "error_code": outcome.error_code,
                        "latency_ms": outcome.latency_ms,
                        "observation": observation,
                    },
                )
                messages.append({"role": "tool", "tool_call_id": call_id, "content": observation})

            # 一步做完（工具批已执行并 append 完）才落检查点：这就是"不重复执行已完成
            # 的工具调用"的边界 —— 半途被杀，恢复时从上一个已提交的步继续。
            await save()
            continue

        raise _Gate("step_limit", f"循环达到轮数上限（{MAX_STEPS}），未产出结果")

    except _Gate as gate:
        await _terminal(emit, registry, session_id, gate.code, gate.message, state, int(used() * 1000))
    except ModelError as error:
        await _terminal(emit, registry, session_id, error.code, error.message, state, int(used() * 1000))
    except PlatformError as error:
        await _terminal(emit, registry, session_id, error.code, error.message, state, int(used() * 1000))
    except Exception as error:  # 兜底：后台任务绝不能带崩进程
        await _terminal(
            emit, registry, session_id, "internal", type(error).__name__, state, int(used() * 1000)
        )


# --------------------------------------------------------------------------
# 辅助
# --------------------------------------------------------------------------


def function_schemas(catalog: Catalog) -> list[dict[str, Any]]:
    """把平台能力清单翻成 OpenAI function-calling 的 tools 数组。

    画像域工具只有 `args_hint` 没有 `args_schema`（契约 §2.1），所以参数说明要拼进
    `description`，否则模型根本不知道该传什么。
    """
    schemas: list[dict[str, Any]] = []
    for tool in catalog.tools:
        description = tool.description
        if tool.args_hint:
            description = f"{description}（{tool.args_hint}）"
        schemas.append(
            {
                "type": "function",
                "function": {
                    "name": tool.name,
                    "description": description,
                    "parameters": tool.args_schema or {"type": "object", "properties": {}},
                },
            }
        )
    return schemas


def _project_line(project_id: str | None) -> str:
    """当前项目上下文一行。多项目之后工具调用普遍要 project_id，
    不告诉模型它只能猜（实测：读查询猜中过，触发型工具漏传就被平台拒）。"""
    if not project_id:
        return ""
    return ("\n\n当前项目：project_id = " + project_id
            + "。调用任何需要 project_id 的工具时都必须用它，不要另猜、也不要省略。")


def _prompt_with_limits(system_prompt: str, limitations: list[str]) -> str:
    """工具清单里的 `limitations` 必须进 prompt（契约 §2.2）。

    不带的后果不是"少说一句"，是模型会假设平台有它没有的能力，然后按那个假设作答。
    """
    if not limitations:
        return system_prompt
    lines = "\n".join(f"- {item}" for item in limitations)
    return f"{system_prompt}\n\n平台能力边界（必须遵守，不得假设超出范围的能力）：\n{lines}"


def _observation(outcome: ToolOutcome) -> str:
    """工具结果 → 喂回模型的一段 JSON 文本，超长截断。

    `failure` 直接把 `ToolOutcome.is_failure` 摊出来：画像域有工具参数不合法时
    `ok=true`、错误藏在 `result.error`，只给 `ok` 会骗过模型。
    """
    payload = {
        "tool": outcome.tool,
        "ok": outcome.ok,
        "failure": outcome.is_failure,
        "result": outcome.result,
        "error_code": outcome.error_code,
        "error_message": outcome.error_message,
        "retrieval_degraded": (
            outcome.retrieval.get("degraded") if isinstance(outcome.retrieval, dict) else None
        ),
        "limitations": outcome.limitations,
    }
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), default=str)
    if len(text) <= _MAX_OBSERVATION_CHARS:
        return text
    return text[:_MAX_OBSERVATION_CHARS] + "…（已截断）"


async def _model_step(
    model: ModelProtocol,
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]],
    used: Callable[[], float],
    *,
    final: bool,
) -> tuple[dict[str, Any], dict[str, int]]:
    """一次模型调用，用剩余预算做硬超时。

    `wait_for` 是"真能打断"的关键：只做调用前检查的话，一次卡住的模型请求能把
    整个预算耗光而循环毫不知情。
    """
    left = MAX_SECONDS - used()
    if left <= 0:
        raise _Gate("run_timeout", "run 超过时限")
    try:
        return await asyncio.wait_for(model.complete(messages, tools, final=final), left)
    except TimeoutError:
        raise _Gate("run_timeout", "run 超过时限") from None


async def _invoke(
    client: JavaClient,
    name: str,
    arguments: dict[str, Any],
    jwt: str,
    used: Callable[[], float],
) -> ToolOutcome:
    left = MAX_SECONDS - used()
    if left <= 0:
        raise _Gate("run_timeout", "run 超过时限")
    try:
        return await asyncio.wait_for(client.call_tool(name, arguments, jwt), left)
    except TimeoutError:
        raise _Gate("run_timeout", "工具调用超过总时限") from None


async def _emit(
    client: JavaClient,
    registry: RunRegistry,
    *,
    session_id: str,
    run_id: str,
    jwt: str,
    type: str,
    data: dict[str, Any],
) -> int:
    """落一条事件：**先落库拿 seq，再唤醒 SSE 等待者**。

    顺序反了就会"唤醒了但读不到"—— 等待者醒来去查 Java，事件还没写进去，
    这一轮空转，要等下一次心跳才补上。
    """
    seq = await client.append_event(
        session_id=session_id, run_id=run_id, type=type, detail=data, jwt=jwt
    )
    registry.notify(session_id)
    return seq


async def _save_checkpoint(client: JavaClient, state: dict[str, Any], jwt: str) -> None:
    await client.put_checkpoint(
        session_id=state["session_id"],
        run_id=state["run_id"],
        node_name=LOOP_NODE,
        state=state,
        jwt=jwt,
    )


async def _terminal(
    emit: Callable[[str, dict[str, Any]], Awaitable[int]],
    registry: RunRegistry,
    session_id: str,
    code: str,
    message: str,
    state: dict[str, Any],
    elapsed_ms: int,
) -> None:
    """发终局事件。**这里再失败就只能放弃**。

    后台任务无处可抛：抛出去会被 asyncio 吞成一句 "Task exception was never retrieved"，
    而且库里的 run 依然停在"未完成"。停在"未完成"是**可恢复**的（下次重连带
    `Last-Event-ID` 重连会接着跑），所以静默收场比再抛一层更安全。
    """
    try:
        await emit(
            "run.error",
            {
                "code": code,
                "message": message,
                "steps": int(state.get("step") or 0),
                "elapsed_ms": elapsed_ms,
            },
        )
    except Exception:
        pass
    finally:
        registry.notify(session_id)
