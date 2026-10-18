package com.aicap.meeting;

import com.aicap.common.AgentError;
import com.aicap.platform.MeetingType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeetingAgentClient} 的协议契约(纯单测:本地 HttpServer 当 ai-service,零外网零费用)。
 *
 * <p>为什么必须有这一层:平台层到 ai-service 是<b>跨进程、跨语言</b>的调用,路径拼错、
 * 枚举字面量写错、令牌没带上,这三类错误都<b>不会</b>被编译拦住 —— 只会在真机上表现为
 * 一个 422/404,而且要跑起整条模型链才看得见。这里用 fixture 把"发出去的那一发"钉死。
 *
 * <p>同时钉住红线②:上游非 200 时,响应体里埋的 canary <b>绝不</b>能出现在异常消息里。
 */
class MeetingAgentClientTest {

    private static final String CANARY = "CANARY-响应体不得回显-42";
    private static final String MEETING = "87c028e0-f54d-4451-acae-2185f6b3f0e1";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final List<String> requestPaths = new ArrayList<>();
    private final List<JsonNode> requestBodies = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();

    /** fixture 的应答剧本 */
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void startFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/meetings/", exchange -> {
            requestPaths.add(exchange.getRequestURI().getPath());
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try (InputStream in = exchange.getRequestBody()) {
                byte[] raw = in.readAllBytes();
                requestBodies.add(raw.length == 0 ? mapper.createObjectNode()
                        : mapper.readTree(new String(raw, StandardCharsets.UTF_8)));
            }
            respond(exchange, status, responseBody);
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    @AfterEach
    void stopFixture() {
        server.stop(0);
    }

    private MeetingAgentClient client() {
        MeetingAgentProperties props = new MeetingAgentProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setReadTimeoutSeconds(10);
        return new MeetingAgentClient(props, mapper);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 五类的端点各不相同 —— 拼错一个就是一个 404,所以逐类钉死 */
    @Test
    void eachTypeHitsItsOwnEndpoint() {
        Map<MeetingType, String> expected = Map.of(
                MeetingType.STATUS, "/api/meetings/" + MEETING + "/analyze",
                MeetingType.PLANNING, "/api/meetings/" + MEETING + "/planning/analyze",
                MeetingType.REVIEW, "/api/meetings/" + MEETING + "/review/analyze",
                MeetingType.RETRO, "/api/meetings/" + MEETING + "/retro/analyze",
                MeetingType.REFINEMENT, "/api/meetings/" + MEETING + "/refinement/analyze");

        responseBody = "{\"analysis_id\":\"a-1\",\"meeting_id\":\"" + MEETING + "\",\"storage_status\":\"no_changes\"}";
        MeetingAgentClient client = client();
        for (MeetingType type : MeetingType.values()) {
            client.analyze(MEETING, type, null, null, "tok");
            assertEquals(expected.get(type), requestPaths.get(requestPaths.size() - 1),
                    "端点路径不对: " + type);
        }
    }

    /** 请求体:client_request_id 由本层生成(幂等键),meeting_type 用 ai-service 的字面量 */
    @Test
    void requestBodyCarriesWireTypeAndGeneratedRequestId() {
        responseBody = "{\"analysis_id\":\"a-2\",\"meeting_id\":\"" + MEETING + "\",\"storage_status\":\"pending\"}";
        MeetingAgentClient.Analyzed done =
                client().analyze(MEETING, MeetingType.PLANNING, 2, 3, "tok");

        JsonNode body = requestBodies.get(0);
        assertEquals("sprint_planning", body.path("meeting_type").asText(), "必须发线上字面量: " + body);
        assertEquals(2, body.path("current_sprint").asInt(), body.toString());
        assertEquals(3, body.path("target_sprint").asInt(), body.toString());
        assertFalse(body.path("client_request_id").asText().isEmpty(), "幂等键必须带上: " + body);

        assertEquals("a-2", done.analysisId());
        assertEquals("pending", done.storageStatus());
    }

    /** 没给 sprint 时不能凭空补一个数(补了就等于给模型错误前提) */
    @Test
    void absentSprintFieldsAreSimplyOmitted() {
        responseBody = "{\"analysis_id\":\"a-3\",\"meeting_id\":\"" + MEETING + "\",\"storage_status\":\"no_changes\"}";
        client().analyze(MEETING, MeetingType.RETRO, null, null, "tok");
        JsonNode body = requestBodies.get(0);
        assertFalse(body.has("current_sprint"), "没给就不该出现: " + body);
        assertFalse(body.has("target_sprint"), "没给就不该出现: " + body);
    }

    /** 调用方令牌必须原样进 Authorization(ai-service 靠它读回 Java) */
    @Test
    void callerTokenIsForwardedAsBearer() {
        responseBody = "{\"analysis_id\":\"a-4\",\"meeting_id\":\"" + MEETING + "\",\"storage_status\":\"no_changes\"}";
        client().analyze(MEETING, MeetingType.REVIEW, null, null, "tok-abc");
        assertEquals("Bearer tok-abc", authHeaders.get(0), "令牌没带上就必然 401");
    }

    /** 令牌缺失:当场拒,不发请求(也就不会浪费一次模型调用) */
    @Test
    void missingTokenIsRejectedBeforeAnyRequest() {
        AgentError error = assertThrows(AgentError.class,
                () -> client().analyze(MEETING, MeetingType.STATUS, null, null, " "));
        assertEquals("invalid_arguments", error.getCode());
        assertTrue(requestPaths.isEmpty(), "参数就不全,不该发出去");
    }

    /** 上游非 200:只取稳定错误码,响应体原文**绝不**进异常(红线②) */
    @Test
    void upstreamErrorYieldsCodeOnly_neverEchoesBody() {
        status = 422;
        responseBody = "{\"detail\":\"unresolved_owner\",\"canary\":\"" + CANARY + "\"}";
        AgentError error = assertThrows(AgentError.class,
                () -> client().analyze(MEETING, MeetingType.RETRO, null, null, "tok"));
        assertEquals("unresolved_owner", error.getCode(), "应取上游的稳定错误码");
        assertFalse(error.getMessage().contains(CANARY), "响应体不得回显: " + error.getMessage());
    }

    /** 上游给了非错误码的 detail(如校验错误数组)时,按状态码归类,而不是把原文当码 */
    @Test
    void unstructuredUpstreamErrorFallsBackToStatusClass() {
        status = 503;
        responseBody = "{\"detail\":[{\"loc\":[\"body\"],\"msg\":\"" + CANARY + "\"}]}";
        AgentError error = assertThrows(AgentError.class,
                () -> client().analyze(MEETING, MeetingType.STATUS, null, null, "tok"));
        assertEquals("agent_unavailable", error.getCode());
        assertFalse(error.getMessage().contains(CANARY), "响应体不得回显: " + error.getMessage());
    }

    /** 200 但没有 analysis_id:产物指针是这条链的全部价值,缺了必须如实失败 */
    @Test
    void okWithoutAnalysisIdIsAFailure() {
        responseBody = "{\"meeting_id\":\"" + MEETING + "\"}";
        AgentError error = assertThrows(AgentError.class,
                () -> client().analyze(MEETING, MeetingType.STATUS, null, null, "tok"));
        assertEquals("invalid_model_proposal", error.getCode());
    }

    /** 8090 没起:报可辨认的不可用错误,而不是 500 或静默 */
    @Test
    void unreachableAgentReportsUnavailable() {
        MeetingAgentProperties props = new MeetingAgentProperties();
        props.setBaseUrl("http://127.0.0.1:1");   // 1 号端口正常不会有服务
        props.setConnectTimeoutSeconds(1);
        MeetingAgentClient offline = new MeetingAgentClient(props, mapper);
        AgentError error = assertThrows(AgentError.class,
                () -> offline.analyze(MEETING, MeetingType.STATUS, null, null, "tok"));
        assertEquals("agent_unavailable", error.getCode());
        assertNotNull(error.getMessage());
    }

    /** 关掉开关时,工具层应当拿到 agent_tool_unavailable(与 catalog 不发布是同一口径) */
    @Test
    void disabledAgentRejectsWithoutRequest() {
        MeetingAgentProperties props = new MeetingAgentProperties();
        props.setEnabled(false);
        MeetingAgentClient disabled = new MeetingAgentClient(props, mapper);
        AgentError error = assertThrows(AgentError.class,
                () -> disabled.analyze(MEETING, MeetingType.STATUS, null, null, "tok"));
        assertEquals("agent_tool_unavailable", error.getCode());
    }
}
