"""内存假 Java 平台 —— 编排层用到的端点 + 门禁 + 工具调用的可编程替身。

**为什么不用真 Java**：`test_chat_stream.py` 要断言"进程重启后从检查点接着跑"。
那需要在同一个测试里造出"重启前崩掉的库状态"，再让一个**全新的 app**（空
`RunRegistry`）去读它。真 Java 做不到这种精确摆位；而这份替身的 store 可以在
两个 app 实例之间共享 —— 于是"状态在 Java、不在编排层内存里"从一个设计声明
变成一条可断言的测试事实。

行为照抄 java-backend 的实际出参（`AgentEventService` / `AgentCheckpointService`
的 record），**包括两处容易走样的语义**：

- `after_seq` 是**严格大于**；
- 未完成 run = 有检查点 ∧ 无 `run.end`/`run.error`，且按 `session_id` 圈定；
- 会话不存在或不属于调用者一律 **404**（不是 403）。
"""

from __future__ import annotations

import copy
import json
import uuid
from datetime import datetime
from typing import Any, Callable

import httpx

from app.loop import DEFAULT_SYSTEM_PROMPT

OWNER_ID = 1

# 照抄 `AgentEventService` 的页大小常量：不传 `limit` 时一页 500 条、上限 1000。
# 替身必须复刻这两个数，否则"编排层有没有真的传小页"根本测不出来 ——
# 一个忽略 `limit` 的假平台会让所有分页断言恒绿。
DEFAULT_EVENT_LIMIT = 500
MAX_EVENT_LIMIT = 1000

# 照抄 `PlatformToolCatalog` 的两枚**现行**触发型工具（`read_only=false`，
# `agent="platform"`，`args_schema.required` 就是派活时的必填参数）。
# `start_profile_analysis` 暂缓（画像域无后台消费者），要测它用
# `FakePlatform.add_trigger_tool()` —— 那也顺便证明"Java 侧加一枚工具，
# 编排层的路由集合零改动就跟着变"。
DEFAULT_TRIGGER_TOOLS: tuple[dict[str, Any], ...] = (
    {
        "name": "start_meeting_analysis",
        "agent": "platform",
        "description": "触发一次会议分析（产出进会议建议审批队列，需人工确认）。只创建 run。",
        "required": ("meeting_id",),
        "properties": {"meeting_id": {"type": "string", "minLength": 1, "maxLength": 36}},
    },
    {
        "name": "start_plan_draft",
        "agent": "platform",
        "description": "为一句规划请求生成结构化修改草案（草案仍需人工确认）。只创建 run。",
        "required": ("request",),
        "properties": {"request": {"type": "string", "minLength": 1, "maxLength": 2000}},
    },
)

# 归还一个「没有 pending 回复」的假模型会抛这个 —— 它意味着测试脚本写少了，
# 不是被测代码的错，所以要能一眼看出来。
_SCRIPT_EXHAUSTED = "脚本化假模型没有更多回复了（测试脚本给少了）"


def _now() -> str:
    """Java 的 `LocalDateTime.toString()` 是**裸的本地时间**，这里照抄这个形状。"""
    return datetime.now().isoformat(timespec="seconds")


class FakePlatform:
    """假平台。`transport()` 给出的 `MockTransport` 可挂到任意多个 `JavaClient` 上。"""

    def __init__(self, *, jwt: str = "tok-ok") -> None:
        self.jwt = jwt
        self.user = {
            "id": OWNER_ID,
            "username": "Wayne",
            "display_name": "韦恩",
            "role": "member",
            "color": "#357",
            "capacity_hours": 60,
        }
        self.limitations = ["未配置当前 Sprint 日期"]
        self.sessions: dict[str, dict[str, Any]] = {}
        self.events: list[dict[str, Any]] = []
        # 每次 `GET /api/agents/events` 实际生效的页大小（照 Java 的 `cap` 算）
        # 与它带的 `after_seq`。编排层"单次读有界""页边界只前进不回退"两条断言
        # 就靠它们：只有真读了多页，这里才会出现多个条目。
        self.event_read_limits: list[int] = []
        self.event_read_after: list[int] = []
        self.checkpoints: dict[tuple[str, str], dict[str, Any]] = {}
        # 工具调用的**全部**记录：断言"恢复时没重跑已完成的工具调用"靠它
        self.calls: list[dict[str, Any]] = []
        self.tools: dict[str, Callable[[dict[str, Any]], dict[str, Any]]] = {}
        # 路由留痕（`POST /api/agents/routing-logs`）落的行
        self.routing_logs: list[dict[str, Any]] = []
        # 想摆出"留痕写不进去"时把它置成 404/500（None = 正常返回 `{"id"}`）
        self.routing_logs_status: int | None = None
        # 想让某枚工具**业务失败**（HTTP 200 + ok=false + 平铺 error_code）时登记它
        self.tool_failures: dict[str, dict[str, Any]] = {}
        # 请求到达顺序（如 `["POST /api/agents/routing-logs", "POST /api/agents/tools/…"]`）——
        # 「plan 之后、派活之前留痕」这条顺序只能靠它断言
        self.trace: list[str] = []
        # 触发型工具（`read_only=false`）是否出现在 catalog 里，以及额外追加的工具
        self.include_triggers = True
        self.extra_tools: list[dict[str, Any]] = []
        self.trigger_results: dict[str, dict[str, Any]] = {}
        self._next_id = 1
        for spec in DEFAULT_TRIGGER_TOOLS:
            self._register_trigger(spec)

    def _register_trigger(self, spec: dict[str, Any]) -> None:
        """照 `PlatformToolCatalog.objectSchema` 的形状发一份参数 schema。"""
        schema: dict[str, Any] = {
            "type": "object",
            "properties": dict(spec.get("properties") or {}),
        }
        required = tuple(spec.get("required") or ())
        if required:
            schema["required"] = list(required)
        self.extra_tools.append(
            {
                "name": spec["name"],
                "agent": spec.get("agent", "platform"),
                "description": spec.get("description", ""),
                "args_schema": schema,
                "args_hint": None,
                "read_only": False,
                "uses_retrieval": False,
            }
        )
        name = spec["name"]
        self.tools.setdefault(name, lambda arguments, _name=name: self.trigger_result(_name))

    def add_trigger_tool(
        self,
        name: str,
        *,
        required: tuple[str, ...] = (),
        description: str = "触发型工具",
        agent: str = "platform",
    ) -> None:
        """往 catalog 里添一枚触发型工具。

        编排层**不认识**这枚工具（没有一行代码提到它的名字）—— 只要 Java 侧把它发出来，
        下一轮 `plan()` 的可用路由里就多一条。这正是"新增工具只改 Java、Python 零改动"。
        """
        self._register_trigger(
            {
                "name": name,
                "agent": agent,
                "description": description,
                "required": required,
                "properties": {field: {"type": "string"} for field in required},
            }
        )

    def trigger_result(self, name: str) -> dict[str, Any]:
        """触发型工具的出参（契约 §2.4）：`{run_id, deduplicated, status}`。"""
        return dict(
            self.trigger_results.get(name)
            or {"run_id": f"run-{name}-1", "deduplicated": False, "status": "queued"}
        )

    def add_read_only_tool(
        self,
        name: str,
        *,
        result: dict[str, Any] | None = None,
        description: str = "只读工具",
        agent: str = "meeting",
        properties: dict[str, Any] | None = None,
        required: tuple[str, ...] = (),
    ) -> None:
        """往 catalog 里添一枚**只读**工具（它不会变成路由）。

        `list_meetings` 这类工具由编排层在规划相主动调用，所以它必须出现在 catalog 里
        才会被 `plan()` 用到；`read_only=True` 也顺便证明"只读工具不会成为派活路由"。
        """
        schema: dict[str, Any] = {"type": "object", "properties": properties or {}}
        if required:
            schema["required"] = list(required)
        self.extra_tools.append(
            {
                "name": name,
                "agent": agent,
                "description": description,
                "args_schema": schema,
                "args_hint": None,
                "read_only": True,
                "uses_retrieval": False,
            }
        )
        payload = result if result is not None else {"items": []}
        self.tools.setdefault(name, lambda arguments, _payload=payload: copy.deepcopy(_payload))

    # ------------------------------------------------------------------
    # 测试用的摆位工具
    # ------------------------------------------------------------------

    def make_session(self, *, session_id: str | None = None, user_id: int = OWNER_ID) -> str:
        sid = session_id or str(uuid.uuid4())
        self.sessions[sid] = {"user_id": user_id, "title": None, "created_at": _now(), "updated_at": _now()}
        return sid

    def add_event(self, session_id: str, run_id: str, type: str, detail: dict[str, Any] | None = None) -> int:
        seq = self._next_id
        self._next_id += 1
        self.events.append(
            {
                "id": seq,
                "session_id": session_id,
                "run_id": run_id,
                "type": type,
                "detail": detail or {},
                "created_at": _now(),
            }
        )
        return seq

    def put_checkpoint(self, run_id: str, state: dict[str, Any], node_name: str = "loop") -> None:
        self.checkpoints[(run_id, node_name)] = {"state": copy.deepcopy(state), "created_at": _now()}

    def checkpoint_state(self, run_id: str, node_name: str = "loop") -> dict[str, Any] | None:
        row = self.checkpoints.get((run_id, node_name))
        return copy.deepcopy(row["state"]) if row else None

    def event_types(self, run_id: str | None = None) -> list[str]:
        return [e["type"] for e in self.events if run_id is None or e["run_id"] == run_id]

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self._handle)

    # ------------------------------------------------------------------
    # 路由
    # ------------------------------------------------------------------

    def _handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/health":
            return _json(200, {"status": "ok"})

        token = (request.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
        if token != self.jwt:
            return _json(401, {"detail": "未认证"})

        # 过了门禁就记一笔到达顺序 —— 「留痕先于派活」这类顺序断言靠它
        self.trace.append(f"{request.method} {path}")

        if path == "/api/auth/me":
            return _json(200, self.user)
        if path == "/api/agents/catalog":
            return _json(200, self._catalog())
        if path == "/api/agents/sessions":
            return self._sessions(request)
        if path == "/api/agents/events":
            return self._events(request)
        if path == "/api/agents/checkpoints":
            return self._checkpoints(request)
        if path == "/api/agents/runs/unfinished":
            return self._unfinished(request)
        if path == "/api/agents/routing-logs":
            return self._routing_logs(request)
        if path.startswith("/api/agents/tools/"):
            return self._tool(request, path.rsplit("/", 1)[-1])
        return _json(404, {"detail": f"未知端点 {path}"})

    def _catalog(self) -> dict[str, Any]:
        tools: list[dict[str, Any]] = [
            {
                "name": "query_tasks",
                "agent": "platform",
                "description": "查询任务",
                "args_schema": {"type": "object", "properties": {}},
                "args_hint": None,
                "read_only": True,
                "uses_retrieval": False,
            },
            {
                "name": "query_member_profile",
                "agent": "profile",
                "description": "查询成员画像",
                "args_schema": None,
                "args_hint": "参数：userId（必填）",
                "read_only": True,
                "uses_retrieval": False,
            },
        ]
        if self.include_triggers:
            tools.extend(copy.deepcopy(self.extra_tools))
        return {
            "platform_version": "1.0",
            "tools": tools,
            "limitations": list(self.limitations),
        }

    def _routing_logs(self, request: httpx.Request) -> httpx.Response:
        """`POST /api/agents/routing-logs`（契约 §2.7）：`Roles.any()` + 会话归属。

        归属校验与会话/事件同源：会话不存在**或**不属于调用者一律 **404**。
        """
        body = json.loads(request.content or b"{}")
        if not self._owned(body.get("session_id")):
            return _json(404, {"detail": "会话不存在"})
        if self.routing_logs_status is not None:
            return _json(self.routing_logs_status, {"detail": "留痕服务暂时不可用"})
        self.routing_logs.append(body)
        return _json(200, {"id": len(self.routing_logs)})

    def routing_log_for(self, run_id: str) -> dict[str, Any] | None:
        for row in self.routing_logs:
            if row.get("run_id") == run_id:
                return dict(row)
        return None

    def _sessions(self, request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            sid = self.make_session()
            return _json(200, {"session_id": sid})
        mine = [s for s in self.sessions.items() if s[1]["user_id"] == OWNER_ID]
        return _json(
            200,
            [
                {
                    "session_id": sid,
                    "title": row["title"],
                    "created_at": row["created_at"],
                    "updated_at": row["updated_at"],
                }
                for sid, row in mine
            ],
        )

    def _events(self, request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            body = json.loads(request.content or b"{}")
            sid, run_id = body.get("session_id"), body.get("run_id")
            if not self._owned(sid):
                return _json(404, {"detail": "会话不存在"})
            seq = self.add_event(sid, run_id, body["type"], body.get("detail") or {})
            self.sessions[sid]["updated_at"] = _now()
            return _json(200, {"id": seq})

        sid = request.url.params.get("session_id")
        if not self._owned(sid):
            return _json(404, {"detail": "会话不存在"})
        after = int(request.url.params.get("after_seq") or 0)
        # 与 `AgentEventService.listEvents` 同一套口径：
        # `limit` 缺省或 <=0 → DEFAULT_LIMIT，否则 min(limit, MAX_LIMIT)。
        raw_limit = request.url.params.get("limit")
        limit = int(raw_limit) if raw_limit else None
        cap = DEFAULT_EVENT_LIMIT if limit is None or limit <= 0 else min(limit, MAX_EVENT_LIMIT)
        self.event_read_limits.append(cap)
        self.event_read_after.append(after)
        rows = [e for e in self.events if e["session_id"] == sid and e["id"] > after]
        page = sorted(rows, key=lambda e: e["id"])[:cap]
        return _json(200, [copy.deepcopy(r) for r in page])

    def _checkpoints(self, request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            body = json.loads(request.content or b"{}")
            sid, run_id = body.get("session_id"), body.get("run_id")
            if not self._owned(sid):
                return _json(404, {"detail": "会话不存在"})
            foreign = any(e["run_id"] == run_id and e["session_id"] != sid for e in self.events)
            if foreign:
                return _json(404, {"detail": "会话不存在"})
            self.put_checkpoint(run_id, body.get("state") or {}, body.get("node_name") or "loop")
            return _json(200, {"id": 1})

        run_id = request.url.params.get("run_id")
        rows = [
            {"run_id": rid, "node_name": node, "state": copy.deepcopy(row["state"]), "created_at": row["created_at"]}
            for (rid, node), row in sorted(self.checkpoints.items())
            if rid == run_id
        ]
        return _json(200, rows)

    def _unfinished(self, request: httpx.Request) -> httpx.Response:
        sid = request.url.params.get("session_id")
        if not self._owned(sid):
            return _json(404, {"detail": "会话不存在"})
        terminal: set[str] = set()
        seen: set[str] = set()
        for event in self.events:
            if event["session_id"] != sid:
                continue
            seen.add(event["run_id"])
            if event["type"] in ("run.end", "run.error"):
                terminal.add(event["run_id"])
        out = []
        for run_id in sorted(seen):
            if run_id in terminal:
                continue
            row = self.checkpoints.get((run_id, "loop"))
            if row is None:
                continue
            out.append(
                {
                    "run_id": run_id,
                    "session_id": sid,
                    "node_name": "loop",
                    "state": copy.deepcopy(row["state"]),
                    "created_at": row["created_at"],
                }
            )
        return _json(200, out)

    def _tool(self, request: httpx.Request, name: str) -> httpx.Response:
        arguments = json.loads(request.content or b"{}")
        self.calls.append({"name": name, "arguments": arguments})
        failure = self.tool_failures.get(name)
        if failure is not None:
            # 契约 §2.3：业务失败 = HTTP 200 + ok=false + **顶层平铺**的 error_code/error_message
            return _json(
                200,
                {
                    "tool": name,
                    "ok": False,
                    "result": {"error": failure.get("error_message")},
                    "retrieval": None,
                    "limitations": [],
                    "latency_ms": 1,
                    "error_code": failure.get("error_code"),
                    "error_message": failure.get("error_message"),
                },
            )
        handler = self.tools.get(name)
        result = handler(arguments) if handler else {"items": []}
        return _json(
            200,
            {
                "tool": name,
                "ok": True,
                "result": result,
                "retrieval": None,
                "limitations": [],
                "latency_ms": 1,
                "error_code": None,
                "error_message": None,
            },
        )

    def _owned(self, session_id: str | None) -> bool:
        row = self.sessions.get(session_id or "")
        return row is not None and row["user_id"] == OWNER_ID


def _json(status: int, payload: Any) -> httpx.Response:
    return httpx.Response(status, json=payload)


# --------------------------------------------------------------------------
# 假模型
# --------------------------------------------------------------------------


class ScriptedModel:
    """按顺序吐预设回复的假模型，并**记录每次收到的 messages**。

    记录是关键断言手段：恢复路径要证明"接着跑而不是从头跑"，看的就是恢复后第一次
    `complete` 收到的 messages 里已经带着崩之前的工具结果。
    """

    def __init__(self, *responses: dict[str, Any]) -> None:
        self._responses = list(responses)
        self.seen: list[list[dict[str, Any]]] = []
        self.finals: list[bool] = []

    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        *,
        final: bool = False,
    ) -> tuple[dict[str, Any], dict[str, int]]:
        self.seen.append(copy.deepcopy(messages))
        self.finals.append(final)
        if not self._responses:
            raise AssertionError(_SCRIPT_EXHAUSTED)
        return copy.deepcopy(self._responses.pop(0)), {"prompt_tokens": 1, "completion_tokens": 1}

    @property
    def calls(self) -> int:
        return len(self.seen)


class BlockingModel:
    """卡在 `complete` 里的假模型，用来把"run 正在进行中"这个状态稳定地摆出来。

    `content` 可换：S5′ 之后一次 run 的第一发模型调用是**规划相**，所以想让它别卡在
    `invalid_response` 上，就得喂一段合法的规划 JSON（见 `plan_json`）。
    """

    def __init__(self, content: str = '{"answer": "ok"}') -> None:
        self.content = content
        self.started = None  # 由 __init__ 之后的 _lazy() 建，避免构造时就要求 event loop
        self.release = None

    def _events(self) -> tuple[Any, Any]:
        import asyncio

        if self.started is None:
            self.started = asyncio.Event()
            self.release = asyncio.Event()
        return self.started, self.release

    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        *,
        final: bool = False,
    ) -> tuple[dict[str, Any], dict[str, int]]:
        started, release = self._events()
        started.set()
        await release.wait()
        return {"role": "assistant", "content": self.content}, {}


# --------------------------------------------------------------------------
# 回复构造器
# --------------------------------------------------------------------------


def tool_call(call_id: str, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    return {
        "role": "assistant",
        "content": None,
        "tool_calls": [
            {
                "id": call_id,
                "type": "function",
                "function": {"name": name, "arguments": json.dumps(arguments, ensure_ascii=False)},
            }
        ],
    }


def final(content: str) -> dict[str, Any]:
    return {"role": "assistant", "content": content}


# --------------------------------------------------------------------------
# 规划相（S5′）的模型回复
# --------------------------------------------------------------------------

MEETING_ROUTE = "start_meeting_analysis"
PLAN_DRAFT_ROUTE = "start_plan_draft"


def plan_payload(**overrides: Any) -> dict[str, Any]:
    """一份**合法**的规划提案（契约 §5.4 的六字段）。

    默认是"高置信度派活"：`start_meeting_analysis` + 必填参数齐全。用例只改它关心的
    那几项（如 `route=None, confidence=0.41` 摆出反问）。
    """
    payload: dict[str, Any] = {
        "intent": "start_meeting",
        "route": MEETING_ROUTE,
        "confidence": 0.92,
        "reason": "用户要求分析这场会议",
        "args": {"meeting_id": "m-1"},
        "clarify": None,
    }
    payload.update(overrides)
    return payload


def plan_json(**overrides: Any) -> str:
    return json.dumps(plan_payload(**overrides), ensure_ascii=False)


def plan_reply(**overrides: Any) -> dict[str, Any]:
    """规划相那一发模型回复（`complete(..., final=True)` 拿到的 message）。"""
    return final(plan_json(**overrides))


def clarify_plan(
    question: str = "要分析哪一场会议？",
    options: tuple[str, ...] = ("最近一次周会", "上一次评审会"),
    **overrides: Any,
) -> dict[str, Any]:
    """反问型提案：`route=None` + 低 confidence + 现成的追问。"""
    base: dict[str, Any] = {"route": None, "confidence": 0.41, "args": {}}
    base.update(overrides)
    base["clarify"] = {"question": question, "options": list(options)}
    return plan_reply(**base)


# --------------------------------------------------------------------------
# "进程被杀"的现场
# --------------------------------------------------------------------------

UTTERANCE = "现在有几个任务在推进？"


def crashed_state(
    session_id: str, run_id: str, *, elapsed: float = 1.0, step: int = 2
) -> dict[str, Any]:
    """崩在 step-2 的检查点：工具批已执行完并落了检查点，进程随后被杀。

    `messages` 里带着崩之前的工具调用与工具结果 —— 恢复时"不重跑已完成的工具调用"
    靠的就是它。
    """
    return {
        "schema_version": 1,
        "run_id": run_id,
        "session_id": session_id,
        "utterance": UTTERANCE,
        "system_prompt": DEFAULT_SYSTEM_PROMPT,
        "step": step,
        "messages": [
            {"role": "system", "content": "（恢复时不该重建：messages 非空即权威）"},
            {"role": "user", "content": UTTERANCE},
            tool_call("c1", "query_tasks", {}),
            {
                "role": "tool",
                "tool_call_id": "c1",
                "content": '{"tool":"query_tasks","ok":true,"result":{"items":[1,2]}}',
            },
        ],
        "called": ["query_tasks"],
        "call_ids": ["c1"],
        "tool_call_count": 1,
        "required_tools": [],
        "elapsed_seconds": elapsed,
    }


def seed_crashed(
    platform: FakePlatform, *, elapsed: float = 1.0, step: int = 2, run_id: str = "run-crashed-1"
) -> tuple[str, str]:
    """摆出"进程被杀"的现场，返回 `(session_id, run_id)`。"""
    session_id = platform.make_session()
    platform.add_event(session_id, run_id, "session.start", {"utterance": UTTERANCE})
    platform.add_event(session_id, run_id, "model.step", {"step": 1, "tool_names": ["query_tasks"]})
    platform.add_event(session_id, run_id, "tool.call", {"name": "query_tasks", "arguments": {}})
    platform.add_event(session_id, run_id, "tool.result", {"name": "query_tasks", "ok": True})
    platform.add_event(session_id, run_id, "model.step", {"step": 2, "tool_names": []})
    platform.put_checkpoint(run_id, crashed_state(session_id, run_id, elapsed=elapsed, step=step))
    # 崩之前那次工具调用真的发生过 —— 记下来，才能断言恢复时**没有**第二次
    platform.calls.append({"name": "query_tasks", "arguments": {}})
    return session_id, run_id
