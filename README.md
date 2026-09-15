# 爱管理 · 团队工作台

> 支持软件团队管理需求、安排任务、四视图联动与六项 AI 能力的协作平台 —— 像素风可交互原型。
> 前端 `frontend/`(Vue3 + Vite)已接后端 API:后端运行时为「在线 · 真多人协作」,后端不可用时自动回退「离线演示」;Vue 改造前的旧版单文件前端归档于 `legacy/`。

## 🚀 队友首次上手

克隆本仓库后怎么跑起来(MySQL + 后端 + 前端),见 **[`上手指南_克隆后如何运行.md`](上手指南_克隆后如何运行.md)**。

## 测试交付物

前后端功能测试的用例设计、执行记录与报告位于 [`qa/`](qa/),**当前基线为 v3**(基于 `main`:Vue3 前端 + `java-backend`):

| 文档 | 内容 |
|---|---|
| [`qa/测试用例设计_当前基线_v3.md`](qa/测试用例设计_当前基线_v3.md) | **168 条**用例设计(后端契约 126 / 前端 E2E 35 / 浏览器验收 7),含优先级与未覆盖缺口登记 |
| [`qa/测试执行记录_当前基线_v3.md`](qa/测试执行记录_当前基线_v3.md) | 逐条执行实况、并发 flake 分析与复跑结论 |
| [`qa/测试报告_当前基线_v3.md`](qa/测试报告_当前基线_v3.md) | 缺陷清单(9 条:7 条已修复 + 2 条环境项)与逐条修复验证表 |

一键跑全套(需 MySQL 3307、后端 8080、Vite 5173 已启动;脚本会强制指向 JDK 23):

```powershell
& .\qa\run-all.ps1                 # 126 + 35 + 7 = 168 条
& .\qa\run-all.ps1 -SkipBackend    # 只跑前端两套
& .\qa\run-all.ps1 -SkipE2E        # 只跑后端契约
```

更早的 legacy 基线(旧 `index.html` + FastAPI 后端)测试文档仍在 `qa/` 内,已标注「旧基线」仅作归档;运行前置与目录说明见 `qa/README.md`。

## 仓库结构

| 目录 | 内容 |
|---|---|
| `frontend/` | 现行前端:Vue3 + Vite(8 视图,像素风;后端在线走 API,离线自动回退) |
| `java-backend/` | 现行后端:Spring Boot 3.5 + MyBatis-Plus(默认 8080,连 MySQL 3307 `AIcap` 库) |
| `backend/` | 历史归档:FastAPI 后端 + SQLAlchemy + 测试(参考实现);其 `docker-compose.yml` 仍是启动 MySQL 8.0 的现行方式(库 `AIcap`,端口 3307) |
| `legacy/` | 历史归档:Vue 改造前的旧版单文件前端 |
| `docs/superpowers/` | 设计规格与实施计划 |
| `qa/` | 前后端测试用例、执行记录与报告(当前基线 v3) |

## 仓库分支

远端只保留 **`main`** 一条分支。此前的 `feat/javaweb-backend`、`feat/vue-frontend`、`feat/meeting-agent`、`feat/meeting-review-mvp` 均已整合进 `main` 并删除 —— 它们的每个提交都能在 `main` 历史中找到,不需要再从这些分支取代码。后续新功能按需开临时分支,合并后即删,保持远端只有 `main`。

## 本地运行(快速)

```bash
# 只开前端(离线演示,数据在本机浏览器)
cd frontend && npm install && npm run dev   # 后端不可用时自动回退离线演示

# 完整模式(前端 + JavaWeb 后端 + MySQL)
cd backend && docker compose up -d          # 起 MySQL(3307,compose 文件在 backend/)
cd java-backend && mvn spring-boot:run      # 起后端,默认 8080(或 mvn package 后 java -jar target/aicap-java-backend.jar)
cd frontend && npm install && npm run dev   # 起前端 http://localhost:5173
# 登录:李锐铭 / 高思晗 / 孙秋实 / 罗子涵 / 成员5,密码 123456
# 别名:成员1–成员4 与真名双向互通;成员5 的显示名「只读查看者」也可直接登录
```

## AI 与 GitHub 接入配置(重要)

### 1. LLM API 密钥(画像分析 / 任务提交智能体 / 会议智能体)

后端通过**环境变量**读取 DeepSeek API Key,**不写入任何配置文件或代码仓库**:

| 环境变量 | 用途 | 示例 |
|---|---|---|
| `PROFILE_LLM_API_KEY` | 画像分析 / 任务提交智能体的难度评估、成员推断、风险归因 | `sk-xxxxxxxx` |
| `AICAP_LLM_API_KEY` | 会议智能体的会议分析 | 同一把 Key 即可 |

PowerShell 启动后端前注入(每次新开终端都要设置,`setx` 只对**之后**新开的进程生效):

```powershell
$env:PROFILE_LLM_API_KEY = "sk-你的key"
$env:AICAP_LLM_API_KEY   = "sk-你的key"
cd D:\AIcap\java-backend
mvn spring-boot:run        # 或 java -jar target\aicap-java-backend.jar
```

> 未配置 Key 时后端仍可运行,AI 分析会回退为「规则打分 + 明确标注的数据不足」,**不会伪造模型结论**;配置后自动启用真实模型。

### 2. GitHub 同步(活动数据接入)

后端以 **GitHub REST API(用 PAT 认证)** 拉取仓库的 Commit / PR / Review / Issue 事件,映射到系统成员后落库 `activity_records(source='github')`,供成员任务图、画像分析、任务提交智能体消费。

| 环境变量 | 必填 | 说明 |
|---|---|---|
| `GITHUB_ENABLED` | 是 | `true` 开启同步 |
| `GITHUB_REPO` | 是 | 仓库名,格式 `owner/repo`,如 `lili618li/aiglcs` |
| `GITHUB_TOKEN` | 是 | Personal Access Token(fine-grained PAT 需授予仓库 Contents:Read / Pull requests:Read 等只读权限;不要把 token 提交进仓库) |
| `GITHUB_USER_MAPPING` | 是 | GitHub 用户名 → 系统成员真名 的 JSON 映射,如 `{"lili618li":"李锐铭","Wayne-SQS":"孙秋实","LHWYAN":"罗子涵","13555853258":"高思晗"}` |

一次性启动示例(把上面的 LLM 变量一并带上):

```powershell
$env:GITHUB_ENABLED="true"
$env:GITHUB_REPO="lili618li/aiglcs"
$env:GITHUB_TOKEN="github_pat_..."
$env:GITHUB_USER_MAPPING='{"lili618li":"李锐铭","Wayne-SQS":"孙秋实","LHWYAN":"罗子涵","13555853258":"高思晗"}'
cd D:\AIcap\java-backend; mvn spring-boot:run
```

**同步机制与边界**:
- 手动触发:前端「AI 助手 → 任务提交智能体 / 画像智能体 → ⟳ 立即同步」(仅管理员/负责人可见按钮);或 `POST /api/profile-agent/github/sync?start=YYYY-MM-DD&end=YYYY-MM-DD`
- 幂等:同一 GitHub 事件按 `github_event_id` 唯一入库,重复同步只跳过不重复插入
- 成员映射:未在 `GITHUB_USER_MAPPING` 中的提交者不入库,同步结果中如实列出警告条数
- 统计口径:`pulled`=本次真实拉取数 / `synced`=新增入库 / `skipped`=跳过(含已存在与未映射)
- 同步状态查询:`GET /api/profile-agent/github/status`

### 3. 安全须知

- API Key 与 GitHub Token **一律走环境变量**,`.gitignore` 已忽略运行产物;不要把密钥写进任何 `.md/.bat/.vue/.java` 后提交
- 同步按钮仅管理员/负责人可见;送审建议进入「AI 审核中心」,**人工审核通过才会执行**,LLM 只提出建议不直接改数据

## 功能视图

| 视图 | 说明 |
|---|---|
| ◈ 项目总览 | 故事/任务统计、3 个 Sprint 进度(工时加权血缘口径)、里程碑、总完成度、实时进度 |
| ◆ 需求池 | 会议/临时需求暂存,可「移入看板」成正式故事(选择 Sprint / 负责人) |
| ▦ 用户故事看板 | 默认「故事地图」(含 Sprint 4+ 后续路线切片)、拖拽、编辑、搜索筛选、JSON/CSV 导出、变更日志 |
| ▤ 甘特图 | 父卡血缘行(子任务周并集 + 工时加权进度) + 子任务行、Sprint 分带表头、点击任务条看前置/后续并可直接编辑任务(真实 PATCH) |
| ▥ 成员任务图 | 5 名真实成员、点击卡片开任务明细抽屉(状态筛选)、周负载热力图(含负载图例与周容量口径)、容量 Bandwidth、成员开发活动图 |
| ◇ UML 图 | 真实 SVG 用例图(按 US01–US37 映射,可悬停高亮/点击看故事) + 时序图(按 Spring Boot 真实路由) |
| ✦ AI 助手 | 6 项能力(演示)、会议智能体(真实 DeepSeek 分析 + 网页录音)、任务提交智能体 |
| ✓ AI 审核中心 | 在线会议建议采纳/拒绝后端留痕；修改后采纳仍仅离线演示 |

## 会议 Agent（DeepSeek）

已实现文本会议分析、只读项目工具调用、结构化结果和证据校验、后台运行记录、失败重试及自动生成待审需求建议。填入服务端 DeepSeek Key 后，在 AI 助手选择已保存会议并点击“分析会议”。

配置和验证边界见 [会议 Agent：DeepSeek 运行与验收](docs/会议Agent_DeepSeek运行与验收.md)。本轮测试使用明确标记的模型夹具；真实模型效果仍需配置密钥后验收。

## 成员画像（按角色差异化）

「成员任务图」页展示每位成员的真实画像：岗位、经验年限、画像摘要，以及三个维度——**技术栈 / 工作能力 / 熟悉的开发流程领域**（每项带熟练度 1–5）。5 名成员画像与角色一一对应（产品与范围 / 故事与 AI 协作 / 架构与后端 / 测试与风险 / 只读查看者），**互不相同**，作为分工与排期依据。

- 读：`GET /api/members/profiles`（任意登录用户）
- 写：`PATCH /api/members/{userId}/profile`（admin/owner 可改任何人；member 只能改本人；viewer 只读 → 403）
- 契约：三个维度必须给出（缺字段 422）、同维度名称不可重复、熟练度 1–5；全局严格 JSON（未知字段 422）
- 落库：`member_profiles`（1:1 `users`，三个维度以 JSON 数组文本存放）；启动按 id 顺序幂等播种，已有画像不覆盖

## 会议录音（麦克风 → MP3）

「AI 助手 → 会议智能体」页内置录音：浏览器调用麦克风（`getUserMedia` + `MediaRecorder`），录音结束后在**浏览器内**解码为 PCM 并用 `lamejs` 编码成**真 .mp3** 后提交到当前会议；同时支持选择本地 `.mp3` 文件提交。音频可回放/下载，删除仅限管理员与负责人。

- 上传：`POST /api/meetings/{id}/audio`（multipart：`file`、`source=recorder|upload`、`duration_ms`）
- 列表：`GET /api/meetings/{id}/audio`；回放：`GET /api/audio/{id}`（需鉴权，前端取字节转 objectURL）；删除：`DELETE /api/audio/{id}`
- 校验：仅 `.mp3`（扩展名 + 魔数双校验）、单文件默认 25MB（`AICAP_AUDIO_MAX_BYTES` 或 `AICAP_AUDIO_MAX_BYTES_BYTES`）
- 落盘：`java-backend/data/audio`（`AICAP_AUDIO_DIR` 可覆盖，已 gitignore），元数据在 `meeting_audio`
- 为什么不用 ffmpeg：浏览器 `MediaRecorder` 原生只出 webm/opus 或 mp4/aac，不出 mp3；引入 `lamejs`(MIT) 在客户端转码，服务端无需安装 ffmpeg

## 会议删除

`DELETE /api/meetings/{meetingId}`（admin/owner，即 reviewer 角色）：在**事务内手工反序级联**清理 事件 → 运行记录 → 音频元数据 → 审批载荷 → 建议及其审核记录 → 会议本体；音频**磁盘文件在事务提交后** best-effort 删除。响应回传 `deleted` / `audio_deleted` / `suggestions_deleted` / `runs_deleted` 计数。

- 刻意保留的边界：**已审批落库的需求池条目是独立产物,不随会议删除**（契约用例 `MEET-06` 专门断言）,需另行删除
- 前端入口：「AI 助手 → 会议智能体」内二次确认;member/viewer 禁用并给出角色原因（后端同样返回 403）
- 契约用例：`MEET-06`（级联成功）/ `MEET-07`（不存在 → 404）/ `MEET-08`（越权 → 403）

## 静态原型新版内容移植(mcc 提交 `9b14ec5`)

队友 mcc 在静态单文件原型上新提交了一版「四类视图完善+一体化」(`legacy/index.html`,现在就是仓库里那份),本轮把其中的**内容与样式**移植进 Vue,但**交互全部按 Vue 重写**(她的原实现是内联 `onclick` + 全局事件委托、编辑后不刷新其它视图),并按「不牺牲既有能力」的原则处理冲突:

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

验收:`cd frontend && npm run e2e:verify`(系统 Edge,无需下载 Playwright 浏览器)。新增 `frontend/e2e-verify/mcc-port.spec.js` 覆盖上述移植项(基线/看板血缘/甘特详情与任务编辑往返/成员抽屉/UML/离线旧缓存回落)。

## 会议建议审核 MVP

已接通会议转写保存、待审建议提交、管理员/负责人采纳或拒绝、采纳后新增需求池及服务器审核记录。包含提交幂等和重复批准保护；在线页面明确标记手动录入，**保留手动入口，自动分析需配置服务端模型密钥**。

运行、接口和 Agent 对接样例见 [会议建议审核 MVP:运行与接入](docs/会议建议审核MVP_运行与接入.md)。(旧版单文件前端的部署需同时包含 `index.html`、`meeting-review.js` 和 `meeting-agent.js`,已随 `legacy/` 归档;现行前端为 `frontend/`。)

## 当前进度(交接说明)

**已完成(阶段 1)**
- 前端 8 视图像素风原型:看板拖拽/编辑/导出、故事地图、需求池、甘特、成员负载、UML、AI 助手(演示)、AI 审核中心(演示)
- 后端:现行为 JavaWeb 版 `java-backend/`(Spring Boot 3.5 + MyBatis-Plus,默认 8080):登录(JWT)、故事/需求池/任务/仪表盘 REST API、变更日志、成员列表;FastAPI 版(124 项 pytest 全绿)已归档至 `backend/` 作参考实现
- 会议 AI Agent(真实调用 DeepSeek:只读工具循环 + 证据校验 + 待审建议)、成员差异化画像、会议录音(浏览器转 MP3)
- 静态原型新版内容移植(US01–US37 基线 / 甘特任务编辑 / 成员任务抽屉 / UML 真实 SVG / 开发活动图),见上一节
- 后端契约测试 **126 项**全绿(`cd java-backend && mvn test`);前端浏览器验收 `cd frontend && npm run e2e:verify`(**7 项**:基线与看板血缘、甘特详情与任务编辑往返、成员抽屉、UML、离线回落、成员画像、录音全链路;系统 Edge + 虚拟麦克风);QA 基线 E2E `qa/`(**35 项**:`npx playwright test`)
- MySQL 8.0(Docker,库 `AIcap`);前端后端在线走 API、离线自动回退演示数据
- QA:**168 条**用例(后端 126 / 前端 35 / 验收 7)一键编排全绿(`& .\qa\run-all.ps1`,exit 0);9 条缺陷中 7 条已修复并回归,余 2 条为环境项(见 `qa/测试报告_当前基线_v3.md`)

**数据说明**:每人本地运行各自一套数据库(互不互通但完全可用);如需共享数据,由一人当"服务器"运行后端,其余人把前端 API 地址指向其局域网 IP(默认 8080,可用 `VITE_API_BASE` 环境变量或 `frontend/src/api/client.js` 默认值设置)。

**待办(阶段 2+)**:
- 会议录音的语音转写(ASR)接入;会议 Agent 真实模型评测
- 任务提交智能体 + GitHub 事件同步
- 六项 AI 能力接入真实大模型(DeepSeek 等,密钥放服务端)
- 上线部署:云服务器 + HTTPS,真·多人在线

## 默认账号(演示)

李锐铭(admin)/高思晗(owner)/孙秋实、罗子涵(member)/成员5(只读查看者,display_name「只读查看者」),密码均为 `123456`。登录别名由后端映射:`成员1`–`成员4` 与真名**双向**互通;`成员5` 本身即该用户的 username,其显示名「只读查看者」也已支持直接登录。

## 技术栈

- 前端:Vue3 + Vite(`frontend/`);后端可用时走 REST API + JWT,否则回退 localStorage 演示
- 后端:Java 23 + Spring Boot 3.5 + MyBatis-Plus(`java-backend/`,默认 8080);MySQL 8.0(Docker,3307);FastAPI 旧版归档于 `backend/`
- 测试:后端 JUnit 契约测试(`java-backend/`,`mvn test`,**126 项**)+ Playwright E2E(`qa/`,**35 项**,系统 Edge)+ 浏览器验收(`frontend/e2e-verify`,**7 项**),由 `qa/run-all.ps1` 一键编排;FastAPI 旧版的 pytest 套件随 `backend/` 归档

## 会议交互修复（2026-09-07）

已支持修改后采纳并保留原始建议、移入看板时选择 Sprint/负责人，以及完整展示会议行动项、协调事项和验收约束。更新后重启后端、强制刷新前端；旧会议可复制并重新分析。

接口变更、验证情况及队友需要补充的任务能力见 [会议 Agent 交互修复与项目侧接续](docs/会议Agent交互修复与项目侧接续_2026-09-07.md)。
