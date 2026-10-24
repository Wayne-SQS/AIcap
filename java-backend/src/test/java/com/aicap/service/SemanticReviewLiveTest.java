package com.aicap.service;

import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语义评审真实对比（需要 AICAP_LLM_API_KEY）。
 * 关键命题：静态规则分不出「完整」和「空洞」——两份 README 章节都齐、静态分接近；
 * 语义评审必须把空洞那份显著压低。
 */
@EnabledIfEnvironmentVariable(named = "AICAP_LLM_API_KEY", matches = ".+")
class SemanticReviewLiveTest {

    @Test
    void hollowReadmeScoresLowerThanRealOne() throws Exception {
        LlmProperties props = new LlmProperties();
        props.setApiKey(System.getenv("AICAP_LLM_API_KEY"));
        props.setBaseUrl("https://api.deepseek.com");
        props.setModel("deepseek-flash");
        props.setTimeoutSeconds(90);
        ModelClient client = new ModelClient(props, new ObjectMapper());
        SemanticReviewService service = new SemanticReviewService(client, props);
        RepoQualityScorer scorer = new RepoQualityScorer();

        Path real = Path.of(System.getProperty("java.io.tmpdir"), "petclinic", "README.md");
        Path hollow = Path.of(System.getProperty("user.dir")).resolve("../qa/fixtures/hollow-readme/README.md").normalize();
        String realText = Files.readString(real);
        String hollowText = Files.readString(hollow);

        // 静态：两份 README 的文档内容质量分
        int realStatic = staticDocScore(scorer, realText);
        int hollowStatic = staticDocScore(scorer, hollowText);

        Map<String, Object> realReview = service.review("readme", realText, "spring-petclinic");
        Map<String, Object> hollowReview = service.review("readme", hollowText, "订单服务示例");

        System.out.println("[SEM] static: real=" + realStatic + " hollow=" + hollowStatic);
        System.out.println("[SEM] semantic: real=" + realReview.get("score") + " hollow=" + hollowReview.get("score"));
        System.out.println("[SEM] real verdict: " + realReview.get("verdict"));
        System.out.println("[SEM] hollow verdict: " + hollowReview.get("verdict"));
        System.out.println("[SEM] hollow missing: " + hollowReview.get("missing"));
        System.out.println("[SEM] real unverified=" + realReview.get("unverified_quotes")
                + " hollow unverified=" + hollowReview.get("unverified_quotes"));

        assertEquals(true, realReview.get("available"), "真实 README 评审应成功：" + realReview.get("reason"));
        assertEquals(true, hollowReview.get("available"), "空洞 README 评审应成功：" + hollowReview.get("reason"));
        int realScore = ((Number) realReview.get("score")).intValue();
        int hollowScore = ((Number) hollowReview.get("score")).intValue();
        assertTrue(realScore - hollowScore >= 20,
                "语义评审应把空洞文档显著压低：真实 " + realScore + " vs 空洞 " + hollowScore
                        + "（静态分 " + realStatic + " vs " + hollowStatic + "）");
    }

    /** 只喂 README，看静态维度给的文档分 */
    private int staticDocScore(RepoQualityScorer scorer, String readme) {
        var files = java.util.List.of(
                new RepoQualityScorer.SourceFile("README.md", readme),
                new RepoQualityScorer.SourceFile("src/main/java/demo/A.java", "class A { void f() {} }"),
                new RepoQualityScorer.SourceFile("src/main/java/demo/B.java", "class B { void g() {} }"),
                new RepoQualityScorer.SourceFile("src/main/java/demo/C.java", "class C { void h() {} }"));
        @SuppressWarnings("unchecked")
        var dims = (java.util.List<Map<String, Object>>) scorer.score(files).get("dimensions");
        return dims.stream().filter(d -> "doc_quality".equals(d.get("key")))
                .map(d -> ((Number) d.get("score")).intValue()).findFirst().orElse(-1);
    }
}
