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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
