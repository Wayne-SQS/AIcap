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

from app.java_client import JavaClient
from app.schemas import PlatformProtocolError, PlatformUnavailable

BASE = "http://java.test"


def _transport(handler) -> httpx.MockTransport:
    return httpx.MockTransport(handler)


def _json_transport(status: int, payload: Any) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=payload)

    return _transport(handler)


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
