package com.aicap.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qdrant 向量库实现 —— 直接用 JDK {@code HttpClient} 调 REST,<b>不引 Java SDK</b>。
 *
 * <p>不引 SDK 是刻意的:少一个依赖,且 REST 契约(建 collection / upsert / search / delete)
 * 是自己读文档调通的,面试追问「Qdrant 的 filter 怎么表达」时答得出细节。
 *
 * <p><b>point id 的坑</b>:Qdrant 的 point id 只接受无符号整数或 UUID,
 * 而 AIcap 的 chunk id 是 {@code story:US01:0} 这种可读字符串。这里用
 * {@link UUID#nameUUIDFromBytes} 做确定性映射 —— 同一 chunk id 永远得到同一 UUID,
 * 因此重索引是覆盖而不是新增。真实 chunk id 存在 payload 里,检索返回时取回。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aicap.rag.vector-store", havingValue = "qdrant")
public class QdrantVectorStore implements VectorStore {

    private final RagProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public QdrantVectorStore(RagProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getQdrant().getTimeoutSeconds()))
                .build();
    }

    @Override
    public String name() {
        return "qdrant";
    }

    @Override
    public void ensureCollection(int dimension) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode vectors = mapper.createObjectNode();
        vectors.put("size", dimension);
        vectors.put("distance", "Cosine");
        body.set("vectors", vectors);
        // 已存在时 Qdrant 返回 result=false,不视为错误(幂等)
        try {
            call("PUT", "/collections/" + enc(props.getQdrant().getCollection()), body, false);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Qdrant collection 创建失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void upsertBatch(List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        ArrayNode points = mapper.createArrayNode();
        for (Entry e : entries) {
            ObjectNode point = mapper.createObjectNode();
            point.put("id", pointId(e.id()));
            ArrayNode vec = mapper.createArrayNode();
            for (float f : e.vector()) {
                vec.add(f);
            }
            point.set("vector", vec);
            point.set("payload", payloadNode(e));
            points.add(point);
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("points", points);
        call("PUT", "/collections/" + enc(collection()) + "/points?wait=true", body, true);
    }

    @Override
    public void deleteBySource(String sourceType, String sourceId) {
        ObjectNode body = mapper.createObjectNode();
        body.set("filter", matchAll(List.of(
                match("source_type", sourceType),
                match("source_id", sourceId))));
        call("POST", "/collections/" + enc(collection()) + "/points/delete?wait=true", body, true);
    }

    @Override
    public void deleteByIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        ObjectNode cond = mapper.createObjectNode();
        cond.put("key", "chunk_id");
        ObjectNode m = mapper.createObjectNode();
        ArrayNode any = mapper.createArrayNode();
        chunkIds.forEach(any::add);
        m.set("any", any);
        cond.set("match", m);
        body.set("filter", matchAll(List.of(cond)));
        call("POST", "/collections/" + enc(collection()) + "/points/delete?wait=true", body, true);
    }

    @Override
    public List<ScoredId> search(float[] queryVector, int topK, RetrievalContext ctx) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode vec = mapper.createArrayNode();
        for (float f : queryVector) {
            vec.add(f);
        }
        body.set("vector", vec);
        body.put("limit", topK);
        body.put("with_payload", true);

        ArrayNode must = mapper.createArrayNode();
        // ACL:acl_rank 是「最低可见角色」的数值化,range.lte 表达「等级不高于调用者」
        ObjectNode acl = mapper.createObjectNode();
        acl.put("key", "acl_rank");
        ObjectNode range = mapper.createObjectNode();
        range.put("lte", ctx.roleRank());
        acl.set("range", range);
        must.add(acl);
        if (ctx.allowedSourceTypes() != null && !ctx.allowedSourceTypes().isEmpty()) {
            ObjectNode match = mapper.createObjectNode();
            match.put("key", "source_type");
            ObjectNode m = mapper.createObjectNode();
            ArrayNode any = mapper.createArrayNode();
            ctx.allowedSourceTypes().forEach(any::add);
            m.set("any", any);
            match.set("match", m);
            must.add(match);
        }
        ObjectNode filter = mapper.createObjectNode();
        filter.set("must", must);
        body.set("filter", filter);

        JsonNode result = call("POST", "/collections/" + enc(collection()) + "/points/search", body, true)
                .path("result");
        List<ScoredId> out = new ArrayList<>();
        if (result.isArray()) {
            for (JsonNode hit : result) {
                JsonNode payload = hit.path("payload");
                // chunk_id 取 payload 里的真实 id,不是 Qdrant 的 UUID point id
                String chunkId = payload.path("chunk_id").asText(hit.path("id").asText());
                out.add(new ScoredId(chunkId, hit.path("score").asDouble(), toMap(payload)));
            }
        }
        return out;
    }

    @Override
    public long count() {
        ObjectNode body = mapper.createObjectNode();
        body.put("exact", true);
        return call("POST", "/collections/" + enc(collection()) + "/points/count", body, true)
                .path("result").path("count").asLong(0);
    }

    // ------------------------------------------------------------------

    private String collection() {
        return props.getQdrant().getCollection();
    }

    /** 确定性映射:同一 chunk id 恒得同一 UUID,保证重索引是覆盖而非重复插入 */
    static String pointId(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private ObjectNode payloadNode(Entry e) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("chunk_id", e.id());
        for (Map.Entry<String, Object> kv : e.payload().entrySet()) {
            Object v = kv.getValue();
            if (v == null) {
                payload.putNull(kv.getKey());
            } else if (v instanceof Integer i) {
                payload.put(kv.getKey(), i);
            } else if (v instanceof Long l) {
                payload.put(kv.getKey(), l);
            } else if (v instanceof Double d) {
                payload.put(kv.getKey(), d);
            } else if (v instanceof Boolean b) {
                payload.put(kv.getKey(), b);
            } else {
                payload.put(kv.getKey(), String.valueOf(v));
            }
        }
        return payload;
    }

    private ObjectNode match(String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key", key);
        ObjectNode m = mapper.createObjectNode();
        m.put("value", value);
        node.set("match", m);
        return node;
    }

    private ObjectNode matchAll(List<ObjectNode> conditions) {
        ObjectNode filter = mapper.createObjectNode();
        ArrayNode must = mapper.createArrayNode();
        conditions.forEach(must::add);
        filter.set("must", must);
        return filter;
    }

    private Map<String, Object> toMap(JsonNode payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (payload != null && payload.isObject()) {
            payload.fields().forEachRemaining(f -> out.put(f.getKey(), f.getValue().asText()));
        }
        return out;
    }

    /** 发请求并解析 JSON;{@code failOnHttpError} 为 false 时忽略非 200(用于幂等建 collection) */
    private JsonNode call(String method, String path, ObjectNode body, boolean failOnHttpError) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(props.getQdrant().getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 请求构造失败", e);
        }
        final HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 请求被中断");
        } catch (IOException e) {
            throw new IllegalStateException("无法连接 Qdrant(" + base() + "),请确认容器已启动", e);
        }
        if (response.statusCode() != 200) {
            if (!failOnHttpError) {
                log.debug("Qdrant {} {} 返回 {}", method, path, response.statusCode());
                return mapper.createObjectNode();
            }
            throw new IllegalStateException("Qdrant 返回 HTTP " + response.statusCode() + "(" + method + " " + path + ")");
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 返回了无法解析的响应");
        }
    }

    private String base() {
        return props.getQdrant().getBaseUrl().replaceAll("/+$", "");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
