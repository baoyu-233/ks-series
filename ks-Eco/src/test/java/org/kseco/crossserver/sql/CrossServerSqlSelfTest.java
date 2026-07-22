package org.kseco.crossserver.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Smoke test for every supported cross-server SQL dialect. */
public final class CrossServerSqlSelfTest {
    @Test
    void validatesGeneratedSqlForEveryDialect() {
        check(SqlDialect.fromJdbcUrl("jdbc:sqlite:data.db") == SqlDialect.SQLITE, "SQLite JDBC detection");
        check(SqlDialect.fromJdbcUrl("jdbc:mysql://db/eco") == SqlDialect.MYSQL, "MySQL JDBC detection");
        check(SqlDialect.fromJdbcUrl("jdbc:postgresql://db/eco") == SqlDialect.POSTGRESQL, "PostgreSQL JDBC detection");

        expectRejected("events; DROP TABLE users");
        expectRejected("schema..events");
        expectRejected("事件");

        for (SqlDialect dialect : SqlDialect.values()) {
            CrossServerSql sql = new CrossServerSql(dialect, CrossServerTables.defaults());
            check(sql.createSchemaStatements().size() == 4, dialect + " schema count");
            check(sql.createOutboxTableSql().contains("event_type"), dialect + " event type");
            check(sql.createOutboxTableSql().contains("source_instance_id"), dialect + " source instance");
            check(sql.createOutboxTableSql().contains("schema_version"), dialect + " schema version");
            check(count(sql.insertOutboxIfAbsentSql(), '?') == CrossServerSql.OUTBOX_COLUMNS.size(),
                    dialect + " outbox parameter count");
            check(count(sql.insertInboxIfAbsentSql(), '?') == CrossServerSql.INBOX_COLUMNS.size(),
                    dialect + " inbox parameter count");
            check(count(sql.upsertHeartbeatSql(), '?') == CrossServerSql.HEARTBEAT_COLUMNS.size(),
                    dialect + " heartbeat parameter count");
            check(count(sql.renewLeaseSql(), '?') == 6, dialect + " lease renewal guard");
            check(count(sql.releaseLeaseSql(), '?') == 5, dialect + " lease release guard");
            check(sql.releaseLeaseSql().contains("+ 1"), dialect + " lease release must invalidate stale tokens");
            check(count(sql.markInboxFailedSql(), '?') == 6, dialect + " inbox retry fencing");
            check(count(sql.markOutboxPublishedSql(), '?') == 4, dialect + " outbox attempt fencing");
            check(sql.pollAvailableOutboxSql().contains("ORDER BY"), dialect + " stable outbox order");
        }

        String sqliteIgnore = SqlDialect.SQLITE.insertIgnore("events", List.of("id", "payload"), List.of("id"));
        String mysqlIgnore = SqlDialect.MYSQL.insertIgnore("events", List.of("id", "payload"), List.of("id"));
        String postgresIgnore = SqlDialect.POSTGRESQL.insertIgnore("events", List.of("id", "payload"), List.of("id"));
        check(sqliteIgnore.startsWith("INSERT OR IGNORE"), "SQLite insert-ignore syntax");
        check(mysqlIgnore.contains("ON DUPLICATE KEY UPDATE"), "MySQL duplicate-only ignore syntax");
        check(postgresIgnore.contains("ON CONFLICT (\"id\") DO NOTHING"), "PostgreSQL insert-ignore syntax");
    }

    private static void expectRejected(String identifier) {
        try {
            SqlIdentifiers.requireQualified(identifier);
            throw new AssertionError("Identifier should have been rejected: " + identifier);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static int count(String value, char needle) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == needle) {
                count++;
            }
        }
        return count;
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
