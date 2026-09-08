package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会议 Agent 运行事件(对齐 FastAPI models.MeetingAgentEvent) */
@Data
@TableName("meeting_agent_events")
public class MeetingAgentEvent {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String runId;
    private Integer attempt;
    private String kind;
    private String detailJson;    // LONGTEXT
    private LocalDateTime createdAt;
}
