"""`ModelClient`：请求体形状、final 分支，以及**安全面三条**。

安全面是移植归档时一条都不能少的（归档 `backend/app/meeting_agent/model_client.py`）：

1. **非 200 绝不回显 body**（红线②的同一条理由：上游 body 可能回显凭据或私有文本）；
2. 地址必须 HTTPS（明文发出去的是 API key，本机测试服务除外）；
3. 模型失败一律收敛成 `ModelError` —— 调用方（`loop`）不该认识 httpx 的任何异常类型。
"""

from __future__ import annotations

import json

import httpx
import pytest

from app import config
from app.model_client import ModelClient, settings_ready
from app.schemas import ModelError

BASE = "https://api.test"
MESSAGES = [{"role": "user", "content": "现在有几个任务在推进？"}]
TOOLS = [{"type": "function", "function": {"name": "query_tasks", "description": "查询任务"}}]


@pytest.fixture
def configured(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "AICAP_LLM_API_KEY", "sk-test")
    monkeypatch.setattr(config, "AICAP_LLM_MODEL", "test-model")
    monkeypatch.setattr(config, "AICAP_LLM_BASE_URL", BASE)


def _client(handler, *, timeout: float | None = None) -> ModelClient:
    return ModelClient(transport=httpx.MockTransport(handler), timeout=timeout)


def _reply(message: dict, *, finish: str = "stop", usage: dict | None = None) -> dict:
    return {
        "choices": [{"message": message, "finish_reason": finish}],
        "usage": usage if usage is not None else {"prompt_tokens": 3, "completion_tokens": 4},
    }


def _ok(payload: dict, *, captured: list[httpx.Request] | None = None):
    def handler(request: httpx.Request) -> httpx.Response:
        if captured is not None:
            captured.append(request)
        return httpx.Response(200, json=payload)

    return handler


async def _send(client: ModelClient, *, final: bool = False):
    return await client.complete(MESSAGES, TOOLS, final=final)


# --------------------------------------------------------------------------
# 配置面
# --------------------------------------------------------------------------


def test_settings_ready_is_false_without_a_key(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(config, "AICAP_LLM_API_KEY", "")
    assert settings_ready() is False


def test_settings_ready_is_false_without_a_model(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(config, "AICAP_LLM_API_KEY", "sk-test")
    monkeypatch.setattr(config, "AICAP_LLM_MODEL", "")
    assert settings_ready() is False


async def test_unconfigured_fails_before_any_request(monkeypatch: pytest.MonkeyPatch):
    """没配密钥 → `not_configured`，而且**一个请求都不发**（连 DNS 都不碰）。"""
    monkeypatch.setattr(config, "AICAP_LLM_API_KEY", "")

    def handler(request: httpx.Request) -> httpx.Response:  # pragma: no cover
        raise AssertionError("未配置时不该发出任何请求")

    with pytest.raises(ModelError) as caught:
        await _send(_client(handler))

    assert caught.value.code == "not_configured"


async def test_plain_http_to_a_remote_host_is_rejected(configured, monkeypatch):
    """明文 HTTP 发出去的是 API key —— 非本机一律拒绝，且拒绝在建连之前。"""
    monkeypatch.setattr(config, "AICAP_LLM_BASE_URL", "http://api.example.com")

    def handler(request: httpx.Request) -> httpx.Response:  # pragma: no cover
        raise AssertionError("不该发出请求")

    with pytest.raises(ModelError) as caught:
        await _send(_client(handler))

    assert caught.value.code == "invalid_config"


async def test_plain_http_to_localhost_is_allowed(configured, monkeypatch):
    """本机测试服务（ollama / vllm / mock）是例外，否则本地根本没法联调。"""
    monkeypatch.setattr(config, "AICAP_LLM_BASE_URL", "http://127.0.0.1:11434/v1")
    captured: list[httpx.Request] = []

    message, _ = await _send(_client(_ok(_reply({"role": "assistant", "content": "hi"}), captured=captured)))

    assert message["content"] == "hi"
    assert str(captured[0].url) == "http://127.0.0.1:11434/v1/chat/completions"


# --------------------------------------------------------------------------
# 请求体形状
# --------------------------------------------------------------------------


async def test_request_body_shape_includes_tools(configured):
    captured: list[httpx.Request] = []
    client = _client(_ok(_reply({"role": "assistant", "content": None, "tool_calls": [
        {"id": "c1", "type": "function", "function": {"name": "query_tasks", "arguments": "{}"}}
    ]}, finish="tool_calls"), captured=captured))

    await _send(client)

    request = captured[0]
    assert str(request.url) == BASE + "/chat/completions"
    assert request.headers["authorization"] == "Bearer sk-test"
    body = json.loads(request.content)
    assert body["model"] == "test-model"
    assert body["messages"] == MESSAGES
    assert body["stream"] is False
    assert body["tools"] == TOOLS
    assert body["tool_choice"] == "auto"
    assert "response_format" not in body


async def test_final_branch_asks_for_json_and_drops_tools(configured):
    """收尾那一发要的是**结构化结论**：带 `response_format`、不带 tools。

    带 tools 的话模型还能再调工具，循环的四个闸就白设了（这一步必须是终点）。
    """
    captured: list[httpx.Request] = []
    client = _client(_ok(_reply({"role": "assistant", "content": '{"answer":"ok"}'}), captured=captured))

    message, _ = await _send(client, final=True)

    body = json.loads(captured[0].content)
    assert body["response_format"] == {"type": "json_object"}
    assert "tools" not in body and "tool_choice" not in body
    assert message == {"role": "assistant", "content": '{"answer":"ok"}'}


async def test_deepseek_gets_thinking_disabled(configured, monkeypatch):
    monkeypatch.setattr(config, "AICAP_LLM_BASE_URL", "https://api.deepseek.com")
    captured: list[httpx.Request] = []

    await _send(_client(_ok(_reply({"role": "assistant", "content": "ok"}), captured=captured)))

    assert json.loads(captured[0].content)["thinking"] == {"type": "disabled"}


async def test_other_providers_do_not_get_a_thinking_field(configured):
    """`thinking` 是 deepseek 的参数，发给别家可能直接被 400 拒掉。"""
    captured: list[httpx.Request] = []

    await _send(_client(_ok(_reply({"role": "assistant", "content": "ok"}), captured=captured)))

    assert "thinking" not in json.loads(captured[0].content)


# --------------------------------------------------------------------------
# 安全面
# --------------------------------------------------------------------------


async def test_non_200_never_echoes_the_upstream_body(configured):
    """红线②：上游 body 里可能回显凭据或私有文本，错误信息里**只有状态码**。"""
    secret = "sk-leaked-into-error-body"
    client = _client(lambda request: httpx.Response(500, text=f"upstream dump: {secret}"))

    with pytest.raises(ModelError) as caught:
        await _send(client)

    error = caught.value
    assert error.code == "provider_error"
    assert "500" in error.message
    assert secret not in error.message
    assert secret not in str(error)


async def test_timeout_maps_to_provider_timeout(configured):
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("too slow")

    with pytest.raises(ModelError) as caught:
        await _send(_client(handler))

    assert caught.value.code == "provider_timeout"


async def test_connection_error_maps_to_provider_unreachable(configured):
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route to host")

    with pytest.raises(ModelError) as caught:
        await _send(_client(handler))

    assert caught.value.code == "provider_unreachable"


# --------------------------------------------------------------------------
# 响应校验
# --------------------------------------------------------------------------


async def test_non_json_body_is_invalid_response(configured):
    client = _client(lambda request: httpx.Response(200, text="<html>502 bad gateway</html>"))

    with pytest.raises(ModelError) as caught:
        await _send(client)

    assert caught.value.code == "invalid_response"


async def test_truncated_output_is_incomplete_response(configured):
    """`finish_reason=length` 说明输出被截断 —— 半截结论不能当结论用。"""
    client = _client(_ok(_reply({"role": "assistant", "content": "半截"}, finish="length")))

    with pytest.raises(ModelError) as caught:
        await _send(client)

    assert caught.value.code == "incomplete_response"


async def test_tool_call_with_non_string_arguments_is_rejected(configured):
    """`arguments` 必须是**字符串**（JSON 文本）。上游给对象的话循环里 `json.loads`
    会直接炸，收敛在这里比在后面每一处都判一次便宜。"""
    client = _client(_ok(_reply({"role": "assistant", "content": None, "tool_calls": [
        {"id": "c1", "type": "function", "function": {"name": "query_tasks", "arguments": {"a": 1}}}
    ]}, finish="tool_calls")))

    with pytest.raises(ModelError) as caught:
        await _send(client)

    assert caught.value.code == "invalid_response"


async def test_too_many_tool_calls_in_one_turn_is_rejected(configured):
    """单轮 9 个工具调用：`loop` 的 `MAX_CALLS_PER_TURN` 是 8，这里先挡一层，
    免得把明显异常的响应搬进 messages 再撞闸。"""
    calls = [
        {"id": f"c{i}", "type": "function", "function": {"name": "query_tasks", "arguments": "{}"}}
        for i in range(9)
    ]
    client = _client(
        _ok(_reply({"role": "assistant", "content": None, "tool_calls": calls}, finish="tool_calls"))
    )

    with pytest.raises(ModelError) as caught:
        await _send(client)

    assert caught.value.code == "invalid_response"


async def test_final_response_must_not_carry_tool_calls(configured):
    """收尾那一发要的是一句话，不是又一次工具调用 —— 给了就该判为不可解析。"""
    calls = [
        {"id": "c1", "type": "function", "function": {"name": "query_tasks", "arguments": "{}"}}
    ]
    client = _client(
        _ok(_reply({"role": "assistant", "content": None, "tool_calls": calls}, finish="tool_calls"))
    )

    with pytest.raises(ModelError) as caught:
        await _send(client, final=True)

    assert caught.value.code == "invalid_response"


async def test_tool_calls_and_usage_survive_the_round_trip(configured):
    calls = [
        {"id": "c1", "type": "function", "function": {"name": "query_tasks", "arguments": "{}"}}
    ]
    client = _client(
        _ok(
            _reply(
                {"role": "assistant", "content": "先查一下", "tool_calls": calls},
                finish="tool_calls",
                usage={"prompt_tokens": 11, "completion_tokens": 22, "total_tokens": 33, "cache_hit": 1},
            )
        )
    )

    message, usage = await _send(client)

    assert message["tool_calls"] == calls
    assert message["content"] == "先查一下"
    # 只留认识的三个 int 字段：`cache_hit` 之类的杂项不该渗进事件的 detail
    assert usage == {"prompt_tokens": 11, "completion_tokens": 22, "total_tokens": 33}


async def test_usage_defaults_to_empty_when_absent(configured):
    client = _client(
        lambda request: httpx.Response(
            200, json={"choices": [{"message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}]}
        )
    )

    _, usage = await _send(client)

    assert usage == {}
