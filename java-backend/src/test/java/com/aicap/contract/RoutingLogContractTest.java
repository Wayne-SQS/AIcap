package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由留痕契约(S5′，契约 §2.7 / §4.3)。
 *
 * <p>这张表是 S7「路由质量」指标的唯一数据源，而编排层不持有 DB 连接 —— 留痕只能经
 * 这个端点写。本类钉住：能写能读、读限 admin/owner、缺字段是 422(不是 500)、
 * 越权写被拒、以及 confidence 越界在边界收口而不是炸成 500。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class RoutingLogContractTest extends ContractTestSupport {

    private static final String LOGS = "/api/agents/routing-logs";
    private static final String SESSIONS = "/api/agents/sessions";

    private String newSession(String who) {
        ApiResponse created = post(SESSIONS, token(who), json(map()));
        assertStatus(created, 200);
        return created.json().path("session_id").asText();
    }

    private Map<String, Object> payload(String sessionId, String utterance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", UUID.randomUUID().toString());
        body.put("session_id", sessionId);
        body.put("utterance", utterance);
        body.put("intent", "start_meeting");
        body.put("route", "start_meeting_analysis");
        body.put("confidence", 0.92);
        body.put("reason", "用户要求分析这场会");
        body.put("asked_clarify", false);
        body.put("candidates", List.of("start_meeting_analysis", "start_plan_draft"));
        return body;
    }

    /** ROUTE-01:写进去能读回来，且响应字段是 snake_case(契约 §4.3 的列名) */
    @Test
    void appendThenListAsAdmin() {
        String sessionId = newSession(USER_ADMIN);
        ApiResponse created = post(LOGS, token(USER_ADMIN), json(payload(sessionId, "分析这场会")));
        assertEquals(200, created.status(), created.body());
        assertTrue(created.json().path("id").asLong() > 0, created.body());

        ApiResponse listed = get(LOGS + "?limit=20", token(USER_ADMIN));
        assertEquals(200, listed.status(), listed.body());
        JsonNode found = null;
        for (JsonNode row : listed.json()) {
            if (sessionId.equals(row.path("session_id").asText())) {
                found = row;
                break;
            }
        }
        assertNotNull(found, "刚写的留痕必须能读回来: " + listed.body());
        assertEquals("start_meeting", found.path("intent").asText(), found.toString());
        assertEquals("start_meeting_analysis", found.path("route").asText(), found.toString());
        assertTrue(found.has("asked_clarify"),
                "响应字段必须是 snake_case,否则对接方按契约取只会拿到 null: " + found);
        assertTrue(found.path("user_id").asInt() > 0,
                "user_id 必须来自调用者身份(JWT),不是 body: " + found);
    }

    /** ROUTE-02:表里有用户原话,属隐私 —— 读限 admin/owner，未登录 401 */
    @Test
    void listRequiresAdminOrOwner() {
        assertStatus(get(LOGS, token(USER_MEMBER)), 403);
        assertStatus(get(LOGS, token(USER_VIEWER)), 403);
        assertStatus(get(LOGS, null), 401);
    }

    /** ROUTE-03:缺必填字段 → 422(协议级错误走标准状态码，契约 §2.3 的口径) */
    @Test
    void missingRequiredFieldsAreUnprocessable() {
        String sessionId = newSession(USER_ADMIN);
        Map<String, Object> noUtterance = payload(sessionId, "分析这场会");
        noUtterance.remove("utterance");
        assertStatus(post(LOGS, token(USER_ADMIN), json(noUtterance)), 422);

        Map<String, Object> noIntent = payload(sessionId, "分析这场会");
        noIntent.remove("intent");
        assertStatus(post(LOGS, token(USER_ADMIN), json(noIntent)), 422);
    }

    /** ROUTE-04:往别人的会话里写留痕必须被拒(与事件/检查点同源的会话归属) */
    @Test
    void foreignSessionIsRejected() {
        String adminSession = newSession(USER_ADMIN);
        ApiResponse r = post(LOGS, token(USER_MEMBER), json(payload(adminSession, "偷写一行")));
        assertTrue(r.status() == 403 || r.status() == 404,
                "越权写留痕必须被拒,而不是 200: " + r.status() + " " + r.body());
    }

    /**
     * ROUTE-05:confidence 越界在边界收口，而不是炸成 500。
     *
     * <p>列是 DECIMAL(4,3)：模型返回 1.5 这类值时直接写会以
     * DataIntegrityViolationException 冒到 500 —— 而它只是"模型多说了个数"。
     */
    @Test
    void outOfRangeConfidenceIsClampedInsteadOfFiveHundred() {
        String sessionId = newSession(USER_ADMIN);
        Map<String, Object> body = payload(sessionId, "分析这场会");
        body.put("confidence", 1.5);
        ApiResponse r = post(LOGS, token(USER_ADMIN), json(body));
        assertEquals(200, r.status(), "越界 confidence 不该变成 500: " + r.body());
        assertTrue(r.json().path("id").asLong() > 0, r.body());
    }

    /** ROUTE-06:不确定性字段可缺省(反问样本没有 route，candidates 也可能没有) */
    @Test
    void clarifySampleWithoutRouteIsAccepted() {
        String sessionId = newSession(USER_ADMIN);
        Map<String, Object> body = payload(sessionId, "出个报告");
        body.remove("route");
        body.remove("candidates");
        body.put("asked_clarify", true);
        body.put("intent", "generate_report");
        body.put("confidence", 0.41);
        ApiResponse r = post(LOGS, token(USER_ADMIN), json(body));
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().path("id").asLong() > 0, r.body());
    }
}
