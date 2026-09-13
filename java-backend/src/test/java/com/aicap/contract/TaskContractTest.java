package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务契约(T01–T16,含新字段 depends_on / progress / blocked / 派生 sprints):
 * - GET /api/tasks → 16 条;
 * - PATCH 12 字段:name/owner_id/hours/week_start/week_end/story_ref/kanban_card_id/
 *   estimated_hours/task_type/depends_on/status/progress/blocked;
 * - 字段级越界 → 422;周序颠倒 → 400「开始周不能晚于结束周」;挂不存在卡 → 400;
 * - depends_on:合法写入/规范化/回读、重复 400、格式 400、自依赖 400、不存在 400、依赖环 400;
 * - story_ref:USxx 规范化回读、重复 400、格式 400(含废弃的 M 编号)、不存在 400;
 * - status↔progress 联动:只给其一时推导另一个,status=3 保留原进度;
 * - sprints 由 week_start..week_end 派生(每 2 周一档);
 * - blocked 输出为 JSON 布尔;
 * - 解绑回归:kanban_card_id=null 必须真正落库(靠 GET /api/tasks 复读验证,不能只看 PATCH 响应);
 * - PATCH 不存在任务 → 404。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskContractTest extends ContractTestSupport {

    /** 管理类任务:无看板卡、无前置依赖 */
    private static final String TASK_T01 = "T01";
    private static final String TASK_T16 = "T16";
    /** 挂在 US07 上的开发任务(depends_on 种子值 "T03,T04") */
    private static final String TASK_T05 = "T05";
    /** 挂在 US25 上的开发任务(用于解绑落库回归) */
    private static final String TASK_T06 = "T06";

    // ---------------- 工具 ----------------

    private static List<Integer> sprintsOf(JsonNode task) {
        List<Integer> out = new ArrayList<>();
        for (JsonNode s : task.path("sprints")) {
            out.add(s.asInt());
        }
        return out;
    }

    /** 字段值;null/缺失 → null,其余 → 文本 */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }

    private static String strField(JsonNode node, String field) {
        return node.path(field).asText();
    }

    /** 恢复 status+progress(两个一起给,避免单给一个触发联动推导) */
    private void restoreStatusProgress(String taskId, JsonNode original) {
        ApiResponse r = patch("/api/tasks/" + taskId, token(USER_ADMIN),
                json(map("status", original.path("status").asInt(), "progress", original.path("progress").asInt())));
        assertEquals(200, r.status(), "恢复 status/progress 失败: " + r.body());
    }

    // ==================== 既有用例(适配新基线) ====================

    @Test
    void listTasks_16() {
        ApiResponse r = get("/api/tasks", token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().isArray());
        assertEquals(16, r.json().size(), "任务基线应为 16 条: " + r.body());
    }

    @Test
    void patchTask_status_ok_andRestore() {
        JsonNode before = taskFromListOrFail(TASK_T01);
        int originalStatus = before.path("status").asInt();
        int originalProgress = before.path("progress").asInt();
        try {
            int target = originalStatus == 1 ? 2 : 1;
            ApiResponse r = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN), json(map("status", target)));
            assertEquals(200, r.status(), r.body());
            assertEquals(target, r.json().path("status").asInt(), r.body());
            // 新字段必须一起输出
            assertTrue(r.json().has("progress"), "PATCH 响应应带 progress: " + r.body());
            assertTrue(r.json().has("blocked"), "PATCH 响应应带 blocked: " + r.body());
            assertTrue(r.json().has("sprints"), "PATCH 响应应带派生 sprints: " + r.body());
            // 真的落库
            assertEquals(target, taskFromListOrFail(TASK_T01).path("status").asInt(),
                    "PATCH 后复读状态应已变更");
        } finally {
            ApiResponse restore = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN),
                    json(map("status", originalStatus, "progress", originalProgress)));
            assertEquals(200, restore.status(), restore.body());
            assertEquals(originalStatus, restore.json().path("status").asInt(), restore.body());
            assertEquals(originalStatus, taskFromListOrFail(TASK_T01).path("status").asInt(),
                    "还原后复读状态应为原始值");
        }
    }

    @Test
    void patchTask_attachAndDetachKanbanCard() {
        String storyId = null;
        try {
            // 建一张临时故事卡
            ApiResponse created = post("/api/stories", token(USER_ADMIN),
                    json(map("title", uniq("CT挂卡"), "owner_id", 1)));
            assertEquals(200, created.status(), created.body());
            storyId = created.json().path("id").asText();
            assertTrue(storyId.matches("^US\\d+$"), "临时卡也应是 US 编号: " + storyId);

            ApiResponse attach = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN),
                    json(map("kanban_card_id", storyId)));
            assertEquals(200, attach.status(), attach.body());
            assertEquals(storyId, attach.json().path("kanban_card_id").asText(), attach.body());
            assertEquals(storyId, strField(taskFromListOrFail(TASK_T01), "kanban_card_id"),
                    "挂卡后复读应真的挂上");

            ApiResponse detach = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN),
                    json(map("kanban_card_id", null)));
            assertEquals(200, detach.status(), detach.body());
            assertTrue(detach.json().path("kanban_card_id").isNull(),
                    "显式 null 应解绑: " + detach.body());
            assertTrue(taskFromListOrFail(TASK_T01).path("kanban_card_id").isNull(),
                    "解绑后复读 GET /api/tasks,kanban_card_id 必须为 null");
        } finally {
            // T01 本来就是管理任务(无卡),确保回到无卡状态再清理临时卡
            patch("/api/tasks/" + TASK_T01, token(USER_ADMIN), json(map("kanban_card_id", null)));
            if (storyId != null) {
                deleteStory(storyId);
            }
        }
    }

    @Test
    void patchTask_statusOutOfRange_422() {
        ApiResponse r1 = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN), json(map("status", 5)));
        assertEquals(422, r1.status(), r1.body());
        ApiResponse r2 = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN), json(map("status", -1)));
        assertEquals(422, r2.status(), r2.body());
        // status 只允许 0..3(4 也不合法)
        ApiResponse r3 = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN), json(map("status", 4)));
        assertEquals(422, r3.status(), "status 上限为 3: " + r3.body());
    }

    @Test
    void patchTask_weekOutOfRange_422() {
        ApiResponse r1 = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN), json(map("week_start", 0)));
        assertEquals(422, r1.status(), r1.body());
        ApiResponse r2 = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN), json(map("week_end", 7)));
        assertEquals(422, r2.status(), r2.body());
    }

    @Test
    void patchTask_weekEndBeforeStart_400() {
        ApiResponse r = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN),
                json(map("week_start", 4, "week_end", 2)));
        assertEquals(400, r.status(), r.body());
        assertEquals("开始周不能晚于结束周", detail(r),
                "周序颠倒文案应为新基线(旧文案「结束周不能早于开始周」已废弃): " + r.body());
        // 校验失败不得留下半截写入
        JsonNode after = taskFromListOrFail(TASK_T01);
        assertEquals(1, after.path("week_start").asInt(), "校验失败不应改 week_start: " + after);
        assertEquals(1, after.path("week_end").asInt(), "校验失败不应改 week_end: " + after);
    }

    @Test
    void patchTask_notFound_404() {
        ApiResponse r = patch("/api/tasks/T99", token(USER_ADMIN), json(map("status", 1)));
        assertEquals(404, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void patchTask_attachNonexistentStory_400() {
        ApiResponse r = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN),
                json(map("kanban_card_id", "US99")));
        assertEquals(400, r.status(), r.body());
        assertEquals("所属看板卡不存在", detail(r), "挂不存在的看板卡应 400: " + r.body());
        assertTrue(taskFromListOrFail(TASK_T01).path("kanban_card_id").isNull(),
                "挂卡失败不应写入库");
    }

    // ==================== 新增:字段级越界(422) ====================

    @Test
    void patchTask_newFields_outOfRange_422() {
        ApiResponse progressHigh = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN),
                json(map("progress", 101)));
        assertEquals(422, progressHigh.status(), "progress>100 应 422: " + progressHigh.body());
        ApiResponse progressLow = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN),
                json(map("progress", -1)));
        assertEquals(422, progressLow.status(), "progress<0 应 422: " + progressLow.body());

        ApiResponse hours = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("hours", 1000)));
        assertEquals(422, hours.status(), "hours>999 应 422: " + hours.body());
        ApiResponse est = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("estimated_hours", 1000)));
        assertEquals(422, est.status(), "estimated_hours>999 应 422: " + est.body());

        ApiResponse name = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("name", "")));
        assertEquals(422, name.status(), "name 为空应 422: " + name.body());

        ApiResponse type = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("task_type", "bugfix")));
        assertEquals(422, type.status(), "task_type 非 feature/management 应 422: " + type.body());
        assertTrue(detail(type).contains("feature/management"), "task_type 文案应说明取值范围: " + type.body());

        ApiResponse blocked = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("blocked", "yes")));
        assertEquals(422, blocked.status(), "blocked 非布尔应 422: " + blocked.body());
    }

    // ==================== 新增:depends_on ====================

    @Test
    void patchTask_dependsOn_ok_writeNormalizeAndReadBack() {
        String original = strField(taskFromListOrFail(TASK_T16), "depends_on");
        try {
            ApiResponse single = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                    json(map("depends_on", "T01")));
            assertEquals(200, single.status(), single.body());
            assertEquals("T01", strField(single.json(), "depends_on"), single.body());
            assertEquals("T01", strField(taskFromListOrFail(TASK_T16), "depends_on"),
                    "depends_on 必须真的落库(复读验证)");

            // 多值 + 大小写/空白规范化
            ApiResponse multi = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                    json(map("depends_on", " t01 , t02 ")));
            assertEquals(200, multi.status(), multi.body());
            assertEquals("T01,T02", strField(multi.json(), "depends_on"),
                    "编号应去空白并转大写后逗号连接: " + multi.body());
            assertEquals("T01,T02", strField(taskFromListOrFail(TASK_T16), "depends_on"),
                    "规范化结果必须真的落库");
        } finally {
            ApiResponse clear = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                    json(map("depends_on", "")));
            assertEquals(200, clear.status(), clear.body());
            assertEquals("", strField(taskFromListOrFail(TASK_T16), "depends_on"),
                    "清空依赖应落库为空串(原值 " + original + ")");
        }
    }

    @Test
    void patchTask_dependsOn_duplicate_400() {
        ApiResponse r = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("depends_on", "T01,T01")));
        assertEquals(400, r.status(), r.body());
        assertEquals("关联编号不能重复", detail(r), r.body());

        // 规范化后才重复(大小写不同)也应被识别
        ApiResponse r2 = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("depends_on", "T01,t01")));
        assertEquals(400, r2.status(), r2.body());
        assertEquals("关联编号不能重复", detail(r2), r2.body());
        assertEquals("", strField(taskFromListOrFail(TASK_T16), "depends_on"),
                "校验失败不应写入库");
    }

    @Test
    void patchTask_dependsOn_badFormat_400() {
        ApiResponse r = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("depends_on", "US01")));
        assertEquals(400, r.status(), r.body());
        assertEquals("关联编号必须使用 Txx 格式，并以逗号分隔", detail(r), r.body());

        ApiResponse r2 = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("depends_on", "T01,X02")));
        assertEquals(400, r2.status(), r2.body());
        assertEquals("关联编号必须使用 Txx 格式，并以逗号分隔", detail(r2), r2.body());
    }

    @Test
    void patchTask_dependsOn_selfReference_400() {
        ApiResponse r = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("depends_on", "T16")));
        assertEquals(400, r.status(), r.body());
        assertEquals("任务不能依赖自身", detail(r), r.body());

        ApiResponse r2 = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("depends_on", "T01,T16")));
        assertEquals(400, r2.status(), r2.body());
        assertEquals("任务不能依赖自身", detail(r2), r2.body());
    }

    @Test
    void patchTask_dependsOn_missingPrerequisite_400() {
        ApiResponse r = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("depends_on", "T99")));
        assertEquals(400, r.status(), r.body());
        assertEquals("前置任务不存在：T99", detail(r), r.body());
        assertEquals("", strField(taskFromListOrFail(TASK_T16), "depends_on"),
                "校验失败不应写入库");
    }

    @Test
    void patchTask_dependsOn_cycle_400() {
        // 种子依赖链:T12.depends_on="T05,T07" → 让 T05 依赖 T12 即构成环 T05→T12→T05
        assertEquals("T03,T04", strField(taskFromListOrFail(TASK_T05), "depends_on"),
                "前置条件:T05 种子依赖应为 T03,T04");
        ApiResponse r = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN),
                json(map("depends_on", "T12")));
        assertEquals(400, r.status(), r.body());
        assertEquals("任务依赖不能形成循环", detail(r), r.body());
        assertEquals("T03,T04", strField(taskFromListOrFail(TASK_T05), "depends_on"),
                "成环被拒后不得改动原依赖");

        // 间接成环:T07 依赖 T10,T10→T09,T09→T07
        ApiResponse r2 = patch("/api/tasks/T07", token(USER_ADMIN), json(map("depends_on", "T10")));
        assertEquals(400, r2.status(), r2.body());
        assertEquals("任务依赖不能形成循环", detail(r2), r2.body());
        assertEquals("T02,T04,T06", strField(taskFromListOrFail("T07"), "depends_on"),
                "间接成环被拒后不得改动原依赖");
    }

    // ==================== 新增:status / progress 联动 ====================

    @Test
    void patchTask_statusOnly_derivesProgress() {
        JsonNode original = taskFromListOrFail(TASK_T05);
        try {
            ApiResponse done = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("status", 2)));
            assertEquals(200, done.status(), done.body());
            assertEquals(2, done.json().path("status").asInt(), done.body());
            assertEquals(100, done.json().path("progress").asInt(),
                    "status=2 应推导 progress=100: " + done.body());

            ApiResponse doing = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("status", 1)));
            assertEquals(200, doing.status(), doing.body());
            assertEquals(1, doing.json().path("status").asInt(), doing.body());
            assertEquals(50, doing.json().path("progress").asInt(),
                    "status=1 应推导 progress=50: " + doing.body());

            ApiResponse todo = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("status", 0)));
            assertEquals(200, todo.status(), todo.body());
            assertEquals(0, todo.json().path("status").asInt(), todo.body());
            assertEquals(0, todo.json().path("progress").asInt(),
                    "status=0 应推导 progress=0: " + todo.body());

            // 联动结果必须落库
            JsonNode after = taskFromListOrFail(TASK_T05);
            assertEquals(0, after.path("status").asInt(), after.toString());
            assertEquals(0, after.path("progress").asInt(), after.toString());
        } finally {
            restoreStatusProgress(TASK_T05, original);
        }
    }

    @Test
    void patchTask_progressOnly_derivesStatus() {
        JsonNode original = taskFromListOrFail(TASK_T05);
        try {
            ApiResponse full = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("progress", 100)));
            assertEquals(200, full.status(), full.body());
            assertEquals(100, full.json().path("progress").asInt(), full.body());
            assertEquals(2, full.json().path("status").asInt(),
                    "progress=100 应推导 status=2: " + full.body());

            ApiResponse partial = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("progress", 40)));
            assertEquals(200, partial.status(), partial.body());
            assertEquals(40, partial.json().path("progress").asInt(), partial.body());
            assertEquals(1, partial.json().path("status").asInt(),
                    "progress=40 应推导 status=1: " + partial.body());

            ApiResponse zero = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("progress", 0)));
            assertEquals(200, zero.status(), zero.body());
            assertEquals(0, zero.json().path("progress").asInt(), zero.body());
            assertEquals(0, zero.json().path("status").asInt(),
                    "progress=0 应推导 status=0: " + zero.body());
        } finally {
            restoreStatusProgress(TASK_T05, original);
        }
    }

    @Test
    void patchTask_statusCancelled_keepsProgress() {
        JsonNode original = taskFromListOrFail(TASK_T05);
        try {
            // 同时给 status+progress → 按给定值落库(不推导)
            ApiResponse seed = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN),
                    json(map("status", 1, "progress", 73)));
            assertEquals(200, seed.status(), seed.body());
            assertEquals(73, seed.json().path("progress").asInt(), seed.body());

            // 只给 status=3(已取消)→ 保留原 progress
            ApiResponse cancelled = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("status", 3)));
            assertEquals(200, cancelled.status(), cancelled.body());
            assertEquals(3, cancelled.json().path("status").asInt(), cancelled.body());
            assertEquals(73, cancelled.json().path("progress").asInt(),
                    "status=3 应保留原 progress(不得被推导成 0/50/100): " + cancelled.body());

            JsonNode after = taskFromListOrFail(TASK_T05);
            assertEquals(3, after.path("status").asInt(), after.toString());
            assertEquals(73, after.path("progress").asInt(), "status=3 保留的进度必须落库: " + after);
        } finally {
            restoreStatusProgress(TASK_T05, original);
        }
    }

    // ==================== 新增:派生 sprints ====================

    @Test
    void patchTask_sprints_derivedFromWeeks() {
        JsonNode original = taskFromListOrFail(TASK_T05);
        int owStart = original.path("week_start").asInt();
        int owEnd = original.path("week_end").asInt();
        List<Integer> oSprints = sprintsOf(original);
        try {
            assertEquals(List.of(1), patchWeeks(1, 2), "week 1..2 应派生 [1]");
            assertEquals(List.of(2), patchWeeks(3, 4), "week 3..4 应派生 [2]");
            assertEquals(List.of(3), patchWeeks(5, 6), "week 5..6 应派生 [3]");
            assertEquals(List.of(1, 2, 3), patchWeeks(2, 5), "week 2..5 应派生 [1,2,3]");
        } finally {
            ApiResponse restore = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN),
                    json(map("week_start", owStart, "week_end", owEnd)));
            assertEquals(200, restore.status(), restore.body());
            assertEquals(oSprints, sprintsOf(taskFromListOrFail(TASK_T05)),
                    "还原排期后 sprints 应回到派生原值");
        }
    }

    /** PATCH 排期并返回复读(不是响应体)得到的派生 sprints */
    private List<Integer> patchWeeks(int weekStart, int weekEnd) {
        ApiResponse r = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN),
                json(map("week_start", weekStart, "week_end", weekEnd)));
        assertEquals(200, r.status(), r.body());
        assertEquals(weekStart, r.json().path("week_start").asInt(), r.body());
        assertEquals(weekEnd, r.json().path("week_end").asInt(), r.body());
        List<Integer> fromResponse = sprintsOf(r.json());
        List<Integer> fromStore = sprintsOf(taskFromListOrFail(TASK_T05));
        assertEquals(fromResponse, fromStore,
                "sprints 是派生字段:响应与复读必须一致(" + weekStart + ".." + weekEnd + ")");
        return fromStore;
    }

    // ==================== 新增:blocked 布尔输出 ====================

    @Test
    void patchTask_blocked_outputIsJsonBoolean() {
        JsonNode original = taskFromListOrFail(TASK_T05);
        boolean oBlocked = original.path("blocked").asBoolean();
        try {
            ApiResponse on = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("blocked", true)));
            assertEquals(200, on.status(), on.body());
            assertTrue(on.json().path("blocked").isBoolean(),
                    "blocked 必须是 JSON 布尔(true/false),不是 1/0: " + on.body());
            assertTrue(on.json().path("blocked").asBoolean(), on.body());
            assertTrue(on.body().contains("\"blocked\":true"),
                    "响应原文应输出 JSON 布尔 true: " + on.body());

            JsonNode reOn = taskFromListOrFail(TASK_T05);
            assertTrue(reOn.path("blocked").isBoolean(), "复读 blocked 也必须是布尔: " + reOn);
            assertTrue(reOn.path("blocked").asBoolean(), "blocked=true 必须落库(库中 1 → 输出 true): " + reOn);

            ApiResponse off = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN), json(map("blocked", false)));
            assertEquals(200, off.status(), off.body());
            assertTrue(off.json().path("blocked").isBoolean(), off.body());
            assertFalse(off.json().path("blocked").asBoolean(), off.body());
            assertTrue(off.body().contains("\"blocked\":false"),
                    "响应原文应输出 JSON 布尔 false: " + off.body());

            JsonNode reOff = taskFromListOrFail(TASK_T05);
            assertTrue(reOff.path("blocked").isBoolean(), reOff.toString());
            assertFalse(reOff.path("blocked").asBoolean(), "blocked=false 必须落库: " + reOff);
        } finally {
            ApiResponse restore = patch("/api/tasks/" + TASK_T05, token(USER_ADMIN),
                    json(map("blocked", oBlocked)));
            assertEquals(200, restore.status(), restore.body());
        }
    }

    // ==================== 新增:解绑落库回归 ====================

    @Test
    void patchTask_unbindKanbanCard_persistedAfterReread() {
        // 回归缺陷:曾经 PATCH {"kanban_card_id": null} 返回体已解绑,但库里 kanban_card_id
        // 仍是旧卡(MyBatis-Plus 默认 NOT_NULL 更新策略跳过 null)。必须复读验证。
        String t06Card = textOrNull(taskFromListOrFail(TASK_T06), "kanban_card_id");
        assertNotNull(t06Card, "前置条件:种子任务 T06 应挂着看板卡(复读 GET /api/tasks)");
        assertTrue(t06Card.matches("^US\\d+$"), "T06 的卡应为 US 编号: " + t06Card);

        String tempStoryId = null;
        try {
            // 阶段一:临时卡 → 挂上 → 显式 null 解绑 → 复读
            ApiResponse created = post("/api/stories", token(USER_ADMIN),
                    json(map("title", uniq("CT解绑回归"), "owner_id", 1)));
            assertEquals(200, created.status(), created.body());
            tempStoryId = created.json().path("id").asText();

            ApiResponse attach = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN),
                    json(map("kanban_card_id", tempStoryId)));
            assertEquals(200, attach.status(), attach.body());
            assertEquals(tempStoryId, strField(taskFromListOrFail(TASK_T01), "kanban_card_id"),
                    "挂卡后复读应真的挂上临时卡");

            ApiResponse detach = patch("/api/tasks/" + TASK_T01, token(USER_ADMIN),
                    json(map("kanban_card_id", null)));
            assertEquals(200, detach.status(), detach.body());
            assertTrue(detach.json().path("kanban_card_id").isNull(), "PATCH 响应应为已解绑: " + detach.body());
            JsonNode reread = taskFromListOrFail(TASK_T01);
            assertTrue(reread.path("kanban_card_id").isNull(),
                    "回归:响应说已解绑,但复读 GET /api/tasks 时 kanban_card_id=" + reread.path("kanban_card_id")
                            + "(期望 null);说明 null 未真正写库: " + reread);

            // 阶段二:把种子卡 T06→某 USxx 也解绑一次,复读必须为 null
            ApiResponse detachSeeded = patch("/api/tasks/" + TASK_T06, token(USER_ADMIN),
                    json(map("kanban_card_id", null)));
            assertEquals(200, detachSeeded.status(), detachSeeded.body());
            assertTrue(detachSeeded.json().path("kanban_card_id").isNull(), detachSeeded.body());
            JsonNode rereadSeeded = taskFromListOrFail(TASK_T06);
            assertTrue(rereadSeeded.path("kanban_card_id").isNull(),
                    "回归:解绑种子卡(" + t06Card + ")后复读应为 null,实际: " + rereadSeeded);
        } finally {
            // 清理 + 还原:T01 回无卡;T06 回原卡
            patch("/api/tasks/" + TASK_T01, token(USER_ADMIN), json(map("kanban_card_id", null)));
            ApiResponse restore = patch("/api/tasks/" + TASK_T06, token(USER_ADMIN),
                    json(map("kanban_card_id", t06Card)));
            assertEquals(200, restore.status(), "还原 T06 挂卡失败: " + restore.body());
            assertEquals(t06Card, strField(taskFromListOrFail(TASK_T06), "kanban_card_id"),
                    "T06 必须还原到原卡 " + t06Card);
            if (tempStoryId != null) {
                deleteStory(tempStoryId);
            }
        }
    }

    // ==================== 新增:story_ref(USxx 命名空间) ====================

    @Test
    void patchTask_storyRef_okNormalizeAndReadBack() {
        String original = strField(taskFromListOrFail(TASK_T16), "story_ref");
        try {
            ApiResponse single = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                    json(map("story_ref", "us01")));
            assertEquals(200, single.status(), single.body());
            assertEquals("US01", strField(single.json(), "story_ref"),
                    "story_ref 应规范化为大写 USxx: " + single.body());
            assertEquals("US01", strField(taskFromListOrFail(TASK_T16), "story_ref"),
                    "story_ref 必须真的落库");

            ApiResponse multi = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                    json(map("story_ref", "US01, US02")));
            assertEquals(200, multi.status(), multi.body());
            assertEquals("US01,US02", strField(multi.json(), "story_ref"), multi.body());
            assertEquals("US01,US02", strField(taskFromListOrFail(TASK_T16), "story_ref"),
                    "多值 story_ref 必须真的落库");
        } finally {
            ApiResponse restore = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                    json(map("story_ref", original)));
            assertEquals(200, restore.status(), restore.body());
            assertEquals(original, strField(taskFromListOrFail(TASK_T16), "story_ref"),
                    "story_ref 应还原为种子值 " + original);
        }
    }

    @Test
    void patchTask_storyRef_invalid_400() {
        ApiResponse unknown = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("story_ref", "US99")));
        assertEquals(400, unknown.status(), unknown.body());
        assertEquals("关联故事不存在：US99", detail(unknown), unknown.body());

        ApiResponse dup = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("story_ref", "US01,US01")));
        assertEquals(400, dup.status(), dup.body());
        assertEquals("关联编号不能重复", detail(dup), dup.body());

        ApiResponse format = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("story_ref", "US1x")));
        assertEquals(400, format.status(), format.body());
        assertEquals("关联编号必须使用 USxx 格式，并以逗号分隔", detail(format), format.body());

        // 旧 M 编号已废弃:不再是合法 story_ref
        ApiResponse legacy = patch("/api/tasks/" + TASK_T16, token(USER_ADMIN),
                json(map("story_ref", "M01")));
        assertEquals(400, legacy.status(), "旧 M 编号应被拒: " + legacy.body());
        assertEquals("关联编号必须使用 USxx 格式，并以逗号分隔", detail(legacy), legacy.body());

        // 全部失败,种子值不应被改动
        assertEquals("US07,US24,US37", strField(taskFromListOrFail(TASK_T16), "story_ref"),
                "校验失败不应改动 story_ref");
    }
}
