"""进程内的 run 登记表 —— "这个会话此刻在不在跑、跑的是哪个 run"。

**它不保存状态。** 状态的唯一权威在 Java 的 `agent_checkpoints`（进程被杀就没了
的东西不能算状态）。这里只回答两个进程内问题：

1. 「这个会话现在有没有循环在跑」——`/chat` 据此回 409，`/chat/stream` 据此决定
   要不要惰性恢复；
2. 「循环又推了一条事件」——`/chat/stream` 的等待/唤醒靠它，避免轮询 Java。

**为什么不是 `asyncio.Lock`**：循环是 `asyncio.create_task` 起的独立后台任务，
SSE 端点只读事件、从不拥有循环（这正是"kill 进程后重连仍能续传"能成立的原因）。
所有权用一次**同步**的 `claim` 交接即可 —— asyncio 是单线程，两次字节码之间不
会切走，`check + set` 天然原子。用 `Lock` 反而要 `await`，把原子性交给调度器。
"""

from __future__ import annotations

import asyncio

# 唤醒信号的默认等待上限（秒）。真正的上限由调用方传（SSE 用 config 里的心跳间隔）。
_DEFAULT_WAIT = 15.0


class RunRegistry:
    """`session_id → run_id` 的进程内登记，外加一个每会话的唤醒位。"""

    def __init__(self) -> None:
        self._active: dict[str, str] = {}
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._wake: dict[str, asyncio.Event] = {}

    # ------------------------------------------------------------------
    # 所有权
    # ------------------------------------------------------------------

    def claim(self, session_id: str, run_id: str) -> bool:
        """占住该会话的循环所有权。**同步、内部无 `await`** —— 见模块 docstring。

        返回 `True` 只表示**本次调用**把空闲状态占了下来。已经是自己的 run
        （重复调用）或已被别的 run 占着，都返回 `False`：调用方据此判断
        "我是不是该起循环"，从而两个客户端同时重连也只恢复一次。
        """
        if session_id in self._active:
            return False
        self._active[session_id] = run_id
        return True

    def finish(self, session_id: str, run_id: str) -> None:
        """循环结束，注销登记并唤醒等待者，让它立刻读到终局事件。

        只有当登记的正是这个 run 时才注销 —— 防止一个迟到的旧循环把新 run 的
        登记抹掉。
        """
        if self._active.get(session_id) == run_id:
            self._active.pop(session_id, None)
        self._tasks.pop(session_id, None)
        self.notify(session_id)

    def active_run(self, session_id: str) -> str | None:
        """该会话此刻在本进程跑的 run_id；没有则 `None`。"""
        return self._active.get(session_id)

    def attach_task(self, session_id: str, task: asyncio.Task[None]) -> None:
        """持一个**强引用**。

        `asyncio` 只对任务持弱引用 —— 不存一份，循环可能跑到一半被 GC 掉，
        表现为"run 莫名其妙停在半路"，且不抛任何异常。
        """
        self._tasks[session_id] = task

    # ------------------------------------------------------------------
    # 唤醒
    # ------------------------------------------------------------------

    def notify(self, session_id: str) -> None:
        """标记"该会话有新事件了"，唤醒正在等的 SSE 生成器。

        调用方必须**先落库、后唤醒**：否则 SSE 醒来去读 Java 时事件还没写进去，
        这一轮就空转，要等下一次心跳才补上。
        """
        event = self._wake.get(session_id)
        if event is not None:
            event.set()

    def clear(self, session_id: str) -> None:
        """重开一个唤醒窗口。

        读事件**之前**调用：这样"读完发现没新事件"与"循环在这期间推了事件"之间
        不会丢唤醒 —— 后者会把标志置上，随后的 `wait` 立即返回。
        """
        self._event(session_id).clear()

    async def wait(self, session_id: str, timeout: float | None = None) -> bool:
        """等一次唤醒。返回 `True` 表示被唤醒，`False` 表示超时（该发心跳了）。

        超时**不是错误**：它是 SSE 心跳的节拍器。
        """
        try:
            await asyncio.wait_for(
                self._event(session_id).wait(),
                timeout if timeout is not None else _DEFAULT_WAIT,
            )
            return True
        except TimeoutError:
            return False

    def _event(self, session_id: str) -> asyncio.Event:
        event = self._wake.get(session_id)
        if event is None:
            event = asyncio.Event()
            self._wake[session_id] = event
        return event


# 生产用的单例。测试要模拟"进程重启"时另起 `RunRegistry()` 注入 `create_app`，
# 不要往这个上面积状态。
REGISTRY = RunRegistry()
