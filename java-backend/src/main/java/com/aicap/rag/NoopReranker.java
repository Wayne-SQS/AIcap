package com.aicap.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 直通精排:只截断,不重排(配置 {@code aicap.rag.retrieval.rerank.provider=none})。
 *
 * <p>存在的理由不是"省事":S7 建评测集时要能<b>单独量出精排带来的增益</b> ——
 * 同一条查询、同一个 Golden Set,关掉精排跑一遍、打开跑一遍,两次的差异才归因得到精排头上。
 * 只能整体开关的管线是无法做这种消融的(ablation)。
 */
@Component
@ConditionalOnProperty(name = "aicap.rag.retrieval.rerank.provider", havingValue = "none")
public class NoopReranker implements Reranker {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<Hit> rerank(String query, List<Hit> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // 保持 RRF 的顺序,只截断到最终条数
        return List.copyOf(candidates.subList(0, Math.min(topK, candidates.size())));
    }
}
