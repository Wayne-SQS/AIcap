package com.aicap.rag;

import java.util.List;

/**
 * 一路召回(设计文档 A5)。
 *
 * <p>三路并行、各自独立,是刻意的设计:任何一路挂掉都不该让整次检索失败,只该让
 * 结果的召回面变窄。因此各实现的 {@code retrieve} 约定<b>不向上抛异常</b> ——
 * 上游不可用时返回空列表并记 WARN,由 {@link RetrievalService} 在日志里留下降级痕迹。
 *
 * <p>三路各自要解决的问题(缺一路就有一个可复现的退化):
 * <ul>
 *   <li>{@link VectorRetriever} —— 语义相近,但专有名词(US01/T12/SetLamp)区分不出来;</li>
 *   <li>{@link KeywordRetriever} —— 专有名词精确命中,但换个说法就问不到;</li>
 *   <li>{@link GraphRetriever} —— 结构化关系,答的是"和它相关的东西",两路都答不了。</li>
 * </ul>
 */
public interface Retriever {

    /** 路名;进检索日志与调试台,因此必须稳定且可读 */
    String name();

    /**
     * 召回。
     *
     * @param query 原始查询词(不保证非空,实现需自行兜底)
     * @param topK  本路最多返回条数
     * @param ctx   调用者上下文,<b>ACL 必须在实现内部生效</b> ——
     *              三路都必须过滤,漏一路就等于开了一个绕权后门(设计文档 A7)
     */
    List<Hit> retrieve(String query, int topK, RetrievalContext ctx);
}
