-- ============================================================
-- AIcap(爱管理)MySQL 8.0 建表脚本(JavaWeb 后端自举用)
-- 由 spring.sql.init 在每次启动时执行;全部使用 IF NOT EXISTS,幂等。
-- 列定义对齐 aicap_java_test_schema.sql(mysqldump 自 SQLAlchemy create_all 的迁移导出)。
-- 依赖顺序:users → stories → tasks → pool_items → suggestions → meetings
--           → meeting_suggestion_records → meeting_approval_payloads
--           → meeting_agent_runs → meeting_agent_events → story_logs
--           → member_profiles(成员画像,1:1 users)
--           → meeting_audio(会议录音/上传的 mp3 元数据,文件落盘)
-- ============================================================

CREATE TABLE IF NOT EXISTS `users` (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `display_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `color` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `capacity_hours` int NOT NULL DEFAULT '60' COMMENT '六周可用容量(小时),成员负载视图分母',
  PRIMARY KEY (`id`),
  UNIQUE KEY `ix_users_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `stories` (
  `id` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `acceptance` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `priority` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sprint` int NOT NULL,
  `activity` int NOT NULL,
  `status` int NOT NULL,
  `owner_id` int DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `owner_id` (`owner_id`),
  CONSTRAINT `stories_ibfk_1` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `tasks` (
  `id` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `owner_id` int NOT NULL,
  `hours` int NOT NULL,
  `week_start` int NOT NULL,
  `week_end` int NOT NULL,
  `story_ref` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `kanban_card_id` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '所属看板卡 ID(强外键;管理类任务为 NULL)',
  `estimated_hours` int NOT NULL DEFAULT '0' COMMENT '预估工时(加权进度分母)',
  `task_type` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'feature' COMMENT '任务类型:feature=开发任务挂卡 / management=管理任务',
  `status` int NOT NULL DEFAULT '0' COMMENT '任务状态:0待办 1进行中 2完成 3已取消',
  `depends_on` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '前置任务 ID(逗号分隔的 Txx)',
  `progress` int NOT NULL DEFAULT '0' COMMENT '完成百分比 0..100',
  `blocked` int NOT NULL DEFAULT '0' COMMENT '是否阻塞:0否 1是',
  PRIMARY KEY (`id`),
  KEY `fk_task_kanban_card` (`kanban_card_id`),
  CONSTRAINT `fk_task_kanban_card` FOREIGN KEY (`kanban_card_id`) REFERENCES `stories` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `pool_items` (
  `id` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `source` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `priority` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `suggestions` (
  `id` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `agent` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `kind` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `evidence` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `affected` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `note` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `change_json` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `meetings` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `transcript` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_by` int NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `created_by` (`created_by`),
  CONSTRAINT `meetings_ibfk_1` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `meeting_suggestion_records` (
  `id` int NOT NULL AUTO_INCREMENT,
  `suggestion_id` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `meeting_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `client_request_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `request_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `submitted_by` int NOT NULL,
  `origin` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reviewed_by` int DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `reason` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `execution_status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `pool_item_id` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_meeting_suggestion_request` (`meeting_id`,`submitted_by`,`client_request_id`),
  UNIQUE KEY `suggestion_id` (`suggestion_id`),
  KEY `submitted_by` (`submitted_by`),
  KEY `reviewed_by` (`reviewed_by`),
  CONSTRAINT `meeting_suggestion_records_ibfk_1` FOREIGN KEY (`suggestion_id`) REFERENCES `suggestions` (`id`),
  CONSTRAINT `meeting_suggestion_records_ibfk_2` FOREIGN KEY (`meeting_id`) REFERENCES `meetings` (`id`),
  CONSTRAINT `meeting_suggestion_records_ibfk_3` FOREIGN KEY (`submitted_by`) REFERENCES `users` (`id`),
  CONSTRAINT `meeting_suggestion_records_ibfk_4` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `meeting_approval_payloads` (
  `suggestion_id` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `changes_json` text COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`suggestion_id`),
  CONSTRAINT `meeting_approval_payloads_ibfk_1` FOREIGN KEY (`suggestion_id`) REFERENCES `suggestions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `meeting_agent_runs` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `meeting_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `requested_by` int NOT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempt` int NOT NULL,
  `model` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `prompt_version` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `worker_token` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `result_json` longtext COLLATE utf8mb4_unicode_ci,
  `error_code` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `error_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `meeting_id` (`meeting_id`),
  KEY `requested_by` (`requested_by`),
  KEY `ix_meeting_agent_runs_status` (`status`),
  CONSTRAINT `meeting_agent_runs_ibfk_1` FOREIGN KEY (`meeting_id`) REFERENCES `meetings` (`id`),
  CONSTRAINT `meeting_agent_runs_ibfk_2` FOREIGN KEY (`requested_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `meeting_agent_events` (
  `id` int NOT NULL AUTO_INCREMENT,
  `run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempt` int NOT NULL,
  `kind` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `detail_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_meeting_agent_events_run_id` (`run_id`),
  CONSTRAINT `meeting_agent_events_ibfk_1` FOREIGN KEY (`run_id`) REFERENCES `meeting_agent_runs` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `story_logs` (
  `id` int NOT NULL AUTO_INCREMENT,
  `story_id` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `log_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `detail` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` int DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 成员画像(1:1 users):技术栈 / 工作能力 / 熟悉的开发流程领域,均存 JSON 数组文本
-- 元素形如 {"name":"Java","level":5}(level 1..5,1=了解 5=精通)
CREATE TABLE IF NOT EXISTS `member_profiles` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `title` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '岗位/画像标题',
  `tech_stack` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'JSON 数组:熟悉的技术栈',
  `capabilities` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'JSON 数组:工作能力',
  `process_domains` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'JSON 数组:熟悉的开发流程领域',
  `summary` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '画像摘要',
  `years_experience` int NOT NULL DEFAULT '0' COMMENT '项目经验年限',
  `updated_by` int DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_member_profiles_user` (`user_id`),
  KEY `member_profiles_updated_by` (`updated_by`),
  CONSTRAINT `member_profiles_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `member_profiles_ibfk_2` FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 会议音频:网页麦克风录音(客户端编码为 mp3)或本地上传的 mp3;字节落盘,本表存元数据
CREATE TABLE IF NOT EXISTS `meeting_audio` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `meeting_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `filename` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_type` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `byte_size` int NOT NULL,
  `duration_ms` int DEFAULT NULL COMMENT '录音时长(毫秒,前端上报)',
  `sha256` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `storage_path` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '相对音频根目录的落盘路径',
  `source` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'recorder=网页录音 / upload=本地文件',
  `uploaded_by` int NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_meeting_audio_meeting_id` (`meeting_id`),
  KEY `ix_meeting_audio_uploaded_by` (`uploaded_by`),
  CONSTRAINT `meeting_audio_ibfk_1` FOREIGN KEY (`meeting_id`) REFERENCES `meetings` (`id`),
  CONSTRAINT `meeting_audio_ibfk_2` FOREIGN KEY (`uploaded_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
