-- 补齐旧库的故事审计字段。只增加可空列，保留所有历史记录，可重复执行。
SET @db = DATABASE();
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=@db AND TABLE_NAME='story_logs' AND COLUMN_NAME='story_internal_id') = 0,
  'ALTER TABLE story_logs ADD COLUMN story_internal_id bigint DEFAULT NULL',
  'SELECT 1');
PREPARE migration FROM @sql;
EXECUTE migration;
DEALLOCATE PREPARE migration;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=@db AND TABLE_NAME='story_logs' AND COLUMN_NAME='story_code_snapshot') = 0,
  'ALTER TABLE story_logs ADD COLUMN story_code_snapshot varchar(20) DEFAULT NULL',
  'SELECT 1');
PREPARE migration FROM @sql;
EXECUTE migration;
DEALLOCATE PREPARE migration;
