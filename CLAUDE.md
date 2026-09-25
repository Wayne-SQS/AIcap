# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 仓库构成:哪些是现行的,哪些是归档的

| 目录 | 状态 |
|---|---|
| `frontend/` | **现行**前端:Vue3 + Vite + Pinia(像素风,8 视图) |
| `java-backend/` | **现行**后端:Java 23 + Spring Boot 3.5 + MyBatis-Plus(8080) |
| `backend/` | **归档**:FastAPI + SQLAlchemy 参考实现。**不要按它改功能**,但它的 `docker-compose.yml` 仍是启动 MySQL 的现行方式 |
| `legacy/` | **归档**:Vue 改造前的旧版单文件前端 |
| `docs/` | 设计规格与实施计划(`AIcap-智能体能力扩展设计_v1.0.md`、`AIcap-Agent能力扩展_实施计划.md`) |
| `qa/` | 测试用例、执行记录、报告,以及前后端之外的第三套 Playwright 用例 |

功能开发一律落在 `java-backend/` + `frontend/`。`backend/` 只读。

## 命令

### 环境前置

```bash
cd backend && docker compose up -d            # 起 MySQL(aiguanli-mysql, 3307)
cd backend && docker compose up -d qdrant     # 起 Qdrant(aicap-qdrant, 6333)
```

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
mvn -B test                         # 全量契约测试(当前 223 项)

# 单个测试类 / 单个方法
mvn -B test -Dtest=RetrievalMySqlContractTest -DfailIfNoSpecifiedTests=false
mvn -B test -Dtest='RetrievalMySqlContractTest#fusionDeduplicatesAcrossRoutes' -DfailIfNoSpecifiedTests=false
# 某一族的全部实现(mysql + qdrant 两遍一起跑)
mvn -B test -Dtest='Retrieval*ContractTest' -DfailIfNoSpecifiedTests=false
```

> 注意 `RetrievalContractTest` / `KnowledgeIndexContractTest` 是**抽象基类**,不能直接当
> `-Dtest` 指定 —— 它们没有可实例化的实现,指定了只会匹配到 0 个用例。

健康检查:`http://127.0.0.1:8080/api/health` → `{"status":"ok","db":true}`

`java-backend/启动后端.ps1` 是给 Windows 用的启动脚本:检查 8080 占用、可选 `-Build`、从 **Windows 凭据管理器**取 GitHub token、从 `backend/.env` 取 LLM Key,再注入环境变量后台启动。密钥只在运行时读取,不落盘。

### 前端

```bash
cd frontend
npm install
npm run dev        # 必须用 http://localhost:5173 访问
npm run build
npm run e2e:verify # 浏览器验收(8 例,走系统 Edge)
```

**Vite dev 只监听 `[::1]:5173`** —— 用 `127.0.0.1:5173` 会连不上。

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

**智能体层**:LLM 驱动的分析任务,分布在 `agent/`(会议 Agent)和 `profile/`(画像 Agent,8 个工具)。会议 Agent 的形态是 `AgentJobs`(原子认领 + 租约 + 幂等队列)→ `AgentWorker`(轮询线程)→ `AgentRunner`(有界工具循环,`MAX_STEPS` + 工具去重 + 超时检查)。画像 Agent 有双模式:`GET /analysis` 零 LLM 只读预览,`POST /analysis/run` 正式分析且失败逐项降级留痕。两者都是**建议生产者**,不直接写业务表。

**RAG 检索层**(`rag/`):`Chunker`(6 类数据源,结构化行拼「增强前缀」)→ `EmbeddingModel` → `VectorStore`(双实现:`MysqlVectorStore` 应用层暴力余弦 / `QdrantVectorStore` REST 调 ANN,由 `AICAP_VECTOR_STORE` 切换)→ `RetrievalService`(三路召回 + RRF 融合 + 精排 + ACL)。每次检索落一行 `retrieval_logs`,存三路/融合/最终三阶段的明细。

**ACL 只比一个数**:角色的等级化(`RetrievalContext.rankOf`,ladder = member/owner/admin)只在 Java 侧算一次,索引时写进 `knowledge_chunks.acl_rank`,同一个数也进 Qdrant 的 payload。检索时 MySQL 比 `acl_rank <= roleRank`、Qdrant 用 `range.lte`,两边过滤的是同一个标量,SQL 里不再出现角色名字面量。

> **不要在 SQL 里写 `FIELD(c.acl_role, 'member','owner','admin')`**。它对本项目 ladder 之外的角色返回 **0**,而 `0 <= 任何等级` 恒成立 —— 等于「角色名写错 → 人人可见」;而 `rankOf` 对同样的输入返回最高要求(默认拒绝)。两处编码一旦分叉,同一个块会在 MySQL 上人人可见、在 Qdrant 上只有 admin 可见,而当时的用例全用合法角色名,两边都绿。契约测试 `RetrievalContractTest#unknownAclRoleFailsClosedInsteadOfOpen` 钉住这个方向(它确实会在旧写法上失败)。

**契约测试**(`src/test/java/com/aicap/contract/`):黑盒 REST(`TestRestTemplate` + `RANDOM_PORT`),继承 `ContractTestSupport`(真登录拿 JWT、`USER_ADMIN`/`USER_OWNER`/`USER_MEMBER`/`USER_VIEWER` 等常量、`assertStatus` 辅助)。**外部依赖一律用本地 fixture 服务替代**(`EmbeddingFixture` 19378、`RerankModelFixture` 19379、`AgentModelFixture`),零外网零费用。测试库是 **`aicap_java_test`**(与开发库 `AIcap` 同服务器不同库),数据源**逐类内联**在 `@SpringBootTest(properties=...)` 里 —— 没有 `application-test.yml` 这种 profile,新测试类照抄现有类的 properties 块。`src/test/resources/db/reset_test_data.sql` 负责清库。

**参数化跑两套向量库实现**:`KnowledgeIndexContractTest` 与 `RetrievalContractTest` 是**抽象基类**(逻辑全在里面),各有两个子类 `*MySqlContractTest` / `*QdrantContractTest` 只差三行注解。为什么不用 `@ParameterizedTest`:实现是 `@ConditionalOnProperty` 在**启动时**选定的 Bean,运行期换不了,而 `@SpringBootTest` 的 properties 必须是编译期常量;`@Nested` 也不行,Spring 不为嵌套类单建上下文。隔离靠 `RagProperties.vectorStore` 钩子 `resetVectorStoreBeforeIndex()`:MySQL 侧由 `reset_test_data.sql` 完成,Qdrant 侧由 `QdrantTestSupport.resetCollection()` 删掉 `aicap_chunks_test` 集合 —— **那个集合名必须以 `_test` 结尾**,工具里有一道闸门拦着,配错就是删开发数据。

新增同类测试时,若某个失效模式只在一条实现上存在(典型:ACL 在 SQL 里 vs 在 payload 里),**务必两遍都跑** —— 只测一条等于只验了一半,而漏的那一半正好是"语义检索绕过可见性"的后门。

## 红线(任何改动不得违反)

1. **模型绝不直接写库**;模型产出的一律经审批队列(`AgentJobs` / `meeting_suggestion_records`)。
2. **上游非 200 绝不落响应体**(防凭据/隐私回显)。
3. **检索层必须做 ACL 过滤**,不得让成员经语义检索绕过 `ResourceController` 的可见性限制。ACL 在 SQL/payload 内过滤,不能捞回来再筛(top-k 会被无权结果占满)。判定只比 `acl_rank`(见「架构要点」),**不要在 SQL 里重新编码角色等级** —— 两份 ladder 必然在「未识别角色」上分叉,而其中一边是放行。
4. **结构化条件(时间/状态/外键)走 SQL,语义条件才走向量** —— 不许把 `query_activities`、`query_tasks` 这类改成向量检索。

## 约定

- **不引第三方 SDK**:HTTP 一律 JDK `HttpClient`,JSON 一律 Jackson(`ObjectMapper`)。已刻意排除 OkHttp/Retrofit/Feign 与 Qdrant Java SDK。
- 配置走 `@ConfigurationProperties` + `application.yml` 的 `${ENV_VAR:默认值}`;密钥默认留空,由环境变量注入。
- **不改动 `agent/`、`profile/` 现有类的公开签名** —— 新能力加在旁路,不改老接口。
- 建表/补列方式见上文「数据库自举」。

## 已知坑

- **MyBatis-Plus 注解 SQL**:含 `<script>` 的语句会被当 XML 解析,裸 `<` / `<=` 直接抛 `SAXParseException`(表现为 `Failed to load ApplicationContext`),必须写成 `&lt;=` 或改用 `FIND_IN_SET` 之类回避。不含 `<script>` 的语句不解析,`<=` 正常。
- **README 的测试数字是分层口径**(v3 用例设计文档记 180 条设计,其中后端 128;正文注释记实际 148 项),已被 RAG 层 S1/S2 与随后的 ACL 加固超过,**实际 223**。README 里已加注说明,但被问到时一律以 `mvn -B test` 的实际输出为准。
- **开发库 `AIcap` 里的向量来自本地 fixture 桩**(64 维,非真实模型),配好真实密钥后跑一次 `reindex` 会自动全部重算(模型名不一致即判过期)。
- **ACL 默认全开**:`aicap.rag.acl` 在 `application.yml` 里是 `{}`,所有源都是 `member` 级,任何登录用户可见。机制存在但开关是关的 —— 配了什么源、什么级别是产品决定。
- **`aicap.rag.acl` 的取值必须在 ladder 里**:启动时校验(`RagProperties.validateAcl`,挂在记录生效配置的那个 `@PostConstruct` 上),配 `reviewer`(系统里真实存在却不在梯子上)、`viewer`、或大小写写错的 `MEMBER` 都**直接启动失败并打印键值**。校验之前它们会静默按「最高要求」处理(只有 admin 可见)—— `viewer` 尤其反直觉,读起来是"viewer 及以上",实际正好相反。校验**只覆盖值**:源类型名(键)拼错不拦,它落到 `aclRoleFor` 的默认 `member`,方向是放行。另外 `acl_role` 是 `varchar(16)`、梯子角色最长 6 字符,所以这条校验顺带堵死了"超长值启动不报错、要到重建索引才以 `DataIntegrityViolationException`(500)炸出来"那条路;将来往 ladder 加更长的角色名,要在这儿补一次长度校验。
- **给已有表补一个参与过滤的列时,必须同时写回填**:列全是 NULL 时 `col <= x` 的结果是 NULL、不成立,老库的数据会整批变得**不可见**(不是"少个字段",是搜不到任何东西)。补列方式见上文「数据库自举」——新列进 `SchemaUpgrader`,老库不重跑 `schema.sql`。
  **回填的判定要按数据,不能按「本次是否刚补列」**:MySQL 的 DDL 自动提交,ALTER 成功而随后那次 UPDATE 没提交(进程被杀 / UPDATE 超时 / 连接断),下次启动时列已存在 —— 按"刚补列"判定就永远不会再回填,列全 NULL、检索对所有人返回空且不报错。现用 `SchemaUpgrader.hasNullAclRank`(`SELECT 1 ... WHERE acl_rank IS NULL LIMIT 1`)判定,幂等自愈;实测把列手工补上留 NULL 再启动,旧判定一行都没回填。注意这条**测试库覆盖不到**:`reset_test_data.sql` 每次都 TRUNCATE `knowledge_chunks`,启动时表是空的。

## 设计文档的定位

`docs/AIcap-Agent能力扩展_实施计划.md` 是**活文档**:每完成一项就地回填勾选状态,是进度的唯一口径。`docs/AIcap-智能体能力扩展设计_v1.0.md` 是设计依据,其 `§4 模块 C`(编排层)、`§5 模块 D`(多智能体 + MCP)、`§6 模块 E`(评测)尚未落地。改动这两份文档前先与作者确认。
