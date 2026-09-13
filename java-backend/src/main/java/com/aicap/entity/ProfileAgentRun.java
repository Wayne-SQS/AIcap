package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 画像智能体分析运行记录(可重试、失败留痕) */
@Data
@TableName("profile_agent_runs")
public class ProfileAgentRun {
    @TableId(type = IdType.INPUT)
    private String id;
    private LocalDate rangeStart;
    private LocalDate rangeEnd;
    private Integer requestedBy;
    /** pending/running/succeeded/failed */
    private String status;
    private Integer attempt;
    private String resultJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
