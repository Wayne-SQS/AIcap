-- Sprint 2 live acceptance fixtures. Run only against the isolated aicap_java_test database.
-- The test database starts with P002 members 1/2 and no tasks. Keep these IDs out of ordinary data.
USE `aicap_java_test`;

INSERT INTO project_members (project_id, user_id, project_role, weekly_capacity_hours, capacity_hours, joined_at)
VALUES ('P002', 3, 'member', 16, 60, NOW()),
       ('P002', 4, 'member', 13, 54, NOW()),
       ('P002', 5, 'viewer', 12, 60, NOW());

-- T996 overloads member 1 in Sprint 2. T997 is blocked by unfinished T996.
-- T998 is a real persisted task for four-view and authorization checks.
INSERT INTO tasks (project_id, code, name, owner_id, hours, week_start, week_end,
                   story_ref, estimated_hours, task_type, status, depends_on, progress, blocked)
VALUES ('P002', 'T996', 'Sprint2 容量前置任务', 1, 60, 3, 4, '', 60, 'feature', 0, '', 0, 0),
       ('P002', 'T997', 'Sprint2 阻塞依赖任务', 1, 16, 3, 4, '', 16, 'feature', 0, 'T996', 0, 1),
       ('P002', 'T998', 'Sprint2 四视图持久化任务', 1, 8, 1, 1, '', 8, 'feature', 0, '', 0, 0);
