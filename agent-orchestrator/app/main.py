"""编排层 FastAPI 入口。

三个端点，各管一件事：

- `GET /health` 真实探测 Java。Java 不在时 `java: false`，但**自身仍返回 200**
  —— 编排层要能如实报告"平台不在"，而不是自己先挂掉（契约 §8 待拍板 #3）。
- `POST /chat` 是**循环的触发口**：起一个独立后台任务跑有界循环，立刻返回
  `{session_id, run_id}`。**请求生命周期不拥有循环** —— 这正是"kill 进程后重连
  仍能续传"的前提（循环活在请求之外，事件活在 Java 里）。
- `GET /chat/stream` 是**只读的事件通道**：从 Java 读事件推给前端，从不拥有循环。
  客户端带 `Last-Event-ID` 重连时，若该会话有未完成的 run，惰性恢复它。

**不降级**：没配模型密钥就是 503 并明说原因。静默降级到演示数据会让"没接上"看起来
像"接上了"，这正是 S1 那两个静默失效缺陷（实施计划 §2.5）的同一种病。
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from typing import Any, AsyncIterator

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from starlette.background import BackgroundTask

from . import config
from . import events
from . import loop as loop_module
from . import orchestrator
from .java_client import JavaClient
from .model_client import ModelClient, ModelProtocol
from .runtime import REGISTRY, RunRegistry
from .schemas import (
    Catalog,
    ModelError,
    PlatformError,
    PlatformProtocolError,
    PlatformUnavailable,
    UnfinishedRun,
)

_LOG = logging.getLogger(__name__)

# 上游协议错误 → 本层 HTTP 状态。编排层是**代理**：Java 说 404（会话不存在 / 不是你
# 的），对前端就该是 404。落成 500 会把"查无此会话"说成"服务端坏了"，前端会去重试。
_PROTOCOL_STATUS = {400: 400, 401: 401, 403: 403, 404: 404, 422: 422}


class ChatRequest(BaseModel):
    """`POST /chat` 的入参。`session_id` 省略则新建会话。"""

    utterance: str
    session_id: str | None = None
    # 当前项目(前端从路由/项目选择器带上来)。多项目之后工具调用普遍需要 project_id,
    # 不传就只能靠模型猜:读查询猜中过 P001,而触发型工具会被平台以
    # invalid_arguments 拒收。省略仍然可用(向后兼容老调用方)。
    project_id: str | None = None


def _bearer(request: Request) -> str:
    """从 `Authorization` 头取令牌。**身份只来自 JWT**，不接受任何 body/查询参数身份。"""
    header = request.headers.get("Authorization") or ""
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=401, detail="缺少 Bearer 令牌")
    return token.strip()


def _as_http(error: PlatformProtocolError) -> HTTPException:
    """上游协议错误 → HTTPException。`detail` 是 Java 自己的 `detail` 字段（已截断）。"""
    return HTTPException(
        status_code=_PROTOCOL_STATUS.get(error.status, 502),
        detail=error.detail,
    )


async def _gate(client: JavaClient, jwt: str) -> None:
    """身份门禁。**令牌合不合法由 Java 说了算** —— 编排层不自己校验签名。

    Java 回 401/403 时**必须转成 HTTP 状态码，不能塞进 SSE 流**：塞进去前端拿到
    的是 200，会以为连上了，而实际上每一帧都是错的。
    """
    try:
        await client.me(jwt)
    except PlatformProtocolError as error:
        raise _as_http(error) from None


def _java(request: Request) -> JavaClient:
    return JavaClient(transport=request.app.state.java_transport)


def _model(request: Request) -> ModelProtocol:
    """拿模型客户端。未配置且没注入假模型 → 503，**在建会话之前**失败，不留副作用。"""
    model = request.app.state.model
    if model is not None:
        return model
    if not config.llm_configured():
        raise HTTPException(
            status_code=503,
            detail="服务端未配置模型密钥（AICAP_LLM_API_KEY），不降级到假数据",
        )
    return ModelClient()


def _start_seq(request: Request, after_seq: int) -> int:
    """续传起点：`Last-Event-ID` 与 `after_seq` 同时给出时取 **max**（契约 §2.7）。

    `Last-Event-ID` 是浏览器 `EventSource` 自动重连时自己带的，`after_seq` 是显式
    参数 —— 取较小值会重复收到已确认的事件，取 max 才是"两处声明里更靠后的那个"。
    """
    candidates = [max(after_seq or 0, 0)]
    raw = request.headers.get("Last-Event-ID")
    if raw:
        try:
            candidates.append(int(raw))
        except ValueError:
            # 客户端给了坏值：忽略它退回 after_seq，不因为一个坏头就把流断掉
            pass
    return max(candidates)


async def _background_run(
    client: JavaClient,
    *,
    session_id: str,
    run_id: str,
    utterance: str,
    jwt: str,
    registry: RunRegistry,
    model: ModelProtocol,
    project_id: str | None = None,
) -> None:
    """后台任务自持一个 `JavaClient`。

    `/chat` 里那个 `async with` 的客户端**不能**交给后台任务 —— 请求一返回它就关了，
    循环下一次 `append_event` 直接炸。所以任务自己开一个，自己关。
    """
    try:
        await loop_module.run(
            client=client,
            session_id=session_id,
            run_id=run_id,
            utterance=utterance,
            jwt=jwt,
            registry=registry,
            model=model,
            project_id=project_id,
            # 规划相挂在 claim 窗口内（见 loop.run 的 `pre_loop` 说明）：
            # 它决定这一轮是"反问"还是"派活"，并且是 loop 之前唯一发事件的地方。
            pre_loop=lambda: _plan_phase(
                client=client,
                model=model,
                registry=registry,
                session_id=session_id,
                run_id=run_id,
                utterance=utterance,
                jwt=jwt,
                project_id=project_id,
            ),
        )
    finally:
        await client.aclose()


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


async def _plan_phase(
    *,
    client: JavaClient,
    model: ModelProtocol,
    registry: RunRegistry,
    session_id: str,
    run_id: str,
    utterance: str,
    jwt: str,
    project_id: str | None = None,
) -> bool:
    """主管规划相：`plan()` → 留痕 → 反问或派活。返回 `True` 表示可以进工具循环。

    主管智能体**只 `plan()`**（契约 §5.4）—— 发事件、留痕、调触发型工具都是**编排层**
    在这个函数里做的，所以 `orchestrator.py` 里没有 `execute()`，这里也没有"主管在干活"。

    事件顺序（契约 §3.4 第 2 条）：`session.start`（loop 的 on_claim，在本相之前）
    → `intent.routed` → `agent.dispatched` → 循环里的 `tool.call`。反问那一轮则是
    `clarify.request` → `run.end`，不进循环；问问题那一轮没有 `agent.dispatched`，
    照发 `intent.routed{route: null}` 后把话交回循环。
    """
    started = time.monotonic()

    async def emit(type_: str, data: dict[str, Any]) -> None:
        """落一条事件并唤醒 SSE 等待者 —— 顺序不能反（见 loop._emit 的同一条理由）。"""
        await client.append_event(
            session_id=session_id, run_id=run_id, type=type_, detail=data, jwt=jwt
        )
        registry.notify(session_id)

    async def fail(code: str, message: str) -> bool:
        await emit(
            "run.error",
            {"code": code, "message": message, "steps": 0, "elapsed_ms": _elapsed_ms(started)},
        )
        return False

    try:
        # 清单在同一个 run 里只拉一次：plan() 用它派生路由，本层用它拼留痕的 candidates。
        catalog = await client.catalog(jwt)
        # 规划相也读会话纪要（2026-10-27）：不读的话，「先反问、再补一句」这条最常用的
        # 路径每轮都从零开始 —— 真机实测：第 2 轮忘掉第 1 轮给的负责人、第 3 轮又问
        # 「想执行哪个动作」。读失败降级空纪要（`_history_messages` 已兜住 PlatformError），
        # 记忆是增强，不能反过来打断对话。
        history = await loop_module.plan_history_messages(
            client=client, jwt=jwt, session_id=session_id, current_run_id=run_id
        )
        decision = await orchestrator.plan(utterance, jwt, model=model, catalog=catalog,
                                           project_id=project_id, client=client, history=history)
    except ModelError as error:
        # 没配密钥（`not_configured`）、模型不可达、返回的不是合法 JSON —— 全部如实上报。
        # **没有任何兜底路由**：这里绝不会"猜一个 route 让它继续跑"。
        return await fail(error.code, error.message)
    except PlatformError as error:
        return await fail(error.code, error.message)

    logged = await _write_routing_log(
        client,
        session_id=session_id,
        run_id=run_id,
        utterance=utterance,
        decision=decision,
        catalog=catalog,
        jwt=jwt,
    )

    if decision.clarify is not None:
        await emit(
            "clarify.request",
            {
                "question": decision.clarify.get("question") or "",
                "options": list(decision.clarify.get("options") or []),
                "logged": logged,
            },
        )
        # 反问也是终局：这轮的答复在下一次 /chat 里进来（契约 §3.4 第 3 条要求
        # run.end/run.error 是该 run 的最后一条）。
        await emit(
            "run.end",
            {"status": "clarify", "intent": decision.intent, "elapsed_ms": _elapsed_ms(started)},
        )
        return False

    # 到这里 route 要么是**本轮 catalog 里真实存在**的触发型工具，要么是 None
    # （"这一轮不需要子智能体"——交给工具循环自己用只读工具作答，见 apply_rules）。
    await emit(
        "intent.routed",
        {
            "intent": decision.intent,
            "route": decision.route,
            "confidence": decision.confidence,
            "reason": decision.reason,
            "logged": logged,
        },
    )

    if decision.route is None:
        # 问问题的回合：交回工具循环。**必须先发 intent.routed** —— 契约 §3.4#2 要求它
        # 先于任何 `tool.call`，而这一轮循环里很可能就要调只读工具。
        return True

    route = decision.route
    try:
        # 派活时把当前项目补进 args(模型漏传/猜错都不至于让整次派发失败),但**只在这枚工具
        # 的 schema 真的声明了 project_id 时**才补 —— 个别工具严格校验未知参数,多塞会被拒
        # (见 Catalog.declares_project_id)。模型自己给了值就以它为准。
        dispatch_args = decision.args if isinstance(decision.args, dict) else {}
        if (project_id and catalog.declares_project_id(route)
                and not dispatch_args.get("project_id") and not dispatch_args.get("projectId")):
            dispatch_args = {**dispatch_args, "project_id": project_id}
        outcome = await client.call_tool(route, dispatch_args, jwt)
    except PlatformError as error:
        return await fail(error.code, error.message)
    if outcome.is_failure:
        # 触发型工具的业务失败也是 200 + ok=false（契约 §2.3）——照实报，
        # 不假装"已派发"（那会让用户去审批队列里找一个根本不存在的 run）。
        return await fail(
            outcome.error_code or "dispatch_failed",
            outcome.error_message or "触发型工具派发失败",
        )

    result = outcome.result if isinstance(outcome.result, dict) else {}
    await emit(
        "agent.dispatched",
        {
            # route 就是触发型工具名（§2.4 的契约），它是"派给谁/派了什么"的唯一无歧义标识
            "agent": route,
            # 触发型工具的回执**形状不止一种**：规划/画像是 run 口径（run_id/status/deduplicated），
            # 会议分析是产物口径（analysis_id/storage_status，2026-10-15 起同步执行，不建 run）。
            # 所以这里既归一化出 run 三兄弟（老回执形状不变），也把原始 result 整包带上 ——
            # 后者是模型据以答复的唯一凭据：**丢掉它，主管就只能去 query_agent_runs 里
            # 抓一个旧的 meeting run 当成本次产物，说出与事实相反的话**（2026-10-15 真机实测）。
            "result": result,
            "job_id": result.get("run_id"),
            "mode": "trigger",
            "status": result.get("status"),
            # 同域幂等防重：目标对象上已有未完成 run 时返回既有的那一个
            "deduplicated": bool(result.get("deduplicated")),
        },
    )
    return True


async def _write_routing_log(
    client: JavaClient,
    *,
    session_id: str,
    run_id: str,
    utterance: str,
    decision: orchestrator.Plan,
    catalog: Catalog,
    jwt: str,
) -> bool:
    """写路由留痕（契约 §2.7：`plan()` 之后、派活之前），返回是否真的写进去了。

    写失败**不终止本轮**：路由事实已经落在 `agent_events` 的 `intent.routed` /
    `clarify.request` 里、没丢，为一次**指标**写失败把用户对话框打断不划算。
    但降级必须**留痕** —— `logged=false` 会跟着事件流出去，S7 的分母少了一行这件事
    必须看得见；静默少一行才是真危险。
    """
    routes = orchestrator.routes_from_catalog(catalog)
    try:
        await client.write_routing_log(
            session_id=session_id,
            run_id=run_id,
            utterance=utterance,  # 隐私：**只**经这个端点落库，别处一律不带
            intent=decision.intent,
            route=decision.route,
            confidence=decision.confidence,
            reason=decision.reason,
            asked_clarify=decision.clarify is not None,
            candidates=orchestrator.candidates(routes, chosen=decision.route),
            jwt=jwt,
        )
    except PlatformError as error:
        # 只记 code 与 run/session：**不记 error.message，更不记 utterance** ——
        # 日志是另一条侧路，别让它把用户原话漏出去。
        _LOG.warning(
            "routing_log 写入失败（本轮继续，事件里带 logged=false）：code=%s session=%s run=%s",
            error.code,
            session_id,
            run_id,
        )
        return False
    return True


async def _background_resume(
    client: JavaClient,
    *,
    state: dict[str, Any],
    jwt: str,
    registry: RunRegistry,
    model: ModelProtocol,
) -> None:
    try:
        await loop_module.resume(state=state, jwt=jwt, client=client, registry=registry, model=model)
    finally:
        await client.aclose()


def create_app(
    *,
    registry: RunRegistry | None = None,
    java_transport: httpx.AsyncBaseTransport | None = None,
    model: ModelProtocol | None = None,
) -> FastAPI:
    """装配一个编排层应用。

    三个注入点都是为了测试能模拟"进程重启"：`registry` 换一个新的即等价于进程
    重启（进程内登记全丢），而 `java_transport` 背后那份共享的假平台 store 保留 ——
    于是"状态其实在 Java 里"这件事就变成可断言的事实，而不是一句设计声明。
    """
    application = FastAPI(
        title="AIcap Agent Orchestrator",
        version="0.1.0",
        description="主管智能体编排层：Java 作平台，本层作编排，结构性不持有数据库连接。",
    )
    application.state.registry = registry if registry is not None else REGISTRY
    application.state.java_transport = java_transport
    application.state.model = model

    # dev 阶段前端直连并开 CORS（契约 §8 待拍板 #2 的建议值）。
    # 生产用 nginx 反代时需要 proxy_buffering off，否则 SSE 退化成一次性吐出（契约 §3.1）。
    application.add_middleware(
        CORSMiddleware,
        allow_origins=[
            "http://localhost:5173",
            "http://127.0.0.1:5173",
        ],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @application.get("/health")
    async def health() -> dict[str, object]:
        """自身存活 + Java 可达性。Java 不在不影响本端点返回 200。"""
        client = JavaClient(
            transport=application.state.java_transport,
            timeout=min(config.JAVA_TIMEOUT, 5.0),
        )
        async with client as probe:
            java_reachable = await probe.health()
        return {
            "status": "ok",
            "orchestrator": True,
            "java": java_reachable,
            "java_base_url": config.JAVA_BASE_URL,
            "llm_configured": config.llm_configured(),
        }

    @application.post("/chat")
    async def chat(payload: ChatRequest, request: Request) -> dict[str, str]:
        """起一轮主管对话：落会话、起后台循环、立刻返回句柄。"""
        jwt = _bearer(request)
        model = _model(request)
        registry: RunRegistry = request.app.state.registry

        async with _java(request) as client:
            try:
                await _gate(client, jwt)  # 身份门禁：令牌不认就是 401/403，不建任何东西

                if payload.session_id:
                    session_id = payload.session_id
                    # 一次调用同时完成两件事：归属校验（不是自己的会话 → 404）与
                    # "有没有没跑完的 run"。新建的会话不可能有，省掉这次往返。
                    unfinished = await client.list_unfinished_runs(session_id=session_id, jwt=jwt)
                else:
                    session_id = (await client.create_session(jwt)).session_id
                    unfinished = []
            except PlatformProtocolError as error:
                raise _as_http(error) from None
            except PlatformUnavailable as error:
                raise HTTPException(status_code=503, detail=error.message) from None

            if registry.active_run(session_id) is not None:
                raise HTTPException(
                    status_code=409,
                    detail="该会话已有正在进行的 run，请等它结束，或连 /chat/stream 观察",
                )
            if unfinished:
                raise HTTPException(
                    status_code=409,
                    detail=(
                        "该会话有未完成的 run，请带 Last-Event-ID 重连 /chat/stream 续传"
                        "（恢复会接着跑，不重复已完成的工具调用），不要新开一轮"
                    ),
                )

            run_id = str(uuid.uuid4())
            # 后台任务自持客户端，别把请求作用域里的这个交出去（见 _background_run）
            task = asyncio.create_task(
                _background_run(
                    _java(request),
                    session_id=session_id,
                    run_id=run_id,
                    utterance=payload.utterance,
                    jwt=jwt,
                    registry=registry,
                    model=model,
                    project_id=payload.project_id,
                )
            )
            registry.attach_task(session_id, task)

        return {"session_id": session_id, "run_id": run_id}

    @application.get("/chat/stream")
    async def chat_stream(
        request: Request, session_id: str, after_seq: int = 0
    ) -> StreamingResponse:
        """事件通道：重放 + 增量 + 心跳，必要时惰性恢复未完成的 run。"""
        jwt = _bearer(request)
        client = _java(request)
        try:
            # 门禁在**流外**：令牌不认就走 HTTP 状态码，不能塞进 SSE 流
            # （前端拿到 200 会以为连上了，实际上每一帧都是错的）。
            await _gate(client, jwt)
            # 会话不存在也必须在建流之前报 404：`StreamingResponse` 一旦开始发字节，
            # 状态码就固定成 200 了，之后 Java 的 404 只能表现为「流被掐断」——
            # 前端看到的是"事件流读取失败"，既不知道原因，也不会清掉本地那个已失效的
            # 会话句柄（症状：库重建之后「和本说不了话」，刷新页面也不恢复）。
            # 顺带把待恢复的 run 取回来，省掉 `_resume_once` 里重复的一次调用。
            pending = await client.list_unfinished_runs(session_id=session_id, jwt=jwt)
        except PlatformProtocolError as error:
            await client.aclose()
            raise _as_http(error) from None
        except PlatformUnavailable as error:
            await client.aclose()
            raise HTTPException(status_code=503, detail=error.message) from None
        except BaseException:
            await client.aclose()
            raise

        return StreamingResponse(
            _stream(
                request.app.state, client, jwt, session_id, _start_seq(request, after_seq), pending
            ),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                # 反代不许缓冲，否则 SSE 退化成一次性吐出（契约 §3.1）
                "X-Accel-Buffering": "no",
            },
            # 断连时 Starlette 会跑它；生成器内部无法安全地 await 关闭
            # （GeneratorExit 期间再挂起会被判为"忽略了 GeneratorExit"）
            background=BackgroundTask(client.aclose),
        )

    return application


async def _stream(
    app_state: Any,
    client: JavaClient,
    jwt: str,
    session_id: str,
    start: int,
    pending: list[UnfinishedRun],
) -> AsyncIterator[str]:
    registry: RunRegistry = app_state.registry
    # 假模型由 create_app 注入；真模型按需建 —— 没有待恢复的 run 时它根本不会被用到，
    # 所以这里不做"未配置就 503"的门禁，未配置会以 run.error{not_configured} 如实呈现。
    model: ModelProtocol = app_state.model or ModelClient()

    try:
        await _resume_once(app_state, client, jwt, session_id, model, pending)
    except PlatformError:
        # 恢复失败不该把已经建立的流打断：没有可发的帧，转入正常读取循环，
        # 由心跳维持连接，故障会体现在事件流里。
        pass

    cursor = start
    while True:
        # 先清唤醒位、再读事件：这样"读完发现没有新事件"与"循环在这期间推了事件"
        # 之间不会丢唤醒 —— 后者会把位置上，下面的 wait 立即返回。
        registry.clear(session_id)
        # 一次只取**一页**（页大小 `config.EVENT_PAGE_LIMIT`，由 java_client 传
        # Java 的 `limit` 参数）。读到满页就说明后面还有：这一圈 yield 完，下一圈
        # 带着新的 cursor 接着读下一页。于是"断线很久、盘子里压着上千条事件"也只是
        # 一页一页地搬 —— 不会一次性读进内存，也不会让第一帧等整批读完。
        # 顺序与 seq 单调性不受影响：Java 侧 `id > after_seq` 升序返回，
        # cursor 只前进不回退，`Last-Event-ID` 续传的起点仍是 `_start_seq`。
        rows = await client.list_events(session_id=session_id, after_seq=cursor, jwt=jwt)
        if not rows:
            yield events.heartbeat()
            await registry.wait(session_id, config.SSE_HEARTBEAT_SECONDS)
            continue
        for row in rows:
            cursor = row.id
            yield events.sse_frame(events.envelope_from_row(row))
        # **终局后不关流**：契约 §3.4 第 4 条允许 agent.report 在 run.end 之后到达，
        # 前端也不能因为 run 结束就关通道。


async def _resume_once(
    app_state: Any,
    client: JavaClient,
    jwt: str,
    session_id: str,
    model: ModelProtocol,
    pending: list[UnfinishedRun],
) -> None:
    """惰性恢复：**每个连接只尝试一次**。

    这正是契约规定的触发点 —— 客户端带 `Last-Event-ID` 重连。**不能每轮心跳都试**：
    一个注定跑不完的 run（恢复后立刻又崩）会变成每 15 秒重跑一次的无限循环。
    恢复所需的 JWT 来自这次重连，`state_json` 里没有任何凭据。

    待恢复的 run 由 `chat_stream` 在建流**之前**取好传进来（同时用于会话存在性校验），
    这里不再自己查一次 —— 否则每开一条流就要多一次 Java 往返。
    """
    registry: RunRegistry = app_state.registry
    if registry.active_run(session_id) is not None:
        return
    for row in pending:
        task = asyncio.create_task(
            _background_resume(
                JavaClient(base_url=client.base_url, transport=app_state.java_transport),
                state=row.state,
                jwt=jwt,
                registry=registry,
                model=model,
            )
        )
        registry.attach_task(session_id, task)


app = create_app()
