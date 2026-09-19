package com.aicap.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容 {@code /embeddings} 客户端。
 *
 * <p>刻意不引任何 SDK,与 {@link com.aicap.agent.ModelClient} 同一条路子:JDK {@code HttpClient}
 * 直调,自己解析。少一个依赖,且 REST 契约是自己能讲清的。
 *
 * <p><b>安全约定(继承 ModelClient)</b>:上游非 200 一律不落响应体 —— 响应体可能回显
 * 请求里的凭据或私有转写文本,写进日志/错误消息就是泄漏。
 */
@Slf4j
@Component
public class ApiEmbeddingModel implements EmbeddingModel {

    private final RagProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ApiEmbeddingModel(RagProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, props.getEmbedding().getTimeoutSeconds())))
                .build();
    }

    @Override
    public int dimension() {
        return props.getEmbedding().getDimension();
    }

    @Override
    public String name() {
        return props.getEmbedding().getModel();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        RagProperties.Embedding cfg = props.getEmbedding();
        if (!cfg.settingsReady()) {
            throw new EmbeddingException("not_configured");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        int batch = Math.max(1, cfg.getBatchSize());
        for (int i = 0; i < texts.size(); i += batch) {
            out.addAll(embedBatch(texts.subList(i, Math.min(i + batch, texts.size()))));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> texts) {
        RagProperties.Embedding cfg = props.getEmbedding();
        URI base = parseBaseUrl(cfg.getBaseUrl());

        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.getModel());
        ArrayNode input = mapper.createArrayNode();
        for (String t : texts) {
            // 超长直接截断:宁可少语义,也不要整批 400 —— 单条超限不该拖垮整次索引
            String s = t == null ? "" : t;
            input.add(s.length() > cfg.getMaxChars() ? s.substring(0, cfg.getMaxChars()) : s);
        }
        body.set("input", input);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(
                            URI.create(base + "/embeddings"))
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build();
        } catch (IOException e) {
            throw new EmbeddingException("向量化请求构造失败", e);
        }

        final HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new EmbeddingException("向量化请求超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("向量化已中断");
        } catch (IOException e) {
            throw new EmbeddingException(e.getCause() instanceof ConnectException || e instanceof ConnectException
                    ? "无法连接向量化服务"
                    : "无法连接向量化服务", e);
        }

        if (response.statusCode() != 200) {
            // 绝不落响应体;状态码足以定位问题
            log.warn("embedding 上游返回 HTTP {},本批 {} 条未索引", response.statusCode(), texts.size());
            throw new EmbeddingException("向量化服务返回 HTTP " + response.statusCode());
        }

        final JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new EmbeddingException("向量化服务返回了无法解析的响应");
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new EmbeddingException("向量化服务返回条数与请求不符");
        }
        List<float[]> vectors = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            JsonNode item = data.get(i);
            JsonNode emb = item.path("embedding");
            if (!emb.isArray() || emb.size() != cfg.getDimension()) {
                throw new EmbeddingException("向量化服务返回维度与配置(" + cfg.getDimension() + ")不符");
            }
            float[] v = new float[emb.size()];
            for (int j = 0; j < emb.size(); j++) {
                v[j] = (float) emb.get(j).asDouble();
            }
            vectors.add(v);
        }
        return vectors;
    }

    /** 对齐 ModelClient:仅允许 https;http 只对本机放行(本地 fixture/自建服务) */
    private static URI parseBaseUrl(String raw) {
        try {
            URI uri = URI.create(raw.replaceAll("/+$", ""));
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean localHttp = "http".equals(scheme)
                    && (host == null || "localhost".equals(host) || "127.0.0.1".equals(host));
            if (!"https".equals(scheme) && !localHttp) {
                throw new EmbeddingException("向量化地址需要 HTTPS(本机服务除外)");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new EmbeddingException("向量化地址无效");
        }
    }
}
