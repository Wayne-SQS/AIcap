package com.aicap.contract;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link KnowledgeIndexContractTest} 的 <b>Qdrant</b>那一遍。
 *
 * <p>与 MySQL 那一遍的差异只有三行配置,但验证的是另一条代码路径:向量与角色都落在
 * Qdrant 的 point 与 payload 里,ACL 靠 {@code range.lte} 表达,重索引靠确定性
 * {@link java.util.UUID} 覆盖写。这些在 MySQL 实现上都不存在。
 *
 * <p><b>跑之前 Qdrant 容器必须在</b>:{@code cd backend && docker compose up -d qdrant}
 * (境内拉镜像会卡住,需先 {@code docker pull} 再 {@code docker tag},见 CLAUDE.md)。
 * 连不上时 {@link QdrantTestSupport#resetCollection} 会直接失败并说明原因 ——
 * 刻意不做成"连不上就跳过":静默跳过等于这条实现又没人测,而它恰恰是权限洞的高发地。
 *
 * <p>集合必须是 {@code *aicap_chunks_test},不能是开发用的 {@code aicap_chunks} ——
 * {@code @BeforeAll} 会把整个集合删掉重建,配错就是删开发数据。
 * {@link QdrantTestSupport} 里有一道 {@code _test} 后缀闸门拦这件事。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql",
        // 本类不测 Agent 队列,关掉 worker 避免与其他类的上下文抢队列
        "aicap.llm.agent-worker-enabled=false",
        "aicap.rag.vector-store=qdrant",
        "aicap.rag.qdrant.base-url=http://127.0.0.1:6333",
        "aicap.rag.qdrant.collection=aicap_chunks_test",
        "aicap.rag.embedding.dimension=" + EmbeddingFixture.DIMENSION,
        "aicap.rag.embedding.model=fixture-embed",
        "aicap.rag.embedding.base-url=http://127.0.0.1:19378",
        "aicap.rag.embedding.api-key=fixture-key",
        "aicap.rag.embedding.batch-size=16",
        "aicap.rag.acl.doc=admin"
})
class KnowledgeIndexQdrantContractTest extends KnowledgeIndexContractTest {

    /**
     * 清空测试集合 —— Qdrant 侧对应 MySQL 的 {@code reset_test_data.sql}。
     *
     * <p>不清的后果见 {@link QdrantTestSupport}:陈旧点会被 {@code /search}
     * 当有效命中返回,而本类有一条断言"向量库点数应恰好等于块数",正是为此。
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
