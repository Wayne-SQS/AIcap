# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 仓库构成:哪些是现行的,哪些是归档的

| 目录 | 状态 |
|---|---|
| `frontend/` | **现行**前端:Vue3 + Vite + Pinia(像素风,15 视图) |
| `java-backend/` | **现行**平台后端:Java 23 + Spring Boot 3.5 + MyBatis-Plus(8080) |
| `ai-service/` | **现行**会议 Agent:Python + FastAPI + **LangGraph**(8090)。五类会议的类型化分析 + 音频转写/说话人分离;写库一律回 Java 业务接口 |
| `agent-orchestrator/` | **现行**「主管智能体」编排层(Python,8100)。**前端已接线**(2026-10-14:`SupervisorAvatar.vue` + `SupervisorChat.vue` 消费 SSE)。进度看契约 §8(见文末「设计文档的定位」),本层 `README.md` 的状态表是它的镜像——当前 **S5′**:S4′.2 的有界循环四道闸 + 检查点续跑 + SSE 事件流 + Java 侧会话/事件/检查点端点之上,又在循环前接上了规划相 `orchestrator.plan()`(真实实现:路由 + 反问阈值 + 触发型工具,**只有 `plan()`,没有 `execute()`**),并新增 `routing_logs` 表与 `POST/GET /api/agents/routing-logs`。**未落地**:`report.build_report()` 是唯一骨架(`raise NotImplementedError("复盘报告属 S8′")`);另两项契约里明确挂着 —— ⛔ `start_profile_analysis`(画像域无后台消费者)、⏳ `routing_logs` 真机联调(端点已落地,需 `mvn -B package` + 重启后端才能验通)。**不得持有数据库连接**——`tests/test_no_db.py` 结构性钉住 |
| `deploy/` | **现行**生产编排:`deploy/compose.yaml` 四容器(Nginx / Java / ai / MySQL) |
| `backend/` | **归档**:FastAPI + SQLAlchemy 参考实现。**不要按它改功能**,但它的 `docker-compose.yml` 仍是**本机开发/测试**起 MySQL(3307) + Qdrant(6333) 的现行方式;生产编排走 `deploy/compose.yaml`,两者别混用。已放 `DEPRECATED.md`(含删除前置条件) |
| `legacy/` | **归档**:Vue 改造前的旧版单文件前端。全仓零引用,可整体删。已放 `DEPRECATED.md` |
| `归档_其他材料/` | **归档**:实验指导书、交付物模板、早期规划稿(`.docx` / `.md`)。只读 |
| `docs/` | 设计规格与实施计划,见文末「设计文档的定位」 |
| `qa/` | 测试用例、执行记录、报告,以及前后端之外的第三套 Playwright 用例 |

功能开发一律落在 `java-backend/` + `frontend/` + `ai-service/`。`backend/`、`legacy/`、`归档_其他材料/` 只读。

**新人/新会话的入口不是本文**:根 `README.md` 指向 `上手指南_克隆后如何运行.md`(依赖准备、密钥、启动、健康检查)与 `deploy/README.md`(完整部署与验收)。本文只记那些"读代码读不出来"的约束与坑。

## 命令

### 环境前置

```bash
cd backend && docker compose up -d            # 起 MySQL(aiguanli-mysql, 3307)
cd backend && docker compose up -d qdrant     # 起 Qdrant(aicap-qdrant, 6333)
```

上面两条只用于**本机开发/测试**(`backend/docker-compose.yml`);**生产编排**是 `deploy/compose.yaml` 的四容器,两者别混用。

**全量 `mvn -B test` 现在需要 Qdrant 容器在跑**:索引与检索两组契约测试对**两套向量库实现各跑一遍**,
qdrant 那一遍连不上会直接失败。刻意不做成"连不上就跳过"——静默跳过等于那条实现又没人测,
而它恰恰是权限洞的高发地(ACL 落在 Qdrant 的 payload 里,不在 SQL 里)。
只跑与 RAG 无关的测试类时不需要它。

境内拉 `qdrant/qdrant` 会卡住,需先 `docker pull docker.1ms.run/qdrant/qdrant:latest` 再 `docker tag` 回标准名。

**本机 JAVA_HOME 陷阱**:环境里默认是 JDK 8(`D:\jdk8u312`),编译会以 "无效的目标发行版: 23" 失败。每次构建前必须显式覆盖,且 Maven 不在 PATH 上:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-23"
export PATH="$JAVA_HOME/bin:/e/apache-maven-3.9.9/bin:$PATH"
```

### 后端

```bash
cd java-backend
mvn -B package -DskipTests          # 打包
mvn spring-boot:run                 # 启动(8080);或 java -jar target/aicap-java-backend.jar
mvn -B test                         # 全量契约测试(项数随轮次增长,以实际输出为准)

# 单个测试类 / 单个方法
mvn -B test -Dtest=RetrievalMySqlContractTest -DfailIfNoSpecifiedTests=false
mvn -B test -Dtest='RetrievalMySqlContractTest#fusionDeduplicatesAcrossRoutes' -DfailIfNoSpecifiedTests=false
# 某一族的全部实现(mysql + qdrant 两遍一起跑)
mvn -B test -Dtest='Retrieval*ContractTest' -DfailIfNoSpecifiedTests=false
```

> 注意 `RetrievalContractTest` / `KnowledgeIndexContractTest` 是**抽象基类**,不能直接当
> `-Dtest` 指定 —— 它们没有可实例化的实现,指定了只会匹配到 0 个用例。

健康检查:`http://127.0.0.1:8080/api/health` → `{"status":"ok","db":true,"workers":{...}}`。`workers` 是三个后台任务的快照(`meeting-agent` / `planning-agent` / `project-generation`),键随各域自注册的 `WorkerTask` 走 —— 某域退役时对应键自动消失,不需要改 `HealthController`。

`java-backend/启动后端.ps1` 是给 Windows 用的启动脚本:检查 8080 占用、可选 `-Build`、从 **Windows 凭据管理器**取 GitHub token、从 `backend/.env` 取 LLM Key,再注入环境变量后台启动。密钥只在运行时读取,不落盘。

### 前端

```bash
cd frontend
npm install
npm run dev        # 必须用 http://localhost:5173 访问
npm run build
npm run e2e:verify # 浏览器验收(frontend/e2e-verify/,19 个 spec / 37 例;走系统 Edge)
npm run e2e:smoke  # 同配置,但 --grep 只跑 8 个命名场景(离线演示/AI 工作台/跨来源队列/…)
```

**Vite dev 只监听 `[::1]:5173`** —— 用 `127.0.0.1:5173` 会连不上。

### 会议 Agent(`ai-service/`)

**独立 venv** —— 不要复用 `backend/.venv`(它没装 LangGraph 依赖),命令必须在 `ai-service/` 目录内跑:

```bash
cd ai-service
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe start_service.py                    # 推荐入口(8090,前台);--check-only 只体检
.venv/Scripts/python.exe -m unittest discover -s tests -v    # 全部用例
.venv/Scripts/python.exe -m pip check
```

音频链路另需 `requirements-stt.txt`(faster-whisper)与 `requirements-diarization.txt`(sherpa-onnx),模型准备脚本 `prepare_stt.py` / `prepare_diarization.py`,产物落 `ai-service/.models/`(不提交)。

### 编排层(`agent-orchestrator/`)

```bash
cd agent-orchestrator
python -m venv .venv
.venv/Scripts/python -m pip install -e ".[dev]"
.venv/Scripts/python -m pytest -q                            # 全绿;不需要 Docker/MySQL/Java
.venv/Scripts/python -m uvicorn app.main:app --port 8100
```

Java 没起时本层照样起得来:`GET /health` 报 `"java": false` 但自身仍 200,**不会**降级到假数据。

### 生产编排(`deploy/`)

完整流程(准备模型、密钥、备份恢复、八条浏览器闭环)见 `deploy/README.md`。核心三条:

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --build
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
ai-service/.venv/Scripts/python.exe -B deploy/verify.py --env-file deploy/.env --live-url http://127.0.0.1:8088
```

`deploy/verify.py` 不联网、不调模型;加了 `--live-url` 才连运行中的编排。浏览器只访问 Nginx 的 `127.0.0.1:8088`;`down` 不删数据卷,`down --volumes` 才删。

### 全部测试套件

```powershell
& .\qa\run-all.ps1                    # 后端 JUnit + qa/ Playwright + frontend 验收
& .\qa\run-all.ps1 -SkipE2E           # 只跑后端
```

前置:MySQL 3307、后端 8080、Vite 5173 **都已在跑**(脚本只检测不启动)。另有 `-SkipBackend` / `-SkipAcceptance` / `-JavaHome`。

```bash
cd qa && npm install && npx playwright test    # 单独跑 qa/ 那套 E2E
```

> `.ps1` 脚本体必须**保持 ASCII**:Windows PowerShell 会把 `.ps1` 当 GBK 解析,中文注释会直接破坏解析器。`run-all.ps1` 的注释全英文就是这个原因。

## 架构要点

三层:`frontend/`(5173) → `java-backend/`(8080) → MySQL(3307)。前端在后端不可用时自动回退到 localStorage 演示数据(US01–US37 + T01–T16),界面上会明示演示模式。

**数据库自举(无迁移步骤)**:后端启动时 `spring.sql.init` 无条件执行 `db/schema.sql`(全部 `CREATE TABLE IF NOT EXISTS`),`config/SchemaUpgrader.java` 给**已有表**补后加的列,`config/DataSeeder.java` 在表为空时播种 5 个演示用户(密码 `123456`)/ 37 个故事(US01–US37)/ 16 个任务(T01–T16)/ 5 份成员画像。全新空库直接启动即可。存量库里的旧故事基线(M01–M23)会被自动替换为 US01–US37,整个播种在一个事务里。

改表结构的两种落点:新表 → 追加进 `db/schema.sql`;给已有表加列 → 追加进 `SchemaUpgrader.java`(不要改 schema.sql 里的既有定义,老库不会重跑)。

**智能体层**:LLM 驱动的分析任务,分布在 `ai-service/`(会议 Agent,五类会议)和 `profile/`(画像 Agent,8 个工具)。**后台调度器与具体域无关**:`worker/AgentWorker` 只认 `WorkerTask` 接口(`name()` + `runNext()`),各域在自己的包里注册一个薄壳(`planning/PlanningWorkerTask`、`generation/GenerationWorkerTask`)。共享的 LLM 配置与传输在 `llm/`(`LlmProperties` 绑 `aicap.llm.*`、`ModelClient`),域错误在 `common/AgentError`。画像 Agent 有双模式:`GET /analysis` 零 LLM 只读预览,`POST /analysis/run` 正式分析且失败逐项降级留痕。两者都是**建议生产者**,不直接写业务表。

> **2026-10-13 变更：上一代会议 Agent（`java-backend/.../agent/`）已整包删除。**
> 前置剥离此前已完成，故删除无副作用：`rag/TranscriptSegmenter`（切分器已从 `AnalysisValidator` 拆出共用）、
> `llm/LlmProperties`、`common/AgentError`、`platform/MeetingTools`（5 个会议只读工具已拆到平台包，
> **不随 `agent/` 退役**）。平台层经 `platform/MeetingAnalysisTrigger` 接口 + `ObjectProvider`
> 可选注入调用，删除后该工具如实报 `agent_tool_unavailable` 而非 500。
>
> **随本次删除一并退役的能力（缺口，待补）**：老家 `AnalysisValidator` 是**运行时阻断式**
> 证据校验（`invented_assignment` 拦截编造负责人 / `invalid_evidence` 逐字核对 / `incomplete_analysis`
> 片段全覆盖），新链路 `ai-service/evaluation.py` 仅为**离线评测脚本、不阻断**。
> 即：迁移后模型伪造 quote 可能查无此句而系统不报错，属「测试不会变红」的静默降级。
> 另外老家的**开放类八项产出**（decisions / coordination_items / status_constraints / source_notes /
> risks / unresolved_questions / limitations 等）在新家无对应，前端「通用会议 Agent」面板已一并移除。

**平台工具层(S3)**:编排层与 Java 之间**唯一**的调用面 —— `GET /api/agents/catalog`(能力清单,只含元数据)与 `POST /api/agents/tools/{name}`。当前 **16 枚工具 = 14 只读**(5 会议 + 8 画像 + 1 `semantic_search`)**+ 2 触发型**(`start_meeting_analysis` / `start_plan_draft`,`read_only=false`;**只创建 run 落审批队列,不写业务表** —— 第三枚 `start_profile_analysis` 因画像域无后台消费者而暂缓,缺口见契约 §2.4)。工具清单**运行时从 `/catalog` 拉取、不硬编码**,主管的路由集合由 catalog 里 `read_only=false` 的那些派生,新增工具只改 Java、Python 零改动。契约由 `PlatformAgentContractTest`(PLAT-01…20)钉住。注意:那 5 个会议只读工具是**平台资产**而非老会议 Agent 的一部分,已从 `agent/AgentTools` 拆到 `com.aicap.platform.MeetingTools`(与 `PlatformToolCatalog` 对外发布的 `agent="meeting"` 契约同名),`PlatformToolCatalog` / `PlatformToolService` 直接依赖它 —— 退役 `agent/` 时它**不跟着删**。另:PLAT-17 钉的是 `aicap.llm.agent-worker-enabled` 真的绑到 `LlmProperties`(本仓曾因少写 `llm.` 前缀而静默失效,见 `qa/测试用例设计_当前基线_v3.md:680`)。

**RAG 检索层**(`rag/`):`Chunker`(6 类数据源,结构化行拼「增强前缀」)→ `EmbeddingModel` → `VectorStore`(双实现:`MysqlVectorStore` 应用层暴力余弦 / `QdrantVectorStore` REST 调 ANN,由 `AICAP_VECTOR_STORE` 切换)→ `RetrievalService`(三路召回 + RRF 融合 + 精排 + ACL)。每次检索落一行 `retrieval_logs`,存三路/融合/最终三阶段的明细。

**ACL 只比一个数**:角色的等级化(`RetrievalContext.rankOf`,ladder = member/owner/admin)只在 Java 侧算一次,索引时写进 `knowledge_chunks.acl_rank`,同一个数也进 Qdrant 的 payload。检索时 MySQL 比 `acl_rank <= roleRank`、Qdrant 用 `range.lte`,两边过滤的是同一个标量,SQL 里不再出现角色名字面量。

> **不要在 SQL 里写 `FIELD(c.acl_role, 'member','owner','admin')`**。它对本项目 ladder 之外的角色返回 **0**,而 `0 <= 任何等级` 恒成立 —— 等于「角色名写错 → 人人可见」;而 `rankOf` 对同样的输入返回最高要求(默认拒绝)。两处编码一旦分叉,同一个块会在 MySQL 上人人可见、在 Qdrant 上只有 admin 可见,而当时的用例全用合法角色名,两边都绿。契约测试 `RetrievalContractTest#unknownAclRoleFailsClosedInsteadOfOpen` 钉住这个方向(它确实会在旧写法上失败)。

**契约测试**(`src/test/java/com/aicap/contract/`):黑盒 REST(`TestRestTemplate` + `RANDOM_PORT`),继承 `ContractTestSupport`(真登录拿 JWT、`USER_ADMIN`/`USER_OWNER`/`USER_MEMBER`/`USER_VIEWER` 等常量、`assertStatus` 辅助)。**外部依赖一律用本地 fixture 服务替代**(`EmbeddingFixture` 19378、`RerankModelFixture` 19379、`AgentModelFixture`),零外网零费用。测试库是 **`aicap_java_test`**(与开发库 `AIcap` 同服务器不同库),数据源**逐类内联**在 `@SpringBootTest(properties=...)` 里 —— 没有 `application-test.yml` 这种 profile,新测试类照抄现有类的 properties 块。`src/test/resources/db/reset_test_data.sql` 负责清库。

**参数化跑两套向量库实现**:`KnowledgeIndexContractTest` 与 `RetrievalContractTest` 是**抽象基类**(逻辑全在里面),各有两个子类 `*MySqlContractTest` / `*QdrantContractTest` 只差三行注解。为什么不用 `@ParameterizedTest`:实现是 `@ConditionalOnProperty` 在**启动时**选定的 Bean,运行期换不了,而 `@SpringBootTest` 的 properties 必须是编译期常量;`@Nested` 也不行,Spring 不为嵌套类单建上下文。隔离靠 `RagProperties.vectorStore` 钩子 `resetVectorStoreBeforeIndex()`:MySQL 侧由 `reset_test_data.sql` 完成,Qdrant 侧由 `QdrantTestSupport.resetCollection()` 删掉 `aicap_chunks_test` 集合 —— **那个集合名必须以 `_test` 结尾**,工具里有一道闸门拦着,配错就是删开发数据。

新增同类测试时,若某个失效模式只在一条实现上存在(典型:ACL 在 SQL 里 vs 在 payload 里),**务必两遍都跑** —— 只测一条等于只验了一半,而漏的那一半正好是"语义检索绕过可见性"的后门。

**`projectId` 由基类统一注入(2026-10-14)**:多项目改造后所有业务接口的 `projectId` 是**必填** `@RequestParam`,但几百个历史调用点都没带 —— 于是每个用例卡在同一个 422 上,真正的断言根本跑不到(135 个红的真因就是这个,不是缺列)。`ContractTestSupport` 现在自动补:URL 上一律补 `?projectId=P001`(`PROJECT_ID`,登录与项目列表除外);请求体只在白名单路径补(`/api/meetings` → `project_id`,`/api/agents/tools/` → `project_id`)—— DTO 是 strict(未知字段拒绝),**不能对所有 body 无脑注入**。multipart 请求不经过基类(自己 `rest.exchange`),要自己把 `projectId` 拼进 URL,**只拼一处**:query 与表单各放一份时 Spring 会把两个值拼成 `P001,P001`,症状是 404「项目不存在或已归档」,比缺参数还难认。要专门验证"缺 project_id 会被拒"的用例走 `requestWithoutProject(...)`,别硬碰注入。

**老库升级别只改 `schema.sql`(2026-10-14 的教训)**:多项目改造把 stories/tasks/milestones 主键从业务编号换成 `internal_id` 自增 + `code` 编号,只改了建表语句 —— 新库是对的,**老库永远停在旧主键**,一读故事/任务就 `Unknown column 'internal_id'`。补列与数据回填现在都在 `SchemaUpgrader`(`migrateInternalKeys` / `backfillReferenceSnapshots` / `ensureReferencedProjectsExist`),改完务必跑一次"实体字段 vs 数据库列"核对(`java-backend/data/check_entity_columns.py`,它列出"实体有、表里没有"的列,这类缺失编译不报错、只在运行时 500)。另外 `story_logs`/`activity_records`/`difficulty_assessments` 还要补 `*_internal_id` + `*_code_snapshot`:编号可在项目内复用,没有快照就无法回答"这条日志当时指的是哪个故事";缺了它**删故事会 500**。

## 红线(任何改动不得违反)

1. **模型绝不直接写库**;模型产出的一律经审批队列(`AgentJobs` / `meeting_suggestion_records`)。
2. **上游非 200 绝不落响应体**(防凭据/隐私回显)。
3. **检索层必须做 ACL 过滤**,不得让成员经语义检索绕过 `ResourceController` 的可见性限制。ACL 在 SQL/payload 内过滤,不能捞回来再筛(top-k 会被无权结果占满)。判定只比 `acl_rank`(见「架构要点」),**不要在 SQL 里重新编码角色等级** —— 两份 ladder 必然在「未识别角色」上分叉,而其中一边是放行。
4. **结构化条件(时间/状态/外键)走 SQL,语义条件才走向量** —— 不许把 `query_activities`、`query_tasks` 这类改成向量检索。
5. **ai-service 的配置面是刻意收紧的**:用户 Bearer 令牌只存在于单次调用闭包,**不进入图状态或模型消息**;禁用 LangSmith tracing,**不配置 checkpointer、不持久化会议内容**(`ai-service/README.md:58`)。LangGraph 的 `checkpointer` / `interrupt` 是**刻意关闭**,不是"能力闲置" —— 接上它等于把转写与候选产出持久化。

## 约定

- **本仓没有 linter**:`frontend/package.json` 无 eslint 脚本,`java-backend/pom.xml` 无 checkstyle/spotbugs/pmd。质量闸门是**契约测试**与 `agent-orchestrator/tests/test_no_db.py`、`arch/AgentPackageIsSelfContainedTest` 这类结构性测试,不是静态检查 —— 别去找或新引一个 lint 工具。
- **不引第三方 SDK**:HTTP 一律 JDK `HttpClient`,JSON 一律 Jackson(`ObjectMapper`)。已刻意排除 OkHttp/Retrofit/Feign 与 Qdrant Java SDK。
- 配置走 `@ConfigurationProperties` + `application.yml` 的 `${ENV_VAR:默认值}`;密钥默认留空,由环境变量注入。
- **不改动 `profile/`、`planning/`、`generation/`、`rag/`、`platform/` 现有类的公开签名** —— 新能力加在旁路,不改老接口。
- 建表/补列方式见上文「数据库自举」。

### 文档纪律(2026-10-13 立)

本仓文档一度膨胀到 **75 份 / 85.5 万字符**,且与代码严重漂移。以下为强制约定:

1. **分三层,不新增第四层**:`01-产品`(做什么,冻结带版本)/ `02-契约`(接口与进度,活文档)/ `03-能力`(功能细节)。过程记录一律进 `_process/`。
2. **智能体名单只在一处定义**:`docs/01-产品/智能体产品契约.md`。任何文档、UI、接口出现第二份名单视为分叉,须先回来改契约文件。
3. **进度口径唯一**:`docs/02-契约/主管智能体_落地方案_v1.0.md §7`。`AIcap-Agent能力扩展_实施计划.md` 的 Sprint 编号属**另一条线**,引用时必须写明是哪条线,或直接改用 §7 的编号。
4. **禁止为"记录过程"新增文档**:开发日志写在 `_process/` 下按日期追加,不新建"第 N 轮"文档。
5. **改代码必须改文档**:结构性删除/重命名后,同 PR 内必须同步所有提及该结构的文档,否则视为未完成。

## 已知坑

- **数据库连不上会伪装成"Bean 装配失败"**:Docker Desktop 没在跑时(容器 `aiguanli-mysql` / `aicap-qdrant` 随之消失),`mvn -B test` 的报错是 `UnsatisfiedDependencyException: Error creating bean ... meetingMapper ... dataSourceScriptDatabaseInitializer: Failed to execute database script`,真正的根因 `java.net.ConnectException: Connection refused` 埋在十几层 `Caused by` 之后。整套契约测试会**逐个类**这样失败,看起来像大面积回归,其实是环境。先 `docker ps` 确认两个容器在跑,再怀疑代码。
- **MyBatis-Plus 注解 SQL**:含 `<script>` 的语句会被当 XML 解析,裸 `<` / `<=` 直接抛 `SAXParseException`(表现为 `Failed to load ApplicationContext`),必须写成 `&lt;=` 或改用 `FIND_IN_SET` 之类回避。不含 `<script>` 的语句不解析,`<=` 正常。
- **README 的测试数字是分层口径**（v3 用例设计文档记 180 条设计，其中后端 128；正文注释记实际 148 项），已被 RAG 层 S1/S2 与随后的 ACL 加固、主管三件套（会话/事件/检查点）、S5′ 路由与触发型工具等超过，**实际 387**（契约目录 50 个类的 `@Test` 计数;抽象基类里的 `@Test` 会被子类各跑一遍,故注解数会**低于**实际执行数）+ 目录外的纯单测 = `mvn -B test` 全量 **501**（均为 2026-10-11 22:10 实测,全绿）。README 里已加注说明，但被问到时一律以 `mvn -B test` 的实际输出为准。
- **开发库 `AIcap` 里的向量来自本地 fixture 桩**(64 维,非真实模型),配好真实密钥后跑一次 `reindex` 会自动全部重算(模型名不一致即判过期)。
- **ACL 默认全开**:`aicap.rag.acl` 在 `application.yml` 里是 `{}`,所有源都是 `member` 级,任何登录用户可见。机制存在但开关是关的 —— 配了什么源、什么级别是产品决定。
- **`aicap.rag.acl` 的取值必须在 ladder 里**:启动时校验(`RagProperties.validateAcl`,挂在记录生效配置的那个 `@PostConstruct` 上),配 `reviewer`(系统里真实存在却不在梯子上)、`viewer`、或大小写写错的 `MEMBER` 都**直接启动失败并打印键值**。校验之前它们会静默按「最高要求」处理(只有 admin 可见)—— `viewer` 尤其反直觉,读起来是"viewer 及以上",实际正好相反。校验**只覆盖值**:源类型名(键)拼错不拦,它落到 `aclRoleFor` 的默认 `member`,方向是放行。另外 `acl_role` 是 `varchar(16)`、梯子角色最长 6 字符,所以这条校验顺带堵死了"超长值启动不报错、要到重建索引才以 `DataIntegrityViolationException`(500)炸出来"那条路;将来往 ladder 加更长的角色名,要在这儿补一次长度校验。
- **给已有表补一个参与过滤的列时,必须同时写回填**:列全是 NULL 时 `col <= x` 的结果是 NULL、不成立,老库的数据会整批变得**不可见**(不是"少个字段",是搜不到任何东西)。补列方式见上文「数据库自举」——新列进 `SchemaUpgrader`,老库不重跑 `schema.sql`。
  **回填的判定要按数据,不能按「本次是否刚补列」**:MySQL 的 DDL 自动提交,ALTER 成功而随后那次 UPDATE 没提交(进程被杀 / UPDATE 超时 / 连接断),下次启动时列已存在 —— 按"刚补列"判定就永远不会再回填,列全 NULL、检索对所有人返回空且不报错。现用 `SchemaUpgrader.hasNullAclRank`(`SELECT 1 ... WHERE acl_rank IS NULL LIMIT 1`)判定,幂等自愈;实测把列手工补上留 NULL 再启动,旧判定一行都没回填。注意这条**测试库覆盖不到**:`reset_test_data.sql` 每次都 TRUNCATE `knowledge_chunks`,启动时表是空的。

## 设计文档的定位

`docs/AIcap-Agent能力扩展_实施计划.md` 是**活文档**:每完成一项就地回填勾选状态。**但它不是主管智能体那条线的进度口径** —— 该文档自己在 `S4` 行与 `:44` 的引用块里写明:原 `AgentGraph`/`Checkpointer` 方案已废弃,`S4′` 起进度看《落地方案》。实况也印证了这点(2026-10-11 22:00 实测:实施计划止于 10:36,落地方案更新到 22:07)。`docs/AIcap-智能体能力扩展设计_v1.0.md` 是设计依据,其 `§4 模块 C`(编排层)、`§5 模块 D`(多智能体 + MCP)、`§6 模块 E`(评测)尚未落地。改动这两份文档前先与作者确认。

**主管智能体两份**:`docs/主管智能体_落地方案_v1.0.md` 是编排层与平台之间的**跨进程实现契约**,**也是这条线进度的现行口径**(其 §8 的 S2.5→S8′ 表逐行记 ✅/🔶/⛔ 与实测数字) —— 其 §9 明写「实现中若发现契约有误,**先改本文再改代码**」;`docs/主管智能体_需求与规划_v1.1.md` 管需求与权限口径。

**会议 Agent 的现状面**:`docs/会议Agent_外部功能基线与新架构落点.md`(外部功能契约,含逐类核对的取证声明)、`docs/会议Agent_方案落地与首轮契约.md`(路线与历史进度)、`docs/会议Agent_五类会议试用指南.md`(首次试用)。`ai-service/README.md` 很长且逐轮追加,「当前仅 Daily」「下一轮」等是当轮历史范围 —— **最新状态以它末尾与各质量报告为准**。
