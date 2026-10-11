"""`/health` 与 `/chat` 的行为（契约 §8 待拍板 #3）。

关键一条：**Java 不在时 `/health` 自身仍 200**，只是 `java: false`。
编排层要能如实报告"平台不在"，而不是跟着一起挂掉。

`/chat` 已在 S4′.2 **复活**（不再是占位 503）。它现在返回 503 只有一个来源：
**没配模型密钥** —— 这一条要盯住，因为它正是"静默降级到假数据"最容易被塞回来的地方。
"""

from __future__ import annotations

import httpx
import pytest

from app import config
from app.main import app


def _client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://orchestrator.test",
    )


async def test_health_is_200_regardless_of_java():
    async with _client() as client:
        response = await client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["orchestrator"] is True
    # java 的真假取决于本机跑没跑 8080，这里只断言字段存在且是布尔
    assert isinstance(body["java"], bool)
    assert "java_base_url" in body


async def test_chat_requires_a_token_now_that_it_is_alive():
    """`/chat` 已复活：它先要令牌，再要请求体 —— 缺令牌该是 401，不是 503。

    这条替代了原来的"占位 503"断言：端点现在真的接上了循环（身份门禁在循环触发
    之前），所以它的失败面从"未实现"变成了正常的入参校验。
    """
    async with _client() as client:
        response = await client.post("/chat", json={"utterance": "现在有几个任务在推进？"})

    assert response.status_code == 401


async def test_chat_is_503_when_unconfigured_and_never_fakes_data(monkeypatch: pytest.MonkeyPatch):
    """没配模型密钥 → 503 且**明说不降级**。

    不能是 200，也不能长得像一份正常回复 —— 静默降级到演示数据会让"没接上"看起来
    像"接上了"，这正是 S1 那两个静默失效缺陷（实施计划 §2.5）的同一种病。
    """
    monkeypatch.setattr(config, "AICAP_LLM_API_KEY", "")

    async with _client() as client:
        response = await client.post(
            "/chat",
            json={"utterance": "现在有几个任务在推进？"},
            headers={"Authorization": "Bearer whatever"},
        )

    assert response.status_code == 503
    detail = response.json()["detail"]
    assert "尚未实现" not in detail, "占位文案该随复活一起消失"
    assert "不降级" in detail and "假数据" in detail
