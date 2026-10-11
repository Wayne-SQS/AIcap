CREATE TABLE IF NOT EXISTS meeting_review_proposal_executions (
    analysis_id varchar(36) NOT NULL,
    proposal_index int NOT NULL,
    proposal_id varchar(80) NOT NULL,
    story_id varchar(10) NOT NULL,
    previous_status int NOT NULL,
    new_status int NOT NULL,
    story_log_id int NOT NULL,
    executed_by int NOT NULL,
    executed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (analysis_id, proposal_index),
    FOREIGN KEY (analysis_id, proposal_index) REFERENCES meeting_review_proposal_reviews(analysis_id, proposal_index),
    FOREIGN KEY (executed_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
