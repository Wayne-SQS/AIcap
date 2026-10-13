package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US27 成员容量三态契约:「未配置(null)」「确认为零(0)」「正常(&gt;0)」必须在
 * **设置 → 保存 → 读取**整条链路上可区分,且不被任何一层兜底改写。
 *
 * <p>口径依据 {@code docs/项目侧交接_会议Agent接入_v0.1.md:56} ——
 * 「无容量数据返回 unknown,**不当作零或无限容量**;容量为零时避免除零。」
 *
 * <p>本类钉住的正是 US11 评审未通过的根因:旧实现把三态塌成一个数
 * (schema {@code NOT NULL DEFAULT 60} → {@code AuthDtos} null→60 → 前端 {@code || 60}),
 * 于是"零容量"和"未配置"在系统里物理不可达。末尾一律把容量还原为种子值。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberCapacityContractTest extends ContractTestSupport {

    private static final int OWNER_ID = 2;              // 高思晗,种子容量 48
    private static final int MEMBER_ID = 3;             // 孙秋实,种子容量 60
    private static final int SEED_OWNER_CAPACITY = 48;
    private static final int SEED_MEMBER_CAPACITY = 60;

    /** 读 /api/auth/users 的容量;字段为 JSON null 时返回 null(这正是"未配置") */
    private Integer capacityFromUsers(int userId, String token) {
        ApiResponse r = get("/api/auth/users", token);
        assertStatus(r, 200);
        for (JsonNode u : r.json()) {
            if (u.path("id").asInt() == userId) {
                JsonNode c = u.path("capacity_hours");
                return c.isNull() || c.isMissingNode() ? null : c.asInt();
            }
        }
        throw new AssertionError("用户列表缺少 id=" + userId + ": " + r.body());
    }

    private Integer capacityFromProfiles(int userId, String token) {
        ApiResponse r = get("/api/members/profiles", token);
        assertStatus(r, 200);
        for (JsonNode p : r.json()) {
            if (p.path("user_id").asInt() == userId) {
                JsonNode c = p.path("capacity_hours");
                return c.isNull() || c.isMissingNode() ? null : c.asInt();
            }
        }
        throw new AssertionError("画像列表缺少 user_id=" + userId + ": " + r.body());
    }

    /** 只带 capacity_hours 的请求体;value 传 null 即"显式 null" */
    private ApiResponse patchCapacity(int userId, Object value, String token) {
        return patch("/api/members/" + userId + "/capacity", token, json(map("capacity_hours", value)));
    }

    /** 1) 三态端到端可区分 —— US27 / US11 的核心 DoD */
    @Test
    void capacityThreeStates_areDistinguishable_onReadBack() {
        String admin = token(USER_ADMIN);
        try {
            assertStatus(patchCapacity(OWNER_ID, 0, admin), 200);
            Integer zero = capacityFromUsers(OWNER_ID, admin);
            assertEquals(0, zero == null ? -1 : zero.intValue(), "确认为 0 必须原样读回,不能被兜成 60");

            assertStatus(patchCapacity(OWNER_ID, null, admin), 200);
            assertNull(capacityFromUsers(OWNER_ID, admin), "显式 null 必须读回 null(未配置),且不得等于 0");

            assertStatus(patchCapacity(OWNER_ID, 12, admin), 200);
            Integer twelve = capacityFromUsers(OWNER_ID, admin);
            assertEquals(12, twelve == null ? -1 : twelve.intValue(), "正常值必须原样读回");
        } finally {
            assertStatus(patchCapacity(OWNER_ID, SEED_OWNER_CAPACITY, admin), 200);
        }
        Integer restored = capacityFromUsers(OWNER_ID, admin);
        assertEquals(SEED_OWNER_CAPACITY, restored == null ? -1 : restored.intValue(), "容量还原失败,污染了后续用例");
    }

    /** 2) 「未配置」不得在两个读端点上表现不一致(users 与 profiles 是同一份 users.capacity_hours) */
    @Test
    void unconfiguredCapacity_readsAsNull_onBothEndpoints() {
        String admin = token(USER_ADMIN);
        try {
            assertStatus(patchCapacity(OWNER_ID, null, admin), 200);
            assertNull(capacityFromUsers(OWNER_ID, admin), "/api/auth/users 未配置应为 null");
            assertNull(capacityFromProfiles(OWNER_ID, admin), "/api/members/profiles 未配置应为 null");
        } finally {
            assertStatus(patchCapacity(OWNER_ID, SEED_OWNER_CAPACITY, admin), 200);
        }
    }

    /** 3) 种子基线不能被"可空迁移"带偏:5 人仍是 60/48/60/54/60 */
    @Test
    void seededCapacities_surviveNullableMigration() {
        String admin = token(USER_ADMIN);
        ApiResponse r = get("/api/auth/users", admin);
        assertStatus(r, 200);
        int[] expected = {60, 48, 60, 54, 60};
        assertEquals(expected.length, r.json().size(), "种子用户数应为 " + expected.length + ": " + r.body());
        for (int i = 0; i < expected.length; i++) {
            JsonNode c = r.json().get(i).path("capacity_hours");
            assertTrue(!c.isNull(), "种子用户 " + (i + 1) + " 的容量不该是 null: " + r.body());
            assertEquals(expected[i], c.asInt(), "种子用户 " + (i + 1) + " 容量被改动: " + r.body());
        }
    }

    /** 4) 请求体校验:漏字段 / 负数 / 非整数 一律 422,不得静默当成 0 或 60 */
    @Test
    void invalidCapacityBodies_areRejected() {
        String admin = token(USER_ADMIN);
        assertStatus(patch("/api/members/" + OWNER_ID + "/capacity", admin, json(map())), 422);
        assertStatus(patchCapacity(OWNER_ID, -1, admin), 422);
        assertStatus(patchCapacity(OWNER_ID, "abc", admin), 422);
        assertStatus(patchCapacity(OWNER_ID, 1.5, admin), 422);
        Integer after = capacityFromUsers(OWNER_ID, admin);
        assertEquals(SEED_OWNER_CAPACITY, after == null ? -1 : after.intValue(), "非法请求不得改动容量");
    }

    /** 5) 权限:viewer 只读、member 只能改本人、未登录 401、成员不存在 404 */
    @Test
    void capacityPermissions_followProfileRules() {
        String viewer = token(USER_VIEWER);
        assertStatus(patchCapacity(OWNER_ID, 0, viewer), 403);

        String member = token(USER_MEMBER);
        assertStatus(patchCapacity(OWNER_ID, 0, member), 403);
        try {
            assertStatus(patchCapacity(MEMBER_ID, 0, member), 200);
            Integer self = capacityFromUsers(MEMBER_ID, member);
            assertEquals(0, self == null ? -1 : self.intValue(), "member 改本人容量应生效");
        } finally {
            assertStatus(patchCapacity(MEMBER_ID, SEED_MEMBER_CAPACITY, token(USER_ADMIN)), 200);
        }

        assertStatus(patch("/api/members/" + OWNER_ID + "/capacity", null, json(map("capacity_hours", 0))), 401);
        assertStatus(patchCapacity(9999, 0, token(USER_ADMIN)), 404);
        Integer untouched = capacityFromUsers(MEMBER_ID, token(USER_ADMIN));
        assertEquals(SEED_MEMBER_CAPACITY, untouched == null ? -1 : untouched.intValue(), "越权/非法请求不得改动容量");
    }
}
