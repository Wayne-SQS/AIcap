package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US22 质量分析的真实 MySQL 契约（黑盒 REST，非 H2 mock）。
 * 钉住 H2 + mock 抓不到的路径：GET/POST 权限方向、幂等、缺口消失 409、跨项目 404、非法类型 422，
 * 以及 evidence 三路结构真实返回（本类未配 GitHub，ci/repository 应降级 available=false，不发 HTTP）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "profile-llm.api-key=",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QualityAnalysisContractTest extends ContractTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private static final String SOURCE = "US38";

    private void seedGapStory() {
        jdbc.update("INSERT INTO stories(project_id,code,title,description,acceptance,priority,sprint,activity,status,owner_id) "
                + "VALUES ('P001',?,?,?,?,'Must',3,5,0,NULL) ON DUPLICATE KEY UPDATE acceptance=VALUES(acceptance),owner_id=VALUES(owner_id)",
                SOURCE, "测试缺验收", "", "");
    }

    @AfterEach
    void cleanGapStory() {
        jdbc.update("DELETE FROM quality_improvements WHERE project_id='P001' AND source_code=?", SOURCE);
        jdbc.update("DELETE FROM tasks WHERE project_id='P001' AND name LIKE ?", "%" + SOURCE + "%");
        jdbc.update("DELETE FROM stories WHERE project_id='P001' AND code=?", SOURCE);
        // 测试集契约用例的残留:先删改进任务,再删登记与测试集。
        jdbc.update("DELETE FROM tasks WHERE project_id='P001' AND code IN ("
                + "SELECT improvement_task_code FROM quality_improvements "
                + "WHERE project_id='P001' AND source_code LIKE 'TS%')");
        jdbc.update("DELETE FROM quality_improvements WHERE project_id='P001' AND source_code LIKE 'TS%'");
        jdbc.update("DELETE FROM quality_test_sets WHERE project_id='P001'");
        // 代码质量/语义评审留档契约用例的残留。
        jdbc.update("DELETE FROM tasks WHERE project_id='P001' AND code IN ("
                + "SELECT improvement_task_code FROM quality_improvements WHERE project_id='P001' "
                + "AND (source_code LIKE 'CQ:%' OR source_code LIKE 'SEM:%'))");
        jdbc.update("DELETE FROM quality_improvements WHERE project_id='P001' "
                + "AND (source_code LIKE 'CQ:%' OR source_code LIKE 'SEM:%')");
        jdbc.update("DELETE FROM quality_code_snapshots WHERE project_id='P001'");
        jdbc.update("DELETE FROM quality_semantic_reviews WHERE project_id='P001'");
    }

    @Test
    void get_returnsEvidenceStructureAndMemberCanRead() {
        ApiResponse r = get("/api/projects/P001/quality-analysis", token(USER_VIEWER));
        assertStatus(r, 200);
        JsonNode evidence = r.json().path("evidence");
        assertTrue(evidence.isObject(), "evidence 应为对象 -> " + r.body());
        assertTrue(evidence.has("code_activity"), "缺 code_activity -> " + r.body());
        assertTrue(evidence.has("ci"), "缺 ci -> " + r.body());
        assertTrue(evidence.has("repository"), "缺 repository -> " + r.body());
        // 本类未配置 GitHub 映射,ci/repository 应降级而不是报错。
        assertTrue(evidence.path("ci").path("available").asBoolean(false) == false
                || evidence.path("ci").has("reason"), "未配置时应 available=false -> " + r.body());
        // limitations 必须按证据动态生成,不能还写着「未接入真实代码」而证据已可用。
        String limitations = r.json().path("limitations").asText("");
        assertTrue(!limitations.isBlank(), "limitations 不能为空");
    }

    @Test
    void viewerCannotCreateImprovement() {
        seedGapStory();
        ApiResponse r = post("/api/projects/P001/quality-analysis/" + SOURCE + "/improvements",
                token(USER_VIEWER), json(map("finding_kind", "story_missing_acceptance",
                        "owner_id", 1, "hours", 2, "week_start", 5, "week_end", 5)));
        assertStatus(r, 403);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quality_improvements WHERE source_code=?",
                Integer.class, SOURCE));
    }

    @Test
    void ownerConfirms_createsTask_idempotent_and404_422() {
        seedGapStory();
        String body = json(map("finding_kind", "story_missing_acceptance",
                "owner_id", 1, "hours", 2, "week_start", 5, "week_end", 5));
        ApiResponse first = post("/api/projects/P001/quality-analysis/" + SOURCE + "/improvements",
                token(USER_OWNER), body);
        assertStatus(first, 200);
        String taskCode = first.json().path("improvement_task_code").asText("");
        assertTrue(taskCode.startsWith("T"), "应创建改进任务 -> " + first.body());

        // 幂等:同缺口重复确认返回同一任务,不重复建。
        ApiResponse again = post("/api/projects/P001/quality-analysis/" + SOURCE + "/improvements",
                token(USER_OWNER), body);
        assertStatus(again, 200);
        assertEquals(taskCode, again.json().path("improvement_task_code").asText());

        // 跨项目:同一来源在 P002 不存在 → 404。
        ApiResponse cross = post("/api/projects/P002/quality-analysis/" + SOURCE + "/improvements",
                token(USER_OWNER), body);
        assertStatus(cross, 404);

        // 非法类型 → 422。
        ApiResponse bad = post("/api/projects/P001/quality-analysis/" + SOURCE + "/improvements",
                token(USER_OWNER), json(map("finding_kind", "code_coverage",
                        "owner_id", 1, "hours", 2, "week_start", 5, "week_end", 5)));
        assertStatus(bad, 422);
    }

    @Test
    void staleFinding_returns409() {
        seedGapStory();
        // 先补上验收条件,使 story_missing_acceptance 不再成立。
        jdbc.update("UPDATE stories SET acceptance='已有验收', owner_id=1 WHERE project_id='P001' AND code=?", SOURCE);
        ApiResponse r = post("/api/projects/P001/quality-analysis/" + SOURCE + "/improvements",
                token(USER_OWNER), json(map("finding_kind", "story_missing_acceptance",
                        "owner_id", 1, "hours", 2, "week_start", 5, "week_end", 5)));
        assertStatus(r, 409);
        assertNotNull(r.json(), "409 应有可读错误");
    }

    @Test
    void testSetInput_locatesMissingBoundaryCases_andOwnerCanConfirm() {
        String payload = json(map(
                "name", "契约边界测试集",
                "source", "test_case",
                "cases", List.of(
                        map("name", "用户名为空时登录失败"),
                        map("name", "无权限用户访问返回 403"))));
        ApiResponse created = post("/api/projects/P001/quality-analysis/test-sets", token(USER_OWNER), payload);
        assertStatus(created, 200);
        String code = created.json().path("code").asText("");
        assertTrue(code.startsWith("TS"), "测试集来源码应以 TS 开头 -> " + created.body());
        assertEquals(2, created.json().path("total").asInt(), "应接收 2 条用例 -> " + created.body());
        assertEquals(6, created.json().path("missing_boundaries").size(), "应定位 6 类遗漏边界 -> " + created.body());

        // 分析结果必须能定位到该测试集,并给出类别/位置/建议。
        ApiResponse analyzed = get("/api/projects/P001/quality-analysis", token(USER_VIEWER));
        assertStatus(analyzed, 200);
        JsonNode quality = analyzed.json().path("test_quality");
        assertTrue(quality.path("sets_total").asInt() >= 1, "test_quality 应含已提交测试集 -> " + analyzed.body());
        assertTrue(quality.path("cases_total").asInt() >= 2, "test_quality 应统计用例数 -> " + analyzed.body());
        assertEquals(8, quality.path("boundaries_total").asInt(), "边界类别总数固定 8 -> " + analyzed.body());
        JsonNode gap = null;
        for (JsonNode finding : analyzed.json().path("findings")) {
            if ("test_missing_boundary_cases".equals(finding.path("kind").asText())
                    && code.equals(finding.path("source_code").asText())) {
                gap = finding;
            }
        }
        assertNotNull(gap, "应定位到测试集 " + code + " 的遗漏边界缺口 -> " + analyzed.body());
        assertEquals("测试质量", gap.path("category").asText());
        assertTrue(gap.path("location").asText().contains("测试集 " + code), "缺口应带定位 -> " + gap);
        assertTrue(gap.path("suggestion").asText().contains("边界用例"), "缺口应带补测建议 -> " + gap);

        // 人工确认 → 生成可追踪改进任务,重复确认幂等。
        String body = json(map("finding_kind", "test_missing_boundary_cases",
                "owner_id", 1, "hours", 2, "week_start", 5, "week_end", 5));
        ApiResponse confirmed = post("/api/projects/P001/quality-analysis/" + code + "/improvements",
                token(USER_OWNER), body);
        assertStatus(confirmed, 200);
        String taskCode = confirmed.json().path("improvement_task_code").asText("");
        assertTrue(taskCode.startsWith("T"), "应创建改进任务 -> " + confirmed.body());
        ApiResponse again = post("/api/projects/P001/quality-analysis/" + code + "/improvements",
                token(USER_OWNER), body);
        assertStatus(again, 200);
        assertEquals(taskCode, again.json().path("improvement_task_code").asText(), "同缺口重复确认应幂等");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quality_improvements "
                + "WHERE project_id='P001' AND source_code=? AND finding_kind='test_missing_boundary_cases'",
                Integer.class, code));

        // viewer 只能读,不能提交测试集。
        ApiResponse forbidden = post("/api/projects/P001/quality-analysis/test-sets", token(USER_VIEWER), payload);
        assertStatus(forbidden, 403);
    }

    @Test
    void persistedContentQualityBecomesConfirmableGaps() {
        String codePayload = json(map(
                "available", true, "sufficient", true, "repo", "aicap/demo", "branch", "main",
                "total", 68, "grade", "待改进", "risk_level", "high",
                "dimensions", List.of(map("key", "test_density", "name", "测试密度")),
                "findings", List.of(map(
                        "severity", "blocking", "category", "test_density",
                        "title", "测试密度：测试文件占比 0%", "metric", "0%",
                        "impact", "改动没有自动化回归保护", "action", "为关键模块补充单元测试",
                        "recoverable_points", 20.0,
                        "evidence", List.of(map("path", "src/main/java/Demo.java", "line", 42))))));
        jdbc.update("INSERT INTO quality_code_snapshots(project_id,repo,branch,total,grade,risk_level,"
                + "payload_json,created_at) VALUES ('P001','aicap/demo','main',68,'待改进','high',?,NOW())", codePayload);

        String semanticPayload = json(map(
                "scope", "readme", "available", true, "score", 55, "grade", "待改进",
                "verdict", "缺少安装步骤", "reader_can_act", false, "unverified_quotes", 0,
                "issues", List.of(map("quote", "npm start", "problem", "未说明依赖安装", "verified", true)),
                "missing", List.of("运行命令")));
        jdbc.update("INSERT INTO quality_semantic_reviews(project_id,scope,source,score,grade,"
                + "reader_can_act,payload_json,content_sha,created_at) "
                + "VALUES ('P001','readme','README.md',55,'待改进',0,?,?,NOW())", semanticPayload, "sha-readme");

        ApiResponse analyzed = get("/api/projects/P001/quality-analysis", token(USER_VIEWER));
        assertStatus(analyzed, 200);
        // 缓存为空时应回放最近一次留档结论,重启后仍可审计。
        assertTrue(analyzed.json().path("code_quality").path("persisted").asBoolean(false),
                "应回放落库的代码质量结论 -> " + analyzed.body());
        assertEquals(55, analyzed.json().path("semantic_reviews").path("readme").path("score").asInt(),
                "应回放落库的语义评审 -> " + analyzed.body());

        JsonNode codeGap = null;
        JsonNode semanticGap = null;
        for (JsonNode finding : analyzed.json().path("findings")) {
            if ("code_quality_dimension_gap".equals(finding.path("kind").asText())) codeGap = finding;
            if ("readme_not_actionable".equals(finding.path("kind").asText())) semanticGap = finding;
        }
        assertNotNull(codeGap, "代码质量维度应转成可确认缺口 -> " + analyzed.body());
        assertEquals("CQ:test_density", codeGap.path("source_code").asText());
        assertEquals("测试质量", codeGap.path("category").asText());
        assertTrue(codeGap.path("location").asText().contains("仓库 aicap/demo"),
                "缺口应带仓库定位 -> " + codeGap);
        assertTrue(codeGap.path("suggestion").asText().contains("补充单元测试"),
                "缺口应带修复建议 -> " + codeGap);

        assertNotNull(semanticGap, "语义评审结论应转成可确认缺口 -> " + analyzed.body());
        assertEquals("SEM:readme", semanticGap.path("source_code").asText());
        assertEquals("文档质量", semanticGap.path("category").asText());
        assertTrue(semanticGap.path("detail").asText().contains("读者无法照做"), "应说明不可照做 -> " + semanticGap);

        // 人工确认两条新缺口,均生成可追踪任务,重复确认幂等。
        String codeBody = json(map("finding_kind", "code_quality_dimension_gap",
                "owner_id", 1, "hours", 3, "week_start", 5, "week_end", 5));
        ApiResponse codeConfirmed = post("/api/projects/P001/quality-analysis/CQ:test_density/improvements",
                token(USER_OWNER), codeBody);
        assertStatus(codeConfirmed, 200);
        String codeTask = codeConfirmed.json().path("improvement_task_code").asText("");
        assertTrue(codeTask.startsWith("T"), "应创建改进任务 -> " + codeConfirmed.body());
        ApiResponse codeAgain = post("/api/projects/P001/quality-analysis/CQ:test_density/improvements",
                token(USER_OWNER), codeBody);
        assertStatus(codeAgain, 200);
        assertEquals(codeTask, codeAgain.json().path("improvement_task_code").asText(), "同缺口重复确认应幂等");

        String semanticBody = json(map("finding_kind", "readme_not_actionable",
                "owner_id", 1, "hours", 2, "week_start", 5, "week_end", 5));
        ApiResponse semanticConfirmed = post("/api/projects/P001/quality-analysis/SEM:readme/improvements",
                token(USER_OWNER), semanticBody);
        assertStatus(semanticConfirmed, 200);
        assertTrue(semanticConfirmed.json().path("improvement_task_code").asText("").startsWith("T"),
                "应创建改进任务 -> " + semanticConfirmed.body());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quality_improvements "
                + "WHERE project_id='P001' AND source_code='SEM:readme'", Integer.class));
    }

    @Test
    void invalidTestSetInput_returns422() {
        ApiResponse emptyCases = post("/api/projects/P001/quality-analysis/test-sets", token(USER_OWNER),
                json(map("name", "空测试集", "source", "test_case", "cases", List.of())));
        assertStatus(emptyCases, 422);
        ApiResponse badSource = post("/api/projects/P001/quality-analysis/test-sets", token(USER_OWNER),
                json(map("name", "来源无效", "source", "unknown",
                        "cases", List.of(map("name", "用例一")))));
        assertStatus(badSource, 422);
        ApiResponse badKind = post("/api/projects/P001/quality-analysis/test-sets", token(USER_OWNER),
                json(map("name", "类别无效", "source", "test_report",
                        "cases", List.of(map("name", "用例一", "boundary_kind", "not_a_kind")))));
        assertStatus(badKind, 422);
    }
}
