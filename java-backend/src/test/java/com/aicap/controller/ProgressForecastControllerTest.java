package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.common.GlobalExceptionHandler;
import com.aicap.entity.User;
import com.aicap.security.AuthContext;
import com.aicap.service.ProjectAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
        jdbc.execute("CREATE TABLE progress_forecasts(id bigint auto_increment primary key,project_id varchar(20),"
                + "history_start date,history_end date,observed_days int,tasks_total int,tasks_open int,"
                + "tasks_done int,recent_completions int,completions_per_week decimal(8,2),"
                + "estimated_remaining_days int,estimated_date date,forecast_status varchar(20),"
                + "assumptions varchar(500),requested_by int,requested_at timestamp,decided_by int,"
                + "decided_at timestamp,decision_note varchar(200),record_status varchar(16))");
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

    /** 3 completions inside a 24-day window, 4 tasks still open. */
    private void forecastableProject() {
        for (var id : new String[] { "T1", "T2", "T3" }) task("P001", id, 2);
        for (var id : new String[] { "T4", "T5", "T6" }) task("P001", id, 0);
        task("P001", "T7", 1);
        log("P001", "T1", 2, "2026-09-30");
        log("P001", "T2", 2, "2026-10-06");
        log("P001", "T3", 2, "2026-10-12");
        log("P001", "T7", 2, "2026-10-10");
        log("P001", "T7", 1, "2026-10-20");
    }

    private User member(int id) {
        var user = new User();
        user.setId(id);
        user.setRole("member");
        AuthContext.set(user);
        return user;
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
        forecastableProject();
        task("P002", "T8", 2);
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

    @Test void onlyADatedForecastCanBecomeABaseline() {
        task("P001", "T1", 0);
        log("P001", "T1", 1, "2026-10-20");
        member(2);
        var error = assertThrows(ApiException.class, () -> forecast.snapshot("P001", 2, today));
        assertEquals(422, error.getStatus());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM progress_forecasts", Integer.class) == 0);
    }

    @Test void baselineKeepsItsInputsAndAcceptIsIdempotent() {
        forecastableProject();
        member(2);
        var created = forecast.snapshot("P001", 2, today);
        long id = ((Number) created.get("id")).longValue();
        assertEquals("proposed", created.get("record_status"));
        assertEquals("2026-11-24", created.get("estimated_date"));
        assertEquals("task_status_history", forecast.calculate("P001", today).get("basis"));
        assertEquals(3, created.get("recent_completions"));
        assertEquals(4, created.get("tasks_open"));
        assertEquals(24, created.get("observed_days"));
        assertEquals(2, created.get("requested_by"));
        assertTrue(String.valueOf(created.get("assumptions")).contains("线性外推"));
        var notAccepted = (Map<?, ?>) created.get("reconciliation");
        assertEquals("not_baseline", notAccepted.get("verdict"));

        var accepted = forecast.decide("P001", id, "accept", "按此基线跟踪", 2, today);
        assertEquals("accepted", accepted.get("record_status"));
        assertEquals("within_plan", ((Map<?, ?>) accepted.get("reconciliation")).get("verdict"));
        assertEquals("accepted", forecast.decide("P001", id, "accept", null, 2, today).get("record_status"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM progress_forecasts WHERE record_status='accepted'",
                Integer.class));
        var conflict = assertThrows(ApiException.class,
                () -> forecast.decide("P001", id, "discard", null, 2, today));
        assertEquals(409, conflict.getStatus());
    }

    @Test void acceptedBaselineIsReconciledAgainstLaterWork() {
        forecastableProject();
        member(2);
        long id = ((Number) forecast.snapshot("P001", 2, today).get("id")).longValue();
        forecast.decide("P001", id, "accept", null, 2, today);

        var overdue = forecast.recordView("P001", id, today.plusDays(40));
        var overdueCheck = (Map<?, ?>) overdue.get("reconciliation");
        assertEquals("overdue", overdueCheck.get("verdict"));
        assertEquals(-8L, overdueCheck.get("due_in_days"));
        assertEquals(0L, overdueCheck.get("completed_since"));

        jdbc.update("UPDATE tasks SET status=2 WHERE project_id='P001'");
        var reached = forecast.recordView("P001", id, today.plusDays(40));
        var reachedCheck = (Map<?, ?>) reached.get("reconciliation");
        assertEquals("reached", reachedCheck.get("verdict"));
        assertEquals(0L, reachedCheck.get("open_now"));
        assertEquals(4L, reachedCheck.get("completed_since"));
    }

    @Test void recordsListExplainsScopeAndCannotBeDecidedAcrossProjects() {
        forecastableProject();
        member(2);
        long id = ((Number) forecast.snapshot("P001", 2, today).get("id")).longValue();
        var listed = forecast.records("P001");
        assertEquals("P001", listed.get("project_id"));
        assertEquals(1, ((java.util.List<?>) listed.get("records")).size());
        assertTrue(String.valueOf(listed.get("limitations")).contains("不替代重新预测"));
        assertEquals(404, assertThrows(ApiException.class,
                () -> forecast.decide("P002", id, "accept", null, 2, today)).getStatus());
        assertEquals(404, assertThrows(ApiException.class,
                () -> forecast.decide("P001", id + 1, "accept", null, 2, today)).getStatus());
        assertEquals("proposed", forecast.recordView("P001", id, today).get("record_status"));
    }

    @Test void viewerCannotCreateOrDecideOnABaseline() throws Exception {
        var user = new User(); user.setId(1); user.setRole("viewer"); AuthContext.set(user);
        var mvc = MockMvcBuilders.standaloneSetup(forecast)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        assertEquals(403, mvc.perform(post("/api/projects/P001/progress-forecast/records"))
                .andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(post("/api/projects/P001/progress-forecast/records/1/decision")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"accept\"}"))
                .andReturn().getResponse().getStatus());
    }

    @Test void decisionRejectsUnknownValues() throws Exception {
        member(2);
        var mvc = MockMvcBuilders.standaloneSetup(forecast)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        assertEquals(422, mvc.perform(post("/api/projects/P001/progress-forecast/records/1/decision")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approve\"}"))
                .andReturn().getResponse().getStatus());
    }
}
