package com.aicap.contract;

import com.aicap.rag.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混合检索契约测试(S2,设计文档 A5/A6/A7)—— <b>两套向量库实现各跑一遍</b>。
 *
 * <p>被测量:{@code GET /api/admin/knowledge/retrieve}、{@code GET /api/admin/knowledge/logs}。
 *
 * <p>{@code rerank.provider=none}:本类聚焦"三路召回 + RRF 融合 + ACL + 落日志",
 * 不想让一次模型调用把失败点混进来 —— 精排单独由 {@link RetrievalRerankContractTest} 覆盖。
 * 这也顺带验证了 {@code provider=none} 这个消融开关本身可用。
 *
 * <h2>为什么必须跑两套实现</h2>
 * 三路里只有向量路会碰向量库,而<b>两条实现过滤的是同一个数、存取方式却完全不同</b>:
 * {@link com.aicap.rag.MysqlVectorStore} 在 SQL 里比 {@code knowledge_chunks.acl_rank} 列,
 * {@link com.aicap.rag.QdrantVectorStore} 把同一个数放进 payload 用 {@code range.lte} 过滤。
 * 数值只在 Java 侧算一次({@code RetrievalContext.rankOf}),但"它有没有真的进到
 * Qdrant 的 payload 里"仍然只有这一遍能验证。<b>后者漏写或写错的表现是"向量路整体返回空"</b> ——
 * 而原有的用例里没有一条断言过向量路非空,所以这个失效模式能一路全绿地过去。
 * 见 {@link #returnsAllThreeRoutesWithStageLabels} 里为此补的断言。
 *
 * <p>与 S1 测试同样的三条隔离手段:本地 embedding fixture(零外网零费用)、
 * 文档源指向临时目录、独立测试库 {@code aicap_java_test};
 * Qdrant 那一遍另加独立集合 {@code aicap_chunks_test}(见 {@link QdrantTestSupport})。
 */
abstract class RetrievalContractTest extends ContractTestSupport {

    /** 本遍跑的是哪条实现。由子类给出,用于钉住配置真的选对了 Bean */
    protected abstract String expectedVectorStore();

    private static final String DOC_ONLY_PHRASE = "成员批量导入";

    private final EmbeddingFixture fixture = new EmbeddingFixture();

    @Autowired
    protected RagProperties ragProperties;

    private Path docsDir;

    @BeforeAll
    void startFixtureAndIndex() throws IOException {
        fixture.start();
        docsDir = Files.createTempDirectory("aicap-rag-retrieval-docs");
        write(docsDir.resolve("需求说明.md"), """
                # 需求说明

                ## 概述
                管理员需要一个批量导入成员的功能,降低逐条录入的成本。
                ## 验收标准
                导入失败时必须整批回滚,不允许出现部分成功。
                """);
        ragProperties.setDocsDir(docsDir.toAbsolutePath().toString());

        // 必须在重建之前清空向量库。放在这里而不是子类的 @BeforeAll,是因为 JUnit 规定
        // 父类 @BeforeAll 先跑 —— 写在子类里会变成"先 upsert 再删集合",向量路必然为空
        resetVectorStoreBeforeIndex();

        // 检索依赖索引已建好;force 一次,避免受其他测试类清库的影响
        ApiResponse r = post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null);
        assertStatus(r, 200);
        assertTrue(r.json().path("embedded").asInt() > 0, "索引未产生任何向量: " + r.body());
    }

    @AfterAll
    void stopFixture() throws IOException {
        fixture.stop();
        if (docsDir != null) {
            try (var paths = Files.walk(docsDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 临时目录清理失败不影响测试结论
                    }
                });
            }
        }
    }

    /**
     * 向量库侧的清理钩子,在任何索引发生<b>之前</b>调用。
     *
     * <p>默认空实现:MySQL 那一遍的隔离由 {@code reset_test_data.sql} 负责(它清掉
     * {@code knowledge_chunks} / {@code knowledge_vectors},相当于把向量库也清了)。
     * Qdrant 的点不归那个脚本管,所以那一遍必须覆写成删集合 ——
     * 见 {@link RetrievalQdrantContractTest}。
     *
     * <p>做成钩子而不是靠子类的 {@code @BeforeAll},是为了让"先清后建"这个顺序由代码
     * 保证:JUnit 是父类 {@code @BeforeAll} 先跑,而重建就在父类里 ——
     * 顺序一旦反过来,表现是向量路整体为空,而不是一条能读懂的错误。
     */
    protected void resetVectorStoreBeforeIndex() {
        // MySQL 实现:无操作,隔离已由 reset_test_data.sql 完成
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    @Test
    void retrieveRequiresLoginButNotElevatedRole() {
        // 未登录:401(token 传 null 不进缓存)
        assertStatus(get("/api/admin/knowledge/retrieve?q=US01", null), 401);
        // 检索结果本身已按调用者做过 ACL 过滤,因此不限制角色 —— 与 /search 一致
        assertStatus(get("/api/admin/knowledge/retrieve?q=US01", token(USER_VIEWER)), 200);
        assertStatus(get("/api/admin/knowledge/retrieve?q=US01", token(USER_MEMBER)), 200);
    }

    /** 日志含其他人的查询词,不是检索调试需要的信息 —— 必须比 /retrieve 收得更紧 */
    @Test
    void logsRequireAdminOrOwner() {
        assertStatus(get("/api/admin/knowledge/logs", token(USER_VIEWER)), 403);
        assertStatus(get("/api/admin/knowledge/logs", token(USER_MEMBER)), 403);
        assertStatus(get("/api/admin/knowledge/logs", token(USER_OWNER)), 200);
        assertStatus(get("/api/admin/knowledge/logs", token(USER_ADMIN)), 200);
    }

    @Test
    void rejectsEmptyAndOverlongQuery() {
        assertStatus(get("/api/admin/knowledge/retrieve?q=", token(USER_ADMIN)), 400);
        assertStatus(get("/api/admin/knowledge/retrieve?q=" + "长".repeat(501), token(USER_ADMIN)), 400);
    }

    // ------------------------------------------------------------------
    // 三路召回
    // ------------------------------------------------------------------

    /**
     * 调试台必须并排给出三路 —— 少了任何一路,"这条为什么没进来"就无法回答。
     *
     * <p>顺带在这里补上<b>向量路非空</b>的断言:这是本类唯一一处能发现
     * "ACL 过滤写反了导致向量路整体返回空"的地方。原先三路里只有关键词路与图路
     * 各自被断言过非空,向量路是空的也不会有任何用例变红 —— 而对 Qdrant 实现来说,
     * {@code acl_rank} 没进 payload 就正好是这个表现。
     */
    @Test
    void returnsAllThreeRoutesWithStageLabels() {
        JsonNode result = retrieve("US01", USER_ADMIN);

        List<String> names = new ArrayList<>();
        for (JsonNode route : result.path("routes")) {
            names.add(route.path("name").asText());
        }
        assertEquals(List.of("vector", "keyword", "graph"), names, "路序应固定且完整: " + result);
        // 配置 → 条件 Bean → 响应,端到端钉住;少这一句,子类 properties 打错时会静默跑在另一条实现上
        assertEquals(expectedVectorStore(), result.path("vectorStore").asText(),
                "检索结果里的向量库与配置不符: " + result);
        assertTrue(result.path("configured").asBoolean(), "应已配置: " + result);
        assertEquals("none", result.path("reranker").asText(), "provider=none 时精排实现应为 none");
        assertTrue(result.path("degraded").isNull(), "本类未配置降级条件,不该出现降级: " + result);

        assertFalse(routeIds(result, "vector").isEmpty(),
                "向量路应有命中。空了通常不是'库里没数据',而是 ACL 过滤把点全挡掉了 —— "
                        + "例如 acl_rank 没写进 payload,Qdrant 的 range.lte 就会匹配不到任何点: " + result);
    }

    /**
     * 关键词路必须能把实体编号捞回来 —— 这是它存在的全部理由。
     *
     * <p>向量路在 fixture 下也会返回东西(字符哈希有重叠),但排序是语义上的"像不像",
     * 对 {@code US01} 这种标识符没有任何判别力。
     *
     * <p><b>断言的是"召回",不是"排首位"</b> —— 实测发现查询 {@code US01} 时关键词路
     * 的首位是 {@code task:T03:0} 而非 {@code story:US01:0}。原因是语料的词频分布:
     * 故事块的内容里 {@code US01} 只出现一次(前缀 {@code 【用户故事 US01】}),
     * 而挂它的任务块里出现两次({@code 关联卡片：US01} 与
     * {@code 关联故事：US01,US03,US04}),BM25 的 TF 项因此把任务顶到前面。
     *
     * <p>这不是缺陷:单路排序不该被要求"完美",实体块自己居首由 RRF 的多路共识保证 ——
     * 见 {@link #fusionRanksEntityOwnChunkInTopFive}。把"必须排首位"写在这一路,
     * 只会得到一个注定要放宽的断言。
     */
    @Test
    void keywordRouteFindsEntityIdentifier() {
        JsonNode result = retrieve("US01", USER_ADMIN);

        List<String> keyword = routeIds(result, "keyword");
        assertFalse(keyword.isEmpty(), "关键词路应命中 US01: " + result);
        assertTrue(keyword.contains("story:US01:0"),
                "US01 自己的块必须被关键词路召回,否则专有名词这一路的定位不成立: " + keyword);
    }

    /**
     * 实体自己的块必须落在融合结果的前 5 —— 实体查询的最低体验线。
     *
     * <p><b>这里断言的是"前 5"而不是"首位",因为实测下来首位不是它。</b>
     * 查询 {@code US01} 的实测融合顺序(2026-09-20,fixture embedding):
     * <pre>
     * [task:T03:0, task:T01:0, story:US01:0, task:T04:0, ...]
     * </pre>
     * 拆开看每一路,结论是 <b>RRF 本身工作正常,是"共识"指向了另一个块</b>:
     * <ul>
     *   <li>{@code task:T03:0} 在<b>三路都名列前茅</b> —— 向量路第 1、关键词路第 1、
     *       图路第 3(种子之后的邻居首位)。按 RRF 的定义它就该赢;</li>
     *   <li>{@code story:US01:0} 只拿到两路的贡献:图路第 1(种子,1.0)与关键词路第 4。
     *       向量路在 fixture 下<b>根本没召回它</b>(前 20 里没有)。</li>
     * </ul>
     *
     * <p>根因是<b>本测试类的 embedding 是字符哈希 fixture,不是真模型</b>:
     * 它没有语义,给 {@code US01} 编码出的向量与 {@code US01} 自己的块并不特别接近,
     * 于是"语义路会支持实体自己的块"这个前提在此处不成立。这也说明
     * <b>不能用这个 fixture 去断言语义质量</b> —— 它只能验证管线是否接通。
     *
     * <p>换成真模型后 {@code story:US01:0} 应当三路齐备并升到首位。这是
     * <b>S2 遗留的验证项</b>,需要一次带真实 API key 的手工核对(见实施计划 §3),
     * 不是本类能覆盖的 —— 在这里把它写成断言,只会得到一个在 CI 里随机红绿的用例。
     *
     * <p>顺带记录了不用"归一化后加权求和"的原因:三路分值单位不可比
     * (余弦 ∈ [-1,1]、BM25 无上界、图路按跳数定值),只有<b>排名</b>可以相加。
     */
    @Test
    void fusionRanksEntityOwnChunkInTopFive() {
        JsonNode result = retrieve("US01", USER_ADMIN);

        List<String> fused = new ArrayList<>();
        for (JsonNode h : result.path("fused")) {
            fused.add(h.path("id").asText());
        }
        assertFalse(fused.isEmpty(), "融合结果不应为空: " + result);

        int at = fused.indexOf("story:US01:0");
        assertTrue(at >= 0 && at < 5,
                "US01 自己的块应落在融合前 5(实体查询的最低体验线),实际位置 " + at + ": " + fused);
    }

    /**
     * 图路的<b>独有能力</b>:从任务反向展开到它实现的故事。
     *
     * <p>实测确认的不对称性 —— task 块的增强前缀里写了 {@code 关联故事：US01,US03,US04},
     * 所以正向(故事 → 任务)关键词路本来就能做到;而 story 块的内容里<b>完全不提任务编号</b>,
     * 因此反向(任务 → 故事)是关键词路与向量路都做不到的。
     *
     * <p>断言因此是两条:图路<b>必须</b>给出 US01;关键词路<b>不该</b>给出 US01。
     * 只断言前者的话,这条用例在一路坏掉时仍会通过。
     */
    @Test
    void graphRouteExpandsTaskToStoriesThatKeywordRouteCannotFind() {
        JsonNode result = retrieve("T03", USER_ADMIN);

        List<String> graph = routeIds(result, "graph");
        assertTrue(graph.contains("story:US01:0"),
                "T03 的 story_ref 含 US01,图路应反向展开到它: " + graph);
        assertTrue(graph.contains("story:US02:0"),
                "T03 的 story_ref 含 US02,同样应展开: " + graph);

        List<String> keyword = routeIds(result, "keyword");
        assertFalse(keyword.contains("story:US01:0"),
                "US01 的内容里没有任何任务编号,关键词路不该命中它 —— 命中说明"
                        + "「图路独有」这个前提不成立了,需要重新评估两路的定位: " + keyword);
    }

    /** 正向展开(故事 → 任务):此方向关键词路也能做到,但图路必须同样给出,不能只做一半 */
    @Test
    void graphRouteExpandsStoryToItsTasks() {
        JsonNode result = retrieve("US01", USER_ADMIN);

        List<String> graph = routeIds(result, "graph");
        assertTrue(graph.contains("task:T01:0"), "T01 的 story_ref 含 US01,应被展开: " + graph);
        assertTrue(graph.contains("task:T03:0"), "T03 的 kanban_card_id 是 US01,应被展开: " + graph);
    }

    /** 查询里没有实体编号时,图路必须退回关键词找种子,而不是直接给空 */
    @Test
    void graphRouteFallsBackToKeywordSeedsWhenNoEntityIdInQuery() {
        JsonNode result = retrieve("成员批量导入", USER_ADMIN);

        List<String> graph = routeIds(result, "graph");
        assertFalse(graph.isEmpty(), "无实体编号时图路应靠关键词种子兜底: " + result);
    }

    /** 融合结果必须去重:同一 chunk 在三路都出现时只能占一条 */
    @Test
    void fusionDeduplicatesAcrossRoutes() {
        JsonNode result = retrieve("US01", USER_ADMIN);

        List<String> fused = new ArrayList<>();
        for (JsonNode h : result.path("fused")) {
            fused.add(h.path("id").asText());
        }
        assertEquals(fused.size(), fused.stream().distinct().count(), "融合结果出现重复 chunk: " + fused);
        assertEquals(List.of("rrf"),
                List.of(result.path("fused").get(0).path("stage").asText()),
                "融合结果应带 rrf 阶段标记: " + result);
    }

    /** 最终结果条数受 final_top_k 约束,且必须是融合结果的子集(不能凭空冒出新 chunk) */
    @Test
    void finalHitsRespectTopKAndComeFromFusedSet() {
        JsonNode result = retrieve("US01", USER_ADMIN, 5, 10, 2);

        List<String> fused = new ArrayList<>();
        for (JsonNode h : result.path("fused")) {
            fused.add(h.path("id").asText());
        }
        List<String> finals = new ArrayList<>();
        for (JsonNode h : result.path("finalHits")) {
            finals.add(h.path("id").asText());
        }
        assertTrue(finals.size() <= 2, "final_top_k=2 时不应超过 2 条: " + finals);
        assertTrue(fused.containsAll(finals), "最终结果必须来自融合候选: " + finals + " not in " + fused);
    }

    // ------------------------------------------------------------------
    // ACL(设计文档 A7:三路都必须过滤,漏一路就是绕权后门)
    // ------------------------------------------------------------------

    /**
     * doc 源已配置为 admin 可见,member/viewer 必须<b>在任何一路</b>都看不到 doc 块。
     *
     * <p>逐路断言而不是只看最终结果:某一漏了 ACL 时,只要它在融合中被别的路挤下去,
     * 只看最终结果就发现不了 —— 而那条路径在小规模数据上随时可能翻身。
     *
     * <p>本用例是"必须跑两套实现"的核心:**向量路的 ACL 判定要过两条不同的存取路径**
     * (SQL 比 {@code knowledge_chunks.acl_rank} 列 vs Qdrant 在 payload 上 {@code range.lte}),
     * 只测一条等于只验了一半,而漏的那一半正是"语义检索绕过可见性"的后门。
     */
    @Test
    void aclHidesRestrictedSourceFromEveryRoute() {
        JsonNode admin = retrieve(DOC_ONLY_PHRASE, USER_ADMIN);
        assertTrue(containsSourceType(admin, "doc"),
                "admin 应能在某一路看到 doc 块(否则本用例前提不成立): " + admin);

        for (String user : List.of(USER_MEMBER, USER_VIEWER)) {
            JsonNode result = retrieve(DOC_ONLY_PHRASE, user);
            for (JsonNode route : result.path("routes")) {
                for (JsonNode hit : route.path("hits")) {
                    assertFalse("doc".equals(hit.path("sourceType").asText()),
                            user + " 不应在 " + route.path("name").asText()
                                    + " 路看到 admin 级 doc 块: " + hit);
                }
            }
            for (JsonNode hit : result.path("finalHits")) {
                assertFalse("doc".equals(hit.path("sourceType").asText()),
                        user + " 的最终结果不应含 doc 块: " + hit);
            }
        }
    }

    /**
     * 未识别的角色必须按「最高要求」处理,而不是「无限制」。
     *
     * <p>回归的是一个<b>只在 MySQL 上存在、且失败方向是放行</b>的缺陷:当时 SQL 用
     * {@code FIELD(c.acl_role,'member','owner','admin') <= roleRank} 比字符串,而
     * {@code FIELD} 对认不出的值返回 <b>0</b> —— {@code 0 <= 任何等级} 恒成立,
     * 于是「角色名写错」在 MySQL 上等于「人人可见」;Qdrant 侧读的是 {@code rankOf}
     * 写进 payload 的 {@code acl_rank},认不出的角色给的是最高要求,即「只有 admin 可见」。
     * 同一个块在两套实现上结论相反,而当时的用例全用合法角色名,两边都绿 ——
     * 所以这条断言的是<b>方向</b>,不是某个具体角色的可见性。
     *
     * <p>注意它只能抓到 MySQL 那一侧:Qdrant 原先就是按 {@code rankOf} 写的,
     * 一直是对的。跑两遍的价值在于防止将来有人"优化"成两边一致地放行。
     *
     * <p>用的角色名是 {@code reviewer} —— 这不是随手编的:它是本系统<b>真实存在</b>
     * 的角色({@code Roles.reviewer()}),却不在 {@code RetrievalContext.LADDER} 里。
     * 也就是说"管理员把某个源配成 reviewer 级"是个完全合理的误配,而不是假想输入。
     * (值必须放得进 {@code acl_role varchar(16)},更长的名字会让重建直接 500。)
     *
     * <p>改配置放在 finally 还原:其余用例依赖子类配的 {@code doc=admin} 这个前提。
     */
    @Test
    void unknownAclRoleFailsClosedInsteadOfOpen() {
        String original = ragProperties.getAcl().get("doc");
        try {
            ragProperties.getAcl().put("doc", "reviewer");
            assertStatus(post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null), 200);

            // 前提:角色没有被整个丢弃,admin 仍然看得到。少了这一步,"member 看不到"
            // 可能只是角色失效而非"按最高要求处理",用例就证明不了它想证明的事
            assertTrue(containsSourceType(retrieve(DOC_ONLY_PHRASE, USER_ADMIN), "doc"),
                    "未识别角色应被当作最高要求(admin 仍可见),而不是被丢弃");

            for (String user : List.of(USER_MEMBER, USER_VIEWER)) {
                JsonNode result = retrieve(DOC_ONLY_PHRASE, user);
                for (JsonNode route : result.path("routes")) {
                    for (JsonNode hit : route.path("hits")) {
                        assertFalse("doc".equals(hit.path("sourceType").asText()),
                                "未识别的 acl 角色绝不能退化成「人人可见」: " + user + " 在 "
                                        + route.path("name").asText() + " 路看到了 " + hit);
                    }
                }
            }
        } finally {
            if (original == null) {
                ragProperties.getAcl().remove("doc");
            } else {
                ragProperties.getAcl().put("doc", original);
            }
            post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null);
        }
    }

    // ------------------------------------------------------------------
    // 检索日志
    // ------------------------------------------------------------------

    /**
     * 每次检索必须落一行日志,且三个阶段(json)都在。
     *
     * <p>只断言"有行"是不够的:三个 JSON 列分别是回看时回答
     * 「没召回 / 被融合挤掉 / 被精排判为不相关」的唯一依据,任一为空就等于那一段不可观测。
     */
    @Test
    void retrievalLogCapturesAllThreeStages() {
        String query = uniq("US01检索日志");
        JsonNode result = retrieve(query, USER_MEMBER);
        assertEquals(query, result.path("query").asText());

        ApiResponse logs = get("/api/admin/knowledge/logs?limit=5", token(USER_ADMIN));
        assertStatus(logs, 200);
        assertTrue(logs.json().isArray(), "日志应为数组: " + logs.body());

        JsonNode latest = logs.json().get(0);
        assertEquals(query, latest.path("query").asText(), "最新一行应是刚才那次检索: " + logs.body());
        assertEquals("member", latest.path("role").asText(), "应记录调用者角色(ACL 生效的依据)");
        assertFalse(latest.path("routesJson").isNull(), "routes_json 不应为空: " + latest);
        assertFalse(latest.path("fusedJson").isNull(), "fused_json 不应为空: " + latest);
        assertFalse(latest.path("finalJson").isNull(), "final_json 不应为空: " + latest);
        assertTrue(latest.path("latencyMs").asInt() >= 0, "应记录耗时: " + latest);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private JsonNode retrieve(String query, String user) {
        return retrieve(query, user, 20, 20, 5);
    }

    private JsonNode retrieve(String query, String user, int routesTopK, int fuseTopK, int finalTopK) {
        String path = "/api/admin/knowledge/retrieve?q=" + query
                + "&routes_topk=" + routesTopK + "&fuse_topk=" + fuseTopK + "&final_topk=" + finalTopK;
        ApiResponse r = get(path, token(user));
        assertStatus(r, 200);
        assertNotNull(r.json(), "检索响应非 JSON: " + r.body());
        return r.json();
    }

    /** 指定路返回的 chunk id 列表(按该路排名) */
    private static List<String> routeIds(JsonNode result, String routeName) {
        List<String> ids = new ArrayList<>();
        for (JsonNode route : result.path("routes")) {
            if (!routeName.equals(route.path("name").asText())) {
                continue;
            }
            for (JsonNode hit : route.path("hits")) {
                ids.add(hit.path("id").asText());
            }
        }
        return ids;
    }

    /** 三路结果里是否出现过指定源类型 */
    private static boolean containsSourceType(JsonNode result, String sourceType) {
        for (JsonNode route : result.path("routes")) {
            for (JsonNode hit : route.path("hits")) {
                if (sourceType.equals(hit.path("sourceType").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
