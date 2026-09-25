package com.aicap.contract;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link RetrievalContractTest} 的 <b>Qdrant</b>那一遍 —— 三路里向量路换了一整条实现。
 *
 * <p>ACL 落在 payload 里:{@code acl_rank} 是"最低可见角色"的数值化,Qdrant 侧用
 * {@code range.lte} 表达"等级不高于调用者"。字符串角色在 Qdrant 里没法比大小,
 * 所以才需要这个冗余字段 —— 而它一旦漏写或写错,表现是<b>向量路整体返回空</b>,
 * 不是报错。{@link RetrievalContractTest#returnsAllThreeRoutesWithStageLabels}
 * 里那条"向量路应有命中"的断言就是为这个失效模式加的。
 *
 * <p>跑之前 Qdrant 容器必须在:{@code cd backend && docker compose up -d qdrant}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql",
        "aicap.llm.agent-worker-enabled=false",
        "aicap.rag.vector-store=qdrant",
        "aicap.rag.qdrant.base-url=http://127.0.0.1:6333",
        "aicap.rag.qdrant.collection=aicap_chunks_test",
        // 本类走 NoopReranker:精排单独测,这里只钉三路召回与融合
        "aicap.rag.retrieval.rerank.provider=none",
        "aicap.rag.embedding.dimension=" + EmbeddingFixture.DIMENSION,
        "aicap.rag.embedding.model=fixture-embed",
        "aicap.rag.embedding.base-url=http://127.0.0.1:19378",
        "aicap.rag.embedding.api-key=fixture-key",
        "aicap.rag.embedding.batch-size=16",
        // doc 源收紧到 admin:否则全部源都是 member 级,ACL 过滤这条路径没有用例会走到
        "aicap.rag.acl.doc=admin"
})
class RetrievalQdrantContractTest extends RetrievalContractTest {

    /**
     * 清空测试集合。父类保证它在强制重建<b>之前</b>被调用 —— 顺序反过来的话,
     * 表现是向量路整体为空,而不是一条能读懂的错误。
     *
     * <p>不清的后果:上一轮的点还在,而它们已不在 {@code knowledge_chunks} 里
     * (那个表被 {@code reset_test_data.sql} 清过),检索却仍会把它们当有效命中返回。
     */
    @Override
    protected void resetVectorStoreBeforeIndex() {
        QdrantTestSupport.resetCollection(ragProperties);
    }

    @Override
    protected String expectedVectorStore() {
        return "qdrant";
    }
}
