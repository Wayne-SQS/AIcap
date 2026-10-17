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
            {"capacity_hours", "`capacity_hours` int DEFAULT NULL COMMENT '每周可用容量(人时);NULL 表示未配置'"},
    };
    private static final String[][] STORY_COLUMNS = {
            {"story_role", "`story_role` varchar(200) NOT NULL DEFAULT ''"},
            {"story_goal", "`story_goal` varchar(500) NOT NULL DEFAULT ''"},
            {"story_value", "`story_value` varchar(500) NOT NULL DEFAULT ''"},
            {"epic_id", "`epic_id` bigint DEFAULT NULL"},
    };

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            Set<String> added = new HashSet<>();
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
                migrateWeeklyCapacity(conn);
            }
            if (tableExists(conn, "stories")) {
                added.addAll(addMissing(conn, "stories", STORY_COLUMNS));
            }
            for (String table : new String[]{"stories", "tasks", "pool_items", "milestones", "story_logs", "task_logs"}) {
                if (tableExists(conn, table)) {
                    added.addAll(addMissing(conn, table, new String[][]{
                            {"project_id", "`project_id` bigint NOT NULL DEFAULT 1"}
                    }));
                }
            }
            execute(conn, "CREATE TABLE IF NOT EXISTS roles (name varchar(50) NOT NULL PRIMARY KEY, can_write tinyint(1) NOT NULL DEFAULT 0, can_manage tinyint(1) NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            execute(conn, "CREATE TABLE IF NOT EXISTS task_logs (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, task_id varchar(10) NOT NULL, old_status int, new_status int NOT NULL, user_id int NOT NULL, created_at datetime NOT NULL, KEY ix_task_logs_task (task_id,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            execute(conn, "CREATE TABLE IF NOT EXISTS planning_agent_runs (id varchar(36) NOT NULL, requested_by int NOT NULL, request_text varchar(2000) NOT NULL, status varchar(30) NOT NULL, attempt int NOT NULL, model varchar(200) NOT NULL, prompt_version varchar(40) NOT NULL, context_json longtext, result_json longtext, error_code varchar(60), error_message varchar(500), created_at datetime NOT NULL, updated_at datetime NOT NULL, PRIMARY KEY (id), KEY ix_planning_agent_runs_status (status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            execute(conn, "CREATE TABLE IF NOT EXISTS planning_agent_events (id int NOT NULL AUTO_INCREMENT, run_id varchar(36) NOT NULL, attempt int NOT NULL, kind varchar(40) NOT NULL, detail_json longtext NOT NULL, created_at datetime(6) NOT NULL, PRIMARY KEY (id), KEY ix_planning_agent_events_run_id (run_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            execute(conn, "CREATE TABLE IF NOT EXISTS project_generation_runs (id varchar(36) NOT NULL, requested_by int NOT NULL, mode varchar(30) NOT NULL, strategy varchar(20) NOT NULL, source_name varchar(255), request_text varchar(4000), status varchar(30) NOT NULL, attempt int NOT NULL, model varchar(200) NOT NULL, prompt_version varchar(40) NOT NULL, draft_json longtext, error_code varchar(60), error_message varchar(500), created_at datetime NOT NULL, updated_at datetime NOT NULL, PRIMARY KEY (id), KEY ix_project_generation_runs_status (status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            execute(conn, "CREATE TABLE IF NOT EXISTS project_generation_events (id int NOT NULL AUTO_INCREMENT, run_id varchar(36) NOT NULL, attempt int NOT NULL, kind varchar(40) NOT NULL, detail_json longtext NOT NULL, created_at datetime(6) NOT NULL, PRIMARY KEY (id), KEY ix_project_generation_events_run_id (run_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            execute(conn, "ALTER TABLE planning_agent_events MODIFY COLUMN created_at datetime(6) NOT NULL");
            execute(conn, "ALTER TABLE project_generation_events MODIFY COLUMN created_at datetime(6) NOT NULL");
            execute(conn, "CREATE TABLE IF NOT EXISTS milestones (id varchar(20) NOT NULL, name varchar(200) NOT NULL, week int NOT NULL, description varchar(500) NOT NULL DEFAULT '', status varchar(20) NOT NULL DEFAULT 'planned', related_task_ids varchar(500), PRIMARY KEY (id), KEY ix_milestones_week (week)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // schema.sql creates these tables on fresh databases. This also repairs older databases.
            execute(conn, "CREATE TABLE IF NOT EXISTS projects (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, name varchar(200) NOT NULL, description varchar(1000) NOT NULL DEFAULT '', created_by int, created_at datetime NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            execute(conn, "CREATE TABLE IF NOT EXISTS epics (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, project_id bigint NOT NULL, name varchar(100) NOT NULL, description varchar(500) NOT NULL DEFAULT '', created_at datetime NOT NULL, UNIQUE KEY ux_epics_project_name(project_id,name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            execute(conn, "CREATE TABLE IF NOT EXISTS project_members (project_id bigint NOT NULL, user_id int NOT NULL, member_role varchar(20) NOT NULL DEFAULT 'member', PRIMARY KEY(project_id,user_id), KEY ix_project_members_user(user_id), CONSTRAINT fk_project_members_project FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE, CONSTRAINT fk_project_members_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            try (PreparedStatement count = conn.prepareStatement("SELECT COUNT(*) FROM projects"); ResultSet rows = count.executeQuery()) {
                rows.next();
                if (rows.getInt(1) == 0) {
                    execute(conn, "INSERT INTO projects(id,name,description,created_at) VALUES (1,'爱管理','原有项目工作区',NOW())");
                    execute(conn, "INSERT INTO project_members(project_id,user_id,member_role) SELECT 1,id,CASE WHEN role IN ('admin','owner') THEN 'owner' ELSE 'member' END FROM users");
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

    /** 旧库的 capacity_hours 是六周总量；列仍为 NOT NULL 时仅转换一次。 */
    private void migrateWeeklyCapacity(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, "users", "capacity_hours")) {
            if (!rs.next() || !"NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"))) return;
        }
        execute(conn, "CREATE TABLE IF NOT EXISTS schema_migrations (name varchar(100) NOT NULL PRIMARY KEY, applied_at datetime NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        int converted;
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT COUNT(*) FROM schema_migrations WHERE name='weekly_capacity_hours_v1'");
             ResultSet rows = check.executeQuery()) {
            rows.next();
            converted = rows.getInt(1);
        }
        if (converted == 0) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                execute(conn, "UPDATE users SET capacity_hours=CEIL(capacity_hours/6.0)");
                execute(conn, "INSERT INTO schema_migrations(name,applied_at) VALUES ('weekly_capacity_hours_v1',NOW())");
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
        execute(conn, "ALTER TABLE users MODIFY COLUMN capacity_hours int DEFAULT NULL COMMENT '每周可用容量(人时);NULL 表示未配置'");
        log.info("users.capacity_hours 已由六周总量迁移为每周人时,并允许未配置(NULL)");
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
