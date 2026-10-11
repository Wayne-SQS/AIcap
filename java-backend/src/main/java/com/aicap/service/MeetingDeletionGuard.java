package com.aicap.service;

import com.aicap.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Preserve the existing foreign-key retention boundary before any legacy cleanup. */
@Service
@RequiredArgsConstructor
public class MeetingDeletionGuard {
    private final JdbcTemplate jdbc;
    private static final List<String> TYPES = List.of("status", "planning", "review", "retro", "refinement", "assignment");

    @Transactional(propagation = Propagation.MANDATORY)
    public void check(String meetingId) {
        // Same lock order as analysis saves. A save either commits first and blocks
        // deletion, or observes a deleted meeting after the delete transaction ends.
        if (jdbc.queryForList("SELECT id FROM meetings WHERE id=? FOR UPDATE", meetingId).isEmpty())
            throw ApiException.notFound("会议不存在");
        if (!jdbc.queryForList("SELECT id FROM meeting_transcript_versions WHERE meeting_id=? OR analysis_meeting_id=? LIMIT 1 FOR UPDATE", meetingId, meetingId).isEmpty())
            throw ApiException.conflict("此会议关联已保存转写版本，需保留音频来源和人工核对记录，不能删除。");
        for (String type : TYPES) {
            // Locking read uses current committed rows even under MySQL repeatable read.
            if (!jdbc.queryForList("SELECT id FROM meeting_" + type + "_analyses WHERE meeting_id=? LIMIT 1 FOR UPDATE", meetingId).isEmpty())
                throw ApiException.conflict("此会议已有会议分析或分配建议记录，需保留原文及审核执行审计，不能删除。可新建会议继续整理。");
        }
    }
}
