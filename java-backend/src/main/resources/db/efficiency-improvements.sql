CREATE TABLE IF NOT EXISTS efficiency_improvements (
  project_id varchar(20) NOT NULL,
  source_task_code varchar(20) NOT NULL,
  issue_kind varchar(20) NOT NULL,
  improvement_task_code varchar(20) NOT NULL,
  reviewed_by int NOT NULL,
  reviewed_at datetime NOT NULL,
  PRIMARY KEY (project_id,source_task_code,issue_kind),
  UNIQUE KEY uq_efficiency_improvement_task (project_id,improvement_task_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
