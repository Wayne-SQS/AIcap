package com.aicap.contract;

import com.aicap.entity.PlanningAgentRun;
import com.aicap.entity.Task;
import com.aicap.llm.ModelClient;
import com.aicap.mapper.PlanningAgentRunMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.planning.PlanningAgentRunner;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** US18: bounded real-data snapshot, model candidate filtering, confirmation and persistence. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class PlanningScheduleDodContractTest extends ContractTestSupport {
    @Autowired private PlanningAgentRunner runner;
    @Autowired private PlanningAgentRunMapper runs;
    @Autowired private TaskMapper tasks;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test void suggestionWaitsForConfirmationAndThenChangesScheduleWithAudit() throws Exception {
        String admin = token(USER_ADMIN);
        Task task = task("T12");
        assertEquals(4, task.getWeekStart());
        assertEquals(5, task.getWeekEnd());
        ModelClient model = mock(ModelClient.class);
        String modelPlan = """
                {"intent":"schedule","summary":"T12 依赖已完成且成员 W5 有容量，建议缩短窗口",
                 "answer":"优先处理 T12；实际工时仍需负责人确认。",
                 "actions":[
                   {"type":"UPDATE_TASK_SCHEDULE","task_id":"T01","target_week":1},
                   {"type":"UPDATE_TASK_SCHEDULE","task_id":"T09","start_week":4,"end_week":5},
                   {"type":"UPDATE_TASK_SCHEDULE","task_id":"T12","start_week":5,"end_week":5}],
                 "affected_entities":[],"warnings":[],"requires_confirmation":true}
                """;
        var reply = mapper.createObjectNode().put("role", "assistant").put("content", modelPlan);
        when(model.complete(anyList(), eq(List.of()), eq(true)))
                .thenReturn(new ModelClient.Completion(reply, Map.of()));

        String runId = UUID.randomUUID().toString();
        try {
            var plan = runner.analyze(PROJECT_ID, "智能排期建议", model);
            assertEquals(1, plan.path("actions").size(), plan.toString());
            assertEquals("T12", plan.at("/actions/0/task_id").asText());
            assertTrue(plan.path("warnings").toString().contains("T01 当前排期未变化"));
            assertTrue(plan.path("warnings").toString().contains("T09 会引入新的排期风险"));
            assertTrue(plan.path("requires_confirmation").asBoolean());
            assertEquals(4, task("T12").getWeekStart(), "生成草案不能改业务数据");

            PlanningAgentRun run = new PlanningAgentRun();
            run.setId(runId);
            run.setProjectId(PROJECT_ID);
            run.setRequestedBy(1);
            run.setRequestText("智能排期建议");
            run.setStatus("waiting_confirmation");
            run.setAttempt(1);
            run.setModel("local-fixture");
            run.setPromptVersion("schedule-dod-v1");
            run.setResultJson(plan.toString());
            run.setCreatedAt(LocalDateTime.now());
            run.setUpdatedAt(LocalDateTime.now());
            runs.insert(run);

            ApiResponse confirmed = post("/api/planning-agent/runs/" + runId
                    + "/confirm?projectId=" + PROJECT_ID, admin, null);
            assertEquals(200, confirmed.status(), confirmed.body());
            assertEquals("completed", confirmed.json().path("status").asText());
            assertEquals(5, task("T12").getWeekStart());
            assertEquals(5, task("T12").getWeekEnd());
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM planning_agent_events WHERE run_id=? AND kind='completed'",
                    Integer.class, runId) > 0);
        } finally {
            jdbc.update("UPDATE tasks SET week_start=4,week_end=5 WHERE project_id=? AND code='T12'", PROJECT_ID);
            jdbc.update("DELETE FROM planning_agent_events WHERE run_id=?", runId);
            jdbc.update("DELETE FROM planning_agent_runs WHERE id=?", runId);
        }
    }

    private Task task(String code) {
        return tasks.selectOne(new QueryWrapper<Task>().eq("project_id", PROJECT_ID).eq("code", code));
    }
}
