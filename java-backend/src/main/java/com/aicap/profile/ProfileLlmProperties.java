package com.aicap.profile;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 画像智能体独立的 LLM 配置——与会议智能体的 llm.* 完全隔离,密钥由使用者自己的环境变量提供。
 *
 * 环境变量(全部独立前缀 PROFILE_LLM_*,不要复用会议智能体的 AICAP_LLM_*):
 *   PROFILE_LLM_API_KEY   DeepSeek API 密钥(必填,不配置则画像智能体降级为规则引擎模式)
 *   PROFILE_LLM_BASE_URL  默认 https://api.deepseek.com
 *   PROFILE_LLM_MODEL     默认 deepseek-chat
 */
@Data
@Component
@ConfigurationProperties(prefix = "profile-llm")
public class ProfileLlmProperties {
    /** DeepSeek API 密钥;为空时降级为规则引擎(不影响系统其余功能) */
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-chat";
    private int timeoutSeconds = 60;
    /** 规划循环最大步数(防失控) */
    private int maxSteps = 8;
    /** 单次 LLM 调用最大 tokens */
    private int maxTokens = 2000;
    private double temperature = 0.3;

    /** 单次直调(不走工具循环)的输出预算:要一次装下聚合后的多条结论 */
    private int singleShotMaxTokens = 4000;
    /** 批量难度评估:单次请求合并的任务数(太大易超出输出上限导致 JSON 被截断) */
    private int batchSize = 8;
    /** 批量难度评估的总开关(false = 回退为逐个任务跑规划循环的旧行为,用于灰度对照) */
    private boolean batchEnabled = true;
}
