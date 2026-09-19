package com.aicap.rag;

import lombok.Data;

/**
 * {@code knowledge_vectors} JOIN {@code knowledge_chunks} 的查询行。
 * 用可变 POJO 而非 record:MyBatis 对 record 的构造器映射需要额外配置,不值得为省几行引入不确定性。
 */
@Data
public class VectorRow {
    private String chunkId;
    private Integer dimension;
    private byte[] vector;
    private String sourceType;
    private String sourceId;
    private String metadataJson;
}
