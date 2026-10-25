package com.aicap.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** US23 release journey against real MySQL and HTTP: evidence, confirmation, task, feedback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class EfficiencyDodContractTest extends ContractTestSupport {
    @Autowired private JdbcTemplate jdbc;

    @Test void blockedTaskFlowsThroughHumanConfirmationAndMeasuredSignal() {
        String source = "TUS23";
        String improvement = null;
        jdbc.update("INSERT INTO tasks(project_id,code,name,owner_id,hours,week_start,week_end,"
                + "task_type,status,progress,blocked) VALUES('P001',?,'US23验收阻塞',1,2,1,1,'management',0,0,1)", source);
        try {
            String path = "/api/projects/P001/efficiency-insights";
            ApiResponse before = get(path, token(USER_ADMIN));
            assertStatus(before, 200);
            assertTrue(before.json().path("issues").toString().contains(source), before.body());

            String body = json(map("issue_kind", "blocked", "owner_id", 1,
                    "hours", 2, "week_start", 1, "week_end", 1));
            assertStatus(post(path + "/" + source + "/improvements", token(USER_VIEWER), body), 403);
            ApiResponse confirmed = post(path + "/" + source + "/improvements", token(USER_ADMIN), body);
            assertStatus(confirmed, 200);
            improvement = confirmed.json().path("improvement_task_code").asText();
            assertFalse(improvement.isBlank(), confirmed.body());
            assertEquals(improvement, post(path + "/" + source + "/improvements", token(USER_ADMIN), body)
                    .json().path("improvement_task_code").asText());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE project_id='P001' AND code=?",
                    Integer.class, improvement));

            jdbc.update("UPDATE tasks SET status=2,progress=100 WHERE project_id='P001' AND code=?", improvement);
            ApiResponse stillBlocked = get(path, token(USER_ADMIN));
            assertTrue(stillBlocked.json().path("improvements").toString().contains("still_present"), stillBlocked.body());
            jdbc.update("UPDATE tasks SET blocked=0 WHERE project_id='P001' AND code=?", source);
            ApiResponse after = get(path, token(USER_ADMIN));
            assertTrue(after.json().path("improvements").toString().contains("signal_cleared"), after.body());
        } finally {
            jdbc.update("DELETE FROM efficiency_improvements WHERE project_id='P001' AND source_task_code=?", source);
            if (improvement != null) {
                jdbc.update("DELETE FROM task_logs WHERE project_id='P001' AND task_id=?", improvement);
                jdbc.update("DELETE FROM tasks WHERE project_id='P001' AND code=?", improvement);
            }
            jdbc.update("DELETE FROM task_logs WHERE project_id='P001' AND task_id=?", source);
            jdbc.update("DELETE FROM tasks WHERE project_id='P001' AND code=?", source);
        }
    }
}
