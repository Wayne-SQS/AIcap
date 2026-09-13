package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求池契约:
 * - POST /api/pool 缺 title → 422(JB-07)、title>200 → 422(JB-02)、priority 非法 → 422(JB-03);
 * - 创建成功 200、DELETE 200 后列表消失、DELETE 不存在 → 404;
 * - 路由观察:GET /api/pool/{id} 不存在 → 405(JB-01);PATCH /api/pool/{id} 亦无映射 → 405;
 * - promote(需求池→看板卡)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoolContractTest extends ContractTestSupport {

    private String createPoolReturnId(String title) {
        ApiResponse r = post("/api/pool", token(USER_ADMIN),
                json(map("title", title, "description", "契约测试需求", "priority", "Should")));
        assertEquals(200, r.status(), r.body());
        return r.json().path("id").asText();
    }

    @Test
    void createPool_ok_200_andAppearsInList() {
        String title = uniq("CT池创建");
        String id = null;
        try {
            id = createPoolReturnId(title);
            assertTrue(id.startsWith("R"), "池条目 id 应为 R 编号: " + id);
            ApiResponse list = get("/api/pool", token(USER_ADMIN));
            boolean present = false;
            for (JsonNode it : list.json()) {
                if (id.equals(it.path("id").asText()) && title.equals(it.path("title").asText())) {
                    present = true;
                }
            }
            assertTrue(present, "创建后应出现在 GET /api/pool: " + list.body());
        } finally {
            if (id != null) {
                deletePoolItem(id);
            }
        }
    }

    @Test
    void createPool_missingTitle_422() {
        ApiResponse r = post("/api/pool", token(USER_ADMIN),
                json(map("description", "x", "priority", "Should")));
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void createPool_titleTooLong_422() {
        ApiResponse r = post("/api/pool", token(USER_ADMIN),
                json(map("title", "长".repeat(201), "priority", "Should")));
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void createPool_invalidPriority_422() {
        ApiResponse r = post("/api/pool", token(USER_ADMIN),
                json(map("title", uniq("CT坏优先级"), "priority", "Whenever")));
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void deletePool_existing_ok_goneFromList() {
        String title = uniq("CT池待删");
        String id = createPoolReturnId(title);
        ApiResponse del = delete("/api/pool/" + id, token(USER_ADMIN));
        assertEquals(200, del.status(), del.body());
        assertEquals(true, del.json().path("ok").asBoolean());
        ApiResponse list = get("/api/pool", token(USER_ADMIN));
        for (JsonNode it : list.json()) {
            assertTrue(!id.equals(it.path("id").asText()), "删除后不应再出现 " + id);
        }
    }

    @Test
    void deletePool_notFound_404() {
        ApiResponse r = delete("/api/pool/R99", token(USER_ADMIN));
        assertEquals(404, r.status(), r.body());
        assertTrue(r.json().has("detail"), "404 应为 {\"detail\":...}: " + r.body());
    }

    @Test
    void getSinglePool_405_methodNotSupported() {
        // 路径 /api/pool/{poolId} 仅有 DELETE,GET 单条无映射 → 405(JB-01)
        ApiResponse r = get("/api/pool/R99", token(USER_ADMIN));
        assertEquals(405, r.status(), r.body());
        assertNotNull(r.json());
        assertTrue(r.json().has("detail"), "405 应为 {\"detail\":...}: " + r.body());
    }

    @Test
    void patchSinglePool_405_noPatchMapping() {
        // 观察:Java 版无 PATCH /api/pool/{poolId}(FastAPI 契约有 PATCH+DELETE)→ 405
        ApiResponse r = patch("/api/pool/R99", token(USER_ADMIN),
                json(map("title", "改标题")));
        assertEquals(405, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void promotePoolToStory_ok() {
        String title = uniq("CT池提升");
        String poolId = null;
        String storyId = null;
        try {
            poolId = createPoolReturnId(title);
            String promotedPoolId = poolId;
            ApiResponse r = post("/api/pool/" + promotedPoolId + "/promote", token(USER_ADMIN),
                    json(map("sprint", 2, "activity", 1, "owner_id", 1)));
            assertEquals(200, r.status(), r.body());
            // promote 成功即 consume 掉池条目:先置空,避免后续断言失败时 finally 误删成 404 掩盖真实失败
            poolId = null;
            storyId = r.json().path("id").asText();
            assertTrue(storyId.matches("^US\\d+$"),
                    "promote 应生成 US 命名空间故事(不再产生 M 编号): " + storyId);
            assertTrue(Integer.parseInt(storyId.substring(2)) > 37,
                    "promote 生成的故事 id 应大于种子基线 US37: " + storyId);
            assertNotNull(storyFromList(storyId), "promote 生成的故事应能在 GET /api/stories 复读到: " + storyId);
            assertEquals(title, r.json().path("title").asText());
            assertEquals(2, r.json().path("sprint").asInt());
            // 池条目已被 promote consume
            ApiResponse list = get("/api/pool", token(USER_ADMIN));
            for (JsonNode it : list.json()) {
                assertTrue(!promotedPoolId.equals(it.path("id").asText()), "promote 后池条目应消失");
            }
        } finally {
            if (poolId != null) {
                deletePoolItem(poolId);
            }
            if (storyId != null) {
                deleteStory(storyId);
            }
        }
    }
}
