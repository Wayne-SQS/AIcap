"""唯一 HTTP 出口：所有对 Java 平台的调用都经这里。

依赖方向（契约 §5.2）：`main → orchestrator → loop → java_client → java-backend`。
任何其他模块直接 `httpx` 都算违规 —— 因为只有这里知道「要透传 JWT」和
「要按红线处理上游响应」。

两条铁律：

1. **身份只来自 JWT**。`Authorization: Bearer` 原样透传，参数里不解析、不落任何
   身份字段。刻意**不做**按 key 拦截 `userId` / `asUserId` —— 契约 §2.5 的字面
   措辞与 `query_activities(userId=…)` 直接冲突（那里的 `userId` 是"查询目标"而
   非"调用者身份"），照字面拦会打断画像工具。
2. **红线②：上游非 200 绝不落响应体**。只取 Java 自己的 `detail` 字段并截断，
   任何级别都不 log 响应体。这条继承自已归档的 `ModelClient`（`model_client.py:38-39`）。
"""

from __future__ import annotations

from typing import Any

import httpx

from . import config
from .schemas import (
    AgentCheckpointRow,
    AgentEventRow,
    AgentSessionView,
    AuthUser,
    Catalog,
    PlatformProtocolError,
    PlatformUnavailable,
    ToolOutcome,
    UnfinishedRun,
)

# Java 自己的 detail 文案最长保留多少字符（红线②：宁可少，不可回显整个 body）
_DETAIL_MAX = 200

_JSON_HEADERS = {"Content-Type": "application/json"}

# HTTP 状态 → 编排层语义码。注意这里没有 200：200 是业务层，
# 失败与否看信封里的 ok，不看状态码。
_STATUS_CODES = {
    400: "bad_request",
    401: "unauthenticated",
    403: "forbidden",
    404: "not_found",
    422: "unprocessable",
    500: "platform_error",
    503: "platform_error",
}


def _auth_headers(jwt: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {jwt}"}


def _safe_detail(response: httpx.Response) -> str:
    """只从 Java 的 `{"detail": "..."}` 里取 `detail`，截断后返回。

    任何情况下都不返回原始 body —— 红线②。解析不出 `detail` 时给一句占位，
    而不是把 body 兜底吐出去。
    """
    try:
        payload = response.json()
    except ValueError:
        return "（无 detail）"
    if isinstance(payload, dict):
        detail = payload.get("detail")
        if isinstance(detail, str) and detail:
            return detail[:_DETAIL_MAX]
    return "（无 detail）"


class JavaClient:
    """对 Java 平台的最小只读客户端。

    `transport` 是测试注入点（`httpx.MockTransport`），与归档 `ModelClient`
    的 `__init__(..., *, transport=None)` 同形。
    """

    def __init__(
        self,
        base_url: str | None = None,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout: float | None = None,
    ) -> None:
        self.base_url = (base_url or config.JAVA_BASE_URL).rstrip("/")
        self._client = httpx.AsyncClient(
            base_url=self.base_url,
            timeout=timeout if timeout is not None else config.JAVA_TIMEOUT,
            follow_redirects=False,
            transport=transport,
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> JavaClient:
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.aclose()

    # ------------------------------------------------------------------
    # 端点
    # ------------------------------------------------------------------

    async def me(self, jwt: str) -> AuthUser:
        """`GET /api/auth/me` —— SSE 开流前的门禁。"""
        response = await self._request("GET", "/api/auth/me", jwt=jwt)
        return AuthUser.model_validate(response.json())

    async def catalog(self, jwt: str) -> Catalog:
        """`GET /api/agents/catalog` —— 工具清单（只含元数据，不含业务数据）。"""
        response = await self._request("GET", "/api/agents/catalog", jwt=jwt)
        return Catalog.model_validate(response.json())

    async def call_tool(
        self,
        name: str,
        args: dict[str, Any] | None,
        jwt: str,
    ) -> ToolOutcome:
        """`POST /api/agents/tools/{name}` —— 统一工具调用端点。

        返回 `ToolOutcome` 即**成功到达**了工具层；工具自己失败与否看
        `outcome.is_failure`，那是要喂回模型的观察结果，不是异常。
        """
        response = await self._request(
            "POST",
            f"/api/agents/tools/{name}",
            jwt=jwt,
            json=args if args is not None else {},
        )
        return ToolOutcome.model_validate(response.json())

    # --- 会话 / 事件流 / 检查点（S4′.2，契约 §2.7）----------------------

    async def create_session(self, jwt: str, *, title: str | None = None) -> AgentSessionView:
        """`POST /api/agents/sessions` —— 建一个归属调用者的会话。"""
        payload: dict[str, Any] = {"title": title} if title else {}
        response = await self._request("POST", "/api/agents/sessions", jwt=jwt, json=payload)
        return AgentSessionView.model_validate(response.json())

    async def list_sessions(self, jwt: str) -> list[AgentSessionView]:
        """`GET /api/agents/sessions` —— 只回**当前用户**的会话。"""
        response = await self._request("GET", "/api/agents/sessions", jwt=jwt)
        return [AgentSessionView.model_validate(item) for item in response.json()]

    async def append_event(
        self,
        *,
        session_id: str,
        run_id: str,
        type: str,
        detail: dict[str, Any],
        jwt: str,
    ) -> int:
        """`POST /api/agents/events` —— 落一条事件，返回的 `id` **就是 seq**。

        事件先落库、拿到自增 id 再推给前端（契约 §3.2）——这是"重启后 seq 不回退"
        的唯一根据。
        """
        response = await self._request(
            "POST",
            "/api/agents/events",
            jwt=jwt,
            json={"session_id": session_id, "run_id": run_id, "type": type, "detail": detail},
        )
        return int(response.json()["id"])

    async def list_events(
        self,
        *,
        session_id: str,
        after_seq: int,
        jwt: str,
    ) -> list[AgentEventRow]:
        """`GET /api/agents/events` —— 增量读，语义是 `id > after_seq`（严格大于）。"""
        response = await self._request(
            "GET",
            "/api/agents/events",
            jwt=jwt,
            params={"session_id": session_id, "after_seq": after_seq},
        )
        return [AgentEventRow.model_validate(item) for item in response.json()]

    async def put_checkpoint(
        self,
        *,
        session_id: str,
        run_id: str,
        node_name: str,
        state: dict[str, Any],
        jwt: str,
    ) -> int:
        """`POST /api/agents/checkpoints` —— 按 `(run_id, node_name)` upsert 覆盖。

        编排层不持有 DB 连接（结构性约束见 `tests/test_no_db.py`），检查点只能
        经这个端点写。
        """
        response = await self._request(
            "POST",
            "/api/agents/checkpoints",
            jwt=jwt,
            json={"session_id": session_id, "run_id": run_id, "node_name": node_name, "state": state},
        )
        return int(response.json()["id"])

    async def list_checkpoints(self, *, run_id: str, jwt: str) -> list[AgentCheckpointRow]:
        """`GET /api/agents/checkpoints` —— 读某 run 的全部检查点。"""
        response = await self._request(
            "GET", "/api/agents/checkpoints", jwt=jwt, params={"run_id": run_id}
        )
        return [AgentCheckpointRow.model_validate(item) for item in response.json()]

    async def list_unfinished_runs(self, *, session_id: str, jwt: str) -> list[UnfinishedRun]:
        """`GET /api/agents/runs/unfinished` —— 惰性恢复的触发源。"""
        response = await self._request(
            "GET", "/api/agents/runs/unfinished", jwt=jwt, params={"session_id": session_id}
        )
        return [UnfinishedRun.model_validate(item) for item in response.json()]

    async def health(self) -> bool:
        """探 Java 是否可达。

        `/api/health` 在 Java 的白名单内，**不带 JWT**。连不上返回 `False`
        而不是抛异常 —— `/health` 要能如实报告"Java 不在"，而不是自己先挂。
        """
        try:
            response = await self._client.get("/api/health")
        except httpx.HTTPError:
            return False
        return response.status_code == 200

    # ------------------------------------------------------------------
    # 内部
    # ------------------------------------------------------------------

    async def _request(
        self,
        method: str,
        path: str,
        *,
        jwt: str,
        json: dict[str, Any] | None = None,
        params: dict[str, Any] | None = None,
    ) -> httpx.Response:
        headers = _auth_headers(jwt)
        if json is not None:
            headers.update(_JSON_HEADERS)
        try:
            response = await self._client.request(
                method, path, headers=headers, json=json, params=params
            )
        except httpx.HTTPError as exc:
            # 连不上 / 超时。只带异常类型名，不带 URL 与参数，避免回显调用内容。
            raise PlatformUnavailable(f"无法连接 Java 平台（{type(exc).__name__}）") from None
        if response.status_code != 200:
            raise PlatformProtocolError(
                response.status_code,
                _STATUS_CODES.get(response.status_code, "unexpected_status"),
                _safe_detail(response),
            )
        return response
