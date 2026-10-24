-- US23: analysis/suggestions only. No automatic task changes.
CREATE TABLE IF NOT EXISTS efficiency_analysis_runs (
 id varchar(36) NOT NULL PRIMARY KEY,
 project_id varchar(20) NOT NULL,
 requested_by int NOT NULL,
 status varchar(30) NOT NULL,
 result_json longtext NOT NULL,
 created_at datetime NOT NULL,
 updated_at datetime NOT NULL,
 KEY ix_efficiency_runs_project (project_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS efficiency_proposals (
 id varchar(36) NOT NULL PRIMARY KEY,
 project_id varchar(20) NOT NULL,
 analysis_id varchar(36) NOT NULL,
 recommendation_id varchar(40) NOT NULL,
 proposal_json longtext NOT NULL,
 status varchar(30) NOT NULL,
 submitted_by int NOT NULL,
 created_at datetime NOT NULL,
 reviewed_by int DEFAULT NULL,
 reviewed_at datetime DEFAULT NULL,
 reason varchar(1000) NOT NULL DEFAULT '',
 UNIQUE KEY uq_efficiency_recommendation (project_id,analysis_id,recommendation_id),
 KEY ix_efficiency_proposals_project (project_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
