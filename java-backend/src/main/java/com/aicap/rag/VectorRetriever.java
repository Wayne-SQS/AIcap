package com.aicap.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量路召回(设计文档 A5)。
 *
 * <p>查询向量在这里现算,而不是由 {@link RetrievalService} 算好传进来:三路里只有
 * 这一路需要 embedding,把它留在本路内部,一次检索就正好一次 embedding 调用 ——
 * 提到外面反而要多一个"查询向量"参数在所有路之间传递,而另外两路根本不用它。
 *
 * <p>这是三路里<b>唯一会产生外网调用与费用</b>的一路,也是唯一会因上游故障而整路失效的一路。
 * 上游失败时返回空列表而不是抛异常:关键词路与图路仍然能出结果(设计文档 A5 的
 * 融合设计本来就允许单路缺席),让整次检索因为 embedding 超时而全灭是更差的选择。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorRetriever implements Retriever {

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    @Override
    public String name() {
        return Hit.STAGE_VECTOR;
    }

    @Override
    public List<Hit> retrieve(String query, int topK, RetrievalContext ctx) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        final float[] vector;
        try {
            vector = embeddingModel.embedOne(query);
        } catch (EmbeddingException e) {
            log.warn("向量路召回失败,本次检索降级为关键词路 + 图路: {}", e.getMessage());
            return List.of();
        }
        List<VectorStore.ScoredId> scored = vectorStore.search(vector, topK, ctx);
        List<Hit> hits = new ArrayList<>(scored.size());
        for (VectorStore.ScoredId s : scored) {
            hits.add(new Hit(s.id(),
                    str(s.payload().get("source_type")),
                    str(s.payload().get("source_id")),
                    null,
                    s.score(),
                    Hit.STAGE_VECTOR,
                    null));
        }
        return hits;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
