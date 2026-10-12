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
import java.util.HashSet;
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
    };

    private static final String[][] USER_COLUMNS = {
            {"capacity_hours", "`capacity_hours` int NOT NULL DEFAULT '60'"},
    };

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            Set<String> added = new HashSet<>();
            migrateProjects(conn);
            ensureTaskStoryRelations(conn);
            if (tableExists(conn, "tasks")) {
                added.addAll(addMissing(conn, "tasks", TASK_COLUMNS));
                if (added.contains("tasks.estimated_hours")) {
                    // 老库补列后 estimated_hours 全为 0,按 hours 回填(对齐 FastAPI 迁移脚本)
                    execute(conn, "UPDATE `tasks` SET `estimated_hours` = `hours` WHERE `estimated_hours` = 0");
                    log.info("tasks.estimated_hours 已按 hours 回填");
                }
            }
            if (tableExists(conn, "users")) {
                added.addAll(addMissing(conn, "users", USER_COLUMNS));
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


    /** 多项目平滑迁移：保留现有正式项目，不再重建已物理删除的历史 P001。 */
    private void migrateProjects(Connection conn) throws SQLException {
        ensureProjectTables(conn);
        execute(conn, "INSERT IGNORE INTO projects(id,name,description,status,total_sprints,created_by,created_at,updated_at) SELECT 'P002','爱管理','正式规划数据待导入','ACTIVE',0,MIN(id),NOW(),NOW() FROM users WHERE NOT EXISTS (SELECT 1 FROM projects WHERE id='P002')");
        execute(conn, "INSERT IGNORE INTO project_members(project_id,user_id,project_role,capacity_hours,joined_at) SELECT 'P002',id,CASE WHEN role IN ('admin','owner') THEN role WHEN role='viewer' THEN 'viewer' ELSE 'member' END,capacity_hours,NOW() FROM users");
        migrateScopedBusinessTable(conn, "stories", "uq_stories_project_code");
        migrateScopedBusinessTable(conn, "tasks", "uq_tasks_project_code");
        migrateScopedBusinessTable(conn, "milestones", "uq_milestones_project_code");
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
    }

    private void ensureProjectTables(Connection conn) throws SQLException {
        execute(conn, "CREATE TABLE IF NOT EXISTS projects (id varchar(20) NOT NULL, name varchar(200) NOT NULL, description text NOT NULL, status varchar(20) NOT NULL DEFAULT 'ACTIVE', start_date date DEFAULT NULL, end_date date DEFAULT NULL, total_sprints int NOT NULL DEFAULT 0, created_by int DEFAULT NULL, created_at datetime NOT NULL, updated_at datetime NOT NULL, PRIMARY KEY (id), KEY ix_projects_status (status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        execute(conn, "CREATE TABLE IF NOT EXISTS project_members (id bigint NOT NULL AUTO_INCREMENT, project_id varchar(20) NOT NULL, user_id int NOT NULL, project_role varchar(20) NOT NULL, capacity_hours int NOT NULL DEFAULT 60, joined_at datetime NOT NULL, PRIMARY KEY (id), UNIQUE KEY uq_project_members_project_user (project_id,user_id), KEY ix_project_members_user (user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
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
            // The formal tables have already been switched to the internal-key schema.
            // Do not run the legacy `id` migration against them on every restart.
            if (!existingColumns.contains("project_id")) {
                throw new SQLException("正式表 " + table + " 缺少 project_id，拒绝按旧结构自动迁移");
            }
            if (!indexExists(conn, table, uniqueName)) {
                execute(conn, "ALTER TABLE `" + table + "` ADD UNIQUE KEY `" + uniqueName + "` (project_id,code)");
            }
            return;
        }
        if (!existingColumns.contains("project_id")) execute(conn, "ALTER TABLE `"+table+"` ADD COLUMN project_id varchar(20) NULL AFTER id");
        execute(conn, "UPDATE `"+table+"` SET project_id='P001' WHERE project_id IS NULL OR project_id=''");
        if (!indexExists(conn, table, uniqueName)) execute(conn, "ALTER TABLE `"+table+"` ADD UNIQUE KEY `"+uniqueName+"` (project_id,id)");
        execute(conn, "ALTER TABLE `"+table+"` MODIFY COLUMN project_id varchar(20) NOT NULL");
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

    private boolean indexExists(Connection conn,String table,String index) throws SQLException {
        try(ResultSet rs=conn.getMetaData().getIndexInfo(conn.getCatalog(),null,table,false,false)) {
            while(rs.next()) if(index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) return true;
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

    private void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
