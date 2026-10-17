package com.aicap.rag;

import com.aicap.entity.KnowledgeVector;
import com.aicap.mapper.KnowledgeVectorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 向量存 MySQL、余弦相似度在应用层算的暴力检索实现。
 *
 * <p><b>为什么这个实现值得存在</b>：AIcap 当前只有几百到几千 chunk,暴力扫描的延迟
 * 远低于一次 LLM 调用的时间(几十毫秒 vs 几百毫秒),引入 ANN 索引在这个规模上
 * 是纯粹的复杂度开销。能讲清「索引结构是数据规模的函数」比多接一个向量库更值钱,
 * 而这个实现就是那句话的证据 —— 它同时是 S2 里 1k/1万/10万 P99 对比的对照组。
 *
 * <p>代价是 O(N) 扫描,由 {@code aicap.rag.mysql-max-scan} 设内存上界。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aicap.rag.vector-store", havingValue = "mysql", matchIfMissing = true)
public class MysqlVectorStore implements VectorStore {

    /** 单条 DELETE 最多的 IN 元素数 */
    private static final int DELETE_BATCH = 500;

    private final KnowledgeVectorMapper mapper;
    private final EmbeddingModel embeddingModel;
    private final RagProperties props;

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public void ensureCollection(int dimension) {
        // 表由 schema.sql 建;此处只校验"既有向量的维度"与当前模型配置是否一致。
        // 只查维度列,不把向量读进内存 —— 校验不该付出 O(N) 的内存代价。
        for (Integer dim : mapper.selectDimensions(embeddingModel.name())) {
            if (dim != null && dim != dimension) {
                throw new IllegalStateException("向量库中已有向量的维度(" + dim
                        + ")与当前配置(" + dimension + ")不一致,换模型后需要重建索引");
            }
        }
    }

    @Override
    public void upsertBatch(List<Entry> entries) {
        for (Entry e : entries) {
            KnowledgeVector row = new KnowledgeVector();
            row.setChunkId(e.id());
            row.setModel(embeddingModel.name());
            row.setDimension(e.vector().length);
            row.setVector(Vectors.toBytes(e.vector()));
            row.setCreatedAt(LocalDateTime.now());
            mapper.upsert(row);
        }
    }

    @Override
    public void deleteBySource(String sourceType, String sourceId) {
        int n = mapper.deleteBySource(sourceType, sourceId);
        if (n > 0) {
            log.debug("已删除 {} 条向量 source={}:{}", n, sourceType, sourceId);
        }
    }

    @Override
    public void deleteByIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        // 分批:IN 列表过长会撑爆 max_allowed_packet / 解析栈
        for (int i = 0; i < chunkIds.size(); i += DELETE_BATCH) {
            mapper.deleteByIds(chunkIds.subList(i, Math.min(i + DELETE_BATCH, chunkIds.size())));
        }
    }

    @Override
    public List<ScoredId> search(float[] queryVector, int topK, RetrievalContext ctx) {
        int limit = props.getMysqlMaxScan();
        List<VectorRow> rows = mapper.selectCandidates(
                embeddingModel.name(), ctx.roleRank(), ctx.sourceTypesCsv(), limit);
        if (rows.size() >= limit) {
            log.warn("暴力检索候选已达上限 {},应切换到 qdrant 向量库(见 aicap.rag.vector-store)", limit);
        }
        List<ScoredId> scored = new ArrayList<>(rows.size());
        for (VectorRow row : rows) {
            float[] v = Vectors.toFloats(row.getVector(), row.getDimension());
            scored.add(new ScoredId(row.getChunkId(), Vectors.cosine(queryVector, v), payloadOf(row)));
        }
        scored.sort(Comparator.comparingDouble(ScoredId::score).reversed());
        return scored.size() > topK ? List.copyOf(scored.subList(0, topK)) : List.copyOf(scored);
    }

    @Override
    public long count() {
        return mapper.countByModel(embeddingModel.name());
    }

    private static Map<String, Object> payloadOf(VectorRow row) {
        return Map.of(
                "chunk_id", row.getChunkId(),
                "source_type", row.getSourceType(),
                "source_id", row.getSourceId());
    }
}
