package com.aicap.platform;

/**
 * 会议分析的**触发面**（S5′ 的触发型工具 `start_meeting_analysis` 用）。
 *
 * <p><b>为什么是接口、而不是这里直接调 {@code AgentJobs}</b>：那是 {@code com.aicap.agent}
 * 里的类，而 {@code arch/AgentPackageIsSelfContainedTest} 钉住「agent 包外无人 import 它」——
 * 平台层直接依赖会让「删掉 {@code agent/} 不牵动任何现行功能」这条保证失效，
 * 而平台工具层恰恰是**不该随老会议 Agent 退役**的那部分。
 *
 * <p>所以照 {@code worker/WorkerTask} 的既有做法：<b>中性包定义接口，agent 包内给实现</b>，
 * 平台层只依赖接口。agent 退役时这里没有实现 Bean，该工具如实报
 * {@code agent_tool_unavailable}，而不是 500。
 */
public interface MeetingAnalysisTrigger {

    /**
     * 触发（或取回）该会议的分析 run。
     *
     * <p>幂等口径由实现决定：底层 {@code meeting_agent_runs} 有
     * {@code UNIQUE KEY meeting_id} —— 一个会议只会有一条 run，所以「已存在」即返回它。
     */
    Triggered trigger(String meetingId, int requesterId);

    /**
     * @param deduplicated true 表示返回的是**既有** run（没有新建）
     */
    record Triggered(String runId, String status, boolean deduplicated) {
    }
}
