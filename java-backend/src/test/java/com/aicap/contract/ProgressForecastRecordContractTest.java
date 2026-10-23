package com.aicap.contract;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US19 预测基线在 <b>真实 MySQL</b> 上的契约：H2 单元用例证明不了两件事 ——
 * {@code GeneratedKeyHolder} 在 MySQL 驱动下能否取回自增主键，以及 {@code SELECT ... FOR UPDATE}
 * 能否让两个相反的决策真正串行（一个生效、另一个 409）。
 *
 * <p>本类只负责这两条边界与权限/作用域；预测口径本身由
 * {@code ProgressForecastControllerTest}（H2）覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressForecastRecordContractTest extends ContractTestSupport {

    private static final String RECORDS = "/api/projects/P001/progress-forecast/records";

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> insertedLogs = new ArrayList<>();
    private final Map<String, Integer> originalStatus = new HashMap<>();

    /** 造出"≥3 条完成记录 + ≥14 天观察期"，让预测有资格固化成基线。 */
    @BeforeEach
    void prepareForecastableHistory() {
        jdbc.update("DELETE FROM progress_forecasts WHERE project_id='P001'");
        Timestamp twentyDaysAgo = Timestamp.valueOf(LocalDateTime.now().minusDays(20));
        for (String code : new String[] { "T01", "T02", "T03" }) {
            originalStatus.put(code, jdbc.queryForObject(
                    "SELECT status FROM tasks WHERE project_id='P001' AND code=?", Integer.class, code));
            jdbc.update("DELETE FROM task_logs WHERE project_id='P001' AND task_id=?", code);
            jdbc.update("UPDATE tasks SET status=2 WHERE project_id='P001' AND code=?", code);
            jdbc.update("INSERT INTO task_logs(project_id,task_id,new_status,user_id,created_at) "
                    + "VALUES ('P001',?,2,2,?)", code, twentyDaysAgo);
            insertedLogs.add(jdbc.queryForObject(
                    "SELECT MAX(id) FROM task_logs WHERE project_id='P001' AND task_id=?", Long.class, code));
        }
    }

    @AfterEach
    void restoreSeedState() {
        for (Long id : insertedLogs) jdbc.update("DELETE FROM task_logs WHERE id=?", id);
        insertedLogs.clear();
        for (var entry : originalStatus.entrySet()) {
            jdbc.update("UPDATE tasks SET status=? WHERE project_id='P001' AND code=?",
                    entry.getValue(), entry.getKey());
        }
        originalStatus.clear();
        jdbc.update("DELETE FROM progress_forecasts WHERE project_id IN ('P001','P002')");
    }

    private long record() {
        ApiResponse created = post(RECORDS, token(USER_OWNER), null);
        assertStatus(created, 200);
        long id = created.json().path("id").asLong();
        assertTrue(id > 0, "MySQL 自增主键必须回填 -> " + created.body());
        return id;
    }

    @Test
    void recordingOnMySqlReturnsTheGeneratedKeyAndReadsBack() {
        ApiResponse created = post(RECORDS, token(USER_OWNER), null);
        assertStatus(created, 200);
        long id = created.json().path("id").asLong();
        assertTrue(id > 0, "MySQL 自增主键必须回填 -> " + created.body());
        assertEquals("proposed", created.json().path("record_status").asText(), created.body());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM progress_forecasts WHERE id=?", Integer.class, id));
        assertEquals(USER_OWNER, jdbc.queryForObject(
                "SELECT display_name FROM users WHERE id=(SELECT requested_by FROM progress_forecasts WHERE id=?)",
                String.class, id));

        ApiResponse listed = get(RECORDS, token(USER_MEMBER));
        assertStatus(listed, 200);
        assertEquals(1, listed.json().path("records").size(), listed.body());
        var row = listed.json().path("records").get(0);
        assertEquals(id, row.path("id").asLong());
        assertEquals("not_baseline", row.path("reconciliation").path("verdict").asText());
        assertNotNull(row.path("assumptions").asText(null), "基线必须带回当时的假设");
    }

    @Test
    void concurrentOppositeDecisionsSerialiseToOneWinner() {
        long id = record();
        String decision = RECORDS + "/" + id + "/decision";
        var results = fireConcurrently(
                () -> post(decision, token(USER_OWNER), "{\"decision\":\"accept\"}"),
                () -> post(decision, token(USER_MEMBER), "{\"decision\":\"discard\"}"));
        var statuses = results.stream().map(ApiResponse::status).sorted().toList();
        assertEquals(List.of(200, 409), statuses, "并发相反决策只能有一个生效 -> " + results);
        String stored = jdbc.queryForObject("SELECT record_status FROM progress_forecasts WHERE id=?",
                String.class, id);
        assertTrue(List.of("accepted", "discarded").contains(stored), "落库状态必须是已决策之一: " + stored);
        assertNotNull(jdbc.queryForObject("SELECT decided_by FROM progress_forecasts WHERE id=?", Integer.class, id));
    }

    @Test
    void repeatedSameDecisionIsIdempotentOnMySql() {
        long id = record();
        String decision = RECORDS + "/" + id + "/decision";
        assertStatus(post(decision, token(USER_OWNER), "{\"decision\":\"accept\"}"), 200);
        assertStatus(post(decision, token(USER_OWNER), "{\"decision\":\"accept\"}"), 200);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM progress_forecasts WHERE id=? AND record_status='accepted'",
                Integer.class, id));
    }

    @Test
    void viewerCannotRecordAndForeignRecordIsInvisible() {
        assertStatus(post(RECORDS, token(USER_VIEWER), null), 403);
        long id = record();
        assertStatus(post(RECORDS + "/" + id + "/decision", token(USER_VIEWER),
                "{\"decision\":\"accept\"}"), 403);

        jdbc.update("INSERT INTO progress_forecasts(project_id,history_start,history_end,observed_days,"
                + "tasks_total,tasks_open,tasks_done,recent_completions,completions_per_week,"
                + "estimated_remaining_days,estimated_date,forecast_status,assumptions,requested_by,"
                + "requested_at,record_status) VALUES ('P002',CURDATE(),CURDATE(),1,0,0,0,0,NULL,NULL,"
                + "NULL,'forecast','非本项目的基线',1,NOW(),'proposed')");
        Long foreign = jdbc.queryForObject(
                "SELECT MAX(id) FROM progress_forecasts WHERE project_id='P002'", Long.class);
        assertStatus(post(RECORDS + "/" + foreign + "/decision", token(USER_OWNER),
                "{\"decision\":\"accept\"}"), 404);
        assertEquals("proposed", jdbc.queryForObject(
                "SELECT record_status FROM progress_forecasts WHERE id=?", String.class, foreign));
    }

    @Test
    void forecastWithoutEnoughHistoryCannotBecomeABaseline() {
        for (Long logId : insertedLogs) jdbc.update("DELETE FROM task_logs WHERE id=?", logId);
        insertedLogs.clear();
        assertStatus(post(RECORDS, token(USER_OWNER), null), 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM progress_forecasts", Integer.class));
    }
}
