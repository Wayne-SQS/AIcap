package com.aicap.contract;

import com.aicap.entity.DifficultyAssessment;
import com.aicap.mapper.ActivityRecordMapper;
import com.aicap.mapper.DifficultyAssessmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像智能体修复回归(对应评审问题):
 * - H1:member 只能录入自己的活动事实(代录他人 → 403);
 * - H2:GET /analysis 为只读预览(无 LLM 密钥时不调 LLM、不写库、返回 rules_fallback);
 * - H3:并发正式分析不产生同一任务的重复评估(任务级锁);
 * - M2:时间范围 start>end 或超 400 天 → 422;
 * - L5:降级评估 assessed_by=rules,与 LLM 的 ai 区分。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "profile-llm.api-key=",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileAgentFixContractTest extends ProfileProjectContractSupport {

    private static final int MEMBER_ID = 3;      // 孙秋实
    private static final int MEMBER2_ID = 4;     // 罗子涵

    @Autowired
    private DifficultyAssessmentMapper difficultyMapper;
    @Autowired
    private ActivityRecordMapper activityMapper;

    @Test
    void heatmap_usesProjectMembershipAndRealDatedActivity() {
        String admin = token(USER_ADMIN);
        String viewer = token(USER_VIEWER);
        String range = "start=2026-10-13&end=2026-10-14&userId=5";
        ApiResponse before = getProject("/api/profile-agent/heatmap?" + range, viewer);
        assertStatus(before, 200);
        assertEquals(2, before.json().size(), before.body());
        int initial = before.json().get(1).path("total").asInt();
        Integer activityId = null;
        try {
            ApiResponse recorded = postProject("/api/profile-agent/activities", admin, json(map(
                    "user_id", 5, "activity_type", "note", "title", "Sprint 2 热力图验收活动",
                    "detail", "独立测试库中的日期与项目范围核对", "module", "Sprint 2",
                    "happened_at", "2026-10-14 10:00:00")));
            assertStatus(recorded, 200);
            activityId = recorded.json().path("activity_id").asInt();

            ApiResponse heatmap = getProject("/api/profile-agent/heatmap?" + range, viewer);
            assertStatus(heatmap, 200);
            assertEquals("2026-10-13", heatmap.json().get(0).path("date").asText());
            assertEquals("2026-10-14", heatmap.json().get(1).path("date").asText());
            assertEquals(initial + 1, heatmap.json().get(1).path("total").asInt());
            assertEquals(0, heatmap.json().get(0).path("total").asInt(),
                    "没有活动的日期仍应返回 0，不得遗漏日期");
            ApiResponse activities = getProject("/api/profile-agent/activities?start=2026-10-14&end=2026-10-14&userId=5", viewer);
            assertStatus(activities, 200);
            assertTrue(activities.json().toString().contains("Sprint 2 热力图验收活动"));
            assertTrue(activities.json().toString().contains("manual"));

            assertStatus(postProject("/api/profile-agent/activities", viewer, json(activityBody(5))), 403);
            assertStatus(get("/api/profile-agent/heatmap?projectId=P099&" + range, admin), 404);
            assertStatus(get("/api/profile-agent/activities?projectId=P099", admin), 404);
            assertStatus(post("/api/profile-agent/activities?projectId=P099", admin, json(activityBody(5))), 404);
        } finally {
            if (activityId != null) activityMapper.deleteById(activityId);
        }
    }

    // ---------- H1:越权录入 ----------

    @Test
    void member_cannotRecordActivityForOthers_butCanForSelf() {
        String member = token(USER_MEMBER);
        Map<String, Object> self = activityBody(MEMBER_ID);
        ApiResponse ok = postProject("/api/profile-agent/activities", member, json(self));
        assertStatus(ok, 200);

        Map<String, Object> other = activityBody(MEMBER2_ID);
        ApiResponse forbidden = postProject("/api/profile-agent/activities", member, json(other));
        assertStatus(forbidden, 403);

        // admin 可代录
        Map<String, Object> byAdmin = activityBody(MEMBER2_ID);
        ApiResponse adminOk = postProject("/api/profile-agent/activities", token(USER_ADMIN), json(byAdmin));
        assertStatus(adminOk, 200);
    }

    private Map<String, Object> activityBody(int userId) {
        return map("user_id", userId,
                "activity_type", "commit",
                "title", "修复登录接口 401",
                "detail", "契约测试活动",
                "module", "权限",
                "happened_at", "2026-09-01 10:00:00");
    }

    // ---------- H2:GET 预览只读 ----------

    @Test
    void getAnalysis_isReadOnlyPreview_rulesFallback() {
        ApiResponse r = getProject("/api/profile-agent/analysis?start=2026-08-25&end=2026-09-15", token(USER_MEMBER));
        assertStatus(r, 200);
        assertNotNull(r.json(), r.body());
        // 无 PROFILE_LLM_API_KEY → 全规则引擎,不调 LLM、不落库
        assertEquals("rules_fallback", r.json().path("engine").asText(), r.body());
        assertTrue(r.json().path("members").isArray(), r.body());
        assertTrue(r.json().path("team_risks").isArray(), r.body());
    }

    // ---------- M2:日期范围校验 ----------

    @Test
    void invalidRange_rejected() {
        String token = token(USER_MEMBER);
        // start > end
        ApiResponse reversed = getProject("/api/profile-agent/analysis?start=2026-09-15&end=2026-08-25", token);
        assertStatus(reversed, 422);
        // 跨度超过 400 天
        ApiResponse tooWide = getProject("/api/profile-agent/analysis?start=2024-01-01&end=2026-09-15", token);
        assertStatus(tooWide, 422);
        // 正常范围通过
        ApiResponse ok = getProject("/api/profile-agent/analysis?start=2026-08-25&end=2026-09-15", token);
        assertStatus(ok, 200);
    }

    // ---------- H3:并发正式分析不重复评估 ----------

    @Test
    void concurrentRuns_produceSingleAssessmentPerTask() throws Exception {
        String admin = token(USER_ADMIN);
        String member = token(USER_MEMBER);
        List<ApiResponse> results = fireConcurrently(
                () -> postProject("/api/profile-agent/analysis/run?start=2026-08-25&end=2026-09-15", admin, null),
                () -> postProject("/api/profile-agent/analysis/run?start=2026-08-25&end=2026-09-15", member, null));
        for (ApiResponse r : results) {
            assertStatus(r, 200);
        }
        // 每个任务在 difficulty_assessments 中最多一条智能体/规则评估(manual 另行计数)
        List<DifficultyAssessment> all = difficultyMapper.selectList(
                new QueryWrapper<DifficultyAssessment>().orderByAsc("task_id"));
        java.util.Map<String, Integer> countByTask = new java.util.HashMap<>();
        for (DifficultyAssessment a : all) {
            if ("manual".equals(a.getAssessedBy())) continue;
            countByTask.merge(a.getTaskId(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : countByTask.entrySet()) {
            assertEquals(1, e.getValue(), "任务 " + e.getKey() + " 的评估记录重复: " + e.getValue() + " 条");
        }
    }

    // ---------- L5:降级评估来源标注 ----------

    @Test
    void fallbackAssessment_markedAsRules() {
        ApiResponse r = getProject("/api/profile-agent/difficulty", token(USER_MEMBER));
        assertStatus(r, 200);
        assertTrue(r.json().isArray(), r.body());
        List<String> seen = new ArrayList<>();
        for (JsonNode d : r.json()) {
            String by = d.path("assessed_by").asText();
            seen.add(by);
            assertTrue(by.equals("manual") || by.equals("rules") || by.equals("ai"),
                    "assessed_by 非法: " + by + " body=" + r.body());
        }
        // 无 LLM 密钥时,未被人工修正的任务应为规则引擎降级(rules)
        assertTrue(seen.contains("rules"), "缺少 rules 来源评估: " + seen + " body=" + r.body());
    }

    // ---------- US02:只读角色不得触发正式分析(会落库运行记录/画像快照,并消耗 LLM 额度) ----------

    @Test
    void runAnalysis_viewerForbidden() {
        // 此前 controller 用 Roles.any(),viewer 也能触发 → 与 US02 只读约束冲突
        ApiResponse denied = postProject("/api/profile-agent/analysis/run?start=2026-08-25&end=2026-09-15",
                token(USER_VIEWER), null);
        assertStatus(denied, 403);
        // 只读预览仍允许(不落库、不调 LLM)
        ApiResponse preview = getProject("/api/profile-agent/analysis?start=2026-08-25&end=2026-09-15",
                token(USER_VIEWER));
        assertStatus(preview, 200);
    }
}
