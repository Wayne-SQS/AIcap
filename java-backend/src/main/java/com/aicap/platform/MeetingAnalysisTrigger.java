package com.aicap.platform;

/**
 * 会议分析的**触发面**（S5′ 的触发型工具 `start_meeting_analysis` 用）。
 *
 * <p><b>为什么是接口、而不是这里直接调队列类</b>：历史上它住在 {@code com.aicap.agent}
 * 里，而原先有一个 arch 守卫测试钉住「agent 包外无人 import 它」。平台层若直接依赖，
 * 「删掉 agent/ 不牵动任何现行功能」这条保证就会失效 —— 而平台工具层恰恰是
 * <b>不该随老会议 Agent 退役</b> 的那部分。
 *
 * <p>所以照 {@code worker/WorkerTask} 的做法：<b>中性包定义接口，域包内给实现</b>，
 * 平台层只依赖接口 + {@code ObjectProvider} 可选注入。
 *
 * <p><b>2026-10-13：</b>老会议 Agent 包（含其唯一的 {@code MeetingAnalysisTrigger}
 * 实现）已删除，当前<b>没有实现 Bean</b> —— 触发型工具如实报
 * {@code agent_tool_unavailable}，而不是 500。会议分析现由 {@code ai-service/}
 * （Python 8090，五类会议）承担；若要把该工具重新接上，应指向新链路而非复活老包。
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
