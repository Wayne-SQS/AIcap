package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.common.GlobalExceptionHandler;
import com.aicap.entity.User;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class EfficiencyInsightsControllerTest {
    private JdbcTemplate jdbc;
    private EfficiencyInsightsController controller;
    private ProjectAccessService projects;
    private final LocalDate today = LocalDate.of(2026, 10, 23);

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE tasks(project_id varchar(20),code varchar(20),name varchar(200),status int,blocked int)");
        jdbc.execute("CREATE TABLE task_logs(id bigint auto_increment primary key,project_id varchar(20),"
                + "task_id varchar(20),new_status int,created_at timestamp)");
        projects = mock(ProjectAccessService.class);
        controller = new EfficiencyInsightsController(projects, jdbc);
    }

    @AfterEach void clearAuth() { AuthContext.clear(); }

    private void task(String project, String code, int status, int blocked) {
        jdbc.update("INSERT INTO tasks VALUES (?,?,?,?,?)", project, code, code, status, blocked);
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
}
