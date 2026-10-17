package com.aicap.contract;

import com.aicap.planning.PlanningAgentTools;
import com.aicap.planning.ImpactAnalysisService;
import com.aicap.planning.PlanningAnalysisValidator;
import com.aicap.planning.PlanningAgentRunner;
import com.aicap.planning.PlanningAgentService;
import com.aicap.agent.ModelClient;
import com.aicap.entity.PlanningAgentRun;
import com.aicap.mapper.PlanningAgentRunMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class StoryGapFixContractTest extends ContractTestSupport {
    @Autowired private PlanningAgentTools planningAgentTools;
    @Autowired private ImpactAnalysisService impactAnalysisService;
    @Autowired private PlanningAnalysisValidator planningValidator;
    @Autowired private PlanningAgentRunner planningRunner;
    @Autowired private PlanningAgentService planningService;
    @Autowired private PlanningAgentRunMapper planningRuns;
    @Autowired private TaskMapper taskMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void epicCanBeMaintainedAndStoryKeepsItsProjectScopedAssignment() {
        String admin = token(USER_ADMIN);
        JsonNode baseline = get("/api/epics", admin).json();
        assertEquals(4, baseline.size());
        Long collaborationId = null;
        for (JsonNode epic : baseline) if ("任务与协作".equals(epic.path("name").asText()))
            collaborationId = epic.path("id").asLong();
        assertNotNull(collaborationId);
        for (JsonNode story : get("/api/stories", admin).json()) if ("US03".equals(story.path("id").asText()))
            assertEquals(collaborationId.longValue(), story.path("epic_id").asLong());

        var epicBody = map("name", uniq("测试史诗"), "description", "验收故事归属");
        assertEquals(403, post("/api/epics", token(USER_VIEWER), json(epicBody)).status());
        ApiResponse created = post("/api/epics", admin, json(epicBody));
        assertEquals(200, created.status(), created.body());
        long epicId = created.json().path("id").asLong();
        String storyId = null;
        boolean deletedEpic = false;
        try {
            ApiResponse story = post("/api/stories", admin, json(map("title", uniq("史诗故事"),
                    "description", "作为负责人，我希望归属史诗，以便形成需求基线",
                    "acceptance", "史诗归属可再次读取", "epic_id", epicId)));
            assertEquals(200, story.status(), story.body());
            storyId = story.json().path("id").asText();
            assertEquals(epicId, story.json().path("epic_id").asLong());
            assertEquals(409, delete("/api/epics/" + epicId, admin).status());
            ApiResponse renamed = patch("/api/epics/" + epicId, admin,
                    json(map("name", "已细化的史诗", "description", "新的范围")));
            assertEquals(200, renamed.status(), renamed.body());
            assertEquals(1, renamed.json().path("storyCount").asInt());
            boolean found = false;
            for (JsonNode listed : get("/api/stories", admin).json()) if (storyId.equals(listed.path("id").asText())) {
                found = true;
                assertEquals(epicId, listed.path("epic_id").asLong());
            }
            assertTrue(found);
            assertEquals(400, patch("/api/stories/" + storyId, admin,
                    json(map("epic_id", 99999999))).status());
            ApiResponse unassigned = patch("/api/stories/" + storyId, admin, "{\"epic_id\":null}");
            assertEquals(200, unassigned.status(), unassigned.body());
            assertTrue(unassigned.json().path("epic_id").isNull());
            assertEquals(200, delete("/api/epics/" + epicId, admin).status());
            deletedEpic = true;
        } finally {
            if (storyId != null) delete("/api/stories/" + storyId + "?undone=keep", admin);
            if (!deletedEpic) assertEquals(200, delete("/api/epics/" + epicId, admin).status());
        }
    }
    @Test
    void poolExposesCreationTime() {
        String admin = token(USER_ADMIN);
        ApiResponse made = post("/api/pool", admin, json(map("title", uniq("pool-date"))));
        assertEquals(200, made.status(), made.body());
        String id = made.json().path("id").asText();
        try {
            assertTrue(made.json().hasNonNull("created_at"), made.body());
            JsonNode listed = get("/api/pool", admin).json();
            assertTrue(listed.toString().contains(id));
            for (JsonNode item : listed) if (id.equals(item.path("id").asText()))
                assertTrue(item.hasNonNull("created_at"), item.toString());
        } finally {
            delete("/api/pool/" + id, admin);
        }
    }

    @Test
    void poolCanBeRefinedWithoutLosingCreationTime() {
        String admin = token(USER_ADMIN);
        ApiResponse made = post("/api/pool", admin, json(map("title", uniq("pool-refine"),
                "source", "计划会", "description", "初步想法")));
        assertEquals(200, made.status(), made.body());
        String id = made.json().path("id").asText();
        String created = null;
        for (JsonNode item : get("/api/pool", admin).json())
            if (id.equals(item.path("id").asText())) created = item.path("created_at").asText();
        assertNotNull(created);
        try {
            assertEquals(403, patch("/api/pool/" + id, token(USER_VIEWER),
                    json(map("title", "不能修改"))).status());
            ApiResponse updated = patch("/api/pool/" + id, admin, json(map("title", "细化后的需求",
                    "description", "具体描述", "source", "计划会记录", "priority", "Must")));
            assertEquals(200, updated.status(), updated.body());
            assertEquals("计划会记录", updated.json().path("source").asText());
            assertEquals(created, updated.json().path("created_at").asText());
            JsonNode listed = get("/api/pool", admin).json();
            boolean found = false;
            for (JsonNode item : listed) if (id.equals(item.path("id").asText())) {
                found = true;
                assertEquals("细化后的需求", item.path("title").asText());
                assertEquals("具体描述", item.path("description").asText());
                assertEquals("Must", item.path("priority").asText());
            }
            assertTrue(found);
        } finally {
            delete("/api/pool/" + id, admin);
        }
    }

    @Test
    void zeroCapacityIsWritableAndReadable() throws Exception {
        String admin = token(USER_ADMIN);
        int previous = get("/api/auth/users", admin).json().get(0).path("capacity_hours").asInt();
        try {
            assertEquals(403, patch("/api/auth/users/1", token(USER_VIEWER), json(map("capacity_hours", 0))).status());
            assertEquals(200, patch("/api/auth/users/1", admin, json(map("capacity_hours", 0))).status());
            assertEquals(0, get("/api/auth/users", admin).json().get(0).path("capacity_hours").asInt());
            assertEquals(0, planningAgentTools.execute("get_member_load",
                    objectMapper.createObjectNode().put("owner_id", 1)).path("capacity_hours").asInt());
            assertEquals(0.0, planningAgentTools.execute("get_member_load",
                    objectMapper.createObjectNode().put("owner_id", 1)).path("weekly_capacity_hours").asDouble());
            JsonNode profile = get("/api/profile-agent/analysis?start=2026-08-25&end=2026-09-15", admin).json();
            boolean checkedProfile = false;
            for (JsonNode member : profile.path("members")) if (member.path("user_id").asInt() == 1) {
                checkedProfile = true;
                assertEquals(100, member.path("current_load_percent").asInt(), member.toString());
            }
            assertTrue(checkedProfile, profile.toString());
            String otherTask = null;
            for (JsonNode task : get("/api/tasks", admin).json())
                if (task.path("owner_id").asInt() != 1) { otherTask = task.path("id").asText(); break; }
            assertNotNull(otherTask);
            var plan = objectMapper.createObjectNode();
            plan.putArray("actions").addObject().put("type", "UPDATE_TASK_OWNER")
                    .put("task_id", otherTask).put("to_owner_id", 1);
            assertTrue(impactAnalysisService.analyze(plan).toString().contains("周容量 0.0h"));
            assertEquals(200, patch("/api/auth/users/1", admin, "{\"capacity_hours\":null}").status());
            assertTrue(get("/api/auth/users", admin).json().get(0).path("capacity_hours").isNull());
            assertTrue(planningAgentTools.execute("get_member_load",
                    objectMapper.createObjectNode().put("owner_id", 1)).path("weekly_capacity_hours").isNull());
            assertTrue(impactAnalysisService.analyze(plan).toString().contains("周容量未配置"));
        } finally {
            assertEquals(200, patch("/api/auth/users/1", admin, json(map("capacity_hours", previous))).status());
        }
    }

    @Test
    void aiPlanningChecksTheSameWeeklyCapacityAsMemberPage() throws Exception {
        String admin = token(USER_ADMIN);
        String otherTask = null;
        for (JsonNode task : get("/api/tasks", admin).json())
            if (task.path("owner_id").asInt() != 1) { otherTask = task.path("id").asText(); break; }
        assertNotNull(otherTask);
        var plan = objectMapper.createObjectNode().put("summary", "核对容量");
        plan.putArray("actions").addObject().put("type", "UPDATE_TASK_OWNER")
                .put("task_id", otherTask).put("to_owner_id", 1);
        JsonNode validated = planningValidator.validate(plan.toString());
        assertTrue(validated.path("warnings").toString().contains("周容量 10.0h"), validated.toString());
        assertEquals(10.0, planningAgentTools.execute("get_member_load",
                objectMapper.createObjectNode().put("owner_id", 1)).path("weekly_capacity_hours").asDouble());
    }

    @Test
    @Transactional
    void planningAgentReadsWeeklyCapacityBeforeProposingAnOverloadedAssignment() throws Exception {
        String admin = token(USER_ADMIN);
        JsonNode chosen = null;
        for (JsonNode task : get("/api/tasks", admin).json())
            if (task.path("owner_id").asInt() != 1) { chosen = task; break; }
        assertNotNull(chosen);
        String taskId = chosen.path("id").asText();
        int originalOwner = chosen.path("owner_id").asInt();

        ModelClient client = mock(ModelClient.class);
        var toolCall = objectMapper.createObjectNode().put("role", "assistant");
        toolCall.putArray("tool_calls").addObject().put("id", "capacity-read")
                .putObject("function").put("name", "get_member_load")
                .put("arguments", "{\"owner_id\":1}");
        var noTools = objectMapper.createObjectNode().put("role", "assistant").put("content", "已读取容量");
        var result = objectMapper.createObjectNode().put("summary", "调整负责人");
        result.putArray("actions").addObject().put("type", "UPDATE_TASK_OWNER")
                .put("task_id", taskId).put("to_owner_id", 1);
        var finalMessage = objectMapper.createObjectNode().put("role", "assistant")
                .put("content", result.toString());
        assertEquals("get_member_load", toolCall.at("/tool_calls/0/function/name").asText());
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> new ModelClient.Completion(
                invocation.getArgument(2, Boolean.class) ? finalMessage
                        : rounds.getAndIncrement() == 0 ? toolCall : noTools,
                java.util.Map.of())).when(client).complete(anyList(), anyList(), anyBoolean());

        var plan = planningRunner.analyze("把任务分配给管理员并核对容量", client);
        assertTrue(plan.path("requires_confirmation").asBoolean(), plan.toString());
        assertTrue(plan.path("warnings").toString().contains("周容量 10.0h"), plan.toString());
        assertEquals(originalOwner, taskOwner(admin, taskId));

        var messages = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(client).complete(messages.capture(), anyList(), eq(true));
        assertTrue(messages.getValue().stream().anyMatch(message ->
                message.toString().contains("weekly_capacity_hours") && message.toString().contains("10.0")),
                messages.getValue().toString());

        var run = new PlanningAgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setRequestedBy(1);
        run.setRequestText("把任务分配给管理员并核对容量");
        run.setStatus("waiting_confirmation");
        run.setAttempt(1);
        run.setModel("fixture");
        run.setPromptVersion("planning-owner-v1");
        run.setResultJson(plan.toString());
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        planningRuns.insert(run);
        assertEquals(originalOwner, taskMapper.selectById(taskId).getOwnerId());
        assertEquals("completed", planningService.confirm(run.getId(), userMapper.selectById(1)).get("status"));
        assertEquals(1, taskMapper.selectById(taskId).getOwnerId());
    }

    private int taskOwner(String token, String taskId) {
        for (JsonNode task : get("/api/tasks", token).json())
            if (taskId.equals(task.path("id").asText())) return task.path("owner_id").asInt();
        throw new AssertionError("任务不存在: " + taskId);
    }

    @Test
    void customRoleCanBeCreatedAssignedAndEnforced() {
        String admin = token(USER_ADMIN);
        String name = "r" + Long.toString(System.nanoTime(), 36);
        ApiResponse created = post("/api/roles", admin, json(map("name", name, "can_write", false)));
        assertEquals(200, created.status(), created.body());
        try {
            assertEquals(200, patch("/api/auth/users/5", admin, json(map("role", name))).status());
            String member = token(USER_VIEWER);
            assertEquals(403, post("/api/pool", member, json(map("title", "denied"))).status());
            assertEquals(409, delete("/api/roles/" + name, admin).status());
            assertEquals(200, patch("/api/roles/" + name, admin, json(map("can_write", true))).status());
            ApiResponse made = post("/api/pool", member, json(map("title", uniq("allowed"))));
            assertEquals(200, made.status(), made.body());
            delete("/api/pool/" + made.json().path("id").asText(), admin);
        } finally {
            patch("/api/auth/users/5", admin, json(map("role", "viewer")));
            delete("/api/roles/" + name, admin);
        }
    }

    @Test
    void createsTaskAndMilestoneWithReadback() {
        String admin = token(USER_ADMIN);
        assertEquals(403, post("/api/tasks", token(USER_VIEWER), json(map("name", "forbidden"))).status());
        ApiResponse task = post("/api/tasks", admin, json(map("name", uniq("split"), "owner_id", 1,
                "hours", 3, "week_start", 1, "week_end", 2, "kanban_card_id", "US01", "story_ref", "US01")));
        assertEquals(200, task.status(), task.body());
        String taskId = task.json().path("id").asText();
        String milestoneId = null;
        try {
            assertTrue(get("/api/tasks", admin).json().toString().contains(taskId));
            ApiResponse milestone = post("/api/milestones", admin,
                    json(map("name", uniq("milestone"), "week", 2, "related_task_ids", taskId)));
            assertEquals(200, milestone.status(), milestone.body());
            milestoneId = milestone.json().path("id").asText();
            assertTrue(get("/api/milestones", admin).json().toString().contains(milestoneId));
            assertEquals(422, post("/api/milestones", admin, json(map("name", "bad", "week", 7))).status());
            assertEquals(400, post("/api/milestones", admin,
                    json(map("name", "too early", "week", 1, "related_task_ids", taskId))).status());
        } finally {
            if (milestoneId != null) delete("/api/milestones/" + milestoneId, admin);
            assertEquals(200, delete("/api/tasks/" + taskId, admin).status());
        }
    }

    @Test
    void dependencyCannotFinishAfterItsDependentTask() {
        String admin = token(USER_ADMIN);
        JsonNode before = get("/api/tasks", admin).json();
        assertEquals(400, patch("/api/tasks/T01", admin,
                json(map("depends_on", "T08"))).status());
        assertEquals(400, post("/api/tasks", admin,
                json(map("name", "错误前置排期", "owner_id", 1, "hours", 2,
                        "week_start", 1, "week_end", 1, "depends_on", "T08"))).status());
        assertEquals(400, patch("/api/tasks/T08", admin,
                json(map("week_end", 6))).status());
        assertEquals(before, get("/api/tasks", admin).json());
    }

    @Test
    void statusChangeHasPersistedHistory() {
        String admin = token(USER_ADMIN);
        JsonNode tasks = get("/api/tasks", admin).json();
        JsonNode original = null;
        for (JsonNode task : tasks) if ("T01".equals(task.path("id").asText())) original = task;
        assertNotNull(original);
        int oldStatus = original.path("status").asInt();
        int oldProgress = original.path("progress").asInt();
        int changed = oldStatus == 1 ? 2 : 1;
        try {
            assertEquals(200, patch("/api/tasks/T01", admin, json(map("status", changed))).status());
            JsonNode history = get("/api/tasks/T01/history", admin).json();
            assertTrue(history.size() > 0);
            JsonNode latest = history.get(history.size() - 1);
            assertEquals(oldStatus, latest.path("old_status").asInt());
            assertEquals(changed, latest.path("new_status").asInt());
            assertTrue(latest.hasNonNull("created_at"));
        } finally {
            patch("/api/tasks/T01", admin, json(map("status", oldStatus, "progress", oldProgress)));
        }
    }

    @Test
    void storyKeepsRoleGoalAndValueAsSeparateFields() {
        String admin = token(USER_ADMIN);
        ApiResponse made = post("/api/stories", admin, json(map("title", uniq("story-parts"),
                "description", "作为测试者，我希望查阅记录，以便核对结果", "role", "测试者",
                "goal", "查阅记录", "value", "核对结果", "acceptance", "可以读取记录")));
        assertEquals(200, made.status(), made.body());
        String id = made.json().path("id").asText();
        try {
            assertEquals("测试者", made.json().path("role").asText());
            assertEquals("查阅记录", made.json().path("goal").asText());
            assertEquals("核对结果", made.json().path("value").asText());
            assertTrue(get("/api/stories", admin).json().toString().contains("核对结果"));
        } finally {
            delete("/api/stories/" + id + "?undone=keep", admin);
        }
    }

    @Test
    void dashboardReportsLatestServerChangeAndExcludesPoolFromStoryTotal() {
        String admin = token(USER_ADMIN);
        int before = get("/api/dashboard", admin).json().path("total").asInt();
        ApiResponse made = post("/api/pool", admin, json(map("title", uniq("dashboard-pool"))));
        assertEquals(200, made.status(), made.body());
        try {
            JsonNode dashboard = get("/api/dashboard", admin).json();
            assertEquals(before, dashboard.path("total").asInt());
            assertTrue(dashboard.hasNonNull("last_updated_at"), dashboard.toString());
        } finally {
            delete("/api/pool/" + made.json().path("id").asText(), admin);
        }
    }
}
