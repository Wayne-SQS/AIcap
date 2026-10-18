-- 存量库升级:业务表切换到 internal-key 结构(老库专用,新库无需执行)
-- ============================================================================
-- 背景:
--   db/schema.sql 里 stories / tasks / milestones 已从「业务码做主键」切换为
--   「internal_id 自增主键 + code 业务码 + UNIQUE(project_id, code)」。
--   但 schema.sql 全是 CREATE TABLE IF NOT EXISTS —— 已存在的老表不会重跑;
--   SchemaUpgrader 只负责补列/补 project_id,不做主键重构。
--   于是老库启动后所有任务/故事/里程碑查询都会报 Unknown column 'internal_id'。
--
-- 本脚本做的事:
--   1) 删掉新 schema 已不存在的三条外键(新结构把它们降级为普通 KEY)
--   2) 三张表: id -> code, 新增 internal_id 自增主键, 补齐 *_legacy 等新列
--   3) activity_records / difficulty_assessments 补 task_internal_id / task_code_snapshot
--   4) 回填 internal_id / code 快照,让历史行与新结构对齐
--
-- 幂等:每一步都先查 information_schema,已迁移则跳过,可重复执行。
-- 执行方式(生产库只需一次):
--   docker exec -i aiguanli-mysql mysql -uaiguanli -paiguanli-2026 AIcap < migrate_legacy_to_internal_key.sql
-- 前置:务必先备份(mysqldump)。
-- ============================================================================

SET @db := DATABASE();

-- ---------- 1) 删除新 schema 已移除的外键 ----------
-- activity_records.task_id -> tasks.id
SET @c := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
           WHERE CONSTRAINT_SCHEMA=@db AND TABLE_NAME='activity_records' AND CONSTRAINT_NAME='activity_records_ibfk_2');
SET @s := IF(@c > 0, 'ALTER TABLE `activity_records` DROP FOREIGN KEY `activity_records_ibfk_2`',
                     'SELECT "skip: activity_records_ibfk_2" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- difficulty_assessments.task_id -> tasks.id
SET @c := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
           WHERE CONSTRAINT_SCHEMA=@db AND TABLE_NAME='difficulty_assessments' AND CONSTRAINT_NAME='difficulty_assessments_ibfk_1');
SET @s := IF(@c > 0, 'ALTER TABLE `difficulty_assessments` DROP FOREIGN KEY `difficulty_assessments_ibfk_1`',
                     'SELECT "skip: difficulty_assessments_ibfk_1" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- tasks.kanban_card_id -> stories.id(必须在 stories 改主键之前删掉)
SET @c := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
           WHERE CONSTRAINT_SCHEMA=@db AND TABLE_NAME='tasks' AND CONSTRAINT_NAME='fk_task_kanban_card');
SET @s := IF(@c > 0, 'ALTER TABLE `tasks` DROP FOREIGN KEY `fk_task_kanban_card`',
                     'SELECT "skip: fk_task_kanban_card" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 2) stories ----------
SET @has_id := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='stories' AND COLUMN_NAME='id');
SET @has_int := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='stories' AND COLUMN_NAME='internal_id');
SET @s := IF(@has_id > 0 AND @has_int = 0,
  'ALTER TABLE `stories` DROP PRIMARY KEY, '
  'CHANGE COLUMN `id` `code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL, '
  'ADD COLUMN `internal_id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST, '
  'ADD KEY `ix_stories_epic` (`epic_id`)',
  'SELECT "stories already migrated" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 3) milestones ----------
SET @has_id := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='milestones' AND COLUMN_NAME='id');
SET @has_int := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='milestones' AND COLUMN_NAME='internal_id');
SET @s := IF(@has_id > 0 AND @has_int = 0,
  'ALTER TABLE `milestones` DROP PRIMARY KEY, '
  'CHANGE COLUMN `id` `code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL, '
  'ADD COLUMN `internal_id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST, '
  'ADD COLUMN `related_task_ids_legacy` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL',
  'SELECT "milestones already migrated" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 4) tasks ----------
-- 注:老表的 priority 列新 schema 已不再使用,保留(多一列不影响代码)
SET @has_id := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='tasks' AND COLUMN_NAME='id');
SET @has_int := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='tasks' AND COLUMN_NAME='internal_id');
SET @s := IF(@has_id > 0 AND @has_int = 0,
  'ALTER TABLE `tasks` DROP PRIMARY KEY, '
  'CHANGE COLUMN `id` `code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL, '
  'ADD COLUMN `internal_id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST, '
  'ADD COLUMN `story_internal_id` bigint DEFAULT NULL, '
  'ADD COLUMN `story_ref_legacy` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL, '
  'ADD COLUMN `depends_on_legacy` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL, '
  'ADD COLUMN `kanban_card_id_legacy` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL, '
  'ADD KEY `ix_tasks_story` (`story_internal_id`)',
  'SELECT "tasks already migrated" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 5) 引用方补 internal 列 ----------
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='activity_records' AND COLUMN_NAME='task_internal_id');
SET @s := IF(@has = 0,
  'ALTER TABLE `activity_records` ADD COLUMN `task_internal_id` bigint DEFAULT NULL, '
  'ADD COLUMN `task_code_snapshot` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL',
  'SELECT "activity_records internal columns exist" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='difficulty_assessments' AND COLUMN_NAME='task_internal_id');
SET @s := IF(@has = 0,
  'ALTER TABLE `difficulty_assessments` ADD COLUMN `task_internal_id` bigint DEFAULT NULL, '
  'ADD COLUMN `task_code_snapshot` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL',
  'SELECT "difficulty_assessments internal columns exist" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 6) 回填:让历史行拿到 internal_id 与 code 快照 ----------
UPDATE `activity_records` a
  JOIN `tasks` t ON t.`code` = a.`task_id` AND t.`project_id` = a.`project_id`
  SET a.`task_internal_id` = t.`internal_id`, a.`task_code_snapshot` = t.`code`
  WHERE a.`task_id` IS NOT NULL AND a.`task_internal_id` IS NULL;

UPDATE `difficulty_assessments` d
  JOIN `tasks` t ON t.`code` = d.`task_id` AND t.`project_id` = d.`project_id`
  SET d.`task_internal_id` = t.`internal_id`, d.`task_code_snapshot` = t.`code`
  WHERE d.`task_internal_id` IS NULL;

-- ---------- 7) 历史数据归入正式项目 ----------
-- SchemaUpgrader 给存量行统一打了 'P001',但 P001 是已被物理删除的历史项目,
-- 库里实际只有 P002(爱管理)。不归口的话,所有存量数据在多项目视图下都不可见
-- (/api/tasks?projectId=P002 返回空数组,而数据其实还在)。
SET @p001 := (SELECT COUNT(*) FROM `projects` WHERE id='P001');
SET @p002 := (SELECT COUNT(*) FROM `projects` WHERE id='P002');
SET @need := IF(@p001 = 0 AND @p002 > 0, 1, 0);

SET @s := IF(@need = 1, 'UPDATE `tasks` SET project_id=''P002'' WHERE project_id=''P001''', 'SELECT "skip rehome" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @s := IF(@need = 1, 'UPDATE `stories` SET project_id=''P002'' WHERE project_id=''P001''', 'SELECT "skip rehome" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @s := IF(@need = 1, 'UPDATE `milestones` SET project_id=''P002'' WHERE project_id=''P001''', 'SELECT "skip rehome" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @s := IF(@need = 1, 'UPDATE `activity_records` SET project_id=''P002'' WHERE project_id=''P001''', 'SELECT "skip rehome" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @s := IF(@need = 1, 'UPDATE `difficulty_assessments` SET project_id=''P002'' WHERE project_id=''P001''', 'SELECT "skip rehome" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @s := IF(@need = 1, 'UPDATE `profile_snapshots` SET project_id=''P002'' WHERE project_id=''P001''', 'SELECT "skip rehome" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @s := IF(@need = 1, 'UPDATE `profile_agent_runs` SET project_id=''P002'' WHERE project_id=''P001''', 'SELECT "skip rehome" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @s := IF(@need = 1, 'UPDATE `suggestions` SET project_id=''P002'' WHERE project_id=''P001''', 'SELECT "skip rehome" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @s := IF(@need = 1, 'UPDATE `pool_items` SET project_id=''P002'' WHERE project_id=''P001''', 'SELECT "skip rehome" AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 8) 自检 ----------
SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='tasks'   AND COLUMN_NAME='internal_id') AS tasks_ok,
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='stories' AND COLUMN_NAME='internal_id') AS stories_ok,
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='milestones' AND COLUMN_NAME='internal_id') AS milestones_ok,
  (SELECT COUNT(*) FROM `tasks`) AS tasks_rows,
  (SELECT COUNT(*) FROM `stories`) AS stories_rows,
  (SELECT COUNT(*) FROM `activity_records`) AS activity_rows;
