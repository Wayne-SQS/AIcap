package com.aicap.rag;

import java.util.List;
import java.util.Map;

/**
 * 向量库抽象(设计文档 A4)。
 *
 * <p>两个实现都是真实交付,不是"预留扩展点":
 * {@link QdrantVectorStore} 交付 JD 明写的「会使用至少一种向量数据库」,
 * {@link MysqlVectorStore} 交付「为什么这个数据量不需要 ANN 索引」的答案。
 * 两者可同时落库,支撑同数据集的 P99 延迟对比。
 */
public interface VectorStore {

    /** 实现名,进检索日志,便于对比两实现的实际表现 */
    String name();

    /** 幂等建 collection/表;维度与实现既有配置不符时应直接失败而不是写入错维向量 */
    void ensureCollection(int dimension);

    void upsertBatch(List<Entry> entries);

    /** 源记录被删除时同步清向量,避免留下检索得到、却已不存在的"幽灵 chunk" */
    void deleteBySource(String sourceType, String sourceId);

    /**
     * 按 chunk id 精确删除。
     *
     * <p>与 {@link #deleteBySource} 的区别不是"顺手多提供个方法":一个源可能切出多块
     * (会议有 N 个片段、文档有 N 个章节),只删掉其中一块时若按源删,会把**仍然有效的
     * 兄弟块**一起清掉,表现为"重新索引后少了几块",且不会报错。
     */
    void deleteByIds(List<String> chunkIds);

    /** 向量检索;filter 由调用方给出(ACL 必须在这一层就生效,不能捞回来再筛) */
    List<ScoredId> search(float[] queryVector, int topK, RetrievalContext ctx);

    /** 已索引向量总数(管理接口展示用) */
    long count();

    record Entry(String id, float[] vector, Map<String, Object> payload) {
    }

    record ScoredId(String id, double score, Map<String, Object> payload) {
    }
}
