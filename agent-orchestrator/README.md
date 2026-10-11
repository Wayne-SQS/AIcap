# agent-orchestrator

AIcap「主管智能体」的**编排层**（Python）。Java 作平台，本层作编排 —— 把一句话
路由成动作、驱动有界工具循环、把子智能体的汇报流回对话框。

契约文档：`docs/主管智能体_落地方案_v1.0.md`（实现契约）、
`docs/主管智能体_需求与规划_v1.1.md`（需求与权限口径）。
进度口径：`docs/AIcap-Agent能力扩展_实施计划.md`。

## 当前状态：S4′.1（骨架）

本步交付**结构与边界**，不是可演示功能。

| 模块 | 状态 |
|---|---|
| `app/java_client.py` | ✅ 真实：唯一 HTTP 出口，透传 JWT |
| `app/tools.py` | ✅ 真实（薄）：从 `/catalog` 拉清单 + 调用转发 |
| `app/main.py` | ✅ `/health` 真实；`/chat` 显式 503 占位 |
| `app/events.py` | ✅ 信封组装（`seq` 由 Java 给，本层不生成） |
| `app/config.py` / `schemas.py` | ✅ 真实 |
| `app/loop.py` / `orchestrator.py` / `report.py` | ⬜ 骨架，只有契约签名（S4′.2 / S5′ / S8′） |

**结构性保证**：本层**不得持有任何数据库连接**。这不是约定，是测试
（`tests/test_no_db.py`）—— 往 `app/` 里加一行 `import sqlalchemy` 会让 CI 立刻变红。

## 起服务

```bash
# 前置：Python 3.11（本机 E:\python3.11）
python -m venv .venv
.venv/Scripts/python -m pip install -e ".[dev]"

cp .env.example .env          # 按需改 JAVA_BASE_URL / PORT
.venv/Scripts/python -m uvicorn app.main:app --port 8100
```

启动顺序无关紧要：Java 没起时本层照样起得来，`GET /health` 会如实报
`"java": false`（自身仍 200）。**不会**降级到假数据。

```bash
curl http://127.0.0.1:8100/health
```

## 测试

```bash
.venv/Scripts/python -m pytest -q
```

全部用例用 `httpx.MockTransport` 铺 Java 的响应，**不需要 Docker / MySQL / Java 在跑**。

验收动作（契约 §7）：往 `app/config.py` 加一行 `import sqlalchemy`，`pytest` 必须变红。
`test_no_db.py` 里另有元测试把「它会失败」固化成机器每次都会验的事实。

## 已知分叉（文档 §2.3 与 Java 代码不一致，以**代码**为准）

写 `java_client` 时逐条核过 Java 侧实现，7 处分叉：

1. 业务失败是顶层平铺的 `error_code` / `error_message`，**没有**嵌套的 `error` 对象
2. 工具层**没有 503**：工具内 DB 异常 → `200 + ok=false + internal_error`；鉴权阶段 DB 异常 → `500`
3. **画像域** `query_*` 参数不合法时是 `200 + ok=true`，错误藏在 `result["error"]` —— 只看 `ok` 会漏（`ToolOutcome.is_failure` 已覆盖）
4. `semantic_search` 的上游降级不体现在 `ok`，而是 `ok=true` + `retrieval["degraded"]`（`ToolOutcome.is_degraded`）
5. `retrieval` 里的 `rewritten` / `log_id` 属 S3.3（未做），现在没有
6. catalog 每项**恒输出** `args_hint` key（缺时为 `null`）
7. §2.5「不接受 `userId` 字段」与 `query_activities(userId=…)` 冲突 —— 那里的 `userId` 是**查询目标**。真实规则是「身份只来自 JWT」，**不能**写成按 key 拦截（拦了会打断画像工具）

第 1/2/3/4/6 条已按代码修正回契约文档；第 7 条改成「身份只来自 JWT」的准确措辞。

## 对契约的两处增补（已在文档与提交信息中标注）

- `app/schemas.py`：契约 §5.1 的文件清单里没有这个文件，但错误类型与 `Plan` 被
  `java_client` / `loop` / `orchestrator` 共用，放三者任一处都会造成反向依赖；
  归档的兄弟实现（`backend/app/meeting_agent/schemas.py`）也是这个布局
- `python-dotenv`：契约 §5.1 把依赖定为 fastapi/httpx/pydantic/uvicorn 四个，
  但契约自己要求 `.env.example`，且 `backend/` 已在用

装 Python 依赖在境内走清华/阿里镜像（`~/pip/pip.ini` 已配），不会卡。

## 与既有部分的关系

| 目录 | 关系 |
|---|---|
| `java-backend/` | **唯一上游**。本层所有数据都经它的 `/api/agents/*` 取 |
| `backend/` | 归档 FastAPI 实现，**只读**。其 `meeting_agent/runner.py` 是 S4′.2 有界循环的复活来源（剥离点见 `app/loop.py` 注释） |
| `frontend/` | SSE 事件的消费方（S5.5′） |
| `qa/` | 前后端之外的第三套 Playwright 用例，本层不涉及 |
