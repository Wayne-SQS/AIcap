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
import com.aicap.service.MeetingAudioService;
import com.aicap.service.MeetingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final MeetingAudioService audioService;
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

    /**
     * DELETE /api/meetings/{meetingId}:删除会议及其全部从属数据(FE-D03),200 + JSON body。
     * <p><b>权限</b>:admin/owner({@link Roles#reviewer()}),与同类敏感删除
     * ({@code StoryController#delete}、{@code MeetingAudioController#delete})口径一致;会议不存在 → 404 {"detail":...}。
     * <p><b>级联顺序</b>(表间外键均无 ON DELETE CASCADE,必须手工反序清理):
     * meeting_agent_events → meeting_agent_runs → meeting_audio → meeting_approval_payloads
     * → meeting_suggestion_records → suggestions → meetings。已审核通过落库的 pool_items 不删(独立产物)。
     * <p><b>事务/文件时序</b>:数据库部分全部在 {@code MeetingService#deleteMeeting} 的
     * {@code @Transactional} 里(要么全成要么全回滚);本方法自身不加事务,
     * 因此 service 返回即代表事务已提交,之后才删除磁盘音频文件(best-effort,失败只记日志、不影响接口成功)。
     * 这样既避免"事务回滚了但文件已删",也不需要在 controller 里手工管理事务边界。
     */
    @DeleteMapping("/meetings/{meetingId}")
    public MeetingDtos.DeleteOut deleteMeeting(@PathVariable String meetingId) {
        Roles.reviewer();
        MeetingService.MeetingDeletion deletion = meetingService.deleteMeeting(meetingId);
        int audioFilesDeleted = audioService.deleteStoredFiles(deletion.audioStoragePaths());
        return new MeetingDtos.DeleteOut(meetingId, true, audioFilesDeleted,
                deletion.suggestionsDeleted(), deletion.runsDeleted());
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
        // 非会议来源(如画像智能体)的建议没有所属会议,meeting 字段置空而不是 404
        Meeting meeting = record.getMeetingId() == null ? null : meetingService.getMeeting(record.getMeetingId());

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
                agentRunId, approvedChanges, s.getId(),
                meeting == null ? null : meeting.getId(),
                meeting == null ? null : meeting.getTitle(),
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
