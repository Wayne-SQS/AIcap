-- Run only after Sprint 2 live specs finish, against the isolated QA database.
USE `aicap_java_test`;
DELETE FROM tasks WHERE project_id = 'P002' AND code IN ('T996', 'T997', 'T998');
DELETE FROM project_members WHERE project_id = 'P002' AND user_id IN (3, 4, 5);
