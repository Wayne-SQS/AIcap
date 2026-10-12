package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project_members")
public class ProjectMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String projectId;
    private Integer userId;
    private String projectRole;
    private Integer capacityHours;
    private LocalDateTime joinedAt;
}
