package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 动态能力画像快照(按时间范围整包 JSON;区分客观事实与 AI 推断) */
@Data
@TableName("profile_snapshots")
public class ProfileSnapshot {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer userId;
    private LocalDate rangeStart;
    private LocalDate rangeEnd;
    /** 画像整包:擅长方向/难度承受/交付及时性/负载/风险/推荐任务 */
    private String payloadJson;
    /** ai=智能体 / manual=人工 */
    private String generatedBy;
    private LocalDateTime createdAt;
}
