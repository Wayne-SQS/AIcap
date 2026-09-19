package com.aicap.contract;

import com.aicap.rag.Chunk;
import com.aicap.rag.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 索引层契约测试(S1)。
 *
 * <p>被测量:{@code GET /api/admin/knowledge/stats}、{@code POST /api/admin/knowledge/reindex}、
 * {@code GET /api/admin/knowledge/search}。
 *
 * <p>关键设计:
 * <ul>
 *   <li><b>不调用真实 embedding</b>:{@link EmbeddingFixture} 是本地 OpenAI 兼容服务
 *       (127.0.0.1:19378),向量由文本哈希确定性生成。整条链(切分→向量化→落库→检索)
 *       真实执行,同时零外网、零费用、可复现。</li>
 *   <li><b>文档源指向临时目录</b>:真跑 {@code ../docs} 会把仓库文档全量索引进来,
 *       测试既慢又依赖仓库内容。{@code @BeforeAll} 里把 {@link RagProperties#setDocsDir}
 *       指到临时目录,doc 源的内容因此完全可控。</li>
 *   <li><b>doc 源设为 admin 可见</b>({@code aicap.rag.acl.doc=admin}),
 *       否则全部源都是 member 级,ACL 过滤这条路径没有任何用例会走到。</li>
 * </ul>
 * 隔离:独立测试库 {@code aicap_java_test},且 {@code reset_test_data.sql} 会清空知识表。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql",
        // 本类不测 Agent 队列,关掉 worker 避免与其他类的上下文抢队列
        "aicap.llm.agent-worker-enabled=false",
        "aicap.rag.vector-store=mysql",
        "aicap.rag.embedding.dimension=" + EmbeddingFixture.DIMENSION,
        "aicap.rag.embedding.model=fixture-embed",
        "aicap.rag.embedding.base-url=http://127.0.0.1:19378",
        "aicap.rag.embedding.api-key=fixture-key",
        "aicap.rag.embedding.batch-size=16",
        "aicap.rag.acl.doc=admin"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeIndexContractTest extends ContractTestSupport {

    private final EmbeddingFixture fixture = new EmbeddingFixture();

    @Autowired
    private RagProperties ragProperties;

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
        assertEquals(scanned, stats.json().path("total_chunks").asInt(), "统计总数应与重建报告一致");
        assertEquals(0, stats.json().path("pending_chunks").asInt(), "重建后不应有待索引块");
        assertEquals(0, stats.json().path("stale_vectors").asInt(), "重建后不应有旧模型残留的向量");
        assertEquals("mysql", stats.json().path("vector_store").asText());
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
        // 分数必须真的有区分度:全等分说明相似度算错了(例如恒返 0 或未归一化)
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
