CREATE TABLE IF NOT EXISTS meeting_transcript_versions (
  id varchar(36) NOT NULL PRIMARY KEY,
  meeting_id varchar(36) NOT NULL,
  audio_id varchar(36) NOT NULL,
  submitted_by int NOT NULL,
  client_request_id varchar(80) NOT NULL,
  draft_json longtext NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  confirmation_json longtext NULL,
  analysis_meeting_id varchar(36) NULL,
  CONSTRAINT uq_transcript_request UNIQUE (meeting_id, submitted_by, client_request_id),
  CONSTRAINT fk_transcript_meeting FOREIGN KEY (meeting_id) REFERENCES meetings(id),
  CONSTRAINT fk_transcript_audio FOREIGN KEY (audio_id) REFERENCES meeting_audio(id),
  CONSTRAINT fk_transcript_user FOREIGN KEY (submitted_by) REFERENCES users(id),
  CONSTRAINT fk_transcript_analysis FOREIGN KEY (analysis_meeting_id) REFERENCES meetings(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
