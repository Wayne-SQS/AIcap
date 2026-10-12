package com.aicap.rag;

import lombok.Data;

/**
 * 关键词路与图路的查询行(两路都直接读 {@code knowledge_chunks},列集合一致)。
 *
 * <p>用可变 POJO 而非 record:与 {@link VectorRow} 同理,MyBatis 对 record 的构造器
 * 映射需要额外配置,不值得为省几行引入不确定性。
 *
 * <p>有意不查 {@code metadata_json}:这两路召回构造 {@link Hit} 时 metadata 一律传 null,
 * 元数据统一由 {@code RetrievalService} 在出结果前从库里补齐(只查一次,而不是每路各查)。
 * 多查这一列也只是当场丢掉。
 */
@Data
public class ChunkHitRow {
    private String chunkId;
    private String sourceType;
    private String sourceId;
    private String rawContent;
    /** 关键词路 = MATCH...AGAINST 相关度;图路不用(按跳数给定值) */
    private Double score;
}
