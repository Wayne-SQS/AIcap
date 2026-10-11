-- Independent of legacy pool.create runs. Stores immutable planning-analysis submissions.
CREATE TABLE IF NOT EXISTS meeting_planning_analyses (
  id varchar(36) NOT NULL PRIMARY KEY,
  meeting_id varchar(36) NOT NULL,
  submitted_by int NOT NULL,
  client_request_id varchar(80) NOT NULL,
  status varchar(20) NOT NULL,
  transcript longtext NOT NULL,
  result_json longtext NOT NULL,
  snapshots_json longtext NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_planning_analysis_request UNIQUE (meeting_id, submitted_by, client_request_id),
  CONSTRAINT fk_planning_analysis_meeting FOREIGN KEY (meeting_id) REFERENCES meetings(id),
  CONSTRAINT fk_planning_analysis_user FOREIGN KEY (submitted_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

