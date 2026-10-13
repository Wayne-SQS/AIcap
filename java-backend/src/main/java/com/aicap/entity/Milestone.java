package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("milestones")
public class Milestone {
    @TableId(value = "internal_id", type = IdType.AUTO) private Long internalId;
    private String projectId; private String code; private String name; private Integer week; private String description; private String status; private String relatedTaskIds; private String relatedTaskIdsLegacy;

    /** External/API compatibility: callers continue to use the business code as id. */
    public String getId() { return code; }
    public void setId(String id) { this.code = id; }
}
