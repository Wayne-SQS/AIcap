package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识块向量(MysqlVectorStore 的存储;Qdrant 实现不使用本表)。
 *
 * <p>{@code model} 与 {@code dimension} 必须随向量落库:换 embedding 模型后
 * 新旧向量不能混算相似度(量纲/语义空间都不同),有了这两列才能识别并重算。
 */
@Data
@TableName("knowledge_vectors")
public class KnowledgeVector {
    @TableId(type = IdType.INPUT)
    private String chunkId;
    private String model;
    private Integer dimension;
    private byte[] vector;      // float32 小端
    private LocalDateTime createdAt;
}
