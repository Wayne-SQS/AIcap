package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_story_relations")
public class TaskStoryRelation {
    @TableId(value = "internal_id", type = IdType.AUTO)
    private Long internalId;
    private String projectId;
    private Long taskInternalId;
    private Long storyInternalId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
