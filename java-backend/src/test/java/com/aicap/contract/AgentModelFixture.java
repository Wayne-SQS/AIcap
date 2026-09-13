package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 OpenAI 兼容 fixture 模型服务(仅测试使用)。
 * <p>
 * 目的:让会议 Agent 的完整链路(HTTP 排队 → AgentWorker 消费 → 工具循环 → 严格校验 → 建议落库)
 * 在<b>不访问外网、不产生费用、结果完全可复现</b>的前提下被自动化验证。
 * 对齐 `qa/agent_provider_fixture.py` 的 FastAPI 版本,这里是 Java 契约测试自带的等价物。
 * <p>
 * 协议要点(与 {@code ModelClient} 的解析严格对齐):
 * <ul>
 *   <li>非最终轮(请求带 {@code tools}/{@code tool_choice}):第 1 次返回 tool_calls
 *       (search_stories + search_pool),之后返回不带工具调用的普通回复;</li>
 *   <li>最终轮(请求带 {@code response_format=json_object}):返回分析 JSON 文本,
 *       证据引用由请求里回传的 {@code segments} 真实构造,保证"逐字引用"校验可通过;</li>
 *   <li>{@link Scenario#PROVIDER_ERROR} 直接返回 500,响应体里埋了 canary 字符串,
 *       用于验证"上游响应体绝不落库/回显"。</li>
 * </ul>
 */
final class AgentModelFixture {

    /** fixture 对"最终输出"的应答方式 */
    enum Scenario {
        /** 正常:覆盖全部片段 + 1 条 pool.create 建议 → run 应为 awaiting_review */
        HAPPY,
        /** 建议标题与已有故事同名 → 应被去重跳过,run 应为 completed 且不落建议 */
        PROPOSAL_CONFLICT,
        /** 建议证据不是会议原文的连续片段 → 应判 invalid_evidence */
        INVALID_EVIDENCE,
        /** 第一次最终输出漏掉部分片段 → 触发 coverage_retry,第二次补全后应通过 */
        COVERAGE_FIRST,
        /** 模型请求了未授权工具 → 应判 tool_not_allowed */
        TOOL_NOT_ALLOWED,
        /** 上游返回 HTTP 500 → 应判 provider_error(且不落上游文案) */
        PROVIDER_ERROR
    }

    /** 上游 500 响应体里的哨兵:绝不能被写进 run.error_message 或任何接口响应 */
    static final String LEAK_CANARY = "SECRET-LEAK-CANARY-9f1a7c";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private volatile Scenario scenario = Scenario.HAPPY;
    private volatile String proposalTitle = "fixture 建议标题";
    private final AtomicBoolean toolCallsIssued = new AtomicBoolean(false);
    private final AtomicInteger finalCalls = new AtomicInteger(0);
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 启动 fixture(端口固定,便于用 @SpringBootTest(properties=...) 指定 base-url) */
    int start() throws IOException {
        int port = 19377;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/chat/completions", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        return port;
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 每个用例开始前重置场景与计数 */
    void prepare(Scenario scenario, String proposalTitle) {
        this.scenario = scenario;
        this.proposalTitle = proposalTitle;
        toolCallsIssued.set(false);
        finalCalls.set(0);
        calls.clear();
    }

    Scenario scenario() {
        return scenario;
    }

    /** 收到的请求路径轨迹(仅用于诊断) */
    List<String> callLog() {
        return List.copyOf(calls);
    }

    int finalCallCount() {
        return finalCalls.get();
    }

    // ------------------------------------------------------------------
    // 请求处理
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String body = readAll(exchange.getRequestBody());
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

        if (scenario == Scenario.PROVIDER_ERROR) {
            calls.add("500");
            respond(exchange, 500, "{\"error\":{\"message\":\"" + LEAK_CANARY + "\"}}");
            return;
        }

        JsonNode request;
        try {
            request = MAPPER.readTree(body);
        } catch (Exception e) {
            calls.add("bad-request");
            respond(exchange, 400, "{\"error\":{\"message\":\"invalid json\"}}");
            return;
        }

        boolean finalRound = request.has("response_format");
        if (!finalRound && scenario == Scenario.TOOL_NOT_ALLOWED && !toolCallsIssued.getAndSet(true)) {
            calls.add("tool_calls:not_allowed");
            respond(exchange, 200, toolCallResponse("delete_story", "{\"id\":\"US01\"}").toString());
            return;
        }
        if (!finalRound) {
            if (toolCallsIssued.compareAndSet(false, true)) {
                calls.add("tool_calls:search");
                respond(exchange, 200, searchToolCalls().toString());
            } else {
                calls.add("assistant_text");
                respond(exchange, 200, plainMessage("已查询已有需求,准备输出最终 JSON").toString());
            }
            return;
        }

        int nth = finalCalls.incrementAndGet();
        calls.add("final#" + nth);
        respond(exchange, 200, plainMessage(finalJson(request, nth)).toString());
    }

    /** 第一次非最终轮:要求同时调用 search_stories 与 search_pool(runner 强制的两个查询工具) */
    private ObjectNode searchToolCalls() {
        ArrayNode callsNode = MAPPER.createArrayNode();
        callsNode.add(toolCall("call_search_stories", "search_stories", "{\"keyword\":\"导入\"}"));
        callsNode.add(toolCall("call_search_pool", "search_pool", "{\"keyword\":\"导入\"}"));
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        message.set("tool_calls", callsNode);
        return envelope("tool_calls", message);
    }

    private ObjectNode toolCallResponse(String name, String arguments) {
        ArrayNode callsNode = MAPPER.createArrayNode();
        callsNode.add(toolCall("call_bad_1", name, arguments));
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        message.set("tool_calls", callsNode);
        return envelope("tool_calls", message);
    }

    private ObjectNode toolCall(String id, String name, String arguments) {
        ObjectNode fn = MAPPER.createObjectNode();
        fn.put("name", name);
        fn.put("arguments", arguments);
        ObjectNode call = MAPPER.createObjectNode();
        call.put("id", id);
        call.put("type", "function");
        call.set("function", fn);
        return call;
    }

    private ObjectNode plainMessage(String content) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        return envelope("stop", message);
    }

    private ObjectNode envelope(String finishReason, ObjectNode message) {
        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("finish_reason", finishReason);
        choice.set("message", message);
        ArrayNode choices = MAPPER.createArrayNode();
        choices.add(choice);
        ObjectNode usage = MAPPER.createObjectNode();
        usage.put("prompt_tokens", 100);
        usage.put("completion_tokens", 50);
        usage.put("total_tokens", 150);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("choices", choices);
        root.set("usage", usage);
        return root;
    }

    // ------------------------------------------------------------------
    // 分析 JSON(证据全部由请求回传的 segments 真实构造)
    // ------------------------------------------------------------------

    private String finalJson(JsonNode request, int nth) {
        JsonNode segments = segmentsOf(request);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("summary", "fixture 已完成 " + (segments == null ? 0 : segments.size()) + " 个会议片段的分析");

        out.set("decisions", MAPPER.createArrayNode());
        out.set("action_items", MAPPER.createArrayNode());
        out.set("coordination_items", MAPPER.createArrayNode());
        out.set("status_constraints", MAPPER.createArrayNode());

        // 背景事实:逐片段覆盖(COVERAGE_FIRST 场景第一次故意只覆盖第 1 个片段)
        int covered = segments == null ? 0 : segments.size();
        if (scenario == Scenario.COVERAGE_FIRST && nth == 1) {
            covered = Math.min(1, covered);
        }
        ArrayNode notes = MAPPER.createArrayNode();
        for (int i = 0; i < covered; i++) {
            notes.add(factNode(segments.get(i)));
        }
        out.set("source_notes", notes);

        out.set("risks", MAPPER.createArrayNode());
        out.set("unresolved_questions", MAPPER.createArrayNode());

        ArrayNode proposals = MAPPER.createArrayNode();
        if (segments != null && segments.size() > 0 && scenario != Scenario.TOOL_NOT_ALLOWED) {
            ObjectNode proposal = MAPPER.createObjectNode();
            proposal.put("action", "pool.create");
            proposal.put("title", proposalTitle);
            proposal.put("description", "fixture 生成的会议新增需求,待人工审核");
            proposal.put("note", "fixture");
            proposal.set("evidence", proposalEvidence(segments.get(0)));
            proposals.add(proposal);
        }
        out.set("proposals", proposals);
        return out.toString();
    }

    /** 背景事实:{text, evidence:{segment_id, quote}}(quote 必须与片段逐字一致) */
    private ObjectNode factNode(JsonNode segment) {
        String text = segment.path("text").asText("");
        ObjectNode node = MAPPER.createObjectNode();
        node.put("text", "片段事实:" + text.substring(0, Math.min(60, text.length())));
        node.set("evidence", evidence(segment.path("segment_id").asText(), text));
        return node;
    }

    /** 建议证据:正常场景逐字引用;INVALID_EVIDENCE 场景故意给一段原文里没有的话 */
    private ObjectNode proposalEvidence(JsonNode firstSegment) {
        if (scenario == Scenario.INVALID_EVIDENCE) {
            return evidence(firstSegment.path("segment_id").asText(), "这条证据在会议原文中并不存在");
        }
        return evidence(firstSegment.path("segment_id").asText(), firstSegment.path("text").asText(""));
    }

    private ObjectNode evidence(String segmentId, String quote) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("segment_id", segmentId);
        node.put("quote", quote);
        return node;
    }

    /** 从 messages 里的用户负载中取出 runner 下发的 segments */
    private JsonNode segmentsOf(JsonNode request) {
        for (JsonNode message : request.path("messages")) {
            String content = message.path("content").asText("");
            if (content.isBlank() || content.charAt(0) != '{') {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(content);
                if (node.path("segments").isArray()) {
                    return node.path("segments");
                }
            } catch (Exception ignored) {
                // 非 JSON 负载,继续找
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // IO 工具
    // ------------------------------------------------------------------

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
