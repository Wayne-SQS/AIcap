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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ProgressForecastControllerTest {
    private JdbcTemplate jdbc;
    private ProgressForecastController forecast;
    private ProjectAccessService projects;
    private final LocalDate today = LocalDate.of(2026, 10, 23);

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE tasks(project_id varchar(20),code varchar(20),status int)");
        jdbc.execute("CREATE TABLE task_logs(id bigint auto_increment primary key,project_id varchar(20),"
                + "task_id varchar(20),new_status int,created_at timestamp)");
        projects = mock(ProjectAccessService.class);
        forecast = new ProgressForecastController(projects, jdbc);
    }

    @AfterEach void clearAuth() { AuthContext.clear(); }

    private void task(String project, String id, int status) {
        jdbc.update("INSERT INTO tasks VALUES (?,?,?)", project, id, status);
    }

    private void log(String project, String id, int status, String date) {
        jdbc.update("INSERT INTO task_logs(project_id,task_id,new_status,created_at) VALUES (?,?,?,?)",
                project, id, status, date + " 12:00:00");
    }

    @Test void insufficientHistoryDoesNotInventACompletionDate() {
        task("P001", "T1", 0);
        task("P001", "T2", 1);
        log("P001", "T3", 2, "2026-10-20");
        var result = forecast.calculate("P001", today);
        assertEquals("insufficient_data", result.get("status"));
        assertNull(result.get("estimated_date"));
        assertEquals(2L, result.get("tasks_open"));
    }

    @Test void recentHistoryExcludesReopenedAndOtherProjectTasks() {
        for (var id : new String[] { "T1", "T2", "T3" }) task("P001", id, 2);
        for (var id : new String[] { "T4", "T5", "T6" }) task("P001", id, 0);
        task("P001", "T7", 1);
        task("P002", "T8", 2);
        log("P001", "T1", 2, "2026-09-30");
        log("P001", "T2", 2, "2026-10-06");
        log("P001", "T3", 2, "2026-10-12");
        log("P001", "T7", 2, "2026-10-10");
        log("P001", "T7", 1, "2026-10-20");
        log("P002", "T8", 2, "2026-10-12");
        var result = forecast.calculate("P001", today);
        assertEquals("forecast", result.get("status"));
        assertEquals(3L, result.get("recent_completions"));
        assertEquals(4L, result.get("tasks_open"));
        assertEquals(24L, result.get("observed_days"));
        assertEquals(32L, result.get("estimated_remaining_days"));
        assertEquals("2026-11-24", result.get("estimated_date"));
    }

    @Test void completedOrEmptyScopeNeedsNoHistoricalRate() {
        assertEquals("empty_scope", forecast.calculate("P001", today).get("status"));
        task("P001", "CANCELLED", 3);
        assertEquals("empty_scope", forecast.calculate("P001", today).get("status"));
        task("P001", "T1", 2);
        var result = forecast.calculate("P001", today);
        assertEquals("complete", result.get("status"));
        assertEquals("2026-10-23", result.get("estimated_date"));
    }

    @Test void endpointRequiresLoginAndProjectMembership() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(forecast)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        assertEquals(401, mvc.perform(get("/api/projects/P001/progress-forecast"))
                .andReturn().getResponse().getStatus());
        var user = new User(); user.setId(1); user.setRole("viewer"); AuthContext.set(user);
        assertEquals(200, mvc.perform(get("/api/projects/P001/progress-forecast"))
                .andReturn().getResponse().getStatus());
        doThrow(ApiException.forbidden("无权访问该项目")).when(projects).requireMember("P002");
        assertEquals(403, mvc.perform(get("/api/projects/P002/progress-forecast"))
                .andReturn().getResponse().getStatus());
    }
}
