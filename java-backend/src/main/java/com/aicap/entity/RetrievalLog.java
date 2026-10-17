package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次混合检索的日志(S2)。
 *
 * <p>三个 JSON 列分别对应检索管线的三个阶段,而不是只存最终结果 ——
 * 只存最终结果的话,「一条本该命中的块为什么没进 prompt」这个最常被追问的问题
 * 就永远查不出来:是没召回、被 RRF 挤下去、还是被精排判为不相关,三者完全不同的处置。
 *
 * @see com.aicap.rag.RetrievalService
 */
@Data
@TableName("retrieval_logs")
public class RetrievalLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String query;
    private Integer userId;
    private String role;
    /** 各路召回明细 [{name,items:[{chunk_id,score}]}] */
    private String routesJson;
    /** RRF 融合后候选 [{chunk_id,rrf_score}] */
    private String fusedJson;
    /** 精排后最终结果 [{chunk_id,score,reason}] */
    private String finalJson;
    /** 实际生效的精排实现:none / llm */
    private String reranker;
    /** 降级原因;NULL = 未降级。有值说明本次结果是粗排直出,不是精排结果 */
    private String degraded;
    private Integer latencyMs;
    private LocalDateTime createdAt;
}
