package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class ProjectAccessContractTest extends ContractTestSupport {
    @Autowired JdbcTemplate jdbc;

    private int selectedStatus(String path, String bearer, long projectId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.set("X-Project-Id", Long.toString(projectId));
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode().value();
    }

    private ApiResponse selected(HttpMethod method, String path, String bearer, long projectId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.set("X-Project-Id", Long.toString(projectId));
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        try {
            return new ApiResponse(response.getStatusCode().value(),
                    response.getBody() == null ? null : om.readTree(response.getBody()), response.getBody());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test
    void projectMembershipGatesMetadataAndAllWorkspaceRoutes() {
        String admin = token(USER_ADMIN);
        String member = token(USER_MEMBER);
        String outsider = token(USER_VIEWER);
        assertEquals(403, post("/api/projects", member, json(map("name", "denied"))).status());
        ApiResponse created = post("/api/projects", admin, json(map("name", uniq("隔离项目"))));
        assertEquals(200, created.status(), created.body());
        long id = created.json().path("id").asLong();
        try {
            assertEquals(403, patch("/api/projects/" + id, member, json(map("name", "越权修改"))).status());
            ApiResponse renamed = patch("/api/projects/" + id, admin,
                    json(map("name", "项目基本信息已修改", "description", "范围已确认")));
            assertEquals(200, renamed.status(), renamed.body());
            assertEquals("项目基本信息已修改", renamed.json().path("name").asText());
            assertEquals("范围已确认", get("/api/projects/" + id, admin).json().path("description").asText());
            assertEquals(422, patch("/api/projects/" + id, admin, json(map("name", " "))).status());
            assertEquals(403, get("/api/projects/" + id, member).status());
            assertEquals(403, get("/api/projects/" + id + "/members", outsider).status());
            assertEquals(403, selectedStatus("/api/stories", outsider, id));
            assertEquals(403, selectedStatus("/api/dashboard", outsider, id));

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(admin);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<JsonNode> added = rest.exchange("/api/projects/" + id + "/members/3",
                    HttpMethod.PUT, new HttpEntity<>(om.readTree("{\"member_role\":\"member\"}"), headers), JsonNode.class);
            assertEquals(200, added.getStatusCode().value());
            assertEquals(200, get("/api/projects/" + id, member).status());
            assertEquals(0, selected(HttpMethod.GET, "/api/stories", member, id, null).json().size());
            assertEquals(0, selected(HttpMethod.GET, "/api/tasks", member, id, null).json().size());
            assertEquals(0, selected(HttpMethod.GET, "/api/pool", member, id, null).json().size());
            assertEquals(0, selected(HttpMethod.GET, "/api/epics", member, id, null).json().size());
            long defaultEpic = get("/api/epics", admin).json().get(0).path("id").asLong();
            assertEquals(404, selected(HttpMethod.PATCH, "/api/epics/" + defaultEpic, admin, id,
                    json(map("name", "越权修改", "description", ""))).status());
            assertEquals(400, selected(HttpMethod.POST, "/api/stories", admin, id,
                    json(map("title", "越权史诗归属", "epic_id", defaultEpic))).status());
            ApiResponse ownEpic = selected(HttpMethod.POST, "/api/epics", admin, id,
                    json(map("name", "隔离史诗", "description", "当前项目")));
            assertEquals(200, ownEpic.status(), ownEpic.body());
            long ownEpicId = ownEpic.json().path("id").asLong();
            assertEquals(1, selected(HttpMethod.GET, "/api/epics", member, id, null).json().size());
            assertEquals(404, patch("/api/epics/" + ownEpicId, admin,
                    json(map("name", "越权修改", "description", ""))).status());
            assertEquals(200, selected(HttpMethod.DELETE, "/api/epics/" + ownEpicId, admin, id, null).status());
            assertEquals(0, selected(HttpMethod.GET, "/api/dashboard", member, id, null).json().path("total").asInt());
            assertEquals(2, selected(HttpMethod.GET, "/api/auth/users", member, id, null).json().size());
            assertEquals(403, selectedStatus("/api/stories", outsider, id));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            jdbc.update("DELETE FROM projects WHERE id=?", id);
        }
    }

    @Test
    void storyTaskPoolAndMilestoneRemainInsideTheirProject() {
        String admin = token(USER_ADMIN);
        ApiResponse project = post("/api/projects", admin, json(map("name", uniq("data-scope"))));
        assertEquals(200, project.status(), project.body());
        long id = project.json().path("id").asLong();
        try {
            ApiResponse story = selected(HttpMethod.POST, "/api/stories", admin, id,
                    json(map("title", "项目内故事", "sprint", 1, "activity", 2, "status", 0, "owner_id", 1)));
            assertEquals(200, story.status(), story.body());
            String storyId = story.json().path("id").asText();
            assertEquals(400, selected(HttpMethod.PATCH, "/api/stories/" + storyId, admin, id,
                    json(map("owner_id", 5))).status());
            assertFalse(get("/api/stories", admin).body().contains("项目内故事"));
            assertEquals(404, patch("/api/stories/" + storyId, admin, json(map("title", "越权"))).status());
            assertEquals(200, selected(HttpMethod.GET, "/api/stories", admin, id, null).status());

            ApiResponse task = selected(HttpMethod.POST, "/api/tasks", admin, id,
                    json(map("name", "项目内任务", "owner_id", 1, "hours", 2, "week_start", 1,
                            "week_end", 1, "kanban_card_id", storyId, "story_ref", storyId)));
            assertEquals(200, task.status(), task.body());
            String taskId = task.json().path("id").asText();
            assertFalse(get("/api/tasks", admin).body().contains("项目内任务"));
            assertEquals(404, patch("/api/tasks/" + taskId, admin, json(map("name", "越权"))).status());
            assertEquals(400, selected(HttpMethod.PATCH, "/api/tasks/" + taskId, admin, id,
                    json(map("depends_on", "T01"))).status());
            assertEquals(400, selected(HttpMethod.PATCH, "/api/tasks/" + taskId, admin, id,
                    json(map("story_ref", "US01"))).status());
            assertEquals(400, selected(HttpMethod.POST, "/api/tasks", admin, id,
                    json(map("name", "越权挂卡", "owner_id", 1, "hours", 1, "week_start", 1,
                            "week_end", 1, "kanban_card_id", "US01"))).status());

            ApiResponse pool = selected(HttpMethod.POST, "/api/pool", admin, id,
                    json(map("title", "项目内需求", "source", "test")));
            assertEquals(200, pool.status(), pool.body());
            assertFalse(get("/api/pool", admin).body().contains("项目内需求"));

            ApiResponse milestone = selected(HttpMethod.POST, "/api/milestones", admin, id,
                    json(map("name", "项目内里程碑", "week", 1, "related_task_ids", taskId)));
            assertEquals(200, milestone.status(), milestone.body());
            assertFalse(get("/api/milestones", admin).body().contains("项目内里程碑"));
            assertEquals(404, delete("/api/milestones/" + milestone.json().path("id").asText(), admin).status());
            assertEquals(1, selected(HttpMethod.GET, "/api/dashboard", admin, id, null).json().path("total").asInt());
        } finally {
            jdbc.update("DELETE FROM task_logs WHERE project_id=?", id);
            jdbc.update("DELETE FROM tasks WHERE project_id=?", id);
            jdbc.update("DELETE FROM story_logs WHERE project_id=?", id);
            jdbc.update("DELETE FROM stories WHERE project_id=?", id);
            jdbc.update("DELETE FROM pool_items WHERE project_id=?", id);
            jdbc.update("DELETE FROM milestones WHERE project_id=?", id);
            jdbc.update("DELETE FROM projects WHERE id=?", id);
        }
    }

    @Test
    void removingLegacyMemberBlocksUnscopedWorkspaceAndAiRoutes() {
        String viewer = token(USER_VIEWER);
        jdbc.update("DELETE FROM project_members WHERE project_id=1 AND user_id=5");
        try {
            assertEquals(403, get("/api/stories", viewer).status());
            assertEquals(403, get("/api/tasks", viewer).status());
            assertEquals(403, get("/api/pool", viewer).status());
            assertEquals(403, get("/api/meetings", viewer).status());
            assertEquals(403, get("/api/auth/users", viewer).status());
            assertEquals(200, get("/api/auth/me", viewer).status());
            assertEquals(0, get("/api/projects", viewer).json().size());
        } finally {
            jdbc.update("INSERT IGNORE INTO project_members(project_id,user_id,member_role) VALUES (1,5,'member')");
        }
    }
}
