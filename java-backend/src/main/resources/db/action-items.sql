CREATE TABLE IF NOT EXISTS action_items (
    id varchar(36) NOT NULL PRIMARY KEY,
    meeting_id varchar(36) NOT NULL,
    analysis_id varchar(36) NOT NULL,
    proposal_index int NOT NULL,
    title varchar(200) NOT NULL,
    description text NOT NULL,
    owner_id int NULL,
    deadline_text varchar(200) NULL,
    status varchar(20) NOT NULL DEFAULT 'open',
    created_by int NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (analysis_id,proposal_index),
    FOREIGN KEY (analysis_id,proposal_index) REFERENCES meeting_retro_proposal_reviews(analysis_id,proposal_index),
    FOREIGN KEY (meeting_id) REFERENCES meetings(id),
    FOREIGN KEY (owner_id) REFERENCES users(id),
    FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS action_item_logs (
    id int NOT NULL AUTO_INCREMENT PRIMARY KEY,
    action_item_id varchar(36) NOT NULL,
    log_type varchar(20) NOT NULL,
    approved_proposal_json longtext NOT NULL,
    user_id int NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (action_item_id) REFERENCES action_items(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
