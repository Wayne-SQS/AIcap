"""`java_client` 对接 Java **实际**响应结构（不以契约文档示例为准）。

这里铺的每条响应都照抄 java-backend 的真实形态：
`PlatformToolService.ToolOutcome`（平铺 `error_code`/`error_message`）、
`PlatformToolCatalog.catalogJson()`（`args_hint` 恒存在）、
`GlobalExceptionHandler`（非 200 走 `{"detail": ...}`）。

全部用 `httpx.MockTransport`，**不需要 Java 在跑**。
"""

from __future__ import annotations

from typing import Any

import httpx
import pytest

from app import config
from app.java_client import JavaClient
from app.schemas import PlatformProtocolError, PlatformUnavailable

BASE = "http://java.test"


def _transport(handler) -> httpx.MockTransport:
    return httpx.MockTransport(handler)


def _json_transport(status: int, payload: Any) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=payload)

    return _transport(handler)


class _Script:
    """按脚本回放应答/异常的 handler，并记录每一发收到的请求。

    元素是 `httpx.Response` 就返回、是异常就抛；脚本用完还继续问就报错 ——
    「测试脚本给少了」必须一眼看出来，否则重试次数断言会变成假的。
    """

    def __init__(self, *steps: Any) -> None:
        self._steps = list(steps)
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if not self._steps:
            raise AssertionError("脚本用完了（测试脚本给少了）")
        step = self._steps.pop(0)
        if isinstance(step, BaseException):
            raise step
        return step

    @property
    def count(self) -> int:
        return len(self.requests)


def _ok(payload: Any, count: int = 1) -> list[httpx.Response]:
    return [httpx.Response(200, json=payload) for _ in range(count)]


def _status(status: int, payload: Any = None, count: int = 1) -> list[httpx.Response]:
    return [httpx.Response(status, json=payload if payload is not None else {}) for _ in range(count)]


CATALOG_PAYLOAD = {
    "platform_version": "1.0",
    "tools": [
        {
            "name": "search_stories",
            "agent": "meeting",
            "description": "按关键词查询真实故事",
            "args_schema": {"type": "object", "properties": {"keyword": {"type": "string"}}},
            "args_hint": None,
            "read_only": True,
            "uses_retrieval": False,
        },
        {
            "name": "query_tasks",
            "agent": "profile",
            "description": "查询任务",
            "args_schema": None,
            "args_hint": "参数：userId（必填）",
            "read_only": True,
            "uses_retrieval": False,
        },
    ],
    "limitations": ["未配置当前 Sprint 日期", "任务无状态字段"],
}


async def test_catalog_parses_actual_shape():
    async with JavaClient(BASE, transport=_json_transport(200, CATALOG_PAYLOAD)) as client:
        catalog = await client.catalog("jwt")

    assert catalog.platform_version == "1.0"
    assert [tool.name for tool in catalog.tools] == ["search_stories", "query_tasks"]
    # args_hint / args_schema 恒存在，缺的那个是 null 而不是缺失
    assert catalog.tools[0].args_hint is None
    assert catalog.tools[0].args_schema is not None
    assert catalog.tools[1].args_schema is None
    assert catalog.tools[1].args_hint == "参数：userId（必填）"
    assert catalog.limitations == ["未配置当前 Sprint 日期", "任务无状态字段"]


async def test_business_failure_uses_flat_error_fields():
    """业务失败 = HTTP 200 + ok=false + **平铺**的 error_code/error_message。"""
    payload = {
        "tool": "search_stories",
        "ok": False,
        "result": {"error": "模型工具参数无效"},
        "retrieval": None,
        "limitations": [],
        "latency_ms": 7,
        "error_code": "invalid_tool_arguments",
        "error_message": "模型工具参数无效",
    }
    async with JavaClient(BASE, transport=_json_transport(200, payload)) as client:
        outcome = await client.call_tool("search_stories", {}, "jwt")

    assert outcome.ok is False
    assert outcome.error_code == "invalid_tool_arguments"
    assert outcome.error_message == "模型工具参数无效"
    assert outcome.latency_ms == 7
    assert outcome.is_failure is True


async def test_profile_tool_hides_failure_behind_ok_true():
    """画像域部分工具缺参时是 ok=true，错误藏在 result.error。

    契约文档把「参数不合法」一律写成 ok=false —— 只看 ok 会把这条当成功。

    实测（2026-10-11，真后端）：`query_member_profile` 与 `query_profile_snapshots`
    空参调用返回 `ok=true` + `result={"error":"userId 必填"}`、`error_code=null`；
    而 `query_tasks` / `query_activities` 空参**不报错**（返回全量）。
    所以这里必须点名那个真会这么干的工具，不能泛化成"画像工具都这样"。
    """
    payload = {
        "tool": "query_member_profile",
        "ok": True,
        "result": {"error": "userId 必填"},
        "retrieval": None,
        "limitations": [],
        "latency_ms": 3,
        "error_code": None,
        "error_message": None,
    }
    async with JavaClient(BASE, transport=_json_transport(200, payload)) as client:
        outcome = await client.call_tool("query_member_profile", {}, "jwt")

    assert outcome.ok is True
    assert outcome.is_failure is True, "result.error 必须被判为失败"


async def test_semantic_search_degradation_is_not_ok_false():
    """检索上游超时降级 → ok=true + retrieval.degraded，必须查 degraded 而非 ok。"""
    payload = {
        "tool": "semantic_search",
        "ok": True,
        "result": {"configured": True, "items": [], "message": None},
        "retrieval": {
            "query": "权限模块",
            "configured": True,
            "vector_store": "qdrant",
            "reranker": "none(降级)",
            "degraded": "rerank_timeout",
            "routes": {"vector": 3, "keyword": 0, "graph": 0},
            "fused": [{"chunk_id": "story:US12:0", "score": 0.03, "stage": "rrf"}],
            "final": [],
            "latency_ms": 41,
            "message": None,
        },
        "limitations": [],
        "latency_ms": 44,
        "error_code": None,
        "error_message": None,
    }
    async with JavaClient(BASE, transport=_json_transport(200, payload)) as client:
        outcome = await client.call_tool("semantic_search", {"q": "权限模块"}, "jwt")

    assert outcome.ok is True
    assert outcome.is_degraded is True


@pytest.mark.parametrize("status", [401, 403, 404, 422, 500])
async def test_protocol_errors_raise_with_status(status: int):
    payload = {"detail": "未知工具: nope"}
    async with JavaClient(BASE, transport=_json_transport(status, payload)) as client:
        with pytest.raises(PlatformProtocolError) as excinfo:
            await client.call_tool("nope", {}, "jwt")

    assert excinfo.value.status == status
    assert "未知工具" in excinfo.value.detail


async def test_non_200_never_echoes_response_body():
    """红线②：非 200 只取 detail，完整响应体绝不进入异常。"""
    payload = {
        "detail": "未知工具: x",
        "secret": "SHOULD-NOT-LEAK",
        "echo": {"authorization": "Bearer leaked"},
    }
    async with JavaClient(BASE, transport=_json_transport(404, payload)) as client:
        with pytest.raises(PlatformProtocolError) as excinfo:
            await client.call_tool("x", {}, "jwt")

    rendered = repr(excinfo.value) + str(excinfo.value)
    assert "SHOULD-NOT-LEAK" not in rendered
    assert "leaked" not in rendered


async def test_non_200_detail_is_truncated():
    payload = {"detail": "长" * 5000}
    async with JavaClient(BASE, transport=_json_transport(500, payload)) as client:
        with pytest.raises(PlatformProtocolError) as excinfo:
            await client.call_tool("x", {}, "jwt")

    assert len(excinfo.value.detail) == 200


async def test_connection_failure_raises_platform_unavailable():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    async with JavaClient(BASE, transport=_transport(handler)) as client:
        with pytest.raises(PlatformUnavailable):
            await client.catalog("jwt")


async def test_health_reports_false_instead_of_raising():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    async with JavaClient(BASE, transport=_transport(handler)) as client:
        assert await client.health() is False


async def test_jwt_travels_in_header_and_identity_never_in_body():
    """身份只来自 JWT：Authorization 头带 token，body 就是工具参数本身。"""
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["authorization"] = request.headers.get("Authorization")
        seen["path"] = request.url.path
        seen["body"] = request.content.decode("utf-8")
        return httpx.Response(
            200,
            json={
                "tool": "query_activities",
                "ok": True,
                "result": {"items": []},
                "retrieval": None,
                "limitations": [],
                "latency_ms": 5,
            },
        )

    async with JavaClient(BASE, transport=_transport(handler)) as client:
        await client.call_tool("query_activities", {"userId": 42}, "tok-abc")

    assert seen["authorization"] == "Bearer tok-abc"
    assert seen["path"] == "/api/agents/tools/query_activities"
    # userId 是**查询目标**，原样透传；它不是身份，身份在头里
    assert seen["body"] == '{"userId":42}'


async def test_me_parses_user():
    payload = {
        "id": 3,
        "username": "liruiming",
        "display_name": "李锐铭",
        "role": "member",
        "color": "#abc",
        "capacity_hours": 60,
    }
    async with JavaClient(BASE, transport=_json_transport(200, payload)) as client:
        user = await client.me("jwt")

    assert user.id == 3
    assert user.display_name == "李锐铭"
    assert user.role == "member"


# ==========================================================================
# 幂等读的有界重试 / 写操作零重试（任务 A）
#
# 重试原语本身在 `test_retry.py`；这里钉的是**哪些端点挂了重试、哪些没有**——
# 这一层的分工才是真正会被改坏的地方。套件级 conftest 把退避压成 0，所以下面
# 每一条都真的跑满 3 次尝试却一次都不真睡。
# ==========================================================================


TOOL_OUTCOME = {
    "tool": "query_tasks",
    "ok": True,
    "result": {"items": []},
    "retrieval": None,
    "limitations": [],
    "latency_ms": 5,
}


async def test_idempotent_read_retries_once_and_then_succeeds():
    """取 catalog：一次瞬时连接失败后重试，用户不该看到半截对话。"""
    script = _Script(httpx.ConnectError("blip"), *_ok(CATALOG_PAYLOAD))

    async with JavaClient(BASE, transport=_transport(script)) as client:
        catalog = await client.catalog("jwt")

    assert script.count == 2
    assert catalog.platform_version == "1.0"


async def test_read_retries_are_bounded_and_then_report_unavailable():
    """Java 真的不在时，发满 3 次（1 + 2）就如实报错 —— 不无限重试。"""
    script = _Script(*[httpx.ConnectError("down") for _ in range(5)])

    async with JavaClient(BASE, transport=_transport(script)) as client:
        with pytest.raises(PlatformUnavailable) as excinfo:
            await client.catalog("jwt")

    assert script.count == 3
    assert "ConnectError" in excinfo.value.message


async def test_tool_call_is_retried_because_the_tool_surface_is_read_only():
    """`POST /api/agents/tools/{name}` 形态是 POST、语义是读。

    `PlatformToolCatalog` 三处 `ToolSpec` 的 `readOnly` 字面量全是 `true`，
    14 个工具无一写业务表（模型产出走审批队列）。所以它是幂等读，可以重试 ——
    工具调用是循环里最频繁的网络往返，不重试等于一次抖动废掉整个 run。
    """
    script = _Script(httpx.ConnectError("blip"), *_ok(TOOL_OUTCOME))

    async with JavaClient(BASE, transport=_transport(script)) as client:
        outcome = await client.call_tool("query_tasks", {}, "jwt")

    assert script.count == 2
    assert outcome.ok is True


_WRITES = [
    (
        "create_session",
        lambda client: client.create_session("jwt"),
    ),
    (
        "append_event",
        lambda client: client.append_event(
            session_id="s", run_id="r", type="model.step", detail={}, jwt="jwt"
        ),
    ),
    (
        "put_checkpoint",
        lambda client: client.put_checkpoint(
            session_id="s", run_id="r", node_name="loop", state={}, jwt="jwt"
        ),
    ),
]


@pytest.mark.parametrize("invoke", [invoke for _, invoke in _WRITES], ids=[name for name, _ in _WRITES])
async def test_write_endpoints_are_never_retried(invoke):
    """写操作零重试 —— 这不是"少一层保护"，是**红线**。

    `append_event` 重发是**真的多一条事件**（`agent_events.id` 自增主键，
    库里分辨不出重复），前端会看到重复的一帧、seq 凭空跳号；
    `put_checkpoint` 重发会把"恰好晚到的那一发"覆盖成先到的那一发，
    而检查点是恢复的唯一根据。
    """
    script = _Script(*[httpx.ConnectError("down") for _ in range(5)])

    async with JavaClient(BASE, transport=_transport(script)) as client:
        with pytest.raises(PlatformUnavailable):
            await invoke(client)

    assert script.count == 1, "写操作重试了一次都不行"


async def test_client_error_is_not_retried():
    """4xx 是确定性应答（令牌过期 / 参数不合法 / 会话不是你的）。

    重发只会拿到同一个 404，代价是把错误推迟 0.6 秒才报出来。
    """
    script = _Script(*_status(404, {"detail": "会话不存在"}, count=5))

    async with JavaClient(BASE, transport=_transport(script)) as client:
        with pytest.raises(PlatformProtocolError) as excinfo:
            await client.list_events(session_id="s", after_seq=0, jwt="jwt")

    assert script.count == 1
    assert excinfo.value.status == 404
    assert excinfo.value.code == "not_found"


async def test_gateway_status_is_retried():
    """502/503/504 是"暂时不可用"（Java 正在重启），是**经典**瞬时故障。"""
    script = _Script(*_status(503, {"detail": "维护中"}), *_ok(CATALOG_PAYLOAD))

    async with JavaClient(BASE, transport=_transport(script)) as client:
        catalog = await client.catalog("jwt")

    assert script.count == 2
    assert catalog.platform_version == "1.0"


async def test_exhausted_gateway_status_still_reports_its_own_status_code():
    """重试预算用尽后，503 必须还原成 `PlatformProtocolError(503)`。

    退化成"无法连接"会把状态码和 Java 的 `detail` 一起丢掉 —— 排障时无从下手。
    """
    script = _Script(*_status(503, {"detail": "维护中"}, count=5))

    async with JavaClient(BASE, transport=_transport(script)) as client:
        with pytest.raises(PlatformProtocolError) as excinfo:
            await client.catalog("jwt")

    assert script.count == 3
    assert excinfo.value.status == 503
    assert excinfo.value.code == "platform_error"
    assert excinfo.value.detail == "维护中"


async def test_non_gateway_5xx_is_not_retried():
    """500 是服务端自己的确定性缺陷，重试只是把同一个失败延迟 0.6 秒报出来。"""
    script = _Script(*_status(500, {"detail": "内部错误"}, count=5))

    async with JavaClient(BASE, transport=_transport(script)) as client:
        with pytest.raises(PlatformProtocolError) as excinfo:
            await client.catalog("jwt")

    assert script.count == 1
    assert excinfo.value.status == 500


async def test_read_timeout_is_not_retried():
    """`ReadTimeout` 点名不重试。

    `JAVA_TIMEOUT` 默认 30s，2 次重试就是 90s —— 循环的总预算才 180s，
    重试会把"一次慢调用"放大成"必然超预算"，比不重试更糟。
    """
    script = _Script(*[httpx.ReadTimeout("slow") for _ in range(5)])

    async with JavaClient(BASE, transport=_transport(script)) as client:
        with pytest.raises(PlatformUnavailable) as excinfo:
            await client.catalog("jwt")

    assert script.count == 1
    assert "ReadTimeout" in excinfo.value.message


async def test_health_probe_is_not_retried():
    """/health 是**探针**：它回答的是"此刻通不通"。

    `main` 里还把它的超时压到 `min(JAVA_TIMEOUT, 5.0)`；重试 2 次会把 5 秒的
    短探针拖成 15 秒，让健康检查自己变成慢的那个。
    """
    script = _Script(*[httpx.ConnectError("down") for _ in range(5)])

    async with JavaClient(BASE, transport=_transport(script)) as client:
        assert await client.health() is False

    assert script.count == 1


# ==========================================================================
# SSE 单轮读的页大小（任务 C）
# ==========================================================================


async def test_list_events_asks_for_a_bounded_page():
    """不传 `limit` 时 Java 一页给 500 条（`AgentEventService.DEFAULT_LIMIT`）。

    编排层显式要一小页：断线久了盘子里可能压着上千条事件，整批读会把它们
    一次性搬进内存，第一帧也要等整批读完才吐得出来。
    """
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(dict(request.url.params))
        return httpx.Response(200, json=[])

    async with JavaClient(BASE, transport=_transport(handler)) as client:
        await client.list_events(session_id="s1", after_seq=7, jwt="jwt")

    assert seen["limit"] == str(config.EVENT_PAGE_LIMIT)
    assert seen["after_seq"] == "7"
    assert seen["session_id"] == "s1"


async def test_event_page_limit_is_read_at_call_time(monkeypatch: pytest.MonkeyPatch):
    """页大小每次调用重读 config —— 否则测试没法把它调小去验分页，运维也没法按负载调。"""
    monkeypatch.setattr(config, "EVENT_PAGE_LIMIT", 3)
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(dict(request.url.params))
        return httpx.Response(200, json=[])

    async with JavaClient(BASE, transport=_transport(handler)) as client:
        await client.list_events(session_id="s1", after_seq=0, jwt="jwt")

    assert seen["limit"] == "3"
