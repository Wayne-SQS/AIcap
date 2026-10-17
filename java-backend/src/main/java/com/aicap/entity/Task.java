package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tasks")
public class Task {
    @TableId(value = "internal_id", type = IdType.AUTO) private Long internalId;
    private String projectId; private String code; private String name; private Integer ownerId; private Integer hours; private Integer weekStart; private Integer weekEnd; private Long storyInternalId; private String storyRef;
    private String storyRefLegacy; private String dependsOnLegacy; private String kanbanCardIdLegacy;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String kanbanCardId;
    private Integer estimatedHours; private String taskType; private Integer status; private String dependsOn; private String priority; private Integer progress; private Integer blocked;

    /** External/API compatibility: callers continue to use the business code as id. */
    public String getId() { return code; }
    public void setId(String id) { this.code = id; }
}
