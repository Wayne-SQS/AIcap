package com.aicap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 会议 + 建议审核域 DTO(对齐 FastAPI meeting_schemas.py:snake_case,extra=forbid 语义由 JSON 反序列化兜底) */
public final class MeetingDtos {

    private MeetingDtos() {
    }

    /** POST /api/meetings(MeetingIn;FastAPI extra=forbid → 未知字段拒绝,不加 ignoreUnknown) */
    public record MeetingIn(@NotBlank @Size(max = 200) String title,
                            @NotBlank @Size(max = 16000) String transcript) {
    }

    /** 会议输出(MeetingOut) */
    public record MeetingOut(String id, String title, String transcript,
                             @JsonProperty("created_by") Integer createdBy,
                             @JsonProperty("created_at") LocalDateTime createdAt) {
    }

    /**
     * DELETE /api/meetings/{id} 响应(风格对齐 PoolDtos.DeleteOut:200 + JSON body,不用 204)。
     * 除 {@code deleted} 外回报级联清理计数,便于前端提示与契约断言:
     * {@code audio_deleted}=实际删除的落盘音频文件数(best-effort,盘上已缺失的文件不计入),
     * {@code suggestions_deleted}=删除的建议数,{@code runs_deleted}=删除的 Agent 分析任务数。
     */
    public record DeleteOut(String id, boolean deleted,
                            @JsonProperty("audio_deleted") int audioDeleted,
                            @JsonProperty("suggestions_deleted") int suggestionsDeleted,
                            @JsonProperty("runs_deleted") int runsDeleted) {
    }

    /** 需求池变更载荷(PoolChanges;FastAPI extra=forbid → 未知字段拒绝) */
    public record PoolChanges(@NotBlank @Size(max = 200) String title,
                              @Size(max = 10000) String description,
                              @Pattern(regexp = "^(Must|Should|Could)$") String priority) {
        public PoolChanges {
            if (description == null) description = "";
            if (priority == null) priority = "Could";
        }
    }

    /** POST /api/suggestions(SuggestionIn;FastAPI extra=forbid → 未知字段拒绝) */
    public record SuggestionIn(
            @NotBlank @Size(max = 36) @JsonProperty("meeting_id") String meetingId,
            @NotBlank @Size(max = 80) @JsonProperty("client_request_id") String clientRequestId,
            @Pattern(regexp = "^(pool\\.create)$") String action,
            @Pattern(regexp = "^(manual|agent)$") String origin,
            @NotBlank @Size(max = 5000) String evidence,
            @Size(max = 2000) String note,
            @NotNull @Valid PoolChanges changes) {
        public SuggestionIn {
            if (action == null) action = "pool.create";
            if (origin == null) origin = "manual";
            if (note == null) note = "";
        }
    }

    /** POST /api/suggestions/{id}/review(ReviewIn;modify_and_approve 必须带 changes,其余不得带;FastAPI extra=forbid) */
    public record ReviewIn(
            @NotBlank @Pattern(regexp = "^(approve|reject|modify_and_approve)$") String decision,
            @Valid PoolChanges changes,
            @Size(max = 1000) String reason) {
        public ReviewIn {
            if (reason == null) reason = "";
        }
    }

    /** 建议输出(SuggestionOut;前端读 meeting_id/meeting_title/evidence/changes/approved_changes/status 等) */
    public record SuggestionOut(
            @JsonProperty("agent_run_id") String agentRunId,
            @JsonProperty("approved_changes") PoolChanges approvedChanges,
            String id,
            @JsonProperty("meeting_id") String meetingId,
            @JsonProperty("meeting_title") String meetingTitle,
            String action,
            String origin,
            String evidence,
            String note,
            PoolChanges changes,
            String status,
            @JsonProperty("created_at") LocalDateTime createdAt,
            @JsonProperty("submitted_by") Integer submittedBy,
            @JsonProperty("reviewed_by") Integer reviewedBy,
            @JsonProperty("reviewed_at") LocalDateTime reviewedAt,
            String reason,
            @JsonProperty("execution_status") String executionStatus,
            @JsonProperty("pool_item_id") String poolItemId) {
    }
}
