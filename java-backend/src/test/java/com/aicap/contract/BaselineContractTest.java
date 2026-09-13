package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基线契约(US01–US37 种子数据 + dashboard 口径):
 * - GET /api/stories → 37 条,ID 恰为 US01..US37(不再出现 M 编号),
 *   且 owner_id∈1..4、sprint∈1..4、activity∈1..5、status∈0..2;
 * - GET /api/tasks → 16 条 T01..T16,每条都带新字段
 *   depends_on / progress / blocked(JSON 布尔) / sprints(派生数组);
 * - 挂卡任务恰为 12 条开发任务(T03..T14),管理任务 T01/T02/T15/T16 不挂卡,
 *   且所有非空 kanban_card_id 都指向 USxx 且真实存在;
 * - GET /api/pool → 0 条;
 * - GET /api/dashboard → 200 且 total = todo+doing+done = Σ by_sprint。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BaselineContractTest extends ContractTestSupport {

    private static final Pattern US_ID = Pattern.compile("^US\\d{2}$");
    private static final Pattern T_ID = Pattern.compile("^T\\d{2}$");

    /** US01..US37 的期望集合 */
    private static Set<String> expectedStoryIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 1; i <= 37; i++) {
            ids.add(String.format("US%02d", i));
        }
        return ids;
    }

    @Test
    void seededStories_exactly37_allUsIdsAndFieldDomains() {
        ApiResponse r = get("/api/stories", token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());
        assertNotNull(r.json(), r.body());
        assertTrue(r.json().isArray(), "故事列表应为数组: " + r.body());
        assertEquals(37, r.json().size(), "故事基线应为 37 条(US01..US37): " + r.body());

        Set<String> actual = new LinkedHashSet<>();
        for (JsonNode s : r.json()) {
            String id = s.path("id").asText();
            assertTrue(US_ID.matcher(id).matches(), "故事 ID 必须为 USxx 形式(不得残留 M 编号): " + id);
            actual.add(id);

            int owner = s.path("owner_id").asInt(-1);
            assertTrue(owner >= 1 && owner <= 4, id + " owner_id 应在 1..4: " + owner);
            int sprint = s.path("sprint").asInt(-1);
            assertTrue(sprint >= 1 && sprint <= 4, id + " sprint 应在 1..4: " + sprint);
            int activity = s.path("activity").asInt(-1);
            assertTrue(activity >= 1 && activity <= 5, id + " activity 应在 1..5: " + activity);
            int status = s.path("status").asInt(-1);
            assertTrue(status >= 0 && status <= 2, id + " status 应在 0..2: " + status);
            String priority = s.path("priority").asText();
            assertTrue("Must".equals(priority) || "Should".equals(priority) || "Could".equals(priority),
                    id + " priority 非法: " + priority);
            assertTrue(!s.path("title").asText().isEmpty(), id + " title 不应为空");
        }
        assertEquals(expectedStoryIds(), actual, "故事 ID 集合应恰为 US01..US37: " + actual);
    }

    @Test
    void seededTasks_exactly16_withDependsOnProgressBlockedSprints() {
        ApiResponse r = get("/api/tasks", token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().isArray(), "任务列表应为数组: " + r.body());
        assertEquals(16, r.json().size(), "任务基线应为 16 条(T01..T16): " + r.body());

        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode t : r.json()) {
            String id = t.path("id").asText();
            assertTrue(T_ID.matcher(id).matches(), "任务 ID 必须为 Txx 形式: " + id);
            ids.add(id);

            // 新增字段必须存在(不裁剪 null/空)
            assertTrue(t.has("depends_on"), id + " 缺少 depends_on 字段: " + t);
            assertTrue(t.has("progress"), id + " 缺少 progress 字段: " + t);
            assertTrue(t.has("blocked"), id + " 缺少 blocked 字段: " + t);
            assertTrue(t.has("sprints"), id + " 缺少派生字段 sprints: " + t);
            assertTrue(t.has("estimated_hours"), id + " 缺少 estimated_hours 字段: " + t);
            assertTrue(t.has("task_type"), id + " 缺少 task_type 字段: " + t);
            assertTrue(t.has("story_ref"), id + " 缺少 story_ref 字段: " + t);
            assertTrue(t.has("kanban_card_id"), id + " 缺少 kanban_card_id 字段: " + t);

            // blocked 对外必须是 JSON 布尔(库中存 0/1)
            assertTrue(t.path("blocked").isBoolean(),
                    id + " blocked 输出必须是 JSON 布尔值,实际: " + t.path("blocked"));

            int progress = t.path("progress").asInt(-1);
            assertTrue(progress >= 0 && progress <= 100, id + " progress 应在 0..100: " + progress);

            int ws = t.path("week_start").asInt(-1);
            int we = t.path("week_end").asInt(-1);
            assertTrue(ws >= 1 && ws <= 6, id + " week_start 应在 1..6: " + ws);
            assertTrue(we >= 1 && we <= 6, id + " week_end 应在 1..6: " + we);
            assertTrue(ws <= we, id + " 周序颠倒: " + ws + ".." + we);

            assertTrue(t.path("sprints").isArray(), id + " sprints 应为数组: " + t.path("sprints"));
            for (JsonNode sp : t.path("sprints")) {
                int v = sp.asInt(-1);
                assertTrue(v >= 1 && v <= 3,
                        id + " sprints 元素应由 week_start..week_end 派生(每 2 周一档,1..3): " + t.path("sprints"));
            }
        }
        Set<String> expected = new LinkedHashSet<>();
        for (int i = 1; i <= 16; i++) {
            expected.add(String.format("T%02d", i));
        }
        assertEquals(expected, ids, "任务 ID 集合应恰为 T01..T16: " + ids);
    }

    @Test
    void seededTaskKanbanCards_pointToUsStories() {
        ApiResponse stories = get("/api/stories", token(USER_ADMIN));
        Set<String> storyIds = new LinkedHashSet<>();
        for (JsonNode s : stories.json()) {
            storyIds.add(s.path("id").asText());
        }

        // 新基线:12 条开发任务挂到 USxx 看板卡(T01/T02/T15/T16 为管理任务,不挂卡)
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("T03", "US01");
        expected.put("T04", "US03");
        expected.put("T05", "US07");
        expected.put("T06", "US25");
        expected.put("T07", "US10");
        expected.put("T08", "US13");
        expected.put("T09", "US08");
        expected.put("T10", "US29");
        expected.put("T11", "US31");
        expected.put("T12", "US14");
        expected.put("T13", "US16");
        expected.put("T14", "US34");

        ApiResponse r = get("/api/tasks", token(USER_ADMIN));
        Map<String, String> actual = new LinkedHashMap<>();
        for (JsonNode t : r.json()) {
            String id = t.path("id").asText();
            JsonNode card = t.path("kanban_card_id");
            if (card.isNull()) {
                continue;
            }
            String cardId = card.asText();
            assertTrue(US_ID.matcher(cardId).matches(),
                    id + " 的 kanban_card_id 必须指向 USxx(不得出现 M 编号): " + cardId);
            assertTrue(storyIds.contains(cardId),
                    id + " 的 kanban_card_id " + cardId + " 必须真实存在于故事列表");
            actual.put(id, cardId);
        }
        assertEquals(expected, actual, "挂卡映射应为新基线(T03→US01 … T14→US34,管理任务不挂卡): " + r.body());

        for (String management : List.of("T01", "T02", "T15", "T16")) {
            for (JsonNode t : r.json()) {
                if (management.equals(t.path("id").asText())) {
                    assertTrue(t.path("kanban_card_id").isNull(),
                            management + " 是管理任务,kanban_card_id 应为 null: " + t);
                    assertEquals("management", t.path("task_type").asText(),
                            management + " 的 task_type 应为 management: " + t);
                }
            }
        }
    }

    @Test
    void pool_empty0() {
        ApiResponse r = get("/api/pool", token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().isArray());
        assertEquals(0, r.json().size(), "需求池基线应为空: " + r.body());
    }

    @Test
    void dashboard_200_andSelfConsistent() {
        ApiResponse r = get("/api/dashboard", token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());
        JsonNode d = r.json();
        assertNotNull(d);
        assertTrue(d.has("total") && d.has("todo") && d.has("doing") && d.has("done")
                && d.has("percent") && d.has("by_sprint"), "dashboard 字段缺失: " + r.body());
        long total = d.path("total").asLong();
        long sum = d.path("todo").asLong() + d.path("doing").asLong() + d.path("done").asLong();
        assertEquals(total, sum, "dashboard total 应等于 todo+doing+done(口径自洽)");

        // by_sprint 枚举 Sprint 1..4(对齐 FastAPI dashboard.py `for sp in (1, 2, 3, 4)`):
        // 新基线含 3 条 sprint=4 的"后续路线"故事(US09/US15/US21),合计必须等于 total。
        // 曾因循环上限写成 3 而漏掉 Sprint 4(见缺陷报告 D1,已修)。
        ApiResponse stories = get("/api/stories", token(USER_ADMIN));
        long sprintSum = 0;
        int bucket = 0;
        for (JsonNode sp : d.path("by_sprint")) {
            bucket++;
            assertEquals(bucket, sp.path("sprint").asInt(-1), "by_sprint 应按 Sprint 1..4 顺序输出: " + r.body());
            sprintSum += sp.path("total").asLong();
        }
        assertEquals(4, bucket, "by_sprint 应恰好包含 Sprint 1..4: " + r.body());
        assertEquals(total, sprintSum, "by_sprint 合计应等于 total(" + total + "): " + r.body());
        assertEquals(3, d.path("by_sprint").get(3).path("total").asLong(),
                "Sprint 4 应恰好 3 条(US09/US15/US21): " + r.body());

        // 与故事列表口径一致
        assertEquals(stories.json().size(), total, "dashboard total 应与故事列表数一致");
        assertEquals(37, total, "dashboard total 应为新基线 37: " + r.body());
    }

    @Test
    void sprintRange_allowsSprint4_onCreateAndPatch() {
        // D2 回归:sprint 上限曾是 3,导致基线里 sprint=4 的故事无法通过接口创建/复现
        // (对齐 FastAPI schemas:StoryIn/StoryPatch/PoolPromoteIn 均为 ge=1, le=4)
        ApiResponse created = post("/api/stories", token(USER_ADMIN),
                json(map("title", "QA sprint4 临时卡", "sprint", 4, "activity", 2)));
        assertEquals(200, created.status(), "sprint=4 应被接受: " + created.body());
        String id = created.json().path("id").asText();
        assertTrue(id.matches("^US\\d+$"), "新建卡应为 US 命名空间: " + id);
        try {
            assertEquals(4, created.json().path("sprint").asInt(-1), "新建卡的 sprint 应为 4: " + created.body());
            ApiResponse reread = get("/api/stories", token(USER_ADMIN));
            JsonNode created2 = null;
            for (JsonNode s : reread.json()) {
                if (id.equals(s.path("id").asText())) {
                    created2 = s;
                }
            }
            assertNotNull(created2, "新建的 sprint=4 卡应能在列表中复读: " + reread.body());
            assertEquals(4, created2.path("sprint").asInt(-1), "复读 sprint 应为 4");

            ApiResponse patched = patch("/api/stories/" + id, token(USER_ADMIN), json(map("sprint", 4)));
            assertEquals(200, patched.status(), "幂等回写 sprint=4 应被接受: " + patched.body());
            ApiResponse outOfRange = patch("/api/stories/" + id, token(USER_ADMIN), json(map("sprint", 5)));
            assertEquals(422, outOfRange.status(), "sprint=5 应被拒: " + outOfRange.body());
        } finally {
            delete("/api/stories/" + id + "?undone=keep", token(USER_ADMIN));
        }
    }

    @Test
    void singleSeededStoryLookup_byIdInList_notMNamespace() {
        // 旧基线用 M01 定位种子卡;新基线必须用 US01,且单条 GET 路由仍不存在(405)
        JsonNode us01 = storyFromList("US01");
        assertNotNull(us01, "种子故事 US01 应存在");
        assertEquals("项目与成员范围", us01.path("title").asText(), "US01 标题应为新基线值: " + us01);
        assertNull(storyFromList("M01"), "旧 M 编号不得再存在于故事列表中");
        ApiResponse g = get("/api/stories/US01", token(USER_ADMIN));
        assertEquals(405, g.status(), "GET 单条故事无映射 → 405: " + g.body());
    }
}
