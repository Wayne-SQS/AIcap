CREATE TABLE IF NOT EXISTS meeting_assignment_analyses (
  id varchar(36) NOT NULL PRIMARY KEY,
  meeting_id varchar(36) NOT NULL,
  submitted_by int NOT NULL,
  client_request_id varchar(80) NOT NULL,
  input_json longtext NOT NULL,
  result_json longtext NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  review_json longtext NULL,
  CONSTRAINT uq_assignment_request UNIQUE (meeting_id, submitted_by, client_request_id),
  CONSTRAINT fk_assignment_meeting FOREIGN KEY (meeting_id) REFERENCES meetings(id),
  CONSTRAINT fk_assignment_user FOREIGN KEY (submitted_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
