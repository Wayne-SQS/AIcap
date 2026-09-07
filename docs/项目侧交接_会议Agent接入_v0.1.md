# 交给项目开发同学：会议 Agent 接入需求与协作约定

日期：2026-09-06
仓库：Wayne-SQS/AIcap，核对基线 486ecf3
状态：建议的 v0.1 对接契约；新增字段、接口和状态均待双方按此实现，不代表已经存在。

## 1. 本轮要交付什么

会议 Agent 尚未实现，由 Agent 负责人开发；请项目负责人补齐它所依赖的真实数据、建议存储、审核和执行能力。

共同目标：用户输入会议转写文本 → Agent 按需查询项目数据 → 生成摘要与带证据的变更建议 → 负责人采纳/修改/拒绝 → 后端执行 → 看板、甘特、成员负载读取更新结果 → 留下记录。

本轮以文本会议为输入，不以录音、语音转写、GitHub 集成或三个 Agent 同时完成为前置。现有五个课程辅助 AI 不需重新配置。

## 2. 谁负责什么

| 工作 | 项目负责人（你） | Agent 负责人 |
|---|---|---|
| Task、Story、Sprint、成员容量等业务数据 | 模型、迁移、查询与业务校验 | 说明查询需要，不自行另建业务库 |
| 会议/运行记录 | 持久化、接口、鉴权、任务触发及状态查询 | 输出运行结果与工具调用记录 |
| 会议智能分析 | 提供运行入口 | 提示词、模型适配、解析、工具选择、对象识别、建议生成 |
| 容量、版本、权限、事务 | 确定性计算与最终执行校验 | 调用结果，解释影响，不用模型代替计算 |
| 建议与审核 | 存储、列表、详情、采纳/修改/拒绝、执行服务 | 提供符合契约的待审建议 |
| 前端 | 会议输入、运行状态、结果、审核和视图刷新 | 提供展示字段和验收样例 |
| 测试 | 接口、权限、执行与前端测试 | Agent 固定案例和真实模型评测；共同联调 |

建议 Agent 作为现有 Python 后端内的独立模块交付，模块之间通过明确接口协作。初期无需额外部署独立 Agent 服务；如果双方确有独立部署需求，再把同一接口封装为 HTTP。

## 3. 仓库已有什么，缺什么

实际已挂载路由：

- POST /api/auth/login：JSON 用户名密码登录，返回 access_token。
- GET /api/auth/me、GET /api/auth/users：当前用户与成员列表。
- /api/stories：故事读写接口。
- /api/pool：需求池创建、读取、删除与移入看板。
- GET /api/tasks：只有读取，没有创建/修改接口。
- GET /api/dashboard：故事状态及 Sprint 汇总。

Story 当前状态为整数 0/1/2；Sprint 为 1–3；priority 为 Must/Should/Could。Task 当前有 name、owner_id、hours、week_start、week_end、story_ref，不能据此认为已有任务状态、真实日期或容量功能。

Suggestion 模型已存在，但没有挂载审核 API，前端审核为 localStorage 演示。请扩展这一实体，不要和 Agent 另建一套互不关联的 Proposal。

注意：前端展示的 US 编号可能是需求文档编号；运行数据故事 ID 使用 M01 等。Agent 必须查询返回的真实 ID，不能把 US02 直接当数据库主键。用户 ID 以 /api/auth/users 为准，不用前端数组下标。

## 4. 请优先提供的项目能力

### P0：可供 Agent 查询的真实上下文

1. 故事搜索及详情：真实 ID、标题、描述、验收条件、状态、负责人、Sprint、版本。
2. 任务查询：按成员/故事/Sprint 筛选，返回状态、工时、日期和版本。
3. 当前 Sprint：编号、开始日、结束日；没有当前 Sprint 时返回明确空值，不固定猜测 Sprint 1。
4. 成员容量：指定成员、指定周的可用工时和已分配工时、统计口径。
5. 需求池搜索：支持检查是否已有相似需求，但名称相近不能自动判为同一对象。

建议容量单位为“人/周/小时”；截止日期用 YYYY-MM-DD，会议时间明确时区（初始 Asia/Shanghai）。跨周任务工时分摊、已完成任务如何影响剩余容量须统一成服务端规则。无容量数据返回 unknown，不当作零或无限容量；容量为零时避免除零。

现有 API 能满足的直接复用。不支持的筛选/字段可新增；新增路径建议：

| 建议接口 | 用途 |
|---|---|
| GET /api/sprints/current | 当前迭代 |
| GET /api/members/{member_id}/capacity?week_start=YYYY-MM-DD | 容量与已分配工时 |
| POST /api/tasks | 通过业务服务创建任务 |
| PATCH /api/tasks/{task_id} | 状态、负责人、估算、日期等修改 |

### P1：任务数据与变更执行

Task 补状态、真实日期、明确故事关联、版本；复杂依赖编辑可后续做，但若有依赖数据则要校验。旧 week_start/week_end 的兼容转换由项目侧统一，不让 Agent 各自换算。

数据变更用迁移脚本，不能依赖 create_all 修改已有表。不要破坏现有种子数据和接口调用方。

至少支持三类建议动作：

- pool.create：创建尚未确定排期的新需求。
- story.update：修改明确定位故事的允许字段。
- task.create / task.update：创建任务或修改状态、负责人、日期等。

这些动作从统一业务服务执行；Agent 只提出建议，不能直接获得批准权限。缺少业务必填信息时存为待确认建议，不能编造后写入。

### P2：会议与建议持久化、审核中心

建议新增接口：

| 建议接口 | 用途 |
|---|---|
| POST /api/meetings | 保存标题、会议时间、原始转写和版本 |
| POST /api/meetings/{id}/runs | 触发分析，返回 run_id |
| GET /api/agent-runs/{id} | 查询进度、结果与错误 |
| GET /api/suggestions | 列表，可按会议/运行/状态筛选 |
| GET /api/suggestions/{id} | 证据、修改差异、影响与执行记录 |
| POST /api/suggestions/{id}/review | approve / modify_and_approve / reject |

创建分析任务及存储模型结果，由后端封装 Agent 模块完成；审核接口必须从登录态确定审核人，不能相信请求体里的 reviewer_id。现有项目多数写接口对所有登录成员开放，新审核能力需单独检查 admin/owner 权限。

运行状态建议：queued → running → awaiting_review，失败为 failed。审核状态与执行状态分开保存：review_status=pending/approved/rejected；execution_status=not_started/running/succeeded/failed/conflict。修改后采纳要保存原建议和编辑后的批准版本。

## 5. 共同数据契约 v0.1

Agent 的输入建议为：meeting_id、run_id、transcript_version、meeting_time、timezone、transcript_segments。登录身份和凭据由后端工具层维护，不交给模型。

会议片段至少包含 segment_id 和 text；speaker、start_ms 可为空。没有音频时间戳时以段落 ID 追溯。会议文本修改后增加版本，旧建议不得继续无提示执行。

下面是“拟新增需求”的建议示例，不是真实项目数据：

```json
{
  "schema_version": "0.1",
  "client_suggestion_id": "run-demo-001:1",
  "meeting_id": "meeting-demo-001",
  "run_id": "run-demo-001",
  "transcript_version": 1,
  "action": "pool.create",
  "target": null,
  "expected_version": null,
  "changes": {
    "title": "导出项目周报",
    "description": "支持导出项目周报",
    "priority": null
  },
  "evidence": [
    {"segment_id": "seg-03", "quote": "我们希望增加一个导出项目周报的功能。"}
  ],
  "impact": {
    "facts": [],
    "estimates": [],
    "warnings": []
  },
  "unresolved": ["优先级未明确"],
  "requires_review": true
}
```

服务端分配最终 suggestion_id、状态、审核人、时间和执行结果，不能相信模型自己填的 approved/succeeded。

pool 当前默认 Could，而示例 priority=null 表示会议未说明。审核时可以确认业务默认值，也可以要求补充；须记录来源为系统默认或人工选择，不能冒充会议决议。建议草稿可缺信息，正式实体仍按业务规则校验。

更新动作要求 target 包含类型与真实 ID，expected_version 对应查询时版本；修改前值由后端从该版本保存快照，不能只信模型生成的 before。最终影响在批准时重新计算。

Agent 返回分为两层：meeting_understanding（摘要/决议/行动项/风险/未决问题）与 suggestions。不要把会议事实与模型提出的调整混在一起。

## 6. 执行可靠性与联调约定

- 提交建议：对 run_id + client_suggestion_id 做唯一约束，重试提交不重复。重新分析是新运行，旧建议保留来源并提示可能重叠，不能盲目再次执行。
- 审核执行：同一建议的批准版本最多执行一次。重复批准返回已有结果，拒绝不改变 Task/Story/Pool。
- 并发：审核期间业务数据或会议文本有变，返回冲突并重新评估；批量批准不能绕过每条校验。
- 事务：业务写入、执行结果与审计一致；失败回滚。已完成动作的恢复需受版本检查约束，不能覆盖后续他人修改。
- 服务端只执行 action 白名单，不执行模型输出的 SQL、代码或任意 URL。
- 工具读权限绑定本次实际用户；凭据只由后端使用。只读工具应调用统一读取服务，内部同进程调用无需绕一圈 HTTP，也不能跳过权限。
- 前端明确展示分析中/失败/待审/已执行；在线异常不显示本地演示为真实保存成功。

## 7. 开发交付顺序

1. 双方固定上面的字段与动作，项目侧先给 OpenAPI/响应样例，Agent 侧同步用测试夹具开发。
2. 项目侧先交只读查询及容量计算，Agent 把假工具换成真实适配器。
3. 项目侧交建议存储、审核执行；先用人工构造的测试建议验证，不依赖模型。
4. Agent 交真实会议分析与工具调用模块，项目侧接运行入口和前端。
5. 共同验收一个真实闭环，再决定是否接音频。

项目负责人请回传：接口字段/状态枚举、容量计算口径、可用测试环境及非生产测试账号、只读接口和审核接口的预计交付顺序。密钥通过双方约定的安全渠道配置，不写入仓库或交接文档。

## 8. 共同验收清单

- 新需求没有 Sprint 时，经批准进入需求池。
- 缺负责人或日期时保留待确认。
- 会议指定成员过载时提示冲突，不擅自替换人员。
- 修改已有故事能看到真实 ID、前后差异、会议证据。
- 同名对象有歧义时不随意选择。
- 拒绝后业务数据不变，重复批准不重复创建。
- 数据/转写变更后检测旧建议冲突。
- 模型或查询失败可显示错误并重试，记录保留。
- 批准后刷新、其他客户端和相关视图的数据一致。

本文是交接与实现建议，不代表这些新增接口已经实现；本轮未复跑仓库测试。
