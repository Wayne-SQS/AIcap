-- GitHub 接入(US34)迁移脚本:给已存在的 activity_records 表补幂等去重列。
-- 测试库(aicap_java_test)由 schema.sql 自动重建,无需执行本脚本;
-- 生产库(AIcap)执行一次即可(幂等:先查列是否存在,存在则跳过)。
-- 执行方式: mysql -u aiguanli -p AIcap < github_migration.sql
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_records' AND COLUMN_NAME = 'github_event_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `activity_records`
       ADD COLUMN `github_event_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL
         COMMENT ''GitHub 事件唯一ID(commit sha/PR号/review id/issue号),同步幂等去重'' AFTER `happened_at`,
       ADD UNIQUE KEY `ix_activity_records_github_event` (`github_event_id`)',
    'SELECT ''github_event_id column already exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
