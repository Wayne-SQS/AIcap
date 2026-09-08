package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会议 Agent 运行(对齐 FastAPI models.MeetingAgentRun) */
@Data
@TableName("meeting_agent_runs")
public class MeetingAgentRun {
    @TableId(type = IdType.INPUT)
    private String id;            // UUID 36
    private String meetingId;     // unique
    private Integer requestedBy;
    private String status;        // queued/running/awaiting_review/completed/failed
    private Integer attempt;
    private String model;
    private String promptVersion;
    private String workerToken;   // 可空
    private LocalDateTime leaseUntil; // 可空
    private String resultJson;    // 可空, LONGTEXT
    private String errorCode;     // 可空
    private String errorMessage;  // 可空
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
