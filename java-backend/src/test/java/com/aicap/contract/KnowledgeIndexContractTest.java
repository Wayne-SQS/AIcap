package com.aicap.contract;

import com.aicap.rag.Chunk;
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
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 索引层契约测试(S1)—— <b>两套向量库实现各跑一遍</b>。
 *
 * <p>被测量:{@code GET /api/admin/knowledge/stats}、{@code POST /api/admin/knowledge/reindex}、
 * {@code GET /api/admin/knowledge/search}。
 *
 * <h2>为什么是「抽象基类 + 两个子类」而不是 {@code @ParameterizedTest}</h2>
 * 向量库实现由 {@code aicap.rag.vector-store} 在<b>启动时</b>经
 * {@code @ConditionalOnProperty} 选定 Bean,运行期换不了;而 {@code @SpringBootTest}
 * 的 {@code properties} 必须是编译期常量,参数化方法喂不进去。JUnit 的 {@code @Nested}
 * 也不行 —— Spring 不为嵌套类单独建上下文。所以逻辑全部上提到这里,只有注解留在
 * {@link KnowledgeIndexMysqlContractTest} 与 {@link KnowledgeIndexQdrantContractTest}。
 * 代价是那份公共 properties 块要写两遍,这与本项目「数据源逐类内联、不搞
 * {@code application-test.yml} profile」的既有约定是一致的。
 *
 * <h2>为什么必须跑两遍(而不是"mysql 通了就算数")</h2>
 * 两条实现的落点完全不同:{@link com.aicap.rag.MysqlVectorStore} 靠 SQL 比
 * {@code knowledge_chunks.acl_rank} 列;{@link com.aicap.rag.QdrantVectorStore}
 * 把同一个数存进 payload,用 {@code range.lte} 过滤。
 * <b>只测一条,另一条上的权限洞不会有任何人发现</b> —— 而"语义检索绕过可见性"
 * 正是本项目红线③。{@link #aclConfigChangeIsAppliedWithoutForce} 尤其如此:
 * 它断言的是"改配置必须真的挡住检索",而这在两条实现上是两段不同的代码。
 *
 * <h2>三条隔离手段</h2>
 * <ul>
 *   <li><b>不调用真实 embedding</b>:{@link EmbeddingFixture} 是本地 OpenAI 兼容服务
 *       (127.0.0.1:19378),向量由文本哈希确定性生成。整条链(切分→向量化→落库→检索)
 *       真实执行,同时零外网、零费用、可复现。</li>
 *   <li><b>文档源指向临时目录</b>:真跑 {@code ../docs} 会把仓库文档全量索引进来,
 *       测试既慢又依赖仓库内容。{@code @BeforeAll} 里把 {@link RagProperties#setDocsDir}
 *       指到临时目录,doc 源的内容因此完全可控。</li>
 *   <li><b>独立测试库 + 独立向量集合</b>:MySQL 侧是 {@code aicap_java_test}
 *       (由 {@code reset_test_data.sql} 清空),Qdrant 侧是 {@code aicap_chunks_test}
 *       (由 {@link QdrantTestSupport#resetCollection} 删除)。两边都不能碰开发数据。</li>
 * </ul>
 *
 * <p>{@code doc} 源设为 admin 可见({@code aicap.rag.acl.doc=admin}),
 * 否则全部源都是 member 级,ACL 过滤这条路径没有任何用例会走到。
 */
abstract class KnowledgeIndexContractTest extends ContractTestSupport {

    /** 本遍跑的是哪条实现。由子类给出,用于断言配置真的选对了 Bean(见各子类 javadoc) */
    protected abstract String expectedVectorStore();

    @Autowired
    protected RagProperties ragProperties;

    private final EmbeddingFixture fixture = new EmbeddingFixture();

    private Path docsDir;

    @BeforeAll
    void startFixtureAndPointDocsAtTempDir() throws IOException {
        fixture.start();
        // 文档源用临时目录:内容可控、索引快,且不受仓库文档变化影响
        docsDir = Files.createTempDirectory("aicap-rag-docs");
        write(docsDir.resolve("需求说明.md"), """
                # 需求说明

                本文档描述成员批量导入功能。
                ## 概述
                管理员需要一个批量导入成员的功能,降低逐条录入的成本。
                ## 验收标准
                导入失败时必须整批回滚,不允许出现部分成功。
                """);
        write(docsDir.resolve("会议纪要.md"), """
                # 会议纪要

                ## 讨论要点
                会议转写功能需要支持录音自动转写,并保留原文片段用于证据引用。
                """);
        ragProperties.setDocsDir(docsDir.toAbsolutePath().toString());
        // 必须在任何索引发生之前清空向量库(见 resetVectorStoreBeforeIndex)
        resetVectorStoreBeforeIndex();
    }

    /**
     * 向量库侧的清理钩子,在任何索引发生<b>之前</b>调用。
     *
     * <p>默认空实现:MySQL 那一遍的隔离由 {@code reset_test_data.sql} 负责(它清掉
     * {@code knowledge_chunks} / {@code knowledge_vectors},相当于把向量库也清了)。
     * Qdrant 的点不归那个脚本管,所以那一遍必须覆写成删集合 ——
     * 见 {@link KnowledgeIndexQdrantContractTest}。
     *
     * <p>做成钩子而不是靠子类的 {@code @BeforeAll},是为了让"先清后建"这个顺序由代码
     * 保证:JUnit 是父类 {@code @BeforeAll} 先跑,顺序一旦反过来,表现是断言看到上一轮
     * 残留的点,而不是一条能读懂的错误。
     */
    protected void resetVectorStoreBeforeIndex() {
        // MySQL 实现:无操作,隔离已由 reset_test_data.sql 完成
    }

    @AfterAll
    void stopFixtureAndCleanup() throws IOException {
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

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    @Test
    void statsRequiresAdminOrOwner() {
        assertStatus(get("/api/admin/knowledge/stats", token(USER_VIEWER)), 403);
        assertStatus(get("/api/admin/knowledge/stats", token(USER_MEMBER)), 403);
        assertStatus(get("/api/admin/knowledge/stats", token(USER_ADMIN)), 200);
        assertStatus(get("/api/admin/knowledge/stats", token(USER_OWNER)), 200);
    }

    @Test
    void reindexRequiresAdminOrOwner() {
        assertStatus(post("/api/admin/knowledge/reindex", token(USER_VIEWER), null), 403);
        assertStatus(post("/api/admin/knowledge/reindex", token(USER_MEMBER), null), 403);
        // 未登录:token 直接传 null,不走 token() 缓存(null 不能作为 ConcurrentHashMap 的键)
        assertStatus(post("/api/admin/knowledge/reindex", null, null), 401);
    }

    // ------------------------------------------------------------------
    // 索引
    // ------------------------------------------------------------------

    @Test
    void rebuildIndexesEverySourceAndReportsHonestly() {
        ApiResponse r = post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null);
        assertStatus(r, 200);
        assertNotNull(r.json(), "重建响应非 JSON: " + r.body());

        int scanned = r.json().path("scanned").asInt();
        int created = r.json().path("created").asInt();
        int updated = r.json().path("updated").asInt();
        int embedded = r.json().path("embedded").asInt();

        // 配置 → 条件 Bean → 响应,这条链要端到端钉住。少这一句,子类把 properties 打错
        // 时会静默地跑在另一条实现上,而用例照样全绿 —— 参数化的意义就没了。
        // 注意键名是 camelCase:本响应直接序列化 IndexReport 记录,而下面的 /stats
        // 是手搭的 Map,那边才是 vector_store。两处键名不同是既有事实,不是笔误
        assertEquals(expectedVectorStore(), r.json().path("vectorStore").asText(),
                "重建报告里的向量库与配置不符: " + r.body());

        assertTrue(scanned > 0, "应至少扫描到种子数据(37 故事 + 16 任务 + 画像): " + r.body());
        assertEquals(0, r.json().path("failed").asInt(), "不应有向量化失败: " + r.body());
        // force=true 时全部重算,因此落库数应等于扫描数,且全部走了一次 embedding
        assertEquals(scanned, created + updated, "新建+更新应覆盖全部扫描块: " + r.body());
        assertEquals(scanned, embedded, "force 重建应向量化全部块: " + r.body());

        // 故事与任务源必须真的有内容 —— 只断言总数会掩盖"某一源整体没索引上"
        ApiResponse stats = get("/api/admin/knowledge/stats", token(USER_ADMIN));
        assertStatus(stats, 200);
        JsonNode bySource = stats.json().path("by_source");
        assertTrue(bySource.path("story").asInt() > 0, "story 源应有块: " + stats.body());
        assertTrue(bySource.path("task").asInt() > 0, "task 源应有块: " + stats.body());
        assertTrue(bySource.path("doc").asInt() > 0, "doc 源应有块: " + stats.body());
        assertEquals(expectedVectorStore(), stats.json().path("vector_store").asText(),
                "统计里的向量库与配置不符: " + stats.body());
        assertEquals(scanned, stats.json().path("total_chunks").asInt(), "统计总数应与重建报告一致");
        assertEquals(0, stats.json().path("pending_chunks").asInt(), "重建后不应有待索引块");
        assertEquals(0, stats.json().path("stale_vectors").asInt(), "重建后不应有旧模型残留的向量");

        // 向量库里的点数必须与块数相等。对 Qdrant 这一遍尤其关键:多出来的点只可能是
        // 上一轮残留(收集没清干净),而陈旧点会被 search 当成有效命中返回 ——
        // 这正是 MySQL 侧 reset_test_data.sql 与 Qdrant 侧 resetCollection 要解决的问题
        assertEquals(scanned, stats.json().path("total_vectors").asInt(),
                "向量库点数应恰好等于块数(多=有陈旧点、少=有块没写进去): " + stats.body());
    }

    @Test
    void rebuildSkipsUnchangedChunksAndCallsNoEmbedding() {
        assertStatus(post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null), 200);

        // 关键断言:第二次不 force 的重建必须"零 embedding 调用"
        // —— 这正是 content_hash 增量机制存在的意义(省钱),不测就等于没做
        fixture.resetCounter();
        ApiResponse second = post("/api/admin/knowledge/reindex?force=false", token(USER_ADMIN), null);
        assertStatus(second, 200);

        int scanned = second.json().path("scanned").asInt();
        assertTrue(scanned > 0, "第二次重建仍应扫描到块: " + second.body());
        assertEquals(0, second.json().path("created").asInt(), "内容未变不应新建: " + second.body());
        assertEquals(0, second.json().path("updated").asInt(), "内容未变不应更新: " + second.body());
        assertEquals(scanned, second.json().path("unchanged").asInt(), "应全部判为未变: " + second.body());
        assertEquals(0, second.json().path("embedded").asInt(), "不应重新向量化: " + second.body());
        assertEquals(0, fixture.embeddedTexts(), "不应向上游发出任何 embedding 请求");
    }

    /**
     * 换 embedding 模型必须自动重算全部块。
     *
     * <p>这是本项目真跑数据时暴露的缺陷的回归测试:原先增量判断只看 content_hash,
     * 换模型时内容一字未改 → 全部判为"未变" → 库里留着旧模型的向量、查询却用新模型
     * 编码,相似度成为噪声<b>且不报任何错</b>。比索引失败更危险,因为没人会发现。
     *
     * <p>对 Qdrant 这一遍,重算还多一层意义:{@code embedding_model} 只记在 MySQL 里,
     * 向量本体在 Qdrant,判定过期靠的是重新 upsert 覆盖同一 UUID 的点。
     * 若 point id 不是确定性映射,这里会表现为"旧向量还在、新向量也在",检索结果翻倍。
     */
    @Test
    void switchingEmbeddingModelRecomputesStaleVectors() {
        assertStatus(post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null), 200);

        String original = ragProperties.getEmbedding().getModel();
        try {
            ragProperties.getEmbedding().setModel(original + "-v2");
            fixture.resetCounter();

            ApiResponse switched = post("/api/admin/knowledge/reindex?force=false", token(USER_ADMIN), null);
            assertStatus(switched, 200);
            int scanned = switched.json().path("scanned").asInt();
            assertTrue(scanned > 0, "应扫描到块: " + switched.body());
            assertEquals(0, switched.json().path("created").asInt(), "内容没变不该新建: " + switched.body());
            assertEquals(scanned, switched.json().path("stale").asInt(),
                    "换模型后每一块都应判为过期: " + switched.body());
            assertEquals(scanned, switched.json().path("embedded").asInt(),
                    "过期块必须全部重新向量化: " + switched.body());
            assertTrue(fixture.embeddedTexts() > 0, "应真的调用了 embedding");

            // 覆盖写(而非追加)必须在向量库里得到验证:点数翻倍就是 point id 不稳定
            assertEquals(scanned, get("/api/admin/knowledge/stats", token(USER_ADMIN)).json()
                    .path("total_vectors").asInt(),
                    "重算应是覆盖同一批点,不应在向量库里堆积重复点");

            // 关键后半段:重算后必须回到"零调用"。若模型名没落库,这里会再次全量重算 ——
            // 只断言"换模型会重算"是不够的,那不能区分"真的记住了模型"和"永远重算"
            fixture.resetCounter();
            ApiResponse again = post("/api/admin/knowledge/reindex?force=false", token(USER_ADMIN), null);
            assertStatus(again, 200);
            assertEquals(0, again.json().path("stale").asInt(), "已是当前模型,不应再判过期: " + again.body());
            assertEquals(0, again.json().path("embedded").asInt(), "不应重复向量化: " + again.body());
            assertEquals(0, fixture.embeddedTexts(), "不应向上游发出任何 embedding 请求");

            assertEquals(0, get("/api/admin/knowledge/stats", token(USER_ADMIN)).json()
                    .path("stale_vectors").asInt(), "重算后不应再有过期向量");
        } finally {
            // 恢复模型名,并立刻重算一次让库里的 embedding_model 与之一致:
            // 否则后续用例会看到"过期块"而误判(本类共用一个 Spring 上下文)
            ragProperties.getEmbedding().setModel(original);
            assertStatus(post("/api/admin/knowledge/reindex?force=false", token(USER_ADMIN), null), 200);
        }
    }

    /**
     * ACL 配置变更必须自动落到已索引的块上。
     *
     * <p>回归的是真跑时撞到的第二个缺陷:{@code acl_role} 是<b>配置</b>不是内容,
     * {@code content_hash} 覆盖不到它。只改 ACL 时内容与模型都没变 → 增量判断认为"未变"
     * → 分支根本不进 → 库里留着旧角色。表现是"配置里已把某个源收紧为 admin,
     * 检索却照样查得到",权限静默失效。
     *
     * <p>本用例是"必须跑两套实现"的最强理由:MySQL 实现靠 SQL JOIN 现读角色,
     * 而 Qdrant 把 {@code acl_rank} 存在 payload 里 —— <b>不重写向量就改不掉</b>。
     * 两条实现的失效方式不同,只测一条等于只验了一半。
     */
    @Test
    void aclConfigChangeIsAppliedWithoutForce() {
        assertStatus(post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null), 200);

        String original = ragProperties.getAcl().get(Chunk.SRC_STORY);
        try {
            ragProperties.getAcl().put(Chunk.SRC_STORY, "owner");

            // 先看 stats:配置改了但还没重索引,必须自己暴露出来,而不是等重建才发现
            ApiResponse before = get("/api/admin/knowledge/stats", token(USER_ADMIN));
            assertTrue(before.json().path("acl_mismatch").asInt() > 0,
                    "改配置后 stats 应报出角色不一致的块: " + before.body());

            ApiResponse r = post("/api/admin/knowledge/reindex?force=false", token(USER_ADMIN), null);
            assertStatus(r, 200);
            int synced = r.json().path("aclSynced").asInt();
            assertTrue(synced > 0, "ACL 变更应被检出并同步: " + r.body());
            assertEquals(synced, r.json().path("embedded").asInt(),
                    "同步角色的块需重写向量(Qdrant 的 acl_rank 存在 payload 里): " + r.body());
            assertEquals(0, r.json().path("created").asInt(), "内容没变不该新建: " + r.body());
            assertEquals(0, get("/api/admin/knowledge/stats", token(USER_ADMIN)).json()
                    .path("acl_mismatch").asInt(), "同步后不应再有不一致的块");

            // 关键:收紧必须真的挡住检索,而不只是报表上好看
            ApiResponse asMember = get("/api/admin/knowledge/search?q=成员批量导入&top=50", token(USER_MEMBER));
            assertStatus(asMember, 200);
            for (JsonNode item : asMember.json().path("items")) {
                assertTrue(!"story".equals(item.path("source_type").asText()),
                        "story 已收紧为 owner,member 不应检索到: " + asMember.body());
            }
            ApiResponse asOwner = get("/api/admin/knowledge/search?q=成员批量导入&top=50", token(USER_OWNER));
            assertTrue(hasSource(asOwner, "story"), "owner 应仍能检索到 story 块: " + asOwner.body());
        } finally {
            // 还原配置并立刻同步,避免后续用例看到收紧后的角色而失败(本类共用一个 Spring 上下文)
            if (original == null) {
                ragProperties.getAcl().remove(Chunk.SRC_STORY);
            } else {
                ragProperties.getAcl().put(Chunk.SRC_STORY, original);
            }
            assertStatus(post("/api/admin/knowledge/reindex?force=false", token(USER_ADMIN), null), 200);
        }
    }

    @Test
    void forceRebuildRecomputesEvenWhenUnchanged() {
        assertStatus(post("/api/admin/knowledge/reindex?force=false", token(USER_ADMIN), null), 200);

        fixture.resetCounter();
        ApiResponse forced = post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null);
        assertStatus(forced, 200);

        int scanned = forced.json().path("scanned").asInt();
        assertEquals(scanned, forced.json().path("embedded").asInt(),
                "force 重建必须无视 content_hash 全量重算: " + forced.body());
        assertTrue(fixture.embeddedTexts() > 0, "force 重建应真的调用了 embedding");
    }

    // ------------------------------------------------------------------
    // 检索与 ACL
    // ------------------------------------------------------------------

    @Test
    void searchReturnsHitsAndGivesAdminTheRestrictedSource() {
        assertStatus(post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null), 200);

        ApiResponse r = get("/api/admin/knowledge/search?q=成员批量导入&top=50", token(USER_ADMIN));
        assertStatus(r, 200);
        assertEquals(true, r.json().path("configured").asBoolean(), "fixture 已配置,应可检索: " + r.body());

        JsonNode items = r.json().path("items");
        assertTrue(items.isArray() && items.size() > 0, "应检索到结果: " + r.body());
        // 分数必须真的有区分度:全等分说明相似度算错了(例如恒返 0 或未归一化)。
        // 两条实现的打分口径不同(应用层余弦 vs Qdrant 的 Cosine 距离),这条断言同时钉住两者
        double first = items.get(0).path("score").asDouble();
        assertTrue(first > 0, "首位得分应大于 0: " + r.body());

        boolean hasDoc = false;
        for (JsonNode item : items) {
            if ("doc".equals(item.path("source_type").asText())) {
                hasDoc = true;
                break;
            }
        }
        assertTrue(hasDoc, "admin 应能看到 admin 级的 doc 块: " + r.body());
    }

    @Test
    void searchHidesRestrictedSourceFromLowerRoles() {
        assertStatus(post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null), 200);

        // doc 源被配成 acl_role=admin;member/viewer 检索时它必须彻底不出现 ——
        // 否则语义检索就成了绕过权限的后门
        for (String who : new String[]{USER_MEMBER, USER_VIEWER}) {
            ApiResponse r = get("/api/admin/knowledge/search?q=成员批量导入&top=50", token(who));
            assertStatus(r, 200);
            for (JsonNode item : r.json().path("items")) {
                assertTrue(!"doc".equals(item.path("source_type").asText()),
                        who + " 不应检索到 admin 级 doc 块: " + r.body());
            }
        }
    }

    @Test
    void searchRejectsEmptyAndOverlongQuery() {
        assertEquals(400, get("/api/admin/knowledge/search?q=", token(USER_ADMIN)).status());
        assertEquals(400, get("/api/admin/knowledge/search?q=" + "x".repeat(501), token(USER_ADMIN)).status());
        assertEquals(401, get("/api/admin/knowledge/search?q=导入", null).status());
    }

    // ------------------------------------------------------------------

    private static boolean hasSource(ApiResponse response, String sourceType) {
        for (JsonNode item : response.json().path("items")) {
            if (sourceType.equals(item.path("source_type").asText())) {
                return true;
            }
        }
        return false;
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
