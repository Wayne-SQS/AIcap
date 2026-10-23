CREATE TABLE IF NOT EXISTS meeting_assignment_save_intents (
  meeting_id varchar(36) NOT NULL,
  user_id int NOT NULL,
  client_request_id varchar(80) NOT NULL,
  input_json longtext NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (meeting_id, user_id),
  CONSTRAINT fk_assignment_intent_meeting FOREIGN KEY (meeting_id) REFERENCES meetings(id),
  CONSTRAINT fk_assignment_intent_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
