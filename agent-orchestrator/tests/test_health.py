"""`/health` 与 `/chat` 的行为（契约 §8 待拍板 #3）。

关键一条：**Java 不在时 `/health` 自身仍 200**，只是 `java: false`。
编排层要能如实报告"平台不在"，而不是跟着一起挂掉。

`/chat` 当前是显式 503 —— 断言它**不返回假数据**（不返回 200）。
"""

from __future__ import annotations

import httpx

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


async def test_chat_is_explicit_503_not_fake_data():
    async with _client() as client:
        response = await client.post("/chat")

    assert response.status_code == 503
    detail = response.json()["detail"]
    assert "尚未实现" in detail
    # 明确不降级：不能是 200，也不能长得像一份正常回复
    assert "假数据" in detail
