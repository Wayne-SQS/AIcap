package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("stories")
public class Story {
    @TableId(value = "internal_id", type = IdType.AUTO) private Long internalId;
    private String projectId;
    private String code;
    private String title; private String description; private String acceptance; private String priority;
    private String role; private String goal; private String value; private Long epicId;
    private Integer sprint; private Integer activity; private Integer status; private Integer ownerId; private LocalDateTime createdAt;

    /** External/API compatibility: callers continue to use the business code as id. */
    public String getId() { return code; }
    public void setId(String id) { this.code = id; }
}
