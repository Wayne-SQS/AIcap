package com.aicap.rag;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 检索层参数(绑定 application.yml 的 aicap.rag.*)。
 *
 * <p>沿用 {@code AgentProperties} 的「无密钥 = 停用」约定:embedding 三项不齐时
 * <b>不抛异常、不静默产出空结果</b>,由调用方(索引任务/管理接口)显式返回 not_configured,
 * 让前端能如实告诉用户"去配密钥",而不是显示"索引完成 0 块"。
 */
@Slf4j
@Data
@ConfigurationProperties(prefix = "aicap.rag")
public class RagProperties {

    /** 总开关;关掉后索引任务拒绝执行(检索层在 S2 接入) */
    private boolean enabled = true;

    /**
     * 向量库实现:{@code mysql} = 应用层暴力算余弦 / {@code qdrant} = REST 调 ANN 索引。
     *
     * <p>默认 mysql 而不是 qdrant:AIcap 当前只有几百到几千 chunk,暴力检索延迟已经够用,
     * 且不需要额外起容器即可开箱运行。数据规模上去后切 qdrant,两者可同时落库做对比。
     */
    private String vectorStore = "mysql";

    /** 项目文档源目录(相对 java-backend 工作目录;仓库里 docs/ 在上一级) */
    private String docsDir = "../docs";

    /** 单次索引最多处理的源记录数(0 = 不限),防止首次全量索引把额度打爆 */
    private int maxSourceRecords = 0;

    /**
     * MySQL 暴力检索单次最多扫入内存的候选向量数。
     *
     * <p>不是性能调优,是<b>内存上界</b>:候选向量全量载入堆,1024 维 float32 下
     * 2 万条约 80MB。没有这个上限,数据量涨到十万级会直接 OOM —— 而 OOM 的表现
     * 是"整个后端挂了",不是"检索变慢",排查成本高得多。触顶时记 WARN 日志。
     */
    private int mysqlMaxScan = 20000;

    private Embedding embedding = new Embedding();
    private Qdrant qdrant = new Qdrant();

    /**
     * 各源类型的最低可见角色,键为 source_type(如 {@code doc})。
     *
     * <p>默认全部 {@code member} —— 与现有接口一致:故事/任务/需求池/成员画像对任何
     * 登录用户都可见,检索层若擅自收紧会让"能看到的查不到",是另一种错。
     *
     * <p>但机制必须存在且可配:一旦某个源(例如内部交接文档)在接口层收紧了可见性,
     * 检索层要能同步收紧,否则语义检索就成了绕过权限的后门。
     */
    private Map<String, String> acl = new LinkedHashMap<>();

    /** 某源类型的最低可见角色;未配置 = member */
    public String aclRoleFor(String sourceType) {
        String role = acl.get(sourceType);
        return role == null || role.isBlank() ? "member" : role;
    }

    /**
     * 启动时把生效的 ACL 配置写进日志。
     *
     * <p>不是装饰:{@code aicap.rag.acl} 是 Map,绑定靠属性名推导,写错了(或环境变量名对不上)
     * 不会报错,只会<b>静默保持默认的全 member</b> —— 表现为"我明明配了收紧,检索还是能查到"。
     * 一行日志把这件事从"要读源码猜"变成"看一眼就知道"。
     */
    @PostConstruct
    void logEffectiveAcl() {
        if (acl.isEmpty()) {
            log.info("RAG ACL:未配置,全部源按 member 级处理(任何登录用户可见)");
        } else {
            log.info("RAG ACL:{} —— 未列出的源仍为 member 级", acl);
        }
    }

    @Data
    public static class Embedding {
        private String baseUrl = "https://api.deepseek.com";
        private String model = "";
        private String apiKey = "";
        /** 维度:写入向量库前校验,防止换模型后新旧向量混用导致相似度全是噪声 */
        private int dimension = 1024;
        /** 单次 /embeddings 请求的最大文本条数 */
        private int batchSize = 32;
        private int timeoutSeconds = 45;
        /** 单条文本最大字符数(超长直接截断,避免上游 400) */
        private int maxChars = 2000;

        /** 三项齐全才可用(对齐 AgentProperties.settingsReady) */
        public boolean settingsReady() {
            return notBlank(apiKey) && notBlank(baseUrl) && notBlank(model);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    @Data
    public static class Qdrant {
        private String baseUrl = "http://127.0.0.1:6333";
        private String collection = "aicap_chunks";
        private int timeoutSeconds = 15;
    }
}
