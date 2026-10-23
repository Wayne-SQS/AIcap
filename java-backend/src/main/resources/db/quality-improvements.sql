CREATE TABLE IF NOT EXISTS quality_improvements (
  project_id varchar(20) NOT NULL,
  source_code varchar(20) NOT NULL,
  finding_kind varchar(30) NOT NULL,
  improvement_task_code varchar(20) NOT NULL,
  reviewed_by int NOT NULL,
  reviewed_at datetime NOT NULL,
  PRIMARY KEY (project_id,source_code,finding_kind),
  UNIQUE KEY uq_quality_improvement_task (project_id,improvement_task_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
