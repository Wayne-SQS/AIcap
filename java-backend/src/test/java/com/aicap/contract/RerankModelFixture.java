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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 精排专用的本地 OpenAI 兼容 fixture(仅测试使用,端口 19379)。
 *
 * <p>为什么不复用 {@link AgentModelFixture}:那个 fixture 的应答是围绕会议 Agent 的
 * 场景(工具调用轮次、证据片段覆盖校验)构造的,精排只需要"收到候选、回一个排序"。
 * 硬塞进去会让两边的场景枚举互相污染 —— 会议 Agent 加一个 scenario,精排测试可能就挂了。
 *
 * <p><b>排序规则是"反转候选顺序"</b>,不是随机:随机排序下"精排真的改变了顺序"与
 * "精排没生效、只是碰巧顺序不同"无法区分。反转是一个可预测的变换,断言才有意义。
 */
final class RerankModelFixture {

    /** fixture 的应答方式 */
    enum Scenario {
        /** 正常:把候选顺序反转后返回 */
        REVERSE,
        /** 返回非 JSON 的自由文本 → 精排应判为不可用并降级 */
        MALFORMED,
        /** 返回全部是编造的 id → 精排应判为降级(没有任何有效 id) */
        HALLUCINATED_IDS
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PORT = 19379;
    /** 从 prompt 里抓 {@code id=xxx} —— 与 LlmReranker.buildPrompt 的写法配套 */
    private static final Pattern ID_IN_PROMPT = Pattern.compile("id=(\\S+)");

    private HttpServer server;
    private volatile Scenario scenario = Scenario.REVERSE;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/chat/completions", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        return PORT;
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 每次用例前重置:场景是可变状态,不重置会让上一个用例的行为漏进下一个 */
    void prepare(Scenario scenario) {
        this.scenario = scenario;
        calls.clear();
    }

    int callCount() {
        return calls.size();
    }

    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        calls.add("chat");
        try {
            JsonNode request = MAPPER.readTree(body);
            List<String> ids = extractIds(request);
            String content = switch (scenario) {
                case REVERSE -> rankingJson(ids);
                case MALFORMED -> "抱歉,我无法完成这个排序任务。";
                case HALLUCINATED_IDS -> rankingJson(List.of("story:NOT_A_REAL_ID:0"));
            };
            ObjectNode out = MAPPER.createObjectNode();
            ArrayNode choices = MAPPER.createArrayNode();
            ObjectNode choice = MAPPER.createObjectNode();
            choice.put("finish_reason", "stop");
            ObjectNode message = MAPPER.createObjectNode();
            message.put("role", "assistant");
            message.put("content", content);
            choice.set("message", message);
            choices.add(choice);
            out.set("choices", choices);
            out.set("usage", MAPPER.createObjectNode().put("total_tokens", 1));
            respond(exchange, 200, out.toString());
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":{\"message\":\"bad request\"}}");
        }
    }

    /** 从 prompt 的候选列表里取出 id,保持出现顺序 */
    private static List<String> extractIds(JsonNode request) {
        List<String> ids = new ArrayList<>();
        for (JsonNode msg : request.path("messages")) {
            Matcher m = ID_IN_PROMPT.matcher(msg.path("content").asText(""));
            while (m.find()) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }

    /** 反转顺序;分数递减,理由写明是 fixture 行为,便于在 retrieval_logs 里辨认 */
    private static String rankingJson(List<String> ids) {
        List<String> reversed = new ArrayList<>(ids);
        Collections.reverse(reversed);
        ArrayNode ranking = MAPPER.createArrayNode();
        double score = 0.9;
        for (String id : reversed) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("id", id);
            item.put("score", score);
            item.put("reason", "fixture 反转排序");
            ranking.add(item);
            score = Math.max(0.1, score - 0.1);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("ranking", ranking);
        return root.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
