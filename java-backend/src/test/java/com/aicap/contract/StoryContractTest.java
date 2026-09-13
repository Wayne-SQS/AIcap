package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故事契约:
 * - CUD:POST 创建(200)、PATCH 改状态并写 story_log、DELETE 删除;
 * - 校验:缺 title → 422、priority 非法 → 422、title 超长 → 422、owner 不存在 → 400;
 * - 删除策略 undone 非法值 → 400;不存在 id → 404;
 * - 路由观察:GET /api/stories/{id} 无该方法(GET 单条不存在)→ 405(实测后修正如需);
 * - 删除后 GET 单条同理 405(方法级),DELETE 后再列表不含该 id。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoryContractTest extends ContractTestSupport {

    private ApiResponse createStory(String title, String priority) {
        Map<String, Object> body = map("title", title);
        if (priority != null) {
            body.put("priority", priority);
        }
        body.put("owner_id", 1);
        return post("/api/stories", token(USER_ADMIN), json(body));
    }

    @Test
    void createStory_ok_200_withDefaultsAndUsId() {
        String title = uniq("CT故事创建");
        String id = null;
        try {
            ApiResponse r = createStory(title, null);
            assertEquals(200, r.status(), r.body());
            id = r.json().path("id").asText();
            assertTrue(id.matches("^US\\d+$"),
                    "新故事 id 应走 US 命名空间(US38 起,不再产生 M 编号): " + id);
            int num = Integer.parseInt(id.substring(2));
            assertTrue(num > 37, "新故事 id 应大于种子基线 US37: " + id);
            assertEquals(title, r.json().path("title").asText());
            // 新建故事必须真的落库可见
            assertNotNull(storyFromList(id), "新建故事应出现在 GET /api/stories: " + id);
        } finally {
            if (id != null) {
                deleteStory(id);
            }
        }
    }

    @Test
    void createStory_missingTitle_422() {
        ApiResponse r = post("/api/stories", token(USER_ADMIN),
                json(map("owner_id", 1, "status", 0, "sprint", 1, "activity", 2)));
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void createStory_invalidPriority_422() {
        String title = uniq("CT坏优先级");
        ApiResponse r = createStory(title, "Wont");
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void createStory_titleTooLong_422() {
        String longTitle = "很".repeat(201);
        ApiResponse r = createStory(longTitle, null);
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void createStory_ownerNotExist_400() {
        String title = uniq("CT坏负责人");
        ApiResponse r = post("/api/stories", token(USER_ADMIN),
                json(map("title", title, "owner_id", 99999)));
        assertEquals(400, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void patchStory_statusChangeAndLogVisible() {
        String title = uniq("CT日志故事");
        ApiResponse created = createStory(title, null);
        assertEquals(200, created.status(), created.body());
        String id = created.json().path("id").asText();
        int oldStatus = created.json().path("status").asInt();
        try {
            int newStatus = oldStatus == 0 ? 1 : 0;
            ApiResponse patched = patch("/api/stories/" + id, token(USER_ADMIN),
                    json(map("status", newStatus)));
            assertEquals(200, patched.status(), patched.body());
            assertEquals(newStatus, patched.json().path("status").asInt());
            // 日志可见
            ApiResponse logs = get("/api/stories/logs", token(USER_ADMIN));
            assertEquals(200, logs.status(), logs.body());
            boolean foundMove = false;
            for (JsonNode l : logs.json()) {
                if (id.equals(l.path("story_id").asText()) && "move".equals(l.path("log_type").asText())) {
                    foundMove = true;
                    break;
                }
            }
            assertTrue(foundMove, "PATCH 状态后应能看到 story_log(move): " + logs.body());
        } finally {
            deleteStory(id);
        }
    }

    @Test
    void patchStory_notFound_404() {
        ApiResponse r = patch("/api/stories/US99", token(USER_ADMIN), json(map("status", 1)));
        assertEquals(404, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void deleteStory_ok_removedFromList() {
        String title = uniq("CT待删故事");
        ApiResponse created = createStory(title, null);
        String id = created.json().path("id").asText();
        ApiResponse del = delete("/api/stories/" + id + "?undone=keep", token(USER_ADMIN));
        assertEquals(200, del.status(), del.body());
        assertEquals(true, del.json().path("ok").asBoolean());
        ApiResponse list = get("/api/stories", token(USER_ADMIN));
        for (JsonNode s : list.json()) {
            assertFalse(id.equals(s.path("id").asText()), "删除后列表中不应再有 " + id);
        }
    }

    @Test
    void deleteStory_notFound_404() {
        ApiResponse r = delete("/api/stories/US99?undone=keep", token(USER_ADMIN));
        assertEquals(404, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void deleteStory_invalidUndone_400_andStoryKept() {
        String title = uniq("CT坏策略");
        ApiResponse created = createStory(title, null);
        String id = created.json().path("id").asText();
        try {
            ApiResponse r = delete("/api/stories/" + id + "?undone=explode", token(USER_ADMIN));
            assertEquals(400, r.status(), r.body());
            assertTrue(r.json().has("detail"));
            // 故事应仍在
            ApiResponse list = get("/api/stories", token(USER_ADMIN));
            boolean present = false;
            for (JsonNode s : list.json()) {
                if (id.equals(s.path("id").asText())) {
                    present = true;
                }
            }
            assertTrue(present, "undone 非法时不应删除故事");
        } finally {
            deleteStory(id);
        }
    }

    @Test
    void getSingleStoryRoute_405_methodNotSupported() {
        // StoryController 只有 PATCH/DELETE /{storyId},无 GET 单条 → 405(观察:无 GET 路由)
        ApiResponse r = get("/api/stories/US01", token(USER_ADMIN));
        assertEquals(405, r.status(), r.body());
        assertNotNull(r.json());
        assertTrue(r.json().has("detail"), "405 应为 {\"detail\":...}: " + r.body());
    }

    @Test
    void getSingleStory_afterDelete_still405_or404_measured() {
        // 契约基线要求"先读 controller 确认该路径存在哪些方法再断言":
        // 本实现 {storyId} 路径仅有 PATCH/DELETE → GET 属方法不支持(405)。
        String title = uniq("CT删除后GET");
        ApiResponse created = createStory(title, null);
        String id = created.json().path("id").asText();
        ApiResponse del = delete("/api/stories/" + id + "?undone=keep", token(USER_ADMIN));
        assertEquals(200, del.status(), del.body());
        ApiResponse r = get("/api/stories/" + id, token(USER_ADMIN));
        assertEquals(405, r.status(), "删除后 GET 单条应仍为方法级 405: " + r.body());
    }
}
