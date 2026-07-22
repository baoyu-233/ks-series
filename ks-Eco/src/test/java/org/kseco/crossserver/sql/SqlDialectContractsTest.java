package org.kseco.crossserver.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

/** SQL generation checks for every supported database. */
public final class SqlDialectContractsTest {
    @Test
    void detectsSupportedDialects() {
        check(SqlDialect.parse(" sqlite3 ") == SqlDialect.SQLITE, "SQLite alias parsing");
        check(SqlDialect.parse("MariaDB") == SqlDialect.MYSQL, "MariaDB alias parsing");
        check(SqlDialect.parse("PGSQL") == SqlDialect.POSTGRESQL, "PostgreSQL alias parsing");
        check(SqlDialect.fromJdbcUrl(" JDBC:MYSQL://db/eco ") == SqlDialect.MYSQL, "JDBC URL normalization");
        expectFailure(() -> SqlDialect.parse("oracle"));
        expectFailure(() -> SqlDialect.fromJdbcUrl("jdbc:h2:mem:eco"));
    }

    @Test
    void quotesQualifiedIdentifiersPerDialect() {
        check(SqlIdentifiers.quoteQualified(SqlDialect.SQLITE, "eco.events").equals("\"eco\".\"events\""),
                "SQLite identifiers must use double quotes");
        check(SqlIdentifiers.quoteQualified(SqlDialect.MYSQL, "eco.events").equals("`eco`.`events`"),
                "MySQL identifiers must use backticks");
        check(SqlIdentifiers.quoteQualified(SqlDialect.POSTGRESQL, "cluster.eco.events")
                        .equals("\"cluster\".\"eco\".\"events\""),
                "PostgreSQL identifiers must quote every segment");
    }

    @Test
    void rejectsUnsafeIdentifiersAndColumnSets() {
        expectFailure(() -> SqlIdentifiers.requireQualified("events;DROP_TABLE"));
        expectFailure(() -> SqlIdentifiers.requireQualified("schema..events"));
        expectFailure(() -> SqlIdentifiers.requireQualified("a.b.c.d"));
        expectFailure(() -> new CrossServerTables("outbox", "inbox", "leases", "bad-name"));
        expectFailure(() -> SqlDialect.SQLITE.insertIgnore("events", List.of(), List.of("id")));
        expectFailure(() -> SqlDialect.SQLITE.insertIgnore("events", List.of("id", "id"), List.of("id")));
        expectFailure(() -> SqlDialect.POSTGRESQL.upsert(
                "events", List.of("id", "payload"), List.of("missing"), List.of("payload")
        ));
    }

    @Test
    void generatesDialectSpecificIdempotentWrites() {
        List<String> columns = List.of("event_id", "payload");
        List<String> conflict = List.of("event_id");
        String sqlite = SqlDialect.SQLITE.insertIgnore("events", columns, conflict);
        String mysql = SqlDialect.MYSQL.insertIgnore("events", columns, conflict);
        String postgres = SqlDialect.POSTGRESQL.insertIgnore("events", columns, conflict);

        check(sqlite.equals("INSERT OR IGNORE INTO \"events\" (\"event_id\", \"payload\") VALUES (?, ?)"),
                "SQLite must use INSERT OR IGNORE");
        check(mysql.endsWith("ON DUPLICATE KEY UPDATE `event_id` = `event_id`"),
                "MySQL duplicate handling must be a no-op");
        check(postgres.endsWith("ON CONFLICT (\"event_id\") DO NOTHING"),
                "PostgreSQL duplicate handling must target the event key");
    }

    @Test
    void generatesDialectSpecificUpserts() {
        List<String> columns = List.of("server_id", "instance_id", "last_seen_at_ms");
        List<String> conflict = List.of("server_id");
        List<String> updates = List.of("instance_id", "last_seen_at_ms");

        String mysql = SqlDialect.MYSQL.upsert("heartbeats", columns, conflict, updates);
        String postgres = SqlDialect.POSTGRESQL.upsert("heartbeats", columns, conflict, updates);
        String sqlite = SqlDialect.SQLITE.upsert("heartbeats", columns, conflict, updates);

        check(mysql.contains("`instance_id` = VALUES(`instance_id`)"), "MySQL must use VALUES in upserts");
        check(postgres.contains("ON CONFLICT (\"server_id\") DO UPDATE SET"),
                "PostgreSQL must declare the heartbeat conflict key");
        check(postgres.contains("\"last_seen_at_ms\" = excluded.\"last_seen_at_ms\""),
                "PostgreSQL must read replacement values from excluded");
        check(sqlite.contains("ON CONFLICT (\"server_id\") DO UPDATE SET"),
                "SQLite must use modern ON CONFLICT upsert syntax");
    }

    @Test
    void preservesCrossServerOrderingAndOwnershipGuards() {
        for (SqlDialect dialect : SqlDialect.values()) {
            CrossServerSql sql = new CrossServerSql(dialect, CrossServerTables.defaults());
            String poll = sql.pollAvailableOutboxSql();

            check(inOrder(poll, "available_at_ms", "occurred_at_ms", "event_id"),
                    dialect + " polling must use the composite cursor order");
            check(count(poll, '?') == 3, dialect + " polling parameter count");
            check(count(sql.claimOutboxSql(), '?') == 5, dialect + " outbox claim parameter count");
            check(count(sql.markOutboxPublishedSql(), '?') == 4,
                    dialect + " publish must require event, lease owner, and attempt fence");
            check(sql.markOutboxPublishedSql().contains("attempt_count"),
                    dialect + " publish must fence an expired claim generation");
            check(count(sql.claimInboxSql(), '?') == 5, dialect + " inbox claim parameter count");
            check(sql.claimInboxSql().contains("PROCESSED"), dialect + " inbox claims must fence processed events");
            check(count(sql.renewLeaseSql(), '?') == 6, dialect + " renewal must include identity, fence, and expiry");
            check(sql.renewLeaseSql().contains("fencing_token"), dialect + " renewal must compare fencing token");
            check(count(sql.releaseLeaseSql(), '?') == 5, dialect + " release must retain the row and include fencing token");
            check(sql.acquireExpiredLeaseSql().contains("fencing_token")
                            && sql.acquireExpiredLeaseSql().contains("+ 1"),
                    dialect + " takeover must advance the fencing token");
        }
    }

    @Test
    void producesCompleteSchemaForEveryDialect() {
        for (SqlDialect dialect : SqlDialect.values()) {
            CrossServerSql sql = new CrossServerSql(dialect, CrossServerTables.defaults());
            List<String> schema = sql.createSchemaStatements();

            check(schema.size() == 4, dialect + " must create outbox, inbox, leases, and heartbeats");
            check(schema.stream().allMatch(statement -> statement.startsWith("CREATE TABLE IF NOT EXISTS")),
                    dialect + " schema statements must be idempotent");
            check(count(sql.insertOutboxIfAbsentSql(), '?') == CrossServerSql.OUTBOX_COLUMNS.size(),
                    dialect + " outbox binding order must cover every declared column");
            check(count(sql.insertInboxIfAbsentSql(), '?') == CrossServerSql.INBOX_COLUMNS.size(),
                    dialect + " inbox binding order must cover every declared column");
            check(count(sql.upsertHeartbeatSql(), '?') == CrossServerSql.HEARTBEAT_COLUMNS.size(),
                    dialect + " heartbeat binding order must cover every declared column");
            check(!sql.databaseTimeMillisSql().contains("?"), dialect + " database time must use server-side clock");
        }
    }

    private static boolean inOrder(String sql, String first, String second, String third) {
        int firstIndex = sql.lastIndexOf(first);
        int secondIndex = sql.lastIndexOf(second);
        int thirdIndex = sql.lastIndexOf(third);
        return firstIndex >= 0 && firstIndex < secondIndex && secondIndex < thirdIndex;
    }

    private static int count(String value, char needle) {
        int result = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == needle) {
                result++;
            }
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected contract rejection.
        }
    }
}
