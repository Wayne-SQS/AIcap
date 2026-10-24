-- US22:人工提交的测试集(用例清单),用于检查边界用例覆盖缺口。
-- 只保存用例名称与可选边界类别声明,不保存也不执行测试代码;分析结果可回溯到具体条数。
CREATE TABLE IF NOT EXISTS quality_test_sets (
  id bigint NOT NULL AUTO_INCREMENT,
  project_id varchar(20) NOT NULL,
  code varchar(20) NOT NULL,
  name varchar(200) NOT NULL,
  source varchar(20) NOT NULL,
  cases_json mediumtext NOT NULL,
  created_by int NOT NULL,
  created_at datetime NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_quality_test_set (project_id,code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
