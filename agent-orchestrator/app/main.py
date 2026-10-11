"""编排层 FastAPI 入口。

S4′.1 只保证两件事：**服务起得来**、**平台可达性可见**。

- `GET /health` 真实探测 Java。Java 不在时 `java: false`，但**自身仍返回 200**
  —— 编排层要能如实报告"平台不在"，而不是自己先挂掉（契约 §8 待拍板 #3）。
- `POST /chat` 是**显式 503 占位**，body 明说尚未实现。**不返回假数据** ——
  静默降级到演示数据会让"没接上"看起来像"接上了"，这正是 S1 那两个静默失效
  缺陷（实施计划 §2.5）的同一种病。
"""

from __future__ import annotations

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from . import config
from .java_client import JavaClient

app = FastAPI(
    title="AIcap Agent Orchestrator",
    version="0.1.0",
    description="主管智能体编排层：Java 作平台，本层作编排，结构性不持有数据库连接。",
)

# dev 阶段前端直连并开 CORS（契约 §8 待拍板 #2 的建议值）。
# 生产用 nginx 反代时需要 proxy_buffering off，否则 SSE 退化成一次性吐出（契约 §3.1）。
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:5173",
        "http://127.0.0.1:5173",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health() -> dict[str, object]:
    """自身存活 + Java 可达性。Java 不在不影响本端点返回 200。"""
    async with JavaClient() as client:
        java_reachable = await client.health()
    return {
        "status": "ok",
        "orchestrator": True,
        "java": java_reachable,
        "java_base_url": config.JAVA_BASE_URL,
        "llm_configured": config.llm_configured(),
    }


@app.post("/chat")
async def chat() -> dict[str, str]:
    """对话框入口（S5′ 实现）。当前显式拒绝，不降级。"""
    raise HTTPException(
        status_code=503,
        detail="编排层尚未实现（S5′）：本步只交付骨架，不提供降级假数据",
    )
