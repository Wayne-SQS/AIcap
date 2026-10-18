package com.aicap.contract;

import com.aicap.common.AgentError;
import com.aicap.entity.PlanningAgentRun;
import com.aicap.entity.Task;
import com.aicap.mapper.PlanningAgentRunMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.planning.PlanningAnalysisValidator;
import com.aicap.service.TaskStoryRelationService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划智能体新增任务({@code CREATE_TASK})的端到端契约。
 *
 * <p>钉住三件事:
 * <ol>
 *   <li><b>校验必须拦</b> —— 负责人不是本项目成员、周次越界、缺必填字段都不能进执行;</li>
 *   <li><b>审批前不落库</b> —— 草案只在 {@code planning_agent_runs.result_json} 里;</li>
 *   <li><b>确认后落成真任务</b> —— 走现有"确认执行"链,编号由 IdAllocator 分配(T%02d),
 *       四视图接口能读回同一份数据。</li>
 * </ol>
 *
 * <p>为什么单独建类而不是塞进 {@code StoryGapFixContractTest}:CREATE_TASK 是规划域的新动作,
 * 用例的装配(直接造 run + 人确认)与那边的小修补无关,分开更好定位。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class PlanningCreateTaskContractTest extends ContractTestSupport {

    @Autowired private PlanningAnalysisValidator validator;
    @Autowired private PlanningAgentRunMapper runs;
    @Autowired private TaskMapper taskMapper;
    @Autowired private TaskStoryRelationService taskStoryRelations;
    @Autowired private ObjectMapper om;

    private ObjectNode createTask(String name) {
        String taskName = name == null ? uniq("文档整理") : name;
        ObjectNode action = om.createObjectNode();
        action.put("type", "CREATE_TASK");
        action.put("name", taskName);
        action.put("owner_id", 3);       // 孙秋实:member,P001 成员
        action.put("hours", 8);
        action.put("week_start", 5);
        action.put("week_end", 6);
        action.put("priority", "Should");
        action.put("task_type", "management");
        return action;
    }

    private ObjectNode plan(ObjectNode action) {
        ObjectNode plan = om.createObjectNode();
        plan.put("summary", "新增文档整理任务");
        plan.putArray("actions").add(action);
        return plan;
    }

    private JsonNode validate(String projectId, ObjectNode action) {
        return validator.validate(projectId, plan(action).toString());
    }

    @Test
    void createTaskValidationRejectsUnrealOwnerBadWeeksAndMissingFields() {
        assertEquals("entity_not_found", assertThrows(AgentError.class,
                () -> validate(PROJECT_ID, createTask(null).put("owner_id", 999999))).getCode());
        assertEquals("invalid_schedule", assertThrows(AgentError.class,
                () -> validate(PROJECT_ID, createTask(null).put("week_end", 7))).getCode());
        ObjectNode missingHours = createTask(null);
        missingHours.remove("hours");
        assertEquals("invalid_plan", assertThrows(AgentError.class,
                () -> validate(PROJECT_ID, missingHours)).getCode());
        // P002 只把 admin/owner 加进成员表:孙秋实(id=3)不是 P002 成员,必须拒绝
        assertEquals("invalid_plan", assertThrows(AgentError.class,
                () -> validate("P002", createTask(null).put("owner_id", 3))).getCode());
    }

    @Test
    void createTaskValidationFillsOwnerNameAndCapacityWarning() {
        JsonNode validated = validate(PROJECT_ID, createTask(null));
        JsonNode action = validated.path("actions").get(0);
        assertEquals("孙秋实", action.path("owner_name").asText());
        assertEquals(5, action.path("week_start").asInt());
        assertEquals(6, action.path("week_end").asInt());
        assertTrue(validated.path("requires_confirmation").asBoolean(), validated.toString());
        assertTrue(validated.path("impacts").toString().contains("task_create"), validated.toString());
    }

    @Test
    void createTaskDraftStaysInQueueUntilHumanConfirmation() throws Exception {
        String name = uniq("文档整理");
        JsonNode validated = validator.validate(PROJECT_ID, plan(createTask(name)).toString());
        assertNull(taskByName(name), "草案阶段不应写入业务表");

        PlanningAgentRun run = queuedRun(name, validated.toString());
        assertNull(taskByName(name));

        // 走真实确认端点(而不是直接调 service):确认链路要当前登录用户,直调会因缺
        // AuthContext 被 requireWriter 以 401 拒掉 —— 那不是本用例要测的东西
        ApiResponse confirmed = post("/api/planning-agent/runs/" + run.getId() + "/confirm",
                token(USER_ADMIN), null);
        assertEquals(200, confirmed.status(), confirmed.body());
        assertEquals("completed", confirmed.json().path("status").asText());

        JsonNode created = taskByName(name);
        assertNotNull(created, "确认后应能读回新任务");
        try {
            assertTrue(created.path("id").asText().matches("T\\d{2,}"), created.toString());
            assertEquals(3, created.path("owner_id").asInt());
            assertEquals(8, created.path("hours").asInt());
            assertEquals(5, created.path("week_start").asInt());
            assertEquals(6, created.path("week_end").asInt());
            assertEquals("Should", created.path("priority").asText());
            assertEquals("management", created.path("task_type").asText());
            assertEquals(name, created.path("name").asText());
        } finally {
            cleanupTask(created.path("id").asText());
        }
    }

    @Test
    void createTaskAllocatesDistinctCodesForTwoNewTasks() throws Exception {
        String first = uniq("文档整理A");
        String second = uniq("文档整理B");
        String firstId = null;
        String secondId = null;
        try {
            firstId = confirmOne(first);
            secondId = confirmOne(second);
            assertNotNull(firstId);
            assertNotNull(secondId);
            assertTrue(!firstId.equals(secondId), "两个新任务不能拿到同一个编号");
        } finally {
            if (firstId != null) cleanupTask(firstId);
            if (secondId != null) cleanupTask(secondId);
        }
    }

    /**
     * 清掉本用例造的任务。**不走 REST**:当前 Java 后端没有 {@code DELETE /api/tasks/{id}}
     * (FastAPI 归档实现才有),用 mapper 直接删,免得用例因清理 404 而假红。
     */
    private void cleanupTask(String code) {
        Task row = taskMapper.selectOne(new QueryWrapper<Task>()
                .eq("project_id", PROJECT_ID).eq("code", code));
        if (row == null) return;
        taskStoryRelations.removeForTask(PROJECT_ID, row.getInternalId());
        taskMapper.deleteById(row.getInternalId());
    }

    private String confirmOne(String name) throws Exception {
        JsonNode validated = validator.validate(PROJECT_ID, plan(createTask(name)).toString());
        PlanningAgentRun run = queuedRun(name, validated.toString());
        ApiResponse confirmed = post("/api/planning-agent/runs/" + run.getId() + "/confirm",
                token(USER_ADMIN), null);
        assertEquals(200, confirmed.status(), confirmed.body());
        JsonNode created = taskByName(name);
        assertNotNull(created, "确认后应能读回任务: " + name);
        return created.path("id").asText();
    }

    private PlanningAgentRun queuedRun(String name, String resultJson) {
        PlanningAgentRun run = new PlanningAgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setRequestedBy(1);
        run.setProjectId(PROJECT_ID);
        run.setRequestText("新增一个任务:" + name);
        run.setStatus("waiting_confirmation");
        run.setAttempt(1);
        run.setModel("fixture");
        run.setPromptVersion("planning-owner-v1");
        run.setResultJson(resultJson);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runs.insert(run);
        return run;
    }

    private JsonNode taskByName(String name) {
        for (JsonNode task : get("/api/tasks", token(USER_ADMIN)).json()) {
            if (name.equals(task.path("name").asText())) return task;
        }
        return null;
    }
}
