package com.aicap.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 画像智能体 DTO(契约 snake_case,容忍 camelCase 别名) */
public final class ProfileAgentDtos {

    private ProfileAgentDtos() {
    }

    /** POST /api/profile-agent/activities 录入活动(契约字段为 snake_case,容忍 camelCase) */
    public record ActivityIn(@NotNull @JsonAlias("user_id") Integer userId,
                             @Size(max = 10) @JsonAlias("task_id") String taskId,
                             @NotBlank @Size(max = 20) @JsonAlias("activity_type") String activityType,
                             @NotBlank @Size(max = 200) String title,
                             @Size(max = 1000) String detail,
                             @Size(max = 100) String module,
                             @NotNull @JsonAlias("happened_at") String happenedAt) {
        public ActivityIn {
            if (detail == null) detail = "";
            if (module == null) module = "";
        }
    }

    /** 活动输出 */
    public record ActivityOut(@JsonProperty("activity_id") Integer activityId,
                              @JsonProperty("user_id") Integer userId,
                              @JsonProperty("task_id") String taskId,
                              @JsonProperty("activity_type") String activityType,
                              String title,
                              String detail,
                              String module,
                              String source,
                              @JsonProperty("happened_at") String happenedAt) {
    }

    /** PATCH /api/profile-agent/difficulty/{taskId} 人工修正难度 */
    public record DifficultyIn(@NotBlank @Size(max = 10) String level,
                               @Min(0) @Max(100) Integer score,
                               @Size(max = 500) String reason) {
    }

    /** 难度评估输出 */
    public record DifficultyOut(@JsonProperty("task_id") String taskId,
                                String level,
                                @Min(0) Integer score,
                                Object basis,
                                @JsonProperty("assessed_by") String assessedBy) {
    }

    /** GET /api/profile-agent/analysis?start=&end= 查询参数 */
    public record AnalysisQuery(LocalDate start, LocalDate end) {
    }

    /** 运行记录输出 */
    public record RunOut(@JsonProperty("run_id") String runId,
                         @JsonProperty("range_start") LocalDate rangeStart,
                         @JsonProperty("range_end") LocalDate rangeEnd,
                         String status,
                         Integer attempt,
                         @JsonProperty("error_message") String errorMessage,
                         @JsonProperty("created_at") String createdAt) {
    }
}
