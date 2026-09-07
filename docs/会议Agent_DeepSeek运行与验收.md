# 会议 Agent：DeepSeek 配置、运行与验收

状态：代码已实现；本机未配置真实 API Key，因此尚未验证真实 DeepSeek 输出质量。
本轮先实现文本会议 → 按需查询项目 → 摘要/行动项 → 新需求建议 → 人工审核 → 需求池。

## 1. 配置 DeepSeek

在 `backend/.env` 配置以下变量（已有文件时直接编辑，不覆盖数据库配置）：

```dotenv
AICAP_LLM_API_KEY=填写你自己的DeepSeek_API_Key
AICAP_LLM_BASE_URL=https://api.deepseek.com
AICAP_LLM_MODEL=deepseek-v4-flash
AICAP_AGENT_WORKER_ENABLED=true
```

`.env` 已在 Git 忽略列表中，不提交密钥。`DEEPSEEK_API_KEY` 可作为未设置 AICAP_LLM_API_KEY 时的备用环境变量。

默认模型名依据 2026-09-06 查阅的 [DeepSeek Chat Completions 文档](https://api-docs.deepseek.com/api/create-chat-completion/)。如账号可用模型不同，请按实际模型列表修改 AICAP_LLM_MODEL。实现使用非思考模式的 [工具调用](https://api-docs.deepseek.com/guides/tool_calls/)；最终输出单独请求 [JSON Output](https://api-docs.deepseek.com/guides/json_mode/)，并继续在本地验证结构和证据。

配置在后端启动时读取，修改后重启后端。缺少密钥时不会使用假答案，页面会提示未配置，手动录入仍可使用。

## 2. 启动

后端沿用 MySQL 及已有依赖，无需安装 Agent 框架：

```powershell
cd backend
docker compose up -d
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

前端在另一个终端启动：

```powershell
# 仓库根目录
backend/.venv/Scripts/python.exe -m http.server 8090 --bind 127.0.0.1
```

打开 `http://127.0.0.1:8090/index.html`。前端文件需要同时包含 index.html、meeting-review.js、meeting-agent.js。若浏览器询问本地网络权限，允许访问本机后端。

## 3. 使用步骤

1. 成员账号登录 → AI 助手 → 填会议标题和转写 → 保存。
2. 选择刚保存的会议，点击“分析会议”。服务端将转写及查询到的项目资料发送到配置的模型。
3. 页面轮询显示排队、分析、完成或失败；刷新后可从已保存会议找回结果。
4. 阅读摘要、决议、行动项、风险、未决问题及工具运行记录。
5. 新需求建议进入审核中心。负责人/管理员采纳后才写入需求池，成员无法自行批准。
6. 失败时查看错误并点击重试；保留同一个 run_id 和历史尝试记录。

可先用下面的示例做真实模型验收：

```text
我们希望增加一个导出项目周报的功能，具体范围和 Sprint 下次确认。
权限接口测试由成员3下周五前完成。
登录模块基本完成，但还没有验收，不能直接标记已完成。
成员3说本周比较忙，请负责人确认能否接下额外工作。
```

预期：周报形成需求池建议，查询已有故事/需求池；行动项保留会议原始负责人和日期表达；没有日期依据时不编造绝对日期；不把“基本完成”改为已验收；提示当前缺少容量数据，不能给出精确过载比例。

以上是验收预期，不是已经获得的真实模型输出。模型语义仍需人工核对，尤其区分否定、假设和真正决议。

## 4. Agent 的实现

- `meeting_agent/model_client.py`：通过 httpx 调用配置服务。工具查询阶段允许 function calls，最终结果阶段要求 JSON；超时、非200、截断及格式错误不转换成假答案。
- `meeting_agent/tools.py`：search_stories、search_pool、list_tasks、list_members、project_summary；查询真实数据库，并检查发起人的当前权限。
- `meeting_agent/runner.py`：有界模型/工具循环、转写分段、输出结构校验、原文证据校验。模型只有只读工具。
- `meeting_agent/jobs.py`：数据库队列、条件更新抢占任务、租约恢复、事件记录、一次事务保存全部建议及最终结果。
- `services/meeting_suggestions.py`：手动建议与 Agent 共用构造逻辑，Agent 只能保存 pending 建议。
- `routers/agent.py`：创建/查询/重试分析；`meeting-agent.js`：分析和结果界面。

不依赖浏览器持续打开才能运行。工作线程在后端启动时启动；队列持久化，重新启动可继续处理排队任务。可设置 AICAP_AGENT_WORKER_ENABLED=false 暂停自动处理，此时新任务仍可经 API 入队，但页面禁用分析按钮。

每次最多6轮工具选择、24次工具调用；单次 HTTP 超时45秒，运行在步骤边界检查180秒总时限。超长/持续等待的运行由300秒租约回收为失败，不会重新执行旧线程的结果。此为课程 MVP 的进程内工作线程，不是高吞吐任务平台。

## 5. 接口

均要求现有 Bearer 登录 token；成员/admin/owner 可发起，viewer 只能查看。

| 方法和路径 | 说明 |
|---|---|
| GET /api/agent/config | 返回是否已配置、模型名、工作线程设置；不返回密钥或地址 |
| POST /api/meetings/{id}/runs | 创建分析；同一不可变会议重复请求返回同一次运行 |
| GET /api/meetings/{id}/runs | 找回该会议运行 |
| GET /api/agent-runs/{id} | 状态、结果、错误、各次尝试事件 |
| POST /api/agent-runs/{id}/retry | 仅允许失败运行重试，attempt增加 |

运行状态：queued、running、awaiting_review、completed、failed。awaiting_review 表示分析已生成并送审，后续各建议的审批/执行状态以审核中心为准；它不是建议全部仍待审的实时统计。

成功结果含 summary、decisions、action_items、risks、unresolved_questions、proposals、suggestion_ids、skipped_proposals 和 limitations。

审核接口仍见 [会议建议审核 MVP](会议建议审核MVP_运行与接入.md)。内置 Agent 建议会返回可验证的 agent_run_id，区别于外部提交方自行声明 origin=agent。

## 6. 边界与可靠性

- 仅生成 pool.create；会议对任务或现有故事的修改暂作为行动项/未决问题，不自动改写它们。
- 当前仓库没有真实成员容量、任务状态和当前 Sprint 日期配置。工具明确返回这些缺口，不能用前端演示数据冒充真实数据。
- 新建议优先级统一默认 Could 并标记待审核，暂不自动安排 Sprint/负责人/截止日期。
- 原文分段只分配 seg-N，不虚构音频时间戳。负责人和日期提及必须逐字出现在行动项引用中；更深层的语义真实性仍需人审核。
- 无效证据、非法动作、非法工具或缺失结构会使运行失败，不能留下部分建议。
- 相同会议只保存一次成功分析；不支持对成功结果重新生成。修订会议内容请新建会议，或拒绝误录建议后手动补充。
- 同名故事/需求/待审建议会在保存前检查并跳过，模型也会搜索相似需求；这不是严格语义去重或跨不同会议的并发唯一约束，仍需审核。
- 同一运行结果和全部待审建议一次事务提交；进程失去租约后不能再保存结果。
- 保留模型名、提示词版本、工具参数/结果、用量和错误码；不保存 API Key、上游错误正文或隐藏推理过程。事件属于当前单项目共享数据。
- 新增 meeting_agent_runs 与 meeting_agent_events 表，启动时补建；已有业务表结构无需修改。

## 7. 已执行测试及限制

2026-09-07：独立 SQLite 测试库全部87项后端测试通过（包括原有63项及新增24项 Agent 测试）；新增 Agent 浏览器检查13项通过；手动审核12项通过。模型响应来自明确标记的测试夹具，工具仍访问实际测试数据库。

本轮 Docker 未响应、MySQL 3307 未可用，因此未完成本轮 MySQL 回归。此前项目侧版本的63项 MySQL测试通过，不能替代本轮新增 Agent 的 MySQL验证。

本机没有真实 DeepSeek Key，因此未执行真实模型评测，不能用上述数字宣称模型理解准确率。

### 后端隔离测试

正常 MySQL 环境：

```powershell
cd backend
.venv/Scripts/python.exe -m pytest tests -q --maxfail=3
```

无 MySQL 时可在临时目录使用 SQLite：

```powershell
cd backend
$env:TEST_DATABASE_URL='sqlite:///' + ($env:TEMP -replace '\\','/') + '/aicap_agent_test'
.venv/Scripts/python.exe -m pytest tests -q --maxfail=3
```

测试会重建 TEST_DATABASE_URL 指向的表，数据库名称必须以 _test 结尾。该变量仅在测试终端配置。

### 浏览器夹具测试（不调用真实模型）

1. 仓库根目录终端A：

```powershell
$env:AICAP_TEST_FIXTURE='1'
backend/.venv/Scripts/python.exe -m uvicorn qa.agent_provider_fixture:app --host 127.0.0.1 --port 9009
```

2. 后端目录终端B，使用独立数据文件及明确的测试模型名：

```powershell
$env:DATABASE_URL='sqlite:///./agent-ui.sqlite3'
$env:AICAP_LLM_API_KEY='fixture-not-a-real-key'
$env:AICAP_LLM_BASE_URL='http://127.0.0.1:9009'
$env:AICAP_LLM_MODEL='TEST-FIXTURE'
.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

3. 另起静态服务8090后执行 `node qa/meeting-agent-e2e.spec.js`。Node需可解析playwright-core，系统安装Edge。测试响应和界面结果明确标记TEST FIXTURE。测试环境变量不要复制到正式运行终端或本地.env。

配置真实密钥之后，按第3节逐个实测并记录：模型版本、输入、输出、工具记录、人工判定和失败案例，再决定是否调整提示词。
