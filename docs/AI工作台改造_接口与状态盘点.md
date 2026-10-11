# AI 工作台改造：接口与状态盘点

盘点日期：2026-10-08（实施状态更新至 2026-10-09）。范围依据 `frontend/src/api`、会议分析组件及现行 Java API 适配器。此表记录前端已接入的契约；未找到独立详情 API 的来源以各自面板的列表/详情加载逻辑为准。审核中心现通过 `GET /api/review-queue` 获取七类来源的只读提案索引并分页加载详情；旧服务回退到原前端 adapter。

## 提案来源与状态矩阵

| 来源 / 提案类型 | 列表与详情 | 分析/保存 | 审核 | 执行 | 权限/边界 |
|---|---|---|---|---|---|
| 通用会议建议（Agent / 手动） | `GET /api/suggestions`；会议及 Agent 运行分别经 `/api/meetings` 与 `/runs` 加载 | `POST /api/suggestions`；会议 Agent 运行接口 | `POST /api/suggestions/{id}/review` | 批准手动/通用建议创建需求池条目；不是五类会议分析执行器 | Member 可提交，Admin/Owner 可审核；幂等键保留在提交载荷；离线记录只在本机 |
| Daily / Status | `GET /api/meetings/{id}/analyses` 与对应分析详情（由 `statusAnalyses.js` 适配） | `POST /api/meetings/{id}/analyze` | Daily 专属 proposal review API | Status 专属 proposal execute API | 会话归属、审核不可覆盖、执行幂等由服务端契约校验 |
| Sprint Planning | `GET /api/meetings/{id}/planning/analyses` 与详情 | `POST /api/meetings/{id}/planning/analyze` | Planning 专属审核接口 | Planning 专属执行接口 | 独立于 Planning Agent 项目规划能力 |
| Sprint Review | `GET /api/meetings/{id}/review/analyses` 与详情 | `POST /api/meetings/{id}/review/analyze` | Review 专属审核接口 | Review 专属执行接口 | 执行可能改变故事验收状态 |
| Sprint Retro | `GET /api/meetings/{id}/retro/analyses` 与详情 | `POST /api/meetings/{id}/retro/analyze` | Retro 专属审核接口 | Retro 专属执行接口 | 执行可能创建独立行动项 |
| Backlog Refinement | `GET /api/meetings/{id}/refinement/analyses` 与详情 | `POST /api/meetings/{id}/refinement/analyze` | Refinement 专属审核接口 | Refinement 专属执行接口 | 执行可能创建新故事 |
| Assignment 分配建议 | `GET /api/meetings/{id}/assignment-suggestions`，列表响应包含审核与执行状态 | 候选查询 `POST .../assignment/recommendations` 为只读；保存经 `POST .../assignment/suggestions` | `POST .../assignment-suggestions/{suggestionId}/review` | `POST .../assignment-suggestions/{suggestionId}/execute` | 查询明确 `writes_performed=false`；批准与执行分离，需确认容量并保留执行日志 |

## 共用状态约定

1. 分析 API 返回 `pending` 或 `no_changes`，不能把两者都显示成“已执行”。
2. 五类分析的建议审核/执行走各自专属 API。批准不会自动执行；执行状态须继续读取来源记录。
3. Assignment 的候选推荐为只读准备阶段；保存建议、审核、执行是后续独立步骤。
4. 通用建议的在线服务器状态与离线本机演示记录必须分开展示。前端角色提示不取代后端授权。
5. 深链需携带来源会议及分析类型，不能用通用审核页声称已覆盖专属提案。

## 本轮信息架构

- AI 工作台是目录与最近工作摘要；详细操作表单进入各自页面。
- 会议工作区分文本会议、语音会议、手动录入建议；五类分析用显式选择器呈现，Assignment 单独标识。
- 会议来源上下文使用 `meetingId` 路由查询参数；会议记录及业务产物仍只写入原服务端接口。
- 语音转写版本区按服务端持久化的 ID 展示原会议、音频、转写版本、确认人/时间与分析会议的来源链；分析会议以独立会议记录存在，源记录保持不变。
- 统一跨来源审核队列已增加服务端只读游标分页索引；索引从原来源实时读取，不复制审核决定。大规模查询成本仍需压测。

## 阶段 3 前端首版进度

- `frontend/src/api/reviewQueue.js` 已通过现有会议列表逐会议读取 Daily、Planning、Review、Retro、Refinement 的分析、审核及执行记录，并读取 Assignment 建议；审核中心再与旧版通用会议建议合并展示。
- 队列支持待审核、已审核、待执行/执行结果、来源、会议/提案文本和日期筛选；可展开查看来源提案与证据，并从会议提案深链进入对应会议、分析类型和分析记录。
- 从审核中心进入来源会议后会显示来源提示，并提供返回入口；返回时保留状态、来源、搜索词及日期筛选。
- 审核与执行动作仍在来源分析组件中触发现有 API，保留各类型的原始载荷验证、权限、审计、幂等和执行确认。通用会议建议继续在审核中心使用其原有审核接口。
- 每个来源读取失败会单独计数并提示“结果可能不完整”，成功来源数据仍可查看。队列详情读取全局最多 6 个并发请求；待审核或已拒绝提案不请求执行记录，执行记录接口失败时仍显示已读取的审核提案并单独计错。`GET /api/review-queue` 每页最多 100 条（默认 50），支持状态、来源、会议标题/提案编号、日期和游标筛选；旧服务回退到全量 adapter。定向 Playwright 回归覆盖跨来源完整性、并发上限和执行记录故障。
- AI 工作台在线模式使用 `GET /api/review-queue/summary` 统计七类来源的待审核、已批准待执行数量，并取得最近提案与最近会议；仅为最近提案加载详情。旧 Java 服务缺少该接口时回退到原只读 adapter，局部来源失败时提示计数可能不完整。通用建议继续入口定位到审核卡片；离线演示仍使用本机建议，不连接服务器。摘要不产生写操作。
- 队列以连续进度文案呈现分析、审核和执行阶段；拒绝/无需执行有中文解释，执行失败和冲突状态若由来源契约返回也会单独提示，操作仍需回到原来源。
- 阶段 4 的审核队列窄屏适配已补齐：筛选器改为自适应网格，输入控件限制在容器宽度内，提案证据可换行，窄屏提案字段纵向排列并保留清晰触控区域。
- 新增 Viewer/Member 权限与手机视口回归：两种角色可读跨来源和通用提案，但审核控件不显示，页面不发起审核写请求；390px 宽度下审核队列不横向溢出，筛选器单列显示，操作按钮高度至少 38px。
- 来源分析深链回归覆盖硬刷新恢复：会议 ID、分析类型、指定分析记录和审核返回入口均从 URL 恢复。
- Assignment 队列摘要直接展示目标故事、目标 Sprint、候选匹配数，并始终注明容量未验证；成功执行时显示故事日志号。
- 通用会议建议已纳入同一队列卡片并直接复用原审核/修改接口；审核后可打开来源会议，再返回保留当前状态、来源、搜索和日期筛选，避免队列和页面下方重复列出同一建议。
- Daily/Review 队列摘要按 `0/1/2` 映射“待办/进行中/已完成”，并使用空值判断保留 `0`；Sprint 变更摘要展示数字 Sprint。适配器回归覆盖 `0 → 1`、`1 → 2` 和 Sprint `1 → 3`。
- 五类会议分析详情共用执行状态文案，明确显示“执行失败”“执行冲突”“无需执行”；Retro 详情读取实际执行状态，不再把失败状态统称为“未执行”。
- 审核中心状态筛选单独映射待审核、已审核和执行结果；“已修改”纳入已审核，执行结果筛选只收录已批准记录或明确的执行终态，排除未审核及已拒绝的未执行记录。
- 会议工作流标签将 `audio` / `manual` 状态写入路由查询参数；刷新语音工作区后保留当前流程，并恢复已确认转写的来源链。
- AI 工作台会议能力卡片按当前会话标注“当前：在线服务/离线演示”，离线演示入口不会静态显示为在线服务。
- 会议工作流标签采用完整键盘交互：左右/Home/End 切换，焦点跟随当前标签，并通过 `aria-controls` / `aria-labelledby` 关联活动面板。
- 语音转写确认回归覆盖一次服务端不确定失败后的原载荷重试，确认两次请求完全一致、分析会议独立创建且来源会议文本保持不变。
- 会议分析面板在 Member/Viewer 有待审核或待执行事项时说明角色权限边界；跨流程队列统一使用“待审核”筛选名，并提醒无审核权限的账号由管理员/负责人处理。
- 前端复验：`npm run e2e:smoke` 12/12 通过（含队列并发上限、状态摘要、执行终态、审核中心筛选、语音工作流刷新/键盘导航、确认失败后幂等重试及工作台离线标识回归）；`npm run build` 通过。先前 Java 全量测试因 `127.0.0.1:3307/aicap_java_test` 不可用仅完成部分上下文用例；2026-10-09 在独立测试库上使用 Maven JDK 21 容器重跑，`mvn test` 350/350 通过，完整日志在 `java-backend/target/full-test-20261009.log`。期间修正 `ProfileAgentDocGapContractTest` 对另一测试方法执行顺序的依赖：比较测试自行写入过去时间窗的测试活动。
- 2026-10-09 在线服务验收：修正真实浏览器用例，使其进入当前 `/#/meetings` 会议工作区，并切换对应分析类型/语音标签后再操作。Assignment、Daily、Planning、Review、Retro、Refinement 六条真实提案闭环全部通过；四人中文音频上传、转写、说话人分离、版本确认与分析会议来源链也通过。各套件运行前恢复同一份测试前数据库/音频快照，最终恢复通过且 manifest 校验通过。完整六套件报告：`deploy/acceptance-runs/4d5b237ef4884b039d8581fdff11687d/report.json`；转写重跑报告：`deploy/acceptance-runs/2c236eccbac4406ba27ae13d679cc3c8/report.json`。最终 `deploy/verify.py --live-url http://127.0.0.1:8088 --smoke-login` 通过，四个 Docker 服务均健康。真实在线服务流程已完成验收。
- 四角色在线补验：Admin/Owner/Member/Viewer 均可查看独立测试建议和来源会议并返回原筛选；Member/Viewer 没有审核控件，Viewer 无会议保存权限；Owner 完成真实拒绝审核且服务端记录为 `rejected`，未创建需求池条目。编排器在测试前后恢复快照，报告：`deploy/acceptance-runs/9f56dc095d274683a77d1518eeec4b14/report.json`。
- 分页容量补验：服务端新增七来源只读索引和游标分页，前端只取当前页详情；同时间、大小写不同 ID 的游标契约测试通过。51 条真实测试提案跨越默认 50 条首页后，“加载更多提案”找回其余提案；最新网页容器验收报告 `deploy/acceptance-runs/ba4bc8237d684bc9a0c1f392cd5bed30/report.json`，最终快照恢复通过。前端构建、smoke 12/12、分页定向回归 1/1、Java 全量 352/352 通过；Java 全量日志 `java-backend/target/full-test-20261009-final.log`。全量测试中曾触发旧故事并发创建的数据库死锁，已将回滚后的冲突统一返回可重试的 409，避免暴露 500。最终在线服务健康与只读登录检查通过，四个 Docker 容器均健康。
- 工作台摘要容量补验：`GET /api/review-queue/summary` 返回七来源准确数量及最近提案、最近会议；前端无需按会议读取所有分析。分页与摘要定向回归 4/4（含旧服务 404 回退）、Java 摘要契约测试 2/2 通过。51 条真实测试提案的在线验收同时核对工作台显示数量，报告 `deploy/acceptance-runs/0a9e0fa6922a485b867ee1ba78aa55a7/report.json`，最终快照恢复通过。数据库仍需为大规模 JSON 提案展开和跨来源汇总做压测。
- 容量基准与一致性：在独立测试库的单事务中临时插入 10,000 条 Daily 分析（20,000 条提案），待审第一页 `EXPLAIN ANALYZE` 为 84.8 ms、单来源精确计数为 37.3 ms，随后回滚并确认分析记录为 0；完整计划与适用边界见 [`审核队列容量基准.md`](审核队列容量基准.md)。服务端分页及摘要现在使用只读事务保持一次请求内的跨来源快照一致。改动后 Java 全量回归 352/352 通过，51 条真实提案分页和工作台计数复验通过，报告 `deploy/acceptance-runs/555d87f1acb245cba033859f3264ad03/report.json`，最终数据库快照恢复通过。
- 回归脚本不再假设所有 Gantt 布局都包含 `.grow.parent` 行，改为断言当前实际渲染的 16 条任务行、管理任务标记和 Story 关联；“已切换离线”提示仅在登录对话框实际显示时选择，覆盖服务自动离线回落。
