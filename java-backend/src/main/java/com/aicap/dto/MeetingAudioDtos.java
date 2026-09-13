package com.aicap.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/** 会议音频 DTO(snake_case 契约;请求字段容忍 camelCase 别名) */
public final class MeetingAudioDtos {

    private MeetingAudioDtos() {
    }

    /** 上传表单的附加字段(source/duration_ms 由前端在 multipart 中一并提交) */
    public record UploadMeta(@JsonAlias("source") String source,
                             @JsonProperty("duration_ms") @JsonAlias("durationMs") Integer durationMs) {
    }

    /** 音频输出 */
    public record AudioOut(String id,
                           @JsonProperty("meeting_id") String meetingId,
                           String filename,
                           @JsonProperty("content_type") String contentType,
                           @JsonProperty("byte_size") Integer byteSize,
                           @JsonProperty("duration_ms") Integer durationMs,
                           String sha256,
                           String source,
                           @JsonProperty("uploaded_by") Integer uploadedBy,
                           @JsonProperty("created_at") LocalDateTime createdAt,
                           /** 回放/下载地址(带鉴权头访问) */
                           String url) {
    }
}
