"""编排层配置：模块级常量，全部来自环境变量。

沿用归档 `backend/app/config.py` 的写法（`os.getenv` + 模块级常量，不用
pydantic-settings）。密钥一律留空默认，由环境变量注入，绝不落盘。
"""

from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

_ENV_FILE = Path(__file__).resolve().parent.parent / ".env"
load_dotenv(_ENV_FILE)

# Java 平台（唯一上游）。编排层自己不连库，一切数据都经它取。
JAVA_BASE_URL = os.getenv("JAVA_BASE_URL", "http://127.0.0.1:8080")

# 编排层自身监听端口（契约 §8 待拍板 #2 的建议值）
PORT = int(os.getenv("PORT", "8100"))

# 调用 Java 的超时（秒）
JAVA_TIMEOUT = float(os.getenv("JAVA_TIMEOUT", "30"))

# LLM 提供方：S4′.2 的有界循环才用到。留空即未配置，不降级到假数据。
AICAP_LLM_API_KEY = os.getenv("AICAP_LLM_API_KEY", "")
AICAP_LLM_BASE_URL = os.getenv("AICAP_LLM_BASE_URL", "https://api.deepseek.com")
AICAP_LLM_MODEL = os.getenv("AICAP_LLM_MODEL", "deepseek-flash")

# 单次模型调用的超时（秒）。有界循环的 180s 总预算由 loop 的 deadline 管，
# 这里是单步上限，防止某一步卡死把整个预算耗光。
AICAP_LLM_TIMEOUT = float(os.getenv("AICAP_LLM_TIMEOUT", "60"))

# 反问阈值（契约 §5.4 的 `confidence < 0.6` → 反问）。
# **可配置、写在代码里、不写死在 prompt 里**：prompt 只告诉模型"如实给 confidence"，
# 由 `orchestrator` 在 Python 里机械比对。环境变量 `AICAP_CLARIFY_CONFIDENCE` 可覆盖，
# 调它不需要改代码、更不需要重训 prompt。
CLARIFY_CONFIDENCE = float(os.getenv("AICAP_CLARIFY_CONFIDENCE", "0.6"))

# SSE 心跳间隔（秒，契约 §3.1 建议 15s 防反代断流）。
# **消费方必须每次循环重新读这个属性**，不要在导入时抄进局部变量 ——
# 测试靠 monkeypatch 成 0.05 把心跳路径跑快。
SSE_HEARTBEAT_SECONDS = float(os.getenv("SSE_HEARTBEAT_SECONDS", "15"))

# SSE 事件**单次读取**的页大小（契约 §3.1「增量读」）。
# Java 侧不传 limit 时一页 500 条、上限 1000（`AgentEventService.DEFAULT_LIMIT`
# / `MAX_LIMIT`）—— 断线久了盘子里可能压着上千条事件，整批读会把它们一次性
# 搬进内存，第一帧也要等整批读完才吐得出来。编排层刻意只要一小页，由
# `main._stream` 的循环带着新的 after_seq 续读下一页。
# **消费方（`java_client.list_events`）每次调用重读这个属性**，不要在导入时抄走。
EVENT_PAGE_LIMIT = int(os.getenv("SSE_EVENT_PAGE_LIMIT", "100"))


def llm_configured() -> bool:
    """是否配了真实 LLM。没配时如实回报，不假装能干活。"""
    return bool(AICAP_LLM_API_KEY and AICAP_LLM_BASE_URL and AICAP_LLM_MODEL)
