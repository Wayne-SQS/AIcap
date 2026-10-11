package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主管对话框会话(S4′.2,契约 §4.1)。
 *
 * <p>{@code user_id} 是<b>会话归属人</b>。查会话一律 {@code WHERE user_id = 当前用户}
 * —— 主管智能体借提问者的工牌，会话列表也不例外(契约 §4.1 注)。
 */
@Data
@TableName("agent_sessions")
public class AgentSession {
    /** UUID 36，由建会话时生成 */
    @TableId(type = IdType.INPUT)
    private String id;
    private Integer userId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
