package com.aicap.contract;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link RetrievalContractTest} 的 <b>MySQL 暴力检索</b>那一遍。
 *
 * <p>ACL 落在 SQL 里:{@code c.acl_rank <= roleRank},等级现读自
 * {@code knowledge_chunks} 表。因此这一遍验证的是
 * "改配置 → 重索引写回表 → SQL 过滤生效"整条链。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql",
        "aicap.llm.agent-worker-enabled=false",
        "aicap.rag.vector-store=mysql",
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
class RetrievalMySqlContractTest extends RetrievalContractTest {

    @Override
    protected String expectedVectorStore() {
        return "mysql";
    }
}
