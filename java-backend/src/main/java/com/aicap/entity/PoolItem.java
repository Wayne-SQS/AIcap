package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pool_items")
public class PoolItem {
    @TableId(type = IdType.INPUT) private String id;
    private String projectId; private String title; private String description; private String source; private String priority; private LocalDateTime createdAt;
}
