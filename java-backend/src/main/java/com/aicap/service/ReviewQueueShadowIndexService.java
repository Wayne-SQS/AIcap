package com.aicap.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Manual shadow-index backfill and validation. Live queue reads still use source tables. */
@Service
@RequiredArgsConstructor
public class ReviewQueueShadowIndexService {
    private final JdbcTemplate jdbc;
    private final ReviewQueueIndexService sources;

    public record Counts(long pending, long execution, long total) {}

    /** Idempotently inserts missing references. Existing rows are never overwritten by backfill. */
    @Transactional
    public long backfillMissing() {
        long inserted = 0;
        for (String sourceSql : sources.sql().values()) {
            inserted += jdbc.update("INSERT IGNORE INTO review_queue_proposals " +
                    "(source,analysis_id,proposal_index,meeting_id,proposal_id,created_at,status,execution_status) " +
                    "SELECT q.source,q.analysis_id,q.proposal_index,q.meeting_id,q.proposal_id,q.created_at," +
                    "q.status,q.execution_status FROM (" + sourceSql + ") q");
        }
        return inserted;
    }

    @Transactional(readOnly = true)
    public Counts counts() {
        Map<String, Object> row = jdbc.queryForMap("SELECT " +
                "COUNT(*) AS total_count," +
                "COALESCE(SUM(status='pending'),0) AS pending_count," +
                "COALESCE(SUM(status='approved' AND execution_status='not_started'),0) AS execution_count " +
                "FROM review_queue_proposals");
        return new Counts(((Number) row.get("pending_count")).longValue(),
                ((Number) row.get("execution_count")).longValue(),
                ((Number) row.get("total_count")).longValue());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingFirst(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        return jdbc.queryForList("SELECT source,analysis_id,proposal_index FROM review_queue_proposals " +
                "WHERE status='pending' ORDER BY created_at DESC,source DESC,analysis_id DESC,proposal_index DESC " +
                "LIMIT ?", limit);
    }
}
