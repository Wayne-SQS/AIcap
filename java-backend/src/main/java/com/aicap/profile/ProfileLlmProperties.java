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
 *   PROFILE_LLM_MODEL     默认 deepseek-flash（与 AICAP_LLM_MODEL 同一个官方列表）
 */
@Data
@Component
@ConfigurationProperties(prefix = "profile-llm")
public class ProfileLlmProperties {
    /** DeepSeek API 密钥;为空时降级为规则引擎(不影响系统其余功能) */
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    /**
     * 与 {@code LlmProperties#model} 同一个官方列表里的名字。
     *
     * <p>这里原先写的是上一代的 {@code deepseek-chat}:它已经不在 DeepSeek 官方
     * {@code /models} 列表里了(2026-10 实测只返回 {@code deepseek-flash} 与
     * {@code deepseek-v4-pro})。画像域的失败是<b>静默降级成规则引擎</b> —— 不报错、
     * 不出钱,只得出一份"看起来也有结论"的规则画像,所以更难发现。
     */
    private String model = "deepseek-flash";
    private int timeoutSeconds = 60;
    /** 规划循环最大步数(防失控) */
    private int maxSteps = 8;
    /** 单次 LLM 调用最大 tokens */
    private int maxTokens = 2000;
    private double temperature = 0.3;
}
