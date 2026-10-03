# 会议 Agent：既定方案落地与首轮契约

基线：[用户指定的共享对话](https://chatgpt.com/share/6aa60c39-0798-83ec-b24d-87f648047719)，读取日期 2026-09-13。

## 已确定的路线

采用 Python + FastAPI + Pydantic + LangGraph 工作流服务；主业务保持 Vue + Spring Boot + MySQL。共享对话中的 PostgreSQL 是示意图，技术选型明确要求沿用现有业务数据库。Agent 不直连业务数据库。Framework 编排状态、暂停与恢复；Skill 定义会议协议；Tool 访问 Java 业务接口。只读工具可自动调用，写工具须人工接受、修改后接受或拒绝。

顺序沿用共享对话：Tool API 与模型 → 纯文本 Agent → DailyScrumSkill → 人工审核闭环 → Planning → Review / Retro / Refinement → Assignment Engine → 上传录音与 STT / Diarization → MeetingQualitySkill → 实时增强。既有录音能力保留，但不改变本轮优先级。

## 代码检查结果

| 现有代码 | 实际能力与缺口 |
|---|---|
| `java-backend/.../agent/AgentRunner.java`、`AgentTools.java` | Java 有界工具循环；最终提案仅 `pool.create`。当前不是方案中的 LangGraph 工作流 |
| `java-backend/.../dto/MeetingDtos.java` | 会议只有标题和转写；审核载荷仅新建需求池，不支持故事状态变更 |
| `java-backend/.../controller/StoryController.java` | 可读、创建和 PATCH 故事，且留变更日志；写逻辑目前在 Controller，后续 Tool 执行需要复用/下沉业务逻辑并加审核事务 |
| `java-backend/.../entity/Story.java` | ID 使用 US13 等真实形式；状态 0/1/2，Sprint 1–4，无故事估时或剩余工时字段 |
| `java-backend/.../entity/Task.java` | 任务与故事分离，Txx 通过 kanban_card_id 关联 USxx；任务有 estimated_hours、progress、blocked，状态 3 是取消 |
| `java-backend/.../entity/User.java`、`dto/MemberProfileDtos.java` | 有六周 capacity_hours 和画像；没有可直接使用的 Sprint 剩余容量 |
| `frontend/src/constants.js` | 故事看板仅待办 / 进行中 / 已完成；不能新增状态 3 来表示阻塞 |
| `backend/`、`legacy/` | 历史归档；新增 Python 工作流放独立 `ai-service/` |

检查时 AIcap Git 工作区干净，未发现仓库内 AGENTS.md。

## 本轮唯一交付：Daily Scrum 状态提案契约

实现位置：`ai-service/meeting_agent/contracts.py`。这是第一步的一个独立切片，不代表全部 Tool 或完整 MeetingState 已实现。

- 输入：meeting_id、固定 daily_scrum 类型、原文分段、Java 故事只读快照。current_sprint 可显式提供，未知时为 null，不按当前日期猜测。
- 输出：summary、proposed_actions、open_questions；允许空提案。
- 当前唯一允许的 Action：`update_story_status`，只允许修改 `changes.status`。提案包含 proposal_id、story_id、expected.status、reason 和逐条 evidence。
- 状态严格使用整数 0/1/2，拒绝布尔、字符串、浮点和额外字段。不把示例 `US-13` 或 `T12` 自动当作实际故事 ID。
- 校验会议一致性、真实快照目标、旧状态一致性、非空且逐字连续的原文引用、重复提案/冲突目标、无效不变操作和输入大小。JSON Schema 可直接从模型生成。
- 输出不接受 approved、reviewer_id 或执行结果等字段；模型无法通过提案自报批准。契约校验不是权限控制，也不证明证据语义支持状态变更。

例：快照 US13.status=1，原文“US13 已验收完成。”，可形成 expected.status=1 → changes.status=2 的待审建议；本轮不会执行 PATCH。对于“基本完成但未验收”，后续 Skill 必须保留约束，不能仅凭原文匹配就认定可完成。

## Tool 与现有业务 API 的映射边界

下表用于后续接入，不表示新 Tool 已注册或可执行。

| 方案 Tool / 模型 | 业务来源或需要补齐的能力 |
|---|---|
| get_stories / Story | `GET /api/stories`；本轮只读投影 id/title/status/sprint/owner_id |
| get_project_context | 后续聚合故事、任务和成员等只读接口；本轮只包含故事快照 |
| get_current_sprint / Sprint | 当前仅数字 Sprint 字段，缺日期、Goal 和当前 Sprint 权威接口，不能推断 |
| get_member_profiles / Member、Profile | `GET /api/members/profiles` 已含真实成员、画像三维度和六周容量 |
| get_member_bandwidth / Bandwidth | 需明确 Sprint 时间范围、计划负载和可用工时；不得将六周容量直接当 Sprint 容量 |
| update_story_status | 目标业务操作为 `PATCH /api/stories/{story_id}`，body 仅 status；未来必须经审核执行入口，不能把此普通 PATCH 当审核入口 |
| create_story、update_story_sprint、assign_story_member | 对应现有 POST/PATCH stories；尚未提供 Agent 审核 Tool；owner_id=null 目前不会清空负责人 |
| update_story_estimate | Story 无估时字段，Task 估时与 Story 估时不可直接混用；待对应阶段补模型 |
| create_blocker / Blocker、create_risk / Risk | 独立实体和受审写接口待实现；Task.blocked 不等于完整 Blocker 记录 |
| create_action_item / ActionItem | 旧分析已有行动项文本，完整可审核执行的实体和接口待实现 |
| create_decision_log / DecisionLog | 现有 StoryLog 是修改日志，还需关联会议、证据、前后值、审核人和执行结果 |

后续审批执行应由服务端绑定提案与会议、保存原始/编辑后载荷、重新检查审核权限与真实故事状态，并在事务内完成修改、审计和幂等保护。`expected.status` 在本轮只检查输入快照，不能替代未来 Java 事务内的并发检查。

## 验证与下一轮

使用独立 unittest 契约测试验证正常提案、空提案、恶意扩字段、不支持动作、类型错误、伪造证据、错误目标、旧状态不符、重复/冲突提案、上下文歧义、长度限制、JSON Schema 和 JSON 往返。不调用模型、不读写数据库。

建议下一轮只接通 **Java 故事只读上下文 Tool**：通过认证 REST API 读取真实故事并投影为本轮输入，验证权限、响应字段及错误处理。完成后再推进纯文本 LangGraph/DailyScrumSkill；本轮到此停止。

## 第二轮接续：Java 故事只读 Tool（已完成）

上文“本轮”与 Tool 映射表记录第一轮状态。第二轮现已落实 `get_stories`：`ai-service/meeting_agent/story_tool.py` 通过用户 Bearer 令牌调用 Java `GET /api/stories`，严格投影为 `StorySnapshot`，并支持组装 `DailyScrumInput`。无需修改 Java 接口或数据库。

读取权限依据现有 `StoryController.list` 的 `Roles.any()` 和 `AuthInterceptor`；保持 viewer 可读，不把读取授权扩大为分析或审批授权。URL 由服务端配置，令牌逐次提供；错误不会返回伪造的空项目。字段、大小、重定向与错误码规则见 `ai-service/README.md`。

验证命令：在 `ai-service/` 执行 `..\backend\.venv\Scripts\python.exe -m unittest discover -s tests -v`。结果：21 项通过，含新增 12 项 Tool 测试。HTTP 测试使用本机夹具，网络/超时用异常注入；未运行真实 Java/MySQL 联调、前端或模型验收。

本轮边界到此结束。建议下一轮只完成 **纯文本 Daily Scrum 的 LangGraph 最小分析流程**：把已有会议输入、只读故事 Tool、模型适配和提案校验串联，产出待审提案；人工审核和实际写入仍按既定顺序单独实现。

## 第三轮接续：纯文本 LangGraph 流程（已完成）

新增 `daily_skill.py`、`model_client.py`、`workflow.py` 和 `tests/test_workflow.py`；更新独立服务依赖、README，并忽略 `.venv`/`.env`。本轮使用 LangGraph 1.2.11，模型配置沿用 Java 的 `AICAP_LLM_*`。

流程：normalize → load_context → analyze → validate。只输出通过现有证据与快照校验的 Daily Scrum 待审状态提案；一次只读 GET、一次模型 JSON 请求，没有写操作、审批执行、HTTP 服务入口或持久化。用户令牌不进入图状态或模型消息，关闭 tracing。

本轮 DailyScrumSkill 实现的是状态变更最小切片。阻塞、剩余工时、人员调配仍保留为摘要/待确认信息；不宣称完整 Daily Skill 的全部动作已经实现。模型语义与证据含义仍需后续真实验收与人工审核。

验证：在 `ai-service/` 使用 `.venv/Scripts/python.exe -m unittest discover -s tests -v`，33 项通过（原 21 项 + 本轮 12 项）；`pip check` 无依赖冲突。使用真实 LangGraph + 本机 Java/模型 HTTP 夹具；没有调用真实模型、写入数据库或进行前端联调。一次在仓库根目录启动的复验因导入路径失败，改为文档规定目录后全量通过。

本轮到此停止。下一轮只实现 **FastAPI 文本分析入口及鉴权接入**，使现有应用能调用本轮模块；在服务端验证会议访问权和分析角色，不把故事只读权限当成分析权限。随后继续既定的人工审核与写入闭环。

## 第四轮接续：FastAPI 文本分析与鉴权（已完成）

新增 `meeting_agent/api.py`、`meeting_agent/meeting_access.py`、`tests/test_api.py`；更新 requirements.txt 和 README。提供 `POST /api/meetings/{meeting_id}/analyze`，只接收 daily_scrum 类型和可选 current_sprint。先向 Java `/api/auth/me` 查询真实角色，再读取保存的会议原文，最后调用既有工作流。

沿用现有 Java 权限：admin/owner/member 可分析，viewer 不可分析；会议对登录成员共享读取，无额外仅创建者限制。缺令牌/过期、禁止访问、会议不存在、伪造请求字段和异常上游响应均在相应边界停止。返回值仅为待审提案，不落库、不写故事、不接入旧 pool.create 审核。

验证：在 `ai-service/` 运行 `.venv/Scripts/python.exe -m unittest discover -s tests -v`，45 项通过；pip check 无依赖冲突。接口通过 TestClient + 本机 Java HTTP 夹具 + 模型夹具验证；并未进行真实 Java/MySQL 或模型效果验收。启动及接口示例见 ai-service/README.md。

本轮到此停止。下一轮建议只完成 **待审故事状态提案的持久化与查询**，由服务端保存原始提案、证据和快照，为后续人工接受/修改/拒绝及审核后写入提供可信记录。

## 第五轮接续：Java 状态分析持久化与查询（已完成）

本轮唯一重点是 Java 存储边界；Python 分析入口仍只返回结果，自动提交存储接口尚未接入。

### 接口（现有 Java 服务，默认 8080）

- `POST /api/meetings/{meetingId}/status-analyses`：admin/owner/member 可提交，返回 200；viewer 不可写。
- `GET /api/meetings/{meetingId}/status-analyses`：登录用户查询该会议记录，按保存时间倒序。
- `GET /api/meetings/{meetingId}/status-analyses/{analysisId}`：登录用户查询详情，路径会议与记录须匹配。

提交 body：

```json
{
  "client_request_id": "stable-request-uuid",
  "result": {
    "schema_version": "1.0",
    "meeting_id": "Java会议ID",
    "meeting_type": "daily_scrum",
    "summary": "会议摘要",
    "proposed_actions": [],
    "open_questions": []
  }
}
```

result 使用 Python DailyScrumOutput 的完整序列化结构，非空提案沿用 proposal_id/action/story_id/expected/changes/reason/evidence。client_request_id 由可信调用端生成并在重试中复用，只允许小写字母、数字和连字符，长度 1–80。同一会议、提交人及请求编号，载荷一致返回原记录；载荷不同返回 409。不同请求编号表示新的提交。

保存前，Java 再次严格校验 JSON 字段、类型、状态范围、会议 ID、唯一提案/目标、原文行号及连续证据；从数据库读取并锁定对应故事，要求 expected.status 与当前状态一致，否则 409。目标不存在也返回 409。任一提案不合法，整批不保存。

数据库新增 `meeting_status_analyses`，保存提交人、时间、会议原文、完整结果及涉及故事的**保存时快照**。快照由 Java 重新读取，不能由客户端提交；它不是 Python 分析开始时的完整项目上下文，也不包含未涉及的故事。原文分段与 Python splitlines 行号一致，空行保留序号位置。

返回字段：id、meeting_id、client_request_id、submitted_by、created_at、status、transcript、result、story_snapshots。有提案时初始 status=pending，无提案时 no_changes。返回的 result 和快照独立于后续故事修改；相同请求重试不会刷新原快照或因后来的状态变化而重复保存。

记录是经过后端校验的**候选提交**，不证明内容一定由模型生成，也不证明证据语义正确；提交人不能自报批准或执行状态。当前按分析批次保存，尚无逐项审核记录、修改接口或故事写入。后续审核时仍需重新验证权限、目标与状态。

并发使用会议行锁串行化同会议提交，并以固定故事 ID 顺序读取锁定快照；数据库唯一键作为持久化约束。建表脚本经 application.yml 注册，Java 启动时在基础 schema.sql 之后幂等执行，旧表不改动。

### 文件清单

新增：
- `java-backend/src/main/java/com/aicap/dto/StatusAnalysisDtos.java`
- `java-backend/src/main/java/com/aicap/service/StatusAnalysisService.java`
- `java-backend/src/main/java/com/aicap/controller/StatusAnalysisController.java`
- `java-backend/src/main/resources/db/status-analyses.sql`
- `java-backend/src/test/java/com/aicap/contract/StatusAnalysisStorageTest.java`

更新：`java-backend/pom.xml`（仅测试 H2 依赖）、`java-backend/src/main/resources/application.yml`（建表脚本注册）、本交接文档与 `ai-service/README.md` 的进度说明。

### 验证结果与边界

在 java-backend 运行 Maven 定向测试 `StatusAnalysisStorageTest`：8 项通过，0 失败/错误，BUILD SUCCESS。测试使用真实 JDBC/Spring 事务、MockMvc 和隔离 H2 MySQL 模式，验证读回快照、幂等、并发、状态冲突、标量类型拒绝、无效批次不落库、角色限制和空结果。建表测试加载正式 SQL，仅移除 H2 不支持的 MySQL 字符集后缀，并重复执行验证幂等。

本机 MySQL 3307 与 Docker 未运行，仓库旧构建脚本中的 JDK23/Maven 路径不存在。本轮用可用 Java21 执行以下兼容验证，未修改 pom 中默认 Java23：

```powershell
$env:JAVA_HOME = 'D:/LHWYAN/download/IntelliJ IDEA 2025.1.2/jbr'
& 'D:/LHWYAN/download/Java/apache-maven-3.9.15/bin/mvn.cmd' -B '-Djava.version=21' '-Dmaven.compiler.source=21' '-Dmaven.compiler.target=21' '-Dtest=StatusAnalysisStorageTest' test
```

没有进行 MySQL/Java23 运行验收或生产库迁移，也未运行依赖 MySQL 的旧契约套件。新权限测试注入已认证用户上下文，验证角色守卫，不重测 JWT。H2 结果不能替代 MySQL 方言、锁行为及生产启动验收。

本轮停止。下一轮建议只接入 **Python 分析结果自动提交 Java 存储接口**，返回持久化记录 ID，明确存储失败及重试语义；随后继续人工审核与故事状态执行。

## 第六轮接续：Python 自动提交 Java（已完成）

分析入口新增必填 client_request_id。鉴权和会议读取后，先调用 Java 新增的按当前用户/会议/编号查询接口；已有记录直接返回，否则执行工作流并提交相同格式的 DailyScrumOutput。保存确认后才返回 analysis_id、client_request_id、storage_status 和原结果字段。

只对存储网络/5xx 故障自动重试一次，同编号同载荷；第二次仍不确定时返回 storage_outcome_unknown，不能把超时当作未写入。后续同编号请求查回已存结果。并发模型调用不去重，但 Java 存储仍幂等；同编号不同提案冲突而不覆盖。新增 Java 查询严格按当前认证用户过滤。

新增文件：`ai-service/meeting_agent/analysis_store.py`、`ai-service/tests/test_analysis_store.py`。
更新文件：`ai-service/meeting_agent/api.py`、`ai-service/tests/test_api.py`、`java-backend/src/main/java/com/aicap/controller/StatusAnalysisController.java`、`java-backend/src/main/java/com/aicap/service/StatusAnalysisService.java`、`java-backend/src/test/java/com/aicap/contract/StatusAnalysisStorageTest.java`、`ai-service/README.md`、本交接文档。

验证：Python `python -m unittest discover -s tests -q` 共 55 项通过；Java 定向 StatusAnalysisStorageTest 共 9 项通过且 BUILD SUCCESS（沿用第五轮 Java21 命令）。前者使用本机 HTTP/模型夹具，后者 H2；未进行真实 MySQL、Java23、模型或前端验收。没有新增数据库表或更改审批/故事状态。

本轮到此停止。下一轮建议只实现 **待审故事状态提案的人工审核记录**，支持接受/修改后接受/拒绝，保存原始提案、审核人及审核意见；实际故事执行作为后续独立重点。

## 第七轮接续：逐项人工审核记录（已完成）

Java 新增 POST/GET `/api/meetings/{meetingId}/status-analyses/{analysisId}/proposal-reviews`。admin/owner 可接受、修改后接受、拒绝；登录用户可查询逐项及汇总审核状态。修改只能调整 changes.status，并须提供修改原因。原始提案和审核后载荷分别保存，提交人与审核人不可混淆。

新表 meeting_status_proposal_reviews 按分析 ID + 原始数组位置唯一，避免数据库不区分大小写的排序规则合并不同 proposal_id。事务锁串行化同分析审核；同一审核人同载荷重复请求返回旧记录，其余覆盖返回 409。接受前检查真实故事存在且旧状态一致；过期提案仍可拒绝。审核通过不代表执行，execution_status=not_started，拒绝为 not_applicable。原分析 result_json、快照与 status 不改动，避免破坏 Python 保存响应契约。

新增文件：
- `java-backend/src/main/java/com/aicap/service/StatusProposalReviewService.java`
- `java-backend/src/main/java/com/aicap/controller/StatusProposalReviewController.java`
- `java-backend/src/main/resources/db/status-proposal-reviews.sql`
- `java-backend/src/test/java/com/aicap/contract/StatusProposalReviewTest.java`

更新：`java-backend/src/main/resources/application.yml`（登记审核表脚本）、`java-backend/src/test/java/com/aicap/contract/StatusAnalysisStorageTest.java`（复用隔离环境时加载审核表与服务）、`ai-service/README.md` 和本交接文档。

验证：使用第五轮相同 Java21 Maven 参数，指定 `-Dtest=StatusProposalReviewTest`；17 项通过，0 失败/错误，BUILD SUCCESS。其中 8 项审核测试，9 项继承存储回归，无需再重复运行父测试类。覆盖接受/修改/拒绝、原始数据不变、过期目标、重试与归属、非法字段/类型、权限与资源范围、部分审核、空分析和并发冲突。仍为 H2/MySQL模式 + MockMvc，未验收真实 MySQL、Java23、JWT 或页面；Python 代码未变，本轮未重复运行 Python 套件。

本轮停止。下一轮建议只实现 **已通过审核的状态提案执行**：在事务中重新检查执行权限和故事旧状态，更新故事状态、记录变更日志及执行结果，重复执行不能重复产生副作用。

## 第八轮接续：已批准提案事务执行（已完成）

Java 新增 POST/GET `/api/meetings/{meetingId}/status-analyses/{analysisId}/proposal-executions`。POST 只接受 `{"proposal_id":"p1"}`，当前 admin/owner 才能执行；GET 供登录用户读取成功结果。严格使用已存 approved_json，不接受客户端替换目标、状态或执行人。

执行时按分析、审核、故事顺序加锁，检查批准决定及实时旧状态；更新故事状态、插入 move 日志、保存执行结果和标记审核 succeeded 在同一事务完成。失败整体回滚，未审核/拒绝/旧状态冲突不执行。独立成功表按分析 ID + 提案位置唯一；重试和并发重复返回首次成功记录，即使故事后来再次变化也不重复产生副作用。没有成功记录时，故事已处于目标状态仍算旧状态冲突。原分析数据保持不可变，Python 返回契约不变。

本轮只保存成功执行审计；回滚后仍为 not_started，未新增失败尝试历史、自动执行或页面。成功结果可关联故事日志及原审核记录。接口请求和返回字段详见 ai-service/README.md 第八轮。

新增文件：
- `java-backend/src/main/java/com/aicap/service/StatusProposalExecutionService.java`
- `java-backend/src/main/java/com/aicap/controller/StatusProposalExecutionController.java`
- `java-backend/src/main/resources/db/status-proposal-executions.sql`
- `java-backend/src/test/java/com/aicap/contract/StatusProposalExecutionTest.java`

更新文件：
- `java-backend/src/main/resources/application.yml`：注册独立执行表，避免对已创建审核表进行结构修改。
- `java-backend/src/test/java/com/aicap/contract/StatusAnalysisStorageTest.java`：接入执行服务、控制器、实际执行 DDL 和隔离故事日志表。
- `java-backend/src/main/java/com/aicap/service/StatusProposalReviewService.java`：仅更新类注释中的执行进度描述。
- `ai-service/README.md` 与本交接文档：接口语义、重试及验证边界。

验证命令（java-backend 目录）：

```powershell
$env:JAVA_HOME = 'D:/LHWYAN/download/IntelliJ IDEA 2025.1.2/jbr'
& 'D:/LHWYAN/download/Java/apache-maven-3.9.15/bin/mvn.cmd' -B '-Djava.version=21' '-Dmaven.compiler.source=21' '-Dmaven.compiler.target=21' '-Dtest=StatusProposalExecutionTest' test
```

结果：25 项通过，0 失败/错误，BUILD SUCCESS（8 项新增执行测试 + 17 项继承存储/审核回归）。首次测试编译发现测试使用了错误的 AuthContext 方法名，修正为 currentUser 后通过。验证包含成功记录与日志、人工修改目标、审核门禁、旧状态和删除冲突、成功重试、当前权限/资源范围/伪造字段拒绝、执行审计插入故障使故事及日志全部回滚、并发去重和不同分析争用同一故事。正式建表 SQL 仅在测试移除 MySQL 字符集后缀并重复执行。

仍使用隔离 H2 MySQL 模式与 MockMvc，权限测试注入认证上下文；未完成真实 MySQL 锁/方言、Java23、JWT、页面或跨进程验收。Python 代码未改，未重复执行 Python 测试。

本轮停止。下一轮建议只实现 **会议页面读取持久化分析提案及逐项审核/执行状态**，先让已存原文证据、提案与当前处理状态在页面可见。

## 第九轮接续：会议页面读取状态提案（已完成）

本轮边界为只读展示，沿用既定状态提案、人工审核与执行接口。会议页面新增「故事状态提案」区域，可切换历史分析、刷新处理状态，查看摘要、待确认问题、保存时原文、故事快照标题、原提案/批准后变更、原文证据、审核信息和成功执行审计。

不根据分析记录保存时 status 推断审核进度；查询独立审核/执行接口。保留原始提案和人工修改后目标的区别。加载、未选会议、无分析、无变更和失败状态分别展示；失败支持重试。异步结果用请求版本及卸载失效保护，避免旧会议结果覆盖新选择。Vue 文本插值展示证据，测试覆盖 HTML 字样不会生成脚本元素。所有新增请求为 GET，携带现有登录令牌。

新增文件：
- `frontend/src/api/statusAnalyses.js`：分析列表、审核列表、执行列表的只读请求。
- `frontend/src/components/ai/StatusAnalysisPanel.vue`：状态提案与证据、处理状态展示和加载生命周期。
- `frontend/playwright.status.config.js`：独立模拟接口浏览器测试配置，自动启动本地 Vite。
- `frontend/e2e-status/status-analysis.spec.js`：4 项页面契约测试。

更新文件：
- `frontend/src/components/ai/MeetingPanel.vue`：在已保存原文后接入状态提案组件。
- `ai-service/README.md` 与本交接文档：进度、访问方式、验证及边界。

验证：按现有 lockfile 安装依赖，npm run build 成功；npx playwright test --config=playwright.status.config.js 共 4 项通过。覆盖 viewer 查询及令牌/GET、原始与人工修改目标、证据转义和历史原文、待审/拒绝/无变更、分析切换、列表与详情失败重试、会议切换后旧响应丢弃。首次测试拦截范围过宽误拦截前端 API 模块，限定为后端 8080 URL 后全部通过。git diff --check 通过。

测试运行完整 Vue 页面和真实 Edge 无头浏览器，但 API 为模拟数据；未进行真实 Python/Java/MySQL/JWT 联调，后端代码未改且本轮未重复运行后端测试。安装未更改依赖声明或 lockfile。页面仅展示，旧 Agent 分析入口仍对应旧需求建议流程；尚无新状态分析触发、审核、执行的页面按钮。

本轮停止。下一轮建议只实现 **状态提案的逐项人工审核页面操作**：接受、修改后接受、拒绝，提交审核后刷新状态；故事执行按钮继续作为后续独立重点。

## 第十轮接续：逐项人工审核页面（已完成）

本轮只接入已建立的审核 API。admin/owner 可在待审状态提案卡片选择接受、修改后接受或拒绝；member/viewer 无审核控件，已审核提案无法从页面再次编辑。修改后接受仅可选不同于原目标与旧状态的新状态，并强制填写非空原因。提交整数 changes.status，接受/拒绝不携带 changes；原提案、故事 ID、证据和审核身份不可由表单改写。

提交期间禁用当前表单，成功后读取最新审核/执行状态。失败保留填写内容，提示结果未确认，可查询或手动重试，网络错误不冒充保存成功；后端仍负责幂等和冲突保护。刷新可读回其他审核人已提交的决定。组件卸载后不处理旧响应，避免切换会议/分析后污染新页面。审核不调用执行写接口。

新增：
- `frontend/src/components/ai/StatusProposalReviewForm.vue`：逐项审核表单、验证、提交、错误保留及响应生命周期。

更新：
- `frontend/src/api/statusAnalyses.js`：新增审核 POST 封装。
- `frontend/src/components/ai/StatusAnalysisPanel.vue`：角色和待审条件下嵌入表单，成功后刷新状态。
- `frontend/e2e-status/status-analysis.spec.js`：扩展角色与写请求夹具，新增 8 项审核交互测试。
- `ai-service/README.md` 与本交接文档：进度和验证记录。

验证命令（frontend 目录）：npm.cmd run build；npx.cmd playwright test --config=playwright.status.config.js。

结果：生产构建成功；12 项测试全部通过（4 项展示回归 + 8 项审核）。覆盖 member/viewer 无入口、admin/owner 提交三种决定、只允许合法修改目标、空白原因拦截、请求内容和令牌、提交中禁用、失败保留内容与相同载荷重试、409 后刷新已有决定、切换会议后旧提交响应失效。首次三项断言误匹配页面说明中的“执行成功”，将断言限定于提案卡片后通过。git diff --check 通过。

边界：浏览器使用真实 Edge，接口为模拟响应；未进行真实 Java/MySQL/JWT 或模型联调，后端代码未改，本轮未重复后端测试。审核后的执行按钮和新状态分析触发入口仍未接入。

本轮停止。下一轮建议只实现 **已批准提案的页面执行操作**，调用已有事务执行接口，处理冲突/重试并刷新成功结果。

## 第十一轮接续：已批准提案页面执行（已完成）

本轮只接入既有事务执行接口。admin/owner 可对已批准、未执行且存在 approved_proposal 的提案点击执行；请求仅为 proposal_id，不允许页面更改已批准的状态或执行人。待审、拒绝、已执行和只读角色没有执行入口。

提交中禁用按钮；失败保留重试入口并提示结果未确认，可读取最新状态。成功后重新查询审核/执行详情，显示成功日志和历史变更。当前分析保留 POST 已确认结果，防止后续查询失败或旧响应将其显示为未执行；状态标签与执行按钮也使用已确认记录判断。切换会议/分析使旧组件响应失效。未增加自动执行或自动写请求重试。

新增文件：
- `frontend/src/components/ai/StatusProposalExecute.vue`：执行操作、角色检查、提交禁用、错误与查询入口。

更新文件：
- `frontend/src/api/statusAnalyses.js`：新增仅发送 proposal_id 的执行 POST。
- `frontend/src/components/ai/StatusAnalysisPanel.vue`：条件展示按钮、执行结果刷新、当前分析已确认记录保留。
- `frontend/e2e-status/status-analysis.spec.js`：新增 8 项执行交互测试。
- `ai-service/README.md` 与本交接文档：进度、访问与验证说明。

验证（frontend）：npm.cmd run build 成功；npx.cmd playwright test --config=playwright.status.config.js，20 项全部通过，包含 12 项展示/审核回归和 8 项执行测试。新增覆盖 viewer/member 不可执行、admin/owner 正确发送请求与显示审计、失败后相同提案重试、提交中禁用、冲突查询、执行成功后查询失败仍保留确认、切换会议后旧执行结果不污染页面。git diff --check 通过。

边界：真实 Edge + 模拟 API，未进行真实 Java/MySQL/JWT 或模型联调；后端未改，未重复运行后端测试。刷新范围为会议提案的审核/执行结果，未新增跨页面故事看板缓存同步；Python 状态分析触发仍需通过现有 API。

本轮停止。下一轮建议只接入 **每日站会状态分析触发入口**：调用现有 Python 分析 API，复用 client_request_id 的保存与重试契约，成功后刷新持久化提案列表。

## 第十二轮接续：每日站会状态分析入口（已完成）

本轮接入已确定的 Python 分析 API。admin/owner/member 可针对已保存会议选择可选 Sprint，触发 daily_scrum 分析；viewer 无入口。浏览器只提交既定三字段，不提交原文、故事快照、审核状态或模型配置。

请求编号在发送前存入 sessionStorage，按用户/会议/Sprint 隔离；失败或结果未知保留编号，刷新后选择相同作用域可恢复并手动重试。已确认成功后再次主动分析才生成新编号；Sprint 变化使用不同作用域。存储不可用时不发送，防止丢失恢复依据。标签页关闭后的恢复不保证。POST 成功须有正确的分析 ID、会议 ID、请求 ID 和存储状态；然后刷新分析列表并优先选择该记录。保存确认与读取列表失败分开显示。切换作用域或卸载后不处理旧响应。

Python 未配置 CORS，因此使用前端同源代理而非直接跨域调用。开发和预览下 /meeting-ai 前缀移除后转发至 8090，可通过 Vite 启动环境变量 AICAP_AI_PROXY_TARGET 更改。生产静态部署需另外配置同路径反向代理，普通静态服务器不能替代此配置。沿用现有 api 封装的令牌与 401 处理。

新增：
- `frontend/src/api/dailyAnalysis.js`：同源分析请求与保存确认检查。
- `frontend/src/components/ai/DailyAnalysisForm.vue`：发起分析、Sprint、请求编号持久化/恢复、失败与成功状态。

更新：
- `frontend/src/api/client.js`：允许可选请求 base，默认 Java 地址行为不变。
- `frontend/src/components/ai/StatusAnalysisPanel.vue`：嵌入入口，保存后刷新并选中返回的分析记录。
- `frontend/vite.config.js`：dev/preview 同源 AI 代理。
- `frontend/playwright.status.config.js`：测试代理指向隔离 HTTP 夹具端口。
- `frontend/e2e-status/status-analysis.spec.js`：新增 7 项分析入口和代理验证。
- `ai-service/README.md` 与本交接文档：使用方式、部署代理要求及验证记录。

验证（frontend）：npm.cmd run build 成功；npx.cmd playwright test --config=playwright.status.config.js，27 项全部通过（原有 20 项 + 7 项本轮测试）。覆盖默认 null/Sprint 整数、精确载荷、成功选择返回记录、新意图编号、未知结果刷新后同编号重试、禁用重复提交、坏响应、作用域变化、viewer/未选会议、切换后迟到响应、保存已确认但列表失败、真实 Vite 代理转发路径与 Authorization。首次一项测试误匹配说明文字，将分析保存断言统一限定到结果提示后通过。git diff --check 通过。

边界：浏览器与 Vite 代理真实运行；Python API 由本机 HTTP 夹具模拟，其余 Java 请求也为模拟。Python/Java 代码未改，本轮未重复运行后端测试；没有真实 MySQL、模型或完整 JWT 联调验收，跨页面故事看板缓存同步仍未增加。

本轮停止。下一轮建议只进行 **每日站会端到端真实服务联调验收**，核对分析保存、人工审核、执行后故事状态和日志，按实际结果修复阻断问题。

## 第十三轮接续：真实服务联调与阻断修复（已完成）

本轮边界为完整每日站会流程验收及其阻断修复，不新增会议类型或写能力。实际运行：Edge → Vite → Python FastAPI/LangGraph → Java Spring Boot/JWT → MySQL 8.0.31。模型端为本机 HTTP 固定响应夹具，经过真实 ChatModelClient 解析；不能据此声称真实模型效果验收完成。Java 使用本机可用 21，仓库默认 23 未改。

真实流程验证：
- 浏览器真实登录、保存会议原文、发起分析并读回持久化提案。
- US13 分析后和审核后保持状态 0，执行后变为 1。
- 执行返回审计记录；重复执行完全等于首次结果。同编号分析重试取回原记录，模型调用总数为 1。
- 原始分析、快照不被审核或执行修改；真实 JWT 下匿名执行 401、viewer 执行/分析 403。
- 切换看板显示进行中，无需重载整个页面；直接 MySQL 查询确认分析、审核、执行、move 日志各一条。

修复：
1. frontend/index.html 写死 8080 覆盖运行时与环境配置，移除该强制赋值，由现有 client.js 解析地址。隔离联调使用 VITE_API_BASE=18180，默认仍为 8080。
2. 执行成功后看板仍读旧前端缓存。增加 refreshStoriesAndLogs，读取真实故事及日志并更新共享 store；只对原认证会话应用结果。页面并行刷新处理状态及看板缓存，后者失败独立提示且可只读重试，不否认已执行。

新增文件：
- `qa/run_daily_live.py`：独立 MySQL 初始化与进程启动、模型 HTTP 夹具、浏览器测试、数据库核对、正常退出清理。
- `qa/DAILY_LIVE.md`：运行方式、专用端口、隔离范围与验证边界。
- `frontend/playwright.live.config.js`：真实服务浏览器配置。
- `frontend/e2e-live/daily-flow.spec.js`：不拦截 API 的完整流程、JWT、重试、历史记录及看板验证。

更新文件：
- `frontend/index.html`：取消固定 Java 地址覆盖。
- `frontend/src/stores/project.js`：故事与日志缓存刷新动作及会话归属检查。
- `frontend/src/components/ai/StatusAnalysisPanel.vue`：执行成功后的共享缓存刷新与独立失败重试。
- `frontend/e2e-status/status-analysis.spec.js`：增加刷新失败不改变执行结果的回归。
- `.gitignore`：忽略 qa/.daily-live 的数据库和日志。
- `ai-service/README.md`、本交接文档。

最终验证：Java package BUILD SUCCESS；npm run build 成功；playwright.status.config.js 28 项通过；qa/run_daily_live.py 的真实服务浏览器 1 项通过，后续直接 SQL 核对通过。报告：`qa/.daily-live/6003beea2dcf42ef8a3ab3381af5064b/result.json`，database=8.0.31，model_calls=1，故事状态及四项计数均为 1。git diff --check 通过。

联调过程中修正了测试模型缺少 finish_reason/role、Windows 日志编码、重复卡片选择器和进程退出检查等夹具问题；这些不通过放宽业务验证处理。所有实例均使用新建 qa/.daily-live 随机目录，未操作既有 MySQL 服务的数据，未运行旧清库脚本。日志和测试数据库保留供复核，临时端口随运行结束释放。

本轮停止。下一轮建议只开展 **真实模型的每日站会提案质量验收**，针对否定、未验收、阻塞、歧义和可靠证据核对提案，不扩展其他会议类型。

## 第十四轮接续：真实模型提案质量验收（已完成，质量未通过）

本轮仅增加可复现的每日站会质量验收，不修改既定架构、生产 Skill 或状态执行规则。使用项目既有模型配置 deepseek-v4-flash，通过真实模型适配器发送合成会议文本和固定故事快照；不读取真实会议、不写业务库，密钥不输出或写报告。

10 个场景各两次：完成正例、开始正例、否定、未验收、阻塞、将来条件、故事歧义、矛盾证据、混合故事、原文指令注入。自动验证精确状态提案集合、结构和真实引用、支持片段以及必要待确认问题，正例可阻止“一律不提案”虚假通过。自然语言摘要另作人工复核。

结果：完整 20/20 自动检查通过；人工复核 4/20 存在明确无依据扩写，3 次补“昨日”时间，1 次将修复权限错误称为阻塞。首次探测的第 7 个响应曾格式无效；工具改为将格式错误计单样本失败继续评测，保留首次报告，不以重跑掩盖异常。整体质量验收未通过，详见 ai-service/evals/QUALITY_REVIEW.md。

新增文件：
- `ai-service/meeting_agent/evaluation.py`：只读真实模型验收运行器，固定合成输入、分样本断言、重复运行、进度报告和错误分类。
- `ai-service/evals/daily_quality.json`：10 项固定验收场景及人工复核标准。
- `ai-service/evals/QUALITY_REVIEW.md`：实际结果、失败证据、复现方法和限制。
- `ai-service/tests/test_evaluation.py`：6 项验收工具测试。

更新：`.gitignore` 忽略 qa/.daily-quality 运行报告，`ai-service/README.md` 和本交接文档记录结论。

验证：ai-service 内 python -m unittest discover -s tests -q，61 项通过。真实模型命令 python -m meeting_agent.evaluation --env-file ../backend/.env --output ../qa/.daily-quality/acceptance.json --repeat 2 完成 20 次，自动退出码0仅表示结构/动作断言通过，非人工质量放行。首次探测报告 baseline.json 同目录留存。git diff --check 通过。本轮前端/Java 未修改，不重复跑无关测试。

本轮停止。下一轮建议只修正 **摘要不得补造时间或阻塞事实** 的 Skill 约束，再用相同验收样本复测；保留人工审核边界。

## 第十五轮接续：收紧摘要事实约束（已完成，质量仍未通过）

本轮只更新 DailyScrumSkill，自 daily-status-v1 经过 v2 收敛到 v3：禁止补造昨日时间，区分缺陷修复、依赖、未验收与阻塞；摘要、reason、open_questions 都必须依据原文/快照，不得在问题中预设未知阻塞，也不得将缺乏证据写成确定不存在。既定架构、状态提案契约、人工审核及执行均未更改。

使用上一轮固定 10 场景与同一真实模型，每场景两次。v2 的 20 次自动检查通过，人工发现条件依赖问句仍预设阻塞；补充规则后 v3 再次 20/20 自动通过。人工逐条复核最终 20 次摘要、原因与问题：未发现补造昨日，仍有 mixed-targets 第二次摘要“原文未说明是否存在其他阻塞”，其中“其他”暗含原文未确认的阻塞。因此整体质量验收仍未通过，保留失败证据，不扩大开发范围。

本轮修改文件：
- ai-service/meeting_agent/daily_skill.py：事实规则及版本。
- ai-service/evals/QUALITY_REVIEW.md：v1/v2/v3 对比、残留问题和复现记录。
- ai-service/README.md 与本交接文档：本轮结论和下一步。

验证：Python 61 项回归通过；最终真实模型 20/20 自动检查通过。原始报告 qa/.daily-quality/acceptance-v2.json、acceptance-v3.json 已保留且被 Git 忽略，仅含合成数据。没有改动验收集或降低断言；本轮前端/Java 未改，不重复无关测试。

本轮停止。下一轮只处理摘要中隐含的阻塞断言，并复测同一验收集；不扩展其他会议类型。

## 第十六轮接续：修正摘要中的隐含阻塞断言（已完成，质量仍未通过）

本轮仅改 daily_skill.py 为 daily-status-v4：阻塞预设约束适用于所有输出句式，摘要省略未涉及的阻塞话题，并用普通错误修复与明确环境阻塞的对照例说明边界。既定方案、提案契约、人工审核与执行不变。

验证：61 项 Python 测试通过；同一真实模型、同一 10 场景各两次，19/20 自动通过。not-accepted 第一次契约失败（invalid_model_proposal），报告未保存具体失败字段，无法归因。其余 19 份结果逐条人工复核，未发现补造昨日或隐含已有阻塞，明确阻塞两次均保留。not-accepted 第二次仍追加无预设的“未说明是否受阻”句，不完全遵循摘要省略规则。整体质量验收仍未通过，失败未被重跑覆盖。

改动文件：ai-service/meeting_agent/daily_skill.py、ai-service/evals/QUALITY_REVIEW.md、ai-service/README.md、本交接文档。原始报告 qa/.daily-quality/acceptance-v4.json 被 Git 忽略并保留。未修改前端、Java、数据集和自动断言。

下一步只完善验收器的脱敏契约错误诊断，定位未验收场景失败。不扩大功能范围或放宽契约。

用户要求的本次完成度估算：按本文开头既定路线和已验证代码覆盖范围，完整会议 Agent 方案约 25%–35%（约 30%）；每日站会“文本→状态提案→人工审核→执行→审计”这一窄闭环约 85%–90%。这是功能覆盖的主观区间，不是剩余工时预测，也不代表生产验收通过。通用基础和状态变更闭环已经实现并做过真实服务联调；Daily 的独立阻塞/风险/行动项等写能力、Planning、Review/Retro/Refinement、Assignment Engine、新流程录音转写与说话人区分、MeetingQualitySkill、实时增强仍未按方案完成。旧录音等能力不能直接计为新 Agent 集成完成。当前仍需解决模型契约稳定性、扩大语义验证及部署验收。本估算仅按用户本次要求提供，不设为以后每轮的固定输出。

## 第十七轮接续：验收契约失败诊断（已完成）

本轮仅完善失败可诊断性。contracts.py 新增 ValueError 子类携带静态错误码与位置，既有校验条件和错误消息保持；evaluation.py 将结构错误及跨字段错误写入 contract_errors，过滤输入值、上下文、异常消息和未知字段名。保持 invalid_model_proposal 分类、失败退出码和继续剩余样本的行为，不记录失败响应原文，不修改生产提示词或放宽契约。

新增 3 项测试验证脱敏、状态不变/快照/目标/引用错误定位，以及报告落盘、失败计数和后续样本继续。全部 Python 测试 64 项通过。真实模型同一 10 场景各两次复测 19/20，mixed-targets 第二次明确缺少 meeting_id（missing，path=[meeting_id]）。未验收场景两次通过；上一轮失败缺少详情，不能倒推历史原因。19 份有效结果人工复核未发现此前目标事实错误，但仍有冗余说明及问题栏目陈述句，整体质量验收仍未通过。

文件：ai-service/meeting_agent/contracts.py、ai-service/meeting_agent/evaluation.py、ai-service/tests/test_evaluation.py、ai-service/evals/QUALITY_REVIEW.md、ai-service/README.md、本交接文档。报告 qa/.daily-quality/diagnostics-v4.json 已忽略并保留；git diff --check 通过。前端与 Java 未改，不重复无关测试。

本轮停止。下一步只处理模型偶发漏填必需 meeting_id 的问题并复测，不以自动补值或放宽契约掩盖失败。

## 第十八轮接续：必需会议编号完整性（已完成，本次固定样本通过）

本轮仅处理模型偶发漏填 meeting_id。daily_skill.py 更新 daily-status-v5，输出前检查四个必填字段，会议编号逐字复制输入顶层值，无提案时也不能省略。没有修改既定架构、输出契约、模型适配器或校验规则，没有自动补值或增加重试。

test_workflow.py 新增工作流测试，覆盖模型缺少或填错 meeting_id 时拒绝结果，且每次只读取一次故事、调用一次模型。全部 Python 测试 65 项通过。真实模型同一固定 10 场景各两次，20/20 自动通过；20 份结果均带正确会议编号，逐条人工复核未发现此前时间补造、隐含阻塞或错误状态提案。本次固定短合成样本验收通过，不能推断长会议或真实噪声下的可靠性，旧失败记录保留。

文件：ai-service/meeting_agent/daily_skill.py、ai-service/tests/test_workflow.py、ai-service/evals/QUALITY_REVIEW.md、ai-service/README.md、本交接文档。报告 qa/.daily-quality/acceptance-v5.json 被 Git 忽略并留存。git diff --check 通过；前端/Java 未改，不重复无关测试。

本轮停止。下一步只扩充多段长会议及转写噪声的独立合成验收样本，保留当前固定集，检查现有 Skill 泛化表现。

## 第十九轮接续：独立长文本及转写噪声验收（已完成，扩展验收未通过）

本轮只新增验收覆盖，不修改生产 Skill 或既定方案。evals/daily_extended.json 新增 6 个纯合成场景：1,853 字符/30 行/5 故事长文本、末尾冲突、口吃截断后澄清、模糊故事编号及任务编号、重复转写、字幕中的伪造会议编号与批准指令。原 daily_quality.json 保留。样本含显式澄清，不代表真实录音或 16,000 字符上限覆盖。

新增 2 项数据质量测试，核对合法上下文、预期动作与快照、支持片段、空行编号、口吃文本及误猜编号/一律弃权不能通过。全部 Python 测试 67 项通过。

真实模型 daily-status-v5 对新 6 场景各两次，11/12 自动通过。noisy-correction 第一次摘要明确 US14 已开始，却漏掉待办→进行中提案；第二次动作正确，但把“已经完……”截断内容补写为“已经完成”。人工复核全部 12 份结果确认这两个问题；其余场景本次通过，整体扩展质量验收未通过。失败保留，不降低标准或重跑覆盖。本轮未改提示词。

改动文件：ai-service/evals/daily_extended.json、ai-service/tests/test_evaluation.py、ai-service/evals/QUALITY_REVIEW.md、ai-service/README.md、本交接文档。报告 qa/.daily-quality/extended-v5.json 被 Git 忽略并留存。git diff --check 通过；前端/Java 未改，原固定集真实模型不重复运行。

本轮停止。下一步只处理噪声澄清场景中的截断补写及已开始开发漏提案，区分未验收不能完成与明确开始可进行中，再复测原集和扩展集。

## 第二十轮接续：噪声澄清候选验证（候选未采用，问题未解决）

本轮尝试 v6 提示词限定不补全截断发言，逐个故事区分未验收与明确开始；未改契约、原集或扩展集。67 项 Python 回归通过，真实模型原集 19/20、扩展集 10/12。

明确失败：mixed-targets 第二次状态不变被契约拒绝；long-multi-story 第一次缺 open_questions；noisy-correction 第二次正确提 US14 开始，但同时错误提 US13 已完成，其 reason 明说不能提出已完成却输出 changes.status=2。后者通过结构与引用校验，被精确动作预期检查判失败，没有业务执行。完整人工复核及候选规则存于 ai-service/evals/QUALITY_REVIEW.md。

候选未采用，daily_skill.py 已恢复本轮前 daily-status-v5，因此本轮最终仅更新质量记录、ai-service/README.md 和本交接文档；两个候选报告 qa/.daily-quality/acceptance-v6.json、extended-v6.json 被 Git 忽略并留存。v5 的历史噪声问题仍未解决，不宣称修复完成。

本轮停止。下一步优先处理提案理由与实际状态动作不一致，继续使用现有反例验收；不放宽契约或自动修改模型动作。

## 第二十一轮接续：动作一致性、噪声与对照验收（已完成，保留 v7）

用户允许本轮多完成相关内容。基于 v5 修改为 daily-status-v7，强调逐故事先判断再输出真正建议执行的动作，理由必须支持实际数值状态；排除否决动作、原状态占位；保留截断与口吃证据、不因未验收遗漏明确开始。本轮不修改既定架构、生产校验或人工审核流程。

同时完善验收器 action_difference，列出精确漏提/多提三元组；新增 daily_consistency.json 的 4 个对照场景，包含另一套编号/措辞、保持进行中、将来计划和旧缺陷明确修复后的完成。新增 3 项测试覆盖历史矛盾动作、差异报告落盘和新数据一致性。

验证：Python 70 项通过；原集 20/20、扩展集 12/12、新对照集 8/8，真实模型共 40 次自动通过。人工复核全部结果，未重现理由否定实际动作、截断补写和开始漏提案，必填字段完整、没有无变化提案。保留 v7，不自动重试或改写模型结果。

限制：原集 clear-start 一次 reason 擅加未验收；长文本一次把向提供方确认时间写成提供方也待确认，并把尚需协商验收安排写成尚未安排验收。整体语义质量未全面通过，详见 QUALITY_REVIEW.md。少量问题栏目冗余暂不扩大处理。

文件：ai-service/meeting_agent/daily_skill.py、ai-service/meeting_agent/evaluation.py、ai-service/evals/daily_consistency.json、ai-service/tests/test_evaluation.py、ai-service/evals/QUALITY_REVIEW.md、ai-service/README.md、本交接文档。报告 acceptance-v7.json、extended-v7.json、consistency-v7.json 在 qa/.daily-quality 留存且被 Git 忽略。git diff --check 通过；Java/前端未改，不重复无关验证。

本轮停止。下一步处理理由与长摘要中“未提及”被写成已确认事实的问题，沿用三组验收，不扩展其他会议类型。

## 第二十二轮接续：信息缺失与确认范围（已完成，保留 v8）

本轮与助手并行，主 AI 只处理 v7 遗留事实扩写。daily_skill.py 更新 daily-status-v8，禁止将未提及验收写成尚未验收、尚需协商写成尚无安排，保留向提供方确认时间的准确范围。契约、执行流程和三组验收数据未改；未读写助手 qa/assistant-work 交付目录。

验证：70 项 Python 测试通过；原集 20/20、扩展集 12/12、对照集 8/8，真实模型共 40/40 自动通过。人工逐条复核目标问题未重现，动作与理由一致性和噪声判断未观察到回退。保留 v8；仍有 repeated-evidence 第二次把“网络重传”写成“网络中继”的摘要术语失真，已记录，不宣称自然语言质量全面通过。

修改文件：ai-service/meeting_agent/daily_skill.py、ai-service/evals/QUALITY_REVIEW.md、ai-service/README.md、本交接文档。三份 v8 报告在 qa/.daily-quality 被 Git 忽略并留存。git diff --check 通过。

本轮停止。下一步收到助手交付后，先核对其 v7 基线发现是否仍适用于 v8，合并未解决问题，再决定下一项，避免双方重复操作。

## 助手 H01 交付接收（2026-09-22）

已接收并核实 H01 的 v7 独立复核，六份数据/报告哈希一致。9 项发现对照 v8，8 项本次未重现，1 项提问冗余部分保留；5 项待判断项已有主 AI 处置，其中 D003 因实际 current_sprint=2 而非 null，不采纳为缺陷。助手冻结交付保持原样，当前生产继续 v8。

主 AI 接收结论及可转交的 H02 指令见 docs/会议Agent_助手H01接收与H02安排.md。下一包为 Planning 数据/API 缺口盘点，只在助手独占目录交付，尚未通过工具启动或通知助手。本轮仅整合验收与派工文档，无生产代码修改和模型重跑。

## 第二十三轮接续：Planning 前置成员画像只读工具（已完成）

为推进既定 Planning 阶段，本轮完成 get_member_profiles 的 Python 只读实现，不再围绕低优先级摘要措辞反复修改。助手 STATUS 仍为 H01 ready、H02尚未开始；本轮只做具体工具实现，未重复其完整数据/API盘点，也未修改助手交付目录。

依据同步后 MemberProfileController、MemberProfileDtos、MemberProfileService、User.java 和 schema.sql，新增 MemberReadTool 固定读取 GET /api/members/profiles，使用调用人令牌并由Java鉴权。投影成员ID、展示名、角色、技能三维、简介、经验和显式 six_week_capacity_hours，不含账号、颜色或其它无关字段；容量不解释为 Sprint 剩余工时，不过滤角色暗示可分配资格。

严格校验响应结构、字段类型、规模与重复数据，缺字段/坏行整批失败；空维度、空成员列表、零容量保持原样。禁止重定向和环境代理传递令牌，错误只返回固定代码。没有缓存、数据库访问、写动作、额外模型请求或Planning/Daily工作流接入。Java既有默认值仍可能出现在返回中，不能从空维度或0经验推断成员真实能力缺失。

文件：新增 ai-service/meeting_agent/member_tool.py、ai-service/tests/test_member_tool.py；更新 ai-service/README.md、本交接文档。生产Daily仍v8，前端/Java及既有契约未改。

验证：新增10项HTTP transport夹具测试，Python全套80项通过；git diff --check通过。未运行实际Java/MySQL联调或模型验收，下一阶段需接入后验证实际读链路。

本轮停止。下一步建议实现 Planning 输入上下文契约并复用既有故事/成员读取，显式表达当前Sprint与缺失数据；H02交付后核对其字段与缺口，不能自行把六周容量换算成Sprint带宽。

## 第二十四轮接续：Planning输入上下文与临时协作目录（已完成）

读取助手状态发现H02已ready；采纳其故事/成员可复用、Sprint权威数据/估时/剩余容量缺失及任务数据尚未接入等结论。核对handoff记录的40个来源SHA-256全部一致。原冻结交付保留，未移改助手STATUS或文件。

新增 ai-service/meeting_agent/planning_context.py：SprintPlanningInput + PlanningContextReader，聚合授权原文、故事、成员与显式current_sprint/target_sprint。两个Sprint字段可为null，不由日期/故事推断。任务、Sprint Goal/日期、故事估时、Sprint剩余容量暂只允许null，缺数据不能伪装空列表/0。严格去重和规模校验，输入坏值先拒绝；读取失败不返回部分结果，嵌套快照重新校验。两个Java读取非原子，不替代将来受审执行的并发检查。

新增 tests/test_planning_context.py 的8项测试，覆盖未知值、范围、分段、提前拒绝、读取错误、重复/被修改对象、无缓存/缺失owner、禁止猜测字段及JSON往返；序列化坏数据不打印输入值。Python88项全部通过。未接入模型、HTTP入口、Planning输出或写动作，Daily仍v8，测试用模拟Tool，未做真实服务联调。

协作改进：新增本地临时 `.agent-collab/` 入口、CURRENT、tasks/work说明，加入.gitignore。此目录由主/助手分区写入，新任务统一使用；H01/H02继续保留原位置。清理仅限已接收落档、closed、无活动引用的临时内容，本轮没有删除历史证据。长期决定仍在本交接文档，临时目录不提交仓库。

本轮文件：新增planning_context.py、test_planning_context.py及临时协作说明；更新.gitignore、ai-service/README.md、助手任务书入口说明与本交接文档。git diff --check通过。

下一步：接入只读TaskReadTool并把Planning的tasks从不可用扩展为真实任务快照，保留Task/Story的不同Sprint表达及工时原义，不提前推算Sprint剩余容量或实现分配写入。

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

## 第三十六轮：Planning真实服务闭环验收（2026-09-28）

在一次性MySQL 8.0.31中启动真实Java/Python/Vite/Edge，HTTP模型夹具只替代模型供应商。Planning浏览器1/1通过，验证分析保存、人工从目标Sprint3修改到4并批准、独立执行、重复请求、401/403、不可变分析、日志ID与数据库一致、刷新审计与故事地图；只改变Story.sprint，其他故事字段与Task列表不变。Daily另用新库回归1/1通过。两套均模型调用一次，测试服务已停止。详细命令与证据路径见qa/DAILY_LIVE.md。

## 第三十七轮：Review分析核心（2026-09-28）

依据既定Review顺序和H05溯源，本轮完成一个只读分析切片，不重设计已定架构。H05原交付冻结，SR-06及整体Sprint达标口径不作为已定要求。主AI确定本切片复用update_story_status且仅提出标记完成(status=2)，不新增独立验收实体；后续扩展不受本切片范围冒充既定完整方案。

新增review_contracts.py、review_skill.py、review_workflow.py，读取故事描述/验收标准并执行LangGraph上下文→模型→校验。meeting_type固定sprint_review，输出摘要、待确认问题、待审完成提案。current_sprint未知不猜；acceptance_results/sprint_goal固定null。Skill review-completion-v1明确展示≠验收、保留否定/条件/局部范围/冲突，不将标准或Story.status当作验收发生的事实。

contracts.py抽取共用状态溯源校验，Daily入口仍先按原契约验证；story_tool.py增加Review只读投影。严格验证整数状态、原始引用、目标/旧值、重复、空操作及额外字段，令牌不进入图状态或模型。纯引用匹配不能证明语义上确实通过验收，测试明确保留这个限制，不用关键词规则假装解决语义问题。

新增test_review_workflow.py 11项及test_story_tool.py 1项，Python全量147项通过，git diff --check通过。使用真实LangGraph/模型适配器和本地HTTP夹具，尚未验证真实模型Review语义质量。本轮未新增HTTP、保存、人工审核执行或页面，不能把Review结果发到Daily持久化入口。

下一轮优先接通Review持久化/查询及带鉴权分析入口，复用既有幂等、原文快照与错误恢复规则；随后接审核执行和页面，形成用户可用闭环。H05无需新增派工。

## 第三十八轮：Review持久化查询与鉴权分析入口（2026-09-28）

完成Python POST /api/meetings/{meeting_id}/review/analyze，先经Java角色和会议访问校验，再查询当前调用者的已存请求；未命中才运行Review工作流。新增review_store.py，复用有界HTTP、严格响应验证和原载荷至多一次保存重试；未知结果保留client_request_id，已有结果恢复不重调模型。

Java新增ReviewAnalysisDtos、ReviewAnalysisService、ReviewAnalysisController和review-analyses.sql，注册application.yml。review-analyses资源支持提交、列表、详情和按当前调用者请求号查询。独立meeting_review_analyses保存原文、候选提案和数据库目标故事基础快照，不混用Daily记录。严格限制sprint_review/update_story_status且changes.status=2；再次检查原文引用、目标/旧状态、重复和无变更。会议锁与数据库唯一键实现并发幂等；同编号不同载荷409；保存不改变故事或写故事日志。

测试：新增test_review_api.py 8项，test_api.py共用HTTP夹具支持Review路径；Python全量155项通过。新增ReviewAnalysisStorageTest 10项，连同Daily/Planning存储回归共28项通过，Java21编译BUILD SUCCESS。使用HTTP夹具及隔离H2/MySQL模式事务，不是Review真实MySQL/前端或真实模型验收。git diff --check通过。

下一步集中实现Review逐项人工审核及批准后执行，继续保留权限、批准载荷绑定、执行前旧值复查、事务审计及幂等；再接页面与真实服务联调。本轮不开放未经审核的故事写入，也未运行业务数据库迁移。

## 第三十九轮：Review人工审核与批准后执行（2026-09-28）

新增ReviewProposalReviewService/Controller与ReviewProposalExecutionService/Controller，路径为review-analyses/{analysisId}/proposal-reviews和proposal-executions，支持GET/POST。admin/owner可审核执行，登录用户可读；按会议及分析记录绑定提案，禁止客户端提交执行载荷或审核人。

支持approve、reject、modify_and_approve。本Review切片目标固定完成状态2，修改后接受仅修订changes.reason，并要求顶层reason说明修改原因；不改证据、故事、旧状态或完成目标。不正确的目标/验收结论应拒绝后重新分析。该选择是本切片实现范围，不将其当作整个既定方案的通用修改限制。

原始提案与批准载荷分开保存，最终审核不可覆盖，同载荷同审核人可重试；批准检查故事旧状态，审核不写故事。独立执行只读持久批准载荷，再次锁定复查旧状态，原子更新Story.status、move日志、执行记录和审核执行状态。重复执行返回首次结果；任一步失败回滚，不影响Story.sprint或Task。

新增review-proposal-reviews.sql、review-proposal-executions.sql并注册application.yml。扩展ReviewAnalysisStorageTest加载新服务/DDL；新增ReviewProposalFlowTest 7项，含继承存储共17项。连同Planning 14项、Daily 25项，共56项通过，Java21 BUILD SUCCESS；git diff --check通过。验证修改理由、不可变原记录、权限、待审/拒绝拦截、错误载荷、状态冲突、并发幂等、审计故障回滚和查询结果。测试是隔离H2/MySQL模式+MockMvc，没有Review生产库迁移或真实服务联调。

下一轮集中接Review前端分析、审核（含修改理由）、执行和审计展示，复用已验证交互与请求恢复；之后完成真实服务联调。暂不扩大Review动作范围或进入Retro。

## 第四十轮：Review前端闭环（2026-09-28）

MeetingPanel增加“打开会议 Sprint Review”懒加载入口。新增ReviewAnalysisForm/Panel、ReviewProposalReviewForm/Execute及reviewAnalysis/reviewAnalyses API，调用独立Review分析/保存/审核执行接口。

请求号按用户、会议和可选当前Sprint隔离，发送前写sessionStorage；未知结果和页面刷新后重试沿用编号。展示原文、摘要、待确认、证据、原提案及批准后理由；人工修改只修订理由且要求修改说明，不允许原样提交修改。拒绝后无执行入口；成员可分析但不能审核执行，只读用户仅查看。

执行只发送proposal_id；确认检查归属、前后状态、目标完成2及审计字段；成功后缓存已确认结果并刷新故事日志。保留选择变化/卸载失效处理，不让旧异步结果覆盖当前面板。未改Daily/Planning交互。

新增review-flow.spec.js六例：修改理由审核执行后刷新审计、残缺响应、成员权限、无效修改/拒绝、只读权限、未知分析刷新后原编号重试。生产构建通过；完整浏览器回归结果见续记。此轮为Vite/Edge+mock业务API，尚未Review真实Java/Python/MySQL联调。下一步完成真实服务联调，再据结果推进后续既定阶段。

最终验证：完整浏览器47/47通过（含新增Review六例），npm run build通过，git diff --check通过。本轮未修改后端或调用真实模型。

## 第四十一轮：Review真实服务闭环验证（2026-09-28）

新增frontend/e2e-live/review-flow.spec.js，qa/run_daily_live.py增加--suite review，复用一次性MySQL目录和专用端口。重新打包Java，运行真实Edge/Vite/Python/Java/JWT/MySQL 8.0.31，只有模型响应使用本地HTTP夹具。

浏览器1/1通过：保存合成会议→Review分析保存→人工修订理由并批准→单独执行→刷新审计/看板。种子US13为待办0，明确会议验收陈述经人工确认后同步为完成2。分析和审核后故事完整记录不变，执行后只改status；Sprint仍2、任务列表不变。重复执行返回首次结果，分析重试返回原分析且模型调用仅1次，原始记录不变；匿名执行401、只读执行/分析403。数据库分析、审核、执行和move日志各1条，日志ID匹配执行审计。

证据目录qa/.daily-live/e3edc5ca620f40f79a87de6173e17c50/（忽略），result.json记录counts=2/2/1/1/1/1；命令与解释见qa/DAILY_LIVE.md。Java打包BUILD SUCCESS，git diff --check通过。不修改日常业务库，无真实模型语义质量结论。

下一步使用H05已有案例开展Review真实模型的有界验收，重点区分展示/验收、部分范围、否定/条件及冲突；保留失败，避免反复提示词打磨。随后按既定顺序推进Retro/Refinement。

## 第四十二轮：Review首轮真实模型验收（2026-09-28）

新增review_evaluation.py、evals/review_quality.json、tests/test_review_evaluation.py，复用既有评测结构，检查契约/动作/证据及必要问题；拒绝覆盖原输出，供应商故障停止。H05原文件未改。转录和故事快照保持，缺描述/验收标准显式null；修正SR-01“准备演示≠已演示”、SR-07未明确目标不得绑定单个快照的候选预期，保留SR-06建议标记。

Python158项通过。使用既有.env配置对8个合成案例各调用一次deepseek-v4-flash；动作/契约8/8通过，唯一完成候选为US07 1→2，其余空提案。但人工复核未通过：SR-06把状态1解释成未验收；SR-04追问将争议中的阻塞视为确定前提；SR-08追问无依据假设，另有低影响冗余。Skill保持review-completion-v1，本轮无提示词调整或重跑，无业务写入。

原报告.review-quality/review-v1-20260928.json保持忽略，SHA256与逐例结论见evals/REVIEW_QUALITY_REVIEW.md。新增评测文件、说明与忽略规则；git diff --check通过。不能把8/8自动检查等同整体语义验收通过。

下一步优先纠正状态含义与冲突前提，补正反对照并定向验证一次，不反复优化低影响措辞；随后继续既定Retro/Refinement顺序。

## 第四十三轮：Review语义定向修正（2026-09-28）

review_skill.py升级review-completion-v2：状态编码0待办/1进行中/2已完成不等于验收事实；争议来源必须在摘要/理由/问题中保留，已明确澄清则采纳最终结论；未来条件不改成已经完成的追问，不臆测已通过但快照未更新。

新增evals/review_semantics.json四个正反案例，扩展test_review_evaluation.py验证Oracle及澄清证据；Python159项通过。原SR-04/06/08与新四例共7次真实deepseek-v4-flash定向复验，动作/契约7/7，人工逐条复核中目标缺陷未重现。澄清通过正例正常提出US13 1→2，避免修复退化成一律不提案。

报告.review-quality/review-v2-target-20260928.json和组合案例文件保留，旧8例及v1报告未覆盖；哈希和人工结论见evals/REVIEW_QUALITY_REVIEW.md。仍有状态编码复述、无关缺失信息、重复问题及“双方确认”的轻微概括，列为低影响待办不继续本轮打磨。未做原8例全套或重复稳定性验证，不宣称Review全部质量通过。无业务写入，git diff --check通过。

本轮到此，下一步按既定顺序推进Retro最小切片，继续保留证据、人工审核与执行分离。

## 第四十四轮：Retro行动项分析核心（2026-09-28）

本轮依据本地既定路线与US30事项/负责人/截止时间不得补造的要求，完成只读行动项候选切片。尝试重读原共享链接失败，未将无法读取的内容当作新设计依据；沿用本地已落地的Skill/Tool/LangGraph和create_action_item方向。

新增retro_contracts.py、retro_skill.py、retro_workflow.py。SprintRetroInput含原文分段、可选current_sprint和成员ID/姓名投影；读取既有成员资料但不向模型发送能力画像/容量。输出摘要、明确决议（带证据）、待审create_action_item及待确认问题。changes包含title、description、owner_id和deadline_text；未知显式null，截止时间保留原文而不擅自换算日期。此为当前切片字段，不宣称既定方案已指定相同底层字段命名。

retro-action-v1要求区分明确决定、建议、抱怨、条件和否定，不把讨论变成已批准行动或个人能力判断。同名/外部/无可靠映射的“我”不得自动指定成员；不写Story/Task/画像。契约检查精确引用、唯一性、成员存在、唯一姓名在引用中出现、截止短语来自引用和精确重复行动；不假装这些检查能证明语义上的责任指派、截止时间或采纳事实。

新增test_retro_workflow.py八项，Python全量167项通过；真实LangGraph+只读/模型夹具，无真实模型或业务写入。git diff --check通过。当前无Retro HTTP、持久化、业务ActionItem实体、审核执行或页面。

下一步集中接Retro候选持久化查询及带鉴权分析入口，再补独立ActionItem实体和审核执行，按原人工审核路线闭环。本轮到此停止。

## 第四十五轮：Retro候选持久化与鉴权入口（2026-09-28）

新增retro_store.py和Python POST /api/meetings/{meeting_id}/retro/analyze，验证调用角色/会议后按当前用户请求号恢复，未命中才读成员目录并运行模型。响应确认保存ID、请求号及pending/no_changes；共享存储传输保持原载荷重试与未知结果恢复。

新增Java RetroAnalysisDtos/Service/Controller及retro-analyses.sql，注册application.yml。retro-analyses资源支持保存、列表、详情、当前调用者请求查询；meeting_retro_analyses保存不可变原文、决议、行动候选及涉及负责人的姓名快照。保存不创建ActionItem/Task/Story，不写故事日志。当前仍只有候选，无业务行动项实体。

Java独立检查决议与行动引用、唯一编号、精确重复内容、原文截止短语、负责人存在及唯一姓名引用。owner_id/deadline_text必须显式填写值或null。指定负责人时稳定顺序锁用户目录，记录member_snapshots；同编号恢复不因后续改名而改写记录。会议锁+唯一约束支持并发幂等，异载荷409，非法批次不保存。证据检查仍不能证明语义上的真实指派或采纳。

新增test_retro_api.py六项、RetroAnalysisStorageTest八项，扩展共用HTTP夹具。Python173项通过；Java Retro8+Review17+Planning14+Daily25=64项通过，Java21 BUILD SUCCESS。使用本地HTTP夹具及隔离H2真实事务；未做Retro真实MySQL/模型/浏览器联调。git diff --check通过。

下一步集中实现业务ActionItem与Retro人工审核/批准后执行：批准载荷独立保存、创建和审计同一事务、重复执行返回首次结果。之后接前端和真实服务验证。本轮停止。

## 第四十六轮：Retro人工审核与业务行动项执行（2026-09-28）

新增RetroProposalReviewService/Controller、RetroProposalExecutionService/Controller，分别提供retro-analyses/{analysisId}/proposal-reviews和proposal-executions的GET/POST。admin/owner审核执行，登录用户可读。支持接受、拒绝、修改后接受；修改提交完整事项/描述/负责人/截止文本及修改说明，原提案和人工批准载荷分开保存。负责人可由人工重新指定，截止文本可人工修订；不能把人工修订当作原文证据。直接接受复查原姓名快照，执行复查批准时姓名；已审核决定不可覆盖，同审核人同载荷可重试。

新增action-items.sql保存业务行动项及创建审计，retro-proposal-reviews.sql、retro-proposal-executions.sql保存审批和执行记录，注册application.yml。执行只接受proposal_id，读取持久批准载荷，在同一事务内创建初始open行动项、创建日志、执行记录并更新审核执行状态；并发重复执行返回首次结果。负责人/截止时间允许显式null。不写Story/Task/画像。ActionItemController提供按会议查询行动项及其审计；尚无行动项后续编辑/完成流转。

扩展RetroAnalysisStorageTest初始化新表/服务，新增RetroProposalFlowTest八项，含继承存储共16项。初次验证发现JDBC自动生成键包含时间戳，已改为明确请求日志id。最终Retro16+Review17+Planning14+Daily25=72项通过，Java21 BUILD SUCCESS；覆盖批准/修改/拒绝、原记录不可变、权限、负责人变化、错误载荷、并发幂等、故障回滚及查询归属。测试使用隔离H2真实事务和MockMvc，未运行日常业务库迁移、Retro真实MySQL或模型验收。git diff --check通过。

下一步集中接Retro前端：分析保存恢复、决议/证据展示、逐项人工审核、批准后执行和行动项审计，再进行真实服务联调。本轮停止。

## 第四十七轮：Retro前端闭环（2026-09-28）

MeetingPanel新增“打开会议 Sprint Retro”入口，新增retroAnalysis/retroAnalyses接口模块和RetroAnalysisForm/Panel、RetroProposalReviewForm/Execute。分析请求号按用户/会议/可选Sprint隔离，发送前保存，未知结果及页面刷新后沿用编号。展示原文、带证据的会议决议、待确认问题、候选及处理状态。

人工审核支持接受、拒绝和修改后接受。修改需完整行动内容及原因，负责人用当前成员姓名/ID选择，可显式未确定；截止时间为空转换null，不推算日期。成员目录加载失败阻止修改后接受并提供重试。原提案和批准内容分开展示，审核后才出现独立执行入口；成员可分析不可审核执行，只读用户仅查看。

执行只发送proposal_id，校验归属和完整审计确认后显示成功，保留已确认结果并刷新行动项。新增按会议展示已创建行动项及创建审计/批准载荷。分析列表、审核执行详情、成员目录和行动查询具备过期请求或卸载保护。未修改Python/Java业务逻辑，也未改既定方案。

浏览器用例覆盖修改审核执行后刷新审计、残缺执行响应、无效修改/拒绝、角色权限、未知分析刷新重试、未知负责人/截止时间保留、成员目录失败恢复。测试使用Vite/Edge与mock业务API；Retro真实服务联调和模型语义验收待后续进行。下一步集中完成Retro真实Python/Java/MySQL业务闭环验证。

最终验证：npm run build通过；完整浏览器回归53项通过，新增后补的两个边界用例2/2通过，合计55个不同用例（Retro8项、既有47项）。首轮修复分析组件导入路径后重新构建成功；完整回归测试服务关闭受沙箱限制停住，核实并停止本轮Vite进程后以exit0结束，补充测试在批准的沙箱外执行并正常退出。git diff --check通过。本轮到此停止。

## 第四十八轮：Retro真实服务闭环验证（2026-09-29）

新增frontend/e2e-live/retro-flow.spec.js；qa/run_daily_live.py增加--suite retro及复盘模型HTTP夹具、数据库结果断言，沿用独立MySQL目录/专用端口。2026-09-28启动，2026-09-29从持久日志和result.json核实完整成功结果；未因工具会话失效重复启动测试。

真实Edge/Vite/Python/Java/JWT/MySQL 8.0.31浏览器1/1通过。模型夹具提出负责人/时间未知的行动，页面人工修改事项、选择负责人及补充截止文本后批准；分析和审核后不创建业务行动，待审执行409，独立执行才创建open行动项及审计。两个并发重复执行返回首次结果，已存分析重试返回原ID，模型总调用1次。原分析、故事、任务和成员资料保持不变；匿名执行401，只读分析/审核/执行403，查询行动项200。页面刷新后行动项和审计可读。

数据库分析/审核/执行/行动项/行动项日志各1条，故事日志0条；创建审计ID与执行记录相符。证据目录qa/.daily-live/3b0311ebaf134f2f92173e549cdd4e32/保留且忽略，运行方法和计数说明见qa/DAILY_LIVE.md。Java21打包BUILD SUCCESS，git diff --check通过。未修改日常业务库；仅模型使用固定HTTP响应，不宣称真实模型语义通过。

下一步集中开展Retro真实模型的有界验收，覆盖明确决定与建议、否定/条件、负责人歧义和截止时间缺失，保留失败结果；之后按既定路线继续Refinement。本轮停止。

## 第四十九轮：Retro首轮真实模型验收（2026-09-29）

新增retro_evaluation.py、evals/retro_quality.json、tests/test_retro_evaluation.py。每例一次，拒绝覆盖旧报告，供应商故障停止；自动比较行动数量、负责人、截止文本、证据片段及必要追问。原解析candidate与校验结果保留供文本复核。新增.retro-quality忽略规则，使用合成原文及最小成员投影，无业务写入。

Python全量176项通过。首次因env路径不存在在调用前退出，随后使用既有backend/.env完成8次deepseek-v4-flash真实调用，自动检查8/8通过。逐条复核发现实质失败：retro-same-name把“整理发布检查表”改成“整理并发布检查表”，标题和描述新增发布动作。同名负责人仍正确为null，时间正确，说明字段/契约通过不等于行动语义准确。另有冲突案例把缺少方案细节表述成尚未明确的轻微概括。

报告.retro-quality/retro-v1-20260929.json及其哈希、逐例结论记录于evals/RETRO_QUALITY_REVIEW.md；本轮不修改retro-action-v1，不重跑、不宣称整体质量通过。git diff --check通过。

下一步优先修正名词对象与新增动作的边界，补“整理发布检查表/整理并发布检查表”正反对照，做一次定向复验，再按既定路线继续Refinement。本轮停止。

## 第五十轮：Retro行动范围定向修正（2026-09-29）

retro_skill.py升级retro-action-v2，明确标题/描述/理由/决议/摘要保持动词、对象及范围，事项名称不能拆成新增发布/部署等动作；已明确要求的动作不能删除。缺失信息追问不补造尚未明确等事实前提。新增evals/retro_semantics.json四个正反案例及一项离线验证，Python177项通过。

原同名失败例/冲突例与新四例共6次deepseek-v4-flash调用，自动6/6，逐条复核中“整理发布检查表”未再新增发布，明确“整理并发布”正例保留两步，部署文档例未新增部署，未采纳建议无行动。没有业务写入或重复调优。

残余问题：同名例把项目成员目录表述成参会成员，显式发布例摘要没有概括决定，另有泛化摘要及重复追问。记录待办，不宣称Retro整体语义全通过；按用户优先完整闭环的要求，不扩大本轮范围继续打磨。

新报告.retro-quality/retro-v2-target-20260929.json、组合案例及SHA256记录于evals/RETRO_QUALITY_REVIEW.md，旧报告哈希未变。未做原8例全量或稳定性重跑。git diff --check通过。下一步按既定路线进入Refinement最小切片，本轮停止。

## 第五十一轮：Refinement新故事候选分析核心（2026-09-29）

依据本地记录的Refinement区分待讨论/确认需求/实际创建原则及create_story工具方向，实现新故事候选最小切片。尝试重读原共享链接失败，沿用本地既定记录，不将无法获取的细节当作已确定设计。已有故事修改、拆分等后续范围不在本轮实现。

新增refinement_contracts.py、refinement_skill.py、refinement_workflow.py；StoryReadTool新增get_refinement_stories，复用PlanningStorySnapshot的业务字段和既有GET /api/stories认证传输。LangGraph只读上下文→模型→校验，无写工具，令牌不进模型/图状态，关闭tracing。refinement-create-v1仅提出明确决定新增的create_story候选，禁止讨论/否定/条件/未决冲突直接变成新故事。

changes含title、description、acceptance、priority、sprint、activity；除标题外字段必须显式为值或null，缺失时追问，不借用StoryIn默认值。模型不得指定ID、负责人、状态或估时。业务创建所需缺失字段必须在后续人工审核处理。字段命名为当前实现选择，不宣称共享方案已指定。校验会议/引用/类型/编号/同名重复；既有与候选标题去首尾空白并casefold比较。同义重复和真实采纳语义仍需模型验收与人工审核，不能由精确检查代替。

新增test_refinement_workflow.py八项及test_story_tool.py一项；Python全量186项通过，git diff --check通过。覆盖未知字段保留、空提案、输入/快照非法阻断、只读认证投影、调用隔离、供应商/工具失败不重试、伪造证据、越界写字段和重复候选。使用真实LangGraph和本地夹具；未调用真实模型/Java写接口、未新增HTTP持久化或页面。

下一步集中实现Refinement候选持久化和鉴权分析入口，再补人工审核/独立执行创建故事。本轮停止。

## 第五十二轮：Refinement鉴权分析与候选持久化（2026-09-29）

新增refinement_store.py及Python POST /api/meetings/{meeting_id}/refinement/analyze，Java先校验角色/会议再按当前调用者请求编号查询，恢复已有结果时不读取故事或再调模型。新结果经Java持久化确认后返回。复用有界传输与相同载荷至多一次保存重试；结果不确定时保留原请求号。

新增RefinementAnalysisDtos/Service/Controller和refinement-analyses.sql，注册application.yml。独立meeting_refinement_analyses保存原文、完整候选及保存时用于同名检查的基础故事目录快照（id/title/status/sprint/owner_id，最多1000条）；不是模型分析时完整内容快照。支持保存、列表、详情及当前用户请求查询。新增故事无目标ID；稳定顺序读取并锁定目录核对标题，保存不创建故事或故事日志。

Java重新验证严格类型、create_story动作、显式可空字段、引用、重复编号及同名冲突。未知内容不使用StoryIn默认值。标题Java使用strip+Locale.ROOT小写，Python用casefold，特殊Unicode折叠并非完全等价；精确同名检查不代表同义去重。会议行锁及唯一键保证同用户同请求幂等，同编号异载荷409，恢复保留原快照。后续批准/执行仍须重新检查冲突并人工处理必需字段。

新增test_refinement_api.py六项，Python192项通过；RefinementAnalysisStorageTest七项加Retro16/Review17/Planning14/Daily25，共79项通过，Java21 BUILD SUCCESS。覆盖权限/归属、无默认填充、严格字段、空候选、非法后续提案整批拒绝、原记录恢复及并发只保存一次。测试使用本地HTTP夹具与隔离H2事务/MockMvc，无真实Refinement模型/MySQL/浏览器联调。git diff --check通过。

下一步集中实现Refinement人工补齐/修改审核及批准后创建故事：批准载荷独立保存、执行前同名复查、故事创建和审计同事务、重复执行返回首次结果。本轮停止。

## 第五十三轮：Refinement人工补齐审核与创建故事（2026-09-30）

新增RefinementProposalReviewService/Controller和RefinementProposalExecutionService/Controller，分别提供proposal-reviews与proposal-executions GET/POST。admin/owner审核执行，登录用户可读。支持接受/拒绝/修改后接受；批准要求title/description/acceptance非空、priority及Sprint/activity有效，未知字段须人工补齐，不套用默认值。修改需改变内容并说明原因，原提案和批准载荷分别保存；相同审核人同载荷可重试，既有决定不可覆盖。

批准不创建故事。独立执行仅接受proposal_id，读取已存批准载荷，再次核对必需字段和已有同名标题。复用IdAllocator分配US编号，故事初始status=0/owner_id=null；在同一事务内创建故事、create日志（detail保存批准提案）、执行记录及审核执行状态。重复执行返回首次结果，包括后续故事已编辑的情况。新增refinement-proposal-reviews.sql和refinement-proposal-executions.sql并注册application.yml。

扩展RefinementAnalysisStorageTest夹具，新增RefinementProposalFlowTest八项，含继承存储共15项，加Retro16/Review17/Planning14/Daily25，共87项通过，Java21 BUILD SUCCESS。上轮测试停在H2不支持的批量ALTER，本轮改逐列ALTER后重新运行通过。验证补齐/直接批准/拒绝、权限、原记录不变、执行越权字段、同名复查、故障回滚、并发重复只创建一次及查询归属。使用真实事务与IdAllocator，其Mapper读以同一隔离H2实现；未运行真实MyBatis/MySQL或浏览器联调、未迁移日常业务库。

编号器沿用既有并发主键冲突409机制，不能据本轮测试宣称所有业务入口无编号冲突或具备全局同名唯一约束。同名检查不等于同义去重。Python未修改，沿用192项；git diff --check通过。下一步集中接Refinement前端分析、人工补齐审核、创建执行和故事审计，再进行真实服务联调。本轮停止。

## 第五十四轮：Refinement前端闭环与真实服务联调（2026-09-30）

新增refinementAnalysis.js/refinementAnalyses.js和五个Refinement组件，通过MeetingPanel接入已保存会议。支持分析、按原请求恢复不确定结果、查看原文证据/未知字段、人工补齐六项故事字段、接受/修改后接受/拒绝、批准后独立执行。缺失字段不套默认值，直接接受不完整提案会阻断；修改需实质变化和原因。原始与批准内容分开展示，权限沿用成员可分析、管理员/负责人审核执行。严格核对执行返回的分析/提案/故事/日志/执行人/时间后才确认成功，执行后刷新故事看板和日志。

新增e2e-status/refinement-flow.spec.js六项，覆盖完整补齐执行与刷新审计、不完整批准阻断和拒绝、畸形确认、成员/只读权限、跨刷新同请求重试。浏览器全量61/61通过，npm run build成功。Java21重新打包成功，未修改后端业务逻辑；本轮未重跑Java87/Python192单元测试，保留上轮结果。

扩展qa/run_daily_live.py --suite refinement，新增e2e-live/refinement-flow.spec.js。真实隔离MySQL8.0.31、MyBatis/IdAllocator、Java/JWT、Python、Vite/Edge闭环1/1通过；批准不创建故事，执行新增待办且未分配负责人的故事，字段与人工批准一致，创建日志保留批准载荷。原故事、任务和成员列表不变，分析原始记录不变；两个并发重复执行返回同一结果，无新增故事/日志。分析重试复用原ID，固定HTTP模型仅调用1次。匿名401、只读审核/执行/分析403；刷新后审计仍可见，故事地图出现新卡片。

证据qa/.daily-live/9c18c665ff514c1cbfc25e7987af2b9f/result.json，counts依次为分析/审核/执行/create日志/目标故事数，均1。未改日常数据库或真实模型配置。固定模型验证业务链路，不代表真实模型需求理解已验收；原有编号跨入口并发冲突及同义重复限制仍保留。README、qa/DAILY_LIVE.md与.agent-collab/CURRENT.md已更新。

下一步进行Refinement真实模型有限样本验收，重点检查未采纳讨论不创建、未知字段不编造、同名已有故事不重复提案及证据可追溯。本轮停止继续开发。

## 第五十五轮：Refinement真实模型基线、修正与复验（2026-09-30）

新增refinement_evaluation.py、refinement_quality.json十二例、refinement_semantics.json五例及test_refinement_evaluation.py五项。评测先离线验证Oracle，再逐例调用生产Skill；检查数量、明确/未知字段、枚举排期、支持片段与追问，文本由人工逐条复核。固定合成输入，不访问真实会议和业务写接口；失败候选可保留，供应商故障停止，输出文件不可覆盖。

deepseek-v4-flash首轮12/12自动通过，四个新增正例保留，建议/否定/条件/冲突/重复/已有修改/注入均无新增。人工发现冲突观点丢失来源、重复追问已知预算、已有修改决定与系统执行边界混淆。refinement-create-v2定向收紧来源、已知事实和流程边界；五例复验自动5/5通过，新增正例未丢失，冲突来源和预算追问改善。仍有条件句含混和“人工确认”指代不清，记录待办，不宣称全部语义通过或继续反复调词。

真实调用共17次，无业务写入，无密钥输出。原报告保存在.refinement-quality忽略目录，逐例结果、SHA256、限制及复现命令见evals/REFINEMENT_QUALITY_REVIEW.md。新增5项离线测试，最终Python197/197通过；首次既有Review API夹具503，第二次停滞中断，第三次带40秒堆栈诊断全量通过（10.318秒），原因未定位，不宣称修复测试稳定性。未改Java/前端，不重复其上轮验证。README及协作状态已更新。

下一步优先梳理现有五类会议入口的统一试用验收与操作指引，并在既定范围内补实际使用阻塞点；残余模型措辞问题保留可追踪待办，不因有界评测通过取消人工审核。本轮停止。

## 第五十六轮：五类会议统一试用验收与指引（2026-09-30）

顺序执行现有Daily/Planning/Review/Retro/Refinement真实服务live套件，5/5通过，每套独立MySQL8.0.31、真实Java/MyBatis/JWT/Python/Vite/Edge，固定HTTP模型各调用1次。验证分析/审核不越过独立执行、幂等写入、权限和刷新审计；各套具体计数、运行目录见qa/DAILY_LIVE.md统一验收表。使用已打包当前Java产物，未改日常库，未调用真实模型，无新增业务阻塞。

新增docs/会议Agent_五类会议试用指南.md：启动服务、backend/.env三项模型配置仅加载到Python进程、页面五类入口和当前动作范围、样例前置、角色权限、未知字段和审核约束、重试与冲突处理、真实业务联调与模型质量边界。修正根README旧会议入口说明，更新ai-service/README和qa/README导航及DAILY_LIVE标题/Refinement命令。未改业务逻辑。PowerShell示例语法解析及git diff --check通过；未据语法验证宣称手工启动步骤已全部实跑。

下一步建议将已核实的Python启动步骤做成可复用启动入口并提供基础就绪检查，减少每次手动加载配置及误用旧服务；不扩展或重设计已定会议业务。本轮停止。

## 第五十七轮：Python服务启动入口与基础检查（2026-09-30）

新增ai-service/start_service.py，默认按脚本所在目录定位backend/.env，仅加载AICAP_LLM_API_KEY/BASE_URL/MODEL三项；文件中的值覆盖当前进程同名配置，不插值、不打印密钥。支持--env-file、--environment-only、--java-url、--port及--check-only；指定文件缺失明确失败，默认文件不存在可使用现有进程环境。

启动前导入服务依赖、使用生产ModelSettings/ChatModelClient验证配置格式、以无代理/不跟重定向/3秒超时/4KiB响应上限检查Java健康，以及尝试独占127.0.0.1监听端口。启动时将已绑定socket直接交给Uvicorn，避免先检查再绑定的端口竞争；不关闭现有进程、不自动安装或调用模型。前台运行，用户Ctrl+C停止。基础检查明确不验证密钥有效性、模型余额、数据库迁移或鉴权分析链路。

新增tests/test_start_service.py六项，覆盖配置白名单/字面值、Java健康与错误地址、端口占用、缺密钥、不调用模型的check-only、从不同工作目录实际启动Uvicorn子进程并读取/health且不输出密钥。测试使用本地Java健康夹具和假模型配置，不访问真实模型/业务库。定向6/6、Python全量203/203通过（14.743秒），全量附60秒堆栈超时保护未触发；git diff --check通过。

更新ai-service/README及五类会议试用指南，以两条启动/检查命令替换手工加载配置片段。Java/前端业务无改动，本轮不重跑五类live。下一步可选择一类会议，在隔离数据库验证真实模型→页面人工审核→执行的整条链路，将此前模型只读评测与固定模型业务联调接起来。本轮停止。

## 第五十八轮：Refinement真实模型至业务执行端到端验收（2026-10-01归档）

qa/run_daily_live.py新增显式--real-model（仅Refinement）和--env-file，默认仍固定模型。真实模式通过本地单次预算网关调用生产ChatModelClient，转发生产Skill消息，仅发送合成会议及隔离种子故事；最多一次供应商尝试，失败不重试，第二次网关请求429。配置不写进报告，供应商异常不回显。启动Python改用start_service.py --environment-only；真实密钥仅供网关，子进程使用本地配置。

frontend/e2e-live/refinement-flow.spec.js按保存结果读取proposal_id，真实模式使用明确标题/未知字段的合成文本；保留原始模型候选、持久化分析及审核/执行/故事/日志JSON。自动化脚本通过人工审核界面填写预设补充字段，不代表模型自动批准或真实用户现场验收。

真实deepseek-v4-flash调用1次，浏览器1/1通过。模型提案保留五项null、证据来自S1；审核前后故事不变，独立执行创建US38（待办、无负责人）和日志#1；并发重复执行、分析恢复、权限、刷新审计和看板均通过。MySQL8.0.31 counts为1/1/1/1/1，证据qa/.daily-live/77fda1eea578435089ab528b0d8ffa09/，原始生成时间不改写，文件SHA256及逐项复核见qa/DAILY_LIVE.md。

新增qa/test_live_model_gate.py两项本地测试，成功/失败均只允许一次调用、错误脱敏，2/2通过。默认模型首次回归中断、无完成报告，不计为通过；恢复后重跑1/1通过，证据qa/.daily-live/d03c3e36049141238dde24e6e8809835/。未再次调用真实供应商、未改业务库或业务服务逻辑，git diff --check通过。未重跑Python203、Java87、浏览器61全量，沿用前轮记录。单次正例不覆盖复杂语义和稳定性，已有语义待办保留。

下一步建议检查会议删除与新增分析/审核/执行记录的关联边界，避免旧删除入口与五类新记录不一致；先核对既定记录保留规则，再做最小修复和验证。本轮停止。

## 第五十九轮：会议删除与五类分析审计一致性（2026-10-01）

检查发现MeetingService旧清理链路遗漏五类分析表，删除有分析的会议会因现有外键失败，前端仍提示可删除。沿用现有外键保护结果而非新增级联删除：新增MeetingDeletionGuard，MANDATORY加入外层删除事务，先对会议FOR UPDATE，再用锁定读检查status/planning/review/retro/refinement分析；任一存在立即409，发生在录音/旧建议等任何清理之前。与五类保存的会议锁顺序一致。包括无变更/拒绝/未执行分析；无分析会议仍可按原流程删除。没有改表结构、没有删除现有记录或业务成果。

修改MeetingService接入保护、MeetingController说明、MeetingPanel删除确认文案。删除冲突保留当前会议和提案，未新增强制删除入口。更新试用指南说明保留边界。

新增MeetingDeletionGuardTest八项，合并五类原回归共95/95通过，Java21打包成功；包括存在检查、404、保存先提交和删除先提交后的结果。H2并发用例不能替代所有MySQL竞争场景。新增浏览器409反馈与状态保留测试，Refinement定向7/7通过；其余前端全量未重跑。五套live增加公共删除断言，在待审及执行后检查409与原记录/业务列表不变，另外验证无分析会议200删除及读取404。真实MySQL运行证据见qa/DAILY_LIVE.md本轮章节。

下一步回到既定路线，梳理Assignment Engine最小切片的现有成员画像、任务估时与容量输入，优先只读建议和人工审核边界；不重复打磨本轮已通过的链路。

本轮最终结果：Java95/95、浏览器定向7/7、五类真实MySQL闭环5/5均通过，git diff --check通过。五类live证据目录已归档到qa/DAILY_LIVE.md。到此停止继续开发。

## 第六十轮：Assignment数据准备、候选规则及鉴权接口（2026-10-01）

原共享链接本轮读取失败，本地缺少具体分配公式，先推进可独立的数据准备。用户随后明确授权“原对话已丢失，请自行决定吧”，据此确定assignment-skills-v1，不将新规则称为原对话既定算法。

新增assignment_context.py，复用PlanningContextReader及三个已鉴权Java GET投影，输出选定故事、成员画像/关联故事/活动任务/目标Sprint任务、完整原始任务及缺口。保留六周容量和两种Task工时原义；故事估时/Sprint可用及剩余工时均未知，Sprint4任务排期不可用用null，非原子读取明确标记。悬空负责人/看板关联及空画像有诊断，不猜测修复。

新增assignment_engine.py，针对一个故事接收人工明确技能要求（维度/名称/最低等级），只按名称精确归一化匹配，逐项等权，按满足数量排序，同分并列、ID仅稳定显示；年限/姓名不打分。viewer排除，其余角色均仍需人工核对；缺技能记录是null而非真实能力为零。空要求不排名，无匹配明确说明。所有候选容量unknown、requires_human_review，不自动决定负责人。

api.py新增POST /api/meetings/{id}/assignment/context及/assignment/recommendations；admin/owner/member可用，viewer403。Java授权会议后读取，不调用模型、不保存、不写业务数据，越权字段及未知目标拒绝。尚无前端入口、建议持久化、审核执行和容量可行性算法。文档docs/会议Agent_AssignmentEngine规则与接口.md记录新规则来源、数据映射、输入样例和限制，ai-service/README加入入口。

新增test_assignment_context.py十一项、test_assignment_engine.py六项。首轮5项因冻结契约不允许原位修改rank报错，改为复制新候选后Python全量220/220通过（15.315秒），未放宽断言。验证数据/规则/权限/无写与本地HTTP适配器；未运行真实Java/MySQL、浏览器或模型评测。git diff --check通过。

下一步接Assignment候选页面：选择故事、人工输入要求、查看逐项匹配/现有负载/未知容量，同分和无匹配明确展示；随后再推进持久化与人工审核后的独立执行，不把只读建议直接写入负责人。本轮停止。

## 第六十一轮：Assignment候选页面与浏览器验证（2026-10-01）

新增frontend/src/components/ai/AssignmentPanel.vue及frontend/src/api/assignment.js，在MeetingPanel增加“打开分配候选”入口。admin/owner/member可选择故事、目标Sprint及1–20项明确技能要求，查看并列排名、逐项画像等级、关联故事、未完成任务及容量缺口。六周容量与Sprint容量分开说明；未匹配不表述为成员无能力。viewer不可提交。

接口响应校验会议/故事/Sprint归属、规则版本、候选成员及逐项阈值依据。修改条件清空结果；切换会议或收起组件使迟到响应失效；请求失败保留输入，故事目录失败支持重试。不调用模型，不持久化候选，不修改负责人，无新助手派工。

新增frontend/e2e-status/assignment-flow.spec.js八项浏览器测试。全量首次69/70通过，唯一失败为目录故障夹具早于页面初始化，影响了应用初次加载；增加页面就绪等待后，该项定向重跑1/1通过。合计70个用例均已通过验证（不是修正后再次全量运行）。npm run build通过，git diff --check通过。未修改后端逻辑，未重新运行Python/Java单测或真实MySQL联调。同步更新规则接口、试用指南、ai-service/README和临时分工记录。

下一步优先完成Assignment建议快照持久化及人工选择/审核，再接独立执行和业务快照冲突检查，形成分配闭环。本轮停止。

## 第六十二轮：Assignment建议持久化与人工选择审核后端（2026-10-01）

新增Java AssignmentSuggestionService/Controller及db/assignment-analyses.sql，application.yml纳入启动建表。快照保留input/result、提交人、时间、请求号和不可覆盖审核决定。保存锁定会议并检查故事id/title/status/sprint/owner_id；同会议/提交人/请求号并发收敛为一条，完全相同重试恢复原记录，载荷不同409。列表与详情按会议隔离，请求恢复按提交人隔离。

admin/owner/member可保存，所有登录角色可读，admin/owner可审核。批准必须明确选择已保存候选、给出理由并知悉容量未验证，重新检查成员角色及故事快照；可选择非首位或无匹配记录的候选。拒绝保留理由，不要求故事仍存在。同审核人相同决定幂等，其他决定409；不修改故事、不产生故事日志，无分配执行端点。capacity_acknowledged不表示容量已核实。

新增Python assignment_store.py和POST /assignment/suggestions入口：只接收技能要求和请求号，读取事实重新计算后保存；恢复请求检查输入一致性，保存响应按快照重算候选校验。沿用有界HTTP和JWT转发，瞬时故障最多同载荷重试一次，未知保存结果显式报错。原只读接口和页面保持原行为，保存及审核页面下一轮接入。

MeetingDeletionGuard扩展Assignment保留边界，已保存建议的会议返回409；增加对应删除测试、更新MeetingPanel提示和Refinement浏览器断言。规则/API文档、试用指南、ai-service/README和临时分工记录同步。正式代码仍由主AI维护，无新助手派工。

本轮文件：新增ai-service/meeting_agent/assignment_store.py、ai-service/tests/test_assignment_store.py、java-backend/src/main/java/com/aicap/controller/AssignmentSuggestionController.java、service/AssignmentSuggestionService.java、src/main/resources/db/assignment-analyses.sql、src/test/java/com/aicap/contract/AssignmentSuggestionTest.java；修改Python api.py及共享HTTP测试夹具test_api.py、Java application.yml/MeetingDeletionGuard.java/MeetingDeletionGuardTest.java、前端MeetingPanel.vue/refinement-flow.spec.js及上述文档。

验证：Python最终225/225（15.584秒），新增5项；初次230项包含测试类导入导致的5项重复发现，改用模块导入后重新验证。Java AssignmentSuggestionTest新增9项、删除保护增加1项，与五类流程隔离回归共105/105；真实H2事务验证并发、幂等、权限、非法载荷、过期故事/成员、拒绝与无业务写入。Java首次无筛选测试触发旧MySQL集成用例，本地数据库连接失败，该完整套件未通过；随后仅运行隔离回归，不宣称真实MySQL验收。Java21 package成功，前端构建成功，删除提示浏览器定向1/1。未调用真实模型，未重启或迁移日常数据库。git diff --check通过。

下一步接入Assignment页面保存、历史恢复及人工选择审核，继续复用现有界面模式；之后补独立执行与冲突检查。本轮到此停止。

## 第六十三轮：Assignment页面保存审核与独立执行闭环（2026-10-01）

用户允许本轮多完成任务，本轮完成页面保存、历史恢复、人工审核、负责人独立执行和审计展示。AssignmentPanel增加保存入口，完整请求按用户/会议保存在sessionStorage，保存未确认时保持原请求编号和条件；存储不可用不发出保存。保存重新计算候选，不把只读查询显示值直接入库。AssignmentHistory展示保存时技能与任务负载快照，支持历史选择、刷新、人工选人和理由/容量未知确认、拒绝与独立执行。member/viewer只有对应读取/提交权限，审核执行仍需admin/owner。

assignment.js校验保存记录归属和原输入、审核决定、执行审计字段，缺日志等无效响应不显示成功。迟到响应不污染新会议，确认执行后刷新看板；刷新失败单独重试读取。服务端成功但响应丢失时可刷新历史恢复真实执行状态，避免再建建议或再写业务。尚未实现跨浏览器保存意图恢复；历史记录可查询已成功保存的请求。

Java AssignmentSuggestionService/Controller新增POST /assignment-suggestions/{id}/execute，仅接收空对象，目标与负责人只读已保存审核。建议行锁串行化同建议执行；执行前检查成员角色和故事title/status/sprint/owner_id，过期、未审核/拒绝或无负责人变化均拒绝。负责人更新、edit故事日志、review_json追加execution审计同事务；输入和审核人/时间不覆盖。重复执行返回同一审计，不重复写日志。此切片只变更故事负责人，不改变Sprint/状态或关联任务，也不推算未知容量；画像与任务仍是供人工参考的保存时快照。

本轮文件：frontend/src/api/assignment.js、frontend/src/components/ai/AssignmentPanel.vue、新增AssignmentHistory.vue与frontend/e2e-status/assignment-lifecycle.spec.js；Java AssignmentSuggestionService.java、AssignmentSuggestionController.java、AssignmentSuggestionTest.java；规则/API文档、试用指南、ai-service/README及本进度/临时分工文档。未新增表结构，执行审计存于原建议review_json。未修改Python逻辑、未操作日常数据库、未提交或推送Git。

验证：Java新增执行5项，Assignment共14项；合并会议删除与五类流程共110/110通过，Java21 package成功。浏览器先全量78/78通过，随后补充审核负载展示及响应丢失恢复、sessionStorage失败测试，最终定向10/10通过，累计80个独立用例已验证。最终npm run build、git diff --check通过。Python沿用前轮225项，没有重复运行。本轮为H2事务/MockMvc及浏览器HTTP夹具，尚未完成Assignment真实Java/Python/MySQL联合验收，不等同于生产验收。

下一步优先进行Assignment隔离真实服务联调，验证真实JWT、MyBatis/MySQL、Python持久化及页面审核执行，并纳入现有QA脚本；通过后按既定路线推进上传录音与STT/Diarization。暂不需要助手派工，本轮停止。

## 第六十四轮：Assignment真实联调及转写音频输入准备（2026-10-01）

qa/run_daily_live.py新增assignment套件，frontend/e2e-live/assignment-flow.spec.js走真实JWT登录、会议保存、Python技能计算、Java/MySQL快照持久化、页面人工审核和独立负责人执行。验证任务/成员画像不变、重复/并发执行幂等、Python请求恢复、权限和会议删除保护、刷新审计；另保存拒绝建议、批准后真实修改故事制造冲突，确认不能执行。最终3条建议：1执行/1拒绝/1冲突未执行，仅1条分配日志，模型调用0。

首轮205a521bdf004b2a8b5e7b048d46dbcf失败：测试对整个面板toContainText，拼接的按钮和标题提前匹配“分配建议已保存”，未等待实际保存；已改为精确成功提示，并同步修正assignment-lifecycle夹具断言。修正后56d17b170f5c469ab670159900aac7f5真实Assignment通过1/1。此问题为测试时序，未修改分配业务逻辑。

继续进入既定STT路线的首个输入切片，新增ai-service/meeting_agent/audio_tool.py及POST /transcription/prepare。复用现有Java音频上传/存储/回放API，按当前用户角色、会议音频目录核对归属，固定Java地址下载，不采用元数据URL。音频25MiB和元数据256KiB上限、超时、禁止重定向、audio/mpeg、字节大小及两处SHA-256校验；仅返回元数据和verified_bytes/not_started状态，不返回音频内容或令牌，不修改原文。时长保留上传声明并标记未验证，不假称解码时长；没有STT/说话人区分引擎、没有新外部调用。

新增test_audio_tool.py九项，Python全量234/234（18.431秒），QA网关2/2通过。扩展同一真实套件，上传2048字节合成MP3头/帧同步夹具到Java，再由Python读取校验，核对匿名/只读/未归属ID拒绝。最终真实MySQL8.0.31联调 **237afe831f7a4fcbb75b374baf67e110** 浏览器1/1、counts3/1/1/1、model_calls0、退出码0；不能据此声称语音识别已完成或音频可解码。运行自动停止本轮服务，日常库未改。

本轮变更：qa/run_daily_live.py、frontend/e2e-live/assignment-flow.spec.js、frontend/e2e-status/assignment-lifecycle.spec.js；新增audio_tool.py/test_audio_tool.py、修改api.py注册音频准备入口；新增docs/会议Agent_音频输入与转写接入.md，更新Assignment规则、QA说明/证据、ai-service/README及进度/分工。Java和前端产品代码未改，沿用前轮打包与110项Java/80个浏览器用例基线，不重复跑全套；git diff --check通过。无Git提交推送，无助手新派工。

下一步接实际转写引擎和音频解码，形成带时间戳/匿名说话人标签的结果；验证真实语音，再保存可人工核对的转写版本并衔接会议Agent。既有会议原文及审核审计不得静默覆盖。本轮停止。

## 第六十五轮：本地语音转写、时间戳草稿及真实页面验收（2026-10-02）

复用成熟faster-whisper 1.2.1及多语言base模型，本机CPU int8推理，PyAV解码MP3。新增可选requirements-stt.txt及prepare_stt.py，显式安装/下载并记录模型revision；服务请求只读本地模型，禁用在线下载，不向外部语言模型发送音频。模型和临时产物不入Git。当前本机已完成安装及下载。

Python新增transcription.py、transcription_worker.py和POST /transcription/run，复用前轮鉴权音频读取。校验实际解码时长不超过10分钟、文本16000字符以内、片段ID/时间范围/全文一致；每服务进程一个转写子进程，CPU4线程、180秒超时及临时文件清理，忙碌明确429。结果是未保存的draft/no_speech，speaker_id=null及diarization_status=not_available；没有用成员身份或任意匿名标签冒充说话人分离结果。

前端新增api/transcription.js及AudioTranscription.vue，RecorderPanel每条音频接入语言选择、开始转写、忙碌/错误反馈、时间戳片段和可复制全文。响应绑定音频/会议/hash，切换会议隔离迟到结果；viewer无发起入口。页面明确草稿需核对、刷新丢失，不自动覆盖原文。尚未实现保存版本与人工编辑确认，也尚未实际区分说话人。

新增test_transcription.py八项及transcription.spec.js七项；Python242/242、pip check通过。浏览器全量86/87，一条Retro入口超时，之后定向Retro8/8通过，累计87个独立用例通过；未声称单次全绿。前端构建及差异检查通过。qa/run_stt_local.py以Windows中英文语音生成MP3并实际执行模型，两例有文本/时间戳，约4.7/2.7秒；中文“登录”误识别为“登陆”，仅作为清晰合成音频链路验证，不代表真实会议准确率。

qa/run_daily_live.py加入transcription套件和--stt-audio，新增真实浏览器transcription-flow.spec.js；上传→鉴权读取→实际转写→页面展示→原文不变→刷新不留草稿，最终930570f8a9c540c49235e5514ba98520真实MySQL/Java/Python/Vite/Edge1/1通过、退出0，音频/分析/故事日志1/0/0，LLM调用0、成功转写1。前两次分别因SQL表名拼写错误、Java音频读取503失败，完整记录见qa/DAILY_LIVE.md；后者顺序重跑未复现，根因未确认。live Playwright产物改到各运行专属子目录。

文档更新：音频指南、主/AI服务README、QA说明/证据及本进度和临时协作记录。Java代码本轮未改，日常数据库未动，未Git提交推送。下一步优先实现独立转写版本保存及人工核对，再明确选择文本进入既有会议分析；说话人分离需另行实现与样本验收。暂不需要助手派工，本轮停止。

## 第六十六轮：转写版本保存、人工核对及分析衔接（2026-10-02）

新增Java TranscriptVersionController/Service及db/transcript-versions.sql，保存用户提交的不可变草稿，严格核对音频归属/hash、片段/全文和未知说话人约束。返回caller_submitted来源，不把客户端传入的模型草稿当作服务器认证的模型输出。按用户/会议/请求号幂等；前端按完整草稿内容生成稳定编号，重复保存不增行。

人工核对允许admin/owner/member，必须明确acknowledged=true。保留原始片段，单独保存校对文本、确认人和时间；同事务创建独立会议并保存analysis_meeting_id，相同确认幂等，不同内容/确认人冲突。新会议复用五类分析入口，原会议文本及历史分析不覆盖。当前每版本只确认一次，不支持确认后的多轮修订或撤销；可手动新建会议继续整理。说话人分离仍未接入。

前端新增TranscriptVersions.vue、api/transcriptVersions.js，AudioTranscription接入保存、历史选择/恢复、原始片段查看、核对文本及显式确认。响应未确认时冻结原提交并允许原样重试/刷新恢复；切换会议隔离迟到结果。打开分析会议单独触发，不自动调用模型。MeetingDeletionGuard扩展版本关联保护，MeetingAudioService将音频外键删除冲突明确返回409；原音频、原会议和派生分析会议均保留。

本轮文件清单：新增Java controller/TranscriptVersionController.java、service/TranscriptVersionService.java、resources/db/transcript-versions.sql、test/.../TranscriptVersionTest.java；修改application.yml、MeetingDeletionGuard.java、MeetingAudioService.java、MeetingDeletionGuardTest.java。前端新增src/api/transcriptVersions.js、src/components/ai/TranscriptVersions.vue、e2e-status/transcript-versions.spec.js；修改AudioTranscription.vue、MeetingPanel.vue、e2e-status/refinement-flow.spec.js、e2e-live/transcription-flow.spec.js。QA修改run_daily_live.py、DAILY_LIVE.md；更新主/AI服务README、音频指南、本进度及.agent-collab/CURRENT.md。Python生产逻辑不变。

验证：新增Java7+1项，相关14类178/178通过，Java21 package通过；浏览器转写/版本13与Refinement/删除7，共20/20定向通过，前端build及差异检查通过。真实服务首轮因刷新默认选择最新会议，测试寻找原音频按钮超时，明确选择原会议后最终 **3288200b0caf4747988047d9c5d8d866** 1/1通过、退出0。真实语音模型1次、固定文本模型1次；音频/分析/故事日志/版本/已确认版本1/1/0/1/1，分析快照为核对文本，原文不变且无提案执行。详情及限制见qa/DAILY_LIVE.md。

下一步优先实际说话人分离与多人音频验收，保留无法识别身份的明确状态；随后再评估确认文本多轮修订和时间片段校对体验。不重做已完成五类会议及Assignment方案。无需新增助手派工；未修改日常库、未Git提交推送，本轮停止。

## 第六十七轮：本地匿名说话人时间段及公开多人样本验收（2026-10-02）

接入sherpa-onnx 1.13.8离线CPU说话人分离。新增requirements-diarization.txt、prepare_diarization.py、diarization.py/worker；下载模型和manifest置于忽略目录，生产请求不联网。POST /diarization/run复用Java音频鉴权和完整性读取，支持自动估计或1–8人提示，返回SPK1起匿名时间段、明确identity_status=anonymous_only/not_saved/human_review。与STT共用单进程槽，限制10分钟、180秒；不识别成员姓名、不写入转写版本、不自动绑定文字。

新增前端diarization.js与SpeakerDiarization.vue，展示人数提示、匿名时间段、重叠/人工核对说明和明确错误；切换会议丢弃迟到结果。AudioTranscription中并列展示转写版本和分离预览。新增Python3项及浏览器5项，Python245/245、相关浏览器18/18、build、pip check与差异检查通过。

qa/run_diarization_local.py用官方公开四人中文样本验收：指定4人正确给出4个匿名聚类/10段，约12.69秒；自动人数误判为6人/10段，约13.16秒。由此维持“人数已知时手工输入、自动结果仅草稿”的产品边界，不宣称真实多人准确率。真实服务5ebd472958144ce0bbc0faf797fe8cb7以单人合成音频指定1人，页面得到1人/2段，并继续完成既有保存/确认/分析，1/1通过、speech_model_calls2、固定文本模型1、counts1/1/0/1/1。

本轮新增/修改集中于Python分离运行与下载、前端匿名预览、QA脚本/真实套件和文档；Java未改，日常库未动，未Git提交推送。下一步优先开发转写片段与匿名时间段的人工对齐，并在获得授权的真实多人录音上评估DER/文字归属；当前不自动合并两类输出。无新助手派工，本轮停止。

## 第六十八轮：转写片段与匿名时间段人工对齐（2026-10-02）

前端将同音频的STT与分离结果在AudioTranscription父组件汇合，TranscriptVersions按最大时间重叠为每个S片段预填SPK标签。用户可逐项选择其他匿名标签或未知；确认前仍可编辑全文。分离人数改变或会议切换会清空预览，对齐不会沿用旧聚类。刷新保留转写版本和已确认对齐；未确认的分离预览当前不持久化，需重新计算。

Java确认载荷新增可选speaker_alignment，不改表结构。服务端保存引擎、音频哈希、时长、说话人数、完整turn快照和按转写片段顺序的assignments；核对来源音频、持续时间、匿名标签序列、实际标签集合和全部片段覆盖。成员姓名、伪造标签、错误哈希/引擎/片段均422。对齐与确认、新分析会议同事务，原草稿speaker_id继续为null，纯文本确认向后兼容。

新增Java1项对齐契约并扩展前端版本测试；会议相关Java14类179/179、package通过，页面分离+版本12/12。真实链路8799a5209dc5402b8bb1b1cdbd39a43f完成STT、两次实际分离、保存/刷新、人工对齐、确认和Daily分析，1/1通过，counts1/1/0/1/1，speech操作3、文本夹具1，无故事执行。文档及QA证据同步更新。

下一步使用获授权真实多人会议样本评估DER、预填准确率和重叠发言体验，再决定是否持久化未确认分离预览；当前不做成员身份识别。本轮完成后按用户授权整理Git提交并同步远端。

## 第六十九轮：分离预览随转写版本持久化（2026-10-02）

保存转写版本时一并保存当前同音频的匿名分离快照，包含固定引擎、时长、人数提示、人数、anonymous_only状态和完整turns。刷新后从不可变版本恢复时间段及按重叠计算的SPK预填，不再重复运行分离模型。版本内容哈希包含快照；同草稿但不同分离结果会形成不同版本。

Java严格校验快照字段、同一时长、1–8人数提示、1–32实际标签、连续SPK编号及时间范围。若版本已经保存快照，确认中的引擎、时长、人数和turns必须完全一致；assignment仍由人工逐片段选择。旧版不含快照的转写版本及纯文本确认继续兼容。

TranscriptVersionTest由8项增至10项并通过，Java21 package成功；转写、分离与版本前端相关19/19、production build通过。真实链路4c7199c866214fffbc29edbde8443401完成一次STT、一次分离、保存刷新、无重算恢复、对齐确认和Daily分析，浏览器1/1、counts1/1/0/1/1，speech2、文本夹具1。下一步使用获授权真实多人样本评估DER、转写片段归属和重叠发言体验。

## 第七十轮：官方四人样本完整页面闭环（2026-10-02）

真实Transcription套件支持显式语言及1–8人提示，同一用例可运行英文单人或中文多人音频。用官方公开四人中文MP3实际执行上传、中文STT、指定4人分离、版本保存、刷新恢复、按时间重叠预填、人工确认和Daily分析。最终0535c057b46449ff901c0d026ef626e9浏览器1/1，speech2、文本夹具1、counts1/1/0/1/1。

56.861秒音频产生12个转写片段和10个分离时间段；12/12片段得到非空预填，SPK1–SPK4均出现在预填及确认中。result.json新增语言、人数、片段覆盖和标签集合，供后续回归读取。样本缺少逐毫秒说话人真值和逐句身份标注，所以这里只证明端到端覆盖与持久化，不能声称归属准确率或DER；中文转写有明显错误，仍需人工核对。下一步优先改善长转写片段跨说话人边界的问题，避免单个STT片段只能选择一个SPK造成信息损失。

## 第七十一轮：跨说话人片段保留完整重叠集合（2026-10-02）

由于现有Whisper只提供段级时间戳，本轮没有按比例切分文字。新`alignment_version: 2`在每个assignment中同时保存人工主说话人和全部`overlapping_speakers`，页面明确提示跨说话人片段。Java依据不可变turns和STT片段起止时间重算集合并严格比对，拒绝漏报/伪造；version 1历史确认保持兼容。

Java TranscriptVersionTest 10/10、package成功；前端相关19/19、build成功。官方四人真实链路ec2b059fbf13410c965a07fbaca9fc09通过1/1，12段中4段跨说话人，完整集合及主标签随确认落库；result新增multi_speaker_segments=4，speech2、文本夹具1、counts1/1/0/1/1。下一步引入词级时间戳并验证其单调性/全文一致性，再按真实词边界拆分，不能用时间比例猜测文字归属。

## 第七十二轮：词级时间戳三层校验与版本持久化（2026-10-02）

faster-whisper启用word_timestamps，每个段返回连续词ID、起止时间和原始词文本。Python验证词时间单调且位于段内、拼接等于段文本；前端接收时复验；Java保存不可变版本时再次复验。旧版不含words的草稿继续兼容。本轮先建立可信词级依据，没有自动切分文字。

Python245/245；TranscriptVersionTest扩至11/11并package成功；转写/版本浏览器15/15，前端build成功。官方四人最终d509f15259f64be2a141aa4645a38fe6通过1/1：12段、95词级时间戳、2段跨说话人、SPK1–4完整覆盖、speech2、文本夹具1、counts1/1/0/1/1。下一步基于已保存词边界生成可人工调整的子段，并由Java验证每个词恰好覆盖一次。

## 第七十三轮：词边界对齐子段与完整覆盖校验（2026-10-02）

alignment v3按每个词与匿名时间段的最大重叠分配主标签，再合并相邻同标签词；页面以SxAy子段让用户逐项调整。确认保存word_ids、原样文字、主标签和重叠集合。Java验证所有词按原顺序恰好覆盖一次，并拒绝删除、重复、跨段或改字。无词时间戳的版本回退v2，历史v1兼容。

Java12/12+package、浏览器15/15、build通过。官方四人最终9d66228b0619435ab48d7745bfe68859通过1/1：12原段/95词生成13子段，S10在真实词边界拆为2段，95词完整落库；仍有1个词级单元跨两位SPK并明确提示。speech2、文本夹具1、counts1/1/0/1/1。下一步让用户在页面合并/拆分相邻词组，同时保持Java的全覆盖约束。

## 第七十八轮：真值驱动的WER、DER与词对齐覆盖率

新增`ai-service/evaluate_speech.py`离线入口，读取明确人工标注的reference及现有转写、分离JSON产物。中英混合文本按汉字/英文单词规范化后计算替换、删除、插入与总错误率；匿名SPK标签依据时间重叠求一对一最优映射，再计算漏检、误检、混淆和DER；同时报告合法词时间戳、录音时间并集及说话人时间段交集覆盖率。三份文件必须使用完全相同的解码时长，错误输入不产生部分报告。

本轮只建立可复现评测方法，没有伪造准确率：现有官方四人样本缺少逐字文本和逐毫秒说话人真值。样例、口径和使用命令见`docs/会议Agent_语音质量评测.md`。10项定向单测通过，含API允许的32说话人上限；项目`.venv`引用的本机Python 3.11已被删除，因此本轮无法复跑依赖FastAPI/LangGraph的全量集。下一步先准备许可明确的2–5段中文多人会议人工真值，再产出首份真实基线并据此设置门槛。
