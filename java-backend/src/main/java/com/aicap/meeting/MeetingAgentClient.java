package com.aicap.meeting;

import com.aicap.common.AgentError;
import com.aicap.platform.MeetingType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * 调 ai-service(会议智能体,Python)的 HTTP 客户端。本仓 Java 侧**唯一**反向调用它的地方。
 *
 * <p><b>令牌纪律</b>:用户的 Bearer 令牌只作为参数在本方法的调用栈里走一趟,
 * 既不写日志也不进异常消息(红线⑤的同一条口径:令牌只存在于单次调用闭包)。
 * 上游失败时**只**回错误码,绝不把上游响应体带出来(红线②)。
 *
 * <p><b>协议</b>:ai-service 的五类分析各自一个端点,入参
 * {@code {client_request_id, meeting_type, current_sprint?, target_sprint?}},
 * 成功回 {@code {analysis_id, meeting_id, client_request_id, storage_status}}。
 * {@code client_request_id} 由本层生成(一次派活一个),ai-service 据此幂等。
 */
@Slf4j
@Component
public class MeetingAgentClient {

    private final MeetingAgentProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public MeetingAgentClient(MeetingAgentProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                .build();
    }

    /**
     * 一次分析的结果指针。{@code analysisId} 落在 {@code meeting_*_analyses} 表里,前端据此打开面板。
     */
    public record Analyzed(String analysisId, String meetingId, String storageStatus) {
    }

    /**
     * 触发一次会议分析。
     *
     * @param meetingType     五类之一(status/planning/review/retro/refinement)
     * @param currentSprint   可空
     * @param targetSprint    可空(仅 planning 用)
     * @param bearerToken     调用方的用户令牌;由控制器从 Authorization 头原样透传
     */
    public Analyzed analyze(String meetingId, MeetingType meetingType,
                            Integer currentSprint, Integer targetSprint, String bearerToken) {
        if (!props.isEnabled()) {
            throw new AgentError("agent_tool_unavailable", "会议智能体未启用(aicap.meeting-agent.enabled=false)");
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            // 没有用户令牌就没法读回 Java 的会议原文与成员表 —— 与其让 ai-service 报 401,不如在这里说清
            throw new AgentError("invalid_arguments", "缺少调用方令牌,无法以用户身份触发会议分析");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("client_request_id", UUID.randomUUID().toString());
        body.put("meeting_type", meetingType.wire());
        if (currentSprint != null) {
            body.put("current_sprint", currentSprint);
        }
        if (targetSprint != null) {
            body.put("target_sprint", targetSprint);
        }

        String url = props.getBaseUrl().replaceAll("/+$", "") + pathFor(meetingType, meetingId);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getReadTimeoutSeconds()))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new AgentError("invalid_arguments", "会议分析请求构造失败: " + e.getMessage());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentError("provider_timeout", "会议分析被中断");
        } catch (Exception e) {
            // 只报类型与目标,不回显上游内容
            throw new AgentError("agent_unavailable",
                    "连不上会议智能体 " + props.getBaseUrl() + "(它在跑吗?)");
        }

        int status = response.statusCode();
        if (status == 200) {
            try {
                JsonNode json = mapper.readTree(response.body());
                String analysisId = json.path("analysis_id").asText("");
                if (analysisId.isEmpty()) {
                    throw new AgentError("invalid_model_proposal", "会议智能体未返回 analysis_id");
                }
                return new Analyzed(analysisId, json.path("meeting_id").asText(meetingId),
                        json.path("storage_status").asText(""));
            } catch (AgentError e) {
                throw e;
            } catch (Exception e) {
                throw new AgentError("invalid_model_proposal", "会议智能体响应无法解析");
            }
        }

        // 非 200:只取上游的稳定错误码(detail),绝不回显响应体原文(红线②)
        String code = upstreamCode(response.body(), status);
        log.warn("meeting agent analyze failed: status={} code={} meetingId={} type={}",
                status, code, meetingId, meetingType.wire());
        throw new AgentError(code, "会议分析未通过:" + code);
    }

    /**
     * 五类各自的端点。ai-service 的路径是「类型进路径」而不是「类型进 body」,
     * 所以这里必须一一对上;多一类少一类都会在运行时变成 404。
     */
    private static String pathFor(MeetingType type, String meetingId) {
        String prefix = "/api/meetings/" + meetingId;
        return switch (type) {
            case STATUS -> prefix + "/analyze";
            case PLANNING -> prefix + "/planning/analyze";
            case REVIEW -> prefix + "/review/analyze";
            case RETRO -> prefix + "/retro/analyze";
            case REFINEMENT -> prefix + "/refinement/analyze";
        };
    }

    /**
     * 取上游错误码。ai-service 的失败响应形状是 {@code {"detail":"<code>"}}
     * (见其 {@code error_response})或 {@code {"detail":...}} 的校验错误。
     * 拿不到就按状态码归类,不把响应体带进异常。
     */
    private String upstreamCode(String responseBody, int status) {
        try {
            String detail = mapper.readTree(responseBody).path("detail").asText("");
            // detail 正常是纯错误码;校验错误的 detail 是数组,asText 会给空串
            if (!detail.isEmpty() && detail.length() <= 60 && detail.matches("[a-z0-9_]+")) {
                return detail;
            }
        } catch (Exception ignored) {
            // 落回状态码归类
        }
        if (status == 401) {
            return "authentication_required";
        }
        if (status == 403) {
            return "permission_denied";
        }
        if (status == 422) {
            return "invalid_meeting_input";
        }
        if (status == 503 || status == 504) {
            return "agent_unavailable";
        }
        return "agent_failed";
    }
}
