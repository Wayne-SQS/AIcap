package com.aicap.service;

import com.aicap.profile.GitHubProperties;
import com.aicap.profile.ProjectRepositoryResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class QualityEvidenceServiceTest {
    private JdbcTemplate jdbc;
    private QualityEvidenceService service;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE activity_records(project_id varchar(20),user_id int,activity_type varchar(20),"
                + "title varchar(200),module varchar(100),source varchar(20),happened_at timestamp)");
        service = new QualityEvidenceService(new GitHubProperties(), jdbc, mock(ProjectRepositoryResolver.class));
    }

    private void activity(String project, String type, String title, String module, String happenedAt) {
        jdbc.update("INSERT INTO activity_records VALUES (?,?,?,?,?,?,?)",
                project, 1, type, title, module, "github", happenedAt + " 12:00:00");
    }

    @Test void codeActivityCountsRealSyncedEventsOnly() {
        activity("P001", "commit", "add login", "前端", "2026-10-20");
        activity("P001", "commit", "add unit test", "测试", "2026-10-21");
        activity("P001", "commit", "update docs", "文档", "2026-09-01");
        activity("P001", "commit", "补充登录测试用例", "", "2026-10-22");
        activity("P001", "commit", "更新接口说明文档", "", "2026-10-22");
        activity("P001", "pr", "合并登录", "", "2026-10-21");
        activity("P001", "review", "评审登录", "", "2026-10-21");
        activity("P001", "test", "跑通契约", "", "2026-10-22");
        activity("P001", "commit", "旧提交", "", "2026-09-01");
        activity("P002", "commit", "别的项目", "", "2026-10-21");
        var m = service.codeActivity("P001");
        assertTrue((Boolean) m.get("synced"));
        assertEquals(6, m.get("commits_total"));
        assertEquals(4, m.get("commits_last_14d"));
        assertEquals(2, m.get("test_commits"));   // "add unit test" + 中文"测试用例"
        assertEquals(2, m.get("doc_commits"));    // "update docs" + 中文"说明文档"
        assertEquals(1, m.get("prs_total"));
        assertEquals(1, m.get("reviews_total"));
        assertEquals(1, m.get("test_activities"));
    }

    @Test void unconfiguredGithubDegradesGracefully() {
        var ci = service.buildCiEvidence("P001");
        assertFalse((Boolean) ci.get("available"));
        assertNotNull(ci.get("reason"));
        var repo = service.buildRepositoryEvidence("P001");
        assertFalse((Boolean) repo.get("available"));
    }

    @Test void parseRunsSkipsPagesAndReportsPassRate() throws Exception {
        String body = "{\"workflow_runs\":["
                + "{\"name\":\"CI\",\"status\":\"completed\",\"conclusion\":\"cancelled\",\"run_number\":19,"
                + "\"html_url\":\"https://x\",\"created_at\":\"2026-10-21\",\"head_sha\":\"aaaaaaa1\"},"
                + "{\"name\":\"CI\",\"status\":\"completed\",\"conclusion\":\"failure\",\"run_number\":21,"
                + "\"html_url\":\"https://x\",\"created_at\":\"2026-10-23T13:08:07Z\",\"head_sha\":\"abcdef12345\"},"
                + "{\"name\":\"pages build and deployment\",\"status\":\"completed\",\"conclusion\":\"success\",\"run_number\":65,"
                + "\"html_url\":\"https://x\",\"created_at\":\"2026-10-23T13:08:07Z\",\"head_sha\":\"abcdef12345\"},"
                + "{\"name\":\"CI\",\"status\":\"completed\",\"conclusion\":\"success\",\"run_number\":20,"
                + "\"html_url\":\"https://x\",\"created_at\":\"2026-10-22\",\"head_sha\":\"12345678901\"}"
                + "]}";
        var m = QualityEvidenceService.parseRuns(body);
        assertEquals(2, m.get("runs_total"));
        assertEquals(1, m.get("runs_cancelled"));
        assertEquals("failure", m.get("last_conclusion"));
        assertEquals(50.0, m.get("pass_rate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> lastRun = (Map<String, Object>) m.get("last_run");
        assertEquals(19, lastRun.get("run_number"));
        assertEquals("cancelled", lastRun.get("conclusion"));
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
                + "{\"path\":\"src/main/java/App.java\"},"
                + "{\"path\":\"src/main/java/latest.java\"}"
                + "]}";
        var m = QualityEvidenceService.scanTree(body);
        assertEquals(true, m.get("has_ci_workflow"));
        assertEquals(true, m.get("has_tests"));
        assertEquals(true, m.get("has_docs"));
        assertEquals(2, m.get("test_file_count"));  // latest.java 不应被误判为测试文件
        assertEquals(false, m.get("truncated"));
    }

    @Test void truncatedTreeLeavesAbsentFileTypesUnknown() throws Exception {
        var m = QualityEvidenceService.scanTree("{\"truncated\":true,\"tree\":["
                + "{\"path\":\"java-backend/src/test/java/com/aicap/FooTest.java\"}]}");
        assertEquals(true, m.get("truncated"));
        assertEquals(true, m.get("has_tests"));
        assertEquals(1, m.get("test_file_count"));
        assertNull(m.get("has_ci_workflow"));
        assertNull(m.get("has_docs"));
    }

    @SuppressWarnings("unchecked")
    @Test void parseFailedTestsExtractsClassNameAndSummary() {
        // 真实日志里单类汇总在前、最终汇总在后;应取最后一个 [ERROR] Tests run:。
        String log = "[ERROR] Tests run: 3, Failures: 0, Errors: 3, Skipped: 0, Time elapsed: 0.1 s <<< FAILURE! -- in com.aicap.contract.ReviewQueueHydrationContractTest\n"
                + "[ERROR] com.aicap.contract.ReviewQueueIndexContractTest.cursorUsesBinaryOrderForMixedCaseIdsAtTheSameTimestamp -- Time elapsed: 0.028 s <<< ERROR!\n"
                + "[ERROR] com.aicap.contract.PlatformAgentContractTest.plat02_catalog_readableByEveryRoleIncludingViewer -- Time elapsed: 0.145 s <<< ERROR!\n"
                + "[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in com.aicap.rag.RrfFuserTest\n"
                + "[ERROR] Tests run: 842, Failures: 0, Errors: 19, Skipped: 0\n";
        var m = QualityEvidenceService.parseFailedTests(log);
        assertEquals(842, m.get("tests_run"));
        assertEquals(0, m.get("failures"));
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

    @Test void parseJacocoSkipsTheActionsScriptEchoBlock() {
        // 真实 GitHub Actions 日志会先回显整段脚本,里面含第一次 "--- JACOCO_CSV ---",
        // 若用 indexOf 取第一个标记会把空块当数据、恒返 null(P0-1)。
        String log = "##[group]Run echo \"--- JACOCO_CSV ---\"\n"
                + "[36;1mecho \"--- JACOCO_CSV ---\"[0m\n"
                + "[36;1mcat target/site/jacoco/jacoco.csv[0m\n"
                + "[36;1mecho \"--- JACOCO_END ---\"[0m\n"
                + "2026-10-23T13:09:04Z --- JACOCO_CSV ---\n"
                + "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n"
                + "aicap,com.aicap,com.aicap.App,100,900,10,40,20,80,0,0,0,0\n"
                + "--- JACOCO_END ---\n";
        var m = QualityEvidenceService.parseJacoco(log);
        assertNotNull(m);
        assertEquals(100L, m.get("instruction_missed"));
        assertEquals(900L, m.get("instruction_covered"));
        assertEquals(90.0, m.get("instruction_pct"));
        assertEquals(20L, m.get("line_missed"));
        assertEquals(80L, m.get("line_covered"));
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

    @Test void redirectedLogDownloadDropsAuthorizationHeader() throws Exception {
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/blob", exchange -> {
            boolean withAuth = exchange.getRequestHeaders().containsKey("Authorization");
            byte[] body = (withAuth ? "AUTH_LEAKED" : "OK").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(withAuth ? 401 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();
        var source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/logs", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + target.getAddress().getPort() + "/blob");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        source.start();
        try {
            var props = new GitHubProperties();
            props.setToken("secret-token");
            var svc = new QualityEvidenceService(props, jdbc, mock(ProjectRepositoryResolver.class));
            assertEquals("OK", svc.get("http://127.0.0.1:" + source.getAddress().getPort() + "/logs"));
        } finally {
            source.stop(0);
            target.stop(0);
        }
    }
}
