package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目规划 Agent 运行记录:模型只生成计划,确认后才执行。 */
@Data
@TableName("planning_agent_runs")
public class PlanningAgentRun {
    @TableId(type = IdType.INPUT)
    private String id;
    private Integer requestedBy;
    private String requestText;
    private String status;
    private Integer attempt;
    private String model;
    private String promptVersion;
    private String contextJson;
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
