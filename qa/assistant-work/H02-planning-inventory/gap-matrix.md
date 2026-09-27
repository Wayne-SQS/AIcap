# H02 Planning 能力缺口矩阵

盘点基线：`main@bb15fc7` + 当前本地会议 Agent 未提交成果，2026-09-22。  
判定口径：`事实`是代码可直接确认的现状；`推断`是基于现状对 Planning 可复用性的判断；`缺口`表示当前代码不存在该能力；`待决策`保留给主 AI/产品，不在本包定义方案。

## A. 已具备

| 能力 | 类型 | 结论与代码证据 |
|---|---|---|
| 鉴权后的 Story 只读 | 事实 | `GET /api/stories` 对任意登录用户开放并支持常用过滤（`java-backend/src/main/java/com/aicap/controller/StoryController.java:81-101`）；输出含 Story 基础字段（`java-backend/src/main/java/com/aicap/dto/StoryDtos.java:54-57`）。 |
| 鉴权后的 Task 只读 | 事实 | `GET /api/tasks` 返回全部任务（`java-backend/src/main/java/com/aicap/controller/TaskPoolController.java:192-195`）；包含负责人、工时、周次、故事引用、依赖、状态、进度和阻塞（`java-backend/src/main/java/com/aicap/dto/TaskDtos.java:19-32`）。 |
| 成员与能力画像只读 | 事实 | `GET /api/members/profiles` 对登录用户开放（`java-backend/src/main/java/com/aicap/controller/MemberProfileController.java:29-32`），输出角色、容量、画像三维和经验（`java-backend/src/main/java/com/aicap/dto/MemberProfileDtos.java:48-61`）。 |
| Python Story 只读适配器 | 事实 | `StoryReadTool` 固定访问 Java `/api/stories`，不接受模型控制 URL，并校验规模、字段及重复 ID（`ai-service/meeting_agent/story_tool.py:28-99`）。 |
| Python Member 只读适配器 | 事实 | `MemberReadTool` 固定访问 `/api/members/profiles`，严格校验并将容量命名为 `six_week_capacity_hours`（`ai-service/meeting_agent/member_tool.py:19-29,55-116`）。 |
| Task 依赖数据及环校验 | 事实 | Task 有 `depends_on`（`java-backend/src/main/java/com/aicap/entity/Task.java:33-40`）；普通 PATCH 校验依赖存在、自依赖与环（`java-backend/src/main/java/com/aicap/controller/TaskPoolController.java:265-280`）。 |
| 角色守卫 | 事实 | `writer=admin/owner/member`、`reviewer=admin/owner`、`any=任意登录用户`（`java-backend/src/main/java/com/aicap/security/Roles.java:15-35`）。 |
| 可借鉴的人工审核/执行闭环 | 事实 | Daily 分离了不可变分析、最终审核和批准载荷执行（`java-backend/src/main/java/com/aicap/service/StatusAnalysisService.java:53-101`；`StatusProposalReviewService.java:66-100`；`StatusProposalExecutionService.java:46-82`）。 |
| 并发与重复执行保护样例 | 事实 | Daily 链使用行锁、expected 状态比较、审核唯一键和执行唯一键；重复执行返回已有结果（`StatusProposalExecutionService.java:49-82`；`java-backend/src/main/resources/db/status-proposal-reviews.sql:1-16`；`status-proposal-executions.sql:1-13`）。 |

## B. 可复用，但语义不一致或不足

| 能力/字段 | 类型 | 可复用部分 | 不能直接沿用的原因 |
|---|---|---|---|
| Story 的 `sprint` | 事实 + 推断 | 可表达故事当前归属数字切片（`java-backend/src/main/java/com/aicap/entity/Story.java:19-23`）。 | 只有 1..4 数字，没有 Sprint 日期、Goal、状态或“当前”标记；不能由某故事的 sprint 推断当前 Sprint。 |
| Task 的 `sprints` | 事实 + 推断 | 可由 `week_start..week_end` 判断现有任务覆盖哪些两周切片（`java-backend/src/main/java/com/aicap/dto/TaskDtos.java:14-17,39-50`）。 | 它是派生值且只覆盖 W1–W6；与 Story.sprint 可不一致，前端仅告警（`frontend/src/stores/project.js:350-375`）。 |
| 前端项目日历 | 事实 + 推断 | 提供当前 UI 的项目起点、周次和展示截止周规则（`frontend/src/data/planCalendar.js:28-43,53-87`）。 | `PROJECT_START=2026-08-31` 是前端硬编码，后端和 Tool 无权威来源；不可提升为 Planning 契约。 |
| Task `hours` 与 `estimated_hours` | 事实 + 待决策 | 两个字段均可读写（`java-backend/src/main/java/com/aicap/entity/Task.java:17-30`；`TaskPoolController.java:217-231,303-326`）。 | 前端负载用 `hours`，进度权重优先 `estimated_hours`（`frontend/src/stores/project.js:88-106,133-139`）；两者谁代表 Planning 承诺估时尚未确定。 |
| 六周容量 | 事实 + 推断 | `User.capacity_hours` 明确定义为六周容量（`java-backend/src/main/java/com/aicap/entity/User.java:19-20`），成员 Tool 保留该语义（`ai-service/meeting_agent/member_tool.py:106-111`）。 | 不是 Sprint 总容量或剩余容量；没有请假、工作日、已消耗工时和当前 Sprint 范围。 |
| 前端周容量/负载 | 事实 + 推断 | 可用于理解当前页面显示：工时按周均分、周容量按六周容量除以 6（`frontend/src/stores/project.js:58-64,147-154`；`frontend/src/views/MembersView.vue:73-92`）。 | 这是展示公式，不是业务服务或已确认 Planning 算法；余数前置与简单 `/6` 都可能造成假精确。 |
| 画像 `current_load_percent` | 事实 + 推断 | 能反映未完成任务工时相对六周容量的粗略比例（`java-backend/src/main/java/com/aicap/service/ProfileAgentService.java:898-906`）。 | 不按 Sprint/周过滤，容量缺失按 60，结果封顶 100；不能区分已承诺、已消耗和剩余带宽。 |
| GitHub/活动事实 | 事实 + 推断 | 活动可按成员和日期读取（`java-backend/src/main/java/com/aicap/controller/ProfileAgentController.java:63-70`），可作为经验或近期工作证据。 | 代码活动量不等于未来可用工时，也不能替代成员明确承诺。 |
| Story 普通 POST/PATCH | 事实 + 推断 | 已有字段校验、负责人存在校验和 story log（`java-backend/src/main/java/com/aicap/controller/StoryController.java:104-163`）。 | 调用即执行，无提案/批准载荷/expected version/幂等绑定；不能暴露给模型充当受审 Tool。`owner_id=null` 也不能清空（`java-backend/src/main/java/com/aicap/dto/StoryDtos.java:41-51`）。 |
| Task 普通 PATCH | 事实 + 推断 | 已有周序、引用和依赖约束（`java-backend/src/main/java/com/aicap/controller/TaskPoolController.java:198-295`）。 | 直接更新且无任务变更日志、提案审批、expected version 或幂等执行记录（同文件 `:297-350`）。 |
| Daily 状态审核链 | 事实 + 推断 | 架构模式可复用：保存快照、逐项审核、只执行批准载荷、冲突检查和审计（`StatusAnalysisService.java:53-101`；`StatusProposalReviewService.java:66-100`；`StatusProposalExecutionService.java:46-82`）。 | 类型固定 `daily_scrum`、动作固定 `update_story_status`，批准变更只含 status（`java-backend/src/main/java/com/aicap/dto/StatusAnalysisDtos.java:12-28`），不能塞入 Planning 字段。 |
| 旧会议建议审核链 | 事实 + 推断 | 有提交幂等、人工审核和批准载荷留存（`java-backend/src/main/java/com/aicap/service/MeetingService.java:166-236,252-320`）。 | 动作只允许 `pool.create`（`java-backend/src/main/java/com/aicap/dto/MeetingDtos.java:42-75`），批准即创建需求池项，不支持排期/分配。 |
| Daily `current_sprint` 输入 | 事实 + 推断 | 调用方可以显式传值，null 被保留（`ai-service/meeting_agent/contracts.py:37-44`；`ai-service/meeting_agent/api.py:20-24,81-92`）。 | 没有验证该值来自权威 Sprint 数据；不能把 Daily 的调用参数当 Planning Sprint 服务。 |

## C. 当前缺失

| 缺失能力 | 类型 | 代码依据与影响 |
|---|---|---|
| Sprint 领域模型与权威查询 | 缺口 | Story 只有数字 `sprint`（`java-backend/src/main/java/com/aicap/entity/Story.java:19-23`）；Task Sprint 仅派生（`java-backend/src/main/java/com/aicap/dto/TaskDtos.java:14-17`）。仓库中无 Sprint 实体/控制器。Planning 无法可靠获得当前 Sprint、起止日期、Goal 或状态。 |
| Story 估时 | 缺口 | Story 字段全集无 estimate（`Story.java:13-24`），Story DTO 也无估时（`java-backend/src/main/java/com/aicap/dto/StoryDtos.java:20-57`）。无法计算候选故事是否装入 Sprint。 |
| Sprint 容量与剩余带宽 | 缺口 | User 只存六周容量（`java-backend/src/main/java/com/aicap/entity/User.java:19-20`）；前端只是展示公式（`frontend/src/views/MembersView.vue:73-92`）。没有请假、Sprint 可用时间、已消耗/剩余工时。 |
| Planning 聚合只读上下文 | 缺口 | Python 当前只有 Story Tool 和 Member Tool（`ai-service/meeting_agent/story_tool.py:28-119`；`member_tool.py:55-116`）；没有 Task Tool、Sprint Tool 或聚合 Project Context。 |
| `sprint_planning` 输入/输出契约与 Skill | 缺口 | MeetingType 枚举虽含 `sprint_planning`（`ai-service/meeting_agent/contracts.py:12-15`），但 API 请求和工作流只接受 Daily（`ai-service/meeting_agent/api.py:20-24,81-92`；`workflow.py:33-90`）。 |
| Planning 受审动作契约 | 缺口 | Daily DTO 只允许状态动作（`java-backend/src/main/java/com/aicap/dto/StatusAnalysisDtos.java:12-28`）；旧建议只允许 `pool.create`（`MeetingDtos.java:52-65`）。没有分配成员、移动 Sprint、更新估时、创建/排期 Task 的待审动作。 |
| Planning 执行器 | 缺口 | 当前受审执行器只更新 Story.status（`java-backend/src/main/java/com/aicap/service/StatusProposalExecutionService.java:62-81`）。普通 Story/Task PATCH 不能替代执行器。 |
| Task 创建 | 缺口 | `TaskPoolController` 的任务部分只有 GET/PATCH（`java-backend/src/main/java/com/aicap/controller/TaskPoolController.java:190-351`）；没有 POST `/api/tasks`。 |
| Task 写审计 | 缺口 | Task PATCH 直接 `updateById` 并返回（`TaskPoolController.java:297-350`），没有与 story_logs 对等的日志或 Planning 执行记录。 |
| 通用并发版本 | 缺口 | Story/Task DTO 均无 version/updated_at/etag（`StoryDtos.java:20-57`；`TaskDtos.java:19-32`）。Daily 仅对 status 使用 expected 值，不足以保护 Planning 同时修改 sprint/owner/estimate。 |
| 批量提案原子性定义 | 缺口 | Daily 按单个 proposal 审核/执行（`StatusProposalReviewService.java:66-100`；`StatusProposalExecutionService.java:46-82`）；没有定义 Planning 多故事/多任务安排应整体提交还是逐项执行。 |

## D. 待主 AI / 产品决策

以下不是本包的实现建议或默认答案。

| 待决策项 | 为什么必须先决定 | 相关事实来源 |
|---|---|---|
| Sprint 权威来源 | 需确定是新增后端 Sprint 模型、项目配置，还是由受信调用方显式提供；否则 Goal、日期和 current Sprint 均不可验证。 | 前端硬编码日历 `frontend/src/data/planCalendar.js:28-43`；Daily 由调用方传 current_sprint `ai-service/meeting_agent/contracts.py:37-44`。 |
| 容量口径与换算规则 | 需明确六周容量怎样映射到具体 Sprint，并纳入请假、已承诺、已消耗和跨 Sprint 任务；本包不采用 `/3`、`/6` 或任意比例。 | `User.java:19-20`；展示算法 `frontend/src/stores/project.js:58-64,133-154`。 |
| 估时权威层级 | 需决定 Story 是否新增估时，还是由 Task 汇总；还需明确 `hours` 与 `estimated_hours` 的含义。 | `Story.java:13-24`；`Task.java:17-30`。 |
| 可分配成员规则 | 需决定 viewer 是否永远不可分配、成员是否可声明不可用、候选人资格和人工确认方式。读取 profile 列表本身不会过滤 viewer。 | 画像输出含 role `MemberProfileDtos.java:48-61`；角色守卫 `Roles.java:15-35`。 |
| Planning 动作白名单 | 需明确首版允许新建 Story、移动 Sprint、更新估时、分配负责人、创建/修改 Task 中的哪些动作。 | 普通 Story/Task 写接口 `StoryController.java:104-163`；`TaskPoolController.java:198-350`，均非受审入口。 |
| 批量计划的审核粒度和事务边界 | 多个动作存在依赖时，逐条批准可能产生半套计划；整体批准又会扩大冲突面。 | 当前 Daily 为逐 proposal 最终审核和执行 `StatusProposalReviewService.java:66-123`；`StatusProposalExecutionService.java:46-90`。 |
| 并发冲突字段 | 需确定每种动作的 expected 快照：仅字段旧值、实体 version，还是分析级项目版本。 | Daily 只比较 Story.status `StatusProposalExecutionService.java:62-70`；Story/Task 无 version `StoryDtos.java:20-57`、`TaskDtos.java:19-32`。 |
| 能力画像与活动的使用边界 | 需确定哪些信息只作解释、哪些能参与候选排序，以及如何避免把活动量当容量。 | 画像读取 `MemberProfileController.java:29-39`；活动读取 `ProfileAgentController.java:63-70`；画像负载公式 `ProfileAgentService.java:898-906`。 |
| 未知值的表达 | 需在正式契约中区分“未提供”“确认为 0”“不适用”；当前部分 DTO 会把 null 默认成 60 或 0。 | 用户容量默认 60 `java-backend/src/main/java/com/aicap/dto/AuthDtos.java:30-32`；Task 输出把空 estimated_hours/status/progress 默认 0 `TaskDtos.java:53-62`。 |

## 最小风险结论

1. 当前可安全复用的是鉴权只读模式、Story/Member 传输适配器，以及 Daily 的“分析—审核—批准载荷执行—审计”结构。
2. 当前不能安全复用的是前端日历/负载公式、画像负载百分比和普通 PATCH；它们分别缺权威语义或缺审核边界。
3. 在 Sprint 权威来源、估时层级和容量口径确定前，Planning 只能生成显式含未知项的分析/待确认问题，不能声称完成可执行排期。
