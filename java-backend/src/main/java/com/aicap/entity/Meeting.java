package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会议(不可变转写;对齐 FastAPI models.Meeting) */
@Data
@TableName("meetings")
public class Meeting {
    @TableId(type = IdType.INPUT)
    private String id;            // UUID 36
    private String title;
    private String transcript;
    private Integer createdBy;
    private LocalDateTime createdAt;
}
