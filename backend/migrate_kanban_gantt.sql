-- ============================================================
-- 爱管理 · 看板与甘特「强制血缘对应」重构迁移脚本 v2
-- 目标库:AIcap(MySQL 8.0,charset utf8mb4)
-- 逻辑:
--   1) tasks 新增 kanban_card_id(强外键→stories.id) + estimated_hours + task_type + status
--   2) 新增 M21/M22/M23 三张看板卡,按业务域拆分 AI 工作
--   3) 12 条开发任务挂载到对应卡,4 条管理任务标记 management
--   4) estimated_hours 沿用现有 hours 值;status 按演示进度初始化
-- 执行前必做:mysqldump 备份(见第 0 步)
-- ============================================================

-- ============================================================
-- 第 0 步:备份(在 PowerShell 执行,不是 SQL)
-- ============================================================
-- docker exec aiguanli-mysql sh -c 'mysqldump -uaiguanli -paiguanli-2026 AIcap' > backup_before_kanban_gantt.sql
-- 说明:DDL(ALTER TABLE)在 MySQL 中隐式提交,无法回滚;
--       必须先备份,一旦 DDL 执行后报错只能用备份恢复。

-- ============================================================
-- 第 1 步:DDL —— tasks 表新增字段与外键(隐式提交,不可回滚)
-- ============================================================
ALTER TABLE tasks
  ADD COLUMN kanban_card_id VARCHAR(10) NULL
    COMMENT '所属看板卡 ID(强外键;管理类任务为 NULL)',
  ADD COLUMN estimated_hours INT NOT NULL DEFAULT 0
    COMMENT '预估工时(加权进度分母)',
  ADD COLUMN task_type VARCHAR(10) NOT NULL DEFAULT 'feature'
    COMMENT '任务类型:feature=开发任务挂卡 / management=管理任务',
  ADD COLUMN status INT NOT NULL DEFAULT 0
    COMMENT '任务状态:0待办 1进行中 2完成 3已取消',
  ADD CONSTRAINT fk_task_kanban_card
    FOREIGN KEY (kanban_card_id) REFERENCES stories(id)
    ON DELETE SET NULL ON UPDATE CASCADE;
-- 删卡策略:ON DELETE SET NULL(解绑任务,保留历史)
-- 注意:应用层 delete_story 会先按 undone 策略处理子任务再删卡,此约束为兜底。

-- ============================================================
-- 第 2 步:DML —— 数据迁移(事务包裹,可回滚)
-- ============================================================
START TRANSACTION;

-- 2.1 estimated_hours 复制现有 hours(沿用既有工时)
UPDATE tasks SET estimated_hours = hours WHERE estimated_hours = 0;

-- 2.2 插入 M21/M22/M23 三张新看板卡(按业务域垂直拆分,owner:成员3/成员2/成员4)
INSERT INTO stories (id, title, description, acceptance, priority, sprint, activity, status, owner_id, created_at) VALUES
  ('M21',
   '智能生成:UML与类图联动',
   '作为技术负责人，我希望UML自动生成与类图影响传播联动，以便设计变更可视化追踪',
   'UML可生成编辑；类图关联可查；影响传播可展示；关联US13/US21',
   'Should', 3, 2, 0, 3, NOW()),
  ('M22',
   'AI辅助开发:拆解与排期',
   '作为项目负责人，我希望AI辅助需求拆解与进度排期，以便降低估算偏差',
   'AI拆解可估算工时；进度预测可排期；关联US14/US17/US18/US19',
   'Should', 3, 3, 0, 2, NOW()),
  ('M23',
   'AI质量保障:分析与风险闭环',
   '作为质量负责人，我希望AI分析质量与风险并闭环改进，以便持续交付',
   '质量分析可定位缺口；风险预警可应对；闭环改进可追踪；关联US22/US23/US24',
   'Should', 3, 4, 0, 4, NOW());

-- 2.3 挂载 12 条开发任务到看板卡(feature)并初始化演示状态
UPDATE tasks SET kanban_card_id='M02', task_type='feature', status=2 WHERE id='T03';  -- 项目/成员/自定义权限 → M02
UPDATE tasks SET kanban_card_id='M04', task_type='feature', status=1 WHERE id='T04';  -- 故事/地图/基础看板 → M04
UPDATE tasks SET kanban_card_id='M08', task_type='feature', status=1 WHERE id='T05';  -- 基础报表与 S1 验证 → M08
UPDATE tasks SET kanban_card_id='M15', task_type='feature', status=0 WHERE id='T06';  -- 看板增强与趋势 → M15
UPDATE tasks SET kanban_card_id='M11', task_type='feature', status=0 WHERE id='T07';  -- 甘特图与成员任务图 → M11
UPDATE tasks SET kanban_card_id='M21', task_type='feature', status=1 WHERE id='T08';  -- UML 自动生成与编辑 → M21
UPDATE tasks SET kanban_card_id='M03', task_type='feature', status=0 WHERE id='T09';  -- 权限扩展与联动 → M03
UPDATE tasks SET kanban_card_id='M22', task_type='feature', status=0 WHERE id='T10';  -- AI 拆解与估算 → M22
UPDATE tasks SET kanban_card_id='M22', task_type='feature', status=0 WHERE id='T11';  -- AI 进度预测与排期 → M22
UPDATE tasks SET kanban_card_id='M23', task_type='feature', status=0 WHERE id='T12';  -- AI 质量分析 → M23
UPDATE tasks SET kanban_card_id='M23', task_type='feature', status=0 WHERE id='T13';  -- AI 风险/效率/闭环 → M23
UPDATE tasks SET kanban_card_id='M21', task_type='feature', status=0 WHERE id='T14';  -- 类图关联与影响传播 → M21

-- 2.4 标记 4 条管理类任务(management,不挂卡)
UPDATE tasks SET kanban_card_id=NULL, task_type='management', status=2 WHERE id IN ('T01','T02');
UPDATE tasks SET kanban_card_id=NULL, task_type='management', status=0 WHERE id='T15';
UPDATE tasks SET kanban_card_id=NULL, task_type='management', status=1 WHERE id='T16';

-- ============================================================
-- 第 3 步:迁移后校验(必须 0 条孤儿开发任务)
-- ============================================================

-- 3.1 孤儿校验:预期 0 行(任何 feature 任务缺 kanban_card_id 都是脏数据)
SELECT t.id, t.name, t.kanban_card_id
FROM tasks t
WHERE t.task_type='feature' AND t.kanban_card_id IS NULL;

-- 3.2 卡下子任务分布(每卡 ≤ 3 条,9 张挂载卡应均有子任务)
SELECT s.id AS card_id, s.title AS card_title, s.sprint, s.activity,
       COUNT(t.id) AS sub_tasks,
       SUM(t.estimated_hours) AS total_hours
FROM stories s
LEFT JOIN tasks t ON t.kanban_card_id = s.id AND t.task_type='feature'
WHERE s.id IN ('M02','M03','M04','M08','M11','M15','M21','M22','M23')
GROUP BY s.id, s.title, s.sprint, s.activity
ORDER BY s.id;

-- 3.3 管理类任务清单(预期 4 条:T01/T02/T15/T16)
SELECT id, name, task_type, kanban_card_id, estimated_hours, status
FROM tasks WHERE task_type='management' ORDER BY id;

-- 3.4 看板卡总数校验(预期 23 张:M01-M23)
SELECT COUNT(*) AS total_stories FROM stories;

-- ============================================================
-- 第 4 步:确认校验无误后提交;有报错用 ROLLBACK 回滚 DML 段
-- ============================================================
COMMIT;
-- 校验异常则:ROLLBACK;
-- 注:DDL(ALTER TABLE)已隐式提交,ROLLBACK 只能回滚 DML,无法撤销新字段;
--     如需完全回退,用第 0 步备份恢复。
