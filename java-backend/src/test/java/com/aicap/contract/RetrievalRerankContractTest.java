package com.aicap.contract;

import com.aicap.agent.AgentProperties;
import com.aicap.contract.RerankModelFixture.Scenario;
import com.aicap.rag.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
 * 精排路径契约测试(S2,设计文档 A6)。
 *
 * <p>单独一个类、{@code provider=llm}:精排与三路召回是两种失败模式,
 * 混在一个上下文里会让"召回错了"与"精排坏了"在同一条断言上显形,定位成本高。
 *
 * <p>本类重点覆盖<b>降级行为</b>,而不只是正常路径 —— 精排依赖外部模型服务,
 * 它坏掉是常态而不是异常。系统在那种时候必须给出"少一次精排但仍然可用"的结果,
 * 并把降级事实如实写出来;静默地退回粗排顺序、却对外声称是精排结果,才是真正的缺陷。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql",
        "aicap.llm.agent-worker-enabled=false",
        "aicap.rag.retrieval.rerank.provider=llm",
        "aicap.rag.vector-store=mysql",
        "aicap.rag.embedding.dimension=" + EmbeddingFixture.DIMENSION,
        "aicap.rag.embedding.model=fixture-embed",
        "aicap.rag.embedding.base-url=http://127.0.0.1:19378",
        "aicap.rag.embedding.api-key=fixture-key",
        "aicap.rag.embedding.batch-size=16"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalRerankContractTest extends ContractTestSupport {

    private static final String FIXTURE_BASE_URL = "http://127.0.0.1:19379";
    private static final String FIXTURE_MODEL = "fixture-rerank";

    private final EmbeddingFixture embedding = new EmbeddingFixture();
    private final RerankModelFixture rerank = new RerankModelFixture();

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private AgentProperties agentProps;

    private Path docsDir;

    @BeforeAll
    void startFixturesAndIndex() throws IOException {
        embedding.start();
        rerank.start();
        docsDir = Files.createTempDirectory("aicap-rag-rerank-docs");
        Files.writeString(docsDir.resolve("需求说明.md"), """
                # 需求说明

                ## 概述
                管理员需要一个批量导入成员的功能,降低逐条录入的成本。
                """, StandardCharsets.UTF_8);
        ragProperties.setDocsDir(docsDir.toAbsolutePath().toString());

        ApiResponse r = post("/api/admin/knowledge/reindex?force=true", token(USER_ADMIN), null);
        assertStatus(r, 200);
        assertTrue(r.json().path("embedded").asInt() > 0, "索引未产生任何向量: " + r.body());
    }

    @AfterAll
    void stopFixtures() throws IOException {
        embedding.stop();
        rerank.stop();
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

    /** 默认把精排指向本地 fixture;个别用例会故意改坏它 */
    @BeforeEach
    void pointAtFixture() {
        rerank.prepare(Scenario.REVERSE);
        agentProps.setApiKey("fixture-test-key");
        agentProps.setBaseUrl(FIXTURE_BASE_URL);
        agentProps.setModel(FIXTURE_MODEL);
    }

    // ------------------------------------------------------------------

    /**
     * 精排真的改变了顺序,且理由被带进结果。
     *
     * <p>用"反转"而不是随机排序作为 fixture 行为:随机顺序下"精排生效了"与
     * "精排没生效、只是碰巧不同"无法区分。反转是可预测的,可以断言<b>逐位相等</b>。
     */
    @Test
    void rerankReordersCandidatesAndCarriesReasons() {
        JsonNode result = retrieve("US01", 4, 4);
        List<String> fused = ids(result.path("fused"));
        List<String> finals = ids(result.path("finalHits"));

        assertFalse(fused.isEmpty(), "融合结果不应为空,否则本用例前提不成立: " + result);
        assertEquals("llm", result.path("reranker").asText(), "应使用 llm 精排: " + result);
        assertTrue(result.path("degraded").isNull(), "正常路径不应降级: " + result);

        List<String> expected = new ArrayList<>(fused);
        java.util.Collections.reverse(expected);
        assertEquals(expected, finals, "精排结果应为融合顺序的反转(fixture 行为): " + result);

        JsonNode top = result.path("finalHits").get(0);
        assertEquals("rerank", top.path("stage").asText(), "最终结果应带 rerank 阶段标记");
        assertEquals(0.9, top.path("score").asDouble(), 1e-9, "分数应来自精排输出");
        assertEquals("fixture 反转排序", top.path("metadata").path("rerank_reason").asText(),
                "精排理由应带进结果,供调试台与日志回看: " + top);
    }

    /** 上游没配密钥是最常见的"精排不可用":必须降级 + 留痕,而不是报错或假装成功 */
    @Test
    void degradesHonestlyWhenModelIsNotConfigured() {
        agentProps.setApiKey("");

        JsonNode result = retrieve("US01", 4, 4);
        List<String> fused = ids(result.path("fused"));
        List<String> finals = ids(result.path("finalHits"));

        assertFalse(result.path("degraded").isNull(), "未配置模型时必须标记降级: " + result);
        assertTrue(result.path("degraded").asText().contains("未配置模型密钥"),
                "降级原因应说清是密钥未配置: " + result.path("degraded").asText());
        assertTrue(result.path("reranker").asText().contains("降级"),
                "reranker 字段应如实反映本次不是精排结果: " + result.path("reranker").asText());
        // 关键:降级不等于失败 —— 用户仍应拿到粗排顺序的结果,而不是空列表
        assertEquals(fused.subList(0, finals.size()), finals,
                "降级时应原样返回粗排顺序的前 N 条: " + result);
    }

    /** 模型返回自由文本(格式漂移)时,同样降级而不是把半截文本当成排序 */
    @Test
    void degradesWhenModelReturnsMalformedJson() {
        rerank.prepare(Scenario.MALFORMED);

        JsonNode result = retrieve("US01", 4, 4);
        List<String> finals = ids(result.path("finalHits"));

        assertFalse(result.path("degraded").isNull(), "输出无法解析时必须标记降级: " + result);
        assertTrue(result.path("degraded").asText().contains("JSON"),
                "降级原因应指出是输出格式问题: " + result.path("degraded").asText());
        assertFalse(finals.isEmpty(), "降级不应导致结果为空: " + result);
    }

    /**
     * 模型编造候选之外的 id:全部无效时判降级。
     *
     * <p>模型幻觉 id 是真实会发生的(尤其候选 id 形如 {@code story:US01:0})。
     * 这里断言的是"编造的 id 绝不会出现在最终结果里" —— 那会把一个不存在的 chunk
     * 交给下游当证据用。
     */
    @Test
    void neverAcceptsIdsOutsideTheCandidateSet() {
        rerank.prepare(Scenario.HALLUCINATED_IDS);

        JsonNode result = retrieve("US01", 4, 4);
        List<String> fused = ids(result.path("fused"));
        List<String> finals = ids(result.path("finalHits"));

        for (String id : finals) {
            assertTrue(fused.contains(id), "最终结果含候选集之外的 id: " + id + " not in " + fused);
        }
        assertFalse(result.path("degraded").isNull(),
                "全部 id 都无效时应判降级,而不是当作「精排后无结果」: " + result);
    }

    /** 精排每次都应真实调用模型(而不是复用上次结果) */
    @Test
    void rerankCallsTheModelOncePerRetrieval() {
        rerank.prepare(Scenario.REVERSE);
        int before = rerank.callCount();
        retrieve("US01", 4, 4);
        assertEquals(before + 1, rerank.callCount(), "一次检索应恰好调用一次精排模型");
    }

    // ------------------------------------------------------------------

    private JsonNode retrieve(String query, int fuseTopK, int finalTopK) {
        String path = "/api/admin/knowledge/retrieve?q=" + query
                + "&routes_topk=20&fuse_topk=" + fuseTopK + "&final_topk=" + finalTopK;
        ApiResponse r = get(path, token(USER_ADMIN));
        assertStatus(r, 200);
        assertNotNull(r.json(), "检索响应非 JSON: " + r.body());
        return r.json();
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : array) {
            ids.add(node.path("id").asText());
        }
        return ids;
    }
}
