package com.aicap.rag;

import java.util.List;

/**
 * 向量化模型抽象。留接口的原因不是"将来可能换",而是当前就有两个真实实现路径:
 * API(开发效率)与本地 ONNX(离线可用/成本可控)——见设计文档 A3。
 *
 * <p>本 Sprint 只交付 API 实现;ONNX 实现留到需要断网演示时再补,接口先行。
 */
public interface EmbeddingModel {

    /** 维度:用于校验向量库 collection/表配置是否匹配 */
    int dimension();

    /** 模型名:随向量一起落库,换模型后能识别出哪些向量是旧的 */
    String name();

    /**
     * 批量向量化。返回顺序与入参严格一一对应(调用方依赖下标回填)。
     *
     * @throws EmbeddingException 上游不可达/非 200/返回条数或维度不符
     */
    List<float[]> embed(List<String> texts);

    default float[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }
}
