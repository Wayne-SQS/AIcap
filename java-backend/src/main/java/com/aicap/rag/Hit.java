package com.aicap.rag;

import java.util.Map;

/**
 * 一条召回结果。
 *
 * <p>{@code score} 的<b>含义随所处阶段变化</b>,这不算优雅但是刻意的:三路召回各自的
 * 分数量纲完全不同(余弦 ∈ [-1,1]、BM25 无上界、图路是按跳数衰减的定值),若给它们
 * 各起一个字段名,融合阶段就得写三个分支去读。用一个字段 + {@code stage} 标明它现在
 * 代表什么,读日志时反而更清楚(设计文档 A5)。
 *
 * @param id         chunk id
 * @param sourceType story/pool_item/task/meeting/doc/profile
 * @param sourceId   源记录主键
 * @param rawContent 原文(不含增强前缀);召回阶段可能为 null,由
 *                   {@link RetrievalService} 在出结果前统一补齐 —— 让每一路各自去查原文
 *                   会变成 N+1 次查询,而三路合起来有 60 条候选、最终只用 5 条
 * @param score      当前阶段得分
 * @param stage      当前得分的含义:vector/keyword/graph/rrf/rerank
 * @param metadata   结构化字段(title/priority/seg_id…),可能为 null
 */
public record Hit(String id,
                  String sourceType,
                  String sourceId,
                  String rawContent,
                  double score,
                  String stage,
                  Map<String, Object> metadata) {

    public static final String STAGE_VECTOR = "vector";
    public static final String STAGE_KEYWORD = "keyword";
    public static final String STAGE_GRAPH = "graph";
    public static final String STAGE_RRF = "rrf";
    public static final String STAGE_RERANK = "rerank";

    /** 换一个得分/阶段,其余字段照搬 —— 融合与精排都是"重排既有结果",不该重建对象 */
    public Hit withScore(double newScore, String newStage) {
        return new Hit(id, sourceType, sourceId, rawContent, newScore, newStage, metadata);
    }

    /**
     * 一次性补上原文与元数据,供 {@link RetrievalService} 出结果前的水合使用。
     *
     * <p>已有值时不覆盖(先到先得):补的时候若让 null 盖掉非 null,就会把先前查到的
     * 内容抹掉 —— 原文与元数据各自判空,互不牵连。
     */
    public Hit hydrate(String content, Map<String, Object> meta) {
        String c = this.rawContent != null ? this.rawContent : content;
        Map<String, Object> m = this.metadata != null ? this.metadata : meta;
        return new Hit(id, sourceType, sourceId, c, score, stage, m);
    }
}
