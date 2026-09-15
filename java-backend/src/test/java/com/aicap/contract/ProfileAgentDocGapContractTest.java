package com.aicap.contract;

import com.aicap.entity.DifficultyAssessment;
import com.aicap.mapper.DifficultyAssessmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设计文档第 4 章差距修复回归(2026-09-15 v2.2):
 * - 4.2/4.6/4.8:test 活动类型可录入(ACTIVITY_TYPES 扩展);
 * - 4.3:成员 objective 含 long_inactive_tasks(进行中近7天无活动);
 * - 4.4:成员 objective 含 cross_module_modules/cross_module_count;
 * - 4.6:成员 objective 含 missing_review_tasks/missing_test_tasks/multi_member_edits;
 * - 4.7:画像含 code_stability/rework_rate/defect_fix_capability/review_participation;
 *        快照 payload 含完整证据(objective/evidence_refs),dynamic_profile 字段提升顶层兼容;
 * - 4.8:team_risks 含 missing_test(种子任务完成/进行中无 test 活动);
 * - 4.9:对比统计含 test_count。
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
class ProfileAgentDocGapContractTest extends ContractTestSupport {

    private static final int MEMBER_ID = 3; // 孙秋实

    @Autowired
    private DifficultyAssessmentMapper difficultyMapper;

    // ---------- 4.2:test 活动类型可录入 ----------

    @Test
    void testActivityType_accepted() {
        Map<String, Object> body = map("user_id", MEMBER_ID,
                "activity_type", "test",
                "title", "补充登录接口单元测试",
                "detail", "契约测试:test 类型活动",
                "module", "认证",
                "happened_at", "2026-09-05 10:00:00");
        ApiResponse ok = post("/api/profile-agent/activities", token(USER_MEMBER), json(body));
        assertStatus(ok, 200);
        // 非法类型仍被拒(校验未被破坏)
        Map<String, Object> bad = map("user_id", MEMBER_ID,
                "activity_type", "hack",
                "title", "x", "module", "认证", "happened_at", "2026-09-05 10:00:00");
        assertStatus(post("/api/profile-agent/activities", token(USER_MEMBER), json(bad)), 422);
    }

    // ---------- 4.3/4.4/4.6:成员 objective 新字段 ----------

    @Test
    void memberObjective_hasDocGapFields() {
        ApiResponse r = get("/api/profile-agent/analysis?start=2026-08-25&end=2026-09-15", token(USER_MEMBER));
        assertStatus(r, 200);
        JsonNode members = r.json().path("members");
        assertTrue(members.isArray() && members.size() > 0, r.body());
        JsonNode m = members.get(0);
        JsonNode obj = m.path("objective");
        assertTrue(obj.isObject(), r.body());
        for (String key : List.of("cross_module_modules", "cross_module_count",
                "long_inactive_tasks", "missing_review_tasks", "missing_test_tasks", "multi_member_edits")) {
            assertTrue(obj.has(key), "objective 缺字段 " + key + " -> " + r.body());
        }
    }

    // ---------- 4.7:画像新字段 + 快照完整证据 ----------

    @Test
    void memberProfile_hasNewFields_snapshotKeepsEvidence() {
        ApiResponse r = get("/api/profile-agent/analysis?start=2026-08-25&end=2026-09-15", token(USER_MEMBER));
        assertStatus(r, 200);
        JsonNode members = r.json().path("members");
        JsonNode dp = members.get(0).path("dynamic_profile");
        assertTrue(dp.isObject(), r.body());
        for (String key : List.of("code_stability", "rework_rate",
                "defect_fix_capability", "review_participation")) {
            assertTrue(dp.has(key), "dynamic_profile 缺字段 " + key + " -> " + r.body());
        }

        // 正式分析落快照后,快照 payload 应含完整证据(objective/evidence_refs),且画像字段提升顶层(兼容旧读取)
        // 用 owner 触发(admin 的 run 被 M4 限流测试占用,避免并发限流窗口冲突)
        ApiResponse run = post("/api/profile-agent/analysis/run?start=2026-08-25&end=2026-09-15", token(USER_OWNER), null);
        assertStatus(run, 200);
        ApiResponse snaps = get("/api/profile-agent/snapshots?user_id=" + MEMBER_ID, token(USER_ADMIN));
        assertStatus(snaps, 200);
        JsonNode arr = snaps.json();
        assertTrue(arr.isArray() && arr.size() > 0, snaps.body());
        JsonNode payload = arr.get(0).path("payload");
        assertTrue(payload.isObject(), snaps.body());
        assertTrue(payload.has("objective") && payload.has("evidence_refs"),
                "快照应含完整证据 objective/evidence_refs -> " + snaps.body());
        assertTrue(payload.has("good_at"), "画像字段应提升到顶层兼容旧读取 -> " + snaps.body());
    }

    // ---------- 4.8:团队风险含 missing_test ----------

    @Test
    void teamRisks_includeMissingTest() {
        ApiResponse r = get("/api/profile-agent/analysis?start=2026-08-25&end=2026-09-15", token(USER_MEMBER));
        assertStatus(r, 200);
        JsonNode risks = r.json().path("team_risks");
        assertTrue(risks.isArray(), r.body());
        Set<String> kinds = new java.util.HashSet<>();
        for (JsonNode n : risks) {
            assertTrue(n.has("kind") && !n.path("kind").asText().isBlank(), r.body());
            assertTrue(n.has("evidence") && !n.path("evidence").asText().isBlank(), r.body());
            assertTrue(n.has("suggested_action") && !n.path("suggested_action").asText().isBlank(), r.body());
            kinds.add(n.path("kind").asText());
        }
        // 种子任务有进行中/已完成且无 test 活动 → 应出现 missing_test
        assertTrue(kinds.contains("missing_test"), "team_risks 应含 missing_test,实际 " + kinds + " <- " + r.body());
    }

    // ---------- 4.5:难度评估含"被后续任务依赖"因子(清掉历史评估后用新规则重算) ----------

    @Test
    void difficulty_assessmentKeepsStructure() {
        // 清掉种子/历史评估,强制预览分支用当前规则引擎重算,验证"被后续任务依赖"因子
        difficultyMapper.delete(new QueryWrapper<DifficultyAssessment>().ne("assessed_by", "manual"));
        ApiResponse r = get("/api/profile-agent/difficulty", token(USER_MEMBER));
        assertStatus(r, 200);
        JsonNode arr = r.json();   // difficulty 接口直接返回数组
        assertTrue(arr.isArray() && arr.size() > 0, r.body());
        boolean sawDownstream = false;
        for (JsonNode n : arr) {
            String level = n.path("level").asText();
            assertTrue(Set.of("low", "medium", "high", "extreme").contains(level), r.body());
            JsonNode basis = n.path("basis");
            assertTrue(basis.isArray() && basis.size() > 0, r.body());
            for (JsonNode b : basis) {
                if (b.asText().contains("被") && b.asText().contains("依赖")) sawDownstream = true;
            }
        }
        // 种子数据存在依赖链(T02 等依赖前置任务)→ 应至少一个任务 basis 提到"被后续任务依赖"
        assertTrue(sawDownstream, "难度评估应含'被后续任务依赖'因子(设计文档 4.5) -> " + r.body());
    }

    // ---------- 4.9:对比统计含 test_count ----------

    @Test
    void compareStats_includeTestCount() {
        ApiResponse r = get("/api/profile-agent/analysis/compare?prevStart=2026-08-01&prevEnd=2026-08-24&start=2026-08-25&end=2026-09-15",
                token(USER_MEMBER));
        assertStatus(r, 200);
        JsonNode list = r.json().path("comparisons");
        assertTrue(list.isArray() && list.size() > 0, r.body());
        boolean sawTestCount = false, sawTestChange = false;
        for (JsonNode c : list) {
            if (c.path("current_period").has("test_count")) sawTestCount = true;
            if (c.path("changes").toString().contains("测试活动")) sawTestChange = true;
        }
        assertTrue(sawTestCount, "对比周期统计应含 test_count -> " + r.body());
        assertTrue(sawTestChange, "对比 changes 应含测试活动项 -> " + r.body());
    }
}
