package com.aicap.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StoryLogMigrationTest {
    @Test
    void upgradesLegacyLogsWithoutLosingHistoryAndCanRunTwice() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        var upgrader = new SchemaUpgrader(source);
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE story_logs (id int primary key, project_id varchar(20), story_id varchar(10), detail varchar(500))");
            statement.execute("INSERT INTO story_logs VALUES (1, 'P002', 'US01', 'historical audit')");
            assertEquals(Set.of("story_logs.story_internal_id", "story_logs.story_code_snapshot"),
                    upgrader.migrateStoryLogs(connection));
            try (var rows = statement.executeQuery("SELECT story_id, detail, story_internal_id, story_code_snapshot FROM story_logs WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals("US01", rows.getString("story_id"));
                assertEquals("historical audit", rows.getString("detail"));
                assertNull(rows.getObject("story_internal_id"));
                assertNull(rows.getString("story_code_snapshot"));
            }
            assertTrue(upgrader.migrateStoryLogs(connection).isEmpty());
        }
    }

    @Test
    void widensDetailSoWholeApprovedJsonFitsAndStaysIdempotent() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        var upgrader = new SchemaUpgrader(source);
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_migrations (name varchar(100) primary key, applied_at datetime not null)");
            statement.execute("CREATE TABLE story_logs (id int primary key, project_id varchar(20), story_id varchar(10), "
                    + "detail varchar(500) NOT NULL)");
            statement.execute("INSERT INTO story_logs VALUES (1, 'P001', 'US01', 'historical audit')");
            assertEquals(Set.of("story_logs.detail"), upgrader.migrateStoryLogDetailText(connection));
            // 迁移前的 varchar(500) 装不下整份批准 JSON;升级后同样的超长内容必须完整落库
            String wide = "批".repeat(1200);
            statement.execute("UPDATE story_logs SET detail='" + wide + "' WHERE id=1");
            try (var rows = statement.executeQuery("SELECT detail FROM story_logs WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals(wide, rows.getString(1));
            }
            assertTrue(upgrader.migrateStoryLogDetailText(connection).isEmpty());
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM app_migrations WHERE name='V15_STORY_LOG_DETAIL_TO_TEXT'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void completesPartiallyUpgradedSchemaWithoutChangingExistingValues() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        var upgrader = new SchemaUpgrader(source);
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE story_logs (id int primary key, story_internal_id bigint)");
            statement.execute("INSERT INTO story_logs VALUES (1, 42)");
            assertEquals(Set.of("story_logs.story_code_snapshot"), upgrader.migrateStoryLogs(connection));
            try (var rows = statement.executeQuery("SELECT story_internal_id FROM story_logs WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals(42L, rows.getLong(1));
            }
        }
    }
}
