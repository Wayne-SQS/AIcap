package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主管路由留痕(S5′，契约 §4.3)。
 *
 * <p>这张表是 <b>S7「路由质量」指标的唯一数据源</b> —— 原 S7 只测工具选择、不含主管的
 * intent。{@code asked_clarify=1} 的行就是"该澄清时澄清了"的样本。
 *
 * <p>{@code utterance} 是**用户原话(隐私)**：只允许落在本表(读接口限 admin/owner),
 * 不得进日志、事件 detail 或异常文本。
 *
 * <p>字段显式标 {@code @JsonProperty}:Jackson 没配全局命名策略,不标就会吐 camelCase
 * ({@code askedClarify}),与本项目其余接口及契约 §4.3 的列名分叉 —— 这种分叉编译不报错,
 * 对接方按文档取字段只会拿到 null(同 §2.3 对工具信封的警告)。
 */
@Data
@TableName("routing_logs")
public class RoutingLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("user_id")
    private Integer userId;

    private String utterance;
    private String intent;
    private String route;
    private BigDecimal confidence;
    private String reason;

    @JsonProperty("asked_clarify")
    private Boolean askedClarify;

    @JsonProperty("candidates")
    private String candidatesJson;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
