"""`POST /chat` —— 循环的触发口。

它只做四件事：验身份、判"能不能开新一轮"、起后台循环、返回句柄。断言集中在这四件
上，尤其是**两种 409**：一个会话同时只该有一个 run 在跑，而"有未完成的 run"时必须
把客户端推去 `/chat/stream` 续传，而不是新开一轮把它盖掉。

`/chat` 是普通 JSON 响应，用 `httpx.ASGITransport` 就够（SSE 那套见
`test_chat_stream.py`，那里必须自己驱动 ASGI）。
"""

from __future__ import annotations

import asyncio
from typing import Any, Callable

import httpx
import pytest

from app import config
from app.main import create_app
from app.runtime import RunRegistry

from fake_platform import (
    BlockingModel,
    FakePlatform,
    ScriptedModel,
    UTTERANCE,
    final,
    plan_json,
    plan_reply,
    seed_crashed,
)

TOKEN = "tok-ok"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


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
    """轮询等待后台任务推进 —— 循环是独立任务，POST 返回时它还没跑。"""
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while loop.time() < deadline:
        if predicate():
            return True
        await asyncio.sleep(0.01)
    return predicate()


# ==========================================================================
# 门禁
# ==========================================================================


async def test_chat_requires_token():
    platform = FakePlatform()
    app = _app(platform, ScriptedModel())

    async with _client(app) as client:
        response = await client.post("/chat", json={"utterance": UTTERANCE})

    assert response.status_code == 401


async def test_chat_rejects_bad_token():
    """令牌合不合法由 Java 说了算；Java 回 401 就要如实转成 401，不能落成 500。"""
    platform = FakePlatform()
    app = _app(platform, ScriptedModel())

    async with _client(app) as client:
        response = await client.post(
            "/chat", json={"utterance": UTTERANCE}, headers={"Authorization": "Bearer wrong"}
        )

    assert response.status_code == 401
    assert platform.sessions == {}, "认证失败不该留下任何副作用"


async def test_chat_503_when_model_not_configured_and_no_side_effects(
    monkeypatch: pytest.MonkeyPatch,
):
    """没配模型密钥 → 503 且**明说不降级**，并且**一个会话都不建**。

    静默降级到演示数据会让"没接上"看起来像"接上了" —— 这正是 S1 那两个静默失效
    缺陷的同一种病（实施计划 §2.5）。
    """
    monkeypatch.setattr(config, "AICAP_LLM_API_KEY", "")
    platform = FakePlatform()
    app = _app(platform, model=None)

    async with _client(app) as client:
        response = await client.post("/chat", json={"utterance": UTTERANCE}, headers=AUTH)

    assert response.status_code == 503
    detail = response.json()["detail"]
    assert "不降级" in detail
    assert platform.sessions == {}, "没配模型就不该建会话"


# ==========================================================================
# 正常路径
# ==========================================================================


async def test_chat_returns_handles_and_drives_the_loop():
    platform = FakePlatform()
    # 第一发是**规划相**（S5′）：模型只提案，Python 判要不要反问。
    model = ScriptedModel(plan_reply(), final("先看看"), final('{"answer": "ok"}'))
    app = _app(platform, model)

    async with _client(app) as client:
        response = await client.post("/chat", json={"utterance": UTTERANCE}, headers=AUTH)
        assert response.status_code == 200, response.text
        body = response.json()
        assert set(body) == {"session_id", "run_id"}

        assert await _wait_for(lambda: "run.end" in platform.event_types(body["run_id"]))

    run_id = body["run_id"]
    types = platform.event_types(run_id)
    assert types[0] == "session.start", "规划相挂在 claim 窗口内，session.start 仍是第一条"
    assert types[-1] == "run.end"
    # 高置信度 + 必填参数齐全 → 派活：route 就是那枚触发型工具，参数原样透传
    assert types[1] == "intent.routed"
    assert types.index("intent.routed") < types.index("agent.dispatched")
    assert platform.calls == [
        {"name": "start_meeting_analysis", "arguments": {"meeting_id": "m-1"}}
    ]
    assert platform.checkpoint_state(run_id) is not None, "跑完的 run 也该留下检查点"

    # 事件全挂在返回的那个会话上
    assert {e["session_id"] for e in platform.events} == {body["session_id"]}


async def test_chat_creates_session_when_none_given_and_reuses_it_when_given():
    platform = FakePlatform()
    model = ScriptedModel(
        plan_reply(), final("a"), final("b"), plan_reply(), final("c"), final("d")
    )
    app = _app(platform, model)

    async with _client(app) as client:
        first = (await client.post("/chat", json={"utterance": UTTERANCE}, headers=AUTH)).json()
        assert await _wait_for(lambda: "run.end" in platform.event_types(first["run_id"]))

        second = (
            await client.post(
                "/chat",
                json={"utterance": "那谁最忙？", "session_id": first["session_id"]},
                headers=AUTH,
            )
        ).json()

    assert second["session_id"] == first["session_id"]
    assert second["run_id"] != first["run_id"]
    assert len(platform.sessions) == 1, "给了 session_id 就不该另建一个"


async def test_chat_404_for_foreign_session():
    """不是自己的会话 → 404（Java 侧口径：不存在与不属于调用者不区分，防 id 枚举）。"""
    platform = FakePlatform()
    foreign = platform.make_session(user_id=999)
    app = _app(platform, ScriptedModel(final("不该跑到")))

    async with _client(app) as client:
        response = await client.post(
            "/chat", json={"utterance": UTTERANCE, "session_id": foreign}, headers=AUTH
        )

    assert response.status_code == 404


# ==========================================================================
# 两种 409
# ==========================================================================


async def test_chat_409_when_a_run_is_already_active():
    """同一会话同时只该有一个 run 在跑。

    卡住的是**规划相**那一发模型调用 —— 规划相落在 `registry.claim` 的窗口内
    （见 `loop.run` 的 `pre_loop`），所以规划期间该会话照样"有 run 在跑"，
    409 不会出现真空窗口。
    """
    platform = FakePlatform()
    model = BlockingModel(
        plan_json(
            route=None,
            confidence=0.41,
            args={},
            clarify={"question": "要分析哪一场会议？", "options": ["最近一次周会"]},
        )
    )
    app = _app(platform, model)

    async with _client(app) as client:
        first = await client.post("/chat", json={"utterance": UTTERANCE}, headers=AUTH)
        assert first.status_code == 200
        session_id = first.json()["session_id"]

        assert await _wait_for(lambda: model.started is not None and model.started.is_set()), (
            "循环没起来，这条测试就不成立"
        )

        second = await client.post(
            "/chat", json={"utterance": "再问一句", "session_id": session_id}, headers=AUTH
        )
        assert second.status_code == 409
        assert "正在进行" in second.json()["detail"]

        model.release.set()
        assert await _wait_for(lambda: "run.end" in platform.event_types())

    assert platform.event_types().count("session.start") == 1


async def test_chat_409_when_an_unfinished_run_exists():
    """有未完成的 run → 把客户端推去 `/chat/stream` 续传，而不是新开一轮把它盖掉。"""
    platform = FakePlatform()
    session_id, run_id = seed_crashed(platform)
    app = _app(platform, ScriptedModel(final("不该跑到")))

    async with _client(app) as client:
        response = await client.post(
            "/chat", json={"utterance": UTTERANCE, "session_id": session_id}, headers=AUTH
        )

    assert response.status_code == 409
    detail = response.json()["detail"]
    assert "未完成" in detail and "Last-Event-ID" in detail
    # 没有新 run 被建出来
    assert platform.event_types().count("session.start") == 1
    assert run_id in [e["run_id"] for e in platform.events]
