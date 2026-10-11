# H02 Planning 盘点交接

状态：`ready`  
完成日期：2026-09-22  
基线：`main@bb15fc7` + 当前工作区本地会议 Agent 未提交成果；Daily Skill `daily-status-v8`。

## 交付结果

- `api-map.md`：整理了 Story、Task、成员、画像/活动、Sprint 表达、普通写接口、Daily 受审链、旧建议链及 Python Tool，并给出真实路径和行号。
- `gap-matrix.md`：按“已具备 / 可复用但语义不一致 / 缺失 / 待主 AI 决策”分类；明确六周容量不等于 Sprint 容量、Story 估时缺失、Story/Task Sprint 口径分离。
- `scenarios.json`：8 个非正式契约的 Planning 合成场景，覆盖无当前 Sprint、容量未知、估时缺失、负责人歧义、跨 Sprint 不一致、依赖冲突、普通 PATCH 边界和 viewer 权限。

本包只写 `qa/assistant-work/H02-planning-inventory/` 与助手状态文件；没有修改生产源码、正式测试、配置、依赖、数据库或主文档，没有调用真实模型，也没有启动服务或连接数据库。

## 核心结论

1. 当前可直接复用的主要是鉴权读取模式、Story/Member Python 只读适配器，以及 Daily 的“不可变分析 → 人工审核 → 只执行批准载荷 → 冲突检查/审计”结构。
2. Planning 仍缺 Sprint 权威模型、Story 估时、Sprint 剩余容量、Task 只读 Tool、Planning 契约/Skill，以及分配/排期类受审执行器。
3. `Story.sprint` 与由 Task 周次派生的 Sprint 是两个可能不一致的口径；当前前端只告警，不自动修正（`java-backend/src/main/java/com/aicap/dto/TaskDtos.java:14-17,39-50`；`frontend/src/stores/project.js:350-375`）。
4. `capacity_hours` 是六周总容量（`java-backend/src/main/java/com/aicap/entity/User.java:19-20`）。前端 `/6` 周容量和画像负载百分比均不是 Planning 权威算法（`frontend/src/views/MembersView.vue:73-92`；`java-backend/src/main/java/com/aicap/service/ProfileAgentService.java:898-906`）。
5. 普通 Story/Task PATCH 调用即执行，不能因为调用者具备 writer 权限就当作 Agent 已获批准（`java-backend/src/main/java/com/aicap/controller/StoryController.java:127-163`；`TaskPoolController.java:198-350`）。

## 最小依赖链

1. **确定或显式保留基础语义**：当前 Sprint 来源、日期/Goal、估时权威层级、容量口径、可分配成员规则。
2. **只读 Planning 上下文**：聚合会议、Story、Task、Member，并让未知 Sprint/估时/容量保持未知；Agent 不直连数据库。
3. **Planning Skill 与候选输出契约**：只允许已确定的动作白名单；所有项目写入保持待审。
4. **Java 持久化审核链**：保存分析快照和证据，逐项或按已决定的批量粒度审核，持久化批准载荷。
5. **专用执行器**：锁定目标、比较 expected 快照、只执行批准载荷、保证幂等并写审计。
6. **前端审核与执行状态**：展示未知信息、冲突和部分批准，不绕过服务端权限。

## 建议先实现的一项

建议主 AI 先实现**只读 Planning 输入上下文契约与 TaskReadTool**，暂不实现任何写动作。原因：

- StoryReadTool 与 MemberReadTool 已有传输、鉴权和严格响应校验样例（`ai-service/meeting_agent/story_tool.py:28-99`；`member_tool.py:55-116`）。
- Task 数据已能从 `GET /api/tasks` 读取依赖、周次、负责人和两种工时（`java-backend/src/main/java/com/aicap/controller/TaskPoolController.java:192-195`；`java-backend/src/main/java/com/aicap/dto/TaskDtos.java:19-32`）。
- 契约可先把 `current_sprint`、Sprint Goal、Story estimate、Sprint capacity 明确表示为未知/待提供，不必提前决定容量公式或数据库结构。

阻塞这一项进入“可计算排期”的事实缺口：

- 无权威 current Sprint、起止日期和 Goal；
- Story 无估时；
- `hours` 与 `estimated_hours` 的 Planning 语义未定；
- 只有六周容量，没有 Sprint 剩余容量；
- Story Sprint 与 Task 派生 Sprint 冲突时没有权威优先级。

因此该首项只能交付可信上下文和待确认信息，不能声称已具备自动装载 Sprint 或人员分配能力。

## 需要主 AI 决定

1. Sprint 权威来源和 current Sprint 的校验方式。
2. Story 估时是否新增字段，或是否从 Task 汇总；`hours/estimated_hours` 各自语义。
3. 六周容量到 Sprint 可用/剩余容量的规则，以及请假、已消耗与跨 Sprint 任务如何处理。
4. 首版 Planning 动作白名单与审核粒度：Story 分配/移动/估时、Task 创建/排期中哪些先做，批量计划整体还是逐项批准。
5. Planning 并发检查使用字段 expected 值还是实体 version。
6. viewer 是否可发起只读分析，以及角色为 viewer 的账号是否永不进入候选分配集合。

## 未完成项与验证边界

- 未定义 Planning 正式请求/响应或动作 JSON；`scenarios.json` 明确是验收素材。
- 未访问真实数据库，因而没有核验当前数据是否存在 null、旧迁移值或线上不一致。
- 未启动 Java/Python/前端服务，没有做运行时接口或权限联调。
- 未运行真实模型，没有验证 Planning 推理质量。
- 未实现代码、测试、表或接口；H03 未领取。

## 读取文件与 SHA-256

以下是本包读取并用于结论的来源快照。核心来源在交付前复算，与记录值一致；未发现“基线变化，需要复核”。

```text
b31dbb8eb2f938451a3084eae914accc855a66e4a4a18030d31c9a6ba57fd30c  docs/会议Agent_助手H01接收与H02安排.md
c2d314e108f57faf51ccc19310f3b714dd1d57c680d6972e1bc727889fa181da  docs/会议Agent_助手并行任务书.md
fa89d150aaf084d9789aaeba2ac24a8915facdfe54dc0c8642e9641695d54f7f  java-backend/src/main/java/com/aicap/entity/Story.java
4b2789a9cc42c3b486e23bded33e96c760a4d955a0d6fc1f2feda1fcac5f1c07  java-backend/src/main/java/com/aicap/dto/StoryDtos.java
2c3c010dabab5e1e6dafc8765a04db78434ed74b012a035b2597017bdc265fae  java-backend/src/main/java/com/aicap/controller/StoryController.java
777151b474bf30aada36e71fd94f7c6c171c746a1d6990872d610d8c2c746282  java-backend/src/main/java/com/aicap/entity/Task.java
672833d8644143d5581090338a139cec8004ee05598484a61a0a13d0c7dfdbf3  java-backend/src/main/java/com/aicap/dto/TaskDtos.java
869218fbf44783e563b8ae113c9dad7f561955f8b50f27d5c73e39f0513eb9e0  java-backend/src/main/java/com/aicap/controller/TaskPoolController.java
753e34e145a577ef357dd55ff0e3762187d567a883f4dc730ba95ead0d69097a  java-backend/src/main/java/com/aicap/entity/User.java
8c996ee6e3d9dd55d5582f1d63bcd2bf259e821db9973e4195229269c2cf33b9  java-backend/src/main/java/com/aicap/dto/AuthDtos.java
9f3106da87198bb05499ee5f7ff8ac436b8fcf168e47751fba843b10010474e0  java-backend/src/main/java/com/aicap/controller/AuthController.java
f3b097a287ce6090f130080907e40d84c3bdadd7b0a159ed29dfa18c2810d74f  java-backend/src/main/java/com/aicap/dto/MemberProfileDtos.java
48d2017ec81429b7e5602216efdc4e3e499168aa81a87cdfd79135c07a1d6a0f  java-backend/src/main/java/com/aicap/controller/MemberProfileController.java
e8b8fd8e8ef3877c0f6b5ec52660943cff61ff79d483ea30bccbe9f34bc02523  java-backend/src/main/java/com/aicap/controller/ProfileAgentController.java
a2465b9fddb65ed00af14510b3438f77a851b584bad7cc9b57f72a6460c30910  java-backend/src/main/java/com/aicap/security/Roles.java
7a33b0e6f09c8a87ae9c5d1f8f57c839c185ea9c7e97da85dbed1ebde1ef7a94  java-backend/src/main/java/com/aicap/dto/StatusAnalysisDtos.java
afd648372fd99f6b6a17975cb30314f422ad76ef07b9355f35c086e7deddeb6e  java-backend/src/main/java/com/aicap/controller/StatusAnalysisController.java
9ed41a008d2982ad16581b58cf14b7603f98af514fef17bf59df0b77086bd63b  java-backend/src/main/java/com/aicap/controller/StatusProposalReviewController.java
5a7a528973912ad6652e17cc983426771f311298dfdc8f819cde1a86ceb57a17  java-backend/src/main/java/com/aicap/controller/StatusProposalExecutionController.java
9a6b83003159d6e63c884be7c4e238f67690ba2429dd8067c6a50f2dad3090cc  java-backend/src/main/java/com/aicap/service/StatusAnalysisService.java
8a63c19edcf12b46c06719a81375f6fb849cb514d6cdb5e19b9be59862b2d08f  java-backend/src/main/java/com/aicap/service/StatusProposalReviewService.java
69037a78a94ca7dceee02e178431102e150e64694aab62ef5c10be776d398c7f  java-backend/src/main/java/com/aicap/service/StatusProposalExecutionService.java
db66b8b9a1c9267ddf8d3617554343058ee8d666c9d73b0f9de4f739468fe16c  java-backend/src/main/java/com/aicap/service/ProfileAgentService.java
303de998809459a5d6abb4182ef4e296aa787a7592f304645bf2011cbc45d700  java-backend/src/main/java/com/aicap/controller/MeetingController.java
5dbee0e7e7d39b8bf92f069d2badfedb98959811bbe77332784be82f33d45cd5  java-backend/src/main/java/com/aicap/dto/MeetingDtos.java
10fe7593a313a2b17d2d9c412cf5f1d30db434c33631b8c8a985e582f5ab900f  java-backend/src/main/java/com/aicap/service/MeetingService.java
07a5b6a4b41d6dbd1a755fd49804b184040f203759154aba60b27331865b7ab2  java-backend/src/main/resources/db/status-analyses.sql
35ea50de996938b6962e28d36a9bde3217c5f5663e8debefedb6f3d022b0891d  java-backend/src/main/resources/db/status-proposal-reviews.sql
e0989e4bcb93c4f1aee6b6e76e55dd6425912d123114a3a7c34f8620343beed3  java-backend/src/main/resources/db/status-proposal-executions.sql
23a4741600e7c096daae2a54276d330936ecca6acc35a728ed488280b87fbe83  frontend/src/data/planCalendar.js
2676c14f4daa58f6945d9c22f25b3967f9b1174e1884d5ce3faa47785e1a9a8a  frontend/src/data/seed.js
1407c3389c87ed03c4f4fea119e9c1bce74e24fe93ace8c08b3c168562f820bc  frontend/src/stores/project.js
71dea29f259be512ccf433460d96a4041559e6933027068f03340701e6e1912c  frontend/src/views/MembersView.vue
87077e9d03d658e509ecce6d2faaae8e7e846b2cb4577fa19f2b090306157d0d  frontend/src/components/gantt/TaskEditorDialog.vue
283c306ec89dc9d593058421053e0b9f5c2e3103ecce4e78541ccfa47c445be6  ai-service/meeting_agent/contracts.py
bace7ad66f639c9920b9d1b135e25c8a6e86dbb0317140298b0971b8b064c304  ai-service/meeting_agent/story_tool.py
256ccfaffc817756d25b51c6c915917201e6221318f0e327d5a561bc47999b3e  ai-service/meeting_agent/member_tool.py
45bc6711e123d31b203059c55f11984ab747de61d3203525adffeaaaf9d06d0b  ai-service/meeting_agent/api.py
2f67541ca24e33af2fe8d61a07478184b05d986c596fb39507e6577e27cf709c  ai-service/meeting_agent/workflow.py
eeefebfaf39a4caa5da588e118888a233f4c318e376770aa22e25427d09fd7cc  ai-service/meeting_agent/daily_skill.py
```

## 改动文件

- `qa/assistant-work/H02-planning-inventory/api-map.md`
- `qa/assistant-work/H02-planning-inventory/gap-matrix.md`
- `qa/assistant-work/H02-planning-inventory/scenarios.json`
- `qa/assistant-work/H02-planning-inventory/handoff.md`
- `qa/assistant-work/STATUS.md`（仅任务状态）

H02 现已冻结，等待主 AI 检查与集成。
