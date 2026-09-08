package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 故事变更日志(对齐 FastAPI models.StoryLog) */
@Data
@TableName("story_logs")
public class StoryLog {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String storyId;
    private String logType;       // create/edit/move/del
    private String detail;
    private Integer userId;
    private LocalDateTime createdAt;
}
