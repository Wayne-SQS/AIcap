# agent-orchestrator

AIcap「主管智能体」的**编排层**（Python）。Java 作平台，本层作编排 —— 把一句话
路由成动作、驱动有界工具循环、把子智能体的汇报流回对话框。

契约文档：`docs/主管智能体_落地方案_v1.0.md`（实现契约）、
`docs/主管智能体_需求与规划_v1.1.md`（需求与权限口径）。
进度口径：`docs/AIcap-Agent能力扩展_实施计划.md`。

## 当前状态：S5′（`plan()` 路由 + 反问阈值 + `routing_logs` + 触发型工具）+ 回程查询与会话记忆（2026-10-15）

S4′.2 交付了有界循环 + 事件落库 + 检查点恢复；**S5′ 在循环前面接上了主管的规划相**：
一句话进来先 `plan()`，判要不要反问，派活就调触发型工具（`POST /api/agents/tools/{name}`），
再进循环作答。

**2026-10-15 增补两项**（修真机实测回归：被追问"刚才那份草案好了吗"时 0 次工具调用、
答复与事实相反）：

- **回程查询**：Java 侧新增只读工具 `query_agent_runs`（按项目查三域 run 状态与产物指针，
  `result_json` 不整包返回）。清单运行时拉取 → 本层**零改动**自动获得它；
- **同会话纪要回填**：每轮 `/chat` 拼装 messages 时从 `agent_events`（经现成的
  `list_events`，增量翻页）现拼最近 3 轮**已终局** run 的 user/assistant 消息对
  （含派活注记），插在 system 之后、当前 utterance 之前 —— 修"模型不知道自己上一轮
  派过活"。上限/截断由 `AICAP_HISTORY_*` 环境变量管；读失败降级空纪要；不持久化、
  不进检查点（见契约 §2.7 末段）。

| 模块 | 状态 |
|---|---|
| `app/java_client.py` | ✅ 真实：唯一 HTTP 出口，透传 JWT（写路径零重试 / 读路径有界重试） |
| `app/tools.py` | ✅ 真实（薄）：从 `/catalog` 拉清单 + 调用转发 |
| `app/model_client.py` | ✅ 真实：Chat Completions 传输层（`httpx.AsyncClient`，不引厂商 SDK） |
| `app/loop.py` | ✅ 真实：有界循环（四道闸 + 接地闸）+ 检查点写入 / 恢复 + 可选 `pre_loop` 钩子 + 同会话纪要回填（`_history_messages`，2026-10-15） |
| `app/runtime.py` | ✅ 进程内 run 登记（`claim` 同步、无 `await`，重连只恢复一次） |
| `app/main.py` | ✅ `/health` / `/chat`（触发口）/ `/chat/stream`（只读通道 + 惰性恢复）+ 规划相 `_plan_phase` |
| `app/orchestrator.py` | ✅ 真实：`plan()` 路由 + 阈值判定 + 反问（**只有 `plan()`，没有 `execute()`**） |
| `app/events.py` | ✅ 信封组装 + 重放映射（`seq` 由 Java 给，本层不生成） |
| `app/config.py` / `schemas.py` | ✅ 真实（阈值 `AICAP_CLARIFY_CONFIDENCE` 可配置） |
| `app/report.py` | ⬜ 骨架，只有契约签名（S8′） |

### 一次 run 的事件序列（`session.start` 之后就是规划相）

```
session.start       ← loop 的 on_claim
intent.routed       ← 派活：必须先于任何 tool.call / agent.dispatched（契约 §3.4#2）
agent.dispatched    ← 触发型工具回执：job_id / status / deduplicated
model.step … tool.call/tool.result …   ← 有界循环
run.end
```

反问那一轮则是 `clarify.request` → `run.end{status: "clarify"}`：**不派活、不进循环**，
用户的答复以新一轮 `/chat` 进来。**问问题那一轮**（"现在有几个任务在推进？"）没有
`agent.dispatched` —— 照发 `intent.routed{route: null}`（契约 §3.4#2 要求它先于循环里的
`tool.call`），然后把话交回工具循环，由主管自己调只读工具作答。派活前先落一行
`routing_logs`（`plan()` 之后、派活之前，契约 §2.7）；留痕写失败**不打断本轮**，
但会在 `intent.routed` / `clarify.request` 里带 `logged=false`，并在服务端记一条 WARNING
（降级必须留痕）。

### 路由集合与阈值都在代码里，不在 prompt 里

- **路由集合运行时从 `/api/agents/catalog` 派生**：`read_only=false` 的工具才是"派活"目标
  （S5′ 的 `start_*` 触发型工具），各路由的必填参数取自它的 `args_schema.required`。
  **本层没有一行硬编码的工具名** —— Java 侧新增/下线一枚触发型工具，本层零改动，
  对应路由自动出现或自动不可用。catalog 里没有的路由**一律不认**（不做别名、不做模糊匹配），
  认不出来就反问。
- **反问阈值（默认 0.6，`AICAP_CLARIFY_CONFIDENCE` 可覆盖）只活在 Python 里**：
  模型在 prompt 里被要求"如实给 confidence"，判定由 `orchestrator.apply_rules()`
  按契约 §5.4 的**有序表**机械执行（路由不可用 → 阈值 → `route=null` 交回循环 →
  必填参数 → 其余派活；命中"写库/采集"类不为确认而反问）。prompt 里不含那个数 ——
  否则调阈值就等于改 prompt，而模型可以不理它。
- **`route=null` 不等于反问**：问数据（模型有把握）就交回工具循环自己答，
  要一个动作而平台没有对应工具（模型如实给低 confidence，如 S8′ 之前的"出个报告"）
  才反问。把 `route=null` 一律当反问，会把 S4′.2 那套问答直接砍掉 ——
  而"问问题"恰恰是这个对话框最主要的用法。
- **未配置模型密钥时如实失败**（`not_configured`，事件流里是 `run.error`），
  **绝不猜路由、绝不硬凑一个 confidence 骗过阈值**。

**事件真相在 Java，不在本层内存里。** 有界循环是一个独立后台任务
（`asyncio.create_task`），SSE 端点**只从 Java 读事件、从不拥有循环** —— 这正是
"kill 进程后重连仍能续传"能成立的原因。恢复是**惰性**的：客户端带
`Last-Event-ID` 重连 `/chat/stream` 时，若该会话有未完成 run 且本进程没在跑它，
才去 `agent_checkpoints` 取 state 接着跑。**每个连接只尝试一次** —— 每轮心跳都试的话，
一个注定跑不完的 run（恢复后立刻又崩）会变成每 15s 重跑一次的无限循环。

**JWT 绝不进 `state_json`/`messages`**：恢复所需的令牌来自那次重连请求本身。
（与 `CLAUDE.md` 红线⑤共享的那条硬约束。红线⑤本身**限定在 ai-service**，指的是
"不配置 checkpointer、不持久化会议转写"；`agent_checkpoints` 是编排层自己的表，
装的是主管对话状态，**不含会议转写原文**。）

**结构性保证**：本层**不得持有任何数据库连接**。这不是约定，是测试
（`tests/test_no_db.py`）—— 往 `app/` 里加一行 `import sqlalchemy` 会让 CI 立刻变红。

## 起服务

```bash
# 前置：Python 3.11（本机 E:\python3.11）
python -m venv .venv
.venv/Scripts/python -m pip install -e ".[dev]"

cp .env.example .env          # 按需改 JAVA_BASE_URL / PORT / AICAP_LLM_*
.venv/Scripts/python -m uvicorn app.main:app --port 8100
```

启动顺序无关紧要：Java 没起时本层照样起得来，`GET /health` 会如实报
`"java": false`（自身仍 200）。

**没配 `AICAP_LLM_API_KEY` 时 `/chat` 是 503** —— 明说原因，**不会**降级到假数据。
`/chat/stream` 不做这个门禁：没有待恢复的 run 时它根本不需要模型，未配置会以
事件流里的 `run.error{not_configured}` 如实呈现。

```bash
curl http://127.0.0.1:8100/health
curl -X POST http://127.0.0.1:8100/chat -H "Authorization: Bearer <jwt>" \
     -H 'Content-Type: application/json' -d '{"utterance": "现在有几个任务在推进？"}'
curl -N "http://127.0.0.1:8100/chat/stream?session_id=<sid>" -H "Authorization: Bearer <jwt>"
```

## 测试

```bash
.venv/Scripts/python -m pytest -q
```

全部用例用 `httpx.MockTransport` 铺 Java 的响应，**不需要 Docker / MySQL / Java 在跑**。

三处测试基建值得先看一眼再改用例：

- `tests/fake_platform.py` —— 内存假 Java 平台（sessions / events / checkpoints /
  **routing-logs** 四组端点 + upsert + `after_seq` 过滤 + 未完成判定 + 触发型工具的
  catalog 与回执）。`/chat/stream` 的**重启续传**用"两个 app 实例共享同一份 store"来摆：
  新 app 的空 `RunRegistry` 等价于进程刚起，而 store 里的现场还在 —— 于是"状态其实在
  Java 里"是条**可断言的事实**，不是一句设计声明。它另外记 `trace`（请求到达顺序），
  「留痕先于派活」这条顺序只有它能断言；`plan_reply()` / `clarify_plan()` 是规划相那发
  模型回复的现成构造器。
- `tests/sse.py` —— 自写的 ASGI 流驱动。**别换成 `httpx.ASGITransport`**：
  httpx 0.28 会等 `await self.app(...)` 跑完才构造 Response，而这条流是不结束的
  （终局后仍开着等 `agent.report`），整条用例会就此挂住。
- 心跳靠 `monkeypatch` `config.SSE_HEARTBEAT_SECONDS = 0.05` 加速 —— 所以
  `main.py` 里**每次循环重新读**这个属性，不要在导入时抄进局部变量。

两处按层分开的用例：`tests/test_orchestrator.py`（`plan()` 与判定表，纯函数级）与
`tests/test_routing.py`（规划相接进 `/chat`：事件顺序、留痕载荷、隐私线、恢复不重跑）。

`tests/test_no_db.py` 另有元测试把「加一行 `import sqlalchemy` 会变红」固化成
机器每次都会验的事实。

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

**S5′ 又核出两处**（都已按代码回改契约文档，实现跟的是代码）：

8. **§5.4 的 `"route": "meeting"` 不是路由的取值**。真实 `PlatformToolCatalog` 给触发型工具
   发的 `agent` 全是 `"platform"`，能派生的区分字段只有 `read_only` —— 所以本层的路由 id
   **就是触发型工具名**（`start_meeting_analysis` / `start_plan_draft`），
   示例写于触发型工具定稿（§2.4）之前
9. **§5.4 的 `def plan(...)` 是同步的**，落不了地：本层唯一的模型出口是
   `model_client.ModelClient.complete`（async）。落地形态是
   `async def plan(utterance, user_jwt, *, model=None, catalog=None, client=None) -> Plan`
   —— 两个位置参数与 `Plan` 六字段逐字保留

## 对契约的增补（已在文档与提交信息中标注）

- `app/schemas.py`：契约 §5.1 的文件清单里没有这个文件，但错误类型与 `Plan` 被
  `java_client` / `loop` / `orchestrator` 共用，放三者任一处都会造成反向依赖；
  归档的兄弟实现（`backend/app/meeting_agent/schemas.py`）也是这个布局
- `python-dotenv`：契约 §5.1 把依赖定为 fastapi/httpx/pydantic/uvicorn 四个，
  但契约自己要求 `.env.example`，且 `backend/` 已在用
- `plan()` 的 `catalog=` 注入点：契约签名只写了 `model` / `client`。多这一个是为了
  **同一个 run 里 `/catalog` 只拉一次**（`main._plan_phase` 拉的那份既给 `plan()`
  派生路由，也给自己拼留痕的 `candidates`）—— 不传它就得为同一件事发两次 HTTP
- `loop.run(pre_loop=…)` 钩子：规划相必须落在 `registry.claim` **之内**，否则 `/chat`
  的 409「同一会话只有一个 run」会出现真空窗口（规划要花一次模型往返）；默认 `None`
  ⇒ 旧调用点与 `resume()` 语义一字不变，**崩溃恢复不会重跑 `plan()`**
- `intent.routed` / `clarify.request` 的 `logged` 字段（布尔，附加）：留痕那一步写不进去时
  是 `false`。静默少一行指标比明着少一行危险，降级必须看得见

装 Python 依赖在境内走清华/阿里镜像（`~/pip/pip.ini` 已配），不会卡。

## 与既有部分的关系

| 目录 | 关系 |
|---|---|
| `java-backend/` | **唯一上游**。本层所有数据都经它的 `/api/agents/*` 取 |
| `backend/` | 归档 FastAPI 实现，**只读**。其 `meeting_agent/runner.py` 是 S4′.2 有界循环的复活来源（剥离点见 `app/loop.py` 注释） |
| `frontend/` | SSE 事件的消费方（S5.5′） |
| `qa/` | 前后端之外的第三套 Playwright 用例，本层不涉及 |
