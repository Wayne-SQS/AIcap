# 爱管理 · 团队工作台(AIcap)

> **一个由「本」统筹的 AI 团队办公室。**
> 三个智能体同事各司其职，你只跟「本」说话；所有会改动项目正式数据的结论，**先过人审，再落库**。

## 这间办公室里有四个人

| | 名字 | 负责 | 你跟它说什么 |
|---|---|---|---|
| **主管** | **本** | 统筹全局、派活、启动同事的服务 | 「现在进度怎么样」「让译去分析这场会」 |
| 会议 | **译** | 把会上说的话译成任务、负责人、排期 | （通过本转达） |
| 画像 | **衡** | 说清每个人做了什么、扛了多难、有什么风险 | （通过本转达） |
| 画图 | **绘** | 把项目数据或一句话画成可交互的图 | （通过本转达） |

**你不需要记住三个同事在哪。** 右下角「本」的像素头像，点开、问它、派它，它去找人。

面向 6 周课程项目的小团队协作工作台：**把「需求 → 用户故事 → 执行任务 → 真实开发活动」整条链路放进一个可交互系统**，由四个智能体协作推进——一个主管 + 三个同事。所有 AI 结论都必须经人工审核才能改变业务数据；查不到证据时如实说"数据不足"，不伪造模型结论。

## 当前推荐运行与验证入口

当前完整运行方式是 `deploy/compose.yaml`：Nginx、Java 后端、会议 Agent 与 MySQL 组成四容器服务。新成员克隆后的依赖准备、密钥配置、启动、健康检查、数据备份与七类会议浏览器验收，请按 **`deploy/` 目录中的脚本与编排**操作；克隆快速入口见 **[`上手指南_克隆后如何运行.md`](上手指南_克隆后如何运行.md)**。不要用归档 FastAPI 目录中的 Compose 文件代替生产编排。

当前仓库使用的测试结果和边界以部署指南中的“当前验证边界”、各模块测试文档及最近一次 CI/本机验收记录为准。下文的 v3 用例数量、旧式本机启动命令和阶段说明属于历史记录，保留作追溯参考，不是当前发布入口。

**目录**

- [当前推荐运行与验证入口](#当前推荐运行与验证入口)
- [一、业务逻辑(先读这一节)](#一业务逻辑)
- [二、界面与视图](#二界面与视图)
- [三、把系统跑起来](#三把系统跑起来)
- [四、AI 与 GitHub 接入配置](#四ai-与-github-接入配置)
- [五、包内设计(实现视角)](#五包内设计实现视角)
- [六、测试与质量](#六测试与质量)
- [七、进度、待办与交接](#七进度待办与交接)
- [八、文档索引](#八文档索引)
- [附录 A、变更与移植记录](#附录-a变更与移植记录)

---

# 一、业务逻辑

## 1. 这个系统解决什么问题

一个 5 人学生团队要在 6 周内交付一个软件项目,典型的痛点是:**需求和会议决议散落在聊天里、任务分配靠感觉、谁干了什么没有依据、AI 生成的东西没人敢直接用**。

「爱管理」针对这四件事各给一条闭环:

| 痛点 | 系统的答案 | 在哪条主线 |
|---|---|---|
| 需求与会议决议散落 | 会议录音 → 智能体结构化提取 → **人工审核** → 落成需求池/故事 | 主线 2 |
| 任务分配靠感觉 | 故事地图 + 甘特图 + 成员负载热力图,排期有工时与容量依据 | 主线 1 |
| 谁干了什么没有依据 | 关联 GitHub 真实提交(repo 提交物)→ 事实与推断分开展示 | 主线 3 |
| AI 结论不敢用 | 一切 AI 输出先过「审核中心」,采纳/修改后采纳/拒绝都留痕 | 主线 2、4 |

时间盒与里程碑(系统内的真实基线):**Sprint 1–3 各 2 周 = W1–W6**,里程碑 **M1 需求与规划基线(W1)/ M2 第一次 Sprint 评审(W2)/ M3 中期评审(W4)/ M4 成果展演(W6)**。

## 2. 谁在用:5 个账号与权限边界

登录别名 `成员1`–`成员4` 与真名**双向**互通;`成员5` 既是 username 也是显示名「只读查看者」。演示密码统一 `123456`。

| 账号 | 后端角色 | 团队职责 | 6 周容量 |
|---|---|---|---|
| 李锐铭 | `admin` 管理员 | 产品与范围 · 总协调(权限 / 公共服务 / 风险闭环) | 60h |
| 高思晗 | `owner` 负责人 | 需求与 AI 协调(故事管理 / 会议智能体 / AI 审核) | 48h |
| 孙秋实 | `member` 成员 | 技术与进度管理(数据接口 / WBS / 甘特 / 智能排期) | 60h |
| 罗子涵 | `member` 成员 | 质量与风险(UML / 质量分析 / 方案评审) | 54h |
| 成员5 | `viewer` 只读查看者 | 基础报表 / 只读视图 | 60h |

**权限口径**(后端是最终判据;前端把无权限的入口保持可见但禁用,并说明原因):

| 能做什么 | admin | owner | member | viewer |
|---|:--:|:--:|:--:|:--:|
| 看各视图与报表 | ✅ | ✅ | ✅ | ✅(只读视图) |
| 建/改 故事 · 任务 · 需求池 · 活动事实 | ✅ | ✅ | ✅ | ❌ 403 |
| 触发 AI 分析、GitHub 同步(含完成任务自动触发) | ✅ | ✅ | ✅ | ❌ 403 |
| **删除**故事/会议,改成员画像(改他人),人工覆盖任务难度 | ✅ | ✅ | ❌ | ❌ |
| **审核** AI 建议(采纳 / 修改后采纳 / 拒绝) | ✅ | ✅ | ❌ | ❌ |

实现上就是两个口径:`admin/owner/member` = `Roles.writer()`,`admin/owner` = `Roles.reviewer()`。

## 3. 业务对象:它们怎么串起来

```
会议 / 人工录入
      │  (智能体提取 + 人工审核)
      ▼
  需求池条目 R01+  ──「移入看板」──►  用户故事 US01–US37 (US38+ 自动编号)
                                          │  故事地图: 骨干活动 A1–A5 (横) × 发布切片 (纵)
                                          │  看板:      待办 / 进行中 / 已完成
                                          ▼
                                    执行任务 T01–T16
                                    负责人 · 预计工时 · 起止周 W1–W6 · 前置依赖 Txx · 状态 · 进度
                                          │  (成员在自己的任务上点「✓ 已完成」)
                                          ▼
                       活动事实 activity_records  ←── GitHub 同步(commit / PR合并 / Review / Issue)
                       (可手工录入 / 批量导入 / 来源=github)
                                          │
                                          ▼
                  难度评估 · 成员能力画像 · 团队风险 ──► 审核中心 ──► 人工采纳才落库
```

**血缘规则**(基线约定):开发任务(T03–T14)挂在某张看板卡下(映射见前端 `seed.js` 的 `TASK_CARD_LINKS`),父卡进度 = 子任务**工时加权**(不是数条数);管理任务(T01/T02/T15/T16)不挂卡,在甘特图里用虚线单独成区;挂到不存在的卡会被后端拒绝,一致性自检也会告警。这条规则保证了「故事进度」与「执行层排期」不会各说各话。

**骨干活动 A1–A5**(横轴,用户流程顺序):A1 进入与组织项目 / A2 梳理需求 / A3 安排与执行 / A4 观察与协同 / A5 复盘改进。
**发布切片**(纵轴):Sprint 1 基础数据与管理闭环 / Sprint 2 四视图协同与会议智能体 / Sprint 3 GitHub 智能体与 AI 闭环 / **Sprint 4+ 后续路线(不纳入本学期六周承诺)**。

## 4. 四条业务主线

### 主线 1 · 项目执行:需求从哪来到哪去

1. **需求进口**:会议智能体提取的待审建议被采纳 → 生成需求池条目,或人工直接录入需求池。
2. **需求池 → 故事**:点「移入看板」,选 Sprint 与负责人 → 自动分配编号(`US38+`),成为正式用户故事。
3. **故事调度**:在**故事地图**上拖卡片 = 同时表达"放到哪条骨干活动、放进哪个发布切片";纵向拖动改 Sprint 会**级联挪动未完成子任务**(已完成/已取消的保留原记录,不追溯历史),横向拖动改骨干活动需要确认(横轴是叙事顺序,不是优先级)。
4. **执行任务**:任务带负责人、预计工时、起止周(W1–W6)、前置依赖(T 编号,含**依赖环校验**)、状态(待办/进行中/已完成/已取消)、进度;甘特图按 Sprint 分带展示,点任务条可看前置/后续任务并直接编辑(真实 PATCH)。
5. **看人**:成员任务图看每人任务明细(按全部/待办/进行中/已完成/阻塞筛选)与 6 周负载热力图,负载档位按 已分配工时 ÷ 容量 判定(超载 / 偏忙 / 正常 / 充足)。
6. **完成动作**:成员在自己的任务明细里点「**✓ 已完成**」→ 写回状态并把进度联动为 100% → 触发主线 3。

### 主线 2 · 会议执行:会议 → 建议 → 人工审核 → 落库

1. **进料**:「AI 工作台 → 会议工作区 → 语音会议」内置录音(浏览器 `getUserMedia` + `MediaRecorder`,在**浏览器内**转成真 MP3 后提交),也支持上传 `.mp3` 或文本转写;转写内容可人工编辑。
2. **智能体分析**:后端用 LLM 跑**只读工具循环**(5 个只读工具:`search_stories` 查故事、`search_pool` 查需求池、`list_tasks` 查任务与工时、`list_members` 查真实成员、`project_summary` 查项目统计,**且明确告知模型当前数据能力的限制**),产出会议摘要、行动项、协调事项、验收约束,以及**待审建议**;每条结论都带原文证据,并强制先查已有需求再提新需求(防重复造需求)。
3. **人在回路**:建议进入「审核中心」,由 admin/owner **采纳 / 修改后采纳 / 拒绝**;采纳才真正创建业务对象(如需求池条目)并留痕(审核人、结果、理由、影响范围)。
4. **幂等与边界**:重复批准不会重复创建对象;**已采纳落库的需求池条目是独立产物,不随会议删除而删除**(有专门的契约用例断言这一点)。在线页面明确标记"手动录入",手动入口始终保留;**自动分析需要配置服务端模型密钥**。

### 主线 3 · 任务提交智能体:完成任务 → 读 GitHub 提交物 → 只评估提交者

这是"谁干了什么"的证据链,也是**唯一由成员动作驱动**的智能体:

1. 成员点「**✓ 已完成**」(成员任务明细抽屉或甘特任务详情)→ `PATCH /api/tasks/{id}` 写入完成状态。
2. 前端自动叫醒智能体:**先读仓库**(`POST /profile-agent/github/sync`,按 GitHub 事件 id 幂等去重,按成员映射归属到人),**再只评估这名提交者本人**(`POST /profile-agent/analysis/run?userId=<该任务负责人>`)。
3. 评估结论以一句话摘要直接显示(如「孙秋实 提交评估(LLM):本周期共 51 次代码活动,覆盖 7 个模块…」),同时画像面板与任务提交面板就地刷新。
4. **不做定时轮询**:没有"每小时去仓库扫一遍"的行为,只有人提交了才跑;连续完成多个任务时评估**串行排队**,每一个都会被执行。
5. 提交信息里写 `Txx` 可自动关联到任务;提交信息按 Angular `(scope):` 约定可提取模块。

### 主线 4 · 成员能力画像与团队风险:从事实到协调建议

1. **画像**:「成员任务图」页展示每人的岗位、经验年限、画像摘要,以及三个维度——**技术栈 / 工作能力 / 熟悉的开发流程领域**(每项带熟练度 1–5)。5 人画像与角色一一对应且**互不相同**,作为分工与排期依据;成员本人可对画像提出纠正,admin/owner 可改他人。
2. **难度评估**:每个任务有 AI 评估的难度(低/中/高/极高)与评估依据;人工可以覆盖,**人工结论永远优先于 AI**,不会被后续 AI 评估改写。
3. **团队风险**:规则引擎与 LLM 两条路都覆盖 7 类风险 —— 成员负载过高 / 关键单点依赖 / 任务长期无进展 / 高难度任务缺 Review / 任务缺测试活动记录 / 模块工作过于集中 / 里程碑延期风险。每条风险必须给出**证据 + 推断 + 建议行动**。
4. **送审闭环**:风险可一键送审到「审核中心」(来源标识区分 会议智能体 / 画像智能体 / 任务提交智能体),由人决定采不采纳——**AI 不直接改业务数据**。

## 5. 关键业务口径(容易被误解的地方)

| 口径 | 规则 |
|---|---|
| 故事进度 | 有子任务 → 子任务**工时加权**;无子任务 → 按故事状态折算(待办 0 / 进行中 0.5 / 完成 1) |
| 任务状态 | `0 待办 / 1 进行中 / 2 已完成 / 3 已取消`;只给状态不给进度时后端自动联动(2 → 100%) |
| 已取消 vs 未完成 | 取消(status 3)语义是"真废弃",排期级联、负责人级联都**跳过已取消与已完成** |
| Sprint 位移 | 每个 Sprint = 2 周,周次夹紧在 W1–W6;换 Sprint 时未完成子任务整体平移相同的周数 |
| 负载 | 已分配工时 ÷ 成员容量;>100% 超载、≥80% 偏忙、≥60% 正常、其余充足 |
| 需求池 | **不计入** Sprint 承诺完成率(它是候选需求,不是承诺) |
| 同步幂等 | 同一 GitHub 事件按 `github_event_id` 唯一索引入库,重复同步只跳过 |
| 同步计数 | `pulled` = 本次真实拉取数,`synced` = 新增入库,`skipped` = 跳过(含已存在与未映射) |
| 未映射提交者 | 不在成员映射表里的提交者**不入库**,并在同步结果里如实报警告条数 |
| AI 引擎降级 | LLM 不可用/未配置/输出不合规 → 回退规则引擎,并把来源如实标注为 `rules_fallback`,不冒充模型结论 |
| 单人评估 vs 全团队 | 完成任务只评估提交者本人(1 次模型调用);全团队正式分析只在人工点「运行分析」/送审时发生 |
| 评估限流 | 全团队分析 30 秒/人(防刷额度);单人评估只做 3 秒双击去抖,连续完成任务不会被挡住 |

## 6. 边界:这个系统**不**做什么

- **AI 不改数据**:模型只能生成建议;真正写业务数据的入口只对"人工审核通过"开放,且由后端业务服务执行,不向模型暴露绕过审核的写通道。
- **提交次数 ≠ 工作量/质量**:系统只呈现事实与推断,**不作为人员奖惩依据**(界面上也这么写)。
- **查不到就说查不到**:缺数据时标注"数据不足/暂无记录",不编造;未映射成员的提交不入库而不是猜一个人。
- **不虚构按天承诺**:排期依据是工时与容量,未明确的负责人/截止日保持"未分配/待确认"。
- **离线演示与真实数据分开**:后端不可用时自动回退本机种子数据(US01–US37 + T01–T16),界面上明确是演示模式。

## 7. 一条 3 分钟演示路径

1. 用 `孙秋实` 登录(密码 `123456`)→「成员任务图」点自己的卡片 → 任务明细抽屉里点某条任务的「**✓ 已完成**」。
2. 观察 toast 依次出现:`已标记完成 · 已触发任务提交智能体` → `智能体已读取 GitHub 提交物:新增 N 条` → `孙秋实 提交评估(LLM):…`;**连续点两条任务,两条都会被评估**(排队执行)。
3. 切到「AI 工作台」:任务提交面板的「最近活动」可按成员筛(全部 / 各人条数),标题如实写「共 N 条,当前显示最近 20 条」,可展开全部;**罗子涵 / 高思晗这种条数少的成员在这里查得到**。
4. 画像面板看成员画像、难度评估、团队风险(每条带证据),点「送审」把风险送进「审核中心」。
5. 切 admin(`李锐铭`)去「审核中心」采纳一条建议 → 需求池里出现新条目 → 「移入看板」成为 US38+ 的故事。

---

# 二、界面与视图

| 视图 | 说明 |
|---|---|
| ◈ 项目总览 | 故事/任务统计、3 个 Sprint 进度(工时加权血缘口径)、里程碑、总完成度、实时进度 |
| ◆ 需求池 | 会议/临时需求暂存,可「移入看板」成正式故事(选择 Sprint / 负责人) |
| ▦ 用户故事看板 | 默认「故事地图」(含 Sprint 4+ 后续路线切片)、拖拽、编辑、搜索筛选、JSON/CSV 导出、变更日志 |
| ▤ 甘特图 | 父卡血缘行(子任务周并集 + 工时加权进度) + 子任务行、Sprint 分带表头、点击任务条看前置/后续并可直接编辑任务(真实 PATCH) |
| ▥ 成员任务图 | 5 名真实成员、点击卡片开任务明细抽屉(状态筛选)、周负载热力图(含负载图例与周容量口径)、容量 Bandwidth、成员开发活动图 |
| ◇ UML 图 | 真实 SVG 用例图(按 US01–US37 映射,可悬停高亮/点击看故事) + 时序图(按 Spring Boot 真实路由) |
| ✦ AI 工作台 | 会议智能体(真实 DeepSeek 分析 + 网页录音 + 待审建议)、任务提交智能体(真实 GitHub 活动 + 只评估提交者)、画像智能体(画像/难度/风险/热力图),以及 6 项演示能力:智能需求拆解 / 进度预测 / 智能排期 / 风险预警 / 质量分析 / 效率优化 |
| ◉ 会议工作区 | 文本会议、语音转写/人工确认、手动建议分流;五类会议分析显式选择,Assignment 独立 |
| ✓ 审核中心 | 跨来源提案队列(通用建议、五类会议分析与 Assignment);按状态/来源/会议/日期筛选,审核与执行仍回到来源流程调用原接口 |

---

# 三、把系统跑起来

**队友首次上手**(克隆后怎么起 MySQL + 后端 + 前端)见 **[`上手指南_克隆后如何运行.md`](上手指南_克隆后如何运行.md)**。仓库内还提供了 `java-backend/启动后端.ps1`,一键注入环境变量并后台启动后端(见 §4.4)。

以下方式面向单独调试 Vue/Java 开发环境,不包含容器化会议 Agent 与生产同源网关。首次运行或完整体验会议流程请使用上面的部署指南。

```bash
# 只开前端(离线演示,数据在本机浏览器)
cd frontend && npm install && npm run dev   # 后端不可用时自动回退离线演示

# 完整模式(前端 + JavaWeb 后端 + MySQL)
cd backend && docker compose up -d          # 起 MySQL(3307,compose 文件在 backend/)
cd java-backend && java -jar target/aicap-java-backend.jar   # 起后端,默认 8080
cd frontend && npm install && npm run dev   # 起前端 http://localhost:5173
```

> 后端需要 **JDK 23**(pom 用 `release 23`);构建前先停掉正在运行的后端,否则 Windows 会锁住 jar 导致 `mvn package` 重命名失败。
> 前端 Vite dev 只监听 `[::1]:5173`,请用 `http://localhost:5173` 访问(用 `127.0.0.1` 会连不上)。

**默认账号(演示)**:李锐铭(admin)/ 高思晗(owner)/ 孙秋实、罗子涵(member)/ 成员5(只读查看者),密码均为 `123456`。

---

# 四、AI 与 GitHub 接入配置

## 4.1 LLM API 密钥(会议智能体 / 画像与任务提交智能体)

后端通过**环境变量**读取 DeepSeek API Key,**不写入任何配置文件或代码仓库**。注意有**两套独立前缀**:

| 环境变量 | 用途 |
|---|---|
| `PROFILE_LLM_API_KEY` | 画像智能体 / 任务提交智能体:成员画像、难度评估、风险归因 |
| `AICAP_LLM_API_KEY` | 会议智能体的会议分析 |

```powershell
$env:PROFILE_LLM_API_KEY = "sk-你的key"
$env:AICAP_LLM_API_KEY   = "sk-你的key"
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-23'
cd java-backend
mvn -B -DskipTests package
java -jar target\aicap-java-backend.jar
```

> 只设 `AICAP_LLM_API_KEY` 时画像智能体会静默回退规则引擎(响应里 `engine=rules_fallback`),看起来像"模型没生效"——两套都要设。
> 未配置 Key 时后端仍可运行,AI 分析会回退为「规则打分 + 明确标注的数据不足」,**不会伪造模型结论**;配置后自动启用真实模型。

## 4.2 GitHub 同步(活动数据接入)

后端以 **GitHub REST API(用 PAT 认证)** 拉取仓库的 Commit / PR / Review / Issue 事件,映射到系统成员后落库 `activity_records(source='github')`,供成员任务图、画像分析、任务提交智能体消费。

| 环境变量 | 必填 | 说明 |
|---|---|---|
| `GITHUB_ENABLED` | 是 | `true` 开启同步 |
| `GITHUB_REPO` | 是 | 仓库名,格式 `owner/repo` |
| `GITHUB_TOKEN` | 是 | Personal Access Token(fine-grained PAT 需仓库 Contents:Read / Pull requests:Read 等只读权限;**不要把 token 提交进仓库**) |
| `GITHUB_USER_MAPPING` | 是 | GitHub 用户名 → 系统成员真名 的 JSON 映射 |
| `GITHUB_AUTO_SYNC_HOURS` | 否 | `0`(默认)= 不启用定时同步,只靠成员动作触发 |

## 4.3 同步机制与边界

- **自动触发(主路径,只在有人提交时才跑)**:成员点「✓ 已完成」→ `POST /api/profile-agent/github/sync`(读仓库提交物)→ `POST /api/profile-agent/analysis/run?userId=<提交者>`(**只评估提交者本人**)→ 广播事件让两个智能体面板立即刷新。
- **没有定时轮询**:`GITHUB_AUTO_SYNC_HOURS=0` 时每小时那次 `@Scheduled` 只空转一次、不发任何请求;`profile-agent.scheduled.enabled=false`(默认)时每周一那次也不跑。
- **连续完成多个任务**:前端把评估**串行排队**,不丢请求;后端限流分两条通道(全团队 30 秒/人、单人 3 秒去抖)。
- **手动触发**:前端「AI 工作台 → 任务提交分析 / 成员画像分析 → ⟳ 立即同步」(管理员/负责人/成员可见);或 `POST /api/profile-agent/github/sync?start=YYYY-MM-DD&end=YYYY-MM-DD`。
- **幂等 / 计数 / 未映射**口径见 §1.5;同步状态查询 `GET /api/profile-agent/github/status`。

## 4.4 RAG 检索层 embedding(知识库索引与语义检索)

RAG 的向量化是**独立于 LLM 的第三套密钥**,**不能用 DeepSeek 的 key 顶替** —— DeepSeek 不提供
`/embeddings` 端点(2026-10-15 实测 `POST https://api.deepseek.com/embeddings` 返回 404)。
指过去的表现最坑:key 和 model 都填了,却每次索引都 404,而日志只说"向量化服务返回 HTTP 404",
看不出是**供应商选错**。

| 环境变量 | 说明 |
|---|---|
| `AICAP_EMBEDDING_API_KEY` | 必填。留空则整个 RAG 保持未启用:检索接口返回 `configured=false` 并说明原因,**不发任何外部请求** |
| `AICAP_EMBEDDING_BASE_URL` | 默认 `https://api.siliconflow.cn/v1`(硅基流动,国内直连) |
| `AICAP_EMBEDDING_MODEL` | 默认 `BAAI/bge-m3`(免费、1024 维) |
| `AICAP_EMBEDDING_DIMENSION` | 默认 `1024`。**换模型时必须同步改**,否则写入向量库前的维度校验会直接失败(这是刻意设计,防止新旧向量混算) |

不想接云端、只想先把链路跑通时,用仓库自带的本地桩服务(零密钥、零费用):

```powershell
python qa/embedding_fixture.py --port 19379 --dim 64
$env:AICAP_EMBEDDING_BASE_URL  = "http://127.0.0.1:19379"
$env:AICAP_EMBEDDING_MODEL     = "local-fixture"
$env:AICAP_EMBEDDING_API_KEY   = "local"
$env:AICAP_EMBEDDING_DIMENSION = "64"
```

它按字符哈希确定性生成向量(共享用词的文本余弦更高),所以**排序是有意义的**,但语义质量只是近似
—— 够联调和演示,不够用来评测检索准确率。

## 4.5 一键启动脚本

`java-backend/启动后端.ps1`(UTF-8 带 BOM,给 PowerShell 5.1 用):检查 8080 是否被占用 → 可选 `-Build` 重新打包 → 从 **Windows 凭据管理器**取 GitHub token、从 `backend/.env` 取 LLM Key(首次使用请复制 `backend/.env.example` 为 `backend/.env` 后填写) → 注入上述环境变量 → 后台启动并把日志写到 `target\backend-dev.log`。**密钥只在运行时读取,不落盘、不进仓库**。

## 4.6 安全须知

- API Key 与 GitHub Token **一律走环境变量**;`.gitignore` 已忽略 `backend/.env` 与运行产物,不要把密钥写进任何 `.md/.bat/.vue/.java` 后提交。
- 同步与写入口对 `Roles.writer()`(管理员/负责人/成员)开放,只读查看者不可见也不可调用(403)。
- 送审建议进入「审核中心」,**人工审核通过才会执行**,LLM 只提出建议不直接改数据。

---

# 五、包内设计(实现视角)

> 上面是「业务怎么运转」,这一节才是「代码怎么摆放、怎么实现」。只想跑起来或演示的话,看到这里就够了。

## 5.1 仓库结构

| 目录 | 内容 |
|---|---|
| `frontend/` | 现行前端:Vue3 + Vite(8 视图,像素风;后端在线走 API,离线自动回退) |
| `java-backend/` | 现行后端:Spring Boot 3.5 + MyBatis-Plus(默认 8080,连 MySQL 3307 `AIcap` 库) |
| `backend/` | 起本机 MySQL 3307 / Qdrant 6333 用 `docker-compose.yml` + `init.sql`;FastAPI 参考实现仅仓库归档保留,不随交付包提供 |
| `ai-service/` | 会议 Agent 与离线语音转写/说话人分离服务 |
| `deploy/` | 当前 Docker Compose 部署、配置模板、验证与备份工具 |
| `qa/` | 前后端测试用例、执行记录与报告 |

## 5.2 技术栈与运行形态

- **前端**:Vue3 + Vite + Pinia + vue-router(hash 路由)、像素风自绘 CSS;后端不可用时自动回退 localStorage 演示数据。
- **后端**:Java 23 + Spring Boot 3.5 + MyBatis-Plus;MySQL 8.0(Docker,3307);JWT 鉴权;契约测试 JUnit 5。
- **智能体**:统一走 Spring 内的"大脑"组件(`ProfileAgentBrain` / 会议 Agent),LLM 调用可降级到规则引擎,每轮分析录制 thought/action/observation 供回溯。
- **远端分支**:只保留 `main`。此前的 `feat/*` 分支均已整合进 `main` 并删除。后续新功能按需开临时分支,合并后即删。

## 5.3 数据模型要点

- `users`(角色 / `capacity_hours`)、`member_profiles`(1:1 `users`,技术栈/能力/流程领域三个维度以 JSON 文本存放,启动幂等播种、已有画像不覆盖)。
- `stories`(US01–US37 基线)、`tasks`(T01–T16,`kanban_card_id` / `depends_on` / `status` / `progress` / `blocked`)、`pool_items`、`story_logs`。
- `activity_records`(活动事实,`source` = `github` / `manual` / `import`,`github_event_id` 唯一索引幂等)。
- `difficulty_assessments`(任务难度,`assessed_by=ai|rules|manual`,manual 永远优先)。
- 会议域:`meetings`(会议与转写文本)、`meeting_agent_runs` / `meeting_agent_events`(分析运行与每步 thought/action/observation)、`meeting_audio`(音频元数据,文件落 `java-backend/data/audio`,已 gitignore)、`meeting_approval_payloads` + `meeting_suggestion_records`(审批载荷与审核记录)、`suggestions`(建议本体)。
- 智能体观测域:`profile_agent_runs`(运行留痕,含本次评估范围 `scope`)、`profile_snapshots`(画像快照,可看趋势)、`profile_corrections`(画像纠正历史)。

## 5.4 三个智能体的内部结构

| 智能体 | 入口 | 关键机制 |
|---|---|---|
| 会议智能体 | 「AI 工作台 → 会议工作区」 | **现行实现是 `ai-service/`(Python 8090,LangGraph)**：每日站会 / 规划 / 评审 / 回顾 / 细化五类会议各一张同构 `StateGraph`,类型化产出 + `snapshot_mismatch` 等校验;录音上传 → 转写(faster-whisper)→ 说话人分离(sherpa-onnx)→ 证据引用 `seg-N`。产出经 `JavaXxxStore` 回写 Java,再落审批队列。<br />**上一代 `java-backend/.../agent/` 已于 2026-10-13 删除**(模型自主工具循环、通用会议不分类型)。其运行时证据校验(`invented_assignment` 拦截编造负责人 / `invalid_evidence` 逐字核对 / `incomplete_analysis` 片段全覆盖)**随之退役,新链路仅有离线评测**——防幻觉能力缺口待补 |
| 画像智能体 | 「AI 工作台 → 成员画像分析」 | 8 个工具(`query_tasks` / `query_activities` / `query_members` / `query_member_profile` / `query_difficulty_history` / `query_profile_snapshots` / `query_corrections` / `query_git_commits`)。双模式:`GET /analysis` 只读预览(零 LLM、复用已存难度、风险走规则引擎);`POST /analysis/run` 正式分析(LLM 优先,失败逐项降级并落库留痕),支持 `userId` 收窄到单人 |
| 任务提交智能体 | 「AI 工作台 → 任务提交分析」 | 消费 `activity_records`(含 GitHub 同步落库的真实事件);事实层与 AI 推断层分开展示;可生成协调建议送审 |

**画像智能体的三层口径**(避免"AI 说话没依据"):客观事实(`objective` / `work_items`)→ 规则引擎底座(可量化指标)→ LLM 只**叠加白名单字段**(缺失字段保留规则值,不会渲染成 `undefined`),并把 `generated_by` 标成 `llm` 还是 `rules_fallback`。

**五类会议闭环**(合并自 `main`):Daily 更新状态、Planning 调整 Sprint、Review 标记验收完成、Retro 创建行动项、Refinement 补齐后创建故事,均为「保存文本 → 分析提案 → 人工审核 → 单独执行 → 审计」同一形态。审核中心通过前端 adapter 读取每种既有来源记录,**不复制审核决定**;跨来源的审核与执行按钮深链回来源会议;部分来源加载失败时队列会提示结果可能不完整;离线模式只显示本机演示数据,不形成服务器审核或模型结论。

会议工作区除 MySQL / Java / 前端外还需启动 `ai-service` Python 服务(默认 8090,`AICAP_LLM` 配置加载在该进程)。缺密钥时新流程**明确报错**,不返回规则或演示提案。隔离业务联调与真实模型质量的不同验证边界见 `ai-service/evals/` 评测数据;试用步骤与旧入口配置记录见 `docs/会议Agent_五类会议试用指南.md`、`docs/会议Agent_DeepSeek运行与验收.md`(设计文档未随交付包提供)。

## 5.5 各能力实现要点

- **会议录音(麦克风 → MP3)**:浏览器 `getUserMedia` + `MediaRecorder` → 浏览器内解码 PCM 并用 `lamejs`(MIT) 编码成真 `.mp3` → 提交到当前会议。上传 `POST /api/meetings/{id}/audio`(multipart:`file`、`source=recorder|upload`、`duration_ms`)、列表 `GET /api/meetings/{id}/audio`、回放 `GET /api/audio/{id}`(需鉴权,前端取字节转 objectURL)、删除 `DELETE /api/audio/{id}`(仅 admin/owner)。校验:扩展名 + 魔数双校验、单文件默认 25MB(`AICAP_AUDIO_MAX_BYTES`);落盘 `java-backend/data/audio`(`AICAP_AUDIO_DIR`)。**为什么不用 ffmpeg**:`MediaRecorder` 原生只出 webm/opus 或 mp4/aac,不出 mp3;客户端转码让服务端零依赖。
- **会议删除**:`DELETE /api/meetings/{meetingId}`(admin/owner)在**事务内手工反序级联**清理 事件 → 运行记录 → 音频元数据 → 审批载荷 → 建议及其审核记录 → 会议本体;音频**磁盘文件在事务提交后** best-effort 删除;响应回传 `deleted` / `audio_deleted` / `suggestions_deleted` / `runs_deleted` 计数。刻意保留的边界:**已审批落库的需求池条目不随会议删除**(契约用例 `MEET-06` 专门断言)。
- **成员画像**:读 `GET /api/members/profiles`(任意登录用户);写 `PATCH /api/members/{userId}/profile`(admin/owner 可改任何人,member 只能改本人,viewer → 403)。契约:三个维度必须给出(缺字段 422)、同维度名称不可重复、熟练度 1–5;全局严格 JSON(未知字段 422)。
- **完成任务触发链**:唯一入口 `frontend/src/composables/useAgentRefresh.js`;任务状态入口在「成员任务明细抽屉」与「甘特任务详情」两处。

## 5.6 关键接口一览

| 域 | 接口 |
|---|---|
| 鉴权 | `POST /api/auth/login`、`GET /api/auth/me`、`GET /api/auth/users` |
| 故事 / 任务 / 需求池 | `GET`+`POST` `/api/stories`、`GET /api/stories/logs`、`PATCH`+`DELETE` `/api/stories/{id}`、`GET /api/tasks`、`PATCH /api/tasks/{id}`、`GET`+`POST` `/api/pool`、`DELETE /api/pool/{id}`、`POST /api/pool/{id}/promote` |
| 管理台与成员 | `GET /api/dashboard`、`GET /api/members/profiles`、`PATCH /api/members/{userId}/profile` |
| 会议与录音 | `POST`+`GET` `/api/meetings`、`GET`+`DELETE` `/api/meetings/{id}`、`POST`+`GET` `/api/meetings/{id}/runs`(触发 / 查询分析)、`GET /api/agent-runs/{runId}`、`POST /api/agent-runs/{runId}/retry`、`POST`+`GET` `/api/meetings/{id}/audio`、`GET /api/audio/{id}`、`DELETE /api/audio/{id}`、`GET /api/agent/config` |
| 建议审核 | `GET`+`POST` `/api/suggestions`、`GET /api/suggestions/{id}`、`POST /api/suggestions/{id}/review`(采纳 / 修改后采纳 / 拒绝) |
| 画像 / 提交智能体 | `GET /api/profile-agent/analysis`、`POST /api/profile-agent/analysis/run[?userId=]`、`GET /api/profile-agent/analysis/compare`、`GET`+`POST` `/api/profile-agent/activities[/import]`、`GET /api/profile-agent/difficulty`、`PATCH /api/profile-agent/difficulty/{taskId}`、`GET /api/profile-agent/heatmap`、`GET /api/profile-agent/snapshots[/trend]`、`GET`+`POST` `/api/profile-agent/corrections`、`GET /api/profile-agent/runs`、`POST /api/profile-agent/risks/submit-suggestions`、`GET /api/profile-agent/github/status`、`POST /api/profile-agent/github/sync` |
| 健康检查 | `GET /api/health` |

---

# 六、测试与质量

容器化生产形态（同源Nginx网关、Java、会议Agent、MySQL、持久化卷及上线验证）见 `deploy/compose.yaml` 与 `deploy/verify.py`。

## 测试交付物

前后端测试用例与脚本位于 [`qa/`](qa/)。历史执行记录与设计文档未随交付包提供；`qa/run-all.ps1` 反映旧本机测试基线，不代表当前部署验收范围。

一键跑全套(需 MySQL 3307、后端 8080、Vite 5173 已启动;脚本会强制指向 JDK 23):

```powershell
& .\qa\run-all.ps1                 # 128 + 44 + 8 = 180 条
& .\qa\run-all.ps1 -SkipBackend    # 只跑前端两套
& .\qa\run-all.ps1 -SkipE2E        # 只跑后端契约
```

**后端契约测试现状**:`cd java-backend && mvn -B test` = **238 项全绿**(上表"后端契约 128"与下方注释"148 项"分别是 v3 文档记录时、画像智能体接入后的数量;此后 RAG 检索层 S1/S2 接入、平台工具层 S3 接入,并新增了对 MySQL / Qdrant 两套向量库实现各跑一遍的参数化测试;其中 S3 工具层的 15 项在 `PlatformAgentContractTest`)。

> 跑全量后端测试**需要 Qdrant 容器**(`cd backend && docker compose up -d qdrant`):索引与检索两组契约测试各跑两遍实现,qdrant 那一遍连不上会直接失败 —— 不做静默跳过,因为 ACL 在 Qdrant 侧落在 payload 而非 SQL 里,漏测就是权限后门。

更早的 legacy 基线(旧 `index.html` + FastAPI 后端)测试文档未随交付包提供。

---

# 七、进度、待办与交接

**已完成**
- 前端 8 视图像素风原型:看板拖拽/编辑/导出、故事地图、需求池、甘特、成员负载、UML、AI 助手、AI 审核中心(现名:AI 工作台、审核中心)。
- 后端 `java-backend/`:登录(JWT)、故事/需求池/任务/仪表盘 REST API、变更日志、成员列表与画像;FastAPI 版已归档至 `backend/` 作参考实现。
- 三个智能体:会议智能体(真实 DeepSeek + 只读工具循环 + 证据校验 + 待审建议)、成员差异化画像 + 难度评估 + 团队风险、任务提交智能体(GitHub 活动同步 + 完成任务只评估提交者)。
- 会议录音(浏览器转 MP3)、成员任务明细抽屉、甘特任务编辑、UML 真实 SVG。
- MySQL 8.0(Docker,库 `AIcap`);前端在线走 API、离线自动回退演示数据。

**待办(阶段 2+)**
- **前端旧界面名残留清理**(合并 `main` 时带入):`AI 助手` 1 处、`会议智能体` 5 处、`提案审核中心` 6 处 —— 需逐个判断是「视图名」(应改)还是「Agent 名」(可留);README 已按合并后界面口径对齐(AI 工作台 / 会议工作区 / 审核中心)。
- 会议录音的语音转写(ASR)接入;会议 Agent 真实模型评测。
- 六项演示能力接入真实大模型。
- 上线部署:云服务器 + HTTPS,真·多人在线。

**数据说明**:每人本地运行各自一套数据库(互不互通但完全可用);如需共享数据,由一人当"服务器"运行后端,其余人把前端 API 地址指向其局域网 IP(默认 8080,可用 `VITE_API_BASE` 或 `frontend/src/api/client.js` 默认值设置)。

---

# 八、文档索引

设计文档(docs/)未随交付包提供;代码与测试即本仓库的权威实现。

**入口**：[`上手指南_克隆后如何运行.md`](上手指南_克隆后如何运行.md)

**权威口径**：智能体名单、命名、职责边界以代码与 `docs/01-产品/智能体产品契约.md`(设计文档未随交付包提供)为准。

---

# 附录 A、变更与移植记录

## A.1 静态原型新版内容移植(mcc 提交 `9b14ec5`)

队友 mcc 在静态单文件原型上新提交了一版「四类视图完善+一体化」(静态原型,归档目录未随交付包提供),本轮把其中的**内容与样式**移植进 Vue,但**交互全部按 Vue 重写**(她的原实现是内联 `onclick` + 全局事件委托、编辑后不刷新其它视图),并按「不牺牲既有能力」的原则处理冲突:

| 项 | 结论 |
|---|---|
| 需求基线 | 采用 **US01–US37**(37 条,含 Sprint 4+ 后续路线),与 Java 后端 `DataSeeder` 逐字段一致;离线种子与在线数据同源 |
| 成员 | 按后端真实用户渲染 **5 人**(admin/owner/member/member/viewer),容量取 `users.capacity_hours`(60/48/60/54/60) |
| 甘特图 | **融合**:保留 v2 血缘(父卡条 = 子任务周并集 + 工时加权进度、子行缩进、管理虚线、独立任务区),新增 mcc 的 Sprint 分带表头、行内任务细节、点击任务条 → 详情面板(前置/后续任务高亮)、任务编辑弹窗(真实 `PATCH /api/tasks/{id}`) |
| 看板血缘 | **保留**:卡面子任务工时加权徽章 `▣ x/y · %`、总览工时加权口径、改 Sprint 时未完成子任务挪动确认(mcc 版删掉了这些) |
| 未分配负责人 | **保留**「未分配」(后端 `owner_id` 允许 null);其余展示统一为真实姓名 |
| UML | 采用 mcc 的真实 SVG 用例图(15 用例 / 4 角色 / 23 条关联,用例名取自故事标题,可悬停高亮、点击查看故事)+ 时序图,但参与者改为 **Spring Boot 后端**并按 `java-backend` 真实路由重画 |
| 成员页 | 新增「点成员卡片 → 右侧任务明细抽屉(全部/待办/进行中/完成/阻塞)」;成员画像板块保留在页面顶部 |
| 其它 | 故事地图设为默认标签、默认筛选「全部 Sprint」、新增「Sprint 4+」筛选与地图切片、热力图 tooltip 与负载图例、开发活动图改 6 周×7 天带星期标签、登录快捷账号改真名、会议/提交智能体补 viewer 只读拦截、新增数据一致性自检(控制台告警) |

验收:`cd frontend && npm run e2e:verify`(系统 Edge,无需下载 Playwright 浏览器);新增 `frontend/e2e-verify/mcc-port.spec.js` 覆盖上述移植项(基线/看板血缘/甘特详情与任务编辑往返/成员抽屉/UML/离线旧缓存回落)。

## A.2 会议交互修复(2026-09-07)

已支持修改后采纳并保留原始建议、移入看板时选择 Sprint/负责人,以及完整展示会议行动项、协调事项和验收约束。更新后重启后端、强制刷新前端;旧会议可复制并重新分析。接口变更与验证情况见 `docs/会议Agent交互修复与项目侧接续_2026-09-07.md`(设计文档未随交付包提供)。

## A.3 完成任务触发链(2026-09-15)

补上「成员完成任务 → 触发智能体 → 读 GitHub 提交物 → 只评估提交者」这条链:新增任务状态入口(成员任务明细抽屉 / 甘特任务详情)、`useAgentRefresh.js`(唯一触发入口,串行排队)、`/analysis/run` 的 `userId` 收窄、`/github/sync` 权限对齐 `Roles.writer()`、任务提交面板按成员可查与如实报总数。详见 `docs/任务提交与画像智能体_说明.md`(设计文档未随交付包提供)。
