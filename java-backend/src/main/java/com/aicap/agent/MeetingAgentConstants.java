package com.aicap.agent;

/**
 * 会议 Agent 专属常量(不绑定配置)。
 *
 * <p>原先与 LLM 提供方配置一起挤在 {@code AgentProperties} 里,拆出后本类随
 * {@code com.aicap.agent} 一起进退役清单。共享的 LLM/运行参数见
 * {@link com.aicap.llm.LlmProperties}。
 */
public final class MeetingAgentConstants {

    public static final String PROMPT_VERSION = "meeting-complete-v2";
    /** 会议 run 的处理租约(秒);超时可由 retry 回收 */
    public static final int LEASE_SECONDS = 300;

    private MeetingAgentConstants() {
    }
}
