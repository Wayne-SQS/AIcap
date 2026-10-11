package com.aicap.agent;

import com.aicap.entity.MeetingAgentRun;
import com.aicap.mapper.MeetingAgentRunMapper;
import com.aicap.platform.MeetingAnalysisTrigger;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link MeetingAnalysisTrigger} 的 agent 侧实现。
 *
 * <p>平台层看不见 {@code AgentJobs}（看接口注释里的 arch 约束），所以这层薄壳必须住在
 * {@code com.aicap.agent} 里 —— 与 {@code MeetingWorkerTask} 之于 {@code WorkerTask} 同理。
 * {@code agent/} 退役时这个 Bean 一起消失，平台侧如实报"工具不可用"。
 *
 * <p><b>幂等口径</b>：{@code meeting_agent_runs} 有 {@code UNIQUE KEY meeting_id}，
 * 一个会议只会有一条 run —— 所以"已存在"就返回它、{@code deduplicated=true}，
 * **不论它处于什么状态**。要重跑已终局的 run 走 {@code POST /api/agent-runs/{id}/retry}。
 * 这里在调用前先读一次只为把"是否已存在"如实告诉调用方；并发下这一读可能落空
 * （另一端刚插入），此时 {@code queueRun} 会靠唯一键返回对方的 run，仅
 * {@code deduplicated} 标记偏保守（报 false），不影响正确性。
 */
@Component
@RequiredArgsConstructor
public class MeetingAnalysisTriggerAdapter implements MeetingAnalysisTrigger {

    private final AgentJobs jobs;
    private final MeetingAgentRunMapper runMapper;

    @Override
    public Triggered trigger(String meetingId, int requesterId) {
        MeetingAgentRun existing = runMapper.selectOne(new QueryWrapper<MeetingAgentRun>()
                .eq("meeting_id", meetingId));
        MeetingAgentRun run = jobs.queueRun(meetingId, requesterId);
        return new Triggered(run.getId(), run.getStatus(), existing != null);
    }
}
