package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 需求池条目(对齐 FastAPI models.PoolItem) */
@Data
@TableName("pool_items")
public class PoolItem {
    @TableId(type = IdType.INPUT)
    private String id;            // R01..
    private String title;
    private String description;
    private String source;
    private String priority;      // Must/Should/Could
    private LocalDateTime createdAt;
}
