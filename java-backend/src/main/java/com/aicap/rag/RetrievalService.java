package com.aicap.rag;

import com.aicap.entity.KnowledgeChunk;
import com.aicap.entity.RetrievalLog;
import com.aicap.mapper.KnowledgeChunkMapper;
import com.aicap.mapper.RetrievalLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索管线(设计文档 A5/A6/A7 的汇合点):
 * <b>三路召回 → RRF 融合 → 精排 → ACL 已在上游生效 → 落检索日志</b>。
 *
 * <p>关于降级:整条链上只有 embedding 是硬依赖(没有它就一路都跑不了,直接返回
 * {@code configured=false})。其余环节一律软失败 —— 关键词索引缺失、图路 SQL 异常、
 * 精排上游超时,都不该让用户拿到一个 500,而该拿到"少了一路但不完整地有用"的结果,
 * 同时把降级事实写进 {@code retrieval_logs.degraded}。**静默降级比失败更危险**,
 * 所以降级必须留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    /** 调试台展示的固定路序:与管线执行顺序一致,不依赖 Bean 装配顺序 */
    private static final List<String> ROUTE_ORDER =
            List.of(Hit.STAGE_VECTOR, Hit.STAGE_KEYWORD, Hit.STAGE_GRAPH);

    private final List<Retriever> retrievers;
    private final Reranker reranker;
    private final KnowledgeChunkMapper chunkMapper;
    private final RetrievalLogMapper logMapper;
    private final RagProperties props;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    /** 一次混合检索的完整结果;调试台直接把它序列化给前端 */
    public record RetrievalResult(String query,
                                  boolean configured,
                                  String vectorStore,
                                  String embeddingModel,
                                  String reranker,
                                  /** 非 null 表示本次是降级结果,内容是原因 */
                                  String degraded,
                                  List<RouteResult> routes,
                                  List<Hit> fused,
                                  List<Hit> finalHits,
                                  long latencyMs,
                                  String message) {
    }

    /** 单路召回明细;调试台并排展示的就是这个。条数由 {@link #hits()} 推导,不另存一份 */
    public record RouteResult(String name, List<Hit> hits) {
    }

    // ------------------------------------------------------------------

    public RetrievalResult retrieve(String query, RetrievalContext ctx) {
        return retrieve(query, ctx,
                props.getRetrieval().getRoutesTopK(),
                props.getRetrieval().getFuseTopK(),
                props.getRetrieval().getFinalTopK());
    }

    public RetrievalResult retrieve(String query, RetrievalContext ctx,
                                    int routesTopK, int fuseTopK, int finalTopK) {
        long started = System.currentTimeMillis();
        if (!props.isEnabled()) {
            return notConfigured(query, "RAG 未启用(aicap.rag.enabled=false)");
        }
        if (!props.getEmbedding().settingsReady()) {
            return notConfigured(query, "未配置 embedding 密钥/模型,无法执行检索");
        }

        List<RouteResult> routes = new ArrayList<>(ROUTE_ORDER.size());
        List<List<Hit>> rankedLists = new ArrayList<>(ROUTE_ORDER.size());
        String degraded = null;
        for (Retriever r : orderedRetrievers()) {
            List<Hit> hits = r.retrieve(query, routesTopK, ctx);
            routes.add(new RouteResult(r.name(), List.copyOf(hits)));
            rankedLists.add(hits);
            if (hits.isEmpty() && degradationWorthy(r.name())) {
                // 向量路整路为空只可能是上游失败(它在实现内部已经吞掉了异常),
                // 而关键词/图路为空是完全正常的(查询里没那些词)。只有前者算降级。
                degraded = "向量路召回为空(embedding 上游可能不可用),结果为另外两路";
            }
        }

        List<Hit> fused = new RrfFuser(props.getRetrieval().getRrfK()).fuse(rankedLists, fuseTopK);
        List<Hit> hydrated = hydrate(fused);

        List<Hit> finalHits;
        String effectiveReranker = reranker.name();
        List<Hit> candidates = trim(hydrated, props.getRetrieval().getRerank().getMaxCandidates());
        try {
            finalHits = reranker.rerank(query, candidates, finalTopK);
        } catch (RerankException e) {
            // 降级为粗排顺序:结果仍然可用,但必须让调用方知道这不是精排结果
            effectiveReranker = "none(降级)";
            // 追加而不是覆盖:向量路可能已经记过一次降级,而 degraded 只有一列 ——
            // 覆盖会让"这次检索降级了两次"在日志里只剩一次
            degraded = degraded == null ? e.getMessage() : degraded + ";" + e.getMessage();
            finalHits = trim(candidates, finalTopK);
        }

        long latency = System.currentTimeMillis() - started;
        if (props.getRetrieval().isLogEnabled()) {
            // 记实际生效的精排实现(降级时是 none)而不是配置值:retrieval_logs.reranker
            // 这列的含义就是"实际生效",记配置值的话得配合 degraded 列才能倒推出真实情况
            writeLog(query, ctx, routes, fused, finalHits, effectiveReranker, degraded, latency);
        }
        log.info("混合检索「{}」:三路 {} 条 → 融合 {} 条 → 精排 {} 条,耗时 {}ms{}",
                query, routes.stream().mapToInt(r -> r.hits().size()).sum(), fused.size(),
                finalHits.size(), latency, degraded == null ? "" : "(降级:" + degraded + ")");

        return new RetrievalResult(query, true, vectorStore.name(), embeddingModel.name(),
                effectiveReranker, degraded, List.copyOf(routes),
                List.copyOf(fused), List.copyOf(finalHits), latency, null);
    }

    // ------------------------------------------------------------------

    private RetrievalResult notConfigured(String query, String message) {
        return new RetrievalResult(query, false, vectorStore.name(), embeddingModel.name(),
                reranker.name(), null, List.of(), List.of(), List.of(),
                System.currentTimeMillis(), message);
    }

    /**
     * 固定路序;未实现的路不占位,新增一路(如 S3 的 Query Rewrite)不会打乱调试台列序。
     *
     * <p>用稳定排序表达:不在 {@link #ROUTE_ORDER} 里的实现(将来新增的)排在最后,
     * 且保持 Bean 装配顺序,而不是被静默丢掉。
     */
    private List<Retriever> orderedRetrievers() {
        return retrievers.stream()
                .sorted(Comparator.comparingInt(r -> {
                    int i = ROUTE_ORDER.indexOf(r.name());
                    return i < 0 ? ROUTE_ORDER.size() : i;
                }))
                .toList();
    }

    private static boolean degradationWorthy(String routeName) {
        return Hit.STAGE_VECTOR.equals(routeName);
    }

    private static List<Hit> trim(List<Hit> hits, int max) {
        if (hits.size() <= max) {
            return hits;
        }
        return List.copyOf(hits.subList(0, max));
    }

    /**
     * 给融合后的候选补上原文与元数据。
     *
     * <p>只在这里补一次,而不是让每一路各自去查:三路加起来 60 条候选,
     * 逐条查就是 60 次往返;一次 {@code selectBatchIds} 取回来即可。
     * 精排要读原文,所以必须在精排<b>之前</b>完成 —— 这也是它不能推迟到只对
     * finalTopK 做的原因。
     */
    private List<Hit> hydrate(List<Hit> hits) {
        if (hits.isEmpty()) {
            return hits;
        }
        List<String> ids = new ArrayList<>(hits.size());
        for (Hit h : hits) {
            ids.add(h.id());
        }
        Map<String, KnowledgeChunk> byId = new HashMap<>();
        for (KnowledgeChunk c : chunkMapper.selectBatchIds(ids)) {
            byId.put(c.getId(), c);
        }
        List<Hit> out = new ArrayList<>(hits.size());
        for (Hit h : hits) {
            KnowledgeChunk c = byId.get(h.id());
            if (c == null) {
                // 融合结果里的块在库中已不存在(索引删除与检索并发)。直接丢掉,
                // 而不是留一条 rawContent 为 null 的结果进 prompt。
                log.debug("检索命中已不存在的 chunk,丢弃: {}", h.id());
                continue;
            }
            out.add(h.hydrate(c.getRawContent() == null ? c.getContent() : c.getRawContent(),
                    parseMetadata(c.getMetadataJson())));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("metadata 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 落检索日志。
     *
     * <p>写日志失败只记 WARN 不上抛:日志是观测设施,不该成为检索的失败点 ——
     * 磁盘满或表被锁时,用户仍然应该拿到检索结果。
     */
    private void writeLog(String query, RetrievalContext ctx, List<RouteResult> routes,
                          List<Hit> fused, List<Hit> finalHits,
                          String rerankerName, String degraded, long latency) {
        try {
            RetrievalLog row = new RetrievalLog();
            row.setQuery(query.length() > 500 ? query.substring(0, 500) : query);
            row.setUserId(ctx.userId());
            row.setRole(ctx.role());
            row.setRoutesJson(json(routeSummary(routes)));
            row.setFusedJson(json(hitSummary(fused, false)));
            row.setFinalJson(json(hitSummary(finalHits, true)));
            row.setReranker(rerankerName);
            row.setDegraded(degraded != null && degraded.length() > 200
                    ? degraded.substring(0, 200) : degraded);
            row.setLatencyMs((int) Math.min(Integer.MAX_VALUE, latency));
            row.setCreatedAt(LocalDateTime.now());
            logMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("检索日志写入失败(不影响本次检索结果): {}", e.getMessage());
        }
    }

    private static List<Map<String, Object>> routeSummary(List<RouteResult> routes) {
        List<Map<String, Object>> out = new ArrayList<>(routes.size());
        for (RouteResult r : routes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("count", r.hits().size());
            m.put("items", hitSummary(r.hits(), false));
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> hitSummary(List<Hit> hits, boolean withReason) {
        List<Map<String, Object>> out = new ArrayList<>(hits.size());
        for (Hit h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunk_id", h.id());
            m.put("source_type", h.sourceType());
            m.put("score", round(h.score()));
            if (withReason && h.metadata() != null && h.metadata().get("rerank_reason") != null) {
                m.put("reason", h.metadata().get("rerank_reason"));
            }
            out.add(m);
        }
        return out;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.debug("检索日志序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
