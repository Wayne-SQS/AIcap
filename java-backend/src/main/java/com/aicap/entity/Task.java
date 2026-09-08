package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 任务(甘特条;对齐 FastAPI models.Task,含看板血缘字段) */
@Data
@TableName("tasks")
public class Task {
    @TableId(type = IdType.INPUT)
    private String id;            // T01..T16
    private String name;
    private Integer ownerId;
    private Integer hours;
    private Integer weekStart;
    private Integer weekEnd;
    private String storyRef;
    /** 所属看板卡(M-id);管理类任务为 NULL */
    private String kanbanCardId;
    /** 预估工时(加权进度分母) */
    private Integer estimatedHours;
    /** feature=开发任务挂卡 / management=管理任务 */
    private String taskType;
    /** 0待办 1进行中 2完成 3已取消 */
    private Integer status;
}
