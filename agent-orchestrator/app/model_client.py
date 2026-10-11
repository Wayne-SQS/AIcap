"""Chat Completions 传输层 —— **不引任何厂商 SDK**。

从归档 `backend/app/meeting_agent/model_client.py` 移植。**是移植不是照抄**：
归档那份是同步 `httpx.Client`（跑在线程池里），这里必须换成
`httpx.AsyncClient` —— 有界循环是 asyncio 后台任务，同步阻塞调用会把整个
事件循环钉死，SSE 心跳跟着一起停。

本模块**不碰** `java_client`，也不 `import httpx` 之外的任何东西：编排层对外的
唯一 HTTP 出口是 `java_client`，这里是唯一对**模型服务**的出口，两者各管一头。

安全面（照搬归档，一条都不能少）：

1. **非 200 绝不回显 body**（红线②的同一条理由）：上游 body 可能回显凭据或
   私有文本，任何级别都不落地。`provider_error` 只带状态码。
2. **地址必须 HTTPS**（本机测试服务除外）—— 明文发出的是 API key。
3. deepseek 关 thinking：本循环要的是非思考型响应。

重试面：单发请求（POST `/chat/completions`）本是**无状态**的，重发不会污染任何
服务端状态，所以瞬时故障（连不上 / 建连超时 / 连接被掐断 / 502·503·504）**有界重试
2 次**，与 `java_client` 共用 `app/retry.py` 那一份原语。两条刻意留白的边界：

- **读超时不重试**（`ReadTimeout` 不在 `RETRYABLE_TRANSPORT` 里）：默认 60s 一发，
  2 次重试 = 180s = 整个 run 的总预算，且每次重发都要**重复付费**；
- **429 不重试**：0.2/0.4 秒的退避清不掉限流窗口，而更长的退避会吃掉预算。
  如实报 `provider_error`，让 run 以真实原因结束比"悄悄重试三次还是失败"更好排查。
"""

from __future__ import annotations

from typing import Any, Protocol
from urllib.parse import urlparse

import httpx

from . import config
from . import retry
from .schemas import ModelError

# 单轮返回的工具调用上限（协议级）。loop 的预算闸与本层的早筛共用这一个值，
# 避免两处各写一个数之后悄悄漂移（漂移会让超限的报错码在两处不一致）。
MAX_CALLS_PER_TURN = 8

_USAGE_KEYS = ("prompt_tokens", "completion_tokens", "total_tokens")


def settings_ready() -> bool:
    """是否配了真实模型。没配时如实失败，不假装能干活。"""
    return bool(config.AICAP_LLM_API_KEY and config.AICAP_LLM_MODEL and config.AICAP_LLM_BASE_URL)


class ModelProtocol(Protocol):
    """模型客户端的**结构性**接口 —— 测试注入脚本化假模型靠它。

    只声明这一个方法：`loop` 需要的全部能力就是"给我下一步"。
    """

    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        *,
        final: bool = False,
    ) -> tuple[dict[str, Any], dict[str, int]]: ...


class ModelClient:
    """OpenAI 兼容的 Chat Completions 客户端。

    `transport` 是测试注入点（`httpx.MockTransport`），与 `JavaClient` 同形。
    每次 `complete` 起一个短连接：归档也是这个形状，而循环至多 6 步、每步本身
    就是秒级，省下的连接复用抵不过多一份生命周期管理的复杂度。
    """

    def __init__(
        self,
        model: str | None = None,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout: float | None = None,
    ) -> None:
        self.model = model or config.AICAP_LLM_MODEL
        self._transport = transport
        self._timeout = timeout if timeout is not None else config.AICAP_LLM_TIMEOUT

    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        *,
        final: bool = False,
    ) -> tuple[dict[str, Any], dict[str, int]]:
        """发一次请求，返回 `(assistant 消息, usage)`。

        抛的只有 `ModelError` —— 调用方（`loop`）不需要认识 httpx 的任何异常类型。
        """
        if not settings_ready():
            raise ModelError("not_configured", "服务端尚未配置模型密钥或模型名称")

        base = config.AICAP_LLM_BASE_URL.rstrip("/")
        parsed = urlparse(base)
        if parsed.scheme != "https" and not (
            parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1")
        ):
            raise ModelError("invalid_config", "模型地址需要 HTTPS（本机测试服务除外）")

        body: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "stream": False,
            "max_tokens": 6000,
        }
        # deepseek 当前默认走 thinking；本循环要的是非思考型响应
        if parsed.hostname == "api.deepseek.com":
            body["thinking"] = {"type": "disabled"}
        if final:
            body["response_format"] = {"type": "json_object"}
        else:
            body.update(tools=tools, tool_choice="auto")

        try:
            async with httpx.AsyncClient(
                timeout=self._timeout,
                follow_redirects=False,
                transport=self._transport,
            ) as client:

                async def attempt() -> httpx.Response:
                    response = await client.post(
                        base + "/chat/completions",
                        json=body,
                        headers={"Authorization": "Bearer " + config.AICAP_LLM_API_KEY},
                    )
                    if response.status_code in retry.RETRYABLE_STATUS:
                        # 502/503/504：提供方过载/重启，是**经典**的瞬时故障
                        raise retry.TransientResponse(response)
                    return response

                # 重试原语与 `java_client` 共用一份（`app/retry.py`）：算不算瞬时故障、
                # 退避多久、最多几次，全层只有一个定义。读完预算用尽后把应答还原回来，
                # 状态码与红线②的"不回显 body"都不受影响。
                try:
                    response = await retry.call_with_retry(attempt)
                except retry.TransientResponse as exhausted:
                    response = exhausted.response
        except httpx.TimeoutException:
            # 注意：**读超时**不在重试范围内（见 `retry.RETRYABLE_TRANSPORT`）——
            # 60s 一发，2 次重试就是 180s，等于把循环的总预算整个吃掉，
            # 而且每次重发都要为一次生成重复付费。`ConnectTimeout` 会先被重试，
            # 预算用尽后才落到这里。
            raise ModelError("provider_timeout", "模型请求超时，可重试") from None
        except httpx.HTTPError:
            raise ModelError("provider_unreachable", "无法连接模型服务，请检查服务端网络") from None

        if response.status_code != 200:
            # 红线②：绝不持久化/回显上游 body 与 header
            raise ModelError(
                "provider_error",
                f"模型服务返回 HTTP {response.status_code}，请检查配置或稍后重试",
            )

        try:
            data = response.json()
            choice = data["choices"][0]
            message = choice["message"]
            if choice.get("finish_reason") not in ("stop", "tool_calls"):
                raise ModelError("incomplete_response", "模型输出被截断或中断，本次 run 未产出结果")
            calls = message.get("tool_calls") or []
            if not isinstance(calls, list) or len(calls) > MAX_CALLS_PER_TURN:
                raise ValueError("invalid tool calls")
            for call in calls:
                if (
                    not isinstance(call.get("id"), str)
                    or call.get("type") != "function"
                    or not isinstance(call.get("function", {}).get("name"), str)
                    or not isinstance(call["function"].get("arguments"), str)
                ):
                    raise ValueError("invalid tool call")
            content = message.get("content")
            if content is not None and not isinstance(content, str):
                raise ValueError("invalid content")
            if final and calls:
                raise ValueError("tools in final response")
        except ModelError:
            raise
        except (ValueError, KeyError, IndexError, TypeError, AttributeError):
            raise ModelError("invalid_response", "模型服务返回了无法解析的响应") from None

        usage = data.get("usage") or {}
        assistant: dict[str, Any] = {"role": "assistant", "content": content}
        if calls:
            assistant["tool_calls"] = calls
        return assistant, {
            key: value for key, value in usage.items() if key in _USAGE_KEYS and isinstance(value, int)
        }
