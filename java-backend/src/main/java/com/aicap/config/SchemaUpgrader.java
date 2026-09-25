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

    private static final String[][] KNOWLEDGE_CHUNK_COLUMNS = {
            {"embedding_model", "`embedding_model` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL"},
            {"acl_rank", "`acl_rank` int DEFAULT NULL"},
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

    private void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
