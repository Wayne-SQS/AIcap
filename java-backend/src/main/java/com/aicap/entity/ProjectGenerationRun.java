package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Project Generator run. Business rows are unchanged until explicit confirmation. */
@Data
@TableName("project_generation_runs")
public class ProjectGenerationRun {
    @TableId(type = IdType.INPUT)
    private String id;
    private Integer requestedBy;
    private String projectId;
    private String mode;
    private String strategy;
    private String sourceName;
    private String requestText;
    private String status;
    private Integer attempt;
    private String model;
    private String promptVersion;
    private String draftJson;
    private String errorCode;
    private String errorMessage;
    /** 认领该 run 的 worker 令牌 + 租约到期时刻:到期仍 running 即视为中断,可被回收 */
    private String workerToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
