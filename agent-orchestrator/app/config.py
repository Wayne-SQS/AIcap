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
AICAP_LLM_MODEL = os.getenv("AICAP_LLM_MODEL", "deepseek-v4-flash")


def llm_configured() -> bool:
    """是否配了真实 LLM。没配时如实回报，不假装能干活。"""
    return bool(AICAP_LLM_API_KEY and AICAP_LLM_BASE_URL and AICAP_LLM_MODEL)
