package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.entity.RetrievalLog;
import com.aicap.entity.User;
import com.aicap.mapper.RetrievalLogMapper;
import com.aicap.rag.EmbeddingException;
import com.aicap.rag.EmbeddingModel;
import com.aicap.rag.KnowledgeIndexer;
import com.aicap.rag.RagProperties;
import com.aicap.rag.RetrievalContext;
import com.aicap.rag.RetrievalService;
import com.aicap.rag.VectorStore;
import com.aicap.security.Roles;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口(RAG 检索层)。
 *
 * <p>权限分层:
 * <ul>
 *   <li>{@code /stats}、{@code /reindex} 限 admin/owner —— 前者暴露知识库构成,
 *       后者会真实调用 embedding(有费用、耗时),都属于管理面;</li>
 *   <li>{@code /search} 任何登录用户可用 —— 它是检索调试台,结果本身已按
 *       {@link RetrievalContext} 做过 ACL 过滤,不会因为"能调用"就多看见东西。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private static final int MAX_QUERY_CHARS = 500;
    private static final int MAX_TOP = 50;
    private static final int MAX_LOGS = 200;

    private final KnowledgeIndexer indexer;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final RagProperties props;
    private final RetrievalService retrievalService;
    private final RetrievalLogMapper retrievalLogMapper;

    /**
     * 知识库构成(chunk 数、待索引数、各源分布)。
     *
     * <p>限 admin/owner:它暴露的是「知识库里有什么」的统计,
     * 若全部登录用户可读,等于向 viewer 泄漏了受限源的存在与规模。
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Roles.reviewer();
        return indexer.stats();
    }

    /**
     * 重建索引。{@code force=true} 时无视 content_hash 全量重算。
     *
     * <p>换 embedding 模型<b>不需要</b> force:块上记了 {@code embedding_model},
     * 模型与当前配置不符的块会自动判为过期重算,报告里的 {@code stale} 即此类块数。
     * force 留给"怀疑向量库与源数据不一致"这类需要无条件重来一遍的场景。
     *
     * <p>返回报告而不是只回 "ok":调用方需要知道"扫了多少、跳过多少、失败多少",
     * 否则"重建成功"这四个字在索引了 0 块时也一样会出现。
     */
    @PostMapping("/reindex")
    public KnowledgeIndexer.IndexReport reindex(
            @RequestParam(value = "force", defaultValue = "false") boolean force) {
        Roles.reviewer();
        return indexer.rebuild(force);
    }

    /**
     * 向量检索调试台的后端(S2 会在此之上加三路召回与 RRF 对比)。
     *
     * <p>现在就做的理由:没有它,ACL 过滤与向量检索的正确性在 S1 完全无法验证,
     * 只能等到 S2 一起暴露问题,定位成本高得多。
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String q,
                                      @RequestParam(value = "top", defaultValue = "5") int top) {
        User user = Roles.any();
        String query = validQuery(q);
        int limit = Math.min(Math.max(top, 1), MAX_TOP);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("role", user.getRole());
        out.put("vector_store", vectorStore.name());

        if (!props.isEnabled() || !props.getEmbedding().settingsReady()) {
            out.put("configured", false);
            out.put("items", List.of());
            out.put("message", "未配置 embedding 密钥/模型,无法执行检索");
            return out;
        }

        long started = System.currentTimeMillis();
        final float[] vector;
        try {
            vector = embeddingModel.embedOne(query);
        } catch (EmbeddingException e) {
            // 上游不可用是 503 而不是 500:调用方可以重试,且不该被当成自身 bug
            throw ApiException.server("检索失败:" + e.getMessage());
        }
        RetrievalContext ctx = new RetrievalContext(user.getId(), user.getRole());
        List<Map<String, Object>> items = new ArrayList<>();
        for (VectorStore.ScoredId hit : vectorStore.search(vector, limit, ctx)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunk_id", hit.id());
            item.put("score", round(hit.score()));
            item.put("source_type", hit.payload().get("source_type"));
            item.put("source_id", hit.payload().get("source_id"));
            items.add(item);
        }
        out.put("configured", true);
        out.put("items", items);
        out.put("latency_ms", System.currentTimeMillis() - started);
        return out;
    }

    /**
     * 混合检索调试台(S2):返回三路各自的召回、RRF 融合结果与精排结果。
     *
     * <p>为什么把三路中间结果<b>都</b>返回,而不是只回最终 5 条:调试检索质量时
     * 真正要回答的是「这条为什么没进来」——是没召回、融合时被挤掉、还是精排判为不相关。
     * 只回最终结果的话这个问题无法回答,只能改代码加日志再跑一遍。
     *
     * <p>任何登录用户可用,与 {@code /search} 同理:结果已按调用者的
     * {@link RetrievalContext} 做过 ACL 过滤,能调用不等于能多看见东西。
     */
    @GetMapping("/retrieve")
    public RetrievalService.RetrievalResult retrieve(
            @RequestParam("q") String q,
            @RequestParam(value = "routes_topk", required = false) Integer routesTopK,
            @RequestParam(value = "fuse_topk", required = false) Integer fuseTopK,
            @RequestParam(value = "final_topk", required = false) Integer finalTopK) {
        User user = Roles.any();
        RetrievalContext ctx = new RetrievalContext(user.getId(), user.getRole());
        RagProperties.Retrieval cfg = props.getRetrieval();
        return retrievalService.retrieve(validQuery(q), ctx,
                clamp(routesTopK, cfg.getRoutesTopK()),
                clamp(fuseTopK, cfg.getFuseTopK()),
                clamp(finalTopK, cfg.getFinalTopK()));
    }

    /**
     * 最近的检索日志(S2):调试台回看「刚才那次查询到底发生了什么」。
     *
     * <p>限 admin/owner:日志里含<b>其他人的查询词</b>,那不是检索调试需要的信息,
     * 而是隐私。{@code /retrieve} 只回调用者自己的那次,所以可以对所有登录用户开放;
     * 这个接口能翻到别人的,必须收权限。
     */
    @GetMapping("/logs")
    public List<RetrievalLog> logs(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        Roles.reviewer();
        int n = Math.min(Math.max(limit, 1), MAX_LOGS);
        return retrievalLogMapper.selectList(new QueryWrapper<RetrievalLog>()
                .orderByDesc("id")
                .last("LIMIT " + n));
    }

    /** 查询词校验:与 /search 同一套规则,避免两个入口对"什么样的查询合法"给出不同答案 */
    private static String validQuery(String q) {
        String query = q == null ? "" : q.trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) {
            throw ApiException.badRequest("查询词长度需在 1-" + MAX_QUERY_CHARS + " 字符之间");
        }
        return query;
    }

    private static int clamp(Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.min(Math.max(value, 1), MAX_TOP);
    }

    /** 分数留 4 位小数:整段浮点对调试没帮助,只会刷屏 */
    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
