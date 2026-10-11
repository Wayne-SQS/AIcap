"""`app.retry` —— 有界重试原语本身。

这一层是「统一落点」：`java_client`（对 Java 平台）与 `model_client`（对模型服务）
两个互不认识的出口都只用它，所以"最多几次、退避多久、什么算瞬时故障"只需在这里钉一次。
两个出口各自的行为断言在 `test_java_client.py` / `test_model_client.py` 里。

**这里注入 `sleep` 记录器与固定的 `uniform`，一次都不真睡** —— 退避数值本身是被测对象，
必须确定性地断言，而不是靠"跑得快"来间接确认。
"""

from __future__ import annotations

import asyncio

import httpx
import pytest

from app import retry


class _Sleeper:
    """记录 sleep 时长、**不真睡**。"""

    def __init__(self) -> None:
        self.delays: list[float] = []

    async def __call__(self, delay: float) -> None:
        self.delays.append(delay)


def _no_jitter(low: float, high: float) -> float:
    """固定抖动为 0，让退避完全确定。"""
    return 0.0


# --------------------------------------------------------------------------
# 退避曲线
# --------------------------------------------------------------------------


def test_backoff_doubles_each_attempt():
    """0.2s → 0.4s：指数退避，总附加耗时 ≈0.6s，可预期。

    这里**显式传** `base_delay` / `jitter`（而不是吃模块默认值）：套件级的零退避
    fixture 会把模块常量压成 0，退避数值这条断言不该依赖"哪个 fixture 生效了"。
    """
    assert retry.backoff_delay(1, base=0.2, jitter=0.05, uniform=_no_jitter) == pytest.approx(0.2)
    assert retry.backoff_delay(2, base=0.2, jitter=0.05, uniform=_no_jitter) == pytest.approx(0.4)


def test_backoff_jitter_is_bounded():
    """抖动存在（防惊群）但**有上界** —— 否则"总耗时可预期"就不成立了。"""
    upper = retry.backoff_delay(1, base=0.2, jitter=0.05, uniform=lambda low, high: high)
    lower = retry.backoff_delay(1, base=0.2, jitter=0.05, uniform=lambda low, high: low)

    assert lower == pytest.approx(0.2)
    assert upper == pytest.approx(0.25)
    # 用字面量而不是 `retry.READ_RETRY_JITTER`：套件级 fixture 把模块常量压成 0 了
    assert upper - lower <= 0.05


def test_backoff_reads_module_constants_at_call_time(monkeypatch: pytest.MonkeyPatch):
    """默认值必须在**调用时**读模块常量。

    写成默认参数就会在 `def` 那一刻固化，`monkeypatch` 改不动 —— 测试只能真睡。
    这条断言就是"零退避 fixture 真的有效"的根据。
    """
    monkeypatch.setattr(retry, "READ_RETRY_BASE_DELAY", 0.0)
    monkeypatch.setattr(retry, "READ_RETRY_JITTER", 0.0)

    assert retry.backoff_delay(1) == 0.0
    assert retry.backoff_delay(2) == 0.0


# --------------------------------------------------------------------------
# 预算与收敛
# --------------------------------------------------------------------------


async def test_two_retries_at_most_three_attempts():
    """2 次重试 = 最多 3 次尝试，退避序列 0.2 / 0.4。"""
    calls: list[int] = []
    sleeper = _Sleeper()

    async def always_fails() -> None:
        calls.append(1)
        raise httpx.ConnectError("down")

    with pytest.raises(httpx.ConnectError):
        await retry.call_with_retry(
            always_fails,
            sleep=sleeper,
            base_delay=0.2,
            jitter=0.05,
            uniform=_no_jitter,
        )

    assert len(calls) == 3
    assert sleeper.delays == pytest.approx([0.2, 0.4])


async def test_retry_stops_as_soon_as_the_call_succeeds():
    """「第一次失败、第二次成功」：只 sleep 一次，返回值原样透出。"""
    calls: list[int] = []
    sleeper = _Sleeper()

    async def flaky() -> str:
        calls.append(1)
        if len(calls) == 1:
            raise httpx.ConnectError("blip")
        return "ok"

    assert await retry.call_with_retry(flaky, sleep=sleeper) == "ok"
    assert len(calls) == 2
    assert len(sleeper.delays) == 1, "成功后不该再退避"


async def test_non_transient_failure_is_not_retried():
    """`should_retry` 说不算瞬时的，一次都不重试、一秒都不睡。"""
    calls: list[int] = []
    sleeper = _Sleeper()

    async def boom() -> None:
        calls.append(1)
        raise ValueError("不是瞬时故障")

    with pytest.raises(ValueError):
        await retry.call_with_retry(boom, sleep=sleeper)

    assert len(calls) == 1
    assert sleeper.delays == []


async def test_zero_attempts_means_no_retry():
    """`attempts=0`（不是默认值）就是"不许重试" —— 调用方可以自己收紧。"""
    calls: list[int] = []

    async def boom() -> None:
        calls.append(1)
        raise httpx.ConnectError("down")

    with pytest.raises(httpx.ConnectError):
        await retry.call_with_retry(boom, attempts=0, sleep=_Sleeper())

    assert len(calls) == 1


async def test_cancellation_is_never_retried():
    """`asyncio.CancelledError` 是 `BaseException`，**绝不能被当成瞬时故障重试**。

    否则一次取消（客户端断连、进程 shutdown）会变成三次网络往返，
    并且 `except BaseException` 还会顺手吞掉 `GeneratorExit` 语义。
    """
    calls: list[int] = []

    async def cancelled() -> None:
        calls.append(1)
        raise asyncio.CancelledError()

    with pytest.raises(asyncio.CancelledError):
        await retry.call_with_retry(cancelled, sleep=_Sleeper())

    assert len(calls) == 1


# --------------------------------------------------------------------------
# 「什么算瞬时故障」的分类
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "error",
    [
        httpx.ConnectError("refused"),
        httpx.ConnectTimeout("connect slow"),
        httpx.PoolTimeout("pool busy"),
        httpx.WriteError("write failed"),
        httpx.WriteTimeout("write slow"),
        httpx.ReadError("reset while reading"),
        httpx.RemoteProtocolError("peer closed early"),
    ],
    ids=lambda e: type(e).__name__,
)
def test_connection_level_failures_are_transient(error: BaseException):
    """请求大概率没被处理完的那一类，重发安全。"""
    assert retry.is_transient(error) is True


@pytest.mark.parametrize(
    "error",
    [
        httpx.ReadTimeout("server too slow"),
        httpx.UnsupportedProtocol("nope"),
        httpx.LocalProtocolError("bad request we built"),
        httpx.DecodingError("bad bytes"),
    ],
    ids=lambda e: type(e).__name__,
)
def test_read_timeout_and_protocol_errors_are_not_transient(error: BaseException):
    """`ReadTimeout` 点名不在重试范围内 —— 见 `retry.RETRYABLE_TRANSPORT` 的注释。

    Java 侧 30s / 模型侧 60s 一发，2 次重试分别是 90s / 180s，
    后者的数字就是循环的**整个**总预算；模型那一侧还要为每次重发重复付费。
    """
    assert retry.is_transient(error) is False


def test_transient_response_carries_the_original_response():
    """`TransientResponse` 是"这一发不算"的信号，必须**带着原应答**，

    否则重试预算用尽后只能报一句"无法连接"，状态码与 Java 的 `detail` 一起丢掉。
    """
    response = httpx.Response(503, json={"detail": "维护中"})

    signal = retry.TransientResponse(response)

    assert retry.is_transient(signal) is True
    assert signal.response is response


def test_only_gateway_statuses_are_retryable():
    """只有 502/503/504。

    `500` 是服务端确定性缺陷（重试只是把同一个失败延迟 0.6 秒）；
    `429` 的退避窗口远大于 0.2/0.4 秒（重试等于白等，更长又会吃掉预算）；
    `4xx` 全是客户端错误（令牌过期 / 参数不合法 / 会话不是你的）。
    """
    assert retry.RETRYABLE_STATUS == frozenset({502, 503, 504})
    for status in (400, 401, 403, 404, 422, 429, 500, 501, 505):
        assert status not in retry.RETRYABLE_STATUS


async def test_a_transient_status_is_retried_by_the_primitive():
    """原语层面：`TransientResponse` 会触发重试，成功即返回。"""
    calls: list[int] = []

    async def flaky() -> str:
        calls.append(1)
        if len(calls) == 1:
            raise retry.TransientResponse(httpx.Response(503))
        return "ok"

    assert await retry.call_with_retry(flaky, sleep=_Sleeper()) == "ok"
    assert len(calls) == 2
