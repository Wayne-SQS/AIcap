-- Each immutable analysis array position can receive one final review.
-- The numeric position avoids database collation conflating distinct proposal IDs.
CREATE TABLE IF NOT EXISTS meeting_status_proposal_reviews (
  analysis_id varchar(36) NOT NULL,
  proposal_index int NOT NULL,
  proposal_id varchar(80) NOT NULL,
  decision varchar(30) NOT NULL,
  reason varchar(1000) NOT NULL,
  reviewed_by int NOT NULL,
  reviewed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  original_json longtext NOT NULL,
  approved_json longtext NULL,
  execution_status varchar(20) NOT NULL,
  PRIMARY KEY (analysis_id,proposal_index),
  CONSTRAINT fk_status_review_analysis FOREIGN KEY (analysis_id) REFERENCES meeting_status_analyses(id),
  CONSTRAINT fk_status_review_user FOREIGN KEY (reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
