-- US22:代码静态诊断与模型语义评审的结论留痕。
-- 内存缓存只用于页面加速,重启即失;落库后结论可审计、可转成质量缺口并回流改进任务。
-- payload_json 保存完整结论(含证据/问题/建议),列上的 total/grade/score 只做检索用。
CREATE TABLE IF NOT EXISTS quality_code_snapshots (
  id bigint NOT NULL AUTO_INCREMENT,
  project_id varchar(20) NOT NULL,
  repo varchar(200) NOT NULL,
  branch varchar(100) NOT NULL,
  total int NOT NULL,
  grade varchar(10) NOT NULL,
  risk_level varchar(20) NOT NULL,
  payload_json mediumtext NOT NULL,
  created_at datetime NOT NULL,
  PRIMARY KEY (id),
  KEY idx_quality_code_project (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS quality_semantic_reviews (
  id bigint NOT NULL AUTO_INCREMENT,
  project_id varchar(20) NOT NULL,
  scope varchar(20) NOT NULL,
  source varchar(300) NULL,
  score int NULL,
  grade varchar(10) NULL,
  reader_can_act tinyint NOT NULL DEFAULT 0,
  payload_json mediumtext NOT NULL,
  content_sha varchar(64) NULL,
  created_at datetime NOT NULL,
  PRIMARY KEY (id),
  KEY idx_quality_semantic_project (project_id, scope, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
