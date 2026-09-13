package com.aicap.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会议 Agent 运行参数(绑定 application.yml 的 aicap.llm.*)。
 * 对齐 FastAPI config.py:无密钥 = 停用(手动建议仍可用)。
 */
@Data
@ConfigurationProperties(prefix = "aicap.llm")
public class AgentProperties {

    public static final String PROMPT_VERSION = "meeting-complete-v2";
    public static final int MAX_STEPS = 6;
    public static final int MAX_SECONDS = 180;
    public static final int LEASE_SECONDS = 300;
    public static final int MAX_TOKENS = 6000;
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
