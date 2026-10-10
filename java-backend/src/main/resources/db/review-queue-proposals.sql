-- Shadow index for proposal-level queue reads. Source review/execution tables remain authoritative.
-- Reads are not switched to this table until every write path and backfill are verified.
CREATE TABLE IF NOT EXISTS review_queue_proposals (
  source varchar(20) COLLATE utf8mb4_bin NOT NULL,
  analysis_id varchar(36) COLLATE utf8mb4_bin NOT NULL,
  proposal_index int NOT NULL,
  meeting_id varchar(36) NOT NULL,
  proposal_id varchar(80) NOT NULL,
  created_at datetime(6) NOT NULL,
  status varchar(20) NOT NULL,
  execution_status varchar(20) NOT NULL,
  PRIMARY KEY (source,analysis_id,proposal_index),
  KEY idx_review_queue_status_time (status,created_at DESC,source DESC,analysis_id DESC,proposal_index DESC),
  KEY idx_review_queue_execution (status,execution_status),
  KEY idx_review_queue_meeting (meeting_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
