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
# final 那一发与 json_object 的供应商硬约束
# --------------------------------------------------------------------------


async def test_final_request_supplies_the_word_json_the_provider_demands(configured):
    """`final=True` 带 `response_format=json_object`，而供应商要求**这一发的 prompt 里出现
    "json" 字样**。实测 api.deepseek.com 的原文是：

        Prompt must contain the word 'json' in some form to use 'response_format' of type 'json_object'.

    本层的 prompt 是纯中文，没有这个词 —— 不补这一句，"问一句 → 出最终答案"这条路
    每一步都会死在最后一发上（真机上就是这样：工具全调成功、路由全对，最后 400）。"""
    captured: list[httpx.Request] = []
    client = _client(_ok(_reply({"role": "assistant", "content": "{}"}), captured=captured))

    await client.complete(MESSAGES, TOOLS, final=True)

    body = json.loads(captured[0].content)
    assert body["response_format"] == {"type": "json_object"}
    assert "tools" not in body and "tool_choice" not in body
    assert any("json" in str(m.get("content") or "").lower() for m in body["messages"])
    # 信封必须点名 answer：只写"字段自定"会让模型每轮发明字段，前端渲染不了，
    # 只能把裸 JSON 灌进气泡（真机实测被用户骂"垃圾"）。契约 §3.3「最终答复信封」。
    assert any("answer" in str(m.get("content") or "") for m in body["messages"])


async def test_final_request_does_not_mutate_the_callers_messages(configured):
    """补的那句只进**本次请求体**：`messages` 会被写进检查点，改它等于污染恢复现场。"""
    captured: list[httpx.Request] = []
    client = _client(_ok(_reply({"role": "assistant", "content": "{}"}), captured=captured))
    messages = [dict(m) for m in MESSAGES]

    await client.complete(messages, TOOLS, final=True)

    assert len(messages) == 1, "调用方的 messages 不得被追加"
    assert len(json.loads(captured[0].content)["messages"]) == 2, "请求体里应有补的那一句"


async def test_final_request_is_not_doubled_when_prompt_already_mentions_json(configured):
    captured: list[httpx.Request] = []
    client = _client(_ok(_reply({"role": "assistant", "content": "{}"}), captured=captured))
    messages = [{"role": "user", "content": "请用 json 回答"}]

    await client.complete(messages, TOOLS, final=True)

    assert len(json.loads(captured[0].content)["messages"]) == 1, "已经有了就不重复补"


async def test_tool_turn_keeps_tools_and_needs_no_json_mention(configured):
    captured: list[httpx.Request] = []
    client = _client(_ok(_reply({"role": "assistant", "content": "…"}), captured=captured))

    await client.complete(MESSAGES, TOOLS, final=False)

    body = json.loads(captured[0].content)
    assert body["tool_choice"] == "auto" and body["tools"] == TOOLS
    assert "response_format" not in body


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
# 有界重试（任务 A）
#
# 重试原语在 `test_retry.py`；这里钉的是模型出口的**接入方式**：
# 单发 POST 是无状态的，瞬时故障该重试；读超时与 429 刻意不重试。
# 套件级 conftest 把退避压成 0，所以这些用例跑满 3 次尝试却一次都不真睡。
# --------------------------------------------------------------------------


def _counting_handler(calls: list[httpx.Request], *steps):
    """按脚本回放（`httpx.Response` 就返回、异常就抛），并记录每一发。

    **最后一步会一直重复**（脚本用完不报错，只有一条脚本时就是"每次都这样"）——
    这样"代码错误地重试了"会表现为**次数断言失败**，而不是一个来自测试脚手架的
    `AssertionError`，失败信息才指得准。
    """
    remaining = list(steps)

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if not remaining:
            raise AssertionError("脚本用完了（测试脚本给少了）")
        step = remaining[0] if len(remaining) == 1 else remaining.pop(0)
        if isinstance(step, BaseException):
            raise step
        return step

    return handler


async def test_transient_connect_failure_is_retried(configured):
    """一次建连抖动不该让整个 run 变成 `run.error` —— 第二次成功即返回。"""
    calls: list[httpx.Request] = []
    handler = _counting_handler(
        calls,
        httpx.ConnectError("blip"),
        httpx.Response(200, json=_reply({"role": "assistant", "content": "hi"})),
    )

    message, _ = await _send(_client(handler))

    assert message["content"] == "hi"
    assert len(calls) == 2


async def test_model_retries_are_bounded(configured):
    """提供方一直连不上：发满 3 次（1 + 2）就收敛成 `provider_unreachable`。"""
    calls: list[httpx.Request] = []
    handler = _counting_handler(calls, httpx.ConnectError("no route"))

    with pytest.raises(ModelError) as caught:
        await _send(_client(handler))

    assert caught.value.code == "provider_unreachable"
    assert len(calls) == 3


async def test_provider_read_timeout_is_not_retried(configured):
    """`ReadTimeout` 不重试：默认 60s 一发，2 次重试 = 180s = 整个 run 的总预算。

    而且每次重发都要为一次生成**重复付费**。宁可如实报 `provider_timeout`。
    """
    calls: list[httpx.Request] = []
    handler = _counting_handler(calls, httpx.ReadTimeout("too slow"))

    with pytest.raises(ModelError) as caught:
        await _send(_client(handler))

    assert caught.value.code == "provider_timeout"
    assert len(calls) == 1


async def test_connect_timeout_is_retried_before_giving_up(configured):
    """连不上（`ConnectTimeout`）重试，预算用尽后仍如实报 `provider_timeout`。"""
    calls: list[httpx.Request] = []
    handler = _counting_handler(calls, httpx.ConnectTimeout("connect slow"))

    with pytest.raises(ModelError) as caught:
        await _send(_client(handler))

    assert caught.value.code == "provider_timeout"
    assert len(calls) == 3


async def test_provider_503_is_retried(configured):
    """502/503/504 是"提供方过载"，是**经典**的瞬时故障。"""
    calls: list[httpx.Request] = []
    handler = _counting_handler(
        calls,
        httpx.Response(503, text="overloaded"),
        httpx.Response(200, json=_reply({"role": "assistant", "content": "ok"})),
    )

    message, _ = await _send(_client(handler))

    assert message["content"] == "ok"
    assert len(calls) == 2


async def test_exhausted_provider_503_reports_the_status_and_leaks_nothing(configured):
    """重试预算用尽后仍是 `provider_error` + 状态码，**上游 body 一个字都不回显**。

    红线②在重试路径上同样成立：重试只是多发几次，错误信息的形状不能变。
    """
    secret = "sk-leaked-into-error-body"
    calls: list[httpx.Request] = []
    handler = _counting_handler(calls, httpx.Response(503, text=f"upstream dump: {secret}"))

    with pytest.raises(ModelError) as caught:
        await _send(_client(handler))

    error = caught.value
    assert error.code == "provider_error"
    assert "503" in error.message
    assert secret not in error.message
    assert secret not in str(error)
    assert len(calls) == 3


async def test_rate_limit_is_not_retried(configured):
    """429 不重试：0.2/0.4 秒的退避清不掉限流窗口，而更长的退避会吃掉循环预算。

    如实报 `provider_error`，让 run 以真实原因结束 —— 比"悄悄重试三次还是失败"好排查。
    """
    calls: list[httpx.Request] = []
    handler = _counting_handler(calls, httpx.Response(429, text="rate limited"))

    with pytest.raises(ModelError) as caught:
        await _send(_client(handler))

    assert caught.value.code == "provider_error"
    assert len(calls) == 1


async def test_retry_reuses_one_http_client(configured):
    """重试发生在 `AsyncClient` 生命周期**内部** —— 不为每次重试新建连接池。

    新建的话"重试"就退化成"重连"，而且 `transport` 注入点也会在第二次起失效。
    """
    calls: list[httpx.Request] = []
    handler = _counting_handler(
        calls,
        httpx.ConnectError("blip"),
        httpx.ConnectError("blip again"),
        httpx.Response(200, json=_reply({"role": "assistant", "content": "ok"})),
    )

    message, _ = await _send(_client(handler))

    assert message["content"] == "ok"
    assert len(calls) == 3
    # 三次尝试发的是同一个请求体 —— 重试不重新构造请求
    assert len({request.content for request in calls}) == 1


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
