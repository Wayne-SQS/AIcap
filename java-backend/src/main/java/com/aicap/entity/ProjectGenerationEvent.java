package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project_generation_events")
public class ProjectGenerationEvent {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String runId;
    private Integer attempt;
    private String kind;
    private String detailJson;
    private LocalDateTime createdAt;
}
