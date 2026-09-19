package com.aicap.contract;

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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 OpenAI 兼容 {@code /embeddings} fixture(仅测试使用)。
 *
 * <p>目的:让「切分 → 向量化 → 落库 → 检索」整条链在<b>不访问外网、不产生费用、
 * 结果可复现</b>的前提下被自动化验证。对齐 {@link AgentModelFixture} 的做法,
 * 只是把 chat 换成 embeddings。
 *
 * <p><b>向量是"有意义"的,不是随机数</b>:按字符与字符二元组做哈希装桶再归一化,
 * 因此共享用词的文本余弦相似度更高 —— 检索断言才有意义(随机向量下任何两条
 * 文本的相似度都差不多,测不出召回对不对)。
 *
 * <p>{@link #embeddedTexts()} 记录"被向量化的文本条数",用来断言增量索引真的跳过了
 * 未变内容:第二次重建这个数应当是 0。
 */
final class EmbeddingFixture {

    /** 测试用小维度:够区分文本,又让断言输出不至于刷屏 */
    static final int DIMENSION = 64;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PORT = 19378;

    private HttpServer server;
    private final AtomicInteger embeddedTexts = new AtomicInteger(0);
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/embeddings", this::handle);
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

    /** 至今被向量化的文本条数(按请求里的 input 条数累计) */
    int embeddedTexts() {
        return embeddedTexts.get();
    }

    void resetCounter() {
        embeddedTexts.set(0);
        calls.clear();
    }

    List<String> callLog() {
        return List.copyOf(calls);
    }

    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        try {
            ObjectNode request = (ObjectNode) MAPPER.readTree(body);
            if (!request.path("input").isArray()) {
                respond(exchange, 400, "{\"error\":{\"message\":\"input must be an array\"}}");
                return;
            }
            ArrayNode data = MAPPER.createArrayNode();
            int index = 0;
            for (var text : request.path("input")) {
                ObjectNode item = MAPPER.createObjectNode();
                item.put("object", "embedding");
                item.put("index", index++);
                ArrayNode vec = MAPPER.createArrayNode();
                for (float f : vectorOf(text.asText(""), DIMENSION)) {
                    vec.add(f);
                }
                item.set("embedding", vec);
                data.add(item);
            }
            embeddedTexts.addAndGet(data.size());
            calls.add("embed:" + data.size());

            ObjectNode out = MAPPER.createObjectNode();
            out.put("object", "list");
            out.set("data", data);
            out.put("model", request.path("model").asText("fixture-embed"));
            respond(exchange, 200, out.toString());
        } catch (Exception e) {
            calls.add("error");
            respond(exchange, 400, "{\"error\":{\"message\":\"bad request\"}}");
        }
    }

    /**
     * 字符 + 字符二元组装桶后 L2 归一化。
     * 用二元组是为了让「会议转写」与「会议录音」比「会议转写」与「成员画像」更接近。
     */
    static float[] vectorOf(String text, int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < text.length(); i++) {
            v[Math.floorMod(Character.hashCode(text.charAt(i)), dim)] += 1f;
            if (i + 1 < text.length()) {
                v[Math.floorMod(text.charAt(i) * 31 + text.charAt(i + 1), dim)] += 1f;
            }
        }
        double norm = 0;
        for (float f : v) {
            norm += (double) f * f;
        }
        if (norm > 0) {
            float inv = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < dim; i++) {
                v[i] *= inv;
            }
        }
        return v;
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
