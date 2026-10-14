package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** Current multi-project story/epic API contract, run only against aicap_java_test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class EpicStoryProjectContractTest extends ContractTestSupport {
    private static final String PROJECT = "P002";
    private static final String PROJECT_QUERY = "?projectId=" + PROJECT;

    @Autowired JdbcTemplate jdbc;

    @Test
    void storyFieldsAndEpicLifecycle() {
        String admin = token(USER_ADMIN);
        ApiResponse createdEpic = post("/api/epics" + PROJECT_QUERY, admin,
                json(map("name", uniq("交付史诗"), "description", "测试归属")));
        assertStatus(createdEpic, 200);
        long epicId = createdEpic.json().path("id").asLong();
        String storyId = null;
        try {
            ApiResponse story = post("/api/stories" + PROJECT_QUERY, admin,
                    json(map("title", uniq("故事字段"), "description", "作为负责人，我希望验收字段，以便确认持久化",
                            "role", "负责人", "goal", "验收字段", "value", "确认持久化",
                            "acceptance", "字段可查询", "epic_id", epicId, "owner_id", 1)));
            assertStatus(story, 200);
            storyId = story.json().path("id").asText();
            JsonNode listed = findStory(admin, storyId);
            assertNotNull(listed);
            assertEquals("负责人", listed.path("role").asText());
            assertEquals("验收字段", listed.path("goal").asText());
            assertEquals("确认持久化", listed.path("value").asText());
            assertEquals(epicId, listed.path("epic_id").asLong());

            assertStatus(delete("/api/epics/" + epicId + PROJECT_QUERY, admin), 409);
            ApiResponse cleared = patch("/api/stories/" + storyId + PROJECT_QUERY, admin,
                    json(map("epic_id", null, "role", "项目负责人")));
            assertStatus(cleared, 200);
            assertTrue(cleared.json().path("epic_id").isNull());
            assertEquals("项目负责人", findStory(admin, storyId).path("role").asText());
            assertTrue(findStory(admin, storyId).path("epic_id").isNull());
        } finally {
            if (storyId != null) assertStatus(delete("/api/stories/" + storyId + "?undone=keep&projectId=" + PROJECT, admin), 200);
            assertStatus(delete("/api/epics/" + epicId + PROJECT_QUERY, admin), 200);
        }
    }

    @Test
    void epicAndStoryRejectCrossProjectAccess() {
        String admin = token(USER_ADMIN);
        jdbc.update("INSERT IGNORE INTO projects(id,name,description,status,total_sprints,created_at,updated_at) VALUES ('P099','测试隔离项目','','ACTIVE',1,NOW(),NOW())");
        jdbc.update("INSERT IGNORE INTO project_members(project_id,user_id,project_role,capacity_hours,joined_at) VALUES ('P099',1,'owner',60,NOW())");
        try {
            ApiResponse otherEpic = post("/api/epics?projectId=P099", admin, json(map("name", uniq("隔离史诗"))));
            assertStatus(otherEpic, 200);
            long otherId = otherEpic.json().path("id").asLong();
            try {
                ApiResponse story = post("/api/stories" + PROJECT_QUERY, admin,
                        json(map("title", uniq("跨项目故事"), "epic_id", otherId)));
                assertStatus(story, 400);
                assertStatus(get("/api/epics" + PROJECT_QUERY, token(USER_MEMBER)), 403);
                assertStatus(patch("/api/epics/" + otherId + PROJECT_QUERY, admin,
                        json(map("name", "越界修改"))), 404);
            } finally {
                assertStatus(delete("/api/epics/" + otherId + "?projectId=P099", admin), 200);
            }
        } finally {
            jdbc.update("DELETE FROM project_members WHERE project_id='P099'");
            jdbc.update("DELETE FROM projects WHERE id='P099'");
        }
    }

    private JsonNode findStory(String token, String id) {
        ApiResponse list = get("/api/stories" + PROJECT_QUERY, token);
        assertStatus(list, 200);
        for (JsonNode story : list.json()) if (id.equals(story.path("id").asText())) return story;
        return null;
    }
}
