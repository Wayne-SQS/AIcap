package com.aicap.meeting;

import com.aicap.platform.MeetingAnalysisTrigger;
import com.aicap.platform.MeetingType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link MeetingAnalysisTrigger} 的实现：把平台层的派活转发到会议智能体（ai-service，8090）。
 *
 * <p>住在 {@code com.aicap.meeting} 而不是平台包：平台层只认接口（{@code ObjectProvider}
 * 可选注入），实现落在域侧——这样「会议链路换实现」不牵动平台层，平台层也不因为
 * 「会议智能体没跑」而起不来。
 *
 * <p>{@code aicap.meeting-agent.enabled=false} 时本 Bean 不注册，清单里那枚工具随之消失
 * （{@code PlatformToolCatalog} 按实现发布）。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aicap.meeting-agent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MeetingAnalysisTriggerImpl implements MeetingAnalysisTrigger {

    private final MeetingAgentClient client;

    @Override
    public Analyzed analyze(String meetingId, MeetingType type,
                            Integer currentSprint, Integer targetSprint, String bearerToken) {
        // 失败一律以 AgentError 上抛(错误码来自 ai-service 的 detail 或本层归类),
        // 由 PlatformToolService 翻成 ok=false 的工具结果 —— 模型能据此自我纠正。
        MeetingAgentClient.Analyzed done =
                client.analyze(meetingId, type, currentSprint, targetSprint, bearerToken);
        return new Analyzed(done.analysisId(), done.meetingId(), done.storageStatus());
    }
}
