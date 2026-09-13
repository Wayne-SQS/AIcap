package com.aicap.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat Completions 直连客户端(无第三方 SDK 依赖;对齐 FastAPI meeting_agent/model_client.py)。
 * 仅调用 /chat/completions;上游非 200 一律不落库响应体(防凭据/隐私回显)。
 */
@Component
public class ModelClient {

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    /** 每次运行可覆盖(优先 run 上记录的模型名);null = 用配置默认 */
    private String modelOverride;

    public ModelClient(AgentProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, props.getTimeoutSeconds())))
                .build();
    }

    public void setModelOverride(String model) {
        this.modelOverride = model;
    }

    /** 一次助手回复:message 为可直接回填 messages 的 assistant 消息节点 */
    public record Completion(JsonNode message, Map<String, Integer> usage) {
    }

    /** 对齐 model_client.complete():非 final 时携带 tools + tool_choice=auto;final 时强制 json_object */
    public Completion complete(List<JsonNode> messages, List<JsonNode> tools, boolean finalRound) throws AgentError {
        if (!props.settingsReady()) {
            throw new AgentError("not_configured", "服务端尚未配置模型密钥/模型名称");
        }
        URI base = parseBaseUrl(props.getBaseUrl());
        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelOverride != null ? modelOverride : props.getModel());
        body.set("messages", mapper.valueToTree(messages));
        body.put("stream", false);
        body.put("max_tokens", AgentProperties.MAX_TOKENS);
        if ("api.deepseek.com".equals(base.getHost())) {
            // DeepSeek 当前默认思考模式;本循环需要非思考响应
            ObjectNode thinking = mapper.createObjectNode();
            thinking.put("type", "disabled");
            body.set("thinking", thinking);
        }
        if (finalRound) {
            ObjectNode rf = mapper.createObjectNode();
            rf.put("type", "json_object");
            body.set("response_format", rf);
        } else {
            body.set("tools", mapper.valueToTree(tools));
            body.put("tool_choice", "auto");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(props.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build();
        } catch (IOException e) {
            throw new AgentError("provider_unreachable", "无法连接模型服务，请检查服务端网络");
        }

        final HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new AgentError("provider_timeout", "模型请求超时，可重试；未自动写入需求池");
        } catch (IOException e) {
            if (e.getCause() instanceof ConnectException || e instanceof ConnectException) {
                throw new AgentError("provider_unreachable", "无法连接模型服务，请检查服务端网络");
            }
            throw new AgentError("provider_unreachable", "无法连接模型服务，请检查服务端网络");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentError("interrupted", "分析已中断，可重试");
        }

        if (response.statusCode() != 200) {
            // 绝不持久化上游响应体:可能回显凭据或私有文本
            throw new AgentError("provider_error",
                    "模型服务返回 HTTP " + response.statusCode() + "，请检查配置或稍后重试");
        }

        final JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new AgentError("invalid_response", "模型服务返回了无法解析的响应");
        }
        try {
            JsonNode choice = root.path("choices").path(0);
            if (choice.isMissingNode() || choice.isNull()) {
                throw new AgentError("invalid_response", "模型服务返回了无法解析的响应");
            }
            String finish = choice.path("finish_reason").asText(null);
            if (!"stop".equals(finish) && !"tool_calls".equals(finish)) {
                throw new AgentError("incomplete_response", "模型输出被截断或中断，未生成任何建议");
            }
            JsonNode messageNode = choice.path("message");
            JsonNode calls = messageNode.path("tool_calls");
            if (!calls.isMissingNode() && !calls.isNull()) {
                if (!calls.isArray() || calls.size() > 8) {
                    throw new AgentError("invalid_response", "模型服务返回了无法解析的响应");
                }
                for (JsonNode call : calls) {
                    if (!call.path("id").isTextual() || !"function".equals(call.path("type").asText(null))
                            || !call.path("function").path("name").isTextual()
                            || !call.path("function").path("arguments").isTextual()) {
                        throw new AgentError("invalid_response", "模型服务返回了无法解析的响应");
                    }
                }
            }
            JsonNode content = messageNode.path("content");
            if (!content.isNull() && !content.isMissingNode() && !content.isTextual()) {
                throw new AgentError("invalid_response", "模型服务返回了无法解析的响应");
            }
            if (finalRound && !calls.isMissingNode() && !calls.isNull() && calls.size() > 0) {
                throw new AgentError("invalid_response", "模型服务返回了无法解析的响应");
            }
            Map<String, Integer> usage = new LinkedHashMap<>();
            JsonNode usageNode = root.path("usage");
            for (String key : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
                JsonNode v = usageNode.path(key);
                if (v.isInt()) usage.put(key, v.asInt());
            }
            ObjectNode assistant = mapper.createObjectNode();
            assistant.put("role", "assistant");
            if (content.isNull() || content.isMissingNode()) {
                assistant.putNull("content");
            } else {
                assistant.put("content", content.asText());
            }
            if (!calls.isMissingNode() && !calls.isNull() && calls.size() > 0) {
                assistant.set("tool_calls", calls.deepCopy());
            }
            return new Completion(assistant, usage);
        } catch (AgentError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AgentError("invalid_response", "模型服务返回了无法解析的响应");
        }
    }

    /** 对齐 FastAPI:仅允许 https;http 只对本机(localhost/127.0.0.1)放行 */
    private static URI parseBaseUrl(String raw) throws AgentError {
        try {
            URI uri = URI.create(raw.replaceAll("/+$", ""));
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean localHttp = "http".equals(scheme)
                    && (host == null || "localhost".equals(host) || "127.0.0.1".equals(host));
            if (!"https".equals(scheme) && !localHttp) {
                throw new AgentError("invalid_config", "模型地址需要 HTTPS（本机测试服务除外）");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new AgentError("invalid_config", "模型地址需要 HTTPS（本机测试服务除外）");
        }
    }
}
