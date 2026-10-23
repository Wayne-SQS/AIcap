package com.aicap.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 存量库列迁移(对齐 FastAPI app/main.py lifespan 中的 inspect(engine).get_columns + ALTER TABLE 段)。
 *
 * <p>schema.sql 只保证"表不存在时建表":对已经建好的老库,新增的列不会被补上。
 * 本组件在播种(DataSeeder)之前运行,按数据库实际列名判定后补列,
 * 使老库无需人工 DDL 即可升级到当前列集合,且可重复执行。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class SchemaUpgrader implements ApplicationRunner {

    private final DataSource dataSource;
    private static final String PROJECT_GENERATION_REQUEST_TEXT_MIGRATION =
            "V12_PROJECT_GENERATION_REQUEST_TEXT_TO_TEXT";
    private static final String LEGACY_PROJECT_RECOVERY = "V13_LEGACY_PROJECT_RECOVERY";
    private static final String LEGACY_RELATIONS = "V14_LEGACY_RELATIONS";

    /** (列名, 列定义) —— 顺序与 FastAPI 迁移脚本保持一致 */
    private static final String[][] TASK_COLUMNS = {
            {"depends_on", "`depends_on` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
            {"priority", "`priority` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'Should'"},
            {"kanban_card_id", "`kanban_card_id` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
            {"estimated_hours", "`estimated_hours` int NOT NULL DEFAULT '0'"},
            {"task_type", "`task_type` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'feature'"},
            {"status", "`status` int NOT NULL DEFAULT '0'"},
            {"progress", "`progress` int NOT NULL DEFAULT '0'"},
            {"blocked", "`blocked` int NOT NULL DEFAULT '0'"},
            // 多项目改造新增:任务与故事的关联改为 internal_id(见 migrateInternalKeys)
            {"story_internal_id", "`story_internal_id` bigint DEFAULT NULL"},
            {"story_ref_legacy", "`story_ref_legacy` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
            {"depends_on_legacy", "`depends_on_legacy` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
            {"kanban_card_id_legacy", "`kanban_card_id_legacy` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
    };

    /**
     * 快照列 + internal_id 外键列:与 stories/tasks 换主键同一批改造。
     * 为什么必须补:业务编号可以在项目内被复用(删了 US01 再建 US01),
     * 于是"某条日志当时指的是哪个故事"只能靠 internal_id 回答;而故事本身被删之后
     * 连 internal_id 都查不到东西,所以还要留一份编号快照。缺了这两列,
     * 删故事(以及写日志)会直接 500 —— 删除失败又会让契约测试的清理留下残留。
     */
    private static final String[][] STORY_LOG_COLUMNS = {
            {"story_internal_id", "`story_internal_id` bigint DEFAULT NULL"},
            // 列定义与 schema.sql 对齐:快照列不写 COLLATE,继承表默认 utf8mb4_unicode_ci。
            // 写了 COLLATE 的 ALTER 在 H2(MODE=MySQL) 上不被接受,StoryLogMigrationTest 会直接红。
            {"story_code_snapshot", "`story_code_snapshot` varchar(20) DEFAULT NULL"},
    };

    private static final String[][] ACTIVITY_RECORD_COLUMNS = {
            {"task_internal_id", "`task_internal_id` bigint DEFAULT NULL"},
            {"task_code_snapshot", "`task_code_snapshot` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
    };

    private static final String[][] DIFFICULTY_ASSESSMENT_COLUMNS = {
            {"task_internal_id", "`task_internal_id` bigint DEFAULT NULL"},
            {"task_code_snapshot", "`task_code_snapshot` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
    };

    private static final String[][] MILESTONE_COLUMNS = {
            {"related_task_ids_legacy", "`related_task_ids_legacy` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
    };

    /**
     * 需要切到「internal_id 自增主键 + code 业务编号」的业务表。
     * 顺序 = 依赖顺序:stories 必须先于 tasks(tasks.story_internal_id 回填依赖 stories.internal_id)。
     */
    private static final String[][] INTERNAL_KEY_TABLES = {
            {"stories", "uq_stories_project_code"},
            {"tasks", "uq_tasks_project_code"},
            {"milestones", "uq_milestones_project_code"},
    };

    private static final String[][] USER_COLUMNS = {
            {"capacity_hours", "`capacity_hours` int NOT NULL DEFAULT '60'"},
    };

    private static final String[][] STORY_COLUMNS = {
            {"role", "`role` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
            {"goal", "`goal` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
            {"value", "`value` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
            {"epic_id", "`epic_id` bigint DEFAULT NULL"},
    };

    private static final String[][] PROJECT_MEMBER_COLUMNS = {
            {"weekly_capacity_hours", "`weekly_capacity_hours` int DEFAULT NULL"},
    };

    private static final String[][] KNOWLEDGE_CHUNK_COLUMNS = {
            {"embedding_model", "`embedding_model` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
            {"acl_rank", "`acl_rank` int DEFAULT NULL"},
    };

    /**
     * planning / generation 两个队列的租约列(对齐 {@code meeting_agent_runs.worker_token/lease_until})。
     * 没有租约时进程崩溃会让 run 永久停在 {@code running},任何路径都不回收 —— 任务是永久丢单。
     */
    private static final String[][] LEASE_COLUMNS = {
            {"worker_token", "worker_token varchar(36) DEFAULT NULL"},
            {"lease_until", "lease_until datetime DEFAULT NULL"},
    };

    /**
     * 画像队列(S5′ 触发型工具 {@code start_profile_analysis})需要的列。
     *
     * <p>{@code only_user_id} 必须落库而不是留在内存里:run 是<b>跨进程</b>交接的 ——
     * 工具只建 run 就返回,真正执行的是另一个线程(甚至是另一次轮询)。范围只存在于
     * 建 run 那次调用的栈里,消费者拿不到,只能全团队分析一遍(成本 6 倍)。
     */
    private static final String[][] PROFILE_RUN_COLUMNS = {
            {"only_user_id", "only_user_id int DEFAULT NULL"},
    };

    /** 四个后台队列表:认领查询同形(WHERE status=? ORDER BY created_at LIMIT 1),索引也应同形 */
    private static final String[] QUEUE_TABLES = {
            "meeting_agent_runs", "planning_agent_runs", "project_generation_runs",
            "profile_agent_runs",
    };

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            Set<String> added = new HashSet<>();
            // 项目风险建议没有所属会议。旧版队列索引列为 NOT NULL，审核时同步索引会 500。
            if (tableExists(conn, "review_queue_proposals") && !isNullable(conn, "review_queue_proposals", "meeting_id")) {
                execute(conn, "ALTER TABLE review_queue_proposals MODIFY COLUMN meeting_id varchar(64) NULL");
            }
            migrateProjects(conn);
            added.addAll(migrateInternalKeys(conn));
            // 数据引用的项目必须在 projects 表里存在,否则接口一律 404「项目不存在或已归档」
            added.addAll(ensureReferencedProjectsExist(conn));
            // story_logs 补 internal-key 审计列(story_internal_id / story_code_snapshot),
            // 与 stories 的 id→code 迁移配套,否则改名后的历史记录追溯不到
            added.addAll(migrateStoryLogs(conn));
            // 队友变更:自定义角色与史诗两张表只对全新库由 schema.sql 建,老库不会重跑它,靠这里补建
            execute(conn, "CREATE TABLE IF NOT EXISTS roles (name varchar(50) NOT NULL, can_write tinyint(1) NOT NULL DEFAULT 0, can_manage tinyint(1) NOT NULL DEFAULT 0, PRIMARY KEY(name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            execute(conn, "CREATE TABLE IF NOT EXISTS epics (id bigint NOT NULL AUTO_INCREMENT, project_id varchar(20) NOT NULL, name varchar(100) NOT NULL, description varchar(500) NOT NULL DEFAULT '', created_at datetime NOT NULL, PRIMARY KEY(id), KEY ix_epics_project(project_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            // 队友变更:stories 补 role/goal/value/epic_id 四列(与 DataSeeder.backfillLegacyStoryFields 配套)
            if (tableExists(conn, "stories")) {
                added.addAll(addMissing(conn, "stories", STORY_COLUMNS));
            }
            if (tableExists(conn, "project_members")) {
                added.addAll(addMissing(conn, "project_members", PROJECT_MEMBER_COLUMNS));
            }
            ensureTaskStoryRelations(conn);
            if (tableExists(conn, "tasks")) {
                added.addAll(addMissing(conn, "tasks", TASK_COLUMNS));
                if (added.contains("tasks.estimated_hours")) {
                    // 老库补列后 estimated_hours 全为 0,按 hours 回填(对齐 FastAPI 迁移脚本)
                    execute(conn, "UPDATE `tasks` SET `estimated_hours` = `hours` WHERE `estimated_hours` = 0");
                    log.info("tasks.estimated_hours 已按 hours 回填");
                }
            }
            // 结构补列完成后才回填:story_internal_id 依赖 stories.internal_id 已存在
            added.addAll(backfillInternalRefs(conn));
            added.addAll(backfillReferenceSnapshots(conn));
            if (tableExists(conn, "milestones")) {
                added.addAll(addMissing(conn, "milestones", MILESTONE_COLUMNS));
                added.addAll(backfillMilestoneLegacy(conn));
            }
            if (tableExists(conn, "users")) {
                added.addAll(addMissing(conn, "users", USER_COLUMNS));
            }
            // knowledge_chunks 建表时可不存在;补列后老向量因 embedding_model 为空会被判为过期,
            // 下次重建自动重算 —— 这正是引入该列的目的,无需额外回填。
            // acl_rank 不同:它参与过滤,不填等于整库不可见,必须回填(判定见 hasNullAclRank)
            if (tableExists(conn, "knowledge_chunks")) {
                added.addAll(addMissing(conn, "knowledge_chunks", KNOWLEDGE_CHUNK_COLUMNS));
                if (hasNullAclRank(conn)) {
                    backfillAclRank(conn);
                }
                added.addAll(ensureFulltextIndex(conn));
            }
            execute(conn, "CREATE TABLE IF NOT EXISTS planning_agent_runs (id varchar(36) NOT NULL, requested_by int NOT NULL, project_id varchar(20) NOT NULL DEFAULT 'P001', request_text varchar(2000) NOT NULL, status varchar(30) NOT NULL, attempt int NOT NULL, model varchar(200) NOT NULL, prompt_version varchar(40) NOT NULL, context_json longtext, result_json longtext, error_code varchar(60), error_message varchar(500), created_at datetime NOT NULL, updated_at datetime NOT NULL, PRIMARY KEY (id), KEY ix_planning_agent_runs_status (status), KEY ix_planning_agent_runs_project (project_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            addProjectColumnWithDefault(conn, "planning_agent_runs", "P001");
            execute(conn, "CREATE TABLE IF NOT EXISTS planning_agent_events (id int NOT NULL AUTO_INCREMENT, run_id varchar(36) NOT NULL, attempt int NOT NULL, kind varchar(40) NOT NULL, detail_json longtext NOT NULL, created_at datetime(6) NOT NULL, PRIMARY KEY (id), KEY ix_planning_agent_events_run_id (run_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            execute(conn, "CREATE TABLE IF NOT EXISTS project_generation_runs (id varchar(36) NOT NULL, requested_by int NOT NULL, project_id varchar(20) NOT NULL DEFAULT 'P001', mode varchar(30) NOT NULL, strategy varchar(20) NOT NULL, source_name varchar(255), request_text text, status varchar(30) NOT NULL, attempt int NOT NULL, model varchar(200) NOT NULL, prompt_version varchar(40) NOT NULL, draft_json longtext, error_code varchar(60), error_message varchar(500), created_at datetime NOT NULL, updated_at datetime NOT NULL, PRIMARY KEY (id), KEY ix_project_generation_runs_status (status), KEY ix_project_generation_runs_project (project_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            addProjectColumnWithDefault(conn, "project_generation_runs", "P001");
            migrateProjectGenerationRequestText(conn);
            execute(conn, "CREATE TABLE IF NOT EXISTS project_generation_events (id int NOT NULL AUTO_INCREMENT, run_id varchar(36) NOT NULL, attempt int NOT NULL, kind varchar(40) NOT NULL, detail_json longtext NOT NULL, created_at datetime(6) NOT NULL, PRIMARY KEY (id), KEY ix_project_generation_events_run_id (run_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            execute(conn, "ALTER TABLE planning_agent_events MODIFY COLUMN created_at datetime(6) NOT NULL");
            execute(conn, "ALTER TABLE project_generation_events MODIFY COLUMN created_at datetime(6) NOT NULL");
            execute(conn, "CREATE TABLE IF NOT EXISTS milestones (id varchar(20) NOT NULL, name varchar(200) NOT NULL, week int NOT NULL, description varchar(500) NOT NULL DEFAULT '', status varchar(20) NOT NULL DEFAULT 'planned', related_task_ids varchar(500), PRIMARY KEY (id), KEY ix_milestones_week (week)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // planning / generation 队列的租约列。补列后老库里已卡在 running 的 run,
            // lease_until 为 NULL —— expireRuns 的判定显式把 NULL 也算过期,不靠一次性回填。
            if (tableExists(conn, "planning_agent_runs")) {
                added.addAll(addMissing(conn, "planning_agent_runs", LEASE_COLUMNS));
            }
            if (tableExists(conn, "project_generation_runs")) {
                added.addAll(addMissing(conn, "project_generation_runs", LEASE_COLUMNS));
            }
            // 画像队列:原先是"同步跑完再返回",没有队列列。S5′ 的 start_profile_analysis
            // 要求只建 run 就返回(否则会吃穿编排层的有界循环预算),因此补同样的租约列。
            if (tableExists(conn, "profile_agent_runs")) {
                added.addAll(addMissing(conn, "profile_agent_runs", LEASE_COLUMNS));
                added.addAll(addMissing(conn, "profile_agent_runs", PROFILE_RUN_COLUMNS));
                addProjectColumnWithDefault(conn, "profile_agent_runs", "P001");
            }
            // 认领查询是 WHERE status=? ORDER BY created_at LIMIT 1,而表上只有 status 单列索引
            // → status 相等的那批仍要 filesort。补 (status, created_at) 复合索引。
            for (String queue : QUEUE_TABLES) {
                if (tableExists(conn, queue)) {
                    added.addAll(ensureQueueIndex(conn, queue, "ix_" + queue + "_status_created"));
                }
            }
            if (added.isEmpty()) {
                log.info("存量库列迁移:列集合已是最新,无需变更");
            } else {
                log.info("存量库列迁移完成:新增 {} 列 {}", added.size(), added);
            }
        } catch (SQLException e) {
            // 列缺失会让后续播种与接口静默出错,因此启动即失败,不做降级
            throw new IllegalStateException("存量库列迁移失败: " + e.getMessage(), e);
        }
    }

    Set<String> migrateStoryLogs(Connection conn) throws SQLException {
        if (!tableExists(conn, "story_logs")) return Set.of();
        return addMissing(conn, "story_logs", STORY_LOG_COLUMNS);
    }

    /** 多项目平滑迁移：保留现有正式项目，不再重建已物理删除的历史 P001。 */
    private void migrateProjects(Connection conn) throws SQLException {
        ensureProjectTables(conn);
        execute(conn, "INSERT IGNORE INTO projects(id,name,description,status,total_sprints,created_by,created_at,updated_at) SELECT 'P002','爱管理','正式规划数据待导入','ACTIVE',0,MIN(id),NOW(),NOW() FROM users WHERE NOT EXISTS (SELECT 1 FROM projects WHERE id='P002')");
        execute(conn, "INSERT IGNORE INTO project_members(project_id,user_id,project_role,capacity_hours,joined_at) SELECT 'P002',id,CASE WHEN role IN ('admin','owner') THEN role WHEN role='viewer' THEN 'viewer' ELSE 'member' END,capacity_hours,NOW() FROM users");
        migrateScopedBusinessTable(conn, "stories", "uq_stories_project_code");
        migrateScopedBusinessTable(conn, "tasks", "uq_tasks_project_code");
        migrateScopedBusinessTable(conn, "milestones", "uq_milestones_project_code");
        recoverLegacyProject(conn);
        addProjectColumn(conn, "story_logs");
        addProjectColumn(conn, "pool_items");
        addProjectColumn(conn, "meetings");
        addProjectColumn(conn, "planning_agent_runs");
        addProjectColumn(conn, "project_generation_runs");
        addProjectColumn(conn, "activity_records");
        addProjectColumn(conn, "difficulty_assessments");
        addProjectColumn(conn, "profile_snapshots");
        addProjectColumn(conn, "profile_agent_runs");
        addProjectColumn(conn, "suggestions");
        addProjectColumn(conn, "meeting_agent_runs");
        migrateLegacyReferences(conn);
    }

    private void ensureProjectTables(Connection conn) throws SQLException {
        execute(conn, "CREATE TABLE IF NOT EXISTS projects (id varchar(20) NOT NULL, name varchar(200) NOT NULL, description text NOT NULL, status varchar(20) NOT NULL DEFAULT 'ACTIVE', start_date date DEFAULT NULL, end_date date DEFAULT NULL, total_sprints int NOT NULL DEFAULT 0, created_by int DEFAULT NULL, created_at datetime NOT NULL, updated_at datetime NOT NULL, PRIMARY KEY (id), KEY ix_projects_status (status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        execute(conn, "CREATE TABLE IF NOT EXISTS project_members (id bigint NOT NULL AUTO_INCREMENT, project_id varchar(20) NOT NULL, user_id int NOT NULL, project_role varchar(20) NOT NULL, weekly_capacity_hours int DEFAULT NULL, capacity_hours int NOT NULL DEFAULT 60, joined_at datetime NOT NULL, PRIMARY KEY (id), UNIQUE KEY uq_project_members_project_user (project_id,user_id), KEY ix_project_members_user (user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        execute(conn, "CREATE TABLE IF NOT EXISTS app_migrations (name varchar(100) NOT NULL, applied_at datetime NOT NULL, PRIMARY KEY(name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }

    private void migrateProjectGenerationRequestText(Connection conn) throws SQLException {
        if (!tableExists(conn, "project_generation_runs")) return;
        String type = null;
        try (ResultSet rs = conn.getMetaData().getColumns(
                conn.getCatalog(), null, "project_generation_runs", "request_text")) {
            if (rs.next()) type = rs.getString("TYPE_NAME");
        }
        boolean alreadyLongText = type != null && Set.of("TEXT", "MEDIUMTEXT", "LONGTEXT")
                .contains(type.toUpperCase(java.util.Locale.ROOT));
        if (!alreadyLongText) {
            execute(conn, "ALTER TABLE project_generation_runs MODIFY COLUMN request_text TEXT COLLATE utf8mb4_unicode_ci DEFAULT NULL");
            log.info("project_generation_runs.request_text 已从 {} 升级为 TEXT", type);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO app_migrations(name, applied_at) VALUES (?, NOW())")) {
            ps.setString(1, PROJECT_GENERATION_REQUEST_TEXT_MIGRATION);
            ps.executeUpdate();
        }
    }

    private void migrateScopedBusinessTable(Connection conn, String table, String uniqueName) throws SQLException {
        if (!tableExists(conn, table)) return;
        Set<String> existingColumns = columns(conn, table);
        if (existingColumns.contains("internal_id") && existingColumns.contains("code")) {
            if (!existingColumns.contains("project_id")) throw new SQLException(table + " 缺少 project_id");
            if (!indexExists(conn, table, uniqueName))
                execute(conn, "ALTER TABLE `" + table + "` ADD UNIQUE KEY `" + uniqueName + "` (project_id,code)");
            return;
        }
        if (!existingColumns.contains("project_id")) execute(conn, "ALTER TABLE `"+table+"` ADD COLUMN project_id varchar(20) NULL");
        execute(conn, "UPDATE `"+table+"` SET project_id='P001' WHERE project_id IS NULL OR project_id=''");
        execute(conn, "ALTER TABLE `"+table+"` MODIFY COLUMN project_id varchar(20) NOT NULL");
        if (!existingColumns.contains("code")) {
            if (!existingColumns.contains("id")) throw new SQLException(table + " 缺少 id 和 code");
            // The old business id becomes the public code. Drop FKs that still point at the
            // old varchar PK; new records are related by project and internal key instead.
            dropForeignKeysReferencing(conn, table);
            execute(conn, "ALTER TABLE `" + table + "` CHANGE COLUMN `id` `code` varchar(20) NOT NULL");
            existingColumns = columns(conn, table);
        }
        if (!existingColumns.contains("internal_id")) {
            if (!hasPrimaryKeyOn(conn, table, "code")) throw new SQLException(table + " 的旧主键不是 code");
            execute(conn, "ALTER TABLE `" + table + "` DROP PRIMARY KEY, ADD COLUMN `internal_id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST");
        }
        if (!indexExists(conn, table, uniqueName)) execute(conn, "ALTER TABLE `"+table+"` ADD UNIQUE KEY `"+uniqueName+"` (project_id,code)");
    }

    private void recoverLegacyProject(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM app_migrations WHERE name=?")) {
            ps.setString(1, LEGACY_PROJECT_RECOVERY);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return; }
        }
        // Only recover orphaned rows from a pre-project database. The marker prevents a later
        // intentional deletion of P001 from being undone on every application restart.
        // INSERT IGNORE：多个测试上下文/进程可能同时走到这里，NOT EXISTS 与 INSERT 之间
        // 仍有并发窗口；主键冲突不应让启动失败（幂等恢复）。
        execute(conn, "INSERT IGNORE INTO projects(id,name,description,status,total_sprints,created_by,created_at,updated_at) "
                + "SELECT 'P001','历史项目','从旧版单项目数据恢复','ACTIVE',COALESCE((SELECT MAX(sprint) FROM stories WHERE project_id='P001'),0),MIN(id),NOW(),NOW() FROM users "
                + "WHERE NOT EXISTS (SELECT 1 FROM projects WHERE id='P001') AND (EXISTS (SELECT 1 FROM stories WHERE project_id='P001') OR EXISTS (SELECT 1 FROM tasks WHERE project_id='P001') OR EXISTS (SELECT 1 FROM milestones WHERE project_id='P001'))");
        execute(conn, "INSERT IGNORE INTO project_members(project_id,user_id,project_role,capacity_hours,joined_at) "
                + "SELECT 'P001',id,CASE WHEN role IN ('admin','owner') THEN role WHEN role='viewer' THEN 'viewer' ELSE 'member' END,capacity_hours,NOW() FROM users WHERE EXISTS (SELECT 1 FROM projects WHERE id='P001')");
        try (PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO app_migrations(name,applied_at) VALUES (?,NOW())")) {
            ps.setString(1, LEGACY_PROJECT_RECOVERY);
            ps.executeUpdate();
        }
    }

    private void migrateLegacyReferences(Connection conn) throws SQLException {
        addMissing(conn, "tasks", new String[][] {
                {"story_internal_id", "`story_internal_id` bigint DEFAULT NULL"},
                {"story_ref_legacy", "`story_ref_legacy` varchar(100) DEFAULT NULL"},
                {"depends_on_legacy", "`depends_on_legacy` varchar(100) DEFAULT NULL"},
                {"kanban_card_id_legacy", "`kanban_card_id_legacy` varchar(10) DEFAULT NULL"}
        });
        execute(conn, "UPDATE tasks SET story_ref_legacy=story_ref WHERE story_ref_legacy IS NULL AND story_ref IS NOT NULL");
        execute(conn, "UPDATE tasks SET depends_on_legacy=depends_on WHERE depends_on_legacy IS NULL AND depends_on IS NOT NULL");
        execute(conn, "UPDATE tasks SET kanban_card_id_legacy=kanban_card_id WHERE kanban_card_id_legacy IS NULL AND kanban_card_id IS NOT NULL");
        execute(conn, "UPDATE tasks t JOIN stories s ON s.project_id=t.project_id AND s.code=COALESCE(NULLIF(t.kanban_card_id,''),SUBSTRING_INDEX(t.story_ref,',',1)) SET t.story_internal_id=s.internal_id WHERE t.story_internal_id IS NULL");
        addMissing(conn, "milestones", new String[][] {{"related_task_ids_legacy", "`related_task_ids_legacy` varchar(500) DEFAULT NULL"}});
        execute(conn, "UPDATE milestones SET related_task_ids_legacy=related_task_ids WHERE related_task_ids_legacy IS NULL AND related_task_ids IS NOT NULL");
        addMissing(conn, "story_logs", new String[][] {
                {"story_internal_id", "`story_internal_id` bigint DEFAULT NULL"},
                {"story_code_snapshot", "`story_code_snapshot` varchar(20) DEFAULT NULL"}
        });
        execute(conn, "UPDATE story_logs l JOIN stories s ON s.project_id=l.project_id AND s.code=l.story_id SET l.story_internal_id=s.internal_id,l.story_code_snapshot=s.code WHERE l.story_internal_id IS NULL");
        for (String table : new String[] {"activity_records", "difficulty_assessments"}) {
            addMissing(conn, table, new String[][] {
                    {"task_internal_id", "`task_internal_id` bigint DEFAULT NULL"},
                    {"task_code_snapshot", "`task_code_snapshot` varchar(20) DEFAULT NULL"}
            });
            execute(conn, "UPDATE `" + table + "` r JOIN tasks t ON t.project_id=r.project_id AND t.code=r.task_id SET r.task_internal_id=t.internal_id,r.task_code_snapshot=t.code WHERE r.task_internal_id IS NULL");
        }
        migrateLegacyRelations(conn);
    }

    private void migrateLegacyRelations(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM app_migrations WHERE name=?")) {
            ps.setString(1, LEGACY_RELATIONS);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return; }
        }
        java.util.List<Object[]> tasks = new java.util.ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT project_id,internal_id,story_internal_id,story_ref_legacy,depends_on_legacy FROM tasks")) {
            while (rs.next()) tasks.add(new Object[] {rs.getString(1), rs.getLong(2), rs.getObject(3), rs.getString(4), rs.getString(5)});
        }
        try (PreparedStatement stories = conn.prepareStatement(
                    "INSERT IGNORE INTO task_story_relations(project_id,task_internal_id,story_internal_id,created_at,updated_at) "
                            + "SELECT ?,?,s.internal_id,NOW(),NOW() FROM stories s WHERE s.project_id=? AND s.code=? AND s.internal_id<>?");
             PreparedStatement dependencies = conn.prepareStatement(
                    "INSERT IGNORE INTO task_dependencies(project_id,predecessor_task_internal_id,successor_task_internal_id,created_at) "
                            + "SELECT ?,t.internal_id,?,NOW() FROM tasks t WHERE t.project_id=? AND t.code=?")) {
            for (Object[] task : tasks) {
                String projectId = (String) task[0];
                long taskId = (Long) task[1];
                Long primaryStoryId = (Long) task[2];
                for (String code : splitLegacyCodes((String) task[3])) {
                    if (primaryStoryId == null) continue;
                    stories.setString(1, projectId); stories.setLong(2, taskId);
                    stories.setString(3, projectId); stories.setString(4, code); stories.setLong(5, primaryStoryId);
                    stories.executeUpdate();
                }
                for (String code : splitLegacyCodes((String) task[4])) {
                    dependencies.setString(1, projectId); dependencies.setLong(2, taskId);
                    dependencies.setString(3, projectId); dependencies.setString(4, code);
                    dependencies.executeUpdate();
                }
            }
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT project_id,internal_id,related_task_ids_legacy FROM milestones")) {
            java.util.List<Object[]> rows = new java.util.ArrayList<>();
            while (rs.next()) rows.add(new Object[] {rs.getString(1), rs.getLong(2), rs.getString(3)});
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT IGNORE INTO milestone_tasks(project_id,milestone_internal_id,task_internal_id,created_at) "
                            + "SELECT ?,?,t.internal_id,NOW() FROM tasks t WHERE t.project_id=? AND t.code=?")) {
                for (Object[] row : rows) for (String code : splitLegacyCodes((String) row[2])) {
                    ps.setString(1, (String) row[0]); ps.setLong(2, (Long) row[1]);
                    ps.setString(3, (String) row[0]); ps.setString(4, code);
                    ps.executeUpdate();
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO app_migrations(name,applied_at) VALUES (?,NOW())")) {
            ps.setString(1, LEGACY_RELATIONS);
            ps.executeUpdate();
        }
    }

    private java.util.List<String> splitLegacyCodes(String value) {
        if (value == null || value.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(code -> !code.isEmpty()).distinct().toList();
    }

    /**
     * 补建"业务数据引用了、但 {@code projects} 表里没有"的项目。
     *
     * <p>为什么必须补:多项目改造给项目加了存在性校验
     * ({@code ProjectAccessService#requireMember} 查不到项目就 404「项目不存在或已归档」)。
     * 而老库里的业务数据 {@code project_id} 全是被迁移代码填进去的 {@code P001},
     * 项目表却只新建了 {@code P002} —— 于是<b>数据全部挂在不存在的项目下</b>:
     * 表结构修好之后接口不再 500,改成 404,看板/故事/任务依旧一片空白。
     * 这类"结构对了、数据还是看不见"的断层比缺列更难查,所以判定必须按<b>数据实际引用了什么</b>,
     * 而不是靠硬编码某个项目号。
     *
     * <p>补的是项目行 + 全员成员关系(角色沿用 users.role):少了成员关系,
     * 下一步 {@code requireMember} 会 403「无权访问该项目」。
     */
    private Set<String> ensureReferencedProjectsExist(Connection conn) throws SQLException {
        if (!tableExists(conn, "projects") || !tableExists(conn, "users")) {
            return Set.of();
        }
        Set<String> existing = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT `id` FROM `projects`")) {
            while (rs.next()) {
                existing.add(rs.getString(1));
            }
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (String table : tablesWithColumn(conn, "project_id")) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT DISTINCT `project_id` FROM `" + table + "` "
                         + "WHERE `project_id` IS NOT NULL AND `project_id` <> ''")) {
                while (rs.next()) {
                    referenced.add(rs.getString(1));
                }
            }
        }
        Set<String> added = new HashSet<>();
        for (String projectId : referenced) {
            if (existing.contains(projectId)) {
                continue;
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO `projects` "
                    + "(`id`,`name`,`description`,`status`,`total_sprints`,`created_by`,`created_at`,`updated_at`) "
                    + "SELECT ?,?,?,'ACTIVE',0,MIN(`id`),NOW(),NOW() FROM `users`")) {
                ps.setString(1, projectId);
                ps.setString(2, "爱管理·" + projectId);
                ps.setString(3, "存量数据所在项目(迁移自动补建)");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO `project_members` "
                    + "(`project_id`,`user_id`,`project_role`,`capacity_hours`,`joined_at`) "
                    + "SELECT ?,`id`,CASE WHEN `role` IN ('admin','owner') THEN `role` WHEN `role`='viewer' THEN 'viewer' "
                    + "ELSE 'member' END,`capacity_hours`,NOW() FROM `users`")) {
                ps.setString(1, projectId);
                ps.executeUpdate();
            }
            added.add("projects." + projectId);
            log.info("业务数据引用的项目 {} 在 projects 表中缺失,已补建项目与全员成员", projectId);
        }
        return added;
    }

    /** 当前库里所有含指定列的表名(用于按数据判定,不硬编码表清单) */
    private List<String> tablesWithColumn(Connection conn, String column) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT `TABLE_NAME` FROM `information_schema`.`COLUMNS` "
                + "WHERE `TABLE_SCHEMA` = ? AND `COLUMN_NAME` = ?")) {
            ps.setString(1, conn.getCatalog());
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    /**
     * 多项目改造的<b>主体</b>:把存量业务表从「业务编号 varchar 主键」切到
     * 「{@code internal_id} 自增主键 + {@code code} 业务编号」。
     *
     * <p>为什么必须有这段代码:schema.sql 只管"表不存在时建表",
     * 于是<b>新库是对的、老库永远停在旧主键</b> —— 实体({@code Story}/{@code Task}/{@code Milestone})
     * 的 {@code @TableId} 已经改成 {@code internal_id},老库上任何一次
     * {@code SELECT internal_id ...} 都会抛
     * {@code Unknown column 'internal_id' in 'field list'},表现为看板/故事/任务全 500。
     * 而 {@link #migrateScopedBusinessTable} 只补 {@code project_id},看到缺 internal_id 时
     * 按旧结构处理就返回了 —— 启动日志因此打印"列集合已是最新",实际什么都没做。
     *
     * <p>三步的顺序不能换:
     * <ol>
     *   <li>先解绑外键 —— 只要还有 {@code tasks.kanban_card_id → stories.id} 这类约束,
     *       {@code DROP PRIMARY KEY} 直接 errno 150 失败;</li>
     *   <li>再换主键 —— {@code internal_id} 必须带 AUTO_INCREMENT 且是键;</li>
     *   <li>最后 {@code code} —— 值从老主键 {@code id} 复制,保持 US01/T01 这类业务编号不变。</li>
     * </ol>
     *
     * <p>可重复执行:每步都按"列/索引当前是否存在"判定,已切过的表第一次判定即返回。
     */
    private Set<String> migrateInternalKeys(Connection conn) throws SQLException {
        Set<String> added = new HashSet<>();
        for (String[] spec : INTERNAL_KEY_TABLES) {
            added.addAll(toInternalKey(conn, spec[0], spec[1]));
        }
        return added;
    }

    /** 单表切换(见 {@link #migrateInternalKeys});返回本次实际变更项 */
    private Set<String> toInternalKey(Connection conn, String table, String uniqueName) throws SQLException {
        if (!tableExists(conn, table)) {
            return Set.of();
        }
        Set<String> added = new HashSet<>();
        Set<String> cols = columns(conn, table);
        if (cols.contains("internal_id") && cols.contains("code")) {
            if (!indexColumns(conn, table, uniqueName).equals(Set.of("project_id", "code"))) {
                if (indexExists(conn, table, uniqueName)) {
                    execute(conn, "ALTER TABLE `" + table + "` DROP INDEX `" + uniqueName + "`");
                }
                execute(conn, "ALTER TABLE `" + table + "` ADD UNIQUE KEY `" + uniqueName + "` (`project_id`,`code`)");
                added.add(table + "." + uniqueName);
            }
            return added;
        }
        boolean hasLegacyId = cols.contains("id");
        if (!cols.contains("project_id")) {
            execute(conn, "ALTER TABLE `" + table + "` ADD COLUMN `project_id` varchar(20) COLLATE utf8mb4_unicode_ci NULL");
            added.add(table + ".project_id");
        }
        execute(conn, "UPDATE `" + table + "` SET `project_id`='P001' WHERE `project_id` IS NULL OR `project_id`=''");

        // 1) 解绑:任何指向旧主键的外键都会让下一步失败
        dropForeignKeysReferencing(conn, table);
        // 2) 换主键
        if (hasLegacyId && hasPrimaryKeyOn(conn, table, "id")) {
            execute(conn, "ALTER TABLE `" + table + "` DROP PRIMARY KEY");
        }
        if (!cols.contains("internal_id")) {
            execute(conn, "ALTER TABLE `" + table + "` ADD COLUMN `internal_id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST");
            added.add(table + ".internal_id");
            log.info("{}.internal_id 缺失,已切换为自增主键", table);
        }
        // 3) 业务编号:值取老主键,保证对外编号(US01/T01/MS01)一个不变
        if (!cols.contains("code")) {
            execute(conn, "ALTER TABLE `" + table + "` ADD COLUMN `code` varchar(20) COLLATE utf8mb4_unicode_ci NULL");
            if (hasLegacyId) {
                execute(conn, "UPDATE `" + table + "` SET `code`=`id` WHERE `code` IS NULL OR `code`=''");
            } else {
                execute(conn, "UPDATE `" + table + "` SET `code`=CONCAT('L',`internal_id`) WHERE `code` IS NULL OR `code`=''");
            }
            execute(conn, "ALTER TABLE `" + table + "` MODIFY COLUMN `code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL");
            added.add(table + ".code");
            log.info("{}.code 缺失,已按老主键回填业务编号", table);
        }
        // 老主键列降级为普通可空列:实体已无 id 字段,留 NOT NULL 会让后续插入报
        // "Field 'id' doesn't have a default value" —— 那比缺列更难查
        if (hasLegacyId) {
            execute(conn, "ALTER TABLE `" + table + "` MODIFY COLUMN `id` varchar(20) COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL");
        }
        // 唯一键从 (project_id,id) 改成 (project_id,code):编号唯一性从此按项目内 code 判定
        if (!indexColumns(conn, table, uniqueName).equals(Set.of("project_id", "code"))) {
            if (indexExists(conn, table, uniqueName)) {
                execute(conn, "ALTER TABLE `" + table + "` DROP INDEX `" + uniqueName + "`");
            }
            execute(conn, "ALTER TABLE `" + table + "` ADD UNIQUE KEY `" + uniqueName + "` (`project_id`,`code`)");
            added.add(table + "." + uniqueName);
        }
        return added;
    }

    /**
     * 回填 tasks 侧对故事的引用,以及三个 {@code *_legacy} 列(保留切换前的旧值)。
     *
     * <p>{@code story_ref} 是<b>逗号分隔的多值</b>(如 {@code US01,US03,US04}),新结构把它拆成两处:
     * 第一个编号 → {@code tasks.story_internal_id}(主故事),其余 → {@code task_story_relations}
     * (附加关系,见 {@code TaskStoryRelationService})。只按整个字符串去 JOIN stories 是<b>匹配不上</b>的,
     * 多值任务会静默丢掉全部故事关联 —— 这是"看起来迁移完了、关联全没了"那类缺陷。
     *
     * <p>判定走"数据当前缺什么",不是"本次是否刚补上列"(DDL 自动提交,补列与回填之间被打断时
     * 下次仍能接着填,理由同 {@link #backfillAclRank})。
     */
    private Set<String> backfillInternalRefs(Connection conn) throws SQLException {
        if (!tableExists(conn, "tasks") || !tableExists(conn, "stories")) {
            return Set.of();
        }
        Set<String> cols = columns(conn, "tasks");
        Set<String> added = new HashSet<>();
        if (!cols.contains("story_internal_id") || !cols.contains("story_ref")) {
            return Set.of();
        }
        if (!columns(conn, "stories").contains("internal_id")) {
            return Set.of();
        }
        if (tableExists(conn, "task_story_relations") && needsTaskStoryBackfill(conn)) {
            int[] counts = backfillTaskStoryLinks(conn);
            if (counts[0] > 0) {
                added.add("tasks.story_internal_id(回填 " + counts[0] + " 行)");
                log.info("tasks.story_internal_id 已按 story_ref 首编号回填 {} 行", counts[0]);
            }
            if (counts[1] > 0) {
                added.add("task_story_relations(回填 " + counts[1] + " 条附加关联)");
                log.info("task_story_relations 已按 story_ref 余下编号回填 {} 条", counts[1]);
            }
        }
        // 未匹配上的引用留 NULL 不报错:它只影响"任务→故事"的新关联查询,不影响编号读写
        if (cols.contains("story_ref_legacy")) {
            execute(conn, "UPDATE `tasks` SET `story_ref_legacy`=`story_ref` WHERE `story_ref_legacy` IS NULL AND `story_ref` IS NOT NULL");
        }
        if (cols.contains("kanban_card_id_legacy")) {
            execute(conn, "UPDATE `tasks` SET `kanban_card_id_legacy`=`kanban_card_id` WHERE `kanban_card_id_legacy` IS NULL AND `kanban_card_id` IS NOT NULL");
        }
        if (cols.contains("depends_on_legacy")) {
            execute(conn, "UPDATE `tasks` SET `depends_on_legacy`=`depends_on` WHERE `depends_on_legacy` IS NULL AND `depends_on` IS NOT NULL");
        }
        return added;
    }

    /** 补列 + 回填"引用快照":story_logs / activity_records / difficulty_assessments */
    private Set<String> backfillReferenceSnapshots(Connection conn) throws SQLException {
        Set<String> added = new HashSet<>();
        if (tableExists(conn, "story_logs")) {
            added.addAll(addMissing(conn, "story_logs", STORY_LOG_COLUMNS));
            added.addAll(snapshotBackfill(conn, "story_logs", "stories", "story_id",
                    "story_internal_id", "story_code_snapshot"));
        }
        if (tableExists(conn, "activity_records")) {
            added.addAll(addMissing(conn, "activity_records", ACTIVITY_RECORD_COLUMNS));
            added.addAll(snapshotBackfill(conn, "activity_records", "tasks", "task_id",
                    "task_internal_id", "task_code_snapshot"));
        }
        if (tableExists(conn, "difficulty_assessments")) {
            added.addAll(addMissing(conn, "difficulty_assessments", DIFFICULTY_ASSESSMENT_COLUMNS));
            added.addAll(snapshotBackfill(conn, "difficulty_assessments", "tasks", "task_id",
                    "task_internal_id", "task_code_snapshot"));
        }
        return added;
    }

    /**
     * 按业务编号回填 internal_id,并把编号原样写进快照列。
     *
     * <p>快照<b>先落、且无条件</b>:编号快照的意义就是"目标被删了也认得出来",
     * 所以哪怕 JOIN 不到 internal_id(故事/任务已删),编号也要先留下来。
     */
    private Set<String> snapshotBackfill(Connection conn, String table, String refTable, String codeColumn,
                                         String internalColumn, String snapshotColumn) throws SQLException {
        if (!tableExists(conn, refTable)) {
            return Set.of();
        }
        Set<String> cols = columns(conn, table);
        if (!cols.contains("project_id") || !cols.contains(codeColumn)
                || !cols.contains(internalColumn) || !cols.contains(snapshotColumn)) {
            return Set.of();
        }
        Set<String> refCols = columns(conn, refTable);
        if (!refCols.contains("internal_id") || !refCols.contains("code")) {
            return Set.of();
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM `" + table + "` WHERE `" + internalColumn + "` IS NULL "
                     + "AND `" + codeColumn + "` IS NOT NULL AND `" + codeColumn + "` <> '' LIMIT 1")) {
            if (!rs.next()) {
                return Set.of();
            }
        }
        execute(conn, "UPDATE `" + table + "` SET `" + snapshotColumn + "` = `" + codeColumn + "` "
                + "WHERE `" + snapshotColumn + "` IS NULL AND `" + codeColumn + "` IS NOT NULL AND `" + codeColumn + "` <> ''");
        int rows;
        try (Statement st = conn.createStatement()) {
            rows = st.executeUpdate("UPDATE `" + table + "` t JOIN `" + refTable + "` r "
                    + "ON r.`project_id` = t.`project_id` AND r.`code` = t.`" + codeColumn + "` "
                    + "SET t.`" + internalColumn + "` = r.`internal_id` "
                    + "WHERE t.`" + internalColumn + "` IS NULL");
        }
        return rows > 0 ? Set.of(table + "." + internalColumn) : Set.of();
    }

    /** 是否还有"story_ref 有值但主故事/附加关系没落库"的任务 —— 回填的唯一依据 */
    private boolean needsTaskStoryBackfill(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM `tasks` t WHERE t.`story_ref` IS NOT NULL "
                     + "AND t.`story_ref` <> '' AND (t.`story_internal_id` IS NULL "
                     + "OR (t.`story_ref` LIKE '%,%' AND NOT EXISTS (SELECT 1 FROM `task_story_relations` r "
                     + "WHERE r.`task_internal_id` = t.`internal_id`))) LIMIT 1")) {
            return rs.next();
        }
    }

    /** 拆 story_ref 回填主故事与附加关系;返回 {主故事行数, 附加关系条数} */
    private int[] backfillTaskStoryLinks(Connection conn) throws SQLException {
        int primary = 0;
        int extra = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT `internal_id`,`project_id`,`story_ref` FROM `tasks` "
                        + "WHERE `story_ref` IS NOT NULL AND `story_ref` <> ''")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long taskInternalId = rs.getLong(1);
                    String projectId = rs.getString(2);
                    List<String> codes = parseRefs(rs.getString(3));
                    if (codes.isEmpty()) {
                        continue;
                    }
                    Long primaryId = storyInternalId(conn, projectId, codes.get(0));
                    if (primaryId != null && setStoryInternalId(conn, taskInternalId, primaryId)) {
                        primary++;
                    }
                    for (String code : codes.subList(1, codes.size())) {
                        Long storyId = storyInternalId(conn, projectId, code);
                        if (storyId != null && insertRelation(conn, projectId, taskInternalId, storyId)) {
                            extra++;
                        }
                    }
                }
            }
        }
        return new int[]{primary, extra};
    }

    /** 逗号分隔的业务编号 → 去空白后的编号列表 */
    private List<String> parseRefs(String value) {
        List<String> codes = new ArrayList<>();
        if (value == null) {
            return codes;
        }
        for (String part : value.split(",")) {
            String code = part.trim();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }

    private Long storyInternalId(Connection conn, String projectId, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT `internal_id` FROM `stories` WHERE `project_id` = ? AND `code` = ? LIMIT 1")) {
            ps.setString(1, projectId);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private boolean setStoryInternalId(Connection conn, long taskInternalId, long storyId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE `tasks` SET `story_internal_id` = ? WHERE `internal_id` = ? AND `story_internal_id` IS NULL")) {
            ps.setLong(1, storyId);
            ps.setLong(2, taskInternalId);
            return ps.executeUpdate() > 0;
        }
    }

    /** 附加关系;UNIQUE(project_id,task,story) 已存在时 INSERT IGNORE 直接跳过(幂等) */
    private boolean insertRelation(Connection conn, String projectId, long taskInternalId, long storyId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO `task_story_relations` (`project_id`,`task_internal_id`,`story_internal_id`,`created_at`,`updated_at`) "
                        + "VALUES (?,?,?,NOW(),NOW())")) {
            ps.setString(1, projectId);
            ps.setLong(2, taskInternalId);
            ps.setLong(3, storyId);
            return ps.executeUpdate() > 0;
        }
    }

    private Set<String> backfillMilestoneLegacy(Connection conn) throws SQLException {
        Set<String> cols = columns(conn, "milestones");
        if (!cols.contains("related_task_ids_legacy") || !cols.contains("related_task_ids")) {
            return Set.of();
        }
        int n;
        try (Statement st = conn.createStatement()) {
            n = st.executeUpdate("UPDATE `milestones` SET `related_task_ids_legacy`=`related_task_ids` "
                    + "WHERE `related_task_ids_legacy` IS NULL AND `related_task_ids` IS NOT NULL");
        }
        return n > 0 ? Set.of("milestones.related_task_ids_legacy") : Set.of();
    }

    /** 索引当前包含的列名(小写);索引不存在返回空集 —— 用于判定"名字在但列变了" */
    private Set<String> indexColumns(Connection conn, String table, String indexName) throws SQLException {
        Set<String> cols = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SHOW INDEX FROM `" + table + "` WHERE `Key_name` = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String column = rs.getString("Column_name");
                    if (column != null) {
                        cols.add(column.toLowerCase());
                    }
                }
            }
        }
        return cols;
    }

    private void addProjectColumn(Connection conn, String table) throws SQLException {
        if (!tableExists(conn, table)) return;
        if (!columns(conn, table).contains("project_id")) execute(conn, "ALTER TABLE `"+table+"` ADD COLUMN project_id varchar(20) NULL");
        execute(conn, "UPDATE `"+table+"` SET project_id='P001' WHERE project_id IS NULL OR project_id=''");
        if (!indexExists(conn, table, "ix_"+table+"_project")) execute(conn, "ALTER TABLE `"+table+"` ADD KEY `ix_"+table+"_project` (project_id)");
    }

    private void ensureTaskStoryRelations(Connection conn) throws SQLException {
        execute(conn, "CREATE TABLE IF NOT EXISTS task_story_relations (internal_id bigint NOT NULL AUTO_INCREMENT, project_id varchar(20) NOT NULL, task_internal_id bigint NOT NULL, story_internal_id bigint NOT NULL, created_at datetime NOT NULL, updated_at datetime NOT NULL, PRIMARY KEY (internal_id), UNIQUE KEY uq_task_story_relations (project_id,task_internal_id,story_internal_id), KEY ix_task_story_relations_project (project_id), KEY ix_task_story_relations_task (task_internal_id), KEY ix_task_story_relations_story (story_internal_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }

    private void addProjectColumnWithDefault(Connection conn, String table, String defaultProject) throws SQLException {
        if (!tableExists(conn, table)) return;
        if (!columns(conn, table).contains("project_id")) execute(conn, "ALTER TABLE `"+table+"` ADD COLUMN project_id varchar(20) NULL");
        execute(conn, "UPDATE `"+table+"` SET project_id='"+defaultProject+"' WHERE project_id IS NULL OR project_id='' ");
        if (!indexExists(conn, table, "ix_"+table+"_project")) execute(conn, "ALTER TABLE `"+table+"` ADD KEY `ix_"+table+"_project` (project_id)");
        execute(conn, "ALTER TABLE `"+table+"` MODIFY COLUMN project_id varchar(20) NOT NULL DEFAULT '"+defaultProject+"'");
    }

    private void dropForeignKeysReferencing(Connection conn, String referencedTable) throws SQLException {
        String sql = "SELECT TABLE_NAME,CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_SCHEMA=? AND REFERENCED_TABLE_NAME=?";
        try (PreparedStatement ps=conn.prepareStatement(sql)) {
            ps.setString(1, conn.getCatalog()); ps.setString(2, referencedTable);
            try (ResultSet rs=ps.executeQuery()) {
                java.util.List<String[]> rows=new java.util.ArrayList<>();
                while(rs.next()) rows.add(new String[]{rs.getString(1),rs.getString(2)});
                for(String[] row:rows) execute(conn,"ALTER TABLE `"+row[0]+"` DROP FOREIGN KEY `"+row[1]+"`");
            }
        }
    }

    private boolean hasPrimaryKeyOn(Connection conn,String table,String column) throws SQLException {
        try(ResultSet rs=conn.getMetaData().getPrimaryKeys(conn.getCatalog(),null,table)) {
            while(rs.next()) if(column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
        }
        return false;
    }

    /**
     * 关键词检索路依赖的 ngram 全文索引(S2,设计文档 A5)。
     *
     * <p>为什么必须在这里补:{@code CREATE TABLE IF NOT EXISTS} 只在建表时生效,
     * 对<b>已经存在</b>的 {@code knowledge_chunks} 表加索引它不会做任何事 ——
     * 于是老库上关键词路会在第一次查询时抛 "Can't find FULLTEXT index matching the
     * column list",而新库一切正常。这类"只有老库坏"的缺陷最容易漏掉。
     *
     * <p>索引存在性靠 {@code SHOW INDEX} 判定:MySQL 的 {@code CREATE INDEX}
     * <b>没有</b> {@code IF NOT EXISTS},不先查就重跑必然报 Duplicate key name,
     * 而本组件每次启动都会跑。
     *
     * @return 本次真正新建的索引(空集 = 已存在)
     */
    private Set<String> ensureFulltextIndex(Connection conn) throws SQLException {
        if (indexExists(conn, "knowledge_chunks", "ft_content")) {
            return Set.of();
        }
        execute(conn, "ALTER TABLE `knowledge_chunks` ADD FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram");
        log.info("knowledge_chunks.ft_content 缺失,已补建 ngram 全文索引");
        return Set.of("knowledge_chunks.ft_content");
    }

    /** 队列表补 (status, created_at) 复合索引;CREATE INDEX 无 IF NOT EXISTS,故先查存在性 */
    private Set<String> ensureQueueIndex(Connection conn, String table, String indexName) throws SQLException {
        if (indexExists(conn, table, indexName)) {
            return Set.of();
        }
        execute(conn, "ALTER TABLE `" + table + "` ADD KEY `" + indexName + "` (`status`, `created_at`)");
        log.info("{}.{} 缺失,已补建 (status, created_at) 索引", table, indexName);
        return Set.of(table + "." + indexName);
    }

    /**
     * 用 {@code acl_role} 回填为 NULL 的 {@code acl_rank}。
     *
     * <p>该不该跑由 {@link #hasNullAclRank} 按<b>数据</b>判定,而不是"本次是否刚补上该列":
     * MySQL 的 DDL 自动提交,若 ALTER 成功而随后这次 UPDATE 没提交(进程被杀 / UPDATE 超时 /
     * 连接断),下次启动时列已存在 —— 按"刚补列"判定就永远不会再回填,而全为 NULL 的
     * {@code acl_rank} 会让<b>检索对所有人返回空,且不报错</b>({@code NULL <= x} 不成立)。
     * 按数据判定自带自愈:下面的 UPDATE 幂等({@code WHERE acl_rank IS NULL})。
     * 这不是假想:把列手工补上、留 NULL、再启动,旧判定确实一行都没回填。
     *
     * <p>不回填的后果不是"少个字段"而是<b>什么都搜不到</b>。
     *
     * <p>下面 CASE 里的 {@code 'member'/'owner'/'admin'} 是<b>迁移快照</b>,不是第二份
     * ladder:它只对存量数据跑一次,跑完即失效,此后新增的块一律由 {@code KnowledgeIndexer}
     * 调 {@link com.aicap.rag.RetrievalContext#rankOf} 写入 —— 所以不存在"改了 Java 的
     * ladder 却忘了改这里"的漂移。{@code ELSE 3} 与 {@code rankOf} 的
     * "未识别角色按最高要求处理"一致:认不出的角色宁可不给看,也不能给所有人看。
     */
    private void backfillAclRank(Connection conn) throws SQLException {
        execute(conn, "UPDATE `knowledge_chunks` SET `acl_rank` = CASE `acl_role` "
                + "WHEN 'member' THEN 1 WHEN 'owner' THEN 2 WHEN 'admin' THEN 3 ELSE 3 END "
                + "WHERE `acl_rank` IS NULL");
        log.info("knowledge_chunks.acl_rank 已按 acl_role 回填");
    }

    /**
     * 是否还有 {@code acl_rank} 为 NULL 的块 —— 判定回填的唯一依据(见 {@link #backfillAclRank})。
     *
     * <p>{@code LIMIT 1} 让它在最坏情况下也只扫到第一行;相比"整库静默搜不到东西",
     * 每次启动多这一次查询是完全划算的。
     */
    private boolean hasNullAclRank(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM `knowledge_chunks` WHERE `acl_rank` IS NULL LIMIT 1")) {
            return rs.next();
        }
    }

    private boolean indexExists(Connection conn, String table, String indexName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getIndexInfo(conn.getCatalog(), null, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && indexName.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 返回本次新增的 "表.列" 集合 */
    private Set<String> addMissing(Connection conn, String table, String[][] wanted) throws SQLException {
        Set<String> existing = columns(conn, table);
        Set<String> added = new HashSet<>();
        for (String[] column : wanted) {
            if (existing.contains(column[0])) {
                continue;
            }
            execute(conn, "ALTER TABLE `" + table + "` ADD COLUMN " + column[1]);
            added.add(table + "." + column[0]);
            log.info("{}.{} 缺失,已补列", table, column[0]);
        }
        return added;
    }

    private Set<String> columns(Connection conn, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, null)) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
        }
        return names;
    }

    private boolean tableExists(Connection conn, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private boolean isNullable(Connection conn, String table, String column) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, column)) {
            return rs.next() && "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
        }
    }

    private void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
