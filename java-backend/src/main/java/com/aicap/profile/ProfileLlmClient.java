package com.aicap.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 画像智能体专属 DeepSeek 客户端(OpenAI 兼容 chat/completions 协议)。
 * 独立于会议智能体的 ModelClient:自己的配置前缀、自己的 HTTP 栈(JDK HttpClient)、自己的异常。
 */
@Component
public class ProfileLlmClient {

    /** 一条对话消息(role: system/user/assistant/tool) */
    public record Msg(String role, String content) {
        public static Msg system(String c) { return new Msg("system", c); }
        public static Msg user(String c) { return new Msg("user", c); }
        public static Msg assistant(String c) { return new Msg("assistant", c); }
    }

    /** 一次调用的结果:正文 + token 用量 + 模型名(进 AgentRun.steps 做可观测) */
    public record ChatResult(String content, int promptTokens, int completionTokens, String model) {
        public int totalTokens() { return promptTokens + completionTokens; }
    }

    /** LLM 调用失败(网络/鉴权/限流);由调用方决定降级或留痕 */
    public static class ProfileLlmException extends RuntimeException {
        public ProfileLlmException(String message) { super(message); }
        public ProfileLlmException(String message, Throwable cause) { super(message, cause); }
    }

    private final ProfileLlmProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public ProfileLlmClient(ProfileLlmProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** 是否可用(未配置密钥时画像智能体降级为规则引擎模式) */
    public boolean available() {
        return props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    /**
     * 发起一次 chat 调用。
     * @param messages      对话消息(system/user/assistant/tool 轮次)
     * @param jsonMode      true 时强制模型输出 JSON(deepseek-chat 支持 response_format)
     */
    public ChatResult chat(List<Msg> messages, boolean jsonMode) {
        if (!available()) {
            throw new ProfileLlmException("PROFILE_LLM_API_KEY 未配置,画像智能体 LLM 内核不可用");
        }
        try {
            var body = objectMapper.createObjectNode();
            body.put("model", props.getModel());
            body.put("temperature", props.getTemperature());
            body.put("max_tokens", props.getMaxTokens());
            var arr = body.putArray("messages");
            for (Msg m : messages) {
                arr.addObject().put("role", m.role()).put("content", m.content());
            }
            if (jsonMode) {
                body.putObject("response_format").put("type", "json_object");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ProfileLlmException("DeepSeek 返回 HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body()));
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            return new ChatResult(content,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    root.path("model").asText(props.getModel()));
        } catch (ProfileLlmException e) {
            throw e;
        } catch (Exception e) {
            throw new ProfileLlmException("DeepSeek 调用失败: " + e.getMessage(), e);
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
