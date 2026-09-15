package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 成员对 AI 画像的纠正(文档 4.7:允许成员纠正错误信息;纠正永久保留并展示在画像旁) */
@Data
@TableName("profile_corrections")
public class ProfileCorrection {
    @TableId(type = IdType.AUTO)
    private Integer id;
    /** 被纠正的画像所属成员(本人提交) */
    private Integer userId;
    /** 纠正的画像字段,如 good_at/recommended_task_types */
    private String field;
    /** 成员确认的正确描述 */
    private String correctedValue;
    /** 纠正理由(可选) */
    private String reason;
    private Integer createdBy;
    private LocalDateTime createdAt;
}
