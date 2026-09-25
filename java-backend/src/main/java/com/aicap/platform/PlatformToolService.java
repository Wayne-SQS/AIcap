package com.aicap.platform;

import com.aicap.agent.AgentError;
import com.aicap.agent.AgentTools;
import com.aicap.common.ApiException;
import com.aicap.entity.User;
import com.aicap.profile.ProfileToolRegistry;
import com.aicap.rag.Hit;
import com.aicap.rag.RagProperties;
import com.aicap.rag.RetrievalContext;
import com.aicap.rag.RetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 平台工具调用的统一入口(S3)。
 *
 * <p>设计要点(见 {@code docs/主管智能体_落地方案_v1.0.md} §2):
 *
 * <ol>
 *   <li><b>一个端点调所有工具</b>,而不是一个工具一个 RESTful 资源 ——
 *       两个注册表的调用签名本来都是 {@code (name, args) → result},统一端点零转换;
 *       新增工具不需要改跨进程契约。</li>
 *   <li><b>业务失败用 200 + ok=false 表达</b>,不用 4xx/5xx。工具失败(参数不合法、
 *       上游超时)是编排层要<b>喂回给模型</b>的一等观察结果,若按 HTTP 错误返回,
 *       上游客户端会走异常分支,把「把错误文本交给模型自我纠正」这条路径丢掉。
 *       协议级错误(未登录/越权/工具不存在)仍然走标准状态码。</li>
 *   <li><b>权限只来自 JWT</b>:入参里的 userId / owner_id 一律是<b>查询目标</b>,
 *       不是调用者身份 —— 工具接口不提供 asUserId / onBehalfOf(规划 §3.2)。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformToolService {

    private static final int MAX_QUERY_CHARS = 500;
    private static final int MAX_CONTENT_CHARS = 600;

    /** 统一响应信封。字段恒输出(含 null),与项目其余接口一致 */
    public record ToolOutcome(String tool,
                              boolean ok,
                              JsonNode result,
                              JsonNode retrieval,
                              List<String> limitations,
                              long latencyMs,
                              String errorCode,
                              String errorMessage) {
    }

    private final PlatformToolCatalog catalog;
    private final AgentTools meetingTools;
    private final ProfileToolRegistry profileTools;
    private final RetrievalService retrievalService;
    private final RagProperties props;
    private final ObjectMapper mapper;

    /**
     * 调用一个工具。
     *
     * @param caller 已经过 {@code Roles.writer()} 校验的调用者;工具内部还会再查一次库,
     *               因为在 LLM 长循环里用户角色可能在调用途中被改掉(既有约定,不改)
     * @throws ApiException 工具名不存在(404)
     */
    public ToolOutcome invoke(String toolName, JsonNode args, User caller) {
        PlatformToolCatalog.ToolSpec spec = catalog.find(toolName);
        if (spec == null) {
            throw ApiException.notFound("未知工具: " + toolName);
        }
        long started = System.currentTimeMillis();
        JsonNode safeArgs = args == null || args.isNull() ? mapper.createObjectNode() : args;
        try {
            return switch (spec.agent()) {
                case PlatformToolCatalog.AGENT_MEETING ->
                        success(toolName, meetingTools.execute(toolName, safeArgs, caller.getId()),
                                null, started);
                case PlatformToolCatalog.AGENT_PROFILE -> invokeProfile(toolName, safeArgs, started);
                case PlatformToolCatalog.AGENT_PLATFORM -> invokePlatform(toolName, safeArgs, caller, started);
                default -> throw ApiException.notFound("未知工具: " + toolName);
            };
        } catch (AgentError e) {
            // AgentTools 的参数校验与权限复核都抛 AgentError;对编排层是"可纠正的观察",
            // 不是服务的错 —— 所以走 ok=false 而不是 5xx
            return failure(toolName, e.getCode(), e.getMessage(), started);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("platform tool {} failed: {}", toolName, e.toString());
            return failure(toolName, "internal_error", "工具执行失败,请稍后重试", started);
        }
    }

    private ToolOutcome invokeProfile(String toolName, JsonNode args, long started) {
        ProfileToolRegistry.ToolCall call = profileTools.invoke(toolName, write(args));
        if (!call.ok()) {
            return failure(toolName, call.error() == null ? "tool_failed" : call.error(),
                    readable(call.resultJson()), started);
        }
        return success(toolName, read(call.resultJson()), null, started);
    }

    private ToolOutcome invokePlatform(String toolName, JsonNode args, User caller, long started) {
        if (PlatformToolCatalog.SEMANTIC_SEARCH.equals(toolName)) {
            return semanticSearch(args, caller, started);
        }
        throw ApiException.notFound("未知工具: " + toolName);
    }

    /**
     * 语义检索。复用 {@code KnowledgeController#retrieve} 背后的同一个
     * {@link RetrievalService},并透传调用者的 {@link RetrievalContext}。
     *
     * <p>为什么要复用而不是新写一个:收紧 ACL 时,接口层、检索层、主管三者必须<b>一起</b>生效。
     * 只有一个检索入口时,"漏掉一个入口"这件事在结构上就不可能发生(规划 §3.2)。
     */
    private ToolOutcome semanticSearch(JsonNode args, User caller, long started) {
        String query = args.path("q").asText("").trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) {
            return failure(PlatformToolCatalog.SEMANTIC_SEARCH, "invalid_tool_arguments",
                    "参数 q 长度需在 1-" + MAX_QUERY_CHARS + " 字符之间", started);
        }
        RagProperties.Retrieval cfg = props.getRetrieval();
        int top = clamp(args.path("top").asInt(cfg.getFinalTopK()), cfg.getFinalTopK());

        RetrievalContext ctx = new RetrievalContext(caller.getId(), caller.getRole());
        RetrievalService.RetrievalResult r = retrievalService.retrieve(query, ctx,
                cfg.getRoutesTopK(), cfg.getFuseTopK(), top);

        ObjectNode retrieval = mapper.createObjectNode();
        retrieval.put("query", r.query());
        retrieval.put("configured", r.configured());
        retrieval.put("vector_store", r.vectorStore());
        retrieval.put("reranker", r.reranker());
        putOrNull(retrieval, "degraded", r.degraded());
        ObjectNode routes = mapper.createObjectNode();
        for (RetrievalService.RouteResult route : r.routes()) {
            routes.put(route.name(), route.hits().size());
        }
        retrieval.set("routes", routes);
        retrieval.set("fused", refs(r.fused()));
        retrieval.set("final", refs(r.finalHits()));
        retrieval.put("latency_ms", r.latencyMs());
        putOrNull(retrieval, "message", r.message());

        ObjectNode result = mapper.createObjectNode();
        result.put("configured", r.configured());
        ArrayNode items = mapper.createArrayNode();
        for (Hit hit : r.finalHits()) {
            ObjectNode item = mapper.createObjectNode();
            item.put("chunk_id", hit.id());
            item.put("source_type", hit.sourceType());
            item.put("source_id", hit.sourceId());
            item.put("score", round(hit.score()));
            item.put("content", truncate(hit.rawContent()));
            items.add(item);
        }
        result.set("items", items);
        putOrNull(result, "message", r.message());

        return new ToolOutcome(PlatformToolCatalog.SEMANTIC_SEARCH, true, result, retrieval,
                PlatformToolCatalog.LIMITATIONS, System.currentTimeMillis() - started, null, null);
    }

    // ------------------------------------------------------------------
    // 组装辅助
    // ------------------------------------------------------------------

    private ToolOutcome success(String tool, JsonNode result, JsonNode retrieval, long started) {
        return new ToolOutcome(tool, true, result, retrieval,
                PlatformToolCatalog.LIMITATIONS, System.currentTimeMillis() - started, null, null);
    }

    private ToolOutcome failure(String tool, String code, String message, long started) {
        ObjectNode result = mapper.createObjectNode();
        result.put("error", message);
        return new ToolOutcome(tool, false, result, null,
                PlatformToolCatalog.LIMITATIONS, System.currentTimeMillis() - started, code, message);
    }

    /** 只留 id 与得分,不带原文 —— 中间结果进的是调试面板,不该把大段正文塞进事件流 */
    private ArrayNode refs(List<Hit> hits) {
        ArrayNode arr = mapper.createArrayNode();
        for (Hit hit : hits) {
            ObjectNode node = mapper.createObjectNode();
            node.put("chunk_id", hit.id());
            node.put("score", round(hit.score()));
            node.put("stage", hit.stage());
            arr.add(node);
        }
        return arr;
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            // 注册表返回非 JSON 时不抛:对调用方来说它仍是一条可读的观察
            return mapper.getNodeFactory().textNode(json);
        }
    }

    private String readable(String json) {
        JsonNode node = read(json);
        String text = node.path("error").asText("");
        return text.isEmpty() ? "工具执行失败" : text;
    }

    private static void putOrNull(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static int clamp(int value, int fallback) {
        int v = value <= 0 ? fallback : value;
        return Math.min(Math.max(v, 1), 50);
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_CONTENT_CHARS ? content : content.substring(0, MAX_CONTENT_CHARS);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
