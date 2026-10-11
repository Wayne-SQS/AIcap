"""编排层共享的数据模型与异常。

本模块是对契约 §5.1 文件清单的一处**增补**：错误类型与 `Plan` 模型被
`java_client` / `loop` / `orchestrator` 共用，放在三者任一处都会造成反向依赖；
归档的兄弟实现（`backend/app/meeting_agent/schemas.py`）也是这个布局。

字段名以 **java-backend 代码**为准，不以契约文档示例为准 —— 两者已有 7 处
分叉，详见 README「已知分叉」。清单字段（`error_code` / `error_message` /
`latency_ms` / `args_hint`）在 Java 侧用 `@JsonProperty` 是显式钉死的。
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


# --------------------------------------------------------------------------
# Java 侧 HTTP 契约
# --------------------------------------------------------------------------


class AuthUser(BaseModel):
    """`GET /api/auth/me` 的返回（SSE 开流前的门禁）。"""

    model_config = ConfigDict(extra="ignore")

    id: int
    username: str
    display_name: str
    role: str
    color: str | None = None
    capacity_hours: int | None = None


class CatalogTool(BaseModel):
    """`GET /api/agents/catalog` 里 `tools[]` 的一个元素。

    `args_schema` 与 `args_hint` **恒存在**（画像工具只有 `args_hint`，
    会议/平台工具只有 `args_schema`），缺的那个是 `null` 而不是缺失。
    """

    model_config = ConfigDict(extra="ignore")

    name: str
    agent: str
    description: str
    args_schema: dict[str, Any] | None = None
    args_hint: str | None = None
    read_only: bool = True
    uses_retrieval: bool = False


class Catalog(BaseModel):
    """能力清单，只含元数据、不含任何业务数据。"""

    model_config = ConfigDict(extra="ignore")

    platform_version: str
    tools: list[CatalogTool]
    limitations: list[str] = Field(default_factory=list)


class ToolOutcome(BaseModel):
    """`POST /api/agents/tools/{name}` 的统一信封。

    三处必须按**代码实际行为**理解（契约文档写错了）：

    1. 业务失败 = HTTP 200 + `ok=false` + **顶层平铺**的 `error_code` /
       `error_message`（外加 `result["error"]` 字符串）。**没有**嵌套的
       `error` 对象。
    2. 画像域 `query_*` 工具参数不合法时是 **`ok=true`**，错误藏在
       `result["error"]` 里 —— 只看 `ok` 会当成成功。
    3. `semantic_search` 的上游（embedding/精排）超时降级 **不体现在 `ok`**，
       而是 `ok=true` + `retrieval["degraded"] != null`。
    """

    model_config = ConfigDict(extra="ignore")

    tool: str
    ok: bool
    result: dict[str, Any] | None = None
    retrieval: dict[str, Any] | None = None
    limitations: list[str] = Field(default_factory=list)
    latency_ms: int = 0
    error_code: str | None = None
    error_message: str | None = None

    @property
    def is_failure(self) -> bool:
        """业务失败，包含画像域把错误塞进 `result["error"]` 的情形。"""
        if not self.ok:
            return True
        return isinstance(self.result, dict) and "error" in self.result

    @property
    def is_degraded(self) -> bool:
        """检索是否在上游降级（`ok` 为真但结果不完整）。"""
        return isinstance(self.retrieval, dict) and self.retrieval.get("degraded") is not None


# --------------------------------------------------------------------------
# 会话 / 事件流 / 检查点（S4′.2，契约 §2.7 / §4）
#
# 字段与 java-backend 的出参 record **逐字对齐**（`AgentEventService.SessionView`
# / `EventView`、`AgentCheckpointService.CheckpointView` / `UnfinishedRunView`）。
# 那些 record 的 snake_case 是靠显式 `@JsonProperty` 钉的，不是全局命名策略 ——
# 所以这里也不能靠 `alias_generator` 猜，只能照抄。
# --------------------------------------------------------------------------


class AgentSessionView(BaseModel):
    """`POST /api/agents/sessions` 与 `GET /api/agents/sessions` 的元素。

    两个形态共用一个模型：建会话时 Java 只回 `{"session_id": ...}`，列会话时
    字段齐全 —— 差异由默认值吸收。
    """

    model_config = ConfigDict(extra="ignore")

    session_id: str
    title: str | None = None
    created_at: str | None = None
    updated_at: str | None = None


class AgentEventRow(BaseModel):
    """`GET /api/agents/events` 的一行。

    `id` 即事件的 **`seq`**（`agent_events.id` 自增主键，全系统唯一产生 seq 处）；
    `detail` 是 JSON **对象**（Java 侧用 `readTree` 解析后才回，不是字符串）。
    """

    model_config = ConfigDict(extra="ignore")

    id: int
    session_id: str
    run_id: str
    type: str
    detail: dict[str, Any] = Field(default_factory=dict)
    created_at: str | None = None


class AgentCheckpointRow(BaseModel):
    """`GET /api/agents/checkpoints` 的一行。`node_name` 恒为 `"loop"`。"""

    model_config = ConfigDict(extra="ignore")

    run_id: str
    node_name: str
    state: dict[str, Any] = Field(default_factory=dict)
    created_at: str | None = None


class UnfinishedRun(BaseModel):
    """`GET /api/agents/runs/unfinished` 的一行 —— 惰性恢复的**唯一**触发源。

    判定口径（Java 侧）：该 run 在 `agent_checkpoints` 有行 **且** 该 run 没有
    `run.end`/`run.error` 事件，按 `session_id` 圈定。少任一半都会误判
    （漏恢复，或把已经跑完的 run 又跑一遍）。
    """

    model_config = ConfigDict(extra="ignore")

    run_id: str
    session_id: str
    node_name: str
    state: dict[str, Any] = Field(default_factory=dict)
    created_at: str | None = None


# --------------------------------------------------------------------------
# 异常
# --------------------------------------------------------------------------


class PlatformError(Exception):
    """编排层错误基类，形态对齐归档的 `AgentError(code, message)`。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class PlatformUnavailable(PlatformError):
    """连不上 Java 平台。

    调用方（`main.py#/chat`）应转 503 并明说原因，**不得静默降级到假数据**
    （契约 §8 待拍板 #3）。
    """

    def __init__(self, message: str = "无法连接 Java 平台") -> None:
        super().__init__("platform_unavailable", message)


class PlatformProtocolError(PlatformError):
    """Java 返回了**协议级**错误（401/403/404/422/500）。

    与业务失败（HTTP 200 + `ok=false`）严格分开：业务失败是能喂回模型的观察
    结果，协议级故障不是 —— 它中止本次 run。

    `detail` 只保留 Java 自己的 `detail` 字段并截断，**绝不携带完整响应体**
    （红线②：上游非 200 绝不落响应体）。
    """

    def __init__(self, status: int, code: str, detail: str) -> None:
        super().__init__(code, f"HTTP {status}: {detail}")
        self.status = status
        self.detail = detail


class ModelError(PlatformError):
    """模型调用失败。码沿用归档 `backend/app/meeting_agent/schemas.py:AgentError`。

    `not_configured` / `invalid_config` 是**配置**问题（不该重试）；
    `provider_timeout` / `provider_unreachable` / `provider_error` /
    `incomplete_response` / `invalid_response` 是**上游**问题（可重试）。

    `provider_error` 的 message 里**只有状态码**，没有上游响应体 —— 归档那里的
    注释写得很清楚：上游 body 可能回显凭据或私有文本，任何级别都不落地。
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(code, message)
