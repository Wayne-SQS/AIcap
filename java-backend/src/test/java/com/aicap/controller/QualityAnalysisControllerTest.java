package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.common.GlobalExceptionHandler;
import com.aicap.dto.TaskDtos;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.security.AuthContext;
import com.aicap.service.ProjectAccessService;
import com.aicap.service.QualityContentService;
import com.aicap.service.QualityEvidenceService;
import com.aicap.service.QualityTestSetService;
import com.aicap.service.RepoQualityFetcher;
import com.aicap.service.SemanticReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class QualityAnalysisControllerTest {
    private JdbcTemplate jdbc;
    private QualityAnalysisController controller;
    private ProjectAccessService projects;
    private TaskPoolController taskController;
    private QualityEvidenceService evidence;
    private QualityTestSetService testSets;
    private RepoQualityFetcher codeQualityFetcher;
    private SemanticReviewService semanticReviewer;
    private QualityContentService content;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE stories(project_id varchar(20),code varchar(20),title varchar(200),"
                + "acceptance text,owner_id int)");
        jdbc.execute("CREATE TABLE tasks(project_id varchar(20),code varchar(20),name varchar(200),"
                + "task_type varchar(10),story_internal_id bigint,status int,progress int)");
        jdbc.execute("CREATE TABLE quality_improvements(project_id varchar(20),source_code varchar(20),"
                + "finding_kind varchar(30),improvement_task_code varchar(20),reviewed_by int,reviewed_at timestamp,"
                + "primary key(project_id,source_code,finding_kind))");
        jdbc.execute("CREATE TABLE quality_test_sets(id bigint NOT NULL AUTO_INCREMENT,project_id varchar(20) NOT NULL,"
                + "code varchar(20) NOT NULL,name varchar(200) NOT NULL,source varchar(20) NOT NULL,"
                + "cases_json varchar(10000) NOT NULL,created_by int NOT NULL,created_at timestamp NOT NULL,"
                + "primary key(id),unique(project_id,code))");
        jdbc.execute("CREATE TABLE quality_code_snapshots(id bigint NOT NULL AUTO_INCREMENT,"
                + "project_id varchar(20) NOT NULL,repo varchar(200) NOT NULL,branch varchar(100) NOT NULL,"
                + "total int NOT NULL,grade varchar(10) NOT NULL,risk_level varchar(20) NOT NULL,"
                + "payload_json varchar(100000) NOT NULL,created_at timestamp NOT NULL,primary key(id))");
        jdbc.execute("CREATE TABLE quality_semantic_reviews(id bigint NOT NULL AUTO_INCREMENT,"
                + "project_id varchar(20) NOT NULL,scope varchar(20) NOT NULL,source varchar(300) NULL,"
                + "score int NULL,grade varchar(10) NULL,reader_can_act int NOT NULL DEFAULT 0,"
                + "payload_json varchar(100000) NOT NULL,content_sha varchar(64) NULL,"
                + "created_at timestamp NOT NULL,primary key(id))");
        projects = mock(ProjectAccessService.class);
        taskController = mock(TaskPoolController.class);
        evidence = mock(QualityEvidenceService.class);
        testSets = new QualityTestSetService(jdbc);
        codeQualityFetcher = mock(RepoQualityFetcher.class);
        semanticReviewer = mock(SemanticReviewService.class);
        content = new QualityContentService(jdbc);
        when(evidence.collect(anyString())).thenReturn(new java.util.LinkedHashMap<>());
        controller = new QualityAnalysisController(projects, jdbc, taskController, evidence,
                testSets, codeQualityFetcher, semanticReviewer, content);
    }

    @AfterEach void clearAuth() { AuthContext.clear(); }

    private void story(String project, String code, String acceptance, Integer owner) {
        jdbc.update("INSERT INTO stories VALUES (?,?,?,?,?)", project, code, code, acceptance, owner);
    }

    private void task(String project, String code, String type, Long storyId, int status) {
        jdbc.update("INSERT INTO tasks VALUES (?,?,?,?,?,?,?)", project, code, code, type, storyId, status, 0);
    }

    private void asOwner(int id) {
        var user = new User(); user.setId(id); user.setRole("owner"); AuthContext.set(user);
    }

    private void improvementTask(String code) {
        when(taskController.createTask(any(), eq("P001"))).thenAnswer(invocation -> {
            var body = (com.fasterxml.jackson.databind.JsonNode) invocation.getArgument(0);
            String name = body.path("name").asText("改进任务");
            jdbc.update("INSERT INTO tasks VALUES (?,?,?,?,?,?,?)", "P001", code, name, "management", null, 0, 0);
            var created = new Task(); created.setId(code); created.setName(name);
            return TaskDtos.toOut(created);
        });
    }

    @SuppressWarnings("unchecked")
    @Test void reportsOnlyEvidenceBackedFindingsInTheRequestedProject() {
        story("P001", "US1", "", null);          // 缺验收 + 未认领 → 2 条
        story("P001", "US2", "给定账号可登录 ", 7); // 规范 → 0 条
        story("P001", "US3", null, 7);           // 只缺验收 → 1 条
        story("P002", "US9", null, null);        // 跨项目 → 不计入
        task("P001", "T1", "feature", null, 0);  // 未关联故事 → 1 条
        task("P001", "T2", "management", null, 0); // 管理任务无需故事 → 0 条
        task("P001", "T3", "feature", 11L, 0);   // 已关联 → 0 条
        task("P001", "T4", "feature", null, 2);  // 已完成不参与 → 0 条
        task("P002", "T9", "feature", null, 0);  // 跨项目 → 不计入
        var result = controller.calculate("P001");
        var payload = result.payload();
        assertEquals(3, payload.get("stories_total"));
        assertEquals(3, payload.get("open_tasks"));
        var findings = result.findings();
        assertEquals(4, findings.size());
        assertEquals("story_missing_acceptance", findings.get(0).get("kind"));
        assertEquals("需求规范", findings.get(0).get("category"));
        assertEquals("故事 US1 · US1", findings.get(0).get("location"));
        assertEquals("US1", findings.get(0).get("source_code"));
        assertEquals("story_missing_owner", findings.get(1).get("kind"));
        assertEquals("story_missing_acceptance", findings.get(2).get("kind"));
        assertEquals("US3", findings.get(2).get("source_code"));
        assertEquals("task_without_story", findings.get(3).get("kind"));
        assertEquals("T1", findings.get(3).get("source_code"));
        assertTrue(payload.get("limitations").toString().contains("不虚构覆盖率"));
    }

    @SuppressWarnings("unchecked")
    @Test void completedOrCancelledStoriesAndTasksLeaveTheScope() {
        story("P001", "US1", null, null);
        jdbc.update("UPDATE stories SET owner_id=7 WHERE code='US1'"); // 只剩缺验收
        task("P001", "T1", "feature", null, 3); // 已取消
        var findings = controller.calculate("P001").findings();
        assertEquals(1, findings.size());
        assertEquals("story_missing_acceptance", findings.get(0).get("kind"));
    }

    @Test void endpointRequiresLoginAndProjectMembership() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        assertEquals(401, mvc.perform(get("/api/projects/P001/quality-analysis"))
                .andReturn().getResponse().getStatus());
        var user = new User(); user.setId(1); user.setRole("viewer"); AuthContext.set(user);
        assertEquals(200, mvc.perform(get("/api/projects/P001/quality-analysis"))
                .andReturn().getResponse().getStatus());
        doThrow(ApiException.forbidden("无权访问该项目")).when(projects).requireMember("P002");
        assertEquals(403, mvc.perform(get("/api/projects/P002/quality-analysis"))
                .andReturn().getResponse().getStatus());
    }

    @SuppressWarnings("unchecked")
    @Test void confirmedFindingCreatesOneTraceableTaskAndReadsItsOutcome() {
        story("P001", "US1", null, null);
        asOwner(7);
        improvementTask("T99");
        var request = new QualityAnalysisController.ImprovementRequest("story_missing_acceptance", 7, 2, 5, 5);
        var first = controller.createImprovement("P001", "US1", request);
        var again = controller.createImprovement("P001", "US1", request);
        assertEquals("T99", first.get("improvement_task_code"));
        assertEquals("T99", again.get("improvement_task_code"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quality_improvements", Integer.class));
        jdbc.update("UPDATE tasks SET status=2,progress=100 WHERE project_id='P001' AND code='T99'");
        var records = (List<Map<String, Object>>) controller.calculate("P001").payload().get("improvements");
        assertEquals(2, records.get(0).get("improvement_status"));
        assertEquals(100, records.get(0).get("improvement_progress"));
    }

    @Test void staleOrCrossProjectFindingCannotCreateAnImprovement() {
        story("P001", "US1", "已有验收", 7); // 该故事不再缺验收
        asOwner(7);
        var request = new QualityAnalysisController.ImprovementRequest("story_missing_acceptance", 7, 2, 5, 5);
        assertEquals(409, assertThrows(ApiException.class,
                () -> controller.createImprovement("P001", "US1", request)).getStatus());
        assertEquals(404, assertThrows(ApiException.class,
                () -> controller.createImprovement("P002", "US1", request)).getStatus());
        assertEquals(422, assertThrows(ApiException.class,
                () -> controller.createImprovement("P001", "US1",
                        new QualityAnalysisController.ImprovementRequest("code_coverage", 7, 2, 5, 5))).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quality_improvements", Integer.class));
    }

    @Test void deletedImprovementTaskCanBeReconfirmed() {
        story("P001", "US1", null, null);
        asOwner(7);
        improvementTask("T99");
        var request = new QualityAnalysisController.ImprovementRequest("story_missing_acceptance", 7, 2, 5, 5);
        assertEquals("T99", controller.createImprovement("P001", "US1", request).get("improvement_task_code"));

        // 改进任务被删后,同缺口应能重新确认建新任务,而不是返回指向已删任务的孤儿记录。
        jdbc.update("DELETE FROM tasks WHERE project_id='P001' AND code='T99'");
        improvementTask("T98");
        assertEquals("T98", controller.createImprovement("P001", "US1", request).get("improvement_task_code"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quality_improvements WHERE source_code='US1'", Integer.class));
    }

    @SuppressWarnings("unchecked")
    @Test void submittedTestSetLocatesMissingBoundaryCasesAndCanCreateTraceableTask() {
        asOwner(7);
        var created = testSets.create("P001", 7, new QualityTestSetService.CreateRequest(
                "登录边界测试集", "test_case", List.of(
                new QualityTestSetService.CaseIn("用户名为空时登录失败", null),
                new QualityTestSetService.CaseIn("无权限用户访问返回 403", null))));
        assertEquals("TS1", created.get("code"));
        assertEquals(2, created.get("total"));
        assertEquals(List.of("empty_value", "permission"), created.get("covered_boundaries"));
        assertEquals(6, ((List<?>) created.get("missing_boundaries")).size());

        var result = controller.calculate("P001");
        var gap = result.findings().stream()
                .filter(f -> QualityTestSetService.FINDING_KIND.equals(f.get("kind"))).findFirst().orElseThrow();
        assertEquals("测试质量", gap.get("category"));
        assertEquals("TS1", gap.get("source_code"));
        assertTrue(String.valueOf(gap.get("location")).contains("测试集 TS1"));
        assertTrue(String.valueOf(gap.get("detail")).contains("2/8"));
        assertTrue(String.valueOf(gap.get("suggestion")).contains("边界用例"));

        var quality = (Map<String, Object>) result.payload().get("test_quality");
        assertEquals(1, quality.get("sets_total"));
        assertEquals(2, quality.get("cases_total"));
        assertEquals(8, quality.get("boundaries_total"));
        assertEquals("仅按用例名称/显式边界类别判断覆盖，不执行测试，也不推断测试代码质量。", quality.get("note"));

        improvementTask("T77");
        var saved = controller.createImprovement("P001", "TS1",
                new QualityAnalysisController.ImprovementRequest(QualityTestSetService.FINDING_KIND, 7, 3, 5, 5));
        assertEquals("T77", saved.get("improvement_task_code"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM quality_improvements WHERE source_code='TS1'", Integer.class));
    }

    @Test void fullyCoveredTestSetLeavesNoGapAndCannotBeConfirmed() {
        asOwner(7);
        var all = QualityTestSetService.BOUNDARIES.keySet().stream()
                .map(kind -> new QualityTestSetService.CaseIn("用例-" + kind, kind)).toList();
        var created = testSets.create("P001", 7,
                new QualityTestSetService.CreateRequest("全覆盖测试集", "test_code", all));
        assertEquals(0, ((List<?>) created.get("missing_boundaries")).size());
        assertTrue(controller.calculate("P001").findings().isEmpty());
        assertEquals(409, assertThrows(ApiException.class, () -> controller.createImprovement("P001", "TS1",
                new QualityAnalysisController.ImprovementRequest(QualityTestSetService.FINDING_KIND, 7, 2, 5, 5)))
                .getStatus());
    }

    @Test void invalidOrUnknownTestSetCannotBeConfirmed() {
        asOwner(7);
        var request = new QualityAnalysisController.ImprovementRequest(QualityTestSetService.FINDING_KIND, 7, 2, 5, 5);
        assertEquals(404, assertThrows(ApiException.class,
                () -> controller.createImprovement("P001", "TS9", request)).getStatus());
        assertEquals(422, assertThrows(ApiException.class,
                () -> testSets.create("P001", 7, new QualityTestSetService.CreateRequest(
                        "", "test_case", List.of(new QualityTestSetService.CaseIn("空名", null))))).getStatus());
        assertEquals(422, assertThrows(ApiException.class,
                () -> testSets.create("P001", 7, new QualityTestSetService.CreateRequest(
                        "来源无效", "unknown", List.of(new QualityTestSetService.CaseIn("用例", null))))).getStatus());
        assertEquals(422, assertThrows(ApiException.class,
                () -> testSets.create("P001", 7, new QualityTestSetService.CreateRequest(
                        "空用例", "test_case", List.of()))).getStatus());
        assertEquals(422, assertThrows(ApiException.class,
                () -> testSets.create("P001", 7, new QualityTestSetService.CreateRequest(
                        "类别无效", "test_report", List.of(new QualityTestSetService.CaseIn("用例", "not_a_kind")))))
                .getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quality_test_sets", Integer.class));
    }

    @SuppressWarnings("unchecked")
    @Test void externalEvidenceIsTurnedIntoLocatedFindingsAndCanBeConfirmed() {
        var repo = new LinkedHashMap<String, Object>();
        repo.put("available", true);
        repo.put("repo", "aicap/demo");
        repo.put("default_branch", "main");
        repo.put("has_tests", false);
        repo.put("has_docs", false);
        repo.put("has_ci_workflow", false);
        var ci = new LinkedHashMap<String, Object>();
        ci.put("available", true);
        ci.put("repo", "aicap/demo");
        ci.put("last_conclusion", "failure");
        ci.put("last_failed", Map.of("run_number", 42, "head_sha", "abc1234"));
        ci.put("failed_tests", Map.of("available", true, "failed_tests_total", 2, "run_number", 41,
                "failed_tests", List.of("LoginTest.empty", "TaskTest.duplicate")));
        ci.put("coverage", Map.of("available", true, "line_pct", 41.5, "instruction_pct", 55.0));
        var ev = new LinkedHashMap<String, Object>();
        ev.put("code_activity", Map.of("synced", true, "prs_total", 4, "reviews_total", 0));
        ev.put("ci", ci);
        ev.put("repository", repo);
        when(evidence.collect(anyString())).thenReturn(ev);

        var findings = controller.calculate("P001", ev).findings();
        var kinds = findings.stream().map(f -> String.valueOf(f.get("kind"))).toList();
        assertTrue(kinds.containsAll(List.of("code_missing_reviews", "ci_pipeline_failing",
                "failed_tests_unresolved", "coverage_below_threshold", "repository_missing_tests",
                "repository_missing_docs", "repository_missing_ci")));
        for (var finding : findings) {
            assertFalse(String.valueOf(finding.get("category")).isBlank());
            assertFalse(String.valueOf(finding.get("location")).isBlank());
            assertFalse(String.valueOf(finding.get("suggestion")).isBlank());
        }
        var coverage = findings.stream().filter(f -> "coverage_below_threshold".equals(f.get("kind")))
                .findFirst().orElseThrow();
        assertEquals("测试质量", coverage.get("category"));
        assertTrue(String.valueOf(coverage.get("detail")).contains("41.5%"));
        var failed = findings.stream().filter(f -> "failed_tests_unresolved".equals(f.get("kind")))
                .findFirst().orElseThrow();
        assertTrue(String.valueOf(failed.get("location")).contains("run #41"));
        assertTrue(String.valueOf(failed.get("detail")).contains("LoginTest.empty"));

        asOwner(7);
        improvementTask("T88");
        var saved = controller.createImprovement("P001", "CI",
                new QualityAnalysisController.ImprovementRequest("ci_pipeline_failing", 7, 4, 5, 5));
        assertEquals("T88", saved.get("improvement_task_code"));
        var taskName = jdbc.queryForObject("SELECT name FROM tasks WHERE project_id='P001' AND code='T88'", String.class);
        assertEquals("改进：修复 CI 流水线 · CI", taskName);
    }

    @Test void unknownEvidenceDoesNotFabricateFindings() {
        var repo = new LinkedHashMap<String, Object>();
        repo.put("available", true);
        repo.put("repo", "aicap/demo");
        repo.put("default_branch", "main");
        repo.put("has_tests", null); // 截断/未知:不推断
        repo.put("has_docs", null);
        repo.put("has_ci_workflow", null);
        var ci = new LinkedHashMap<String, Object>();
        ci.put("available", false);
        ci.put("reason", "未配置 GitHub Token");
        var ev = new LinkedHashMap<String, Object>();
        ev.put("code_activity", Map.of("synced", false));
        ev.put("ci", ci);
        ev.put("repository", repo);

        var result = controller.calculate("P001", ev);
        assertTrue(result.findings().isEmpty(), "未知证据不应被推断成缺口");
        assertFalse(String.valueOf(result.payload().get("limitations")).contains("已接入 CI"));
    }

    private Map<String, Object> codeQualityPayload(boolean sufficient) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("available", true);
        payload.put("repo", "aicap/demo");
        payload.put("branch", "main");
        payload.put("sufficient", sufficient);
        payload.put("total", 68);
        payload.put("grade", "待改进");
        payload.put("risk_level", "high");
        payload.put("dimensions", List.of(
                Map.of("key", "test_density", "name", "测试密度"),
                Map.of("key", "naming", "name", "命名规范")));
        payload.put("findings", List.of(
                Map.of("severity", "blocking", "category", "test_density",
                        "title", "测试密度：测试文件占比 0%", "metric", "0%",
                        "impact", "改动没有自动化回归保护", "action", "为关键模块补充单元测试",
                        "recoverable_points", 20.0,
                        "evidence", List.of(Map.of("path", "src/main/java/Demo.java", "line", 42,
                                "snippet", "public void run()"))),
                // low 只留页面展示,不该占用人工确认队列
                Map.of("severity", "low", "category", "naming",
                        "title", "命名规范：少量缩写", "metric", "2 处",
                        "impact", "可读性", "action", "重命名变量",
                        "recoverable_points", 1.0, "evidence", List.of())));
        return payload;
    }

    @SuppressWarnings("unchecked")
    @Test void codeQualitySnapshotBecomesLocatedFindingAndCanBeConfirmed() {
        asOwner(7);
        when(codeQualityFetcher.evaluate("P001")).thenReturn(codeQualityPayload(true));
        controller.evaluateCodeQuality("P001");

        var snapshot = content.latestCodeSnapshot("P001");
        assertNotNull(snapshot);
        assertEquals(68, snapshot.get("total"));
        assertEquals(true, snapshot.get("persisted"));

        var findings = controller.calculate("P001").findings();
        assertEquals(1, findings.size(), "只把 blocking/high/medium 维度转成可确认缺口");
        var gap = findings.get(0);
        assertEquals(QualityContentService.CODE_KIND, gap.get("kind"));
        assertEquals("测试质量", gap.get("category"));
        assertEquals("CQ:test_density", gap.get("source_code"));
        assertTrue(String.valueOf(gap.get("location")).contains("仓库 aicap/demo（main）"));
        assertTrue(String.valueOf(gap.get("location")).contains("测试密度"));
        assertTrue(String.valueOf(gap.get("detail")).contains("src/main/java/Demo.java:42"));
        assertTrue(String.valueOf(gap.get("suggestion")).contains("补充单元测试"));
        assertTrue(String.valueOf(gap.get("suggestion")).contains("20 分"));

        var analysis = controller.analyze("P001");
        var cq = (Map<String, Object>) analysis.get("code_quality");
        assertEquals(true, cq.get("persisted"), "缓存过期后仍应回放最近一次留档结论");

        improvementTask("T66");
        var saved = controller.createImprovement("P001", "CQ:test_density",
                new QualityAnalysisController.ImprovementRequest(QualityContentService.CODE_KIND, 7, 3, 5, 5));
        assertEquals("T66", saved.get("improvement_task_code"));
        assertEquals("改进：修复代码质量维度 · CQ:test_density", jdbc.queryForObject(
                "SELECT name FROM tasks WHERE project_id='P001' AND code='T66'", String.class));
    }

    @Test void insufficientCodeQualitySampleIsNeitherPersistedNorReported() {
        asOwner(7);
        when(codeQualityFetcher.evaluate("P001")).thenReturn(codeQualityPayload(false));
        controller.evaluateCodeQuality("P001");
        assertNull(content.latestCodeSnapshot("P001"));
        assertTrue(controller.calculate("P001").findings().isEmpty());
        assertEquals(409, assertThrows(ApiException.class, () -> controller.createImprovement("P001",
                "CQ:test_density", new QualityAnalysisController.ImprovementRequest(
                        QualityContentService.CODE_KIND, 7, 3, 5, 5))).getStatus());
    }

    @SuppressWarnings("unchecked")
    @Test void semanticReviewIsPersistedAndBecomesActionableFinding() {
        asOwner(7);
        var review = new LinkedHashMap<String, Object>();
        review.put("scope", "readme");
        review.put("available", true);
        review.put("score", 55);
        review.put("grade", "待改进");
        review.put("verdict", "缺少安装步骤");
        review.put("reader_can_act", false);
        review.put("unverified_quotes", 1);
        review.put("issues", List.of(Map.of("quote", "npm start", "problem", "未说明依赖安装", "verified", true)));
        review.put("missing", List.of("运行命令"));
        when(semanticReviewer.review(eq("readme"), anyString(), any())).thenReturn(review);

        var body = new LinkedHashMap<String, String>();
        body.put("scope", "readme");
        body.put("text", "# Demo");
        body.put("context", "仓库 aicap/demo");
        assertEquals(55, controller.reviewSemantics("P001", body).get("score"));
        assertNotNull(jdbc.queryForObject("SELECT content_sha FROM quality_semantic_reviews", String.class));

        var findings = controller.calculate("P001").findings();
        assertEquals(1, findings.size());
        var gap = findings.get(0);
        assertEquals(QualityContentService.README_KIND, gap.get("kind"));
        assertEquals("文档质量", gap.get("category"));
        assertEquals("SEM:readme", gap.get("source_code"));
        assertTrue(String.valueOf(gap.get("detail")).contains("读者无法照做"));
        assertTrue(String.valueOf(gap.get("detail")).contains("1 条未能在原文核实"));
        assertTrue(String.valueOf(gap.get("suggestion")).contains("运行命令"));

        assertEquals(1, ((Map<?, ?>) controller.analyze("P001").get("semantic_reviews")).size());

        improvementTask("T55");
        var saved = controller.createImprovement("P001", "SEM:readme",
                new QualityAnalysisController.ImprovementRequest(QualityContentService.README_KIND, 7, 2, 5, 5));
        assertEquals("T55", saved.get("improvement_task_code"));
        assertEquals("改进：提升 README 可操作性 · SEM:readme", jdbc.queryForObject(
                "SELECT name FROM tasks WHERE project_id='P001' AND code='T55'", String.class));
    }

    @Test void actionableSemanticReviewLeavesNoGap() {
        asOwner(7);
        var review = new LinkedHashMap<String, Object>();
        review.put("scope", "tests");
        review.put("available", true);
        review.put("score", 88);
        review.put("grade", "良");
        review.put("verdict", "断言清晰，可按说明执行");
        review.put("reader_can_act", true);
        review.put("unverified_quotes", 0);
        review.put("issues", List.of());
        review.put("missing", List.of());
        when(semanticReviewer.review(eq("tests"), anyString(), any())).thenReturn(review);

        var body = new LinkedHashMap<String, String>();
        body.put("scope", "tests");
        body.put("text", "@Test void works() {}");
        controller.reviewSemantics("P001", body);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quality_semantic_reviews", Integer.class));
        assertTrue(controller.calculate("P001").findings().isEmpty());
    }
}
