package com.aicap.contract;

import com.aicap.agent.AgentProperties;
import com.aicap.contract.AgentModelFixture.Scenario;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 会议 AI Agent 契约测试(此前 5 个 Agent 接口无任何自动化覆盖)。
 * <p>
 * 被测量:{@code /api/agent/config}、{@code POST|GET /api/meetings/{id}/runs}、
 * {@code GET /api/agent-runs/{id}}、{@code POST /api/agent-runs/{id}/retry}。
 * <p>
 * 关键设计:
 * <ul>
 *   <li><b>不调用真实模型</b>:{@link AgentModelFixture} 是一个本地 OpenAI 兼容服务(127.0.0.1:19377),
 *       在 {@code @BeforeAll} 里把 {@link AgentProperties} 指向它。因此"排队→worker 消费→工具循环→
 *       严格校验→建议落库"整条链路都是真实执行的,同时零外网、零费用、结果可复现。</li>
 *   <li><b>worker 真开启</b>({@code aicap.llm.agent-worker-enabled=true}),队列由后台线程消费,
 *       测试通过轮询 {@code GET /api/agent-runs/{id}} 等待终态 —— 与前端 AgentRunPanel 的行为一致。</li>
 *   <li>其余契约测试类原先写的 {@code aicap.agent-worker-enabled=false} 少了 {@code llm.} 前缀,
 *       实际不生效(worker 仍是开启状态);本轮已统一修正为
 *       {@code aicap.llm.agent-worker-enabled}(见 {@code AgentProperties} 的 prefix 定义),
 *       本类则显式写 true,保证只有本类的上下文有 worker 在消费队列。</li>
 * </ul>
 * 隔离:使用独立测试库 {@code aicap_java_test};每个用例用唯一标题,不触碰种子 US01–US37 / T01–T16。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=true",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentContractTest extends ContractTestSupport {

    /** 三句话 → 三个片段,保证覆盖校验(每个片段都要有证据)是有意义的 */
    private static final String TRANSCRIPT =
            "李锐铭:本次会议讨论成员批量导入功能,目标是降低管理员操作成本。"
                    + "高思晗:希望下一个 Sprint 就能上线,范围先做表格导入。"
                    + "孙秋实:评审前需要确认字段映射规则和失败回滚策略。";

    private static final String PROMPT_VERSION = "meeting-complete-v2";

    private final AgentModelFixture fixture = new AgentModelFixture();

    @Autowired
    private AgentProperties agentProps;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @BeforeAll
    void startFixtureAndPointAgentAtIt() throws IOException {
        fixture.start();
        agentProps.setApiKey("fixture-test-key");
        agentProps.setBaseUrl("http://127.0.0.1:19377");
        agentProps.setModel("fixture-model");
    }

    @AfterAll
    void stopFixture() {
        fixture.stop();
    }

    @BeforeEach
    void resetFixture() {
        fixture.prepare(Scenario.HAPPY, uniq("Agent新增需求"));
        // 防止上一个用例(未配置场景)遗留状态影响本用例
        agentProps.setApiKey("fixture-test-key");
        agentProps.setBaseUrl("http://127.0.0.1:19377");
        agentProps.setModel("fixture-model");
    }

    // ------------------------------------------------------------------
    // 1. 配置与鉴权
    // ------------------------------------------------------------------

    /** AGENT-01:未登录访问 Agent 全部接口一律 401(且不泄露任何配置信息) */
    @Test
    void agent01_endpoints_withoutToken_401() {
        for (String path : List.of("/api/agent/config",
                "/api/meetings/whatever/runs", "/api/agent-runs/whatever")) {
            assertStatus(get(path, null), 401);
        }
        assertStatus(post("/api/meetings/whatever/runs", null, null), 401);
        assertStatus(post("/api/agent-runs/whatever/retry", null, null), 401);
    }

    /** AGENT-02:已配置模型时 /agent/config 如实上报能力与版本;只读角色也可读 */
    @Test
    void agent02_config_configuredTrue_reportsModelAndCapabilities() {
        for (String who : List.of(USER_ADMIN, USER_VIEWER)) {
            ApiResponse r = get("/api/agent/config", token(who));
            assertEquals(200, r.status(), r.body());
            assertTrue(r.json().path("configured").asBoolean(), r.body());
            assertEquals("fixture-model", r.json().path("model").asText(), r.body());
            assertTrue(r.json().path("worker_enabled").asBoolean(), "worker 应为开启:" + r.body());
            assertEquals("pool.create", r.json().path("supported_actions").path(0).asText(), r.body());
            assertEquals(PROMPT_VERSION, r.json().path("prompt_version").asText(), r.body());
        }
    }

    /** AGENT-03:未配置密钥时接口必须明确拒绝(503 + 可读 detail),不能默默排队 */
    @Test
    void agent03_startRun_withoutModelKey_503_notConfigured() {
        agentProps.setApiKey("");
        try {
            ApiResponse config = get("/api/agent/config", token(USER_ADMIN));
            assertEquals(200, config.status(), config.body());
            assertFalse(config.json().path("configured").asBoolean(),
                    "无密钥时 configured 必须为 false:" + config.body());

            String mid = createMeeting(uniq("CT无密钥会议"));
            ApiResponse run = post("/api/meetings/" + mid + "/runs", token(USER_ADMIN), null);
            // ApiException.server() 落在 503(Service Unavailable),不是 FastAPI 版的 500
            assertStatus(run, 503);
            assertTrue(detail(run).contains("密钥"), "detail 应说明未配置密钥: " + run.body());
            // 未配置时不得留下任何 run
            ApiResponse list = get("/api/meetings/" + mid + "/runs", token(USER_ADMIN));
            assertEquals(200, list.status(), list.body());
            assertEquals(0, list.json().size(), "未配置时不应创建 run: " + list.body());
        } finally {
            agentProps.setApiKey("fixture-test-key");
        }
    }

    /** AGENT-04:viewer 可读但不可写(发起分析/重试均 403),且权限校验先于存在性校验 */
    @Test
    void agent04_viewer_canRead_butCannotStartOrRetry() {
        String viewer = token(USER_VIEWER);
        assertEquals(200, get("/api/agent/config", viewer).status());
        String mid = createMeeting(uniq("CT只读会议"));
        assertStatus(post("/api/meetings/" + mid + "/runs", viewer, null), 403);
        assertStatus(post("/api/agent-runs/not-exist/retry", viewer, null), 403);
        assertEquals(200, get("/api/meetings/" + mid + "/runs", viewer).status());
    }

    /** AGENT-05:会议不存在 → 404(发起/列表);run 不存在 → 404(读取/重试) */
    @Test
    void agent05_meetingAndRunNotFound_404() {
        assertStatus(post("/api/meetings/no-such-meeting/runs", token(USER_ADMIN), null), 404);
        assertStatus(get("/api/meetings/no-such-meeting/runs", token(USER_ADMIN)), 404);
        assertStatus(get("/api/agent-runs/no-such-run", token(USER_ADMIN)), 404);
        assertStatus(post("/api/agent-runs/no-such-run/retry", token(USER_ADMIN), null), 404);
    }

    // ------------------------------------------------------------------
    // 2. 排队幂等与重试闸门
    // ------------------------------------------------------------------

    /** AGENT-06:同一会议重复发起只会有 1 个 run(幂等),第二次返回同一条 */
    @Test
    void agent06_startRun_twice_isIdempotent_singleRunRow() {
        String mid = createMeeting(uniq("CT幂等会议"));
        ApiResponse first = post("/api/meetings/" + mid + "/runs", token(USER_ADMIN), null);
        assertEquals(200, first.status(), first.body());
        String runId = first.json().path("id").asText();
        assertFalse(runId.isEmpty(), first.body());

        ApiResponse second = post("/api/meetings/" + mid + "/runs", token(USER_ADMIN), null);
        assertEquals(200, second.status(), second.body());
        assertEquals(runId, second.json().path("id").asText(), "重复发起应返回同一个 run:" + second.body());

        ApiResponse list = get("/api/meetings/" + mid + "/runs", token(USER_ADMIN));
        assertEquals(200, list.status(), list.body());
        assertEquals(1, list.json().size(), "同一会议只应有一条 run:" + list.body());

        JsonNode run = awaitTerminal(runId);
        assertTrue(List.of("awaiting_review", "completed").contains(run.path("status").asText()), run.toString());
    }

    /** AGENT-07:只有 failed 的 run 可重试;正常完成的 run 重试必须 409 */
    @Test
    void agent07_retry_successfulRun_409() {
        String mid = createMeeting(uniq("CT重试闸门"));
        String runId = startRun(mid);
        JsonNode run = awaitTerminal(runId);
        assertEquals("awaiting_review", run.path("status").asText(), run.toString());

        ApiResponse retry = post("/api/agent-runs/" + runId + "/retry", token(USER_ADMIN), null);
        assertStatus(retry, 409);
        assertTrue(detail(retry).contains("只能重试失败的分析"), retry.body());
    }

    // ------------------------------------------------------------------
    // 3. 完整分析链路(worker 真实消费 + fixture 模型)
    // ------------------------------------------------------------------

    /** AGENT-08:正常链路 —— 排队→分析→严格校验→建议落库,run 进入 awaiting_review,事件链完整 */
    @Test
    void agent08_happyPath_awaitingReview_withPendingSuggestionAndEventChain() {
        String title = uniq("Agent新增需求");
        fixture.prepare(Scenario.HAPPY, title);
        String mid = createMeeting(uniq("CT正常分析"));

        String runId = startRun(mid);
        JsonNode run = awaitTerminal(runId);

        assertEquals("awaiting_review", run.path("status").asText(), "有建议时应为待审核:" + run);
        assertEquals(1, run.path("attempt").asInt(), run.toString());
        assertEquals("fixture-model", run.path("model").asText(), run.toString());
        assertEquals(PROMPT_VERSION, run.path("prompt_version").asText(), run.toString());
        assertTrue(run.path("error_code").isNull(), "正常链路不应有 error_code:" + run);

        JsonNode result = run.path("result");
        assertEquals(1, result.path("suggestion_ids").size(), result.toString());
        assertEquals(0, result.path("skipped_proposals").size(), result.toString());
        assertTrue(result.path("limitations").size() >= 1, "应声明能力边界:" + result);
        assertTrue(result.path("summary").asText().contains("3 个会议片段"),
                "校验后的分析结果应原样落库:" + result);

        List<String> kinds = eventKinds(run);
        for (String expected : List.of("queued", "started", "context", "model_step", "tool",
                "model_final", "validated", "completed")) {
            assertTrue(kinds.contains(expected), "事件链缺少 " + expected + ":" + kinds);
        }
        assertTrue(kinds.indexOf("started") < kinds.indexOf("validated"), kinds.toString());
        assertTrue(kinds.indexOf("validated") < kinds.indexOf("completed"), kinds.toString());

        JsonNode suggestion = suggestionByTitle(mid, title);
        assertNotNull(suggestion, "分析产生的建议应出现在待审队列: " + title);
        assertEquals("pending", suggestion.path("status").asText(), suggestion.toString());
        assertEquals("agent", suggestion.path("origin").asText(), suggestion.toString());
        assertEquals(runId, suggestion.path("agent_run_id").asText(), suggestion.toString());
        assertEquals("Could", suggestion.path("changes").path("priority").asText(),
                "Agent 新需求初始优先级必须由系统默认 Could:" + suggestion);
        assertTrue(TRANSCRIPT.contains(suggestion.path("evidence").asText()),
                "建议证据必须是会议原文的连续片段:" + suggestion);

        // 未经人工审核,不得写入需求池
        assertEquals(0, poolSize(), "Agent 只提建议,不得直接写需求池");
    }

    /** AGENT-09:建议标题与已有故事重名 → 去重跳过,run 完成但没有建议 */
    @Test
    void agent09_proposalTitleConflict_skipped_completedWithoutSuggestion() {
        JsonNode us01 = storyFromList("US01");
        assertNotNull(us01, "种子故事 US01 必须存在");
        String existingTitle = us01.path("title").asText();
        fixture.prepare(Scenario.PROPOSAL_CONFLICT, existingTitle);
        String mid = createMeeting(uniq("CT重名会议"));

        JsonNode run = awaitTerminal(startRun(mid));
        assertEquals("completed", run.path("status").asText(), "无新建议时应为 completed:" + run);
        JsonNode result = run.path("result");
        assertEquals(0, result.path("suggestion_ids").size(), result.toString());
        assertEquals(1, result.path("skipped_proposals").size(), result.toString());
        assertEquals(existingTitle, result.path("skipped_proposals").path(0).path("title").asText(), result.toString());
        assertTrue(result.path("skipped_proposals").path(0).path("reason").asText().contains("同名"),
                result.toString());
        assertNull(suggestionByTitle(mid, existingTitle), "重名建议不得落库");
        assertEquals(0, poolSize(), "重名建议不得进需求池");
    }

    /** AGENT-10:证据无法在原文核对 → invalid_evidence,且事务回滚不落任何建议 */
    @Test
    void agent10_evidenceNotVerbatim_failedInvalidEvidence_noSuggestion() {
        String title = uniq("Agent伪造证据");
        fixture.prepare(Scenario.INVALID_EVIDENCE, title);
        String mid = createMeeting(uniq("CT伪造证据"));

        JsonNode run = awaitTerminal(startRun(mid));
        assertEquals("failed", run.path("status").asText(), run.toString());
        assertEquals("invalid_evidence", run.path("error_code").asText(), run.toString());
        assertTrue(run.path("error_message").asText().contains("未创建建议"), run.toString());
        assertTrue(run.path("result").isNull(), "失败时不应有分析结果:" + run);
        assertNull(suggestionByTitle(mid, title), "证据不实的建议不得落库");
        assertEquals(0, poolSize(), "失败链路不得写需求池");
    }

    /** AGENT-11:模型第一次输出漏片段 → 自动覆盖重试(repair),补全后仍然落建议 */
    @Test
    void agent11_coverageRetry_recoversOnSecondFinalOutput() {
        String title = uniq("Agent覆盖重试");
        fixture.prepare(Scenario.COVERAGE_FIRST, title);
        String mid = createMeeting(uniq("CT覆盖重试"));

        JsonNode run = awaitTerminal(startRun(mid));
        assertEquals("awaiting_review", run.path("status").asText(), "补全后应恢复正常:" + run);
        assertEquals(2, fixture.finalCallCount(), "应发生一次覆盖重试(共 2 次最终输出)");
        List<String> kinds = eventKinds(run);
        assertTrue(kinds.contains("coverage_retry"), "事件链应记录覆盖重试:" + kinds);
        assertTrue(kinds.contains("model_repair"), "事件链应记录修复调用:" + kinds);
        assertNotNull(suggestionByTitle(mid, title), "补全后应正常落建议");
    }

    /** AGENT-12:模型请求未授权工具 → tool_not_allowed,直接失败不落建议 */
    @Test
    void agent12_unauthorizedTool_failedToolNotAllowed() {
        String title = uniq("Agent越权工具");
        fixture.prepare(Scenario.TOOL_NOT_ALLOWED, title);
        String mid = createMeeting(uniq("CT越权工具"));

        JsonNode run = awaitTerminal(startRun(mid));
        assertEquals("failed", run.path("status").asText(), run.toString());
        assertEquals("tool_not_allowed", run.path("error_code").asText(), run.toString());
        assertTrue(run.path("error_message").asText().contains("未授权工具"), run.toString());
        assertNull(suggestionByTitle(mid, title), "越权链路不得产生建议");
    }

    /** AGENT-13:上游 500 → provider_error;失败可重试,重试成功后 attempt 递增且不泄露上游文案 */
    @Test
    void agent13_providerError_failedWithoutLeakingUpstream_thenRetrySucceeds() {
        String failTitle = uniq("Agent上游故障");
        fixture.prepare(Scenario.PROVIDER_ERROR, failTitle);
        String mid = createMeeting(uniq("CT上游故障"));
        String runId = startRun(mid);

        JsonNode failed = awaitTerminal(runId);
        assertEquals("failed", failed.path("status").asText(), failed.toString());
        assertEquals("provider_error", failed.path("error_code").asText(), failed.toString());
        assertTrue(failed.path("error_message").asText().contains("HTTP 500"), failed.toString());
        assertFalse(failed.toString().contains(AgentModelFixture.LEAK_CANARY),
                "上游响应体绝不能回显(凭据/隐私回显风险):" + failed);

        // 上游恢复后重试:同一条 run 重新排队,attempt=2
        String okTitle = uniq("Agent重试成功");
        fixture.prepare(Scenario.HAPPY, okTitle);
        ApiResponse retry = post("/api/agent-runs/" + runId + "/retry", token(USER_ADMIN), null);
        assertEquals(200, retry.status(), retry.body());
        assertEquals(runId, retry.json().path("id").asText(), retry.body());
        assertEquals(2, retry.json().path("attempt").asInt(), retry.body());
        assertTrue(List.of("queued", "running").contains(retry.json().path("status").asText()),
                "重试后应重新排队(worker 可能已开始消费):" + retry.body());

        JsonNode recovered = awaitTerminal(runId);
        assertEquals("awaiting_review", recovered.path("status").asText(), recovered.toString());
        assertEquals(2, recovered.path("attempt").asInt(), recovered.toString());
        assertTrue(recovered.path("error_code").isNull(), "重试成功后应清空 error_code:" + recovered);
        assertNotNull(suggestionByTitle(mid, okTitle), "重试后应正常落建议");
        assertNull(suggestionByTitle(mid, failTitle), "失败那一次不得留下建议");
    }

    /** AGENT-14:重试次数受控 —— 反复失败不会无限重试,重试后仍失败则为 failed 且可再重试 */
    @Test
    void agent14_retry_afterFailure_staysFailedWhenProviderStillDown() {
        String title = uniq("Agent二次故障");
        fixture.prepare(Scenario.PROVIDER_ERROR, title);
        String mid = createMeeting(uniq("CT二次故障"));
        String runId = startRun(mid);
        assertEquals("failed", awaitTerminal(runId).path("status").asText());

        ApiResponse retry = post("/api/agent-runs/" + runId + "/retry", token(USER_ADMIN), null);
        assertEquals(200, retry.status(), retry.body());
        JsonNode again = awaitTerminal(runId);
        assertEquals("failed", again.path("status").asText(), again.toString());
        assertEquals("provider_error", again.path("error_code").asText(), again.toString());
        assertEquals(2, again.path("attempt").asInt(), again.toString());
        assertEquals(0, poolSize(), "始终失败时需求池必须保持为空");
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private String createMeeting(String title) {
        ApiResponse r = post("/api/meetings", token(USER_ADMIN),
                json(map("title", title, "transcript", TRANSCRIPT)));
        assertEquals(201, r.status(), r.body());
        String id = r.json().path("id").asText();
        assertFalse(id.isEmpty(), r.body());
        return id;
    }

    private String startRun(String meetingId) {
        ApiResponse r = post("/api/meetings/" + meetingId + "/runs", token(USER_ADMIN), null);
        assertEquals(200, r.status(), r.body());
        String runId = r.json().path("id").asText();
        assertFalse(runId.isEmpty(), r.body());
        assertEquals("queued", r.json().path("status").asText(), r.body());
        return runId;
    }

    /** 轮询到 run 进入终态(queued/running 之外的任意状态);worker 每 1s 轮询一次队列 */
    private JsonNode awaitTerminal(String runId) {
        long deadline = System.currentTimeMillis() + 90_000L;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            ApiResponse r = get("/api/agent-runs/" + runId, token(USER_ADMIN));
            assertEquals(200, r.status(), r.body());
            last = r.json();
            String status = last.path("status").asText();
            if (!"queued".equals(status) && !"running".equals(status)) {
                return last;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("run 未在 90s 内进入终态,最后状态: " + last);
        return last;
    }

    private List<String> eventKinds(JsonNode run) {
        List<String> kinds = new ArrayList<>();
        for (JsonNode event : run.path("events")) {
            kinds.add(event.path("kind").asText());
        }
        return kinds;
    }

    private JsonNode suggestionByTitle(String meetingId, String title) {
        ApiResponse r = get("/api/suggestions?meetingId=" + meetingId, token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());
        for (JsonNode s : r.json()) {
            if (title.equals(s.path("changes").path("title").asText())) {
                return s;
            }
        }
        return null;
    }

    private int poolSize() {
        ApiResponse r = get("/api/pool", token(USER_ADMIN));
        assertEquals(200, r.status(), r.body());
        return r.json().size();
    }
}
