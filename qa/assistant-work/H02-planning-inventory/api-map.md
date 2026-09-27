# H02 Planning 现有 API 与数据映射

盘点日期：2026-09-22  
读取基线：`main@bb15fc7` + 当前工作区本地会议 Agent 未提交成果；Daily Skill 为 `daily-status-v8`。  
说明：本文件记录现状，不定义 Planning 正式契约。`writer` 指 `admin/owner/member`，`reviewer` 指 `admin/owner`，`any` 指任意已登录用户；实际守卫定义见 `java-backend/src/main/java/com/aicap/security/Roles.java:15-35`。

## 1. Planning 可读数据

| 功能 | 现有端点/实现 | 权限 | 当前字段 | Planning 结论 |
|---|---|---|---|---|
| 故事列表 | `GET /api/stories`；支持 `owner/sprint/status/activity/priority/q` 过滤（`java-backend/src/main/java/com/aicap/controller/StoryController.java:81-101`） | `any`（同文件 `:89`） | `id/title/description/acceptance/priority/sprint/activity/status/owner_id`（`java-backend/src/main/java/com/aicap/dto/StoryDtos.java:54-57`） | 可作为故事快照；没有故事估时、版本号、截止日期或 Sprint Goal（实体字段全集见 `java-backend/src/main/java/com/aicap/entity/Story.java:13-24`）。 |
| 任务列表 | `GET /api/tasks`，无过滤参数（`java-backend/src/main/java/com/aicap/controller/TaskPoolController.java:192-195`） | `any` | `id/name/owner_id/hours/week_start/week_end/story_ref/kanban_card_id/estimated_hours/task_type/depends_on/status/progress/blocked/sprints`（`java-backend/src/main/java/com/aicap/dto/TaskDtos.java:19-32`） | 可读取现有排期、负责人、依赖、状态与工时；`sprints` 是由 W1–W6 每两周派生，不是持久化 Sprint 关系（同文件 `:14-17,39-50`）。 |
| 成员基础信息 | `GET /api/auth/users`（`java-backend/src/main/java/com/aicap/controller/AuthController.java:66-70`） | `any` | `id/username/display_name/role/color/capacity_hours`（`java-backend/src/main/java/com/aicap/dto/AuthDtos.java:17-22`） | 可识别用户和角色；`capacity_hours` 定义为六周容量（`java-backend/src/main/java/com/aicap/entity/User.java:19-20`），不是 Sprint 剩余带宽。数据库空值在 DTO 中会被默认成 60（`AuthDtos.java:30-32`），因此响应值 60 不一定证明人工维护过。 |
| 成员画像 | `GET /api/members/profiles`（`java-backend/src/main/java/com/aicap/controller/MemberProfileController.java:29-32`） | `any` | 用户基础字段、`capacity_hours/title/tech_stack/capabilities/process_domains/summary/years_experience/updated_at`（`java-backend/src/main/java/com/aicap/dto/MemberProfileDtos.java:48-61`） | 可作为能力证据；没有 Sprint 可用时间、请假或剩余容量。角色为 `viewer` 也会出现在列表中，读取到成员不等于其可被自动分配。 |
| 活动事实 | `GET /api/profile-agent/activities?userId=&start=&end=`（`java-backend/src/main/java/com/aicap/controller/ProfileAgentController.java:63-70`） | `any` | 活动事实 DTO | 可作为经验或近期事实证据；活动数量不能直接换算为未来 Sprint 带宽。触发画像分析会落库并可能消耗模型额度，使用 `POST /api/profile-agent/analysis/run` 且仅 `writer`（同文件 `:98-121`），不属于 Planning 只读 Tool。 |
| 画像负载百分比 | 画像分析输出 `current_load_percent`（`java-backend/src/main/java/com/aicap/service/ProfileAgentService.java:826-851`） | 经画像分析接口读取 | 未完成任务 `hours` 总和 / 用户六周容量，容量无效时按 60，结果封顶 100（同文件 `:898-906`） | 只能作为既有画像展示指标。它不按 Sprint/周过滤、隐藏 100% 以上超载幅度且使用 `hours`，不能作为 Planning 的权威 Sprint 带宽。 |

## 2. Sprint、排期与估时的实际表达

| 对象 | 当前表达 | 证据 | 限制 |
|---|---|---|---|
| Story Sprint | `Story.sprint`，范围 1..4 | `java-backend/src/main/java/com/aicap/entity/Story.java:19-23`；创建/修改校验见 `java-backend/src/main/java/com/aicap/dto/StoryDtos.java:20-30,41-51` | 只有数字切片；无 Sprint 实体、日期、Goal、状态或当前 Sprint 标记。 |
| Task Sprint | 由 `week_start..week_end` 派生：W1–W2=S1、W3–W4=S2、W5–W6=S3 | `java-backend/src/main/java/com/aicap/dto/TaskDtos.java:14-17,39-50` | 不抄 Story.sprint；一个任务可跨多个派生 Sprint。 |
| Story/Task 一致性 | 前端发现任务派生 Sprint 未覆盖关联 Story Sprint 时只加 warning | `frontend/src/stores/project.js:350-375` | 不自动修复，也没有后端约束；Planning 必须把两者不一致当待确认事实，不能任选其一覆盖。 |
| 日期锚点 | 前端硬编码 `PROJECT_START=2026-08-31`、六周计划和每两周一个 Sprint | `frontend/src/data/planCalendar.js:28-43` | 这是展示层规则，不是后端权威 Sprint API；Sprint 4+ 被视为六周计划外、无排期（同文件 `:39-43,71-87`）。 |
| 截止周 | 前端优先取故事子任务最晚 `week_end`，否则取 Sprint 末周 | `frontend/src/data/planCalendar.js:77-87` | 推导的展示值，不是故事持久字段或已确认截止日期。 |
| Story 估时 | 无字段 | `java-backend/src/main/java/com/aicap/entity/Story.java:13-24` | 不能从优先级、活动编号或 Task 数量猜故事估时。 |
| Task 工时 | 同时存在 `hours` 与 `estimated_hours` | `java-backend/src/main/java/com/aicap/entity/Task.java:17-30`；输出见 `java-backend/src/main/java/com/aicap/dto/TaskDtos.java:19-31` | 两者业务语义未形成 Planning 权威约定；前端总负载使用 `hours`（`frontend/src/stores/project.js:133-139`），进度权重优先 `estimated_hours`（同文件 `:88-106`）。 |
| 六周总负载 | 所有任务 `hours` 求和 / 六周 `capacity_hours` | `frontend/src/stores/project.js:133-139` | 包含哪些任务状态、如何处理跨期任务由展示代码决定，不等于当前 Sprint 剩余承诺。 |
| 周负载 | 将任务 `hours` 在 `week_start..week_end` 均分，余数前置；周容量简单取六周容量 `/6` | `frontend/src/stores/project.js:58-64,147-154`；`frontend/src/views/MembersView.vue:73-92` | 仅前端展示公式；没有请假、工作日、已消耗工时或 Sprint 可用比例。 |

## 3. 普通业务写接口

这些端点是人工业务操作，不是 Agent 的受审写入口。

| 能力 | 端点 | 权限与请求字段 | 当前保护 | 为什么不能直接作为 Planning 审核入口 |
|---|---|---|---|---|
| 新建 Story | `POST /api/stories` | `writer`；`title/description/acceptance/priority/sprint/activity/status/owner_id`（`java-backend/src/main/java/com/aicap/controller/StoryController.java:104-124`；DTO `StoryDtos.java:20-38`） | 校验负责人，创建 story log | 没有提案 ID、审批决定、批准载荷、预期版本或执行幂等键。 |
| 修改 Story | `PATCH /api/stories/{storyId}` | `writer`；同 Story 字段均可选（`StoryController.java:127-163`；`StoryDtos.java:41-52`） | 直接更新并写 edit/move 日志 | `owner_id=null` 被当成未提供，不能清空负责人；未知字段被忽略（`StoryDtos.java:41-51`）。没有绑定 Agent 分析或人工批准记录。 |
| 修改 Task | `PATCH /api/tasks/{taskId}` | `writer`；`name/owner_id/hours/week_start/week_end/story_ref/kanban_card_id/estimated_hours/task_type/depends_on/status/progress/blocked`（`java-backend/src/main/java/com/aicap/controller/TaskPoolController.java:198-231`） | 校验成员/故事、周序、依赖存在、自依赖和环；状态与进度联动（同文件 `:234-295`） | 直接更新 Task（同文件 `:297-350`）；没有任务写审计、提案绑定、批准载荷、expected version 或执行幂等。当前也没有 Task POST 创建端点。 |
| 需求池提升 Story | `POST /api/pool/{poolId}/promote` | `writer`；需要 `sprint/activity`，`owner_id` 可选（`TaskPoolController.java:387-419`） | 校验需求池项和负责人，创建 Story 日志 | 是直接业务写；不是 Planning 提案审核。提升时自动补“待补充”描述/验收，不能冒充 Planning 已确认内容（同文件 `:400-418`）。 |
| 删除 Story | `DELETE /api/stories/{id}?undone=cancel|keep|detach` | `reviewer`（`StoryController.java:166-198`） | 按策略处理子任务并写日志 | 敏感权限不等于 Agent 审批链；仍无分析/提案/批准载荷绑定。 |

## 4. 可借鉴的受审写链

### 4.1 Daily 状态提案链（当前本地成果）

| 阶段 | 端点 | 权限 | 已实现边界 |
|---|---|---|---|
| 保存不可变分析 | `POST /api/meetings/{meetingId}/status-analyses` | `writer` | 控制器见 `java-backend/src/main/java/com/aicap/controller/StatusAnalysisController.java:13-20`。只接受 `daily_scrum`、动作 `update_story_status`、状态 0..2 和原文证据（`java-backend/src/main/java/com/aicap/dto/StatusAnalysisDtos.java:12-28`）；验证会议、证据、故事快照与请求幂等后保存，不执行项目变更（`java-backend/src/main/java/com/aicap/service/StatusAnalysisService.java:53-101`）。 |
| 查看分析 | `GET .../status-analyses`、`GET .../{analysisId}` | `any` | `StatusAnalysisController.java:22-36`。按请求号查询仅允许提交者且要求 `writer`（同文件 `:28-30`）。 |
| 审核单条提案 | `POST .../{analysisId}/proposal-reviews` | `reviewer` | `StatusProposalReviewController.java:12-22`。决定为 approve/modify_and_approve/reject；审核记录不可被不同载荷覆盖，并再次检查故事状态（`StatusProposalReviewService.java:25-40,66-100`）。 |
| 执行批准载荷 | `POST .../{analysisId}/proposal-executions` | `reviewer` | `StatusProposalExecutionController.java:11-21`。请求只能含 `proposal_id`；只执行持久化的批准载荷，锁分析/审核/Story，比较 expected 状态，原子更新故事、写 story log 和执行记录；重复执行返回既有结果（`StatusProposalExecutionService.java:21-25,46-82`）。 |

持久化边界也可复用为设计参考：分析按 `(meeting_id, submitted_by, client_request_id)` 唯一（`java-backend/src/main/resources/db/status-analyses.sql:2-14`）；每个分析数组位置只能有一个最终审核（`status-proposal-reviews.sql:1-16`）；执行按 `(analysis_id, proposal_index)` 唯一且记录前后状态和日志 ID（`status-proposal-executions.sql:1-13`）。

**不能直接复用为 Planning 正式入口的部分**：DTO 和服务把会议类型固定为 `daily_scrum`、动作固定为 `update_story_status`，批准载荷只有 `changes.status`（`StatusAnalysisDtos.java:12-28`）。Planning 所需 Sprint、估时、负责人、任务排期等动作尚无对应契约和执行器。

### 4.2 旧会议建议链

`POST /api/suggestions`（`writer`）与 `POST /api/suggestions/{id}/review`（`reviewer`）见 `java-backend/src/main/java/com/aicap/controller/MeetingController.java:91-125`。输入动作被固定为 `pool.create`，批准后直接创建需求池项（`java-backend/src/main/java/com/aicap/dto/MeetingDtos.java:42-75`；`java-backend/src/main/java/com/aicap/service/MeetingService.java:290-320`）。它可说明幂等和人工审核模式，但不能表达 Planning 的 Story/Task 分配、Sprint 或估时变更。

## 5. Python Meeting Agent 可复用 Tool

| Tool/入口 | 当前行为 | Planning 可复用性 |
|---|---|---|
| `StoryReadTool.get_stories` | 固定访问 `GET /api/stories`，携带调用人令牌，只投影 `id/title/status/sprint/owner_id`，严格拒绝坏行/重复 ID（`ai-service/meeting_agent/story_tool.py:28-99`） | 传输与鉴权边界可复用；当前投影缺 description、acceptance、priority、activity，且 `StorySnapshot` 没有估时（`ai-service/meeting_agent/contracts.py:27-35`）。 |
| `MemberReadTool.get_member_profiles` | 固定访问 `GET /api/members/profiles`；将 `capacity_hours` 显式改名为 `six_week_capacity_hours`，注释禁止解释为剩余/Sprint 容量（`ai-service/meeting_agent/member_tool.py:55-116`） | 能力画像读取可复用；尚未接入工作流，且不计算 Sprint 带宽、不根据角色过滤候选人。 |
| `POST /api/meetings/{meeting_id}/analyze` | 请求类型只允许 `daily_scrum`，`current_sprint` 由调用方可选传入（`ai-service/meeting_agent/api.py:20-24,81-92`） | 当前不是 Planning 入口；没有 `sprint_planning` 工作流或输入/输出契约。 |
| `DailyScrumInput.current_sprint` | 显式输入，可为 null；注释说明尚无 current-Sprint API（`ai-service/meeting_agent/contracts.py:37-44`） | 只证明当前缺口被显式保留；不能用它推断 Planning 的权威 Sprint。 |

## 6. 当前没有的端点/模型

- 没有 Sprint 实体/表，也没有获取当前 Sprint、Sprint 日期、Sprint Goal 或 Sprint 状态的权威后端端点。
- 没有聚合 Story + Task + Member + Sprint 的 Planning 上下文 Tool。
- 没有 Task 创建端点。
- 没有 Story 估时字段或更新接口。
- 没有 Sprint 可用容量、请假、已消耗/剩余工时模型。
- 没有 `sprint_planning` Skill、工作流、分析保存契约、审核契约或执行器。
- 没有受审的 `assign_story_member`、`update_story_sprint`、`update_story_estimate`、Task 创建/排期/重分配动作。
