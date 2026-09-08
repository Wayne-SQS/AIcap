package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 建议(对齐 FastAPI models.Suggestion;change_json 为数组 JSON 文本) */
@Data
@TableName("suggestions")
public class Suggestion {
    @TableId(type = IdType.INPUT)
    private String id;            // S.. 或 SG..
    private String agent;
    private String kind;          // meeting/submit
    private String evidence;
    private String affected;
    private String note;
    private String changeJson;    // JSON 数组
    private String status;        // pending/approved/rejected
    private LocalDateTime createdAt;
}
