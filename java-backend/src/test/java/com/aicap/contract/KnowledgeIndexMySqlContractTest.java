package com.aicap.contract;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link KnowledgeIndexContractTest} 的 <b>MySQL 暴力检索</b>那一遍。
 *
 * <p>不需要任何额外容器即可跑通 —— 这也是 {@code aicap.rag.vector-store} 默认 mysql 的原因:
 * 几百到几千 chunk 的规模下应用层算余弦已经够用,少一个部署件就少一处故障点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql",
        // 本类不测 Agent 队列,关掉 worker 避免与其他类的上下文抢队列
        "aicap.llm.agent-worker-enabled=false",
        "aicap.rag.vector-store=mysql",
        "aicap.rag.embedding.dimension=" + EmbeddingFixture.DIMENSION,
        "aicap.rag.embedding.model=fixture-embed",
        "aicap.rag.embedding.base-url=http://127.0.0.1:19378",
        "aicap.rag.embedding.api-key=fixture-key",
        "aicap.rag.embedding.batch-size=16",
        "aicap.rag.acl.doc=admin"
})
class KnowledgeIndexMySqlContractTest extends KnowledgeIndexContractTest {

    @Override
    protected String expectedVectorStore() {
        return "mysql";
    }
}
