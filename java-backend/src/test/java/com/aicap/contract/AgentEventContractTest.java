package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主管会话与事件流契约(S4′.2)：{@code /api/agents/sessions} 与 {@code /api/agents/events}。
 *
 * <p>这是编排层"状态在库、进程无状态"的落点，三件事一旦走样，SSE 续传就静默错位：
 * <ul>
 *   <li><b>{@code id} 即 seq</b>，写入后递增返回，由 Java 自增主键提供(不用进程内计数器)；</li>
 *   <li><b>{@code after_seq} 是严格大于</b>：写成 {@code >=} 会让重连时重复收到已确认的那条；</li>
 *   <li><b>会话归属</b>：他人会话一律 404，写不进去也读不出来(不用 403，避免 id 枚举探针)。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql",
        "aicap.rag.embedding.api-key=",
        "aicap.rag.embedding.model="
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentEventContractTest extends ContractTestSupport {

    private static final String SESSIONS = "/api/agents/sessions";
    private static final String EVENTS = "/api/agents/events";

    private String newSession(String who) {
        ApiResponse r = post(SESSIONS, token(who), json(map()));
        assertStatus(r, 200);
        String id = r.json().path("session_id").asText();
        assertFalse(id.isBlank(), "建会话未返回 session_id: " + r.body());
        return id;
    }

    private ApiResponse append(String who, String sessionId, String runId, String type, Map<String, Object> detail) {
        return post(EVENTS, token(who), json(map(
                "session_id", sessionId, "run_id", runId, "type", type, "detail", detail)));
    }

    private ApiResponse listEvents(String who, String sessionId, long afterSeq) {
        return get(EVENTS + "?session_id=" + sessionId + "&after_seq=" + afterSeq, token(who));
    }

    // ==================== 会话 ====================

    /** SESS-01:未登录不可建会话 */
    @Test
    void sess01_createSession_withoutToken_401() {
        assertStatus(post(SESSIONS, null, json(map())), 401);
    }

    /** SESS-02:任何登录用户都能建自己的会话(含 viewer，门槛 Roles.any()) */
    @Test
    void sess02_createSession_anyLoggedInUser_200() {
        String id = newSession(USER_VIEWER);
        assertEquals(36, id.length(), "session_id 应是 36 位 UUID: " + id);
    }

    /** SESS-03:会话列表只回自己的 */
    @Test
    void sess03_listSessions_onlyMine() {
        String adminSession = newSession(USER_ADMIN);
        ApiResponse mine = get(SESSIONS, token(USER_MEMBER));
        assertStatus(mine, 200);
        for (JsonNode s : mine.json()) {
            assertFalse(adminSession.equals(s.path("session_id").asText()),
                    "member 的会话列表里出现了 admin 的会话 " + adminSession);
        }
    }

    /** SESS-04:未登录不可列会话 */
    @Test
    void sess04_listSessions_withoutToken_401() {
        assertStatus(get(SESSIONS, null), 401);
    }

    // ==================== 事件 ====================

    /** EVT-01:未登录不可写事件 */
    @Test
    void evt01_appendEvent_withoutToken_401() {
        assertStatus(post(EVENTS, null, json(map(
                "session_id", "x", "run_id", "y", "type", "tool.call", "detail", map()))), 401);
    }

    /** EVT-02:写事件返回递增的自增 id(= seq) */
    @Test
    void evt02_appendEvent_returnsIncreasingSeq() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        ApiResponse a = append(USER_MEMBER, sid, run, "tool.call", map("tool", "list_tasks"));
        ApiResponse b = append(USER_MEMBER, sid, run, "tool.result", map("ok", true));
        assertStatus(a, 200);
        assertStatus(b, 200);
        long id1 = a.json().path("id").asLong();
        long id2 = b.json().path("id").asLong();
        assertTrue(id1 > 0, "seq 应为正整数: " + a.body());
        assertTrue(id2 > id1, "第二条事件 seq 应更大: " + id1 + " -> " + id2);
    }

    /** EVT-03:A 往 B 的会话写事件被拒(404)，且库里没多出该事件 */
    @Test
    void evt03_appendEvent_toOthersSession_404_andNoSideEffect() {
        String memberSession = newSession(USER_MEMBER);
        int before = listEvents(USER_MEMBER, memberSession, 0).json().size();
        ApiResponse r = append(USER_OWNER, memberSession, uniq("run"), "tool.call", map("tool", "list_tasks"));
        assertStatus(r, 404);
        int after = listEvents(USER_MEMBER, memberSession, 0).json().size();
        assertEquals(before, after, "越权写入不应真的落库");
    }

    /** EVT-04:after_seq 增量读正确 */
    @Test
    void evt04_listEvents_afterSeqIncremental() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        long[] ids = new long[3];
        for (int i = 0; i < 3; i++) {
            ApiResponse r = append(USER_MEMBER, sid, run, "model.step", map("step", i + 1));
            assertStatus(r, 200);
            ids[i] = r.json().path("id").asLong();
        }
        JsonNode all = listEvents(USER_MEMBER, sid, 0).json();
        assertEquals(3, all.size(), "after_seq=0 应回全部: " + all);

        JsonNode afterFirst = listEvents(USER_MEMBER, sid, ids[0]).json();
        assertEquals(2, afterFirst.size(), "after_seq=第1条id 应回第2、3条: " + afterFirst);

        JsonNode afterLast = listEvents(USER_MEMBER, sid, ids[2]).json();
        assertEquals(0, afterLast.size(), "after_seq=末条id 应回空: " + afterLast);
    }

    /** EVT-05:Last-Event-ID 语义 = 严格大于(恰好等于某 id 时只回它之后的) */
    @Test
    void evt05_afterSeq_isStrictlyGreater() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        long first = append(USER_MEMBER, sid, run, "model.step", map("step", 1)).json().path("id").asLong();
        long second = append(USER_MEMBER, sid, run, "model.step", map("step", 2)).json().path("id").asLong();
        long third = append(USER_MEMBER, sid, run, "model.step", map("step", 3)).json().path("id").asLong();

        JsonNode r = listEvents(USER_MEMBER, sid, second).json();
        assertEquals(1, r.size(), "after_seq=第2条id 只应回第3条: " + r);
        assertEquals(third, r.get(0).path("id").asLong());
        assertTrue(r.get(0).path("id").asLong() > first);
    }

    /** EVT-06:读他人会话的事件被拒(404) */
    @Test
    void evt06_listEvents_othersSession_404() {
        String memberSession = newSession(USER_MEMBER);
        assertStatus(listEvents(USER_OWNER, memberSession, 0), 404);
    }

    /** EVT-07:detail 往返是 JSON 对象而非字符串 */
    @Test
    void evt07_detailRoundTripsAsObject() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        assertStatus(append(USER_MEMBER, sid, run, "tool.result", map("a", map("b", new int[]{1, 2}))), 200);
        JsonNode detail = listEvents(USER_MEMBER, sid, 0).json().get(0).path("detail");
        assertTrue(detail.isObject(), "detail 应是对象: " + detail);
        assertTrue(detail.path("a").path("b").isArray(), "嵌套结构应保真: " + detail);
    }

    /** EVT-08:跨会话隔离——A 会话的事件不出现在 B 会话的读结果里 */
    @Test
    void evt08_eventsAreIsolatedPerSession() {
        String sa = newSession(USER_MEMBER);
        String sb = newSession(USER_MEMBER);
        assertStatus(append(USER_MEMBER, sa, uniq("run"), "tool.call", map("tool", "x")), 200);
        assertEquals(0, listEvents(USER_MEMBER, sb, 0).json().size(), "B 会话不应看到 A 的事件");
    }

    /** EVT-10:追加式——同 run_id 重放(同 type)追加新 seq，不覆盖、不去重 */
    @Test
    void evt10_sameRunReplay_appendsNewSeq() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        long id1 = append(USER_MEMBER, sid, run, "run.end", map("status", "ok")).json().path("id").asLong();
        long id2 = append(USER_MEMBER, sid, run, "run.end", map("status", "ok")).json().path("id").asLong();
        assertTrue(id2 > id1, "重放应追加新 seq: " + id1 + " -> " + id2);
        assertEquals(2, listEvents(USER_MEMBER, sid, 0).json().size(), "两条都在(追加式，不去重)");
    }
}
