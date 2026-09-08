package com.aicap.controller;

import com.aicap.dto.MeetingDtos;
import com.aicap.entity.Meeting;
import com.aicap.entity.MeetingAgentRun;
import com.aicap.entity.MeetingApprovalPayload;
import com.aicap.entity.MeetingSuggestionRecord;
import com.aicap.entity.Suggestion;
import com.aicap.mapper.MeetingAgentRunMapper;
import com.aicap.mapper.MeetingApprovalPayloadMapper;
import com.aicap.mapper.SuggestionMapper;
import com.aicap.security.Roles;
import com.aicap.service.MeetingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 会议 + 建议审核接口(对齐 FastAPI routers/meetings.py)。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;
    private final SuggestionMapper suggestionMapper;
    private final MeetingAgentRunMapper runMapper;
    private final MeetingApprovalPayloadMapper payloadMapper;
    private final ObjectMapper objectMapper;

    // ---------- meetings ----------

    @PostMapping("/meetings")
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingDtos.MeetingOut createMeeting(@Valid @RequestBody MeetingDtos.MeetingIn in) {
        Meeting m = meetingService.createMeeting(in, Roles.writer());
        return toOut(m);
    }

    @GetMapping("/meetings")
    public List<MeetingDtos.MeetingOut> listMeetings() {
        Roles.any();
        return meetingService.listMeetings().stream().map(this::toOut).toList();
    }

    @GetMapping("/meetings/{meetingId}")
    public MeetingDtos.MeetingOut getMeeting(@PathVariable String meetingId) {
        Roles.any();
        return toOut(meetingService.getMeeting(meetingId));
    }

    // ---------- suggestions ----------

    @PostMapping("/suggestions")
    public MeetingDtos.SuggestionOut submitSuggestion(@Valid @RequestBody MeetingDtos.SuggestionIn in) {
        var holder = meetingService.submitSuggestionIdempotent(in, Roles.writer());
        return toOut(holder.suggestion(), holder.record());
    }

    @GetMapping("/suggestions")
    public List<MeetingDtos.SuggestionOut> listSuggestions(
            @RequestParam(required = false) String meetingId) {
        Roles.any();
        List<MeetingDtos.SuggestionOut> out = new ArrayList<>();
        for (MeetingSuggestionRecord r : meetingService.listRecords(meetingId)) {
            if (!StringUtils.hasText(r.getSuggestionId())) continue;
            Suggestion s = suggestionMapper.selectById(r.getSuggestionId());
            if (s != null) out.add(toOut(s, r));
        }
        return out;
    }

    @GetMapping("/suggestions/{suggestionId}")
    public MeetingDtos.SuggestionOut getSuggestion(@PathVariable String suggestionId) {
        Roles.any();
        MeetingSuggestionRecord record = meetingService.getRecordBySuggestionId(suggestionId);
        Suggestion suggestion = suggestionMapper.selectById(suggestionId);
        return toOut(suggestion, record);
    }

    @PostMapping("/suggestions/{suggestionId}/review")
    public MeetingDtos.SuggestionOut review(@PathVariable String suggestionId,
                                            @Valid @RequestBody MeetingDtos.ReviewIn in) {
        var holder = meetingService.review(suggestionId, in, Roles.reviewer());
        return toOut(holder.suggestion(), holder.record());
    }

    // ---------- 组装 ----------

    private MeetingDtos.MeetingOut toOut(Meeting m) {
        return new MeetingDtos.MeetingOut(m.getId(), m.getTitle(), m.getTranscript(),
                m.getCreatedBy(), m.getCreatedAt());
    }

    /** 组装 SuggestionOut(对齐 FastAPI _out:agent_run_id 推导 + approved_changes) */
    private MeetingDtos.SuggestionOut toOut(Suggestion s, MeetingSuggestionRecord record) {
        Meeting meeting = meetingService.getMeeting(record.getMeetingId());

        String agentRunId = null;
        String[] parts = record.getClientRequestId().split(":");
        if (parts.length == 3 && "agent".equals(parts[0])) {
            MeetingAgentRun run = runMapper.selectById(parts[1]);
            if (run != null && run.getResultJson() != null) {
                try {
                    JsonNode ids = objectMapper.readTree(run.getResultJson()).path("suggestion_ids");
                    if (ids.isArray()) {
                        for (JsonNode n : ids) {
                            if (s.getId().equals(n.asText())) {
                                agentRunId = run.getId();
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // result 解析失败视为无关联 run
                }
            }
        }

        MeetingDtos.PoolChanges approvedChanges = null;
        MeetingApprovalPayload approved = payloadMapper.selectById(s.getId());
        if (approved != null) {
            approvedChanges = parseChanges(approved.getChangesJson());
        }
        MeetingDtos.PoolChanges changes = parseChanges(s.getChangeJson());

        return new MeetingDtos.SuggestionOut(
                agentRunId, approvedChanges, s.getId(), meeting.getId(), meeting.getTitle(),
                "pool.create", record.getOrigin(), s.getEvidence(), s.getNote(), changes,
                s.getStatus(), s.getCreatedAt(), record.getSubmittedBy(),
                record.getReviewedBy(), record.getReviewedAt(), record.getReason(),
                record.getExecutionStatus(), record.getPoolItemId());
    }

    private MeetingDtos.PoolChanges parseChanges(String json) {
        if (json == null) return null;
        try {
            JsonNode j = objectMapper.readTree(json);
            return new MeetingDtos.PoolChanges(
                    j.path("title").asText(),
                    j.path("description").asText(""),
                    j.path("priority").asText("Could"));
        } catch (Exception e) {
            return null;
        }
    }
}
