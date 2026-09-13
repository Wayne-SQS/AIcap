package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证契约:
 * - POST /api/auth/login 成功 → 200 且含 access_token + user{role:admin,capacity_hours};
 * - 登录双向别名:真名(李锐铭…)↔ 存量名(成员1…)都能登录到同一账号;
 *   第 5 个用户额外支持"显示名(只读查看者)→ 成员5"单向别名(FE-D02);
 * - 错密码 → 401 {"detail":"用户名或密码错误"};未知用户 → 401;
 * - GET /api/auth/me 带 Bearer → 200;
 * - 无 token 访问受保护接口 → 401 {"detail":...};
 * - GET /api/auth/users → 5 个种子用户,容量依次 60/48/60/54/60,成员5=viewer。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthContractTest extends ContractTestSupport {

    /** 新基线用户顺序与容量(李锐铭 60 / 高思晗 48 / 孙秋实 60 / 罗子涵 54 / 成员5 60) */
    private static final List<String> SEEDED_USERS = List.of(
            USER_ADMIN, USER_OWNER, USER_MEMBER, USER_MEMBER2, USER_VIEWER);
    private static final List<Integer> SEEDED_CAPACITY = List.of(60, 48, 60, 54, 60);
    private static final List<String> SEEDED_ROLES = List.of("admin", "owner", "member", "member", "viewer");

    /** 第 5 个用户(成员5 / viewer)的展示名:登录时也应能作为别名使用(FE-D02) */
    private static final String VIEWER_DISPLAY_NAME = "只读查看者";

    private JsonNode userByName(JsonNode array, String username) {
        for (JsonNode u : array) {
            if (username.equals(u.path("username").asText())) {
                return u;
            }
        }
        return null;
    }

    private ApiResponse login(String username, String password) {
        return post("/api/auth/login", null, json(map("username", username, "password", password)));
    }

    @Test
    void loginAdmin_ok_200_withAccessTokenAndAdminRole() {
        ApiResponse r = login(USER_ADMIN, PASSWORD);
        assertEquals(200, r.status(), r.body());
        assertNotNull(r.json());
        assertTrue(!r.json().path("access_token").asText().isEmpty(), "缺少 access_token: " + r.body());
        assertEquals("admin", r.json().path("user").path("role").asText());
        assertEquals(USER_ADMIN, r.json().path("user").path("username").asText());
        assertEquals(60, r.json().path("user").path("capacity_hours").asInt(-1),
                "登录响应的 user 必须带 capacity_hours=60: " + r.body());
    }

    @Test
    void login_alias_bidirectional_bothSpellingsSameAccount() {
        // 真名登录(库中用户名为真名)
        ApiResponse real = login(USER_ADMIN, PASSWORD);
        assertEquals(200, real.status(), "真名登录应 200: " + real.body());
        assertEquals(USER_ADMIN, real.json().path("user").path("username").asText());
        int adminId = real.json().path("user").path("id").asInt(-1);

        // 存量别名登录(成员1)必须落到同一账号
        ApiResponse alias = login(ALIAS_ADMIN, PASSWORD);
        assertEquals(200, alias.status(), "别名 成员1 登录应 200: " + alias.body());
        assertEquals(USER_ADMIN, alias.json().path("user").path("username").asText(),
                "别名 成员1 应登录到同一账号 " + USER_ADMIN + ": " + alias.body());
        assertEquals(adminId, alias.json().path("user").path("id").asInt(-1),
                "真名与别名必须指向同一用户 id: " + alias.body());

        // 反向亦然(高思晗 ↔ 成员2)
        ApiResponse ownerReal = login(USER_OWNER, PASSWORD);
        assertEquals(200, ownerReal.status(), ownerReal.body());
        ApiResponse ownerAlias = login(ALIAS_OWNER, PASSWORD);
        assertEquals(200, ownerAlias.status(), "别名 成员2 登录应 200: " + ownerAlias.body());
        assertEquals(USER_OWNER, ownerAlias.json().path("user").path("username").asText(),
                "别名 成员2 应登录到 " + USER_OWNER + ": " + ownerAlias.body());

        // 别名拿到的 token 也是可用凭证
        String t = alias.json().path("access_token").asText();
        assertTrue(!t.isEmpty(), "别名登录应返回 access_token: " + alias.body());
        ApiResponse me = get("/api/auth/me", t);
        assertEquals(200, me.status(), me.body());
        assertEquals(USER_ADMIN, me.json().path("username").asText(), "别名 token 应解析为 " + USER_ADMIN);
        assertEquals(60, me.json().path("capacity_hours").asInt(-1),
                "/api/auth/me 必须带 capacity_hours: " + me.body());
    }

    @Test
    void login_allFourAliases_mapToRealNames() {
        List<String> realNames = List.of(USER_ADMIN, USER_OWNER, USER_MEMBER, USER_MEMBER2);
        List<String> aliases = List.of(ALIAS_ADMIN, ALIAS_OWNER, ALIAS_MEMBER, ALIAS_MEMBER2);
        for (int i = 0; i < aliases.size(); i++) {
            ApiResponse byAlias = login(aliases.get(i), PASSWORD);
            assertEquals(200, byAlias.status(), "别名 " + aliases.get(i) + " 登录应 200: " + byAlias.body());
            ApiResponse byReal = login(realNames.get(i), PASSWORD);
            assertEquals(200, byReal.status(), "真名 " + realNames.get(i) + " 登录应 200: " + byReal.body());
            assertEquals(byReal.json().path("user").path("id").asInt(-1),
                    byAlias.json().path("user").path("id").asInt(-1),
                    aliases.get(i) + " 与 " + realNames.get(i) + " 必须是同一账号: " + byAlias.body());
        }
    }

    /**
     * FE-D02:第 5 个用户(username=成员5/display_name=只读查看者)此前没有真名别名,
     * 用"只读查看者"登录返回 401。要求:两种叫法都能登录到同一 viewer 账号。
     */
    @Test
    void login_viewerDisplayNameAlias_mapsToMember5() {
        // ① 显示名登录 ② 用户名登录 → 都 200 且同一 user.id / role / display_name
        ApiResponse byDisplayName = login(VIEWER_DISPLAY_NAME, PASSWORD);
        assertEquals(200, byDisplayName.status(),
                "显示名 " + VIEWER_DISPLAY_NAME + " 登录应 200: " + byDisplayName.body());
        ApiResponse byUsername = login(USER_VIEWER, PASSWORD);
        assertEquals(200, byUsername.status(), "用户名 " + USER_VIEWER + " 登录应 200: " + byUsername.body());

        assertEquals(byUsername.json().path("user").path("id").asInt(-1),
                byDisplayName.json().path("user").path("id").asInt(-1),
                "显示名与用户名必须指向同一用户 id: " + byDisplayName.body());
        for (ApiResponse r : List.of(byDisplayName, byUsername)) {
            JsonNode u = r.json().path("user");
            assertEquals("viewer", u.path("role").asText(), r.body());
            assertEquals(VIEWER_DISPLAY_NAME, u.path("display_name").asText(), r.body());
            assertEquals(USER_VIEWER, u.path("username").asText(), r.body());
        }

        // 显示名登录拿到的 token 同样是该账号的可用凭证
        String t = byDisplayName.json().path("access_token").asText();
        assertTrue(!t.isEmpty(), "显示名登录应返回 access_token: " + byDisplayName.body());
        ApiResponse me = get("/api/auth/me", t);
        assertEquals(200, me.status(), me.body());
        assertEquals(USER_VIEWER, me.json().path("username").asText(), me.body());

        // ③ 密码错误时两种叫法都必须 401(别名不得放宽口令校验)
        assertEquals(401, login(VIEWER_DISPLAY_NAME, "wrong-pass").status(),
                "显示名 + 错密码必须 401");
        assertEquals(401, login(USER_VIEWER, "wrong-pass").status(),
                "用户名 + 错密码必须 401");
    }

    @Test
    void login_wrongPassword_401_withDetail() {
        ApiResponse r = login(USER_ADMIN, "wrong-pass");
        assertEquals(401, r.status(), r.body());
        assertEquals("用户名或密码错误", detail(r));
    }

    @Test
    void login_unknownUser_401() {
        ApiResponse r = login("不存在的人", PASSWORD);
        assertEquals(401, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void me_withBearerToken_200() {
        String t = token(USER_ADMIN);
        ApiResponse r = get("/api/auth/me", t);
        assertEquals(200, r.status(), r.body());
        assertEquals(USER_ADMIN, r.json().path("username").asText());
    }

    @Test
    void protectedEndpoint_withoutToken_401_detail() {
        ApiResponse r = get("/api/stories", null);
        assertEquals(401, r.status(), r.body());
        assertTrue(r.json().has("detail"), "401 响应应为 {\"detail\":...}: " + r.body());
        ApiResponse r2 = get("/api/tasks", null);
        assertEquals(401, r2.status(), r2.body());
    }

    @Test
    void usersList_containsFiveSeededUsers_withCapacityHours_viewerLast() {
        ApiResponse r = get("/api/auth/users", token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());
        assertNotNull(r.json());
        assertTrue(r.json().isArray());
        assertEquals(5, r.json().size(), "种子用户应为 5 个: " + r.body());

        List<String> names = new ArrayList<>();
        for (JsonNode u : r.json()) {
            names.add(u.path("username").asText());
        }
        assertEquals(SEEDED_USERS, names, "用户顺序应为 " + SEEDED_USERS + ": " + r.body());

        for (int i = 0; i < SEEDED_USERS.size(); i++) {
            JsonNode u = r.json().get(i);
            String name = SEEDED_USERS.get(i);
            assertEquals(SEEDED_ROLES.get(i), u.path("role").asText(), name + " 角色不符: " + u);
            assertTrue(u.has("capacity_hours"), name + " 缺少 capacity_hours 字段: " + u);
            assertEquals(SEEDED_CAPACITY.get(i), u.path("capacity_hours").asInt(-1),
                    name + " 容量应为 " + SEEDED_CAPACITY.get(i) + ": " + u);
        }

        JsonNode viewer = userByName(r.json(), USER_VIEWER);
        assertNotNull(viewer, "应存在 viewer 账号 成员5: " + r.body());
        assertEquals("viewer", viewer.path("role").asText());
    }

    @Test
    void loginResponse_capacityHours_matchesUsersList() {
        ApiResponse users = get("/api/auth/users", token(USER_ADMIN));
        assertEquals(200, users.status(), users.body());
        for (String name : SEEDED_USERS) {
            JsonNode u = userByName(users.json(), name);
            assertNotNull(u, "用户列表中应有 " + name + ": " + users.body());
            ApiResponse logged = login(name, PASSWORD);
            assertEquals(200, logged.status(), name + " 登录应 200: " + logged.body());
            assertEquals(u.path("capacity_hours").asInt(-1),
                    logged.json().path("user").path("capacity_hours").asInt(-1),
                    name + " 登录响应的 capacity_hours 应与 /api/auth/users 一致: " + logged.body());
        }
    }
}
