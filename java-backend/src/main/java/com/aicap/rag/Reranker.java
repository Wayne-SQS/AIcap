package com.aicap.rag;

import java.util.List;

/**
 * 精排(设计文档 A6):把 RRF 融合后的粗排候选交给更贵的模型重新排序。
 *
 * <p><b>为什么必须有第二阶段</b>(RAG 的标准考点,而这里是真的做了两阶段):
 * 召回用的 Bi-Encoder 对「查询」和「文档」<b>分别</b>编码,两者在编码时互相看不见,
 * 因此快、可预计算、但只能逼近相关性。精排用的 Cross-Encoder 把两者拼在一起过一遍
 * 模型,能建模词级交互,准得多 —— 但代价是每条候选都要跑一次前向,不可能对全库做。
 * 所以必然是「便宜的先粗筛到几十条,贵的再精排到几条」。
 *
 * <p>实现约定:{@code rerank} 失败时抛 {@link RerankException},
 * <b>不返回空列表</b> —— 空列表与"精排后确实没有相关结果"无法区分,
 * 调用方会把一次上游故障当成一次正常但空的结果返回给用户。
 */
public interface Reranker {

    /** 实现名,进 retrieval_logs 的 {@code reranker} 列 */
    String name();

    /** 当前配置下是否可用;false 时调用方直接跳过,不产生任何外部调用 */
    boolean available();

    /**
     * 重排。
     *
     * @param candidates 粗排候选,<b>须已带原文</b>(精排要读内容,不能只给 id)
     * @return 重排后的结果,长度 ≤ topK;不会包含 candidates 之外的 id
     * @throws RerankException 精排不可用/上游失败/输出无法解析
     */
    List<Hit> rerank(String query, List<Hit> candidates, int topK);
}
