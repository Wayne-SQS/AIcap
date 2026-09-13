package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.dto.MeetingDtos;
import com.aicap.entity.Meeting;
import com.aicap.entity.MeetingApprovalPayload;
import com.aicap.entity.MeetingSuggestionRecord;
import com.aicap.entity.PoolItem;
import com.aicap.entity.Suggestion;
import com.aicap.entity.User;
import com.aicap.mapper.MeetingApprovalPayloadMapper;
import com.aicap.mapper.MeetingMapper;
import com.aicap.mapper.MeetingSuggestionRecordMapper;
import com.aicap.mapper.PoolItemMapper;
import com.aicap.mapper.SuggestionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会议建议服务(对齐 FastAPI services/meeting_suggestions.py + routers/meetings.py 业务语义)。
 * - stage:幂等(meeting+submitted_by+client_request_id 唯一);同 key 不同 payload → 409
 * - 证据必须是会议转写连续片段(422)
 * - review:原子认领 pending → 目标状态;approve/modify 建 PoolItem(A+id 命名空间)+ ApprovalPayload
 */
@Service
@RequiredArgsConstructor
public class MeetingService {

    private final MeetingMapper meetingMapper;
    private final SuggestionMapper suggestionMapper;
    private final MeetingSuggestionRecordMapper recordMapper;
    private final MeetingApprovalPayloadMapper payloadMapper;
    private final PoolItemMapper poolItemMapper;
    private final ObjectMapper objectMapper;

    // ---------- meetings ----------

    @Transactional
    public Meeting createMeeting(MeetingDtos.MeetingIn in, User user) {
        Meeting m = new Meeting();
        m.setId(UUID.randomUUID().toString());
        m.setTitle(in.title().trim());
        m.setTranscript(in.transcript());
        m.setCreatedBy(user.getId());
        m.setCreatedAt(LocalDateTime.now());
        meetingMapper.insert(m);
        return m;
    }

    public List<Meeting> listMeetings() {
        return meetingMapper.selectList(new QueryWrapper<Meeting>().orderByDesc("created_at").orderByDesc("id"));
    }

    public Meeting getMeeting(String meetingId) {
        Meeting m = meetingMapper.selectById(meetingId);
        if (m == null) throw ApiException.notFound("会议不存在");
        return m;
    }

    // ---------- suggestions ----------

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 生成建议(幂等):同 client_request_id 返回既有建议;不同 payload 409 */
    @Transactional
    public SuggestionOutHolder stage(MeetingDtos.SuggestionIn in, User user) {
        Meeting meeting = meetingMapper.selectById(in.meetingId());
        if (meeting == null) throw ApiException.notFound("会议不存在");
        if (meeting.getTranscript() == null || !meeting.getTranscript().contains(in.evidence())) {
            throw ApiException.unprocessable("证据必须是会议原文中的连续片段");
        }
        String fingerprint = sha256(canonicalJson(in));

        MeetingSuggestionRecord existing = recordMapper.selectOne(new QueryWrapper<MeetingSuggestionRecord>()
                .eq("meeting_id", in.meetingId())
                .eq("submitted_by", user.getId())
                .eq("client_request_id", in.clientRequestId()));
        if (existing != null) {
            if (!existing.getRequestHash().equals(fingerprint)) {
                throw ApiException.conflict("同一 client_request_id 不能用于不同建议");
            }
            Suggestion s = suggestionMapper.selectById(existing.getSuggestionId());
            return new SuggestionOutHolder(s, existing);
        }

        Suggestion suggestion = new Suggestion();
        suggestion.setId("S" + UUID.randomUUID().toString().replace("-", "").substring(0, 9));
        suggestion.setAgent("manual".equals(in.origin()) ? "手动录入" : "会议 Agent");
        suggestion.setKind("meeting");
        suggestion.setEvidence(in.evidence());
        suggestion.setAffected("新需求 → 需求池");
        suggestion.setNote(in.note());
        suggestion.setChangeJson(writeJson(in.changes()));
        suggestion.setStatus("pending");
        suggestion.setCreatedAt(LocalDateTime.now());
        suggestionMapper.insert(suggestion);

        MeetingSuggestionRecord record = new MeetingSuggestionRecord();
        record.setSuggestionId(suggestion.getId());
        record.setMeetingId(in.meetingId());
        record.setSubmittedBy(user.getId());
        record.setClientRequestId(in.clientRequestId());
        record.setRequestHash(fingerprint);
        record.setOrigin(in.origin());
        record.setExecutionStatus("not_started");
        record.setReason("");
        recordMapper.insert(record);
        return new SuggestionOutHolder(suggestion, record);
    }

    /** 幂等提交重试(唯一键冲突 → 重新查询返回既有) */
    public SuggestionOutHolder submitSuggestionIdempotent(MeetingDtos.SuggestionIn in, User user) {
        for (int i = 0; i < 3; i++) {
            try {
                return stage(in, user);
            } catch (DuplicateKeyException e) {
                // 唯一键冲突:重新走幂等查询路径
                MeetingSuggestionRecord existing = recordMapper.selectOne(new QueryWrapper<MeetingSuggestionRecord>()
                        .eq("meeting_id", in.meetingId())
                        .eq("submitted_by", user.getId())
                        .eq("client_request_id", in.clientRequestId()));
                if (existing != null) {
                    Suggestion s = suggestionMapper.selectById(existing.getSuggestionId());
                    return new SuggestionOutHolder(s, existing);
                }
            }
        }
        throw ApiException.conflict("建议编号或提交冲突，请重试");
    }

    public List<MeetingSuggestionRecord> listRecords(String meetingId) {
        QueryWrapper<MeetingSuggestionRecord> qw = new QueryWrapper<>();
        if (meetingId != null) qw.eq("meeting_id", meetingId);
        return recordMapper.selectList(qw.orderByDesc("id"));
    }

    public MeetingSuggestionRecord getRecordBySuggestionId(String suggestionId) {
        MeetingSuggestionRecord r = recordMapper.selectOne(
                new QueryWrapper<MeetingSuggestionRecord>().eq("suggestion_id", suggestionId));
        if (r == null) throw ApiException.notFound("会议建议不存在");
        return r;
    }

    /** 审核:原子认领 pending→目标;approve/modify 落 pool + payload
     *  READ_COMMITTED:认领失败后同事务内重读须见到并发胜者已提交的最新状态
     *  (对齐 FastAPI 版 rollback 后新事务重读的语义;RR 快照会把同结论并发误判为 409) */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SuggestionOutHolder review(String suggestionId, MeetingDtos.ReviewIn in, User user) {
        // 决策⇔载荷一致性(对齐 FastAPI ReviewIn model_validator):modify_and_approve 必须带 changes,其余不得带
        boolean isModify = "modify_and_approve".equals(in.decision());
        if (isModify != (in.changes() != null)) {
            throw ApiException.unprocessable("仅修改后采纳必须提供 changes");
        }
        Suggestion suggestion = suggestionMapper.selectById(suggestionId);
        MeetingSuggestionRecord record = getRecordBySuggestionId(suggestionId);
        if (suggestion == null) throw ApiException.notFound("会议建议不存在");

        String targetStatus = "reject".equals(in.decision()) ? "rejected" : "approved";

        if (!"pending".equals(suggestion.getStatus())) {
            // 已审核:同结论幂等返回;异结论 409
            return reviewedOutcome(suggestion, record, targetStatus, in);
        }

        // 原子认领:UPDATE suggestions SET status=? WHERE id=? AND status='pending'
        // 受影响 0 行 = 已被并发审核,重读最新状态后走同一套已审核裁决(幂等返回/409)
        int claimed = suggestionMapper.update(null, new UpdateWrapper<Suggestion>()
                .set("status", targetStatus)
                .eq("id", suggestionId)
                .eq("status", "pending"));
        if (claimed == 0) {
            suggestion = suggestionMapper.selectById(suggestionId);
            record = getRecordBySuggestionId(suggestionId);
            return reviewedOutcome(suggestion, record, targetStatus, in);
        }
        suggestion.setStatus(targetStatus);

        record.setReviewedBy(user.getId());
        record.setReviewedAt(LocalDateTime.now());
        record.setReason(in.reason());

        if (!"reject".equals(in.decision())) {
            String poolId = String.format("A%09d", record.getId());
            if (poolId.length() > 10) {
                throw ApiException.conflict("需求池编号容量已满");
            }
            Map<String, Object> changes = in.changes() != null
                    ? readJsonMap(writeJson(in.changes())) : readJsonMap(suggestion.getChangeJson());

            MeetingApprovalPayload payload = new MeetingApprovalPayload();
            payload.setSuggestionId(suggestionId);
            payload.setChangesJson(writeJson(changes));
            payloadMapper.insert(payload);

            PoolItem item = new PoolItem();
            item.setId(poolId);
            item.setTitle((String) changes.get("title"));
            item.setDescription((String) changes.getOrDefault("description", ""));
            item.setSource("会议 " + record.getMeetingId() + " / 建议 " + suggestionId);
            item.setPriority((String) changes.getOrDefault("priority", "Could"));
            item.setCreatedAt(LocalDateTime.now());
            try {
                poolItemMapper.insert(item);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("执行冲突，未写入任何变更；请检查需求池后重试");
            }
            record.setPoolItemId(poolId);
            record.setExecutionStatus("succeeded");
        } else {
            record.setExecutionStatus("not_needed");
        }
        recordMapper.updateById(record);
        return new SuggestionOutHolder(suggestion, record);
    }

    /** 已审核裁决(预检与并发认领失败共用):同结论幂等返回;异结论 409;approved 且 changes 不同 → 409 */
    private SuggestionOutHolder reviewedOutcome(Suggestion suggestion, MeetingSuggestionRecord record,
                                                String targetStatus, MeetingDtos.ReviewIn in) {
        if (!targetStatus.equals(suggestion.getStatus())) {
            throw ApiException.conflict("建议已审核，不能改变审核结论");
        }
        MeetingApprovalPayload prev = payloadMapper.selectById(suggestion.getId());
        Map<String, Object> applied = prev != null ? readJsonMap(prev.getChangesJson()) : readJsonMap(suggestion.getChangeJson());
        Map<String, Object> requested = in.changes() != null ? readJsonMap(writeJson(in.changes())) : readJsonMap(suggestion.getChangeJson());
        if ("approved".equals(targetStatus) && !applied.equals(requested)) {
            throw ApiException.conflict("建议已采纳，不能覆盖已执行的内容");
        }
        return new SuggestionOutHolder(suggestion, record);
    }

    // ---------- 组装输出 ----------

    private String canonicalJson(MeetingDtos.SuggestionIn in) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("meeting_id", in.meetingId());
        map.put("client_request_id", in.clientRequestId());
        map.put("action", in.action());
        map.put("origin", in.origin());
        map.put("evidence", in.evidence());
        map.put("note", in.note());
        map.put("changes", toMap(in.changes()));
        return writeJson(map);
    }

    private Map<String, Object> toMap(MeetingDtos.PoolChanges c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", c.title());
        map.put("description", c.description());
        map.put("priority", c.priority());
        return map;
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 建议 + 记录的持有器(避免 tuple) */
    public record SuggestionOutHolder(Suggestion suggestion, MeetingSuggestionRecord record) {
    }
}
