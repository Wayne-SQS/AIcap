package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主管事件流(S4′.2,契约 §4.2)。
 *
 * <p><b>{@code id} 就是事件的 {@code seq}</b> —— 自增主键是"单调递增由 Java 提供"的落地方式：
 * 事件先落库拿到自增 id，再推给前端；续传时 {@code WHERE session_id=? AND id>?}。
 * 用进程内计数器当 seq 会在重启后回退，续传就错位(契约 §3.2)。
 *
 * <p>{@code id} 必须是 {@code Long}：DDL 是 BIGINT，映射成 {@code Integer} 编译不报错、
 * 小数据量测试也过，只有量大了才炸。
 */
@Data
@TableName("agent_events")
public class AgentEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String runId;
    /** 事件类型，见 agent-orchestrator/app/events.py 的 EVENT_TYPES */
    private String type;
    /** 事件载荷 JSON 文本；列是 JSON NOT NULL，故恒非空(空载荷写 {}) */
    private String detailJson;
    private LocalDateTime createdAt;
}
