package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UML 布局跨设备保存（原技术债：草稿只存当前浏览器）。
 *
 * <p>钉住：项目成员可读、可写角色可保存、再次保存覆盖旧布局、
 * 非成员得到 404、viewer 不能保存、非法 body 被拒。
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
class UmlLayoutContractTest extends ContractTestSupport {

    private static final String PATH = "/api/projects/P001/uml-layout";

    private Map<String, Object> layout(int x) {
        return map("layout", map(
                "actors", List.of(map("id", "admin", "x", x, "y", 20)),
                "usecases", List.of(map("id", "uc-1", "x", 30, "y", 40)),
                "participants", List.of(map("id", "p-backend", "x", 50))));
    }

    @Test
    void memberCanSaveAndReadLayout() {
        ApiResponse saved = request(HttpMethod.PUT, PATH, token(USER_ADMIN), json(layout(10)));
        assertStatus(saved, 200);

        ApiResponse read = get(PATH, token(USER_MEMBER));
        assertStatus(read, 200);
        JsonNode layout = read.json().path("layout");
        assertTrue(layout.isObject(), read.body());
        assertEquals(10, layout.path("actors").get(0).path("x").asInt(), read.body());
        assertEquals("uc-1", layout.path("usecases").get(0).path("id").asText(), read.body());
    }

    @Test
    void secondSaveOverwritesLayout() {
        assertStatus(request(HttpMethod.PUT, PATH, token(USER_ADMIN), json(layout(10))), 200);
        assertStatus(request(HttpMethod.PUT, PATH, token(USER_ADMIN), json(layout(99))), 200);

        ApiResponse read = get(PATH, token(USER_OWNER));
        assertStatus(read, 200);
        assertEquals(99, read.json().path("layout").path("actors").get(0).path("x").asInt(), read.body());
    }

    @Test
    void viewerCannotSave() {
        ApiResponse denied = request(HttpMethod.PUT, PATH, token(USER_VIEWER), json(layout(1)));
        assertStatus(denied, 403);
    }

    @Test
    void nonMemberGetsNotFound() {
        ApiResponse read = get("/api/projects/P999/uml-layout", token(USER_MEMBER));
        assertStatus(read, 404);
    }

    @Test
    void invalidLayoutIsRejected() {
        ApiResponse bad = request(HttpMethod.PUT, PATH, token(USER_ADMIN),
                json(map("layout", "not-an-object")));
        assertStatus(bad, 422);
    }
}
