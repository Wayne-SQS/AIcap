package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 知识块(设计文档 A2)。
 *
 * <p>{@code content} 是「增强前缀 + 原文」,向量化与关键词检索都用它;
 * {@code rawContent} 是不含前缀的原文,展示与证据引用用它 —— 两者不可混用:
 * 前缀里有「优先级：Must」这类结构化字段,拿它当引用证据就不再是原文了。
 */
@Data
@TableName("knowledge_chunks")
public class KnowledgeChunk {
    @TableId(type = IdType.INPUT)
    private String id;              // {source_type}:{source_id}:{chunk_index}
    private String sourceType;      // story/pool_item/task/meeting/doc/profile
    private String sourceId;
    private Integer chunkIndex;     // 从 0 起;meeting 源 = seg 号 - 1
    private String content;         // 增强前缀 + 原文
    private String rawContent;      // 原文
    private String contentHash;     // SHA-256(content)
    private String aclRole;         // member < owner < admin
    private String metadataJson;
    /**
     * 产出当前向量的 embedding 模型名。
     *
     * <p>光靠 {@code contentHash} 判断「要不要重算」是不安全的:换 embedding 模型时
     * 内容一个字符都没变,hash 全等 → 全部判为未变 → 库里留着旧模型的向量、
     * 查询却用新模型编码,相似度变成噪声<b>且不报任何错</b>。
     * 记下模型名后,「模型不一致」与「内容变了」一样触发重算。
     */
    private String embeddingModel;
    private LocalDateTime embeddedAt;  // null = 待索引
    private LocalDateTime createdAt;
}
