package com.aicap.platform;

/**
 * 五类会议(ai-service 的类型化分析口径)。
 *
 * <p>为什么放在平台包:它是**跨进程的共同词汇** —— 清单 schema 要把 {@code meeting_type}
 * 声明成枚举供主管识别,触发面又要按它选端点。两边都得看得见它,而平台层不得编译期依赖
 * 某个域包,故落在中性位置(与 {@link MeetingAnalysisTrigger} 同一处)。
 *
 * <p>{@code wire()} 是 ai-service 端点与请求体里用的字面量,**不要改**;
 * 改了要同步 {@code ai-service/meeting_agent/api.py} 的 {@code Literal[...]}。
 */
public enum MeetingType {

    /** 站会:每日同步(Daily Scrum) */
    STATUS("daily_scrum"),
    /** 规划会:Sprint 规划 */
    PLANNING("sprint_planning"),
    /** 评审会:Sprint 评审 */
    REVIEW("sprint_review"),
    /** 回顾会:Sprint 回顾 */
    RETRO("sprint_retrospective"),
    /** 需求梳理会:待办项精化 */
    REFINEMENT("backlog_refinement");

    private final String wire;

    MeetingType(String wire) {
        this.wire = wire;
    }

    /** ai-service 侧的字面量(请求体的 {@code meeting_type}) */
    public String wire() {
        return wire;
    }

    /**
     * 按输入解析。接受 ai-service 的字面量,也接受本枚举名(大小写不敏感),
     * 容忍 {@code planning}/{@code retro} 这类简称 —— 模型给的是自然语言边的值,
     * 这里宽进严出:解析不出就返回 null,由调用方拒为 invalid_arguments。
     */
    public static MeetingType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        for (MeetingType t : values()) {
            if (t.wire.equalsIgnoreCase(v) || t.name().equalsIgnoreCase(v)) {
                return t;
            }
        }
        return null;
    }
}
