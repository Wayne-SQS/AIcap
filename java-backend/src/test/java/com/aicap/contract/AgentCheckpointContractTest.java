package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主管检查点契约(S4′.2，契约 §4.5)：{@code /api/agents/checkpoints} 与
 * {@code /api/agents/runs/unfinished}。
 *
 * <p>检查点是"重启后把未完成的 run 接着跑完"的唯一依据，两件事必须钉死：
 * <ul>
 *   <li><b>upsert 覆盖</b>：同 {@code (run_id, node_name)} 只有一行，state 取最新——
 *       否则恢复会读到旧状态、重跑已完成的步骤；</li>
 *   <li><b>未完成判定</b>：有检查点且无 {@code run.end}/{@code run.error}。
 *       少任一半都会误判(漏恢复，或把已完成的 run 又跑一遍)。</li>
 * </ul>
 * 归属：他人会话一律 404(检查点表没有 session_id 列，只能借事件表反查)。
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
class AgentCheckpointContractTest extends ContractTestSupport {

    private static final String SESSIONS = "/api/agents/sessions";
    private static final String EVENTS = "/api/agents/events";
    private static final String CHECKPOINTS = "/api/agents/checkpoints";
    private static final String UNFINISHED = "/api/agents/runs/unfinished";

    private String newSession(String who) {
        ApiResponse r = post(SESSIONS, token(who), json(map()));
        assertStatus(r, 200);
        return r.json().path("session_id").asText();
    }

    private ApiResponse append(String who, String sid, String run, String type, Map<String, Object> detail) {
        return post(EVENTS, token(who), json(map(
                "session_id", sid, "run_id", run, "type", type, "detail", detail)));
    }

    private ApiResponse putCheckpoint(String who, String sid, String run, Map<String, Object> state) {
        return post(CHECKPOINTS, token(who), json(map(
                "session_id", sid, "run_id", run, "node_name", "loop", "state", state)));
    }

    private ApiResponse getCheckpoints(String who, String run) {
        return get(CHECKPOINTS + "?run_id=" + run, token(who));
    }

    private ApiResponse unfinished(String who, String sid) {
        return get(UNFINISHED + "?session_id=" + sid, token(who));
    }

    private boolean containsRun(JsonNode arr, String run) {
        for (JsonNode n : arr) {
            if (run.equals(n.path("run_id").asText())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 写 / upsert ====================

    /** CKPT-01:未登录不可写检查点 */
    @Test
    void ckpt01_putCheckpoint_withoutToken_401() {
        assertStatus(post(CHECKPOINTS, null, json(map(
                "session_id", "x", "run_id", "y", "node_name", "loop", "state", map()))), 401);
    }

    /** CKPT-02:upsert——同 (run_id, node_name) 写两次只留 1 行，state 取第二次 */
    @Test
    void ckpt02_upsert_overwritesSameRunNode() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        append(USER_MEMBER, sid, run, "model.step", map("step", 1));
        assertStatus(putCheckpoint(USER_MEMBER, sid, run, map("step", 1)), 200);
        assertStatus(putCheckpoint(USER_MEMBER, sid, run, map("step", 2)), 200);
        ApiResponse r = getCheckpoints(USER_MEMBER, run);
        assertStatus(r, 200);
        assertEquals(1, r.json().size(), "同 run+node 应只有一行: " + r.body());
        assertEquals(2, r.json().get(0).path("state").path("step").asInt(), "state 应取第二次: " + r.body());
    }

    /** CKPT-03:往他人会话写检查点被拒(404) */
    @Test
    void ckpt03_putCheckpoint_toOthersSession_404() {
        String memberSession = newSession(USER_MEMBER);
        assertStatus(putCheckpoint(USER_OWNER, memberSession, uniq("run"), map("step", 1)), 404);
    }

    /** CKPT-04:session_id 与该 run_id 既有事件所属会话不一致 → 404(堵"自己的 session + 别人的 run") */
    @Test
    void ckpt04_sessionMismatchWithExistingEvents_404() {
        String sa = newSession(USER_MEMBER);
        String sb = newSession(USER_MEMBER);
        String run = uniq("run");
        append(USER_MEMBER, sa, run, "model.step", map("step", 1));
        // run 的事件在 sa，却拿 sb 写检查点——同一调用者也不行
        assertStatus(putCheckpoint(USER_MEMBER, sb, run, map("step", 1)), 404);
    }

    /** CKPT-05:state 往返保真(嵌套 JSON 对象) */
    @Test
    void ckpt05_stateRoundTripsAsObject() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        append(USER_MEMBER, sid, run, "model.step", map("step", 1));
        assertStatus(putCheckpoint(USER_MEMBER, sid, run, map("messages", new Object[]{map("role", "user")})), 200);
        JsonNode state = getCheckpoints(USER_MEMBER, run).json().get(0).path("state");
        assertTrue(state.isObject(), "state 应是对象: " + state);
        assertTrue(state.path("messages").isArray(), "嵌套数组应保真: " + state);
    }

    // ==================== 未完成判定 ====================

    /** CKPT-06:有检查点且无 run.end → 未完成 */
    @Test
    void ckpt06_hasCheckpointNoTerminal_isUnfinished() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        append(USER_MEMBER, sid, run, "model.step", map("step", 1));
        assertStatus(putCheckpoint(USER_MEMBER, sid, run, map("step", 1)), 200);
        ApiResponse r = unfinished(USER_MEMBER, sid);
        assertStatus(r, 200);
        assertTrue(containsRun(r.json(), run), "有检查点无终局应答未完成: " + r.body());
    }

    /** CKPT-07:补一条该 run 的 run.end 后，不再算未完成 */
    @Test
    void ckpt07_afterTerminal_notUnfinished() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        append(USER_MEMBER, sid, run, "model.step", map("step", 1));
        putCheckpoint(USER_MEMBER, sid, run, map("step", 2));
        append(USER_MEMBER, sid, run, "run.end", map("status", "ok"));
        ApiResponse r = unfinished(USER_MEMBER, sid);
        assertFalse(containsRun(r.json(), run), "有 run.end 就不该再算未完成: " + r.body());
    }

    /** CKPT-08:只有事件、没有检查点的 run 不算未完成(没有可恢复状态) */
    @Test
    void ckpt08_eventsWithoutCheckpoint_notUnfinished() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        append(USER_MEMBER, sid, run, "model.step", map("step", 1));
        ApiResponse r = unfinished(USER_MEMBER, sid);
        assertFalse(containsRun(r.json(), run), "无检查点不应算未完成: " + r.body());
    }

    // ==================== 归属 / 隔离 ====================

    /** CKPT-09:读他人会话的未完成列表被拒(404) */
    @Test
    void ckpt09_unfinished_othersSession_404() {
        String memberSession = newSession(USER_MEMBER);
        assertStatus(unfinished(USER_OWNER, memberSession), 404);
    }

    /** CKPT-10:未登录不可读检查点 / 未完成列表 */
    @Test
    void ckpt10_reads_withoutToken_401() {
        assertStatus(get(CHECKPOINTS + "?run_id=x", null), 401);
        assertStatus(get(UNFINISHED + "?session_id=x", null), 401);
    }

    /** CKPT-11:读他人 run 的检查点被拒(404) */
    @Test
    void ckpt11_getCheckpoints_othersRun_404() {
        String sid = newSession(USER_MEMBER);
        String run = uniq("run");
        append(USER_MEMBER, sid, run, "model.step", map("step", 1));
        putCheckpoint(USER_MEMBER, sid, run, map("step", 1));
        assertStatus(getCheckpoints(USER_OWNER, run), 404);
    }

    /** CKPT-12:跨会话隔离——A 会话的未完成 run 不出现在 B 会话的列表里 */
    @Test
    void ckpt12_unfinishedIsolatedPerSession() {
        String sa = newSession(USER_MEMBER);
        String sb = newSession(USER_MEMBER);
        String runA = uniq("run");
        append(USER_MEMBER, sa, runA, "model.step", map("step", 1));
        putCheckpoint(USER_MEMBER, sa, runA, map("step", 1));
        ApiResponse rb = unfinished(USER_MEMBER, sb);
        assertFalse(containsRun(rb.json(), runA), "B 会话不应看到 A 的未完成 run: " + rb.body());
    }
}
