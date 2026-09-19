# AIcap Agent 能力扩展 · 实施计划与进度

> 设计依据：[`AIcap-智能体能力扩展设计_v1.0.md`](./AIcap-智能体能力扩展设计_v1.0.md)
> 基线：`Wayne-SQS/AIcap` @ `main` `bb15fc7`（68 commits，2026-09-19）
> 本文件是**活文档**：每完成一项就地回填，S2~S8 以本文件的勾选状态为准。

---

## 0. 实施约定

| 项 | 约定 |
|---|---|
| 代码位置 | `java-backend/src/main/java/com/aicap/` 下新建包，**不改动** `agent/` `profile/` 现有类的公开签名 |
| 建表方式 | 追加到 `java-backend/src/main/resources/db/schema.sql`（全部 `CREATE TABLE IF NOT EXISTS`，启动自举） |
| 存量库补列 | 若需给**已有表**加列，追加到 `config/SchemaUpgrader.java`，不移除既有条目 |
| 配置 | `@ConfigurationProperties` + `application.yml` 中 `${ENV_VAR:默认值}`，密钥一律留空默认、由环境变量注入 |
| 依赖 | **不引第三方 SDK**（对齐现有 `ModelClient`/`GitHubActivitySyncService` 风格）：HTTP 一律用 JDK `HttpClient`，JSON 一律用 Jackson `ObjectMapper` |
| 测试 | `src/test/java/com/aicap/contract/` 下加黑盒契约测试（`ContractTestSupport` 基类）；**测试前必须有 3307 的 MySQL 与 8080 实例** |
| 验证 | 每 Sprint 结束跑 `java-backend` 全量测试 + `qa/run-all.ps1`，结果回填本文件「验证」栏 |

**红线（继承现有架构，任何模块不得违反）**

1. 模型**绝不直接写库**；建议一律经审批队列（`AgentJobs` / `meeting_suggestion_records`）。
2. 上游非 200 **绝不落响应体**（防凭据/隐私回显）。
3. 检索层必须做 **ACL 过滤**，不得让成员经语义检索绕过 `ResourceController` 的可见性限制。
4. 结构化条件（时间/状态/外键）走 SQL，语义条件才走向量——**不许把 `query_activities` 这类改成向量检索**。

---

## 1. Sprint 总览与进度

| Sprint | 内容 | 可演示产出 | 状态 |
|---|---|---|---|
| **S1** | `knowledge_chunks` 表 + 切分器 + `EmbeddingModel` + `QdrantVectorStore` + 索引任务 | 知识库页能重建索引，chunk 数正确 | ✅ **已完成**（2026-09-19，实测 239 chunk） |
| **S2** | `Retriever` 三路 + RRF + Reranker + ACL + `retrieval_logs` | 检索调试台并排看三路结果 | ⬜ 未开始 |
| **S3** | 替换 `AgentTools` 内部实现 + 新增 4 个工具 + Query Rewrite | 会议 Agent 建议质量提升 | ⬜ 未开始 |
| **S4** | `AgentGraph` + `Checkpointer` + 会议 Agent 重构成图 + interrupt 节点 | kill 进程后重启能续跑 | ⬜ 未开始 |
| **S5** | `OrchestratorAgent` + `RiskAgent` + `ReviewAgent` | 一句话触发多 Agent 协作 | ⬜ 未开始 |
| **S6** | **MCP Server** | Claude Desktop 直连查 AIcap 数据 | ⬜ 未开始 |
| **S7** | Golden Set + 4 类指标 + `baseline.json` | 第一份评测报告 | ⬜ 未开始 |
| **S8** | 流式 + 引用卡片 + 检索调试台前端 | 完整演示 | ⬜ 未开始 |

> 最小集（时间紧时不可砍）：**S1 → S2 → S3 → S6**，覆盖 JD 最硬的三条：RAG + 工具 + MCP。

---

## 2. S1 详细任务（RAG 地基）

**目标**：把项目数据变成可检索的知识块，并落进向量库。**S2 的检索、S3 的工具全部依赖本 Sprint 的产出。**

### 2.1 任务清单

| # | 任务 | 产出文件 | 状态 |
|---|---|---|---|
| 1 | 知识块 / 向量表建表 | `db/schema.sql` 追加 `knowledge_chunks`、`knowledge_vectors` | ✅ |
| 2 | Embedding 抽象 + OpenAI 兼容实现 | `rag/EmbeddingModel.java`、`rag/ApiEmbeddingModel.java`、`rag/RagProperties.java` | ✅ |
| 3 | 向量库抽象 + 双实现 | `rag/VectorStore.java`、`rag/QdrantVectorStore.java`、`rag/MysqlVectorStore.java` | ✅ |
| 4 | 切分器（6 类数据源） | `rag/Chunk.java`、`rag/Chunker.java` | ✅ |
| 5 | 源数据扫描 | `rag/KnowledgeSourceScanner.java` | ✅ |
| 6 | 增量索引任务（content_hash + 模型比对） | `rag/KnowledgeIndexer.java` | ✅ |
| 7 | 管理接口 | `controller/KnowledgeController.java` | ✅ |
| 8 | 配置接入 | `application.yml` 追加 `aicap.rag.*` | ✅ |
| 9 | 契约测试 | `test/.../KnowledgeIndexContractTest.java`（9 例）、`ChunkerTest`（7 例）、`RetrievalContextTest`（4 例） | ✅ |
| 10 | Qdrant docker 服务 | `backend/docker-compose.yml` 追加 `qdrant` | ✅ |

> 路径修正：实体与 Mapper **不在 `rag/` 下**，而在 `entity/KnowledgeChunk.java`、`entity/KnowledgeVector.java`、
> `mapper/KnowledgeChunkMapper.java`、`mapper/KnowledgeVectorMapper.java`。
> 原因：项目用显式 `@MapperScan("com.aicap.mapper")`，Mapper 放别处不会被扫到，
> 表现为启动后 `NoSuchBeanDefinitionException`。
>
> 配置类合并为一个 `rag/RagProperties.java`（含 `Embedding` / `Qdrant` 两个嵌套类），
> 未按初稿拆成 `EmbeddingProperties`——三者同属一个功能的配置，拆开只会让 `application.yml` 的层级对不上代码。
>
> 额外增加的补列：`embedding_model`（见 2.5），走 `config/SchemaUpgrader.java` 兼容老库。

### 2.2 切分策略（**面试考点，勿改**）

| 源 | 表 | 切分单位 | 理由 |
|---|---|---|---|
| story | `stories` | 单行 = 单 chunk | 本身即语义单元，切开丢上下文 |
| pool_item | `pool_items` | 单行 = 单 chunk | 同上 |
| task | `tasks` | 单行 = 单 chunk | 同上 |
| meeting | `meetings.transcript` | **复用 `AnalysisValidator.segmentsFor()`** | 切分结果与 Agent 证据引用 ID（`seg-N`）**天然对齐**——检索命中的 chunk 可直接当证据，白拿的一致性 |
| doc | `docs/*.md` | 按 `##` 标题层级切，超长再按段落 | Markdown 标题是天然语义边界 |
| profile | `member_profiles` | 单行 = 单 chunk | 同上 |

**上下文增强前缀（必做）**：结构化行单独成 chunk 时信息不足，必须拼前缀：

```
【用户故事 US12】标题：会议录音自动转写
优先级：Must ｜ Sprint：2 ｜ 状态：进行中 ｜ 负责人：李锐铭
描述：……
验收标准：……
```

> 理由：向量检索只看内容。不加前缀，「Must」「Sprint 2」这类结构化字段在语义上等于不存在，按「高优先级需求」检索会**漏**。

### 2.3 向量库双实现（**本设计最值钱的面试点**）

| 实现 | 定位 |
|---|---|
| `QdrantVectorStore`（默认） | Docker 起 `qdrant/qdrant`，JDK `HttpClient` 直调 REST，**不引 Java SDK**（少依赖 + 能讲清 REST 契约） |
| `MysqlVectorStore` | 向量存 `knowledge_vectors.vector` BLOB，应用层算余弦；< 1 万 chunk 时延迟 < 20ms |

**为什么两个都做**：JD 明写「会使用至少一种向量数据库」→ 必须有真向量库；但 AIcap 当前只有几百到几千 chunk，暴力检索根本不需要 ANN 索引 → 只做 Qdrant 就答不上「为什么不用 MySQL 直接算余弦」。两个都做 + S2 的 1k/1万/10万 P99 benchmark，才能讲出**「索引结构是数据规模的函数，不是技术栈的装饰」**。

> **两条路径都已真实跑通**（见 2.4）。值得记一笔的是：两者在同一数据集上返回**逐位相同**的
> top-5 与分数——这不是设计出来的，是验证出来的，也因此成了彼此最强的正确性证据。
>
> **环境坑**：`docker compose up -d qdrant` 在境内直连 Docker Hub 拉不动
> （实测 8 分钟无进展）。已配的镜像加速对 `docker pull` 生效但 `compose` 未走通，
> 可用 `docker pull docker.1ms.run/qdrant/qdrant:latest` 后
> `docker tag docker.1ms.run/qdrant/qdrant:latest qdrant/qdrant:latest` 再起。
> 换机器/换网络时这是第一个会绊住 S2 的点。

### 2.4 验证清单

全部于 2026-09-19 实测。

| 验证项 | 方法 | 结果 |
|---|---|---|
| 编译通过 | `JAVA_HOME=jdk-23 mvn -B -q -DskipTests package` | ✅ 产出 `target/aicap-java-backend.jar` |
| 全量后端测试 | 3307 MySQL + fixture 就绪后 `mvn test` | ✅ **169/169 通过**（18 个测试类，0 失败 0 错误；S1 基线 167，新增 2 个回归用例） |
| 索引可重建（MySQL 实现） | `POST /reindex?force=true`（真实开发库） | ✅ scanned 239 / created 239 / embedded 239 / failed 0 / 6526ms |
| 幂等（增量真的跳过） | 紧接再跑 `force=false` | ✅ unchanged 239 / **embedded 0** / 23ms |
| **索引可重建（Qdrant 实现）** | `AICAP_VECTOR_STORE=qdrant` + `aicap-qdrant` 容器 | ✅ scanned 240 / embedded 240 / failed 0 / 3711ms |
| **两种实现结果一致** | 同一查询分别打两个实现 | ✅ top-5 与分数**逐位相同**（见下） |
| **ACL 过滤（Qdrant `range.lte`）** | `aicap.rag.acl.doc=admin` 后按角色检索 | ✅ admin 见 28 条 doc 块；owner/member/viewer **0 条** |
| ACL 过滤（MySQL `FIELD()`） | 契约测试 2 例 | ✅ admin 可见 / member+viewer 不可见 |
| 换 embedding 模型 | 改模型名后 `force=false` 重建 | ✅ stale 239 / embedded 239；再跑 stale 0 / embedded 0（见 2.5.1） |
| ACL 配置变更 | 改 `acl.story=owner` 后 `force=false` 重建 | ✅ `aclSynced 168` / `acl_mismatch` 归零；member 检索不到 story（见 2.5.2） |

**双实现结果一致性（同一查询 `成员批量导入`，同一数据集）**

| 排名 | chunk_id | MySQL 得分 | Qdrant 得分 |
|---|---|---|---|
| 1 | `story:US01:0` | 0.4618 | 0.4618 |
| 2 | `doc:…需求说明….md:5` | 0.4271 | 0.4271 |
| 3 | `story:US27:0` | 0.4054 | 0.4054 |
| 4 | `task:T09:0` | 0.4041 | 0.4041 |
| 5 | `story:US36:0` | 0.4040 | 0.4040 |

> 两个独立实现（应用层暴力余弦 vs Qdrant ANN + REST）给出逐位相同的排序与分数，
> 是彼此最强的正确性证据——任何一边的归一化、距离度量或 payload 映射写错，
> 这里都会立刻分叉。**这条对比本身就是 S2 benchmark 的基线。**

**真实数据规模（开发库 `AIcap`）**

| 源 | story | pool_item | task | meeting | doc | profile | 合计 |
|---|---|---|---|---|---|---|---|
| chunk 数 | 37 | 2 | 16 | 12 | 168 | 5 | **240** |

> doc 数 167 → 168 是本文件（`docs/AIcap-Agent能力扩展_实施计划.md`）在验证过程中被编辑、
> 新增章节所致——增量索引如实识别出了这次变化，算是一次意外的活体验证。

### 2.5 实测暴露的两个缺陷（均已修）

这两个都不是读代码能看出来的，是**真跑数据**撞出来的。共同点：
`content_hash` 只覆盖 `content` 一个字段，任何**不属于 content 但会影响检索正确性的状态**
都逃出了增量判断。

#### 2.5.1 换 embedding 模型后旧向量静默失效

> chunk 内容一字未改 → hash 全等 → **全部判为「未变」跳过** → 库里留着旧模型的向量，
> 查询却用新模型编码。两个向量不在同一空间，相似度是噪声，**且不报任何错**。
> 比索引失败更危险——索引失败会有人发现，静默错结果不会。

修法：`knowledge_chunks` 增加 `embedding_model` 列记住产出向量的模型名；
「模型与当前配置不一致」与「内容变了」一样触发重算。`embedding_model IS NULL` 的老行
（早于本列存在、来历不明）同样按过期处理——**默认拒绝，而不是默认放行**。

#### 2.5.2 ACL 配置收紧后不生效（权限静默失效）

`acl_role` 是**配置**不是内容，同样不被 `content_hash` 覆盖。验证 Qdrant ACL 时发现：
配置里已把 `doc` 源设为 admin 可见，库里 168 个 doc 块却仍是 `member`——
因为只改 ACL 时内容与模型都没变，增量分支根本不进。

修法：把 `acl_role` 纳入增量比对，不一致即重算。ACL 变更**必须重写向量**——
MySQL 实现靠 JOIN 实时读角色，但 Qdrant 把 `acl_rank` 存在 payload 里，不重写就改不掉，
两种实现的行为必须一致。

#### 2.5.3 由此新增的可观测性

- `stats.stale_vectors`：向量由旧模型产出的块数。过期向量**不是** `pending`（它已索引），
  混在一起会被 `pending_chunks=0` 掩盖。
- `stats.acl_mismatch`：角色与当前 `aicap.rag.acl` 配置不一致的块数。
  **改配置不会自动重索引**，没有这个数，"收紧可见性"是否真落地了只能靠翻日志。
- `RagProperties` 启动时打印生效的 ACL 配置（`RAG ACL:{doc=admin}`）。
  加它是因为排查 2.5.2 时绕了远路：Map 型配置绑定失败**不报错**，只静默保持默认全 member。
  一行日志把这件事从"要读源码猜"变成"看一眼就知道"。

#### 2.5.4 一次自伤的回归（记录在案）

修 2.5.1 时我把 `applyContent` 挪进了 `if (contentChanged)` 分支内。
原代码进分支就无条件调用，所以 `force=true` 本来能刷新 `acl_role`，被我改坏了。
是 2.5.2 的排查过程中发现的——**改增量判断这类"顺带优化"必须重跑全量契约测试**，
只跑新增用例会漏掉它。

> **面试讲点**：`content_hash` 增量是文档里到处都有的标准做法，但"哪些状态逃出了 hash"
> 只有真跑一遍才会撞上。两个缺陷同源：**把正确性交给"操作者记得按 force 按钮"，
> 而不是放进数据本身**。修法都是把状态落到列上，让系统自己发现不一致。

---

## 3. 完工后要回填的东西

- [ ] README 测试数字口径统一（前文「128 后端契约 / 44 E2E」 vs 技术栈段「127 JUnit / 37 Playwright」——**面试官会逐字看**）
- [ ] README 技术栈段测试数 167 → **169**
- [ ] 仓库 description
- [ ] `docs/` 下内部过程产物归档（`项目侧交接_*.md` 等）
- [x] ~~S1 实测 chunk 总数 / 索引耗时 / embedding 调用次数 → 填回本文件 2.4~~（见 2.4）
- [ ] S2 benchmark：1k / 1万 / 10万 chunk 下 Qdrant vs MySQL 的 P99 延迟对比
      （基线已有：240 chunk 下两者得分逐位一致，见 2.4）
- [ ] **契约测试仍只覆盖 `vector-store=mysql`**。Qdrant 路径已用真实容器手工验证通过
      （2.4 全部条目），但**没有自动化用例守住** —— S2 改 `Retriever` 时极易碰坏而无人察觉。
      建议 S2 开头把 `KnowledgeIndexContractTest` 参数化跑两遍实现
- [ ] **开发库 `AIcap` 已写入 240 条 `knowledge_chunks`**；MySQL 实现下
      `knowledge_vectors` 另有 240 条，Qdrant 容器内 240 个 point。
      向量来自本地 fixture 桩（`local-fixture`，64 维），**不是真实 embedding 模型**。
      配好真实密钥后跑一次 `reindex`（不必 force，模型名不同会自动全部重算）即可覆盖
- [x] ~~`backend/docker-compose.yml` 补一句境内镜像源拉取说明~~（已加注释）
- [ ] 本次改动**尚未 commit**；`backend/.env` 的真实 `AICAP_LLM_API_KEY` 全程未被读取或写入日志
