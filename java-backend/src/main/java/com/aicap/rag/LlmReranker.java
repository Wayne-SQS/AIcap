package com.aicap.rag;

import com.aicap.agent.AgentError;
import com.aicap.agent.AgentProperties;
import com.aicap.agent.ModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用 LLM 做 listwise 精排(设计文档 A6)。
 *
 * <p>为什么先做这个而不是直接上 {@code bge-reranker-v2-m3}:零新依赖(复用已有的
 * {@link ModelClient}),而且 <b>listwise 会顺带产出排序理由</b> —— 那些理由直接进
 * {@code retrieval_logs},让"这条为什么排第一"变成可读的一句话,而不是一个 0.83 的分数。
 * 调试体验上的收益,在 S7 建评测集时比几个点的准确率更值钱。代价是比专用
 * Cross-Encoder 慢且贵,所以配置里留了 {@code provider=none} 的直通开关。
 *
 * <p>输出用 JSON 而不是让模型回一个 id 列表:JSON 能被严格校验(未知 id 直接丢弃、
 * 分数越界直接夹紧),而自由文本一旦格式漂移就只能整体降级。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aicap.rag.retrieval.rerank.provider", havingValue = "llm", matchIfMissing = true)
public class LlmReranker implements Reranker {

    private static final int REASON_MAX_CHARS = 40;

    private final ModelClient modelClient;
    private final AgentProperties agentProps;
    private final RagProperties ragProps;
    private final ObjectMapper mapper;

    public LlmReranker(ModelClient modelClient, AgentProperties agentProps,
                       RagProperties ragProps, ObjectMapper mapper) {
        this.modelClient = modelClient;
        this.agentProps = agentProps;
        this.ragProps = ragProps;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public boolean available() {
        return agentProps.settingsReady();
    }

    @Override
    public List<Hit> rerank(String query, List<Hit> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (!available()) {
            throw new RerankException("未配置模型密钥,精排跳过(结果为粗排顺序)");
        }
        int limit = Math.min(topK, candidates.size());

        List<JsonNode> messages = List.of(userMessage(buildPrompt(query, candidates, limit)));
        final ModelClient.Completion completion;
        try {
            // finalRound=true:走 response_format=json_object,且不挂工具 —— 精排不需要工具
            completion = modelClient.complete(messages, List.of(), true);
        } catch (AgentError e) {
            // 上游故障不该让整次检索失败:降级为粗排顺序,原因写进 retrieval_logs
            throw new RerankException("精排调用失败(" + e.getMessage() + "),已降级为粗排顺序");
        }
        return parse(completion.message(), candidates, limit);
    }

    // ------------------------------------------------------------------

    private String buildPrompt(String query, List<Hit> candidates, int limit) {
        int maxChars = Math.max(80, ragProps.getRetrieval().getRerank().getMaxContentChars());
        StringBuilder sb = new StringBuilder();
        sb.append("你是检索结果精排器。请按候选知识块与用户查询的相关性,从高到低排序。\n");
        sb.append("用户查询:").append(query).append("\n\n候选知识块:\n");
        int i = 0;
        for (Hit h : candidates) {
            i++;
            sb.append("[").append(i).append("] id=").append(h.id())
                    .append(" | 来源=").append(h.sourceType()).append("\n");
            sb.append(truncate(h.rawContent(), maxChars)).append("\n\n");
        }
        sb.append("要求:\n");
        sb.append("1. 只保留真正与查询相关的候选,最多 ").append(limit).append(" 条;不相关的直接不列出。\n");
        sb.append("2. 只输出 JSON,不要任何解释文字,格式:\n");
        sb.append("{\"ranking\":[{\"id\":\"上面给出的 id\",\"score\":0.0到1.0,\"reason\":\"不超过")
                .append(REASON_MAX_CHARS).append("字的理由\"}]}\n");
        sb.append("3. id 必须原样抄写上面对应的 id,不要改写、不要编造。");
        return sb.toString();
    }

    private JsonNode userMessage(String text) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", "user");
        node.put("content", text);
        return node;
    }

    /** 解析并严格校验模型输出:未知 id 丢弃、分数夹紧到 [0,1],理由截断 */
    private List<Hit> parse(JsonNode message, List<Hit> candidates, int limit) {
        Map<String, Hit> byId = new LinkedHashMap<>();
        for (Hit h : candidates) {
            byId.put(h.id(), h);
        }
        String content = message == null ? null : message.path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new RerankException("精排返回空内容,已降级为粗排顺序");
        }
        final JsonNode ranking;
        try {
            ranking = mapper.readTree(stripFence(content)).path("ranking");
        } catch (Exception e) {
            throw new RerankException("精排返回的不是合法 JSON,已降级为粗排顺序");
        }
        if (!ranking.isArray() || ranking.isEmpty()) {
            throw new RerankException("精排未返回任何排序结果,已降级为粗排顺序");
        }

        List<Hit> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : ranking) {
            String id = item.path("id").asText(null);
            Hit base = id == null ? null : byId.get(id);
            // 模型编造 id 是真实会发生的(尤其候选 id 形如 story:US01:0)。丢弃而不是
            // 报错:一部分 id 幻觉不该让整次精排白费,剩下的排序仍然有效。
            if (base == null || !seen.add(id)) {
                continue;
            }
            double score = clamp(item.path("score").asDouble(0.0));
            String reason = item.path("reason").asText("");
            if (reason.length() > REASON_MAX_CHARS) {
                reason = reason.substring(0, REASON_MAX_CHARS);
            }
            out.add(withReason(base.withScore(score, Hit.STAGE_RERANK), reason));
            if (out.size() >= limit) {
                break;
            }
        }
        if (out.isEmpty()) {
            throw new RerankException("精排返回的 id 全部不在候选集内,已降级为粗排顺序");
        }
        return out;
    }

    /** 理由放进 metadata:它只用于展示与回看,不该参与任何逻辑判断 */
    private static Hit withReason(Hit h, String reason) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (h.metadata() != null) {
            meta.putAll(h.metadata());
        }
        if (!reason.isBlank()) {
            meta.put("rerank_reason", reason);
        }
        return new Hit(h.id(), h.sourceType(), h.sourceId(), h.rawContent(), h.score(), h.stage(), meta);
    }

    /** 模型常把 JSON 包在 ```json 围栏里;不剥掉的话解析必然失败 */
    private static String stripFence(String s) {
        String t = s.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNewline = t.indexOf('\n');
        int lastFence = t.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return t;
        }
        return t.substring(firstNewline + 1, lastFence).trim();
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
