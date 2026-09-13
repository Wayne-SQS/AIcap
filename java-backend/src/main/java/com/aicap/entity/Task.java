package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /**
     * 所属看板卡(USxx);管理类任务为 NULL。
     * updateStrategy=ALWAYS:解绑(显式 null)必须真正写库,默认 NOT_NULL 会跳过 null 字段,
     * 导致"响应已解绑、库中仍挂卡"。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String kanbanCardId;
    /** 预估工时(加权进度分母) */
    private Integer estimatedHours;
    /** feature=开发任务挂卡 / management=管理任务 */
    private String taskType;
    /** 0待办 1进行中 2完成 3已取消 */
    private Integer status;
    /** 前置任务 ID(逗号分隔的 Txx;空串/空表示无前置) */
    private String dependsOn;
    /** 完成百分比 0..100 */
    private Integer progress;
    /** 是否阻塞(库中 0/1,对外输出布尔) */
    private Integer blocked;
}
