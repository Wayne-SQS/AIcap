package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 项目里程碑，周次与关联任务均来自统一项目数据源。 */
@Data
@TableName("milestones")
public class Milestone {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private Integer week;
    private String description;
    private String status;
    private String relatedTaskIds;
}
