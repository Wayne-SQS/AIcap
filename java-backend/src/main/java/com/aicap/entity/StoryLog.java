package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("story_logs")
public class StoryLog {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String projectId;
    private Long storyInternalId;
    private String storyCodeSnapshot;
    private String storyId;
    private String logType;
    private String detail;
    private Integer userId;
    private LocalDateTime createdAt;
}
