package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多项目隔离契约:项目编号是 {@code P001} 这样的字符串,所有工作区路由都带 projectId。
 * 早期版本用数值项目号 + X-Project-Id 头,多项目改造后已废弃 —— 本类按现行口径重写。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class ProjectAccessContractTest extends ContractTestSupport {
    @Autowired JdbcTemplate jdbc;

    private int selectedStatus(String path, String bearer, String projectId) {
        return selected(HttpMethod.GET, path, bearer, projectId, null).status();
    }

    private ApiResponse selected(HttpMethod method, String path, String bearer, String projectId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        String scoped = projectId == null ? path
                : path + (path.contains("?") ? "&" : "?") + "projectId=" + projectId;
        ResponseEntity<String> response = rest.exchange(scoped, method, new HttpEntity<>(body, headers), String.class);
        try {
            return new ApiResponse(response.getStatusCode().value(),
                    response.getBody() == null ? null : om.readTree(response.getBody()), response.getBody());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    /** 清理一个临时项目(项目号是字符串,旧版按数值删除会撞 "Truncated incorrect DOUBLE") */
    private void purgeProject(String projectId) {
        jdbc.update("DELETE FROM task_story_relations WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM task_dependencies WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM milestone_tasks WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM task_logs WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM tasks WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM story_logs WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM stories WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM pool_items WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM milestones WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM epics WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM project_members WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM projects WHERE id=?", projectId);
    }

    @Test
    void projectMembershipGatesMetadataAndAllWorkspaceRoutes() {
        String admin = token(USER_ADMIN);
        String member = token(USER_MEMBER);
        String outsider = token(USER_VIEWER);
        // 任何可写角色都能新建项目并成为负责人(前端 ProjectsView 对所有人显示「+ 新建项目」)
        ApiResponse created = post("/api/projects", member, json(map("name", uniq("隔离项目"))));
        assertEquals(200, created.status(), created.body());
        String id = created.json().path("id").asText();
        assertTrue(id.matches("P\\d{3}"), "项目编号应为 Pxxx: " + id);
        try {
            // 非项目成员不能改/读项目元数据
            assertEquals(403, patch("/api/projects/" + id, admin, json(map("name", "越权修改"))).status());
            ApiResponse renamed = patch("/api/projects/" + id, member,
                    json(map("name", "项目基本信息已修改", "description", "范围已确认")));
            assertEquals(200, renamed.status(), renamed.body());
            assertEquals("项目基本信息已修改", renamed.json().path("name").asText());
            assertEquals("范围已确认", get("/api/projects/" + id, member).json().path("description").asText());
            assertEquals(422, patch("/api/projects/" + id, member, json(map("name", " "))).status());
            assertEquals(403, get("/api/projects/" + id, outsider).status());
            assertEquals(403, get("/api/projects/" + id + "/members", outsider).status());
            assertEquals(403, selectedStatus("/api/stories", outsider, id));
            assertEquals(403, selectedStatus("/api/dashboard", outsider, id));

            // 负责人把 admin 加为成员后,项目元数据才对 admin 可见
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(member);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<JsonNode> added = rest.exchange("/api/projects/" + id + "/members/1",
                    HttpMethod.PUT, new HttpEntity<>(om.readTree("{\"member_role\":\"member\"}"), headers), JsonNode.class);
            assertEquals(200, added.getStatusCode().value());
            assertEquals(200, get("/api/projects/" + id, admin).status());

            // 新项目内四个工作区都是空的
            assertEquals(0, selected(HttpMethod.GET, "/api/stories", member, id, null).json().size());
            assertEquals(0, selected(HttpMethod.GET, "/api/tasks", member, id, null).json().size());
            assertEquals(0, selected(HttpMethod.GET, "/api/pool", member, id, null).json().size());
            assertEquals(0, selected(HttpMethod.GET, "/api/epics", member, id, null).json().size());
            assertEquals(0, selected(HttpMethod.GET, "/api/dashboard", member, id, null).json().path("total").asInt());

            // 跨项目资源:默认史诗属于 P001,在新项目里既不能改也不能挂
            long defaultEpic = get("/api/epics", admin).json().get(0).path("id").asLong();
            assertEquals(404, selected(HttpMethod.PATCH, "/api/epics/" + defaultEpic, member, id,
                    json(map("name", "越权修改", "description", ""))).status());
            assertEquals(400, selected(HttpMethod.POST, "/api/stories", member, id,
                    json(map("title", "越权史诗归属", "epic_id", defaultEpic))).status());
            ApiResponse ownEpic = selected(HttpMethod.POST, "/api/epics", member, id,
                    json(map("name", "隔离史诗", "description", "当前项目")));
            assertEquals(200, ownEpic.status(), ownEpic.body());
            long ownEpicId = ownEpic.json().path("id").asLong();
            assertEquals(1, selected(HttpMethod.GET, "/api/epics", member, id, null).json().size());
            // 拿 P001 的上下文去找这个新史诗 → 404(按项目隔离,不是按 id 全局命中)
            assertEquals(404, selected(HttpMethod.PATCH, "/api/epics/" + ownEpicId, admin, PROJECT_ID,
                    json(map("name", "越权修改", "description", ""))).status());
            assertEquals(200, selected(HttpMethod.DELETE, "/api/epics/" + ownEpicId, member, id, null).status());
            assertEquals(403, selectedStatus("/api/stories", outsider, id));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            purgeProject(id);
        }
    }

    @Test
    void storyTaskPoolAndMilestoneRemainInsideTheirProject() {
        String admin = token(USER_ADMIN);
        ApiResponse project = post("/api/projects", admin, json(map("name", uniq("data-scope"))));
        assertEquals(200, project.status(), project.body());
        String id = project.json().path("id").asText();
        try {
            ApiResponse story = selected(HttpMethod.POST, "/api/stories", admin, id,
                    json(map("title", "项目内故事", "sprint", 1, "activity", 2, "status", 0, "owner_id", 1)));
            assertEquals(200, story.status(), story.body());
            String storyId = story.json().path("id").asText();
            // 负责人必须是项目成员
            assertEquals(400, selected(HttpMethod.PATCH, "/api/stories/" + storyId, admin, id,
                    json(map("owner_id", 5))).status());
            assertFalse(get("/api/stories", admin).body().contains("项目内故事"));
            // 故事编号在项目内唯一:新项目同样从 US01 起步,不能按编号跨项目 404,
            // 但要能从当前项目读到它、且 P001 的列表里看不到它
            assertEquals(200, selected(HttpMethod.GET, "/api/stories", admin, id, null).status());

            ApiResponse task = selected(HttpMethod.POST, "/api/tasks", admin, id,
                    json(map("name", "项目内任务", "owner_id", 1, "hours", 2, "week_start", 1,
                            "week_end", 1, "kanban_card_id", storyId, "story_ref", storyId)));
            assertEquals(200, task.status(), task.body());
            String taskId = task.json().path("id").asText();
            assertFalse(get("/api/tasks", admin).body().contains("项目内任务"));
            assertEquals(400, selected(HttpMethod.PATCH, "/api/tasks/" + taskId, admin, id,
                    json(map("depends_on", taskId))).status());
            assertEquals(400, selected(HttpMethod.PATCH, "/api/tasks/" + taskId, admin, id,
                    json(map("story_ref", "US99"))).status());
            assertEquals(400, selected(HttpMethod.POST, "/api/tasks", admin, id,
                    json(map("name", "越权挂卡", "owner_id", 1, "hours", 1, "week_start", 1,
                            "week_end", 1, "kanban_card_id", "US99"))).status());

            ApiResponse pool = selected(HttpMethod.POST, "/api/pool", admin, id,
                    json(map("title", "项目内需求", "source", "test")));
            assertEquals(200, pool.status(), pool.body());
            assertFalse(get("/api/pool", admin).body().contains("项目内需求"));

            ApiResponse milestone = selected(HttpMethod.POST, "/api/milestones", admin, id,
                    json(map("id", "MS99", "name", "项目内里程碑", "week", 1, "related_task_ids", taskId)));
            assertEquals(200, milestone.status(), milestone.body());
            String milestoneId = milestone.json().path("id").asText();
            assertFalse(get("/api/milestones", admin).body().contains("项目内里程碑"));
            assertEquals(404, delete("/api/milestones/" + milestoneId, admin).status());
            assertEquals(1, selected(HttpMethod.GET, "/api/dashboard", admin, id, null).json().path("total").asInt());
        } finally {
            purgeProject(id);
        }
    }

    @Test
    void removingLegacyMemberBlocksUnscopedWorkspaceAndAiRoutes() {
        String viewer = token(USER_VIEWER);
        // 项目号是字符串:旧的 `project_id=1` 会把 'P001' 隐式转 DOUBLE 并报错
        jdbc.update("DELETE FROM project_members WHERE project_id='P001' AND user_id=5");
        try {
            assertEquals(403, selectedStatus("/api/stories", viewer, PROJECT_ID));
            assertEquals(403, selectedStatus("/api/tasks", viewer, PROJECT_ID));
            assertEquals(403, selectedStatus("/api/pool", viewer, PROJECT_ID));
            assertEquals(403, selectedStatus("/api/epics", viewer, PROJECT_ID));
            assertEquals(403, selectedStatus("/api/meetings", viewer, PROJECT_ID));
            assertEquals(200, get("/api/auth/me", viewer).status());
            // P001 已不可见(该账号仍在 P002,所以列表不是空,但不能出现 P001)
            for (JsonNode project : get("/api/projects", viewer).json())
                assertNotEquals("P001", project.path("id").asText());
        } finally {
            jdbc.update("INSERT IGNORE INTO project_members(project_id,user_id,project_role,joined_at)"
                    + " VALUES ('P001',5,'viewer',NOW())");
        }
    }
}
