package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成员画像契约(技术栈 / 工作能力 / 熟悉的开发流程领域):
 * - GET /api/members/profiles → 5 名成员,三个维度均非空、level∈1..5,且各成员技术栈集合互不相同;
 * - PATCH /api/members/{userId}/profile:admin/owner 可改任意成员,member 只能改本人,viewer 只读(403);
 * - 校验边界:level 越界 / 同维度重名 / 未知字段 / 缺维度 → 422;成员不存在 → 404;未登录 → 401;
 * - 写入可复读:保存后重新 GET 能读到新值(测试末尾一律还原种子画像)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberProfileContractTest extends ContractTestSupport {

    private static final int ADMIN_ID = 1;
    private static final int OWNER_ID = 2;
    private static final int MEMBER_ID = 3;
    private static final int VIEWER_ID = 5;

    private JsonNode profileOf(int userId) {
        ApiResponse r = get("/api/members/profiles", token(USER_ADMIN));
        assertStatus(r, 200);
        for (JsonNode p : r.json()) {
            if (p.path("user_id").asInt() == userId) return p;
        }
        throw new AssertionError("画像列表缺少 user_id=" + userId + ": " + r.body());
    }

    /** 由现有画像构造等值载荷(用于还原) */
    private Map<String, Object> payloadFrom(JsonNode p) {
        return map("title", p.path("title").asText(),
                "summary", p.path("summary").asText(),
                "years_experience", p.path("years_experience").asInt(),
                "tech_stack", items(p.path("tech_stack")),
                "capabilities", items(p.path("capabilities")),
                "process_domains", items(p.path("process_domains")));
    }

    private List<Map<String, Object>> items(JsonNode array) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode it : array) {
            out.add(map("name", it.path("name").asText(), "level", it.path("level").asInt()));
        }
        return out;
    }

    private Map<String, Object> payload(List<Map<String, Object>> tech) {
        return map("title", "契约测试岗位", "summary", "契约测试画像",
                "years_experience", 3,
                "tech_stack", tech,
                "capabilities", List.of(map("name", "测试设计", "level", 4)),
                "process_domains", List.of(map("name", "测试与质量", "level", 4)));
    }

    @Test
    void profiles_5rows_allDimensionsFilled_levelsInRange_andDistinct() {
        ApiResponse r = get("/api/members/profiles", token(USER_ADMIN));
        assertStatus(r, 200);
        assertEquals(5, r.json().size(), r.body());

        Set<String> techSignatures = new LinkedHashSet<>();
        for (JsonNode p : r.json()) {
            assertTrue(p.path("title").asText().length() > 0, "画像标题不能为空: " + p);
            for (String dim : List.of("tech_stack", "capabilities", "process_domains")) {
                JsonNode arr = p.path(dim);
                assertTrue(arr.isArray() && arr.size() > 0, dim + " 不能为空: " + p);
                for (JsonNode item : arr) {
                    assertTrue(item.path("name").asText().length() > 0, dim + " 条目名不能为空");
                    int level = item.path("level").asInt();
                    assertTrue(level >= 1 && level <= 5, dim + " level 必须在 1..5,实际 " + level);
                }
            }
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode item : p.path("tech_stack")) names.add(item.path("name").asText());
            techSignatures.add(String.join("|", names));
        }
        assertEquals(5, techSignatures.size(), "5 名成员的技术栈画像必须互不相同");
    }

    @Test
    void profiles_withoutToken_401() {
        assertStatus(get("/api/members/profiles", null), 401);
    }

    @Test
    void patchProfile_selfByMember_ok_persisted_andRestored() {
        JsonNode before = profileOf(MEMBER_ID);
        List<Map<String, Object>> tech = new ArrayList<>(items(before.path("tech_stack")));
        tech.add(map("name", uniq("契约新增技能"), "level", 5));

        ApiResponse patched = patch("/api/members/" + MEMBER_ID + "/profile", token(USER_MEMBER), json(payload(tech)));
        assertStatus(patched, 200);
        assertEquals(tech.size(), patched.json().path("tech_stack").size(), patched.body());

        // 可复读:重新 GET 必须看到新值
        assertEquals(tech.size(), profileOf(MEMBER_ID).path("tech_stack").size(), "写入后应能复读到新画像");

        // 还原
        assertStatus(patch("/api/members/" + MEMBER_ID + "/profile", token(USER_ADMIN), json(payloadFrom(before))), 200);
        assertEquals(before.path("tech_stack").size(), profileOf(MEMBER_ID).path("tech_stack").size());
    }

    @Test
    void patchProfile_otherMemberByMember_403() {
        ApiResponse r = patch("/api/members/" + OWNER_ID + "/profile", token(USER_MEMBER),
                json(payload(List.of(map("name", "越权写入", "level", 3)))));
        assertStatus(r, 403);
        assertTrue(detail(r).contains("本人"), r.body());
    }

    @Test
    void patchProfile_adminOnOther_ok_andRestored() {
        JsonNode before = profileOf(OWNER_ID);
        ApiResponse r = patch("/api/members/" + OWNER_ID + "/profile", token(USER_ADMIN),
                json(payload(List.of(map("name", "管理员代改", "level", 2)))));
        assertStatus(r, 200);
        assertEquals("管理员代改", r.json().path("tech_stack").path(0).path("name").asText());
        assertStatus(patch("/api/members/" + OWNER_ID + "/profile", token(USER_ADMIN), json(payloadFrom(before))), 200);
    }

    @Test
    void patchProfile_viewerSelf_403_readOnlyRole() {
        JsonNode self = profileOf(VIEWER_ID);
        ApiResponse r = patch("/api/members/" + VIEWER_ID + "/profile", token(USER_VIEWER),
                json(payloadFrom(self)));
        assertStatus(r, 403);
        assertTrue(detail(r).contains("只读"), r.body());
    }

    @Test
    void patchProfile_validation_422() {
        String path = "/api/members/" + MEMBER_ID + "/profile";
        String admin = token(USER_ADMIN);

        // level 越界
        assertStatus(patch(path, admin, json(payload(List.of(map("name", "越界", "level", 9))))), 422);
        // 同维度重名(大小写不同也算重名)
        assertStatus(patch(path, admin, json(payload(List.of(
                map("name", "Java", "level", 3), map("name", "java", "level", 4))))), 422);
        // 未知字段(全局严格 JSON)
        Map<String, Object> unknown = payload(List.of(map("name", "x", "level", 3)));
        unknown.put("hacker", "x");
        assertStatus(patch(path, admin, json(unknown)), 422);
        // 缺维度:整份替换契约要求三个维度都给出
        assertStatus(patch(path, admin, json(map("title", "t", "capabilities", List.of(),
                "process_domains", List.of()))), 422);
    }

    @Test
    void patchProfile_unknownUser_404() {
        ApiResponse r = patch("/api/members/9999/profile", token(USER_ADMIN),
                json(payload(List.of(map("name", "不存在", "level", 3)))));
        assertStatus(r, 404);
    }

    @Test
    void patchProfile_withoutToken_401() {
        assertStatus(patch("/api/members/" + MEMBER_ID + "/profile", null,
                json(payload(List.of(map("name", "匿名", "level", 3))))), 401);
        assertNotNull(profileOf(ADMIN_ID));
    }

    /**
     * 画像文本不得是「双编码乱码」。
     *
     * <p><b>为什么加这条</b>:开发库 {@code AIcap} 里 user_id=3(孙秋实)/4(罗子涵)的画像曾被写成
     * UTF-8 → cp1252 双编码,界面上整块画像全是 {@code MySQL 8 ä¸ç´¢å¼ä¼å} 这种乱码,
     * 而当时**没有任何用例会红** —— 既有断言只查「非空 / level∈1..5 / 技术栈互不相同」,
     * 这些对乱码一律成立。脏数据是靠人眼发现的,这本身就是测试缺口。
     *
     * <p><b>判据</b>:中文被逐字节拆开后**一个 CJK 字符都不剩**,只剩 Latin-1 字母与 cp1252 标点。
     * 因此取「含非 ASCII 但一个 CJK 都没有」:纯 ASCII 的合法值(Docker / Git / JUnit /
     * MyBatis-Plus)天然不受影响,含中文的正常值必然有 CJK,而乱码必然命中。
     *
     * <p><b>适用范围</b>:这条判据只给「以中文为主的画像文本」用,不是通用乱码检测器 ——
     * 对「W1–W2」这类只有排版符号、没有中文的串会误报,所以不外推到其他表。
     *
     * <p><b>为什么能防住复发</b>:契约测试每个上下文都会
     * {@code TRUNCATE member_profiles} 后重新播种(见 {@code db/reset_test_data.sql}),
     * 所以任何一条把乱码写进库的路径(种子串被写坏、请求体编码不对)都会在这里被挡住。
     * 开发库的历史脏数据不受影响(它不会被重播),那类只能按
     * {@code qa/测试用例设计_当前基线_v3.md} §9.4 的 {@code ENV-D03} 手工修。
     */
    @Test
    void profiles_textFields_neverDoubleEncoded() {
        ApiResponse r = get("/api/members/profiles", token(USER_ADMIN));
        assertStatus(r, 200);
        List<String> bad = new ArrayList<>();
        for (JsonNode p : r.json()) {
            List<String> texts = new ArrayList<>(List.of(p.path("title").asText(), p.path("summary").asText()));
            for (String dim : List.of("tech_stack", "capabilities", "process_domains")) {
                for (JsonNode item : p.path(dim)) texts.add(item.path("name").asText());
            }
            for (String t : texts) {
                boolean nonAscii = t.chars().anyMatch(c -> c > 0x7f);
                boolean hasCjk = t.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
                if (nonAscii && !hasCjk) bad.add(p.path("user_id").asInt() + " → " + t);
            }
        }
        assertTrue(bad.isEmpty(), "画像文本不得是双编码乱码(中文被拆成 Latin-1,一个 CJK 都不剩): " + bad);
    }
}
