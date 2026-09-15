package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 任务难度评估(AI 依据工时/依赖/核心模块/返工等生成,必须带依据;人工可修正覆盖) */
@Data
@TableName("difficulty_assessments")
public class DifficultyAssessment {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String taskId;
    /** low/medium/high/extreme */
    private String level;
    /** 难度分 0..100 */
    private Integer score;
    /** JSON 数组:难度依据,逐条可追溯 */
    private String basis;
    /** ai=智能体 / manual=人工 */
    private String assessedBy;
    private LocalDateTime createdAt;
}
