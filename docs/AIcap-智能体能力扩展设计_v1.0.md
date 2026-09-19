# AIcap 智能体能力扩展设计 v1.0

> 目标：把 AIcap 从「AI 功能集成」升级为「Agent 系统」，同时补齐 2027 届 AI Agent 校招 JD 的全部硬性缺口。
> 基线：`Wayne-SQS/AIcap` @ `main`（68 commits，2026-09-15）

---

## 0. 现状盘点（先说清手上有什么）

### 已有的（比想象中强，别推倒重来）

| 组件 | 位置 | 实际能力 |
|---|---|---|
| 有界工具循环 | `agent/AgentRunner.java` | MAX_STEPS 上限、工具调用去重、超时检查、`Emitter` 事件回灌、**绝不直接写库** |
| 工具定义与执行 | `agent/AgentTools.java` | 5 个只读工具，OpenAI function-calling schema，**参数严格校验（对齐 pydantic extra=forbid）**，执行前校验调用人 role |
| 切分器 | `agent/AnalysisValidator.java` | `segmentsFor()` 按句切 + 2000 字硬上限，产出 `seg-1..seg-n` |
| 输出校验 | 同上 | 结构 + 字段 + **证据逐字引用** + **片段全覆盖**，失败进 repair 重试 |
| 任务队列 | `agent/AgentJobs.java` | 原子认领 + 租约 + 超时回收 + 幂等（唯一键兜底）+ **全有或全无落库** |
| 模型客户端 | `agent/ModelClient.java` | 仅 HTTPS、finish_reason 校验、**上游非 200 绝不落响应体**（防凭据回显） |
| 工具注册表 | `profile/ProfileToolRegistry.java` | 8 个工具，schema 注入 prompt，**异常转 ok=false 返回给模型而非炸循环** |
| 记忆层 | `profile/ProfileMemoryStore.java` | 长期记忆 = 历史快照 + 人工修正 + 成员纠正，注入 prompt 块 |
| 活动同步 | `profile/GitHubActivitySyncService.java` | GitHub REST + PAT，按 `github_event_id` 幂等 |
| 测试体系 | `qa/` | 180 条用例（128 后端契约 / 44 Playwright E2E / 8 浏览器验收） |

### 缺的（对照 2027 届 JD）

| JD 要求 | 现状 | 缺口等级 |
|---|---|---|
| **RAG 全流程**（解析/切分/Embedding/向量检索/Rerank） | 全部检索是 `LIKE '%kw%'` | 🔴 零 |
| **向量数据库**（Milvus/Qdrant/Chroma） | 无 | 🔴 零 |
| **Agent 工作流编排**（任务规划/状态管理/记忆） | 手写 while 循环，无显式状态、无 checkpoint | 🟡 部分 |
| **MCP 协议** | 无 | 🔴 零 |
| **多智能体协作** | 两个 Agent 完全隔离、不通信 | 🔴 零 |
| **模型评测机制** | 有 E2E，无 agent 质量评测 | 🔴 零 |
| Function / Tool Calling | ✅ 已有，做得比多数人好 | — |

**结论：缺口集中在「检索层」和「编排层」两块，各约 3 周。**

---

## 1. 总体架构（改造后）

```
┌─────────────────────────────────────────────────────────────┐
│  Orchestrator Agent（新增，规划 + 分派）                       │
│  ├── MeetingAgent   会议转写 → 需求建议（已有，接入检索）        │
│  ├── ProfileAgent   成员活动 → 难度评估（已有，接入检索）        │
│  ├── RiskAgent      依赖 + 负载 → 风险扫描（新增）              │
│  └── ReviewAgent    对建议做二次审查（新增，替代纯人工 Review）  │
└──────────────────────┬──────────────────────────────────────┘
                       │ 全部通过 GraphRunner（显式状态机 + checkpoint）
┌──────────────────────┴──────────────────────────────────────┐
│  工具层（ToolRegistry，统一）                                 │
│  旧工具签名不变，内部换成混合检索                               │
│  + search_meetings / search_documents / find_similar_stories  │
└──────────────────────┬──────────────────────────────────────┘
┌──────────────────────┴──────────────────────────────────────┐
│  检索层（新增，核心）                                          │
│  ① 索引任务：切分 → Embedding → 写 Qdrant + MySQL              │
│  ② 查询：向量召回 + 关键词召回 → RRF 融合 → Rerank → ACL 过滤   │
└──────────────────────┬──────────────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        │  MCP Server（新增）           │
        │  把上面全部能力暴露给           │
        │  Claude Desktop / TRAE / Cursor│
        └─────────────────────────────┘
```

---

## 2. 模块 A：检索层（RAG 基础设施）

> 这是整个扩展的地基，也是 JD 里最硬的一条。**先做这个，其他都依赖它。**

### A1. 数据源与切分策略

AIcap 里有 4 类可检索内容，**切法各不相同**——这是面试要能讲清的工程判断：

| 源 | 表/文件 | 切分单位 | 理由 |
|---|---|---|---|
| **结构化短文本** | `stories` / `pool_items` / `tasks` | **单行 = 单 chunk** | 本身就是一个语义单元，切开反而丢上下文 |
| **会议转写** | `meetings.transcript` | **复用 `AnalysisValidator.segmentsFor()`** | 已有切分器，且**切分结果与 Agent 证据引用 ID 对齐**（`seg-7`）——检索命中的 chunk 能直接当证据用，这是免费的强一致性 |
| **项目文档** | `docs/*.md` | **按 `##` 标题层级切**，超长再按段落 | Markdown 标题就是天然语义边界 |
| **成员画像** | `member_profiles` + `profile_snapshots` | 单行 = 单 chunk | 同上 |

**关键设计点（面试会被问）——上下文增强前缀：**

结构化行单独成 chunk 时信息不足。切分时必须拼一个前缀：

```
【用户故事 US12】标题：会议录音自动转写
优先级：Must ｜ Sprint：2 ｜ 状态：进行中 ｜ 负责人：李锐铭
描述：……
验收标准：……
```

理由：向量检索只看内容，不加前缀的话「Must」「Sprint 2」这类结构化字段在语义上等于不存在，按"高优先级需求"检索会漏。

### A2. 数据表（新增 2 张）

```sql
-- 知识块主表
CREATE TABLE IF NOT EXISTS `knowledge_chunks` (
  `id`            VARCHAR(64)  NOT NULL COMMENT 'chunk 唯一 id',
  `source_type`   VARCHAR(32)  NOT NULL COMMENT 'story/pool_item/meeting/doc/task/profile',
  `source_id`     VARCHAR(64)  NOT NULL COMMENT '源记录主键',
  `chunk_index`   INT          NOT NULL DEFAULT 0 COMMENT '同源内序号',
  `content`       TEXT         NOT NULL COMMENT '增强前缀 + 原文',
  `raw_content`   TEXT         NOT NULL COMMENT '原文（不含前缀）',
  `content_hash`  CHAR(64)     NOT NULL COMMENT 'SHA-256，增量索引比对用',
  `acl_role`      VARCHAR(16)  NOT NULL DEFAULT 'member' COMMENT '最低可见角色',
  `metadata_json` JSON         NULL COMMENT 'title/priority/sprint/seg_id 等',
  `embedded_at`   DATETIME     NULL COMMENT 'NULL = 待索引',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_chunk` (`source_type`, `source_id`, `chunk_index`),
  KEY `idx_embedded` (`embedded_at`),
  KEY `idx_source` (`source_type`, `source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 检索日志（评测 + 可观测 + 前端调试台的数据源）
CREATE TABLE IF NOT EXISTS `retrieval_logs` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `run_id`        VARCHAR(36)  NULL COMMENT '所属 agent run',
  `query`         VARCHAR(500) NOT NULL,
  `mode`          VARCHAR(32)  NOT NULL COMMENT 'vector/keyword/hybrid',
  `top_k`         INT          NOT NULL,
  `hits_json`     JSON         NOT NULL COMMENT '命中 chunk_id + 各路分数 + 最终排序',
  `latency_ms`    INT          NOT NULL,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> `retrieval_logs` 是**评测体系（模块 E）和前端检索调试台（模块 F）的共用数据源**。没有它，评测只能靠人肉。

### A3. Embedding 模型（接口 + 双实现）

```java
package com.aicap.rag;

public interface EmbeddingModel {
    /** 维度，用于校验向量库 collection 配置 */
    int dimension();
    String name();
    /** 批量向量化；失败抛 EmbeddingException，由索引任务决定重试 */
    List<float[]> embed(List<String> texts);
    default float[] embedOne(String text) { return embed(List.of(text)).get(0); }
}
```

| 实现 | 说明 | 用在哪 |
|---|---|---|
| `ApiEmbeddingModel`（默认） | 走 OpenAI 兼容 `/embeddings`。配置 `AICAP_EMBEDDING_BASE_URL` / `AICAP_EMBEDDING_MODEL` / `AICAP_EMBEDDING_API_KEY` | 开箱能跑，适合演示 |
| `OnnxEmbeddingModel` | `ai.djl` + HuggingFace `bge-m3`（1024 维，中英混，MIT） | **离线可用**，答辩/断网演示的保险 |

**为什么两个都做**：面试问"为什么不用现成的"→ 答"我两条路都留了，API 保开发效率，本地 ONNX 保可用性和成本可控"。这是**真实的工程取舍**，不是凑数。

### A4. 向量库（接口 + 双实现）

```java
package com.aicap.rag;

public interface VectorStore {
    void ensureCollection(int dimension);
    void upsert(String id, float[] vector, Map<String, Object> payload);
    void deleteBySource(String sourceType, String sourceId);
    List<ScoredId> search(float[] queryVector, int topK, Map<String, Object> filter);
    record ScoredId(String id, double score, Map<String, Object> payload) {}
}
```

| 实现 | 说明 |
|---|---|
| **`QdrantVectorStore`（默认）** | Docker Compose 加一个 `qdrant/qdrant` 服务（6333 端口，单容器零配置），用 JDK `HttpClient` 直接调 REST，**不引 Java SDK**（少一个依赖，且能讲清 REST 契约） |
| `MysqlVectorStore` | 向量存 `BLOB`，应用层算余弦相似度。**数据量 < 1 万 chunk 时延迟 < 20ms**，完全够用 |

**为什么两个都做（这是本设计最值钱的面试点）**：

- JD 明写「会使用至少一种向量数据库（Milvus / Qdrant / Chroma）」→ **必须有真向量库**，`QdrantVectorStore` 交付这条
- 但 AIcap 当前数据量只有几百到几千 chunk，暴力检索根本不需要 ANN 索引 → 如果只做 Qdrant，面试官问"为什么不用 MySQL 直接算余弦"就答不上
- **两个都做 + 一份 benchmark 数据**（1k / 1万 / 10万 chunk 下的 P99 延迟对比）→ 你能主动讲出「索引结构是数据规模的函数，不是技术栈的装饰」。**这一句话的分量超过多背十道八股。**

`docker-compose.yml` 追加：
```yaml
  qdrant:
    image: qdrant/qdrant:latest
    ports: ["6333:6333"]
    volumes: ["./data/qdrant:/qdrant/storage"]
```

### A5. 混合检索 + RRF 融合（**别跳过这步**）

纯向量检索在 AIcap 上会**明显退化**，原因是项目文本里全是专有名词：`US01`、`R07`、`成员3`、`T12`、`SetLamp`。这些词向量模型几乎无法区分。

```java
package com.aicap.rag;

public interface Retriever {
    List<Hit> retrieve(String query, int topK, RetrievalContext ctx);
    String name();
}

// 三路并行召回 → RRF 融合
```

| 路 | 实现 | 召回 |
|---|---|---|
| 向量路 | `VectorRetriever` → Qdrant top-20 | 语义相近 |
| 关键词路 | `KeywordRetriever` → MySQL **ngram FULLTEXT** top-20 | 专有名词精确命中 |
| 图路 | `GraphRetriever` → 沿 `tasks.story_ref` / `story_logs` 展开一跳邻居 | 结构化关系 |

**融合用 RRF（Reciprocal Rank Fusion）**，`score = Σ 1/(60 + rank_i)`：

```java
public final class RrfFuser {
    private static final int K = 60;
    public List<Hit> fuse(List<List<Hit>> rankedLists, int topK) {
        Map<String, Double> score = new HashMap<>();
        Map<String, Hit> byId = new HashMap<>();
        for (List<Hit> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                Hit h = list.get(rank);
                score.merge(h.id(), 1.0 / (K + rank + 1), Double::sum);
                byId.putIfAbsent(h.id(), h);
            }
        }
        return score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()).withRrfScore(e.getValue()))
                .toList();
    }
}
```

**为什么是 RRF 而不是加权求和**：两路分数的量纲完全不同（余弦相似度 ∈ [-1,1]，BM25 无上界），归一化后再加权是伪科学；RRF 只用**排名**，天然免疫量纲问题。**这是面试高频追问点。**

MySQL 建表：
```sql
ALTER TABLE `knowledge_chunks`
  ADD FULLTEXT INDEX `ft_content` (`content`) WITH PARSER ngram;
```

### A6. Rerank（粗排→精排）

```
召回 60 条（三路各 20）→ RRF 融合取 20 → Rerank 取 5 → 进 prompt
```

两个实现，**先做 LLM rerank**：

| 实现 | 做法 | 取舍 |
|---|---|---|
| `LlmReranker`（先做） | 把 20 条候选交给 DeepSeek，要求 listwise 输出相关性排序 + 理由 | 零新依赖；**且 rerank 理由能直接进 `retrieval_logs` 供人看**，调试体验好 |
| `CrossEncoderReranker`（后做） | `bge-reranker-v2-m3`，走 API 或 ONNX | 更准更快更便宜，但多一个模型 |

**面试点**：能讲清「Bi-Encoder 召回快但粗，Cross-Encoder 精排准但慢，所以必须两阶段」——这是 RAG 的标准考点，而你**真的做了两阶段**。

### A7. 权限过滤（**别漏，现有系统有 role 体系**）

现有 `AgentTools` 在工具执行前校验 `WRITER_ROLES`。检索层必须做同样的事，否则成员能通过语义检索**绕过** `ResourceController` 那类"非管理员只能看已发布内容"的限制。

```java
public record RetrievalContext(int userId, String role, Set<String> allowedSourceTypes) {
    /** admin/owner 可见全部；member 只能看 acl_role='member'；viewer 只读已发布 */
    public boolean canSee(String aclRole) { ... }
}
```

过滤**在向量库侧做**（Qdrant payload filter），不要捞回来再过滤——否则 top-k 会被无效结果占满。

### A8. 增量索引

```java
@Component
public class KnowledgeIndexer {
    /** 全量：扫描所有源表 → 算 content_hash → 比对 → 只对变化的做 embedding */
    public IndexReport rebuild(boolean force);

    /** 增量：源记录写操作后调用（挂到 StoryService / TaskService / MeetingService） */
    public void reindexOne(String sourceType, String sourceId);
}
```

- 有 `content_hash` 比对，**重复重建的成本极低**（只有变更行会调 embedding）
- 源记录删除时 → `deleteBySource()` 同步删向量
- 暴露 `POST /admin/knowledge/reindex`，前端知识库页有按钮

---

## 3. 模块 B：工具层升级（最小侵入）

**核心原则：工具签名不变，只换内部实现。** 这样 `AgentRunner` / `AgentJobs` / `ProfileAgentRuntime` 一行都不用改。

### B1. 替换现有工具的内部实现

| 工具 | 改前 | 改后 |
|---|---|---|
| `AgentTools.search_stories` | `like("title", keyword)` | `Retriever.retrieve()` → 混合检索 → RRF → Rerank |
| `AgentTools.search_pool` | 同上 | 同上 |
| `ProfileToolRegistry.query_activities` | SQL 过滤 | 保留 SQL（**时间范围过滤是结构化查询，不该走语义**） |

> **注意**：`query_activities`、`query_tasks` 这类**带明确结构化条件**的工具**不该改成向量检索**。什么时候用 SQL、什么时候用向量——这是要能讲清的边界。面试标准答案：**「过滤条件是结构化的（时间/状态/外键）用 SQL，是语义的（"类似的需求""相关的讨论"）用向量。」**

### B2. 新增工具

| 工具 | 签名 | 用途 |
|---|---|---|
| `search_meetings` | `{keyword, limit?}` | 检索历史会议转写。**返回 `segment_id`，可直接当证据引用** |
| `search_documents` | `{keyword, limit?}` | 检索 `docs/*.md` 项目文档 |
| `find_similar_stories` | `{storyId, limit?}` | 给一个故事找语义相近的。**直接支撑现有 prompt 里那句「形成新需求前必须检查已有需求」** |
| `search_all` | `{query, limit?}` | 跨源检索，返回带 `source_type` 标签的混合结果 |

### B3. 工具返回结构统一加 `retrieval` 块

```json
{
  "items": [...],
  "truncated": false,
  "retrieval": {
    "mode": "hybrid",
    "rewritten_query": "会议录音自动转写功能",
    "recall": {"vector": 20, "keyword": 20, "graph": 3},
    "fused": 20,
    "reranked": 5,
    "latency_ms": 187,
    "log_id": 10231
  }
}
```

**为什么要给模型看这些**：让模型知道「这次只召回了 5 条、可能不完整」，它才会在 `unresolved_questions` 里诚实标注。**这和你现在 prompt 里那句「不能计算过载或编造进度」是同一套哲学——把系统的能力边界暴露给模型，而不是让它猜。**

### B4. Query Rewrite（检索前必做）

会议转写里的用户提问是口语、带指代。**检索前先用 LLM 改写**：

```java
@Component
public class QueryRewriter {
    /** "那个录音的功能" + 会话上下文 → "会议录音自动转写" */
    public List<String> rewrite(String rawQuery, List<String> history);
}
```

多查询扩展：生成 3 个变体，各自召回后一起进 RRF。**代价是 3 倍检索、1 次额外 LLM 调用，收益是口语化查询召回率显著提升。** 这是能讲的取舍。

---

## 4. 模块 C：编排层（Graph Runner）

### C1. 为什么现在的手写循环不够

`AgentRunner.analyze()` 是 `for (step < MAX_STEPS)` 循环。能跑，但缺三样：

1. **无显式状态** —— 全靠局部变量，无法序列化 → 断了没法恢复
2. **无 checkpoint** —— 一次 LLM 超时，整轮白跑
3. **分支是隐式的** —— `if 校验失败 → repair` 藏在 try/catch 里，改不动

### C2. 设计（对标 LangGraph 概念，自己实现）

> **Java 生态没有 LangGraph。** 这是个机会不是障碍——自己实现等价物，面试时能说「我理解 LangGraph 的 State / Node / Edge / Checkpoint / Interrupt 五件套，并在 Java 里实现了」。

```java
package com.aicap.graph;

/** ① 显式状态：全程可序列化 */
public interface AgentState {
    String runId();
    Map<String, Object> data();      // 节点读写
    Map<String, Object> toJson();
}

/** ② 节点 */
public interface Node {
    String name();
    void run(AgentState state, NodeContext ctx) throws AgentError;
}

/** ③ 条件边 */
public record Edge(String from, Predicate<AgentState> when, String to) {}

/** ④ 图定义 */
public class AgentGraph {
    public AgentGraph node(Node n);
    public AgentGraph edge(String from, String to);
    public AgentGraph branch(String from, Predicate<AgentState> when, String t, String f);
    public AgentState run(AgentState initial, Checkpointer cp) throws AgentError;
}

/** ⑤ 检查点：每节点执行后落库，可恢复 */
public interface Checkpointer {
    void save(AgentState state, String nodeName);
    Optional<AgentState> load(String runId);
}
```

新增表：
```sql
CREATE TABLE IF NOT EXISTS `agent_checkpoints` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT,
  `run_id`       VARCHAR(36) NOT NULL,
  `node_name`    VARCHAR(64) NOT NULL,
  `state_json`   JSON        NOT NULL,
  `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_node` (`run_id`, `node_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### C3. 会议 Agent 重构成图

```
retrieve ─→ plan ─→ tool_loop ─┬─(有工具调用)─→ tool_exec ─┐
                               │                            │
                               └─(无工具调用)───────────────┘
                                        ↓
                                   synthesize
                                        ↓
                                   validate ─┬─(通过)─→ commit ─→ END
                                              └─(失败)─→ repair ──→ validate
```

**`commit` 节点就是现有的 `AgentJobs.complete()`** —— 全有或全无落库的逻辑原样保留。重构只改控制流，不改业务。

### C4. Interrupt / Resume（**你已经有了一半**）

现有的 **Suggestion 人工审核**（`ReviewView.vue` + `meeting_suggestion_records`）本质就是 LangGraph 的 `interrupt`：

```
... → propose → [INTERRUPT: 人工审核] → approved → apply
                                     → rejected → END
```

**把它显式建模成图的 interrupt 节点**，你就拥有了一条完整的 human-in-the-loop 链路。这是 JD 里「任务规划、权限、异常处理」的直接体现，而且**不是造出来的，是本来就有的**。

### C5. 并行分支

多路召回、多源检索用 `CompletableFuture.allOf()` 并行。现有循环是纯串行的，改成图之后天然支持 fan-out/fan-in。

---

## 5. 模块 D：多智能体 + MCP Server

### D1. Orchestrator Agent（规划者）

```java
@Component
public class OrchestratorAgent {
    /** 输入：一个自然语言意图。输出：执行计划 + 分派 */
    public Plan plan(String intent, AgentState state);
    public Result execute(Plan plan);
}
```

可调度的 subgraph：

| Agent | 状态 | 职责 |
|---|---|---|
| `MeetingAgent` | ✅ 已有 | 会议转写 → 需求/行动项/协调事项 |
| `ProfileAgent` | ✅ 已有 | 成员活动 → 能力画像 → 难度评估 |
| `RiskAgent` | 🆕 新增 | 扫 `tasks.story_ref` 依赖链 + 成员工时 → 识别阻塞/超载风险 |
| `ReviewAgent` | 🆕 新增 | 对 `suggestions` 做**预审**，给出 approve/reject 建议 + 理由，降低人工审核负担 |

**RiskAgent 是四两拨千斤的**：现有 `ProjectToolRegistry` 里那句注释——「工时是计划值，缺少任务状态和真实容量，不能计算完成度或过载」——**已经定义了它的边界**。RiskAgent 就做这件事：在**承认数据不足**的前提下，输出「可确认的风险」和「需要人工确认的疑点」两类，而不是硬算过载。**这和你现有 prompt 的诚实哲学完全一致。**

### D2. MCP Server（**最亮的一步**）

JD 明写 MCP。而且这件事的**说服力远超其他任何一项**——因为它能当场演示。

```xml
<dependency>
  <groupId>io.modelcontextprotocol.sdk</groupId>
  <artifactId>mcp</artifactId>
</dependency>
```

`mcp-server/` 独立模块，暴露：

**Tools**
| 名称 | 说明 |
|---|---|
| `query_stories` | 按关键词/优先级/Sprint 查用户故事 |
| `query_tasks` | 查任务与工时 |
| `query_members` | 查成员与容量 |
| `semantic_search` | **走 RAG 检索层**，跨故事/需求池/会议/文档 |
| `create_pool_suggestion` | 提一条需求池建议（进人工审核队列，不直接落库） |

**Resources**
| URI | 内容 |
|---|---|
| `aicap://project/overview` | 项目统计 + 数据能力限制声明 |
| `aicap://story/{id}` | 单个用户故事全文 |
| `aicap://meeting/{id}/analysis` | 某次会议的 Agent 分析结果 |
| `aicap://member/{id}/profile` | 成员画像与难度历史 |

**效果演示（这是面试现场能演的）**：在 Claude Desktop / TRAE 里配置：
```json
{ "mcpServers": { "aicap": { "command": "java", "args": ["-jar", "aicap-mcp-server.jar"] } } }
```
然后直接问：「AIcap 里有哪些 Must 需求还没分配负责人？」——**Agent 通过 MCP 调你的代码查真实数据并回答。**

> **注意**：`create_pool_suggestion` 必须走审批队列，**不能直接写库**。理由是现成的——你现有架构里「模型绝不直接写库，建议落库由 AgentJobs 完成」这条铁律，MCP 同样适用。**把这条讲出来，比演示本身更值钱。**

---

## 6. 模块 E：评测体系

> JD 原文：「搭建模型评测机制并持续迭代」。你现在有 180 条 E2E，但**没有一条在评测 Agent 质量**。

### E1. Golden Set

从三处构建，**不靠人工造**：

| 来源 | 数量 | 构造方式 |
|---|---|---|
| `qa/` 现有用例 | ~40 条 | 从中筛出需要检索的场景 |
| 真实会议转写 | ~30 条 | 每条标注「应被召回的 chunk」 |
| 合成负例 | ~30 条 | 项目里**不存在**的需求（测幻觉） |

### E2. 指标

| 类别 | 指标 | 为什么 |
|---|---|---|
| **检索质量** | Recall@5、MRR、nDCG@10 | RAG 的根基，检索错了后面全错 |
| **生成质量** | **Groundedness**（每个 claim 能否对应到 evidence）、引用准确率 | 你现有 `AnalysisValidator` 已经强制 evidence 引用——**直接统计它** |
| **幻觉率** | 负例集上编造需求/进度的比例 | 用负例集测 |
| **工具选择** | 该调工具时调了没、调对没 | 统计 `AgentRun.steps` 里的 tool_names |
| **成本** | tokens / 单次分析 | 已有 `usage` 埋点，直接聚合 |

### E3. 落地

```
qa/agent-eval/
├── golden_set.jsonl          # 90 条
├── retrieval_eval.py         # Recall/MRR/nDCG
├── groundedness_eval.py      # 引用准确率（复用 AnalysisValidator 的 evidence 结构）
├── run_eval.ps1              # 纳入 run-all.ps1
└── baseline.json             # 每次改动后的指标快照，看回归
```

**关键**：`baseline.json` 让每次改动都有**可对比的回归基线**。面试时能说：「我加了 rerank 之后 Recall@5 从 0.62 涨到 0.81，但 P99 延迟从 180ms 涨到 420ms，所以我加了缓存把延迟压回 240ms。」——**这是一句话证明你在做工程，不是在调 API。**

---

## 7. 模块 F：前端

| 页面/组件 | 改动 |
|---|---|
| `ChatBox.vue` | 流式输出（SSE）+ **引用来源卡片**（点击跳到源记录） |
| `AgentRunPanel.vue` | 加「检索链路」时间轴：改写后的 query → 三路召回数 → RRF → rerank → 最终 5 条 |
| **`KnowledgeView.vue`（新增）** | 知识库管理：源列表、chunk 数、待索引数、重建按钮、切分预览 |
| **`RetrievalDebugView.vue`（新增）** | 检索调试台：输入 query，实时看三路结果并排对比 + 分数。**这是你调参时的主力工具，也是答辩时的演示利器** |
| `ReviewView.vue` | 接 `ReviewAgent` 的预审建议，人工只做确认 |

---

## 8. 落地顺序

| Sprint | 内容 | 产出（可演示） | 对应 JD 条目 |
|---|---|---|---|
| **S1**（2 周） | `knowledge_chunks` 表 + 切分器 + `EmbeddingModel` + `QdrantVectorStore` + 索引任务 | 知识库页能重建索引，chunk 数正确 | RAG 切分、向量库 |
| **S2**（2 周） | `Retriever` 三路 + RRF + Reranker + ACL + `retrieval_logs` | 检索调试台并排看三路结果 | RAG 全流程、混合检索 |
| **S3**（1 周） | 替换 `AgentTools` 内部实现 + 新增 4 个工具 + Query Rewrite | 会议 Agent 建议质量明显提升 | 工具调用 |
| **S4**（2 周） | `AgentGraph` + `Checkpointer` + 会议 Agent 重构成图 + interrupt 节点 | 中途 kill 进程，重启能续跑 | 工作流编排、状态管理 |
| **S5**（2 周） | `OrchestratorAgent` + `RiskAgent` + `ReviewAgent` | 一句话触发多 Agent 协作 | 多智能体 |
| **S6**（1.5 周） | **MCP Server** | Claude Desktop 直连查 AIcap 数据 | **MCP** |
| **S7**（2 周） | Golden Set + 4 类指标 + `baseline.json` | 跑出第一份评测报告 | **模型评测机制** |
| **S8**（1 周） | 流式 + 引用卡片 + 检索调试台前端 | 完整演示 | — |

**共约 13.5 周。若时间紧张，S1→S2→S3→S6 是不可砍的最小集**（RAG + 工具 + MCP），约 7 周，已覆盖 JD 最硬的三条。S4/S5/S7 是拉开差距的部分。

---

## 9. 面试可讲点清单（对应你最缺的那一环）

做完之后，这些是你**能主动讲、且别人讲不出来**的：

1. **「为什么两套向量库」** —— 索引结构是数据规模的函数。附 1k/1万/10万 chunk 的 P99 延迟 benchmark
2. **「为什么 RRF 而不是加权求和」** —— 两路分数量纲不可比，归一化是伪科学；RRF 只用排名
3. **「为什么混合检索」** —— 项目里 `US01`/`R07`/`成员3` 这类专有名词，纯向量必然漏
4. **「为什么两阶段检索」** —— Bi-Encoder 快而粗、Cross-Encoder 准而慢
5. **「什么时候不用向量」** —— 结构化过滤（时间/状态/外键）走 SQL，语义查询才走向量
6. **「为什么模型不该直接写库」** —— 现有架构的铁律，MCP 也遵守；建议一律进审核队列
7. **「怎么知道改好了」** —— `baseline.json` + Recall@5 涨了但延迟也涨了，以及怎么压回去
8. **「检索结果为什么给模型看」** —— 让模型知道召回可能不完整，才会诚实标注 `unresolved_questions`
9. **「切分器为什么要复用 `segmentsFor`」** —— 检索命中的 chunk id 与证据引用 id 天然对齐，白拿的一致性
10. **「Interrupt 在哪」** —— Suggestion 人工审核就是 human-in-the-loop，不变量，只是显式建模

---

## 10. 顺手要修的（投递前）

- README 测试数字自相矛盾：前文「128 后端契约 / 44 E2E」，技术栈段「127 JUnit / 37 Playwright」——**面试官会逐字看**
- 仓库无 description —— 补上
- `docs/` 下三份交接文档（`项目侧交接_会议Agent接入_v0.1.md` 等）是内部过程产物，投递前考虑归档或整理
