package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 当前项目基线：P002 是空项目，旧 P001 演示故事和任务不再自动播种。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class BaselineContractTest extends ContractTestSupport {
    private static final String PROJECT = "?projectId=P002";

    @Test
    void scopedCollections_andDashboard_startEmpty() {
        String admin = token(USER_ADMIN);
        for (String path : new String[]{"/api/stories", "/api/tasks", "/api/pool"}) {
            ApiResponse response = get(path + PROJECT, admin);
            assertStatus(response, 200);
            assertTrue(response.json().isArray(), path + " 应返回数组");
            assertEquals(0, response.json().size(), path + " 不应重建已删除的 P001 演示基线");
        }
        ApiResponse dashboard = get("/api/dashboard" + PROJECT, admin);
        assertStatus(dashboard, 200);
        assertEquals(0, dashboard.json().path("total").asInt());
        assertEquals(4, dashboard.json().path("by_sprint").size());
    }

    @Test
    void projectContext_isRequiredAndUnknownProjectRejected() {
        String admin = token(USER_ADMIN);
        for (String path : new String[]{"/api/stories", "/api/tasks", "/api/pool", "/api/dashboard"}) {
            assertStatus(get(path, admin), 422);
            assertStatus(get(path + "?projectId=P099", admin), 404);
        }
    }

    @Test
    void storyChanges_areVisibleOnlyInsideScopedDashboard() {
        String admin = token(USER_ADMIN);
        String title = uniq("基线项目故事");
        ApiResponse created = post("/api/stories" + PROJECT, admin,
                json(map("title", title, "sprint", 4, "activity", 2)));
        assertStatus(created, 200);
        String id = created.json().path("id").asText();
        assertFalse(id.isBlank());
        try {
            ApiResponse stories = get("/api/stories" + PROJECT, admin);
            assertStatus(stories, 200);
            JsonNode found = null;
            for (JsonNode story : stories.json()) {
                if (id.equals(story.path("id").asText())) found = story;
            }
            assertNotNull(found);
            assertEquals(title, found.path("title").asText());
            ApiResponse dashboard = get("/api/dashboard" + PROJECT, admin);
            assertStatus(dashboard, 200);
            assertEquals(stories.json().size(), dashboard.json().path("total").asInt());
            assertTrue(dashboard.json().path("by_sprint").get(3).path("total").asInt() >= 1);
        } finally {
            assertStatus(delete("/api/stories/" + id + "?undone=keep&projectId=P002", admin), 200);
        }
    }
}
