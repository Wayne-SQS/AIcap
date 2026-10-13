package com.aicap.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 提供方与运行参数(绑定 application.yml 的 aicap.llm.*)。
 * 对齐 FastAPI config.py:无密钥 = 停用(手动建议仍可用)。
 *
 * <p>原先住在 {@code com.aicap.agent.AgentProperties},但 generation / planning / rag /
 * profile 都在用它出网,从来不是会议专属。字段名与前缀逐字未动,故 {@code aicap.llm.*}
 * 的绑定与 env {@code AICAP_LLM_*} / {@code AICAP_AGENT_WORKER_ENABLED} 完全不变。
 */
@Data
@ConfigurationProperties(prefix = "aicap.llm")
public class LlmProperties {

    /** 有界工具循环的轮数上限(会议 Agent 与 planning 共用) */
    public static final int MAX_STEPS = 6;
    /** 有界工具循环的时间预算(秒) */
    public static final int MAX_SECONDS = 180;
    public static final int MAX_TOKENS = 6000;
    public static final int PROJECT_GENERATION_MAX_TOKENS = 8192;
    public static final int PROJECT_GENERATION_TIMEOUT_SECONDS = 120;
    public static final int TIMEOUT_SECONDS = 45;

    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-v4-flash";
    private int timeoutSeconds = TIMEOUT_SECONDS;
    private boolean agentWorkerEnabled = true;

    /** 对齐 FastAPI model_client.settings_ready():三项齐全才可用 */
    public boolean settingsReady() {
        return notBlank(apiKey) && notBlank(baseUrl) && notBlank(model);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
