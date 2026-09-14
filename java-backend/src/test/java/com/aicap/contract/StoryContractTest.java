package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
            // 字段名契约:时间戳必须走 snake_case。此前 LogOut.createdAt 漏写 @JsonProperty,
            // 项目又没配全局命名策略 → 实际输出 createdAt,前端读 l.created_at 得 undefined,
            // 在线模式下变更记录面板时间列全空(离线走本机时间,掩盖了该缺陷)。
            for (JsonNode l : logs.json()) {
                assertTrue(l.has("created_at"), "日志必须输出 snake_case 的 created_at: " + l);
                assertFalse(l.has("createdAt"), "日志不得泄漏 camelCase 的 createdAt: " + l);
                assertTrue(l.path("created_at").asText().length() >= 19,
                        "created_at 应为 ISO 本地日期时间(如 2026-09-14T15:32:47): " + l.path("created_at").asText());
            }
        } finally {
            deleteStory(id);
        }
    }

    /**
     * 并发创建:只允许「全部成功且编号互不相同」或「明确 409」,**不得出现 500**。
     *
     * <p>编号来自「扫描当前最大号 +1」({@code com.aicap.service.IdAllocator}),并发下多个请求
     * 可能算出同一个号;{@code stories.id} 是 varchar 主键,后者插入即冲突。修复前该冲突落到
     * {@code GlobalExceptionHandler} 的兜底分支,客户端拿到 **500「服务器内部错误」**。
     *
     * <p>本用例钉住的**保证边界**(刻意不断言"全部成功" —— 那由「扫描最大值」这一分配方式
     * 不提供,强断言会 flaky):<br>
     * ① 任何响应都不得是 500;② 非成功响应只能是 409;③ 成功创建的编号互不相同。
     *
     * <p>彻底消除冲突需要把编号来源换成单调计数器(独立计数表 + 原子 UPDATE)或数据库序列,
     * 属未做的架构改动,详见 {@code IdAllocator} 类尾注释。
     */
    @Test
    void createStory_concurrently_never500_andIdsDistinct() throws Exception {
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiResponse>> futures = new ArrayList<>();
        List<String> created = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                String title = uniq("CT并发故事");
                futures.add(pool.submit(() -> {
                    start.await();
                    return post("/api/stories", token(USER_ADMIN), json(map("title", title)));
                }));
            }
            // 同时起跑,把「算出同一个号」的窗口放到最大
            start.countDown();

            int conflicts = 0;
            for (Future<ApiResponse> f : futures) {
                ApiResponse r = f.get(60, TimeUnit.SECONDS);
                assertNotEquals(500, r.status(), "并发创建不得返回 500: " + r.body());
                if (r.status() == 200) {
                    created.add(r.json().path("id").asText());
                } else {
                    assertEquals(409, r.status(), "非 200 的响应只允许是 409(编号冲突): " + r.body());
                    assertTrue(r.body().contains("detail"), "409 必须带可操作的 detail: " + r.body());
                    conflicts++;
                }
            }
            assertTrue(created.size() >= 1, "并发创建至少应有一条成功: created=" + created + " conflicts=" + conflicts);
            assertEquals(created.size(), new HashSet<>(created).size(),
                    "成功创建的编号必须互不相同(否则就是编号重复落库): " + created);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            for (String id : created) {
                deleteStory(id);
            }
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
