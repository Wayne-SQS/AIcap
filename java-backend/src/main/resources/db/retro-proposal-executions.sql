CREATE TABLE IF NOT EXISTS meeting_retro_proposal_executions (
    analysis_id varchar(36) NOT NULL,
    proposal_index int NOT NULL,
    proposal_id varchar(80) NOT NULL,
    action_item_id varchar(36) NOT NULL,
    action_item_log_id int NOT NULL,
    executed_by int NOT NULL,
    executed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (analysis_id,proposal_index),
    FOREIGN KEY (analysis_id,proposal_index) REFERENCES meeting_retro_proposal_reviews(analysis_id,proposal_index),
    FOREIGN KEY (action_item_id) REFERENCES action_items(id),
    FOREIGN KEY (action_item_log_id) REFERENCES action_item_logs(id),
    FOREIGN KEY (executed_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
