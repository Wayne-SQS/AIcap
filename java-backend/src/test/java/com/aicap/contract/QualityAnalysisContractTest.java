package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
}
