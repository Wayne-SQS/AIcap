# 会议 Agent AI 服务（纯文本工作流阶段）

遵循[已确定的共享方案](https://chatgpt.com/share/6aa60c39-0798-83ec-b24d-87f648047719)：Python / FastAPI / Pydantic / LangGraph；Skill 分析会议，Tool 经 Java 业务接口访问项目，写操作须人工审核。

已完成：Daily Scrum 状态提案契约、Java 故事只读 Tool、纯文本 LangGraph 最小分析流程。当前提供可调用的 Python 模块和带鉴权的 FastAPI 分析入口，会议页面已可读取持久化状态提案、证据与处理状态；已接入逐项人工审核及批准提案执行操作；已接入每日站会状态分析入口；LangGraph 持久化恢复尚未接入。Java 已提供逐项人工审核及批准提案的事务执行接口。`backend/` 仍为历史归档。

## 安装与验证

Python 3.10+，在 `ai-service/` 目录执行：

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe -m unittest discover -s tests -v
.venv/Scripts/python.exe -m pip check
```

本机已创建独立 `.venv`。前两轮复用的 `backend/.venv` 未安装本轮 LangGraph 依赖，请改用上述环境。测试须在 `ai-service/` 目录运行。

## 文本工作流调用

```python
from meeting_agent.model_client import ChatModelClient, ModelSettings
from meeting_agent.story_tool import StoryReadTool
from meeting_agent.workflow import DailyScrumWorkflow

workflow = DailyScrumWorkflow(
    StoryReadTool("http://localhost:8080"),
    ChatModelClient(ModelSettings.from_env()),
)
# 这些变量来自已授权的服务端调用，不让模型提供认证信息。
result = workflow.run(
    meeting_id=meeting_id,
    transcript=transcript,
    access_token=access_token,
)
print(result.model_dump_json(indent=2))
```

模型配置沿用 Java：`AICAP_LLM_API_KEY` 必须设置，`AICAP_LLM_BASE_URL` 默认 `https://api.deepseek.com`，`AICAP_LLM_MODEL` 默认 `deepseek-v4-flash`。直接读取进程环境，不自动加载 `.env`。密钥缺失立即报错，不返回模拟结果；本轮未使用真实密钥调用模型。

流程为 `START → normalize → load_context → analyze → validate → END`。每次运行读取一次 Java 故事、调用一次模型，再使用第一轮的 `validate_daily_result` 做证据和快照一致性校验。任何失败立即停止，无自动重试或写入；只返回通过校验的 `DailyScrumOutput`。

- `daily_skill.py` 定义 DailyScrumSkill 的类型、上下文、允许工具、动作、审核策略和分析规则；不负责 HTTP 或数据库操作。
- 原文按非空行编号 S1/S2…，保留行内原文和空行对应的编号间隔；最大 16000 字符、100 个非空段。
- 只允许提议故事状态 0/1/2 的变化。阻塞不是状态 3；验收约束、冲突和超出当前能力的事项须保留在摘要/待确认问题中。
- `model_client.py` 使用一次非流式 `/chat/completions` JSON 请求，沿用现有 DeepSeek 非思考模式配置；其他兼容服务通过显式 base_url/model 配置接入。没有模型自主工具循环，故事 Tool 由工作流固定节点调用。
- 用户 Bearer 令牌仅存在于单次调用闭包，不进入图状态或模型消息。禁用 LangSmith tracing；不配置 checkpointer，不持久化会议内容。
- HTTP 不跟随重定向、不使用环境代理；模型地址要求 HTTPS（本机测试地址除外），模型操作超时默认 45 秒、响应限制 1 MiB。超时是网络操作时限，不是工作流总时限。
- 结构校验和原文匹配不保证模型语义正确，尤其“未验收不得完成”仍需真实模型验收和后续人工审核。夹具不会证明模型具备抗注入或完整理解能力。

直接调用 Python 工作流仍要求调用者先验证会议读取与分析发起权限；新增 FastAPI 入口已通过 Java 用户与会议接口完成该检查。Java 当前实行已登录成员共享会议读取权限，没有仅创建者可读的规则。

## Java 故事只读 Tool

`StoryReadTool.get_stories(access_token=...)` 返回 `StorySnapshot`；`load_daily_context(...)` 可单独组装 `DailyScrumInput`。各自发起一次固定 `GET /api/stories`，无缓存或重试。

后端 origin 来自服务端配置，不带 `/api`、查询串或凭据。Java 负责 Bearer 认证；故事 GET 允许任何已登录用户包括 viewer，但不代表可发起分析或批准变更。仅投影 `id/title/status/sprint/owner_id`，保留 owner_id=null；缺字段、错误类型、重复 ID、超过 1000 条或响应超过 2 MiB 时整体失败。current_sprint 默认 null，不从快照猜测。

## 错误边界

- `StoryToolError.code`：authentication_required、permission_denied、backend_redirect、backend_http_error、backend_unavailable、response_too_large、invalid_backend_response、invalid_meeting_context。
- `ModelError.code`：model_not_configured、invalid_model_config、provider_http_error、provider_timeout、provider_unavailable、provider_response_too_large、incomplete_model_output、invalid_model_response。
- `WorkflowError.code`：invalid_meeting_input、invalid_model_proposal。

错误文本不回显令牌、模型响应或会议内容。模型截断、重复 JSON 键、非对象输出和不请求的工具调用都不进入提案结果。

## 验证边界与接续

测试运行真实 LangGraph 和 HTTP 适配器；Java 与模型接口由本机 HTTP 夹具模拟，网络断开与超时用异常注入。覆盖读取顺序、认证隔离、空提案、连续运行隔离、无效证据/目标、模型截断、畸形及超限响应；未进行真实 Java/MySQL、前端或真实模型验收。

FastAPI 入口与鉴权已在第四轮完成。Java 存储与 Python 自动提交已接通。逐项人工审核记录已在第七轮完成，下一轮只接通审核通过提案的故事状态执行。

路线与历史进度见[接续说明](../docs/会议Agent_方案落地与首轮契约.md)。实现参考：[LangGraph Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)、[DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode/)。

## FastAPI 分析入口（第四轮）

在 `ai-service/` 安装更新的 requirements.txt 后启动：

```powershell
$env:AICAP_JAVA_BASE_URL = 'http://localhost:8080'
.venv/Scripts/python.exe -m uvicorn meeting_agent.api:create_app --factory --host 127.0.0.1 --port 8090
```

模型配置继续使用前文 `AICAP_LLM_*`，缺密钥时服务可启动、分析返回 503。`GET /health` 仅表示进程存活，不表示 Java/模型就绪；`/docs` 和 `/openapi.json` 提供接口说明。

```http
POST http://localhost:8090/api/meetings/{meeting_id}/analyze
Authorization: Bearer <Java 登录接口签发的 access_token>
Content-Type: application/json

{"meeting_type":"daily_scrum","client_request_id":"stable-request-uuid"}
```

会议须先用 Java 现有 `POST /api/meetings` 保存标题和文本；路径使用该接口返回的真实会议 ID。分析请求不接收 transcript、用户角色、故事快照、模型地址或密钥，额外字段返回 422。可选 current_sprint 为 1–4 的整数或 null，表示用户明确选择，不是系统推断。当前仅支持显式 daily_scrum。

执行顺序：同一 Bearer 令牌 → Java `GET /api/auth/me` → 确认 admin/owner/member → `GET /api/meetings/{id}` → 使用服务端保存的原文运行上一轮工作流。viewer/未知角色在读取会议前返回 403；不存在的会议返回 404。每次请求重新查询角色，不缓存用户授权。Java 当前允许已登录用户共享读取会议，不额外增加仅创建者可读的限制。

成功返回 200 和 DailyScrumOutput（schema_version、meeting_id、meeting_type、summary、proposed_actions、open_questions）。同步请求完成并确认 Java 保存后才返回；不创建 run/job，不调用旧的 pool.create 审核接口。

响应错误格式统一为 `{"detail":"错误码"}`：401 认证失败（含 WWW-Authenticate: Bearer）、403 无分析/会议权限、404 会议不存在、422 输入错误、502 上游响应或模型提案无效、503 配置缺失/服务不可达、504 模型或鉴权/会议读取超时。用户请求校验错误不回显被拒绝的字段内容。故事读取的网络/超时仍沿用上一轮 backend_unavailable → 503。

当前没有浏览器跨域配置或 Vue 代理变更；本轮交付是独立服务接口，尚未接入现有页面。后续页面接入时统一处理代理路由。

验证：45 项 unittest 通过（原 33 项 + 新增 12 项），pip check 无冲突。新增测试使用 FastAPI TestClient、真实 LangGraph/Java HTTP 适配器、本机 Java HTTP 夹具和明确标记的模型夹具；验证允许角色、viewer 拒绝、角色变化、401/403/404、原文来源、字段伪造、坏响应、无密钥和错误映射。未进行真实 Java JWT/MySQL、真实模型或前端验收。

API 实现参考：[FastAPI Security Tools](https://fastapi.tiangolo.com/reference/security/)、[FastAPI TestClient](https://fastapi.tiangolo.com/reference/testclient/)。

Java 存储接口的提交样例、保存时快照口径、文件清单和 8 项隔离存储测试结果见 [第五轮交接说明](../docs/会议Agent_方案落地与首轮契约.md)。Python analyze 已在第六轮接通自动提交，详见下文。

## 自动保存与重试（第六轮）

分析请求现在**必须**包含 client_request_id（小写字母、数字、连字符，1–80 字符）。调用端为每次新的分析意图生成新编号；网络重试复用原编号。改变会议选择、Sprint 或希望重新分析时，应使用新编号；已保存编号表示取回原结果，不会比较本次 current_sprint 或重新调用模型。

流程：鉴权与读取会议 → 按当前用户/会议/请求编号查找已有记录 → 若存在直接返回 → 否则运行工作流 → POST Java 状态分析存储 → 校验保存响应 → 返回成功。

成功响应保留原 DailyScrumOutput 字段，并新增：

```json
{
  "analysis_id": "Java保存的记录UUID",
  "client_request_id": "stable-request-uuid",
  "storage_status": "pending"
}
```

以上仅展示新增字段；无提案时 storage_status=no_changes。分析有结果但保存未确认时不会返回 200，不会只返回草稿并声称已保存。原始输出中的 schema_version/meeting_type 会完整序列化给 Java。Bearer 令牌仅在 HTTP 头传递。

Java 新增 `GET /api/meetings/{meetingId}/status-analyses/by-request/{clientRequestId}`，按认证用户筛选并要求 writer 角色，不接受客户端自报用户 ID。未找到返回 404；Python 将其视为尚未保存，其余错误直接阻止分析。Java 与 Python 应一起更新并重启。

仅保存阶段的超时、连接错误或 5xx 自动重试一次（最多两次 POST），两次使用相同请求编号和完全相同的序列化载荷，不重新调用模型。401/403/404/409/422、重定向和坏响应不自动重试。

若两次均无法确认，返回 503/504，body 为 `{"detail":"storage_outcome_unknown","client_request_id":"..."}`。它表示**保存结果未知**，不是证明没有写入；调用端随后以同一编号重试分析入口，已有记录会直接返回。Java 的请求编号幂等和载荷冲突保护仍生效。

存储冲突返回 409 storage_conflict，字段/证据拒绝返回 422 storage_rejected，非法保存响应返回 502 invalid_storage_response；错误不回显后端响应、原文或令牌。若同一编号的并发请求在保存前同时到达，仍可能分别调用模型；Java 确保只保存一条，同编号但不同结果会冲突，不保证模型只调用一次。

本轮未保存失败草稿，也未增加工作流 checkpoint。如果确实未保存，下一次完整请求可再次分析。读取已保存结果仍先验证本次用户和会议权限。

验证：Python 55 项测试通过、Java StatusAnalysisStorageTest 9 项通过。新增覆盖非空/空提案保存、成功记录 ID、已保存重试不调用模型、保存故障、超时后固定载荷重试、响应丢失、坏响应/重定向和按用户隔离查找。Python 使用本机 HTTP/模型夹具；Java 使用 Java21+H2 隔离测试。未进行真实 Python↔Java↔MySQL 进程联调、Java23 或真实模型效果验收。

## 逐项人工审核（第七轮，Java 接口）

Java 新增以下同路径接口，默认端口 8080：

`POST /api/meetings/{meetingId}/status-analyses/{analysisId}/proposal-reviews`

```json
{"proposal_id":"p1","decision":"approve","reason":"核对原文和验收结果后接受"}
```

拒绝使用 `decision=reject`。修改后接受示例（原提案 1→2，审核人决定改为 1→0）：

```json
{"proposal_id":"p1","decision":"modify_and_approve","changes":{"status":0},"reason":"尚需补充验收，退回待办"}
```

reason 字段必须提供；修改后接受必须有非空原因。只有 modify_and_approve 可带 changes，并且只允许 changes.status（严格整数 0/1/2）。必须不同于原提案的目标状态，也不能等于原 expected.status；故事目标、证据和原始预期状态不能替换。若仅需接受原提案，使用 approve。

只有 admin/owner 可审核；member/viewer 只能读取。审核人和时间由服务端确定，不能自报。接受前检查真实故事仍存在且状态匹配原 expected；过期或已删除目标返回 409，可拒绝该提案。拒绝不检查实时故事状态，不生成待执行载荷。

`GET` 同路径返回 analysis_id、review_status 和 proposals：

- review_status：pending / partially_reviewed / reviewed / no_changes。
- 每项 status：pending / approved / rejected；包含 original_proposal、approved_proposal、decision、reason、reviewed_by、reviewed_at、execution_status。
- 原提案始终保留；修改后接受只在 approved_proposal 中替换 changes。未审核及拒绝的 approved_proposal=null。
- 已接受但未执行提案 execution_status=not_started；执行成功后为 succeeded；已拒绝为 not_applicable。审核操作本身不改变故事状态。

同一审核人以相同决定、原因和修改内容重试，返回最初记录（即使之后故事状态变化）；改变决定、原因、修改内容或审核人均返回 409。审核表的唯一键与事务锁防止并发覆盖。本轮不支持撤回或重新打开审核。

原状态分析记录及 Python 返回的 storage_status 保持保存时状态，不能据此判断审核完成；应使用上述审核查询接口。审核记录位于独立 `meeting_status_proposal_reviews` 表，由启动建表脚本幂等创建，原分析 JSON 不更新。

验证：Java StatusProposalReviewTest 共 17 项通过（8 项新增审核 + 9 项继承存储回归），BUILD SUCCESS。使用现有 Java21/H2 隔离环境和 MockMvc；未进行 MySQL/Java23、真实 JWT、前端或跨进程联调。新表 SQL 的 MySQL 字符集后缀仅在 H2 测试时移除。执行接口现已完成，见第八轮说明。

## 已批准提案执行（第八轮，Java 接口）

`POST /api/meetings/{meetingId}/status-analyses/{analysisId}/proposal-executions`

```json
{"proposal_id":"p1"}
```

每次请求重新要求当前用户为 admin/owner，其他字段全部拒绝。Java 只执行数据库中 approved_proposal 的目标状态，包含人工修改后接受的目标；未审核或拒绝的提案返回 409。故事不存在或当前状态不同于 expected.status 时返回 409，无任何写入。即使当前状态碰巧等于目标，若没有成功执行记录仍为冲突。

故事状态、story_logs 的 move 日志、独立 meeting_status_proposal_executions 成功记录和审核表 execution_status=succeeded 在同一事务提交。任一写入失败全部回滚，审核仍为 not_started，可在修复故障后重试。失败请求不写成功记录，也没有独立的失败尝试历史。

成功响应包含 execution_status、analysis_id、proposal_id、story_id、previous_status、new_status、story_log_id、executed_by、executed_at。按分析 ID + 原始提案位置持久化去重；重复或并发执行返回第一次的成功记录，不重复更新或追加日志。其他有权限的用户重试也返回首次执行人和时间。权限仍逐次检查，后来的故事修改不改变历史成功结果。

`GET` 同路径供登录用户查询本分析的成功执行记录数组，尚无成功执行时返回空数组；执行进度同时可从 proposal-reviews 查询。原始分析结果、快照和 storage_status 不改变。新表在审核表之后幂等创建，无需更改已有表结构。

验证：Java21 + H2 MySQL 模式 + MockMvc，StatusProposalExecutionTest 共 25 项通过（8 项执行、17 项继承存储/审核），0 失败/错误。包含并发重复执行、跨分析竞争、人工修改目标、过期/删除故事、权限和载荷、原记录保留、事务失败整体回滚。未进行真实 MySQL/Java23/JWT 或前端联调；Python 代码未变。本轮停止，下一轮建议只接入会议页面的持久化提案与审核/执行状态展示。

## 会议页面状态提案展示（第九轮）

在线登录后，在「AI 助手 → 会议智能体」选择已保存会议，可在「故事状态提案」区查看分析记录。默认选择后端返回的最新记录，也可切换历史记录；独立刷新按钮重新获取分析、审核和执行数据。

展示摘要、待确认问题、保存时原文、故事保存时标题、原始变更与证据、批准后的变更、审核人及意见，以及成功执行前后状态、执行人、时间和故事日志编号。原文来自分析快照，不使用当前会议文本替换历史证据。审核进度读取 proposal-reviews，执行详情读取 proposal-executions；不把存储时的 pending 当作当前审核状态。

本轮新增接口调用全部为 GET，沿用 Java 登录令牌；包括 viewer 在内的登录用户可查看。无会议、无分析、无变更、加载中及请求失败分别展示。失败不伪装成待审或空结果，支持重试；切换会议/分析及组件卸载时丢弃过期结果。原文与提案使用 Vue 文本插值，不执行其中的 HTML。

本轮未增加分析触发、审核或执行按钮；页面原有 Agent 入口仍为旧版需求建议流程，不能用它生成新状态分析。新状态分析需要通过已提供的 Python 分析接口保存后，刷新此展示区查看。

在 frontend 目录验证：

```powershell
npm.cmd ci --no-audit --no-fund
npm.cmd run build
npx.cmd playwright test --config=playwright.status.config.js
```

生产构建成功；4 项 Edge 无头浏览器测试通过。测试自动启动 5187 端口 Vite，模拟 Java API 响应，验证页面与请求契约；未调用真实模型、Java 或 MySQL。下一步建议接入逐项人工审核操作（接受、修改后接受、拒绝），完成后刷新审核状态。

## 逐项人工审核页面（第十轮）

在线 admin/owner 用户在「故事状态提案」的待审核卡片中可选择接受、修改后接受、拒绝。member/viewer 仍只读，已经审核的提案没有修改入口。请求使用既有 Java proposal-reviews POST 接口及当前登录令牌，最终权限、旧状态和不可覆盖校验仍由 Java 执行。

修改后接受仅允许选择不同于原始目标和预期旧状态的整数状态，并要求非空修改原因。接受/拒绝的审核意见可为空，始终发送 reason 字段；不会发送 changes。意见长度限制为 1000，客户端不提交审核人、故事目标或证据等权限字段。

提交期间禁用当前表单，成功后刷新处理状态。失败保留当前填写内容，提示结果未确认，可使用「刷新处理状态」查询已有决定，或以相同内容重试。不会自动重试写请求；冲突不会覆盖已有审核。切换会议或分析后，旧表单的迟到响应不会刷新新选择。审核后不自动执行故事更新。

验证：frontend 中 npm run build 成功；npx playwright test --config=playwright.status.config.js 共 12 项通过（4 项展示回归 + 8 项审核相关）。覆盖两种只读角色、三种审核决定、修改校验、精确请求载荷、提交禁用、失败保留与重试、冲突刷新及会议切换。使用 Edge 和模拟接口，未验收真实 Java/MySQL/JWT 联调。

本轮停止。下一步建议只接入已批准提案的执行按钮与执行结果刷新。

## 已批准提案页面执行（第十一轮）

在线 admin/owner 可在已批准、未执行且存在批准载荷的提案卡片点击「执行已批准变更」。请求只提交 proposal_id，Java 使用保存的人工批准载荷执行；member/viewer、待审、拒绝或已成功执行的提案没有执行入口。

执行期间禁用按钮，失败显示后端原因及结果未确认提示，可刷新查询或手动重试同一提案；不自动重试写请求。成功后刷新审核/执行详情，显示状态变更、执行人、时间和故事日志。当前分析内保留 POST 已确认的结果，后续详情查询失败或读到旧状态也不会重新提供执行按钮。切换会议/分析后丢弃旧组件的响应，回到原分析时从服务端重新查询。

验证：frontend 中 npm.cmd run build 成功；npx.cmd playwright test --config=playwright.status.config.js 共 20 项通过（原有 12 项 + 8 项执行相关），使用 Edge 和模拟接口。新增覆盖两种只读角色、两种执行角色、精确请求和令牌、失败重试/禁用、冲突刷新、成功后查询失败及会议切换。未验收真实 Java/MySQL/JWT；本轮刷新范围为会议提案处理状态，未增加跨页面故事看板缓存同步。

本轮停止。下一步建议接入 Python 每日站会分析触发入口，并沿用 client_request_id 保存与重试语义。

## 每日站会状态分析入口（第十二轮）

在线 admin/owner/member 可在已保存会议的「每日站会状态分析」区发起分析。可选 Sprint 1–4，默认未指定，发送 null。viewer 仅查看。入口调用 Python 的 analyze API，不使用旧 Agent 需求建议接口；请求仅含 client_request_id、meeting_type=daily_scrum、current_sprint，原文和项目数据由 Python 经 Java 获取。

每个用户/会议/Sprint 的未确认请求编号保存在当前浏览器标签页的 sessionStorage，提交前先保存。网络错误、存储结果未知或不完整的成功响应均保留编号，手动重试使用相同载荷。刷新页面后，选择同一会议和 Sprint 可恢复未确认请求；重新打开标签页不保证保留。会话存储不可用时暂不发送请求，避免刷新后丢失重试编号。仅成功确认后的「重新分析每日站会」会生成新编号；改变 Sprint 使用另一请求作用域。

成功确认校验分析记录 ID、会议 ID、请求 ID 与 storage_status，随后刷新并优先选中返回的记录。列表读取失败与保存确认分别显示；不会自动重试分析写请求。切换会议、Sprint 或登录用户使旧响应失效，不污染新页面；服务端已收到的请求仍可能完成。

浏览器请求同源 `/meeting-ai/api/meetings/{id}/analyze`，沿用现有 Bearer 令牌及 401 会话处理。Vite dev/preview 将 `/meeting-ai` 前缀移除后转发至 `http://127.0.0.1:8090`。需要改地址时，在启动 Vite 前设置 PowerShell 环境变量 AICAP_AI_PROXY_TARGET；该地址只用于代理进程，不由页面用户指定。Python 启动命令仍使用前文的 8090 端口。

静态生产部署必须由 Web 服务器配置同源 `/meeting-ai/` 反向代理到 Python，移除该前缀并保留 Authorization；仅复制 dist 到普通静态服务器不会自动拥有 Vite 代理。Java 接口仍按原有 VITE_API_BASE 配置连接。本轮没有新增 Python CORS 放行或模型密钥前端配置。

验证：frontend 中 npm.cmd run build 成功；npx.cmd playwright test --config=playwright.status.config.js 共 27 项通过。7 项新增覆盖正确载荷/新编号、刷新后同编号重试、坏响应、Sprint 作用域、权限/未选会议、迟到响应、保存确认与列表失败，以及浏览器经真实 Vite 代理到本机 HTTP 夹具的路径和令牌转发。测试代理目标为隔离端口 18090，Java 和模型仍使用夹具；未完成真实 Python/Java/MySQL/模型端到端验收。

本轮停止。下一步建议进行每日站会流程的真实服务联调验收：分析保存 → 人工审核 → 执行 → 故事与日志核对。

## 真实服务联调验收（第十三轮）

已用独立 MySQL 8.0.31 数据目录、Java21 Spring Boot、真实 FastAPI/LangGraph、Vite、Edge 及真实 JWT 完成每日站会流程。模型为本机固定响应的 HTTP 夹具；未评估真实模型语义质量或外部提供方可用性。

验收确认：创建会议 → 分析保存后故事仍为待办 → 人工接受后仍为待办 → 执行后变为进行中 → 切换看板显示进行中。重复执行返回同一结果；同编号重新请求分析返回原记录且不再调用模型；原始分析快照不变。匿名执行返回 401，viewer 执行及发起分析返回 403。直接查询 MySQL 确认分析、审核、执行及 move 日志各一条，模型只调用一次。

本轮修复两处集成问题：index.html 的固定 8080 地址原先覆盖 VITE_API_BASE，现由 client.js 统一按 runtime override / VITE_API_BASE / 默认地址解析；执行成功后新增故事与日志缓存读取，使当前会话切换到看板时无需重载。缓存刷新失败有独立提示和只读重试，不改变已确认执行结果；更换登录会话后不会应用旧请求结果。

复现步骤见 [qa/DAILY_LIVE.md](../qa/DAILY_LIVE.md)。最终运行记录为 `qa/.daily-live/6003beea2dcf42ef8a3ab3381af5064b/result.json`，数据与日志被 Git 忽略且保留供复核。实例使用独立端口，不连接原 3306/3307 数据库；脚本结束后正常关闭本轮实例。

验证：Java 服务打包成功（Java21 临时参数，默认 Java23 未改）；前端生产构建成功，28 项浏览器夹具回归通过；1 项完整真实服务浏览器联调通过并完成数据库核对。没有扩大业务功能，本轮停止。下一步建议只验收真实模型对每日站会证据的提案质量，覆盖未验收、否定、阻塞和歧义场景。

## 真实模型质量验收（第十四轮）

新增 `python -m meeting_agent.evaluation`，使用生产 Skill/模型适配器/结果校验器和固定合成原文快照验收；不读取真实会议或写业务库。10 个场景覆盖明确完成/开始、否定、未验收、阻塞、将来条件、目标歧义、证据冲突、混合故事及原文指令注入。支持每场景 1–3 次，逐次保存报告；模型格式错误计失败，基础设施错误中断并留存进度。

本轮真实 deepseek-v4-flash 完整 20 次运行自动检查均通过，但人工复核发现 4 次无依据时间/阻塞事实扩写；首次探测另遇 1 次格式异常。因此整体质量验收未通过，不能以 20/20 代替事实准确性。生产提示词未修改。本轮 Python 回归 61 项通过。

运行命令与逐项发现见 [evals/QUALITY_REVIEW.md](evals/QUALITY_REVIEW.md)。原始报告在 qa/.daily-quality，已忽略；配置仅进程内加载，密钥不记录。下一轮建议只修正摘要事实约束后复测相同样本。

## 摘要事实约束收紧（第十五轮）

DailyScrumSkill 更新到 daily-status-v3：禁止从每日站会栏目补造昨日时间，区分普通缺陷、依赖、未验收与阻塞，并约束摘要、原因及问题清单中的预设。状态提案契约与人工审核流程不变。

同一模型、同一 10 场景各两次复测，v2/v3 均自动 20/20；最终版本人工复核未发现补造昨日，但 mixed-targets 第二次摘要仍使用“其他阻塞”，暗含未确认的阻塞事实。因此整体质量验收仍未通过，不能以自动通过替代语义质量结论。Python 回归 61 项通过。

逐轮证据和原始报告位置见 [evals/QUALITY_REVIEW.md](evals/QUALITY_REVIEW.md) 的 2026-09-21 节。本轮停止；下一步只修正摘要中隐含的阻塞断言，并复测相同样本。

## 隐含阻塞断言修正（第十六轮）

DailyScrumSkill 更新为 daily-status-v4，将预设限制覆盖摘要、原因、问题中的所有句式，并要求原文未涉及阻塞时省略摘要中的阻塞话题，明确阻塞则必须保留。输出契约与人工审核流程不变。

Python 回归 61 项通过。同一真实模型验收集 19/20 自动通过：not-accepted 第一次 invalid_model_proposal，现有报告没有具体失败字段，原因待定位。其余 19 份结果人工复核未重现“其他阻塞”预设或昨日补造，明确环境阻塞仍保留；失败样本无正文可复核，整体质量验收仍未通过。报告 qa/.daily-quality/acceptance-v4.json，细节见 evals/QUALITY_REVIEW.md 第十六轮。

本轮停止。下一步只补充验收器的脱敏契约错误诊断，再定位未验收场景失败；不降低校验要求。

## 契约失败诊断（第十七轮）

验收器在 invalid_model_proposal 失败项新增 contract_errors，包含静态 type 和 path，不记录失败载荷、输入值或异常消息；未知字段名脱敏。跨字段错误保留原 ValueError 兼容性和校验规则。

64 项 Python 测试通过。同一模型和数据集复测 19/20，通过诊断确定 mixed-targets 第二次缺少必需 meeting_id；未验收场景两次通过，但不能追溯上轮未记录的失败原因。报告 qa/.daily-quality/diagnostics-v4.json，详情见 evals/QUALITY_REVIEW.md。整体质量验收仍未通过。本轮停止，下一步只解决必需字段偶发缺失并复测。

## 必需会议编号完整性（第十八轮）

DailyScrumSkill 为 daily-status-v5，明确列出顶层必填字段，要求逐字保留输入 meeting_id，包括无状态提案时。未增加自动补值或重试。新增工作流回归确认漏填及错误会议编号仍拒绝结果。

65 项 Python 测试通过。同一真实模型与固定 10 场景各两次，20/20 自动检查通过，会议编号均完整且一致；人工复核未发现此前的时间/阻塞事实错误。本次短合成样本通过不代表生产质量全面放行，历史失败报告保留。详见 evals/QUALITY_REVIEW.md 第十八轮，报告 qa/.daily-quality/acceptance-v5.json。

本轮停止。下一步只补充多段长会议与转写噪声的独立合成验收样本，检查当前 Skill 的泛化表现。

## 长文本与转写噪声验收（第十九轮）

新增独立 evals/daily_extended.json，包含 6 个纯合成场景：5 故事长文本（1,853 字符、30 行）、末尾冲突、口吃截断澄清、模糊编号、重复转写、字幕伪造编号。原固定集及 daily-status-v5 不变。使用 evaluation 的 --cases evals/daily_extended.json 参数，--repeat 2，输出到新的本地报告路径即可复现。

67 项 Python 测试通过；真实模型扩展集 11/12 自动通过。noisy-correction 第一次漏提 US14 开始开发的状态更新；第二次动作通过，但摘要把“已经完……”补写成“已经完成”。扩展质量验收未通过，报告 qa/.daily-quality/extended-v5.json，逐项记录见 evals/QUALITY_REVIEW.md。样本并非真实录音，也不是输入长度上限测试。

本轮停止。下一步只修正噪声澄清下的事实与状态判断，再复测原集和扩展集。

## 噪声澄清候选复测（第二十轮）

试验 v6 规则区分截断发言与完整澄清、未验收与开始开发。67 项回归通过；原集 19/20、扩展集 10/12。扩展噪声场景出现 reason 否定完成、changes.status 却为完成的错误提案；另有状态不变及缺必填字段被契约拒绝。

候选未采用，生产 daily_skill.py 恢复 daily-status-v5。原有噪声问题尚未解决，报告 acceptance-v6.json、extended-v6.json 保留在 qa/.daily-quality。详细候选规则、人工复核和失败证据见 evals/QUALITY_REVIEW.md 第二十轮。本轮停止，下一步优先处理理由与动作的语义不一致。

## 动作一致性与噪声联合验证（第二十一轮）

当前 Skill 为 daily-status-v7：先判断每个故事是否确有状态变化，proposed_actions 只列建议执行的动作；被理由否定的动作、保持原状态的占位动作不能输出。噪声不补全，未验收不否定明确开始，但快照已进行中则不重复提案。

验收报告新增 action_difference.missing/unexpected，用故事编号、旧状态、新状态三元组定位漏提或多提；仅验收工具使用已知预期结果，生产不按理由关键词改写动作。新 daily_consistency.json 含 4 个对照场景，可通过 --cases evals/daily_consistency.json 运行。

70 项 Python 回归通过，三组真实模型自动检查分别 20/20、12/12、8/8。人工复核未再发现本轮目标错误，v7 保留；仍有个别验收/提供方信息的无依据扩写，整体语义质量未全面放行。详见 evals/QUALITY_REVIEW.md 第二十一轮及 qa/.daily-quality 的三份 v7 报告。下一步只处理理由和长摘要中的无依据推断。

## 信息缺失与确认范围（第二十二轮）

当前 daily-status-v8 明确区分未提及与明确否定，保留待确认事项的准确对象；不因需要确认交付时间而推断提供方未知，也不把验收安排需协商改成尚无安排。

70 项 Python 测试通过；原集、扩展集及对照集真实模型共 40/40 自动通过。逐条人工复核未重现本轮目标扩写，v8 保留。仍有一处摘要把网络重传误写成网络中继，见 evals/QUALITY_REVIEW.md 第二十二轮；不宣称整体语义质量全面通过。三份 v8 报告在 qa/.daily-quality 保留。助手独立目录未修改，交付后再对照最新版本合并发现。

## Planning 前置：成员画像只读 Tool（第二十三轮）

新增 meeting_agent.member_tool.MemberReadTool，通过当前调用人的 Bearer 令牌访问固定 GET /api/members/profiles。Java 的 Roles.any() 仍负责读取鉴权；Python 不直连数据库、不调用画像写接口、不触发画像智能体或人员分配。本轮仅交付可复用的只读工具，尚未接入 Planning 工作流或每日站会。

```python
from meeting_agent.member_tool import MemberReadTool
from meeting_agent.task_tool import TaskReadTool

# 地址来自服务端配置；令牌由已认证调用端逐次传入，不放入模型消息。
profiles = MemberReadTool(backend_origin).get_member_profiles(access_token=access_token)
```

投影字段：user_id、display_name、role、six_week_capacity_hours、title、tech_stack、capabilities、process_domains、summary、years_experience。技能项保留 name 和整数 level（1–5），三维各最多20项。成员最多1000，响应最多2MiB；必需字段缺失、类型错误、重复成员/同维技能名、重复JSON字段或坏响应整批拒绝，不跳过坏行伪装为完整上下文。合法空列表、空画像维度、0容量均原样保留。返回全部角色，不等同于允许将工作分配给所有角色。

six_week_capacity_hours 是 Java capacity_hours 的显式改名，依据 User.java 与 schema.sql 的“六周可用容量”定义，不能当作当前 Sprint 剩余容量或扣减后的可用工时。Java 自身可能在画像缺失时返回空维度/0年经验、容量缺失时返回60；本 Tool 忠实读取该接口结果，不能把这些默认值视为额外采集证据或推断画像完整度。没有读取最新AI评估分数或活动负载。

地址不接受路径、凭据或查询参数；令牌不缓存、不通过重定向或环境代理转发；默认超时10秒。稳定异常 MemberToolError.code：authentication_required、permission_denied、backend_redirect、backend_http_error、backend_unavailable、response_too_large、invalid_backend_response，不含令牌或后端响应正文。

验证：新增10项 HTTP transport 夹具测试，全部 Python 回归80项通过。覆盖精确GET/令牌、最小投影、容量口径、空值边界、无缓存、权限/重定向、网络错误、响应上限、坏行/重复数据及地址配置。没有调用实际Java/MySQL或真实模型，不宣称完成真实服务联调。Daily Skill 继续 v8。

下一步建议完成 Planning 的输入上下文契约，复用故事和成员只读 Tool；Sprint 日期、Goal、估时和剩余容量等缺失信息继续显式保留未知，不猜测。H02 尚未交付，助手可继续独立盘点，本工具实现不替代其缺口报告。

## Planning 输入上下文第一阶段（第二十四轮）

新增 planning_context.py 的 SprintPlanningInput 和 PlanningContextReader。当前只组装已授权会议文本、StoryReadTool 的故事快照、MemberReadTool 的成员画像，以及调用方显式提供的 current_sprint/target_sprint（1–4或null）。当前与目标Sprint独立保留，不从故事所属Sprint、当前日期或前端日历推断，也不把本轮输入视为Sprint权威服务。

```python
from meeting_agent.planning_context import PlanningContextReader
from meeting_agent.story_tool import StoryReadTool
from meeting_agent.member_tool import MemberReadTool

context = PlanningContextReader(
    StoryReadTool(backend_origin), MemberReadTool(backend_origin), TaskReadTool(backend_origin)
).load(meeting_id=meeting_id, transcript=authorized_transcript,
       access_token=access_token, current_sprint=None, target_sprint=3)
```

调用方必须事先验证会议访问权限并读取保存原文。聚合器按顺序各读取一次故事/成员；两个接口不是数据库原子快照，后续写入必须重新检查。任一Tool失败直接传播，不返回部分结果；输入错误在网络调用前拒绝，读取后重新验证嵌套内容且不通过序列化警告输出坏数据。

本阶段 tasks、sprint_goal、sprint_dates、story_estimates、sprint_remaining_capacity 都仅允许 null（表示来源尚未接入/未定义），不能填空列表、零或猜测值。将来接入权威数据后再扩展相应类型，不能据此宣称已能排期。stories=[]、members=[] 则表示成功读取的合法空列表；缺失的负责人引用保留原ID待核对，不造人或重分配。六周容量仍是原字段含义。

原文最长16000字符、非空段最多100；空行不会改变后续S编号。拒绝重复片段/故事/成员及额外字段，输入不含批准或动作输出。没有新HTTP入口、模型调用、Planning Skill、任务读取或业务写入；Daily仍v8。

验证：新增8项契约/聚合测试，Python全套88项通过；采用模拟只读工具，不代表Java真实服务联调。本轮采纳H02的未知数据与容量语义结论，40个记录的来源哈希均一致。

## 临时协作入口

从2026-09-24起，新任务的临时沟通集中在项目根目录 `.agent-collab/`（Git忽略）：README.md规定规则，CURRENT.md记录主/助手分工，tasks/归主AI，work/<任务编号>/与HELPER-STATUS.md归助手。H01/H02旧交付不移动，ready后保持冻结；没有新派工时不自行开始下一包。只有正式结论已落档、已closed且无活动引用的临时文件才可清理，不能删除唯一失败证据或未集成交付。

## Planning 任务快照接入（第二十五轮，2026-09-26）

本节替代上一阶段关于 tasks=null、两次读取的描述；上方示例已更新为三个必需读取器。

新增 task_tool.py：TaskReadTool 携带调用人令牌，只调用 GET /api/tasks。响应最多2MiB、1000项；严格拒绝缺字段、类型错误、重复任务/JSON键、非法周序以及与周排期不一致的 sprints；重定向不跟随，网络/鉴权失败仅暴露稳定错误码。不会按目标Sprint过滤或隐藏取消任务。

TaskSnapshot 分别保留 hours 与 estimated_hours，不合并为估时或剩余工时；story_ref/depends_on 保留接口逗号分隔原文，kanban_card_id 独立保留。sprints 是W1–W6派生的1–3，不是Story的1–4；跨期、缺失引用、故事与任务范围不一致保留待确认，不自动修正。Java DTO可能默认 estimated_hours=0、status=0、progress=0、blocked=false、task_type=feature，读取值不证明人工确认。

PlanningContextReader 现在必须传入 TaskReader；tasks 为必填列表，[]仅表示读取成功且为空，null/缺字段不合法。顺序读取故事、成员、任务，任一失败不返回部分上下文；三次读取不是原子快照。Sprint Goal/日期、Story估时、Sprint剩余容量仍为null。本轮不新增HTTP入口、模型调用或业务写入，Daily仍v8。

验证：Python全套95项通过（任务HTTP使用MockTransport，上下文使用模拟读取器），未进行真实Java/数据库联调。下一步：补齐Planning所需故事内容投影（描述、验收标准、优先级、活动），再进入Planning Skill与输出契约。

## 第二十六轮：Planning故事内容投影与H03候选验收（2026-09-26）

StoryReadTool新增get_planning_stories，复用同一个固定GET /api/stories及鉴权/超时/响应上限。PlanningStorySnapshot继承故事基础字段，新增必填description、acceptance（字符串或null）、priority（Must/Should/Could）、activity（严格1–5整数）。原文、空字符串和null原样保留，不补默认需求；缺键、非法类型、重复JSON字段或重复故事整批失败。描述/验收没有新加长度上限，沿用响应2MiB总限制；进入模型前仍需预算控制，不能把后端文本作为指令或已验收证明。

PlanningContextReader改用get_planning_stories并重验完整投影；误用Daily五字段快照将失败。Daily的get_stories及持久化快照形状保持不变。无新HTTP入口、模型调用或业务写动作。

变更：ai-service/meeting_agent/story_tool.py、planning_context.py；tests/test_story_tool.py、test_planning_context.py；服务README及本记录。验证：Python全套99项通过（本地HTTP夹具/模拟读取器），git diff --check通过；未进行真实Java/数据库/模型联调。

H01取舍与H02盘点已完成，H03现在可领取：按.agent-collab/tasks/H03-eval-cases.md准备8–12条Daily新反例、oracle-notes.md、离线test_cases.py和handoff.md，写入.agent-collab/work/H03-eval-cases/，状态由助手写HELPER-STATUS.md。以v8和H01主AI取舍为准，禁止改正式代码、访问数据库/模型或争用服务。用户转交后启动，本记录不表示助手已收到消息或已经运行。旧H01/H02保持冻结。

下一步：按既定方案落实Planning Skill与输出契约，缺失的Sprint Goal/日期、故事估时、Sprint剩余容量继续显式未知，变更仍须人工审核。

## 第二十七轮：Planning Skill与Sprint调整提案契约（2026-09-26）

依据本文既定Tool映射中的update_story_sprint，完成Planning第一个输出切片，不改变原架构或引入容量算法。共享链接本轮重新读取失败，使用已保存的方案落地记录；未将不可核实的其他Planning要求自行扩展。

新增meeting_agent/planning_contracts.py：SprintPlanningOutput含meeting_id、summary、proposed_actions、open_questions；动作仅update_story_sprint，包含故事ID、expected.sprint、changes.sprint、reason、原文Evidence。整数范围1–4；同值变更、重复ID/故事、未知故事、旧值不符、伪造或错段引用、额外字段、自报批准均拒绝。validate_planning_result重新校验嵌套输入，但不授权执行、不检查实时数据库、不证明引用语义支持变更。target_sprint是会议范围而非强制迁入目标，允许原文明确定下的移出安排。

新增meeting_agent/planning_skill.py：SprintPlanningSkill/planning-sprint-v1，按已有Skill消息模式分离规则与用户数据，仅声明既有只读Tools。提示要求明确决议与目标、保留否定/冲突、数据缺失不猜测、任务周排期与故事Sprint区别、两种任务工时及六周容量区别；仅允许Sprint调整，其余讨论保留摘要/问题，不冒充受审动作。输入序列化后UTF-8最多128000字节，超限整包拒绝planning_context_too_large，不截断证据。此为本地请求保护上限，不是模型token上限承诺。

新增tests/test_planning_skill.py共9项：合法/空提案、JSON往返、会议/快照/引用匹配、重复/冲突、严格类型及越权字段、消息数据分离、超预算、嵌套可变列表、引用出处与语义证明的区别。全套108项通过，git diff --check通过。测试使用构造结果，未调用真实模型、Java或数据库；提示包含防注入规则不等于实测防注入保证。

无Planning HTTP入口、LangGraph路由、持久化、审核执行接入；Daily v8与evaluation未改，H03助手继续独占候选验收。下一步接通Planning纯文本工作流和已有模型适配器，验证错误/重试边界，再安排真实模型验收；负责人分配、估时和任务调整仍是后续阶段。

## 第二十八轮：Planning纯文本工作流、H03接收与H04安排

新增meeting_agent/planning_workflow.py：SprintPlanningWorkflow复用PlanningContextReader、SprintPlanningSkill、validate_planning_result与CompletionModel协议，可直接注入既有ChatModelClient。LangGraph顺序为读取上下文→模型分析→契约验证，返回SprintPlanningOutput；调用方须先验证会议访问与分析权限。令牌仅在调用闭包传给读Tool，不进图状态或模型消息；无checkpoint、关闭tracing、无自动重试、无部分成功输出、无写入。

工具/模型错误沿用稳定错误码；无效或超预算上下文转invalid_planning_context，在模型前停止；模型提案契约失败转invalid_model_proposal。此内部工作流尚未注册到HTTP入口，也未接Planning持久化/人工审批/执行。

新增tests/test_planning_workflow.py的6项测试，使用真实ChatModelClient配MockTransport验证完整链路、鉴权令牌隔离、输入/Tool/超预算提前停止、坏提案、供应商超时/坏JSON不重试、连续调用重新读取。没有真实模型或Java/数据库调用。

H03接收：核对8个来源SHA256和3个交付SHA256全部一致；逐例检查11例（7动作正例、4无动作反例），接收cases.json原样至evals/daily_h03.json、oracle-notes原样至evals/daily_h03_oracle.md。test_cases.py仅调整正式路径/说明后移入tests/test_daily_h03_cases.py，9项离线检查通过。精确动作Oracle能拒绝全弃权和多余动作，但不能自动理解reason矛盾、关系保真或术语，仍需人工复核真实模型结果。H03原交付保持冻结，未删除；Daily Skill及evaluation未修改。

验证：全套123项Python测试通过，git diff --check通过。H03未真实调用模型，不报告模型通过率。

H04现在可领取，任务书.agent-collab/tasks/H04-ui-regression.md；只写work/H04-ui-regression/和HELPER-STATUS.md，盘点前端角色/冲突/重复点击/未知结果/切会议/缓存边界覆盖并准备候选测试，不改正式代码或启动共享服务。用户转交后启动。

下一步建议：为Planning准备小规模模型验收集，先验证明确调整、相对Sprint未知、否定/冲突、范围移出、容量缺失等场景，再扩展HTTP与受审持久化。已接收H03可由evaluation --cases evals/daily_h03.json单独验收，不与本轮离线结果混淆。

## 第二十九轮：Planning合成模型验收集与离线检查

新增evals/planning_quality.json：8例（3动作正例、5无动作案例），包括明确数字、未知/已知本Sprint、未解决冲突、条件未满足、移出目标Sprint、无变化、容量未知。每例显式携带current_sprint/target_sprint（可null）、故事/成员/任务快照；工时8与13、六周容量60保留原义。动作预期与支持片段供自动检查，rubric供人工复核，不是模型输出。

新增meeting_agent/planning_evaluation.py：复用既有ChatModelClient，输入先全量预检再构造模型客户端；检查ID唯一、上下文预算、动作旧值/目标/类型/支持片段。assess调用正式Planning契约，精确比较故事/Sprint旧新值，检查必要问题和支持片段；不将词句匹配包装成语义验收。报告逐例保存并始终标manual_review_required，供应商故障停止不反复重试；结构不合格结果记录脱敏诊断。Daily评价器未改。

新增tests/test_planning_evaluation.py：数据与构造Oracle、全弃权/缺问题失败、非法预期预检、CLI报告、供应商失败停止、客户端构造前阻止坏集。全套128项通过，git diff --check通过。测试日志中的8/8来自Mock模型的构造fixture，不是Planning真实模型通过率。本轮未访问真实模型或数据库。

从ai-service运行真实验收的入口（另轮执行）：

```powershell
.venv/Scripts/python.exe -B -m meeting_agent.planning_evaluation --cases evals/planning_quality.json --env-file ../backend/.env --output .planning-quality/report.json --repeat 1
```

仅显式加载既有AICAP_LLM配置，不展示密钥；报告含合成内容，人工检查否定/冲突、相对范围、容量推断、摘要/理由、批准/执行措辞后才能形成质量结论。下一步运行这8例真实模型验收并逐份复核；H04保持独立进行，不改助手状态/交付。

## 第三十轮：Planning真实模型验收与H04静态接收

Planning首次真实模型8例自动8/8，人工发现把一致的Story/Task Sprint误报为不同步。planning_skill.py更新至planning-sprint-v2，限定现有快照与提案目标比较并清理无问题占位。8例各2次复验自动16/16；全部输出人工复核后，Sprint误报未重现，但conditional-negative仍出现“外部依赖”等范围扩写，不能判全文语义通过。具体结论和报告哈希见ai-service/evals/PLANNING_QUALITY_REVIEW.md，原始报告在ai-service/.planning-quality/。Python128项回归通过；无业务写入。

H04接收为静态分析与候选测试：19来源+3交付哈希全部匹配，node --check candidate.spec.js通过；4条浏览器候选尚未运行，不计通过。确认execute API缺2xx响应结构校验、组件直接emit返回值，残缺响应假成功风险有代码依据，需下一步隔离浏览器复现与修复；同会议切analysis竞态/sessionStorage失败/401清权限三例也待运行。原交付冻结保留，未复制进正式CI。

H05可领取但仅Sprint Review要求溯源和验收素材，任务书.agent-collab/tasks/H05-review.md，交付work/H05-review/；缺少原方案细节必须列缺口，不发明契约，不修改生产文件或调用模型。用户转交后启动。

下一轮优先复现并修复H04指出的残缺执行响应假成功风险；Planning保留v2并记录剩余语义问题，不连续堆叠提示词。

## 第三十一轮：H04执行确认缺口复现与修复

主AI将冻结的H04候选复制至frontend/e2e-status/h04-boundaries.spec.js，首次运行4项：3通过、1失败。残缺HTTP 200响应实际显示“执行结果已确认，故事日志 #undefined”和“执行成功”，确认此前静态风险。失败证据摘要在本记录保留，助手原文件未改。

修改frontend/src/api/statusAnalyses.js：execute返回前验证analysis_id/proposal_id与请求一致、execution_status=succeeded、合法US故事ID、严格整数0–2前后状态且发生变化、正安全整数story_log_id/executed_by、非空执行时间。坏响应抛出错误，复用组件已有“未能确认执行结果”与状态刷新/同提案幂等重试路径，不写confirmedExecutions缓存、不触发假成功。此校验不证明服务端真实提交，不替代服务端鉴权/事务/审计；GET执行列表和审核响应验证不在本轮改动范围。

正式H04测试扩为10项：原3个边界与7类残缺/错归属/错类型响应。完整playwright.status回归38/38通过（41.4秒），包括既有正常执行、冲突、重试、详情/看板刷新失败和代理路径。npm run build通过，git diff --check通过。使用本地Vite/无头Edge与mock业务API，无真实Java/MySQL写入。Python/模型未变，本轮不重复模型验收。

本轮变更：frontend/src/api/statusAnalyses.js、frontend/e2e-status/h04-boundaries.spec.js、服务README、本记录及临时协作状态。H04的4条候选均已验证并接入正式测试，原助手包冻结保留。H05仍按既有独占目录交付。

下一步回到Planning，处理PLANNING_QUALITY_REVIEW.md中依赖范围扩写的已知语义问题，再复验相关场景；不以自动动作全通过代替人工质量结论。

## 第三十二轮：Planning依赖语义范围修正

planning_skill.py升级planning-sprint-v3，区分未提及与明确未知，禁止猜依赖内外部及扩写已知提供方/责任。新增evals/planning_dependency.json四例正反对照，扩展tests/test_planning_evaluation.py离线Oracle检查。旧8例未修改。

Python129项通过；真实模型专项8/8、原场景15/16，共23/24自动通过。一次move-out-of-target返回invalid_model_response并被拒绝，无可验证结果，失败保留不重跑覆盖。人工复核23份有效输出，依赖范围已知错误未重现，但摘要仍有重复/来源混淆问题，不判整体质量通过。详情和报告哈希见evals/PLANNING_QUALITY_REVIEW.md。

本轮更新README、正式记录与临时协作状态；没有业务写入，没有修改助手交付。下一步完善模型适配器的脱敏格式诊断，定位invalid_model_response，再针对性复验。

## 格式诊断补强（第三十三轮）

model_client.py保留原有ModelError.code/异常文字，对invalid_model_response新增白名单diagnostic标签：envelope/content的json_syntax、duplicate_key、invalid_encoding、too_deep、structure，以及choices_structure、finish_reason_missing、message_structure、unexpected_message、content_not_text。仅输出固定标签，不记录供应商正文、任意字段名、令牌或原始异常消息。Planning评价报告新增model_diagnostic；Daily API/错误码兼容，Skill仍v3。

新增test_model_diagnostics.py验证11类异常、标签白名单与有效结果；Planning报告测试检查错误标签落盘且无无效result。Python131项通过。测试控制台的0/8与provider_timeout来自故障注入，不是真实模型结果。

将原move-out-of-target案例原样提取到.planning-quality/format-target-case.json，计划定向运行3次。真实调用收到provider_http_error，入口立即停止，无有效case结果；报告planning-v3-format-target.json保留。不能把本次阻断解释成原来的格式错误，也不能声称错误已经修复或3次通过。旧planning-v3-baseline.json的1次invalid_model_response仍无法追溯细分原因，因为当时没有诊断标签和原始报文，未覆盖旧报告。

下一步：核对供应商请求可用性并在恢复后完成这3次定向复验；若格式错误再现，依据新标签做有界修复，不盲目重跑到全绿。仍保留此前摘要冗余/来源混淆待办。

## 第三十四轮：加速完成Planning后端API链路（2026-09-27）

按用户要求把优先级调整为完整闭环：减少低影响措辞的反复提示词打磨，继续保留证据、权限、人工审核、幂等与执行前检查。复用项目现有LangGraph/httpx/Pydantic和Daily已验证流程，本轮无需引入额外GitHub框架或依赖。

新API配置已恢复：planning-v3-new-key-20260927.json单次真实合成案例1/1通过，人工核对目标4、原文引用、摘要/问题正确。仅说明当前可用及本次通过，不追溯消除旧格式失败，未修改或展示密钥。

### 本轮接口

- Python POST /api/meetings/{meeting_id}/planning/analyze：body含meeting_type=sprint_planning、client_request_id、可选current_sprint/target_sprint。先经Java授权/加载原文，再按调用者请求号查询既存结果；没有结果才读取上下文并调用模型。返回结果含analysis_id/client_request_id/storage_status。
- Java POST/GET /api/meetings/{meetingId}/planning-analyses，GET /by-request/{clientRequestId}及/{analysisId}：保存不可变原文、提案、目标故事快照；重新校验证据及Sprint旧值，幂等冲突保护。没有批准就不改故事。
- Java POST/GET .../{analysisId}/proposal-reviews：admin/owner可approve、modify_and_approve或reject，修改目标为changes.sprint（1–4）；批准载荷单独保存，原分析保持不可变。
- Java POST/GET .../{analysisId}/proposal-executions：POST仅接受proposal_id，执行持久化批准载荷，锁定分析/审核/故事、再次校验旧Sprint；原子更新Story.sprint、写story_logs(edit)和执行审计。返回previous_sprint/new_sprint/story_log_id等；重复执行返回首次持久结果，不重复写入。不会联动Task排期或修改Story.status。

### 文件

Python新增planning_store.py（复用Daily有界HTTP/原载荷重试），更新api.py、analysis_store.py；新增tests/test_planning_api.py，test_api.py只扩展测试夹具支持Planning路径。
Java新增PlanningAnalysisDtos、PlanningAnalysisService/Controller、PlanningProposalReviewService/Controller、PlanningProposalExecutionService/Controller；新增planning-analyses.sql、planning-proposal-reviews.sql、planning-proposal-executions.sql并注册application.yml。新增PlanningAnalysisStorageTest、PlanningProposalFlowTest。

### 验证与边界

Python135项通过，包含授权失败不调用模型、只读失败/错误提案不保存、已保存请求恢复跳过模型、存储响应未知时只重试完全相同载荷。Java最终PlanningProposalFlowTest 14项、Daily StatusProposalExecutionTest 25项，共39项通过；此前Planning/Daily存储18项亦通过，继承用例有重复不相加计数。覆盖人工修改、拒绝、权限、旧值冲突、审计失败回滚、并发重复执行、原记录不可变。git diff --check通过。
Java为隔离H2/MySQL模式+MockMvc；Python为HTTP测试夹具+模型fixture。没有启动生产Java/修改真实MySQL，没有前端Planning页面或完整真实服务联调，不能把分段验证说成端到端部署完成。启动Java时按既有schema init应用新增3表；不重置业务表。

### H05接收

14个来源及4个交付哈希全部匹配（核对在本轮改api.py前完成）。接收为Sprint Review要求溯源及8个候选文字案例，原包冻结。采纳展示不等于验收、部分范围/否定/冲突需保留等已有原则；SR-06及弱依据项仍待决定，不将候选建议冒充既定正式契约。Review不阻塞当前Planning闭环，不自行开始Retro/Refinement。

下一轮集中接前端Planning分析/审核/执行面板，复用Daily组件交互模式与响应校验，完成浏览器闭环验证；随后真实Java/Python/MySQL联调。暂不继续扩大Prompt或引入通用新框架。

## 第三十五轮：Git同步与Planning前端闭环（2026-09-27）

用户授权以LHWYAN身份同步Wayne-SQS/AIcap。确认GitHub当前账号LHWYAN、Git作者LHWYAN；先提交所有本地会议Agent成果848736f，再合并origin/main的7ef25f6为5530a0e，格式整理a2ab009，均已推送main。没有强推、重置或丢弃远端更新。冲突client.js保留远端FormData处理及本地可选base；vite.config保留/api与/meeting-ai代理。密钥/.env、虚拟环境、模型运行报告、临时协作目录保持忽略。

合并验证：前端构建、原38项浏览器、135项Python、39项Java事务回归通过。远端新增Task.priority由现有投影忽略，未把新的项目规划Agent当作会议Sprint Planning审批入口。

同步后完成会议Planning前端：MeetingPanel新增“打开会议 Sprint Planning”入口，独立懒加载。新增PlanningAnalysisForm/Panel、PlanningProposalReviewForm/Execute和planningAnalysis/planningAnalyses API。当前/目标Sprint可选且不猜测；请求号按用户/会议/两Sprint隔离，发送前存sessionStorage，不确定结果重试沿用。展示原文证据、原始与批准Sprint；支持接受/修改后接受/拒绝及独立执行，执行确认校验归属和审计字段，刷新故事/日志。无写权限不显示审核执行入口。

新增planning-flow.spec.js三项：分析→修改目标Sprint4→审核→执行→刷新审计仍在；残缺执行响应不能假成功；member可分析但不可审核执行。使用Vite/Edge和mock业务接口。前端构建通过，完整浏览器回归结果以下续记；尚未完成真实Java/Python/MySQL前端联调，不能视为已部署生产闭环。

下一步直接做Planning真实服务联调（同一合成会议从分析保存到人工审核执行并核对数据库/日志/看板），优先收敛端到端问题，不扩展新会议类型或反复打磨低影响措辞。

最终验证：Planning加入后的完整前端浏览器回归41/41通过（48.4秒），生产构建通过。
