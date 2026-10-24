package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.common.GlobalExceptionHandler;
import com.aicap.entity.User;
import com.aicap.entity.Task;
import com.aicap.dto.TaskDtos;
import com.aicap.security.AuthContext;
import com.aicap.service.ProjectAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class EfficiencyInsightsControllerTest {
    private JdbcTemplate jdbc;
    private EfficiencyInsightsController controller;
    private ProjectAccessService projects;
    private TaskPoolController taskController;
    private final LocalDate today = LocalDate.of(2026, 10, 23);

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE tasks(project_id varchar(20),code varchar(20),name varchar(200),status int,blocked int,progress int)");
        jdbc.execute("CREATE TABLE task_logs(id bigint auto_increment primary key,project_id varchar(20),"
                + "task_id varchar(20),new_status int,created_at timestamp)");
        jdbc.execute("CREATE TABLE efficiency_improvements(project_id varchar(20),source_task_code varchar(20),"
                + "issue_kind varchar(20),improvement_task_code varchar(20),reviewed_by int,reviewed_at timestamp,"
                + "primary key(project_id,source_task_code,issue_kind))");
        projects = mock(ProjectAccessService.class);
        taskController = mock(TaskPoolController.class);
        controller = new EfficiencyInsightsController(projects, jdbc, taskController);
    }

    @AfterEach void clearAuth() { AuthContext.clear(); }

    private void task(String project, String code, int status, int blocked) {
        jdbc.update("INSERT INTO tasks VALUES (?,?,?,?,?,?)", project, code, code, status, blocked, 0);
    }

    private void log(String project, String code, int status, String date) {
        jdbc.update("INSERT INTO task_logs(project_id,task_id,new_status,created_at) VALUES (?,?,?,?)",
                project, code, status, date + " 12:00:00");
    }

    @SuppressWarnings("unchecked")
    @Test void reportsOnlyEvidenceBackedIssuesInTheRequestedProject() {
        task("P001", "BLOCKED", 0, 1);
        task("P001", "STALLED", 1, 0);
        task("P001", "RECENT", 1, 0);
        task("P001", "NO_HISTORY", 1, 0);
        task("P001", "MISMATCH", 1, 0);
        task("P001", "DONE", 2, 1);
        task("P002", "OTHER", 1, 1);
        log("P001", "STALLED", 1, "2026-10-09");
        log("P001", "RECENT", 1, "2026-10-10");
        log("P001", "MISMATCH", 2, "2026-09-01");
        log("P002", "OTHER", 1, "2026-09-01");
        var result = controller.calculate("P001", today);
        var issues = (List<Map<String, Object>>) result.get("issues");
        assertEquals(5, result.get("open_tasks"));
        assertEquals(3, result.get("missing_or_mismatched_history"));
        assertEquals(2, issues.size());
        assertEquals("BLOCKED", issues.get(0).get("task_code"));
        assertEquals("blocked", issues.get(0).get("kind"));
        assertNull(issues.get(0).get("days_in_status"));
        assertEquals("STALLED", issues.get(1).get("task_code"));
        assertEquals("stalled", issues.get(1).get("kind"));
        assertEquals(14L, issues.get(1).get("days_in_status"));
    }

    @SuppressWarnings("unchecked")
    @Test void latestTransitionControlsStallInference() {
        task("P001", "RESTARTED", 1, 0);
        log("P001", "RESTARTED", 1, "2026-09-01");
        log("P001", "RESTARTED", 0, "2026-10-15");
        log("P001", "RESTARTED", 1, "2026-10-20");
        var result = controller.calculate("P001", today);
        assertTrue(((List<Map<String, Object>>) result.get("issues")).isEmpty());
        assertEquals(0, result.get("missing_or_mismatched_history"));
    }

    @Test void endpointRequiresLoginAndProjectMembership() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        assertEquals(401, mvc.perform(get("/api/projects/P001/efficiency-insights"))
                .andReturn().getResponse().getStatus());
        var user = new User(); user.setId(1); user.setRole("viewer"); AuthContext.set(user);
        assertEquals(200, mvc.perform(get("/api/projects/P001/efficiency-insights"))
                .andReturn().getResponse().getStatus());
        doThrow(ApiException.forbidden("无权访问该项目")).when(projects).requireMember("P002");
        assertEquals(403, mvc.perform(get("/api/projects/P002/efficiency-insights"))
                .andReturn().getResponse().getStatus());
    }

    @SuppressWarnings("unchecked")
    @Test void confirmedIssueCreatesOneTraceableTaskAndReadsItsOutcome() {
        task("P001", "SOURCE", 0, 1);
        var user = new User(); user.setId(7); user.setRole("owner"); AuthContext.set(user);
        var newTask = new Task(); newTask.setId("T99"); newTask.setName("改进任务");
        when(taskController.createTask(any(), eq("P001"))).thenAnswer(invocation -> {
            jdbc.update("INSERT INTO tasks VALUES (?,?,?,?,?,?)", "P001", "T99", "改进任务", 0, 0, 0);
            return TaskDtos.toOut(newTask);
        });
        var request = new EfficiencyInsightsController.ImprovementRequest("blocked", 7, 2, 5, 5);
        var first = controller.createImprovement("P001", "SOURCE", request);
        var again = controller.createImprovement("P001", "SOURCE", request);
        assertEquals("T99", first.get("improvement_task_code"));
        assertEquals("T99", again.get("improvement_task_code"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM efficiency_improvements", Integer.class));
        jdbc.update("UPDATE tasks SET status=2,progress=100 WHERE project_id='P001' AND code='T99'");
        var records = (List<Map<String, Object>>) controller.calculate("P001", today).get("improvements");
        assertEquals(2, records.get(0).get("improvement_status"));
        assertEquals(100, records.get(0).get("improvement_progress"));
        assertEquals("still_present", records.get(0).get("source_outcome"));
        assertEquals(1, records.get(0).get("after_issue"));
        jdbc.update("UPDATE tasks SET blocked=0 WHERE project_id='P001' AND code='SOURCE'");
        records = (List<Map<String, Object>>) controller.calculate("P001", today).get("improvements");
        assertEquals("signal_cleared", records.get(0).get("source_outcome"));
        assertEquals(0, records.get(0).get("after_issue"));
    }

    @Test void staleOrCrossProjectIssueCannotCreateAnImprovement() {
        task("P001", "SOURCE", 1, 0);
        log("P001", "SOURCE", 1, "2026-10-20");
        var user = new User(); user.setId(7); user.setRole("owner"); AuthContext.set(user);
        var request = new EfficiencyInsightsController.ImprovementRequest("stalled", 7, 2, 5, 5);
        assertEquals(409, assertThrows(ApiException.class,
                () -> controller.createImprovement("P001", "SOURCE", request)).getStatus());
        assertEquals(404, assertThrows(ApiException.class,
                () -> controller.createImprovement("P002", "SOURCE", request)).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM efficiency_improvements", Integer.class));
    }

    @SuppressWarnings("unchecked")
    @Test void stalledOutcomeDoesNotClaimSuccessWhenHistoryIsMissing() {
        task("P001", "SOURCE", 1, 0);
        task("P001", "FIX", 0, 0);
        jdbc.update("INSERT INTO efficiency_improvements VALUES ('P001','SOURCE','stalled','FIX',7,CURRENT_TIMESTAMP)");
        var records = (List<Map<String, Object>>) controller.calculate("P001", today).get("improvements");
        assertEquals("insufficient_data", records.get(0).get("source_outcome"));
        assertNull(records.get(0).get("after_issue"));
        jdbc.update("UPDATE tasks SET status=2 WHERE project_id='P001' AND code='SOURCE'");
        records = (List<Map<String, Object>>) controller.calculate("P001", today).get("improvements");
        assertEquals("signal_cleared", records.get(0).get("source_outcome"));
        assertEquals(0, records.get(0).get("after_issue"));
    }
}
