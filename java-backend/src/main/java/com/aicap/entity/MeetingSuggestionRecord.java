package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会议建议审核记录(对齐 FastAPI models.MeetingSuggestionRecord;唯一约束 meeting_id+submitted_by+client_request_id) */
@Data
@TableName("meeting_suggestion_records")
public class MeetingSuggestionRecord {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String suggestionId;      // unique, FK->suggestions.id
    private String meetingId;
    private String clientRequestId;
    private String requestHash;
    private Integer submittedBy;
    private String origin;            // manual/agent
    private Integer reviewedBy;       // 可空
    private LocalDateTime reviewedAt; // 可空
    private String reason;
    private String executionStatus;   // not_started/executed/failed
    private String poolItemId;        // 可空,非 FK
}
