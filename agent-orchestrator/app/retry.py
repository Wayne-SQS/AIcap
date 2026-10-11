"""幂等读的**有界重试** —— 全层唯一的重试落点。

为什么单独一个模块：`java_client` 与 `model_client` 是两个互不认识的 HTTP 出口
（前者对 Java 平台，后者对模型服务），但「什么算瞬时故障、退避多久、最多重试几次」
只能有一份定义。写两份必然漂移 —— 漂移之后，同一个网络抖动在一个出口是"再试一次"，
在另一个出口是 `run.error`，而用户看到的是半截对话。

三条边界，一条都不能松：

1. **只给幂等调用**。`JavaClient` 侧把「可重试」与「不可重试」拆成两个方法，
   重试只挂在读路径上；写路径（`append_event` / `put_checkpoint` /
   `create_session`）**结构上够不到** `call_with_retry`。刻意不用
   `retry=True` 这种开关 —— 开关总有一天会被复制到写路径上，而重复入库的
   事件没人能事后分辨出来。
2. **4xx 绝不重试**。令牌过期、参数不合法、会话不是你的 —— 这些都是服务端
   给出的**确定性**应答，重发只会得到同一个答案，代价是把错误推迟 0.6 秒才报出来。
3. **读超时不重试**。理由见 `RETRYABLE_TRANSPORT` 上方。

本模块不认识 `PlatformUnavailable` / `ModelError`：它只按 httpx 的异常类型分类，
把 HTTP 语义映射成业务异常是各出口自己的事。
"""

from __future__ import annotations

import asyncio
import random
from typing import Awaitable, Callable, TypeVar

import httpx

T = TypeVar("T")

# 重试预算：**2 次重试**，即最多 3 次尝试。总附加耗时 = 两次退避之和
# （默认 0.2 + 0.4 ≈ 0.6s，另加 ≤100ms 抖动），可预期、可写进文档。
READ_RETRY_ATTEMPTS = 2

# 指数退避：第 1 次重试前 0.2s，第 2 次前 0.4s，各自再加 ≤50ms 抖动。
# 抖动是为了让多条并发连接的重试不撞在同一刻（惊群）；上限刻意取小值 ——
# 这套重试是给"瞬时抖动"用的，不是给"服务真的慢了"用的，退避太长反而吃掉循环预算。
READ_RETRY_BASE_DELAY = 0.2
READ_RETRY_JITTER = 0.05

# 可重试的**传输层**异常：这一类意味着"请求大概率没被处理完"，重发安全。
#
#   * `ConnectError` / `ConnectTimeout` —— TCP 都没建起来，请求根本没送达；
#   * `PoolTimeout` / `WriteError` / `WriteTimeout` —— 请求没写出去；
#   * `ReadError` / `RemoteProtocolError` —— 连接在读取途中被对端掐断。
#
# **`ReadTimeout` 刻意不在列表里**（`is_transient` 会返回 False）：
#   Java 侧 `JAVA_TIMEOUT` 默认 30s、模型侧 `AICAP_LLM_TIMEOUT` 默认 60s，
#   而整个 run 的总预算是 180s。读超时意味着"对端收到了，只是回得慢"——
#   重试会把一次慢调用放大成 `3 × timeout`（90s / 180s），大概率直接把总预算打穿；
#   模型那一侧还要为每一次重发**重复付费**。所以读超时如实报错，让 run 以真实原因结束。
RETRYABLE_TRANSPORT: tuple[type[BaseException], ...] = (
    httpx.ConnectError,
    httpx.ConnectTimeout,
    httpx.PoolTimeout,
    httpx.WriteError,
    httpx.WriteTimeout,
    httpx.ReadError,
    httpx.RemoteProtocolError,
)

# 可重试的 HTTP 状态码：网关/服务**暂时**不可用（Java 重启中、模型提供方过载）。
# 只有 502/503/504 —— `500` 是"服务端自己炸了"（确定性缺陷），重试三次只是把同一个
# 失败延迟 0.6 秒报出来；`429` 也不在（见 `is_transient` 的注释）。
RETRYABLE_STATUS = frozenset({502, 503, 504})


class TransientResponse(Exception):
    """内部信号：收到了一个**可重试状态码**的应答。

    它不是错误，是「这一发不算，再试一次」的标记。重试预算用尽后由调用方把它
    还原成原来的 `response` 走正常映射 —— 绝不能当异常吞掉，否则 `503` 会退化成
    一句"无法连接"，状态码与 Java 的 `detail` 一起丢掉，排障时无从下手。
    """

    def __init__(self, response: httpx.Response) -> None:
        super().__init__(f"retryable status {response.status_code}")
        self.response = response


def is_transient(exc: BaseException) -> bool:
    """这一发失败算不算「瞬时故障」（默认判据，两个出口共用）。

    - 传输层连接失败（`RETRYABLE_TRANSPORT`）：算。
    - `ReadTimeout`：**不算** —— 见上面那段预算/付费的说明。
    - 可重试状态码（`TransientResponse`，502/503/504）：算。
    - 其余 httpx 错误（`UnsupportedProtocol` / `LocalProtocolError` /
      `DecodingError`）与 **所有 4xx/5xx**：不算 —— 它们要么是客户端错误，
      要么是服务端的确定性应答。
    """
    return isinstance(exc, (TransientResponse, *RETRYABLE_TRANSPORT))


def backoff_delay(
    attempt: int,
    *,
    base: float | None = None,
    jitter: float | None = None,
    uniform: Callable[[float, float], float] = random.uniform,
) -> float:
    """第 `attempt` 次重试（从 1 起）之前的退避秒数：`base · 2^(attempt-1)` + 抖动。

    默认值在**调用时**从模块常量读（而不是写成默认参数）—— 写成默认参数会在
    `def` 那一刻被固化，`monkeypatch.setattr(retry, "READ_RETRY_BASE_DELAY", 0.0)`
    就改不动了，测试只能真睡 0.6 秒。
    """
    resolved_base = READ_RETRY_BASE_DELAY if base is None else base
    resolved_jitter = READ_RETRY_JITTER if jitter is None else jitter
    return resolved_base * (2 ** (attempt - 1)) + uniform(0.0, resolved_jitter)


async def call_with_retry(
    call: Callable[[], Awaitable[T]],
    *,
    should_retry: Callable[[BaseException], bool] = is_transient,
    attempts: int | None = None,
    base_delay: float | None = None,
    jitter: float | None = None,
    sleep: Callable[[float], Awaitable[None]] | None = None,
    uniform: Callable[[float, float], float] = random.uniform,
) -> T:
    """跑 `call`；`should_retry(exc)` 为真的失败最多**再**试 `attempts` 次。

    全部旋钮都可注入，测试才能确定性地摆出「第一次失败、第二次成功」而不真睡：

    - `sleep` 换成记录器即可（默认 `asyncio.sleep`）；
    - `uniform` 固定返回 0 即可让退避完全确定（默认 `random.uniform`）；
    - `attempts` / `base_delay` / `jitter` 不传时读模块常量，因此 monkeypatch 生效。

    `except Exception` 而不是 `BaseException` 是刻意的：`asyncio.CancelledError`
    在 3.8+ 属于 `BaseException`，**取消绝不能被当成瞬时故障重试**（那会让
    一次取消变成三次网络往返，还会吞掉 `GeneratorExit` 语义）。
    """
    budget = READ_RETRY_ATTEMPTS if attempts is None else attempts
    sleeper = asyncio.sleep if sleep is None else sleep
    last_attempt = budget + 1

    for index in range(1, last_attempt + 1):
        try:
            return await call()
        except Exception as exc:
            if index == last_attempt or not should_retry(exc):
                raise
            await sleeper(backoff_delay(index, base=base_delay, jitter=jitter, uniform=uniform))

    raise AssertionError("不可达：循环必然 return 或 raise")  # pragma: no cover
