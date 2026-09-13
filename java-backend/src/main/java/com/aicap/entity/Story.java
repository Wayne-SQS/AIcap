package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户故事/看板卡(对齐 FastAPI models.Story) */
@Data
@TableName("stories")
public class Story {
    @TableId(type = IdType.INPUT)
    private String id;            // US01..US37(新建走 US 命名空间) 
    private String title;
    private String description;
    private String acceptance;
    private String priority;      // Must/Should/Could
    private Integer sprint;       // 1..4
    private Integer activity;     // 1..5
    private Integer status;       // 0待办 1进行中 2完成
    private Integer ownerId;      // 可空(未分配)
    private LocalDateTime createdAt;
}
