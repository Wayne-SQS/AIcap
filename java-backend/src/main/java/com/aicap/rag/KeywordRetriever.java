package com.aicap.rag;

import com.aicap.mapper.KnowledgeChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键词路召回(设计文档 A5):MySQL ngram 全文索引。
 *
 * <p><b>这一路不是可有可无的补充</b>。项目文本里 {@code US01}、{@code T12}、{@code SetLamp}
 * 这类专有名词密度极高,而向量模型对它们的区分能力接近噪声 —— 一个不含这些词的
 * 语义相近文档,分数往往高于真正含这些词的文档。没有这一路,「US01 是什么」这种
 * 最直白的问题反而问不到。
 *
 * <p>失败时返回空列表:全文索引缺失属于部署缺陷(由 {@code SchemaUpgrader} 补),
 * 但不该让整次检索陪葬 —— 记 WARN 保留痕迹,由另外两路继续出结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordRetriever implements Retriever {

    private final KnowledgeChunkMapper chunkMapper;

    @Override
    public String name() {
        return Hit.STAGE_KEYWORD;
    }

    @Override
    public List<Hit> retrieve(String query, int topK, RetrievalContext ctx) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        final List<ChunkHitRow> rows;
        try {
            rows = chunkMapper.searchByKeyword(query, ctx.roleRank(), ctx.sourceTypesCsv(), topK);
        } catch (RuntimeException e) {
            log.warn("关键词路召回失败(检查 knowledge_chunks.ft_content 全文索引是否存在): {}", e.getMessage());
            return List.of();
        }
        List<Hit> hits = new ArrayList<>(rows.size());
        for (ChunkHitRow r : rows) {
            hits.add(new Hit(r.getChunkId(), r.getSourceType(), r.getSourceId(),
                    r.getRawContent(), r.getScore() == null ? 0.0 : r.getScore(),
                    Hit.STAGE_KEYWORD, null));
        }
        return hits;
    }
}
