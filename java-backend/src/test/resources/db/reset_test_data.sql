-- ============================================================
-- 契约测试专用清理脚本(仅测试上下文加载,不影响 src/main 与运行实例):
-- 每次 Spring 测试上下文启动时,spring.sql.init 先执行 classpath:db/schema.sql
-- (CREATE TABLE IF NOT EXISTS,含 tasks.depends_on / tasks.progress / tasks.blocked /
--  users.capacity_hours 等新列),随后执行本脚本,把 aicap_java_test 全部表清空并
-- 重置自增计数器。随后 DataSeeder(ApplicationRunner)从空库播种,
-- 保证确定性基线:
--   users   5 行(id=1..5:李锐铭 admin 60h / 高思晗 owner 48h / 孙秋实 member 60h /
--            罗子涵 member 54h / 成员5 viewer 60h;登录支持真名 ↔「成员N」双向别名)
--   stories 37 行 US01..US37(sprint 1..4 / activity 1..5 / status 0..2 / owner_id 1..4)
--   tasks   16 行 T01..T16(含 depends_on / progress / blocked;sprints 由 week_start..week_end
--            派生不落库;12 条开发任务挂 USxx 看板卡,管理任务 T01/T02/T15/T16 挂空)
--   pool    空(需求池基线为空)
-- 清空顺序遵循外键依赖:tasks.kanban_card_id → stories、story_logs、pool_items 先清,
-- 最后清 users(否则 stories.owner_id / meetings.created_by 外键失败)。
-- 修复场景:历史 DELETE 残留使 users 自增从 6 起步,导致故事 owner_id(1..5)外键失败。
-- 注意:tasks.kanban_card_id 外键为 ON DELETE SET NULL,任何删除故事的清理都会
-- 顺带把挂卡任务的 kanban_card_id 置空;契约测试因此不依赖"临时卡仍挂在任务上"。
-- ============================================================
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE meeting_audio;
TRUNCATE TABLE meeting_agent_events;
TRUNCATE TABLE meeting_agent_runs;
TRUNCATE TABLE meeting_approval_payloads;
TRUNCATE TABLE meeting_suggestion_records;
TRUNCATE TABLE suggestions;
TRUNCATE TABLE meetings;
TRUNCATE TABLE story_logs;
TRUNCATE TABLE tasks;
TRUNCATE TABLE pool_items;
TRUNCATE TABLE stories;
TRUNCATE TABLE member_profiles;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;
