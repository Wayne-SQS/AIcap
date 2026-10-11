"""测试级全局摆位。

**零退避**：重试的退避（0.2s / 0.4s）是**生产**行为，由 `tests/test_retry.py`
显式注入记录器逐个钉住。测试套件里另有一批用例是**故意**让上游失败的
（`test_connection_failure_raises_platform_unavailable`、模型侧的超时/断连），
它们全都会命中重试路径 —— 真睡 0.6 秒 × N 会把套件从 1.5 秒拖成十几秒，
而它们要断言的是"重试了几次"，不是"睡了多久"。

写成 autouse fixture 而不是逐用例 monkeypatch：漏掉一处就是一个悄悄变慢的套件，
而"忘了改退避"这件事没有任何断言能发现。
"""

from __future__ import annotations

import pytest

from app import retry


@pytest.fixture(autouse=True)
def _no_real_retry_backoff(monkeypatch: pytest.MonkeyPatch) -> None:
    """把退避基数与抖动压到 0 —— `backoff_delay` 在**调用时**读模块常量，所以生效。"""
    monkeypatch.setattr(retry, "READ_RETRY_BASE_DELAY", 0.0)
    monkeypatch.setattr(retry, "READ_RETRY_JITTER", 0.0)
