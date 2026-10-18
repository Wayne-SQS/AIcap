package com.aicap.platform;

/**
 * 会议分析的**触发面**（平台触发型工具 {@code start_meeting_analysis} 用）。
 *
 * <p><b>为什么是接口、而不是这里直接调客户端</b>：平台层不得编译期依赖任何具体域包。
 * 历史原因更直白：它原先住在 {@code com.aicap.agent}，而那时有一条 arch 守卫测试钉住
 * 「agent 包外无人 import 它」。所以照 {@code worker/WorkerTask} 的做法——
 * <b>中性包定义接口，域包内给实现</b>，平台层只依赖接口 + {@code ObjectProvider} 可选注入。
 * 当前实现是 {@code com.aicap.meeting.MeetingAnalysisTriggerImpl}（转发到 ai-service）。
 *
 * <p><b>2026-10-13</b>：老会议 Agent 包（含当时唯一的实现）整包删除，此后一直没有实现 Bean，
 * 清单里那枚工具随之不发布（{@code PlatformToolCatalog.meetingTriggerAvailable}）。
 *
 * <p><b>2026-10-15 契约修订（原方案被证伪）</b>：本接口原签名是
 * {@code Triggered trigger(String meetingId, int requesterId)}，语义是「建一条 run 入队，
 * 由后台消费者执行」，返回 {@code (runId, status, deduplicated)}。落到新链路时发现这条路
 * <b>走不通，而且不通在身份上</b>：
 * <ul>
 *   <li>{@code meeting_agent_runs} 队列<b>两侧都没有消费者</b>——老 agent 包退役后 Java 只把它
 *       用于「删会议时清理」与「建议回显归属」，{@code ai-service} 全模块 grep 不到该表；</li>
 *   <li>要新增消费者，它执行分析时<b>必须带用户令牌</b>才能读回 Java 的会议原文与成员表
 *       （{@code ai-service} 的 {@code current_token} 不接受无令牌调用）；而「令牌不得持久化」
 *       （本仓红线⑤：令牌只存在于单次调用闭包）使得「把令牌写进 run 行让消费者稍后自取」
 *       不可选。造服务账号则是引入新的信任面。</li>
 * </ul>
 * 于是：<b>谁持有令牌，谁在同一请求内执行</b>。主管调 {@code /api/agents/tools/{name}} 时
 * 带的就是用户 JWT，平台层把它透传下去调用 ai-service，模型产出落
 * {@code meeting_*_analyses}（前端五个面板本来就在读这些表）。令牌只在本次调用栈里走一趟，
 * 不落库、不进日志。代价是这枚工具从「瞬时建 run」变为「阻塞一次模型调用」（实测 2~3 秒）。
 *
 * <p>因此 {@code Triggered}（run 口径）被 {@link Analyzed}（分析产物口径）取代：
 * run 队列在这条链上已无消费者，再回一个永远 {@code queued} 的 run_id 只会让
 * {@code query_agent_runs} 显示一个永不推进的假进度。
 */
public interface MeetingAnalysisTrigger {

    /**
     * 触发一次会议分析，并返回落库的产物指针。
     *
     * <p>失败以 {@code MeetingAnalysisTriggerException} 抛出，错误码直接来自 ai-service 的
     * {@code detail}（如 {@code unresolved_owner} / {@code invalid_meeting_input}），
     * 或本层的归类码（{@code agent_unavailable} / {@code invalid_arguments}）。
     *
     * @param meetingId    会议 id
     * @param type         五类之一；调用方负责"没说就反问"，不在这里猜
     * @param currentSprint 可空
     * @param targetSprint  可空（仅 PLANNING 有意义）
     * @param bearerToken  调用方的用户令牌，单次调用内有效，不得持久化
     */
    Analyzed analyze(String meetingId, MeetingType type,
                     Integer currentSprint, Integer targetSprint, String bearerToken);

    /**
     * @param analysisId    落在 {@code meeting_*_analyses} 的主键，前端据此打开面板
     * @param meetingId     回显会议 id（ai-service 会回，用于比对）
     * @param storageStatus {@code pending}（有提案待审）或 {@code no_changes}（无变更）
     */
    record Analyzed(String analysisId, String meetingId, String storageStatus) {
    }
}
