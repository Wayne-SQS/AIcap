"""直接驱动 ASGI 逐帧读 SSE —— `httpx.ASGITransport` 在这里用不了。

httpx 0.28 的 `ASGITransport.handle_async_request` 会 `await self.app(...)` **跑到底**、
把整个响应体缓冲进 `body_parts` 才返回 Response。`/chat/stream` 是永不主动结束的流
（终局后也不关，契约 §3.4 第 4 条），拿它测会一直挂着。

所以自己驱动：`send` 把响应体片段塞进队列，测试按需取。请求侧的 `receive` 阻塞在一个
断开事件上 —— 只有 `close()` 才放行，模拟真实客户端一直挂着。
"""

from __future__ import annotations

import asyncio
import json
from typing import Any
from urllib.parse import urlencode


class SSEStream:
    """一次 `GET /chat/stream` 连接。"""

    def __init__(self) -> None:
        self.status: int | None = None
        self.headers: dict[str, str] = {}
        self._chunks: asyncio.Queue[bytes | None] = asyncio.Queue()
        self._disconnect = asyncio.Event()
        self._task: asyncio.Task[Any] | None = None
        self._buffer = ""

    async def open(
        self,
        app: Any,
        path: str,
        *,
        query: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> SSEStream:
        query_string = urlencode(query or {}).encode()
        start_sent = asyncio.Event()

        scope = {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": "GET",
            "scheme": "http",
            "path": path,
            "raw_path": path.encode(),
            "query_string": query_string,
            "root_path": "",
            "headers": [(k.lower().encode(), v.encode()) for k, v in (headers or {}).items()],
            "server": ("orchestrator.test", 80),
            "client": ("testclient", 12345),
        }

        async def receive() -> dict[str, Any]:
            await self._disconnect.wait()
            return {"type": "http.disconnect"}

        async def send(message: dict[str, Any]) -> None:
            if message["type"] == "http.response.start":
                self.status = message["status"]
                self.headers = {k.decode(): v.decode() for k, v in message.get("headers", [])}
                start_sent.set()
            elif message["type"] == "http.response.body":
                body = message.get("body", b"")
                if body:
                    await self._chunks.put(body)
                if not message.get("more_body", False):
                    await self._chunks.put(None)

        self._task = asyncio.create_task(app(scope, receive, send))
        await asyncio.wait_for(start_sent.wait(), 5.0)
        return self

    async def collect(self, until: str, *, timeout: float = 5.0) -> list[dict[str, Any]]:
        """读到第一条 `event: <until>` 为止（含）并返回途中的所有帧。

        注释帧（心跳）也会在返回值里，用 `event_names()` 过滤后断言。
        """
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        frames: list[dict[str, Any]] = []
        while True:
            left = deadline - loop.time()
            if left <= 0:
                raise TimeoutError(f"等待 {until} 超时；已收到 {event_names(frames)}")
            frame = await self._next_frame(left)
            if frame is None:
                return frames
            frames.append(frame)
            if frame["kind"] == "event" and frame["event"] == until:
                return frames

    async def frame(self, *, timeout: float = 5.0) -> dict[str, Any] | None:
        """读下一条帧（注释帧也算）；流结束返回 None。"""
        return await self._next_frame(timeout)

    async def close(self) -> None:
        """断开并等应用收尾（含 Starlette 的 `BackgroundTask`）。"""
        self._disconnect.set()
        if self._task is None:
            return
        try:
            await asyncio.wait_for(self._task, 2.0)
        except TimeoutError:
            self._task.cancel()
        except asyncio.CancelledError:
            pass
        except Exception:
            # 流中途抛错（例如上游 404）是这类测试的合法结局，不该在这里再炸一次
            pass

    async def __aenter__(self) -> SSEStream:
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.close()

    # ------------------------------------------------------------------

    async def _next_frame(self, timeout: float) -> dict[str, Any] | None:
        while True:
            raw, self._buffer = _split(self._buffer)
            if raw is not None:
                return _parse(raw)
            try:
                chunk = await asyncio.wait_for(self._chunks.get(), timeout)
            except TimeoutError:
                raise TimeoutError(
                    f"等待 SSE 帧超时；已缓冲 {self._buffer!r}"
                ) from None
            if chunk is None:
                if self._buffer.strip():
                    raw, self._buffer = self._buffer, ""
                    return _parse(raw)
                return None
            self._buffer += chunk.decode("utf-8")


def _split(buffer: str) -> tuple[str | None, str]:
    index = buffer.find("\n\n")
    if index < 0:
        return None, buffer
    return buffer[:index], buffer[index + 2 :]


def _parse(raw: str) -> dict[str, Any]:
    if raw.startswith(":"):
        return {"kind": "comment", "text": raw.lstrip(": ").strip()}
    fields: dict[str, str] = {}
    for line in raw.splitlines():
        key, _, value = line.partition(": ")
        fields[key] = value
    envelope = json.loads(fields["data"])
    return {
        "kind": "event",
        "id": int(fields["id"]),
        "event": fields.get("event"),
        "seq": envelope["seq"],
        "data": envelope["data"],
        "ts": envelope["ts"],
    }


def event_names(frames: list[dict[str, Any]]) -> list[str]:
    """帧里的事件类型（注释帧记成 `:ping`），用于失败信息与断言。"""
    return [f["event"] if f["kind"] == "event" else ":ping" for f in frames]


def events_only(frames: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [f for f in frames if f["kind"] == "event"]
