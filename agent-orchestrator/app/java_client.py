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
3. **幂等读有界重试，写操作零重试**。两条路径是两个方法：读走
   `_request_idempotent`（挂在 `app/retry.py` 的重试原语上），写走 `_request`。
   刻意不做成 `retry=True` 参数 —— 写操作（尤其 `append_event`）重发是**真的多一条
   事件**，不是幂等的重复，所以"重试"这件事必须让写路径**结构上够不到**。
"""

from __future__ import annotations

from typing import Any

import httpx

from . import config
from . import retry
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


def _unavailable(exc: httpx.HTTPError) -> PlatformUnavailable:
    """连不上 / 超时。只带异常类型名，不带 URL 与参数，避免回显调用内容。"""
    return PlatformUnavailable(f"无法连接 Java 平台（{type(exc).__name__}）")


def _ensure_ok(response: httpx.Response) -> httpx.Response:
    """非 200 → 协议错误。重试与否都不改变这一段：**先重试，再映射**。"""
    if response.status_code != 200:
        raise PlatformProtocolError(
            response.status_code,
            _STATUS_CODES.get(response.status_code, "unexpected_status"),
            _safe_detail(response),
        )
    return response


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
        response = await self._request_idempotent("GET", "/api/auth/me", jwt=jwt)
        return AuthUser.model_validate(response.json())

    async def catalog(self, jwt: str) -> Catalog:
        """`GET /api/agents/catalog` —— 工具清单（只含元数据，不含业务数据）。"""
        response = await self._request_idempotent("GET", "/api/agents/catalog", jwt=jwt)
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

        **形态是 POST，但语义是读**：三处只读 `ToolSpec` 的 `readOnly` 字面量是 `true`，
        14 个只读工具无一写业务表（红线①：模型产出一律经审批队列）。所以它走重试路径
        —— 工具调用是循环里最频繁的网络往返，不重试等于一次抖动就废掉整个 run。

        **S5′ 的触发型工具（`read_only=false`）也走这条路，是有意的**：它们不写业务表、
        只创建 run，而同域**幂等防重**（契约 §2.4 第 2 条：目标对象上已有未完成 run 时
        返回既有 `run_id` + `deduplicated=true`）让"请求已送达但响应丢了"那种重发**不产生
        第二个 run**。于是这里重试的收益（一次网络抖动不再吃掉一次派活）大于代价。
        ⚠️ 将来若往这个端点挂上**没有幂等键**的写工具，必须把它移出重试路径。
        """
        response = await self._request_idempotent(
            "POST",
            f"/api/agents/tools/{name}",
            jwt=jwt,
            json=args if args is not None else {},
        )
        return ToolOutcome.model_validate(response.json())

    # --- 会话 / 事件流 / 检查点（S4′.2，契约 §2.7）----------------------

    async def create_session(self, jwt: str, *, title: str | None = None) -> AgentSessionView:
        """`POST /api/agents/sessions` —— 建一个归属调用者的会话。

        **写操作，不重试**：重发会凭空多出一个空会话。
        """
        payload: dict[str, Any] = {"title": title} if title else {}
        response = await self._request("POST", "/api/agents/sessions", jwt=jwt, json=payload)
        return AgentSessionView.model_validate(response.json())

    async def list_sessions(self, jwt: str) -> list[AgentSessionView]:
        """`GET /api/agents/sessions` —— 只回**当前用户**的会话。"""
        response = await self._request_idempotent("GET", "/api/agents/sessions", jwt=jwt)
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

        **写操作，绝不重试**：这里重发不是"幂等的重复"，而是**真的多一条事件** ——
        `agent_events.id` 是自增主键，重试造成的事件重复在库里分辨不出来，
        前端会看到重复的一帧、seq 凭空跳号。调用方传重试预算也改不了这一点，
        因为这一路**结构上就够不到** `_request_idempotent`。
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
        """`GET /api/agents/events` —— 增量读，语义是 `id > after_seq`（严格大于）。

        **一次只取一页**（`config.EVENT_PAGE_LIMIT`）。Java 侧不传 `limit` 时一页
        给 500 条、上限 1000（`AgentEventService.DEFAULT_LIMIT` / `MAX_LIMIT`）——
        断线久了盘子里可能压着上千条事件，整批读会把它们一次性搬进内存，而且
        第一帧要等整批读完才吐得出来。调用方（`main._stream`）在循环里带着新
        `after_seq` 续读下一页，所以"小页"不会漏事件。
        """
        response = await self._request_idempotent(
            "GET",
            "/api/agents/events",
            jwt=jwt,
            params={
                "session_id": session_id,
                "after_seq": after_seq,
                # 每次调用重读 config（不要在导入时抄成局部变量）—— 测试靠它把页调小
                "limit": config.EVENT_PAGE_LIMIT,
            },
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

        **写操作，不重试**：虽然 upsert 本身对同一份 `state` 幂等，但重试会把
        "恰好晚到的那一发"覆盖成先到的那一发 —— 检查点是恢复的唯一根据，
        让一个已经过期的状态赢下来比直接报错危险得多。
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
        response = await self._request_idempotent(
            "GET", "/api/agents/checkpoints", jwt=jwt, params={"run_id": run_id}
        )
        return [AgentCheckpointRow.model_validate(item) for item in response.json()]

    async def list_unfinished_runs(self, *, session_id: str, jwt: str) -> list[UnfinishedRun]:
        """`GET /api/agents/runs/unfinished` —— 惰性恢复的触发源。"""
        response = await self._request_idempotent(
            "GET", "/api/agents/runs/unfinished", jwt=jwt, params={"session_id": session_id}
        )
        return [UnfinishedRun.model_validate(item) for item in response.json()]

    async def write_routing_log(
        self,
        *,
        session_id: str,
        run_id: str,
        utterance: str,
        intent: str,
        route: str | None,
        confidence: float,
        reason: str,
        asked_clarify: bool,
        candidates: list[dict[str, Any]],
        jwt: str,
    ) -> int:
        """`POST /api/agents/routing-logs` —— 落一行路由留痕（契约 §2.7 / §4.3），返回 `id`。

        `plan()` 之后、派活之前调它：反问的样本（`asked_clarify=1`）与派活的样本都要留，
        否则 S7 的"澄清率"没有分母。

        **写操作，零重试**（走 `_request`，结构上够不到 `_request_idempotent`）：
        `routing_logs` 是**追加式**表、没有幂等键（`id` 自增，契约 §2.7「同一 run 重放会
        追加新行、不去重」），所以重发不是"幂等的重复"而是**真的多一行留痕** ——
        而 S7 的澄清率与 intent 准确率**以行数为分母**，多出来的行会直接把指标算歪，
        且事后在库里分辨不出哪一行是重试产物。宁可少一行（调用方会把它标成
        `logged=false` 发进 `intent.routed`，指标缺失**可见**），也不留一行假的。

        ⚠️ `utterance` 是**用户原话（隐私）**：它是本方法唯一的用途，也只允许经这个
        端点落到 `routing_logs.utterance`（读取限 admin/owner）。**不要**把它捎进
        日志、事件 detail 或任何异常文本 —— 本方法抛出的异常里也不含它。
        """
        response = await self._request(
            "POST",
            "/api/agents/routing-logs",
            jwt=jwt,
            json={
                "session_id": session_id,
                "run_id": run_id,
                "utterance": utterance,
                "intent": intent,
                "route": route,
                "confidence": confidence,
                "reason": reason,
                "asked_clarify": asked_clarify,
                "candidates": candidates,
            },
        )
        return int(response.json()["id"])

    async def health(self) -> bool:
        """探 Java 是否可达。

        `/api/health` 在 Java 的白名单内，**不带 JWT**。连不上返回 `False`
        而不是抛异常 —— `/health` 要能如实报告"Java 不在"，而不是自己先挂。

        **探针刻意不重试**（`main` 里还把它的超时压到 `min(JAVA_TIMEOUT, 5.0)`）：
        它的职责是回答"**此刻**通不通"，重试 2 次会把 5 秒的短探针拖成 15 秒，
        让健康检查自己变成慢的那个。这里也不经 `_request_idempotent`，
        免得将来的改动把它悄悄带上重试。
        """
        try:
            response = await self._client.get("/api/health")
        except httpx.HTTPError:
            return False
        return response.status_code == 200

    # ------------------------------------------------------------------
    # 内部
    # ------------------------------------------------------------------

    async def _send(
        self,
        method: str,
        path: str,
        *,
        jwt: str,
        json: dict[str, Any] | None = None,
        params: dict[str, Any] | None = None,
    ) -> httpx.Response:
        """发**一次**请求。不重试、不映射语义 —— 抛的都是 httpx 自己的异常。"""
        headers = _auth_headers(jwt)
        if json is not None:
            headers.update(_JSON_HEADERS)
        return await self._client.request(method, path, headers=headers, json=json, params=params)

    async def _request(
        self,
        method: str,
        path: str,
        *,
        jwt: str,
        json: dict[str, Any] | None = None,
        params: dict[str, Any] | None = None,
    ) -> httpx.Response:
        """**不重试**的请求：所有写操作走这条路。

        `append_event` / `put_checkpoint` / `create_session` 只调这个方法 ——
        它们**结构上够不到** `_request_idempotent`，所以"给写操作加了个重试"
        这种事故不可能悄悄发生。
        """
        try:
            response = await self._send(method, path, jwt=jwt, json=json, params=params)
        except httpx.HTTPError as exc:
            raise _unavailable(exc) from None
        return _ensure_ok(response)

    async def _request_idempotent(
        self,
        method: str,
        path: str,
        *,
        jwt: str,
        json: dict[str, Any] | None = None,
        params: dict[str, Any] | None = None,
    ) -> httpx.Response:
        """**幂等调用**：有界重试的唯一入口（重试原语在 `app/retry.py`）。

        只有两类调用允许进来：GET（`me` / `catalog` / `sessions` / `events` /
        `checkpoints` / `unfinished`），以及 `POST /api/agents/tools/{name}`
        （平台工具面全部只读，见 `call_tool`）。

        顺序是**先重试、再映射**：
        502/503/504 会被包成 `retry.TransientResponse`（内部信号）参与重试，
        预算用尽后还原成原始 `response`，交给 `_ensure_ok` 走正常的
        `PlatformProtocolError(503, …)` —— 不会退化成一句"无法连接"。
        """

        async def attempt() -> httpx.Response:
            response = await self._send(method, path, jwt=jwt, json=json, params=params)
            if response.status_code in retry.RETRYABLE_STATUS:
                raise retry.TransientResponse(response)
            return response

        try:
            response = await retry.call_with_retry(attempt)
        except retry.TransientResponse as exhausted:
            response = exhausted.response  # 重试预算用尽：还原成应答走正常映射
        except httpx.HTTPError as exc:
            raise _unavailable(exc) from None
        return _ensure_ok(response)
