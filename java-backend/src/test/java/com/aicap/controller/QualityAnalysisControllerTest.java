package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.common.GlobalExceptionHandler;
import com.aicap.dto.TaskDtos;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.security.AuthContext;
import com.aicap.service.ProjectAccessService;
import com.aicap.service.QualityEvidenceService;
import com.aicap.service.RepoQualityFetcher;
import com.aicap.service.SemanticReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
    private RepoQualityFetcher codeQualityFetcher;
    private SemanticReviewService semanticReviewer;

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
        projects = mock(ProjectAccessService.class);
        taskController = mock(TaskPoolController.class);
        evidence = mock(QualityEvidenceService.class);
        codeQualityFetcher = mock(RepoQualityFetcher.class);
        semanticReviewer = mock(SemanticReviewService.class);
        when(evidence.collect(anyString())).thenReturn(new java.util.LinkedHashMap<>());
        controller = new QualityAnalysisController(projects, jdbc, taskController, evidence, codeQualityFetcher, semanticReviewer);
    }

    @AfterEach void clearAuth() { AuthContext.clear(); }

    private void story(String project, String code, String acceptance, Integer owner) {
        jdbc.update("INSERT INTO stories VALUES (?,?,?,?,?)", project, code, code, acceptance, owner);
    }

    private void task(String project, String code, String type, Long storyId, int status) {
        jdbc.update("INSERT INTO tasks VALUES (?,?,?,?,?,?,?)", project, code, code, type, storyId, status, 0);
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
        var user = new User(); user.setId(7); user.setRole("owner"); AuthContext.set(user);
        var newTask = new Task(); newTask.setId("T99"); newTask.setName("改进任务");
        when(taskController.createTask(any(), eq("P001"))).thenAnswer(invocation -> {
            jdbc.update("INSERT INTO tasks VALUES (?,?,?,?,?,?,?)", "P001", "T99", "改进任务", "management", null, 0, 0);
            return TaskDtos.toOut(newTask);
        });
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
        var user = new User(); user.setId(7); user.setRole("owner"); AuthContext.set(user);
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
        var user = new User(); user.setId(7); user.setRole("owner"); AuthContext.set(user);
        var t99 = new Task(); t99.setId("T99"); t99.setName("改进任务");
        when(taskController.createTask(any(), eq("P001"))).thenAnswer(invocation -> {
            jdbc.update("INSERT INTO tasks VALUES (?,?,?,?,?,?,?)", "P001", "T99", "改进任务", "management", null, 0, 0);
            return TaskDtos.toOut(t99);
        });
        var request = new QualityAnalysisController.ImprovementRequest("story_missing_acceptance", 7, 2, 5, 5);
        assertEquals("T99", controller.createImprovement("P001", "US1", request).get("improvement_task_code"));

        // 改进任务被删后,同缺口应能重新确认建新任务,而不是返回指向已删任务的孤儿记录。
        jdbc.update("DELETE FROM tasks WHERE project_id='P001' AND code='T99'");
        var t98 = new Task(); t98.setId("T98"); t98.setName("改进任务2");
        when(taskController.createTask(any(), eq("P001"))).thenAnswer(invocation -> {
            jdbc.update("INSERT INTO tasks VALUES (?,?,?,?,?,?,?)", "P001", "T98", "改进任务2", "management", null, 0, 0);
            return TaskDtos.toOut(t98);
        });
        assertEquals("T98", controller.createImprovement("P001", "US1", request).get("improvement_task_code"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quality_improvements WHERE source_code='US1'", Integer.class));
    }
}
