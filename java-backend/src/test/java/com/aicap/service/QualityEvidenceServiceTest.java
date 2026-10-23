package com.aicap.service;

import com.aicap.profile.GitHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QualityEvidenceServiceTest {
    private JdbcTemplate jdbc;
    private QualityEvidenceService service;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE activity_records(project_id varchar(20),user_id int,activity_type varchar(20),"
                + "title varchar(200),module varchar(100),source varchar(20),happened_at timestamp)");
        service = new QualityEvidenceService(new GitHubProperties(), jdbc);
    }

    private void activity(String project, String type, String title, String module, String happenedAt) {
        jdbc.update("INSERT INTO activity_records VALUES (?,?,?,?,?,?,?)",
                project, 1, type, title, module, "github", happenedAt + " 12:00:00");
    }

    @Test void codeActivityCountsRealSyncedEventsOnly() {
        activity("P001", "commit", "add login", "前端", "2026-10-20");
        activity("P001", "commit", "add unit test", "测试", "2026-10-21");
        activity("P001", "commit", "update docs", "文档", "2026-09-01");
        activity("P001", "pr", "合并登录", "", "2026-10-21");
        activity("P001", "review", "评审登录", "", "2026-10-21");
        activity("P001", "test", "跑通契约", "", "2026-10-22");
        activity("P001", "commit", "旧提交", "", "2026-09-01");
        activity("P002", "commit", "别的项目", "", "2026-10-21");
        var m = service.codeActivity("P001");
        assertTrue((Boolean) m.get("synced"));
        assertEquals(4, m.get("commits_total"));
        assertEquals(2, m.get("commits_last_14d"));
        assertEquals(1, m.get("test_commits"));
        assertEquals(1, m.get("doc_commits"));
        assertEquals(1, m.get("prs_total"));
        assertEquals(1, m.get("reviews_total"));
        assertEquals(1, m.get("test_activities"));
    }

    @Test void unconfiguredGithubDegradesGracefully() {
        var ci = service.buildCiEvidence();
        assertFalse((Boolean) ci.get("available"));
        assertNotNull(ci.get("reason"));
        var repo = service.buildRepositoryEvidence();
        assertFalse((Boolean) repo.get("available"));
    }

    @Test void parseRunsSkipsPagesAndReportsPassRate() throws Exception {
        String body = "{\"workflow_runs\":["
                + "{\"name\":\"CI\",\"status\":\"completed\",\"conclusion\":\"failure\",\"run_number\":21,"
                + "\"html_url\":\"https://x\",\"created_at\":\"2026-10-23T13:08:07Z\",\"head_sha\":\"abcdef12345\"},"
                + "{\"name\":\"pages build and deployment\",\"status\":\"completed\",\"conclusion\":\"success\",\"run_number\":65,"
                + "\"html_url\":\"https://x\",\"created_at\":\"2026-10-23T13:08:07Z\",\"head_sha\":\"abcdef12345\"},"
                + "{\"name\":\"CI\",\"status\":\"completed\",\"conclusion\":\"success\",\"run_number\":20,"
                + "\"html_url\":\"https://x\",\"created_at\":\"2026-10-22\",\"head_sha\":\"12345678901\"}"
                + "]}";
        var m = QualityEvidenceService.parseRuns(body);
        assertEquals(2, m.get("runs_total"));
        assertEquals("failure", m.get("last_conclusion"));
        assertEquals(50.0, m.get("pass_rate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> lastFailed = (Map<String, Object>) m.get("last_failed");
        assertEquals(21, lastFailed.get("run_number"));
        assertEquals("abcdef1", lastFailed.get("head_sha"));
    }

    @Test void scanTreeDetectsCiTestsAndDocs() throws Exception {
        String body = "{\"truncated\":false,\"tree\":["
                + "{\"path\":\".github/workflows/ci.yml\"},"
                + "{\"path\":\"java-backend/src/test/java/com/aicap/FooTest.java\"},"
                + "{\"path\":\"frontend/e2e-status/Bar.spec.js\"},"
                + "{\"path\":\"docs/README.md\"},"
                + "{\"path\":\"src/main/java/App.java\"}"
                + "]}";
        var m = QualityEvidenceService.scanTree(body);
        assertEquals(true, m.get("has_ci_workflow"));
        assertEquals(true, m.get("has_tests"));
        assertEquals(true, m.get("has_docs"));
        assertEquals(2, m.get("test_file_count"));
        assertEquals(false, m.get("truncated"));
    }

    @SuppressWarnings("unchecked")
    @Test void parseFailedTestsExtractsClassNameAndSummary() {
        String log = "[ERROR] Tests run: 842, Failures: 0, Errors: 19, Skipped: 0\n"
                + "[ERROR] com.aicap.contract.ReviewQueueIndexContractTest.cursorUsesBinaryOrderForMixedCaseIdsAtTheSameTimestamp -- Time elapsed: 0.028 s <<< ERROR!\n"
                + "[ERROR] com.aicap.contract.PlatformAgentContractTest.plat02_catalog_readableByEveryRoleIncludingViewer -- Time elapsed: 0.145 s <<< ERROR!\n"
                + "[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in com.aicap.rag.RrfFuserTest\n";
        var m = QualityEvidenceService.parseFailedTests(log);
        assertEquals(842, m.get("tests_run"));
        assertEquals(19, m.get("errors"));
        assertEquals(2, m.get("failed_tests_total"));
        var tests = (List<String>) m.get("failed_tests");
        assertTrue(tests.contains("com.aicap.contract.ReviewQueueIndexContractTest.cursorUsesBinaryOrderForMixedCaseIdsAtTheSameTimestamp"));
        assertTrue(tests.contains("com.aicap.contract.PlatformAgentContractTest.plat02_catalog_readableByEveryRoleIncludingViewer"));
    }

    @Test void parseJacocoReadsTotalRow() {
        String log = "random log line\n--- JACOCO_CSV ---\n"
                + "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n"
                + "aicap,com.aicap,com.aicap.App,100,900,10,40,20,80,0,0,0,0\n"
                + "aicap,com.aicap.contract,com.aicap.contract.FooTest,200,700,50,100,40,60,0,0,0,0\n"
                + "--- JACOCO_END ---\n";
        var m = QualityEvidenceService.parseJacoco(log);
        assertNotNull(m);
        assertEquals(300L, m.get("instruction_missed"));
        assertEquals(1600L, m.get("instruction_covered"));
        assertEquals(84.2, m.get("instruction_pct"));
        assertEquals(60L, m.get("line_missed"));
        assertEquals(140L, m.get("line_covered"));
        assertEquals(70.0, m.get("line_pct"));
    }

    @Test void parseJacocoReturnsNullWhenNoBlock() {
        assertNull(QualityEvidenceService.parseJacoco("no jacoco here"));
    }

    @SuppressWarnings("unchecked")
    @Test void collectMergesAllThreeSections() {
        activity("P001", "commit", "add login", "前端", "2026-10-20");
        var evidence = service.collect("P001");
        assertTrue(evidence.containsKey("code_activity"));
        assertTrue(evidence.containsKey("ci"));
        assertTrue(evidence.containsKey("repository"));
        assertFalse((Boolean) ((Map<String, Object>) evidence.get("ci")).get("available"));
        assertEquals(List.of("code_activity", "ci", "repository"), List.copyOf(evidence.keySet()));
    }
}
