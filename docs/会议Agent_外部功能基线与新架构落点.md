# 会议智能体 · 外部功能基线与新架构落点

> **这份文档要解决什么**：把「老会议智能体」对外可见的功能**逐条固定为契约**，并写明每一条在「新架构」里的落点与**验收方式**。
>
> **这不是迁移映射表。** 两家的执行模型不同，不存在一一对应的搬运关系：
> - 老会议智能体 = `java-backend/.../agent/`，**模型自主工具循环**（`AgentRunner` 下发 toolDefs → 模型选工具 → 执行 → 回喂），通用会议（不分类型）
> - 新架构 = `ai-service/`，**LangGraph 固定工作流节点**（README 原文：「没有模型自主工具循环，故事 Tool 由工作流固定节点调用」），按类型五类
>
> 本表的用途是：**新架构必须满足这些外部契约**。哪一条没被满足，换过去就是能力缺失。叫「迁移」会诱导后人去找一一对应，而下面多处恰恰说明不存在简单对应。
>
> 依据（都是读过的源码，不是印象）：`frontend/src/components/ai/AgentRunPanel.vue`（外部可见面）、`agent/AgentRunner.java`（prompt 与 emit 点）、`agent/AnalysisValidator.java`（阻断式校验）、`agent/AgentJobs.java`（状态机、去重、送审）、`agent/MeetingAgentService.java`。

---

## 0. 一句话结论

- **老家提供**：一次**通用会议**分析 → 八类带证据锚点的结构化产出 + 一套**可见的运行状态机与事件流** + 送审去重。
- **新家提供**：**五类会议**的类型化提案分析 + 提案级证据校验 + **快照比对**。
- **两者不是包含关系**。新家有老家没有的（`snapshot_mismatch`）；老家有新家没有的（开放类产出、编造负责人拦截、片段全覆盖、通用入口、运行状态机）。

---

## 1. 分析产出（模型产出 / 代码产出已分开标注）

`AgentRunPanel.vue:83-95` 是这一节的取证面。除 `summary` 外**每一项都必须带 `evidence{segment_id, quote}`**，面板逐条渲染「依据 {seg}：{quote}」。

| # | 外部功能 | 老家依据 | 新架构落点 | **验收方式** |
|---|---|---|---|---|
| F1 | **通用会议分析入口**（不分类型，给转写就出结果） | `AgentJobs.queueRun` → `AgentRunner`，整条无类型参数 | **需新建**：一条通用 LangGraph 工作流。新家现有五类入口（`/analyze`、`/planning/analyze`、…）都是**类型化**的，没有通用入口。⚠️ **公共骨架已抽出**（`graph_runtime.run_linear_graph`，五张图共用；全仓仅此一处 `StateGraph(`）——F1 是**往骨架里加节点**（节点表 + 边表），不是复制第六份 | 契约测试：给一段转写 + 会议 id，返回含 F2–F9 全部键 |
| F2 | `summary`（模型产出） | `AnalysisValidator:70`，1–2000 字 | 工作流输出模型 | 结构测试 + 非空 |
| F3 | `decisions` 决议（模型产出） | `:71`，≤20，每项 `text`+`evidence` | 同上 | 逐项 `evidence` 非空 |
| F4 | `action_items` 行动项（模型产出） | `:72`，`description`+`owner_mention`+`deadline_text`+`evidence` | 同上 | 逐项证据 + F12 |
| F5 | `coordination_items` 协调事项（模型产出） | `:73`，optional，同上结构 | 同上 | 同上 |
| F6 | `status_constraints` 状态与验收约束（模型产出） | `:74`，optional，`text`+`evidence` | 同上 | 同上 |
| F7 | `source_notes` 其他会议事实（模型产出） | `:75`，optional，≤100 | 同上 | 同上 |
| F8 | `risks` 风险/待确认推断（模型产出） | `:76`，≤20 | 同上 | 同上 |
| F9 | `unresolved_questions` + `limitations`（**前者模型产出，后者代码常量**） | `:78-89`；`limitations` 由 `AgentJobs` 写成固定串「未配置真实成员容量/任务状态/当前Sprint日期；不自动修改任务和故事」 | 前者入模型；后者照抄为代码常量 | 前者是数组；后者文本相等 |

> **F3–F8 是新家完全没有的**。新家的输出模型只有 `proposed_actions`（状态提案），没有开放类产出。这是本次换算量最大的一块，也是 F11/F13 校验必须跟着扩的原因。

---

## 2. 校验：阻断式，四道关

老家的立场写在类注释里：「模型产出一律视为**不可信输入**：结构/字段/证据逐字引用/片段全覆盖校验通过前**不落任何建议**」。四道关全部是 `throw`，不是告警。

| # | 外部功能 | 老家依据 | 新架构落点 | **验收方式** |
|---|---|---|---|---|
| F10 | **字段集精确匹配**——evidence 字段集必须**恰好等于** `{segment_id, quote}`，多一个少一个都拒 | `AnalysisValidator:143` `if (!seen.equals(Set.of("segment_id","quote"))) throw` | **已有**：`contracts.py:19` `ConfigDict(extra="forbid", strict=True, frozen=True)` | 契约测试：多带一个字段必须失败 |
| F11 | **证据逐字核对**——`quote` 必须在被引用的转写片段里**逐字出现** | `:104` → `invalid_evidence` | **五类都有，且已并成一处**：`contracts.py:require_quoted_evidence`（原先是 daily / planning / retro / refinement 四份逐字复制的实现，review 经 daily 的 `validate_status_provenance` 复用；本轮合并）。**均在运行时 validate 节点内** | **必须新增**：喂**伪造 quote**（原文查无此句）**必须失败**。且用例要覆盖 F3–F8 每一类——只测提案类等于只测了五分之一 |
| F12 | **不得编造负责人/日期**——`owner_mention` / `deadline_text` 必须逐字出现在该条的 quote 里 | `:108-119` → `invented_assignment`「负责人或日期缺少原文依据」 | **retro 已有，且比老家更严**（`retro_contracts.py:95-99`）：除「名字必须在 quote 里」外，还要求 `sum(m.display_name == owner.display_name for m in context.members) == 1`——**同名成员数必须恰好为 1**，防歧义。日期侧 `deadline_not_quoted`。**其余四类不是「没有」，是「没有对象」**——逐类核过：planning / review / refinement **根本没有负责人字段**；daily 的 `StorySnapshot.owner_id`（`contracts.py:34`）是**输入侧**快照字段，`validate_status_provenance` **从不校验它**，产出侧 `StatusChange` / `StoryStatusProposal` 也没有负责人；老家拦的 `action_items.owner_mention` / `coordination_items` 属 **F3–F8 开放类产出**，那套产出模型在新家**还不存在**。所以 F12 不是「推广到现有四类」，而是 **F3–F8 产出模型落地时必须自带的一条**（retro 是现成的参照实现） | **随 F3–F8 落地**：F4 行动项 / F5 协调事项的产出模型必须带这条校验。喂一条 owner 不在 quote 里的行动项，**必须失败**；另补一条**同名成员**用例（两个同名成员时必须拒，这条老家没有，不能丢） |
| F13 | **片段全覆盖**——每个转写片段都必须被某条证据引用到，否则拒 | `:120-131` → `incomplete_analysis`「会议片段未被分析：…」 | ❌ **新家没有**（核过：`validate_status_provenance` 只核提案自己引用的片段，不作全覆盖；同为 F3–F8 产出模型落地时自带） | **必须新增**：给 3 段转写、只分析 2 段，**必须失败** |

**落地要求**：F11–F13 应当是 LangGraph 里**一个独立的 validate 节点**——所以这里**不要**用纯固定节点的思路。校验节点用代码逐字比对（不调模型），失败即终止本次 run、不落库、不送审。
**「码出不了边界」已修**（`cbfc2d8`）：五类的 validate 节点此前一律 `except ValueError: raise WorkflowError('invalid_model_proposal')`，而 `ResultContractError` 是 `ValueError` 子类 → 细码在 API 边界被吞、还被报成 502。现在细码透传、契约码 → 422；五处 validate 节点体也已并成 `graph_runtime.validate_candidate`。详见 §8.3。**不要**期望 Pydantic 结构校验能兜住 F11–F13：结构合法但 quote 伪造的输入，Pydantic 一定放行。

---

## 3. 送审与去重（代码产出，搬迁成本低）

| # | 外部功能 | 老家依据 | 新架构落点 | **验收方式** |
|---|---|---|---|---|
| F14 | **送审**：`proposals` 经审批队列落库，**不直接写业务表** | `AgentJobs:285` `meetingService.stage(in, user).suggestion()`；`AnalysisValidator:189` 强制 `action == "pool.create"` | Java 侧审批队列（平台能力，两家共用，**不动**） | 契约测试：提案落 `suggestions` 而非业务表 |
| F15 | **去重**：同名故事/需求/待审建议 → 不重复创建，回 `skipped_proposals{title, reason}`，面板显示「未重复创建：xxx」 | `AgentJobs:265-271`，`existingTitles.contains(title.toLowerCase())` | **代码层**，可照搬 | 契约测试：连续两次同一标题，第二次进 `skipped_proposals` |
| F16 | `suggestion_ids`（代码产出） | `AgentJobs:287` | 同 F14 | 结构测试 |

> **F15 有个容易被误判的点**：去重是**代码层**做的（`existingTitles.contains(title.toLowerCase())`），**不依赖模型的自主性**。老家的 prompt 另有一层要求「形成新需求前必须调用 `search_stories` 和 `search_pool` 检查已有需求」，那一层影响的是**模型提不提**、以及分类是否准确（「已有需求修改」该放行动项还是协调事项）。换执行模型会动的是**后者**，见 F21。
>
> **但 F15 只兜住了「重复创建」，兜不住「信息丢失」**，这一点必须写准：
> 去重只在 `proposals` 循环里做（`AgentJobs:262-275`），**不作用于 `action_items` / `coordination_items`**。
> 于是模型若把「修改已有需求 X」误分类成「新建需求 X」——它落在 `proposals` 里 → 被 `existingTitles` 拦下 → 进 `skipped_proposals`；
> 而它**本来应该出现在 F4 行动项或 F5 协调事项里**。结果这条诉求既没进需求池，也没进行动项/协调项，**彻底消失**。
> 更要紧的是**这次是真的静默**：面板显示「未重复创建：xxx」，看起来像系统正常工作并成功识别了重复，
> 用户不会知道有一条诉求被吞了。所以 F21 的验收不能只看「分类准确率」，还要看**被 skip 的提案里有多少本该是行动项/协调项**。

---

## 4. 运行可见性（面板上点得开，属于外部功能）

| # | 外部功能 | 老家依据 | 新架构落点 | **验收方式** |
|---|---|---|---|---|
| F17 | **运行状态机**：`queued / running / awaiting_review / completed / failed` + `attempt` 尝试次数 + `error_code` / `error_message` | `AgentJobs:93/120/203/314`；`MeetingAgentRun` 实体 | **需新建**。新家是**同步 HTTP**，无 run 状态、无 attempt。落点是 §2.7 的会话/事件表。⚠️ **不要走 LangGraph checkpointer**，见下 | 契约测试：5 个状态都能到达；失败态带可读 `error_message` |
| F18 | **失败重试**（面板一个按钮，`start(run.id)`） | `AgentRunPanel.vue:77`；`AgentController` `/agent-runs/{id}/retry` | **需新建** | 契约测试：失败后重试能跑通 |
| F19 | **运行事件流可见**（`<details>` 展开看到 `model` / `prompt_version` 与事件列表） | `AgentRunPanel.vue:99-104`，渲染 `run.events` 的 `attempt` + `kind` + `detail` | **需新建，且与 §2.7 是同一份需求** | 见下 |
| F20 | **复制会议并重新分析**（`reanalyze()`） | `AgentRunPanel.vue:79` | **需新建**（Java 侧复制会议 + 重跑） | 人工核对 |

### F19 与 §2.7 的关系（这里要写准，别说成"同一件事"）

**共用同一份需求来源，但不是同一份实现。** 三处不同：

1. **层级不同**。老家是 `meeting_agent_runs → meeting_agent_events`，一个 run 就是一次会议分析；§2.7 是 `agent_sessions → agent_events`，**会话在上、run 在下**，事件同时带 `session_id` 和 `run_id`——它是为「主管一句话 → 多个子智能体 → 多次 run」设计的。
2. **`seq` 是新增语义**。老家事件表字段是 `id / runId / attempt / kind / detailJson / createdAt`——**没有全局序号，没有续传**。而 §3.2 要求 `seq` 由 `agent_events.id` 提供、续传靠 `WHERE session_id=? AND id>?`。**老家的表搬过来得不到 `seq`，这是要新造的。**
3. **写入方向相反**。老家 Java 进程内直接写表；§2.7 要 Python 经 HTTP 写、并把自增 id 拿回来当 `seq`。

**另有一条被忽略的语义**：面板是 `第 {{ e.attempt }} 次 · {{ e.kind }}`——**`attempt` 在事件上，不在运行上**。也就是说**重试是追加一批新事件，不是替换**。这直接影响续传：重试后 `after_seq` 该从哪续？老家不存在这个问题（它没有续传），§2.7 必须回答。

### F17 不要接 LangGraph checkpointer（这条最容易想当然）

LangGraph 原生有 `checkpointer`（断点持久化）、`interrupt`（挂起待人审）、`Command(resume=…)`（续跑），
看着正好就是 F17 要的 `queued/running/awaiting_review` + 重试。五张图现在一个都没用——
但**这不是能力闲置，是刻意关闭**：

- `ai-service/README.md:58`：「用户 Bearer 令牌仅存在于单次调用闭包，**不进入图状态或模型消息**。
  禁用 LangSmith tracing；**不配置 checkpointer，不持久化会议内容**。」
  `:147`（「未增加工作流 checkpoint」）与 `:425`（「无 checkpoint、关闭 tracing」）各再复述一次。
- checkpointer 按设计就是把**图状态**写进存储，而图状态里装的是转写与候选产出——
  接上它 = **持久化会议内容**，正面撞上这条。想安全地接，得先让图状态只带引用不带内容
  （状态下沉到 §2.7 的表、节点按 id 重取），那是**五张图的重构**，不是加一个参数。
- 而且 run 状态**必须能被前端与 Java 审批 UI 查到**（F17 的验收就是「5 个状态都能到达」），
  LangGraph 的 checkpoint 不是那个查询面。**§2.7 的会话/事件表无论走哪条路都要建**。

所以「接 checkpointer 更省」不成立：省不掉 §2.7，还要新增依赖（checkpointer 的包不在
`requirements.txt` 里，那里只有 `langgraph==1.2.11`）。**真正可复用的是形状**——「状态在库、进程无状态」
这一点 §2.7 与 checkpointer 是同一个形状，值得保留；只是别用 LangGraph 的那份产物去实现它。

---

## 5. 新家更强的几处（反向差异，别当成对齐项）

| # | 外部功能 | 新家依据 | 老家 |
|---|---|---|---|
| F22 | **快照比对**：提案里的 `expected.status` 必须等于 Java 传回的 story 当前状态 | `contracts.py:134-135` → `snapshot_mismatch`；另有 `duplicate_story` / `unknown_story` / `duplicate_proposal_id` | ❌ 老家没有 |
| F23 | **负责人可解析 + 行动项签名去重** | retro `retro_contracts.py:88-92`：`unknown_owner`（`owner_id` 不在成员表即拒）、`duplicate_action_item`（`title + description + owner_id + deadline_text` 四元组签名重复即拒） | ❌ 老家没有（老家 `AnalysisValidator:141` 的 `seen` 是 evidence 字段集校验，不是去重；`AgentJobs:78` 的 `DuplicateKeyException` 是入队幂等，不是产出去重） |

F22 在换算时**不能丢**——它是防止模型基于过期快照提状态变更的唯一一道关。F23 同理：它拦的是"模型把同一件事换个措辞提两遍"，而 F15 的标题去重**拦不住**这种情况（签名里还有 description / owner / deadline 三个维度）。

---

## 6. 执行模型差异带来的行为变化（不是"落点"，要单独记账）

| # | 变化 | 说明 | **验收方式** |
|---|---|---|---|
| F21 | **模型自主选源 → 固定工作流节点** | 老家给模型 5 个工具（`search_stories` / `search_pool` / `list_tasks` / `list_members` / `project_summary`），模型自己决定何时查，可多次换关键词。新家是固定节点。**受影响的是分类质量**：prompt 明确要求「已有需求修改和任务调配仍应作为行动项或协调事项展示，不能伪装为新需求」——模型不先查，就分不出来。**注意这不是静默的**：真提了重复需求会被 F15 的代码层去重拦下并显示「未重复创建」 | 离线评测：同一批转写，比对新老家在「已有需求修改」类上的分类准确率 |
| F21a | **`project_summary` 本来就是预取** | `AgentRunner:192` `tools.execute("project_summary", ...)` → `emit.emit("context", ...)` → 注入 payload 的 `project` 字段，**不是**模型选的。所以「5 个工具」里模型真正能自主选的是**其余 4 个**（`search_stories` / `search_pool` / `list_tasks` / `list_members`） | — |

> **F21a 削弱了选项二的成本**：`project_summary` 本来就是无条件预取的先例，说明"喂全量上下文"对这一类数据已经是可接受的做法。真正需要权衡的只有 `search_stories` / `search_pool` / `list_tasks` / `list_members` 这四个按需查询的源——不是五个。

**这一条的二选一要在开工前定**：要么工作流给模型留自主选源的空间（那就不是纯固定节点），要么无条件把全部上下文喂进去（prompt 变长、成本上升、可能喂进无关内容）。**别等做的时候才发现**——这是本表里唯一会改变 ai-service 执行模型的条目。

---

## 7. 明确不在本表范围内的

- **planning 的重复**（Java `planning/` 包与 ai-service `planning/analyze` 并存）：本轮范围只有会议智能体，planning 不动，另行记账。
- **五类会议分析**（ai-service 现有能力）：这是**增量**，不是替换对象，不列入基线。
- **语音链路**（转写 / 说话人分离 / Assignment Engine）：ai-service 独有，老家没有，不属于"不能少"的范围。
- **平台工具层**：`PlatformToolCatalog` / `PlatformToolService` **直接依赖** `com.aicap.agent.AgentTools` 与 `AgentError`（`PlatformToolCatalog.java:3`、`PlatformToolService.java:3,67`）。那 5 个只读工具是**平台资产**，退役老 agent 时必须**先拆出来**，不能跟着一起删——删了主管自己就没法查数据了。
> **2026-10-11 已完成**：`agent/AgentTools` → `com.aicap.platform.MeetingTools`、`AgentError` → `com.aicap.common`；同批还把 `AgentProperties`/`ModelClient` → `com.aicap.llm`、切分逻辑 → `com.aicap.rag.TranscriptSegmenter`，并让后台调度器只认 `worker/WorkerTask` 接口。现 `com.aicap.agent` 外无人 import 它（`arch/AgentPackageIsSelfContainedTest` 钉住），删 `agent/` 不再牵动任何现行功能。

---

## 8. 未决

1. **F21 的二选一**（留自主选源 / 无条件喂全量上下文）——唯一会改执行模型的条目。
2. **F17/F19 的 `seq` 与续传语义**，含"重试后 `after_seq` 从哪续"——§2.7 必须回答。
3. ~~**F11 的错误码出不了边界**~~ **已收口**（`cbfc2d8` + 本轮重构）：
   - 细码透传：catch `ResultContractError` 必须在 `except ValueError` **之前**（它是 `ValueError` 子类，顺序反了两者一样被吞）；五处 validate 节点体并成 `graph_runtime.validate_candidate`。`api.py` 里契约码 → **422**，`invalid_*_context` 等内部状态码保持 502。
   - 四处逐字复制的证据核对并成 `contracts.py:require_quoted_evidence`；图构造并成 `graph_runtime.run_linear_graph`。
   - **仍未做的是「覆盖面」**：F11 现在只覆盖提案类；F3–F8 开放类产出还不存在，所以「每一类都测」仍**随 F3–F8 落地**（见 F12）。
4. **F13 片段全覆盖**在新家是否仍适用（新家是类型化分析，覆盖率的口径可能不同）。

---

*本表按 `AgentRunPanel.vue` 的渲染面、`AnalysisValidator` / `AgentJobs` 的代码路径，以及 ai-service 五类 `*_contracts.py` 的**逐类**核对取证（F11/F12 的"有没有"是五类分别查过的，不是看一处推断全部）；凡标 ❌ 的都是核过「确实没有」，不是推断。*
