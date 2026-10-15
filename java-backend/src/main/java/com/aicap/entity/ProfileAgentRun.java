package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 画像智能体分析运行记录(可重试、失败留痕) */
@Data
@TableName("profile_agent_runs")
public class ProfileAgentRun {
    @TableId(type = IdType.INPUT)
    private String id;
    /** 多项目之后必填:分析的是哪个项目(后台消费者靠它还原作用域,不能靠"当前项目") */
    private String projectId;
    private LocalDate rangeStart;
    private LocalDate rangeEnd;
    private Integer requestedBy;
    /** 单人评估的目标成员;null = 全团队 */
    private Integer onlyUserId;
    /** queued/running/succeeded/failed */
    private String status;
    private Integer attempt;
    private String resultJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /**
     * 后台消费者认领凭据。与 planning/generation 同形:没有租约时进程崩溃会让 run
     * 永久停在 running,没有任何路径回收 —— 这一次分析就是永久丢单。
     */
    private String workerToken;
    private LocalDateTime leaseUntil;
}
