-- Run only on the isolated aicap_java_test database. All generated rows are rolled back.
SET @benchmark_rows = COALESCE(@benchmark_rows, 3000);
SET SESSION cte_max_recursion_depth = 12000;
SET @benchmark_meeting_id = UUID();
START TRANSACTION;

INSERT INTO meetings (id, title, transcript, created_by, created_at)
VALUES (@benchmark_meeting_id, 'review queue benchmark', 'synthetic', 1, NOW());

INSERT INTO meeting_status_analyses
    (id, meeting_id, submitted_by, client_request_id, status, transcript,
     result_json, snapshots_json, created_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @benchmark_rows
)
SELECT UUID(), @benchmark_meeting_id, 1, CONCAT('benchmark-', n), 'completed',
       'synthetic',
       CONCAT('{"proposed_actions":[{"proposal_id":"p-', n,
              '-a"},{"proposal_id":"p-', n, '-b"}]}'),
       '[]', TIMESTAMPADD(SECOND, n, '2025-01-01 00:00:00')
FROM seq;

SELECT COUNT(*) AS synthetic_analyses
FROM meeting_status_analyses WHERE meeting_id = @benchmark_meeting_id;

EXPLAIN ANALYZE
SELECT q.*, CAST(UNIX_TIMESTAMP(q.created_at) * 1000000 AS SIGNED) AS sort_time
FROM (
    SELECT 'daily' AS source, a.meeting_id, m.title AS meeting_title,
           a.id AS analysis_id, jt.proposal_id, jt.ordinal - 1 AS proposal_index,
           a.created_at,
           CASE WHEN r.decision IS NULL THEN 'pending'
                WHEN r.decision = 'reject' THEN 'rejected' ELSE 'approved' END AS status,
           COALESCE(r.execution_status, 'not_started') AS execution_status
    FROM meeting_status_analyses a
    JOIN meetings m ON m.id = a.meeting_id
    JOIN JSON_TABLE(a.result_json, '$.proposed_actions[*]'
        COLUMNS (ordinal FOR ORDINALITY,
                 proposal_id VARCHAR(80) PATH '$.proposal_id')) jt
    LEFT JOIN meeting_status_proposal_reviews r
        ON r.analysis_id = a.id AND r.proposal_index = jt.ordinal - 1
) q
WHERE q.status = 'pending'
ORDER BY sort_time DESC, BINARY q.source DESC, BINARY q.analysis_id DESC,
         q.proposal_index DESC
LIMIT 51;

EXPLAIN ANALYZE
SELECT COALESCE(SUM(CASE WHEN q.status = 'pending' THEN 1 ELSE 0 END), 0) AS pending_count,
       COALESCE(SUM(CASE WHEN q.status = 'approved'
                         AND q.execution_status = 'not_started'
                    THEN 1 ELSE 0 END), 0) AS execution_count
FROM (
    SELECT 'daily' AS source, a.meeting_id, m.title AS meeting_title,
           a.id AS analysis_id, jt.proposal_id, jt.ordinal - 1 AS proposal_index,
           a.created_at,
           CASE WHEN r.decision IS NULL THEN 'pending'
                WHEN r.decision = 'reject' THEN 'rejected' ELSE 'approved' END AS status,
           COALESCE(r.execution_status, 'not_started') AS execution_status
    FROM meeting_status_analyses a
    JOIN meetings m ON m.id = a.meeting_id
    JOIN JSON_TABLE(a.result_json, '$.proposed_actions[*]'
        COLUMNS (ordinal FOR ORDINALITY,
                 proposal_id VARCHAR(80) PATH '$.proposal_id')) jt
    LEFT JOIN meeting_status_proposal_reviews r
        ON r.analysis_id = a.id AND r.proposal_index = jt.ordinal - 1
) q;

ROLLBACK;
