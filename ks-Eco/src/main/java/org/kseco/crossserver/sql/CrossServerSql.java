package org.kseco.crossserver.sql;

import java.util.List;
import java.util.Objects;

/** Prepared-statement SQL for the cross-server outbox, inbox, heartbeat and lease stores. */
public final class CrossServerSql {
    public static final List<String> OUTBOX_COLUMNS = List.of(
            "event_id", "topic", "event_type", "aggregate_key", "source_server_id", "source_instance_id",
            "payload_json", "schema_version", "occurred_at_ms", "available_at_ms", "published_at_ms",
            "attempt_count", "lease_owner_id", "lease_until_ms", "last_error"
    );
    public static final List<String> INBOX_COLUMNS = List.of(
            "consumer_id", "event_id", "source_server_id", "status", "received_at_ms", "processed_at_ms",
            "lease_owner_id", "lease_until_ms", "attempt_count", "last_error"
    );
    public static final List<String> LEASE_COLUMNS = List.of(
            "lease_name", "owner_id", "fencing_token", "expires_at_ms", "updated_at_ms"
    );
    public static final List<String> HEARTBEAT_COLUMNS = List.of(
            "server_id", "instance_id", "started_at_ms", "last_seen_at_ms", "expires_at_ms", "metadata_json"
    );

    private final SqlDialect dialect;
    private final CrossServerTables tables;
    private final String outbox;
    private final String inbox;
    private final String leases;
    private final String heartbeats;

    public CrossServerSql(SqlDialect dialect, CrossServerTables tables) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.outbox = quote(tables.outbox());
        this.inbox = quote(tables.inbox());
        this.leases = quote(tables.leases());
        this.heartbeats = quote(tables.heartbeats());
    }

    public SqlDialect dialect() {
        return dialect;
    }

    public CrossServerTables tables() {
        return tables;
    }

    public List<String> createSchemaStatements() {
        return List.of(createOutboxTableSql(), createInboxTableSql(), createLeaseTableSql(), createHeartbeatTableSql());
    }

    public String databaseTimeMillisSql() {
        return "SELECT " + dialect.currentEpochMillisExpression();
    }

    public String createOutboxTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + outbox + " ("
                + q("event_id") + " " + identifierType(36) + " PRIMARY KEY, "
                + q("topic") + " " + identifierType(128) + " NOT NULL, "
                + q("event_type") + " " + identifierType(128) + " NOT NULL, "
                + q("aggregate_key") + " " + identifierType(256) + " NOT NULL, "
                + q("source_server_id") + " " + identifierType(64) + " NOT NULL, "
                + q("source_instance_id") + " " + identifierType(96) + " NOT NULL, "
                + q("payload_json") + " " + longTextType() + " NOT NULL, "
                + q("schema_version") + " INTEGER NOT NULL, "
                + q("occurred_at_ms") + " BIGINT NOT NULL, "
                + q("available_at_ms") + " BIGINT NOT NULL, "
                + q("published_at_ms") + " BIGINT NULL, "
                + q("attempt_count") + " INTEGER NOT NULL DEFAULT 0, "
                + q("lease_owner_id") + " " + identifierType(192) + " NULL, "
                + q("lease_until_ms") + " BIGINT NULL, "
                + q("last_error") + " " + textType() + " NULL)";
    }

    public String createInboxTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + inbox + " ("
                + q("consumer_id") + " " + identifierType(128) + " NOT NULL, "
                + q("event_id") + " " + identifierType(36) + " NOT NULL, "
                + q("source_server_id") + " " + identifierType(64) + " NOT NULL, "
                + q("status") + " " + shortTextType() + " NOT NULL, "
                + q("received_at_ms") + " BIGINT NOT NULL, "
                + q("processed_at_ms") + " BIGINT NULL, "
                + q("lease_owner_id") + " " + identifierType(192) + " NULL, "
                + q("lease_until_ms") + " BIGINT NULL, "
                + q("attempt_count") + " INTEGER NOT NULL DEFAULT 0, "
                + q("last_error") + " " + textType() + " NULL, PRIMARY KEY ("
                + q("consumer_id") + ", " + q("event_id") + "))";
    }

    public String createLeaseTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + leases + " ("
                + q("lease_name") + " " + identifierType(128) + " PRIMARY KEY, "
                + q("owner_id") + " " + identifierType(192) + " NOT NULL, "
                + q("fencing_token") + " BIGINT NOT NULL, "
                + q("expires_at_ms") + " BIGINT NOT NULL, "
                + q("updated_at_ms") + " BIGINT NOT NULL)";
    }

    public String createHeartbeatTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + heartbeats + " ("
                + q("server_id") + " " + identifierType(64) + " PRIMARY KEY, "
                + q("instance_id") + " " + identifierType(96) + " NOT NULL, "
                + q("started_at_ms") + " BIGINT NOT NULL, "
                + q("last_seen_at_ms") + " BIGINT NOT NULL, "
                + q("expires_at_ms") + " BIGINT NOT NULL, "
                + q("metadata_json") + " " + longTextType() + " NOT NULL)";
    }

    /** Parameters follow {@link #OUTBOX_COLUMNS}. */
    public String insertOutboxIfAbsentSql() {
        return dialect.insertIgnore(tables.outbox(), OUTBOX_COLUMNS, List.of("event_id"));
    }

    /** Parameters: available-now, lease-now, limit. */
    public String pollAvailableOutboxSql() {
        return "SELECT " + joined(OUTBOX_COLUMNS) + " FROM " + outbox
                + " WHERE " + q("published_at_ms") + " IS NULL"
                + " AND " + q("available_at_ms") + " <= ?"
                + " AND (" + q("lease_until_ms") + " IS NULL OR " + q("lease_until_ms") + " <= ?)"
                + " ORDER BY " + q("available_at_ms") + ", " + q("occurred_at_ms") + ", " + q("event_id")
                + " LIMIT ?";
    }

    /** Parameters: owner, lease-until, event-id, available-now, lease-now. */
    public String claimOutboxSql() {
        return "UPDATE " + outbox + " SET " + q("lease_owner_id") + " = ?, " + q("lease_until_ms") + " = ?, "
                + q("attempt_count") + " = " + q("attempt_count") + " + 1"
                + " WHERE " + q("event_id") + " = ? AND " + q("published_at_ms") + " IS NULL"
                + " AND " + q("available_at_ms") + " <= ? AND (" + q("lease_until_ms") + " IS NULL OR "
                + q("lease_until_ms") + " <= ?)";
    }

    /** Parameters: published-at, event-id, owner, expected-attempt-count. */
    public String markOutboxPublishedSql() {
        return "UPDATE " + outbox + " SET " + q("published_at_ms") + " = ?, " + q("lease_owner_id")
                + " = NULL, " + q("lease_until_ms") + " = NULL, " + q("last_error") + " = NULL WHERE "
                + q("event_id") + " = ? AND " + q("lease_owner_id") + " = ? AND "
                + q("attempt_count") + " = ? AND " + q("published_at_ms") + " IS NULL";
    }

    /** Parameters: next-available-at, error, event-id, owner, expected-attempt-count. */
    public String retryOutboxSql() {
        return "UPDATE " + outbox + " SET " + q("available_at_ms") + " = ?, " + q("last_error") + " = ?, "
                + q("lease_owner_id")
                + " = NULL, " + q("lease_until_ms") + " = NULL WHERE " + q("event_id") + " = ? AND "
                + q("lease_owner_id") + " = ? AND " + q("attempt_count") + " = ? AND "
                + q("published_at_ms") + " IS NULL";
    }

    public String markOutboxFailedSql() {
        return retryOutboxSql();
    }

    /** Parameters: published-before, limit. Returns event IDs for guarded, transactional deletion. */
    public String selectPublishedOutboxIdsForPurgeSql() {
        return "SELECT " + q("event_id") + " FROM " + outbox + " WHERE " + q("published_at_ms")
                + " IS NOT NULL AND " + q("published_at_ms") + " < ? ORDER BY " + q("published_at_ms")
                + ", " + q("event_id") + " LIMIT ?";
    }

    /** Parameters: event-id, published-before. */
    public String deletePublishedOutboxByIdSql() {
        return "DELETE FROM " + outbox + " WHERE " + q("event_id") + " = ? AND " + q("published_at_ms")
                + " IS NOT NULL AND " + q("published_at_ms") + " < ?";
    }

    /** Parameters follow {@link #INBOX_COLUMNS}. */
    public String insertInboxIfAbsentSql() {
        return dialect.insertIgnore(tables.inbox(), INBOX_COLUMNS, List.of("consumer_id", "event_id"));
    }

    /** Parameters: owner, lease-until, consumer-id, event-id, lease-now. */
    public String claimInboxSql() {
        return "UPDATE " + inbox + " SET " + q("lease_owner_id") + " = ?, " + q("lease_until_ms")
                + " = ?, " + q("attempt_count") + " = " + q("attempt_count") + " + 1, "
                + q("status") + " = 'PENDING' WHERE " + q("consumer_id") + " = ? AND "
                + q("event_id") + " = ? AND " + q("status") + " <> 'PROCESSED' AND ("
                + q("lease_until_ms") + " IS NULL OR " + q("lease_until_ms") + " <= ?)";
    }

    /** Parameters: processed-at, consumer-id, event-id, owner, expected-attempt-count. */
    public String markInboxProcessedSql() {
        return "UPDATE " + inbox + " SET " + q("status") + " = 'PROCESSED', " + q("processed_at_ms")
                + " = ?, " + q("lease_owner_id") + " = NULL, " + q("lease_until_ms") + " = NULL, "
                + q("last_error") + " = NULL WHERE " + q("consumer_id") + " = ? AND " + q("event_id")
                + " = ? AND " + q("lease_owner_id") + " = ? AND " + q("attempt_count") + " = ? AND "
                + q("status") + " <> 'PROCESSED'";
    }

    /** Parameters: retry-at, error, consumer-id, event-id, owner, expected-attempt-count. */
    public String markInboxFailedSql() {
        return "UPDATE " + inbox + " SET " + q("status") + " = 'FAILED', " + q("lease_until_ms")
                + " = ?, " + q("last_error") + " = ?, " + q("lease_owner_id")
                + " = NULL WHERE " + q("consumer_id") + " = ? AND "
                + q("event_id") + " = ? AND " + q("lease_owner_id") + " = ? AND "
                + q("attempt_count") + " = ? AND " + q("status") + " <> 'PROCESSED'";
    }

    /** Parameters: consumer-id, event-id. */
    public String selectInboxSql() {
        return "SELECT " + joined(INBOX_COLUMNS) + " FROM " + inbox + " WHERE " + q("consumer_id")
                + " = ? AND " + q("event_id") + " = ?";
    }

    /** Parameters: processed-before, limit. Returns keys for guarded, transactional deletion. */
    public String selectProcessedInboxKeysForPurgeSql() {
        return "SELECT " + q("consumer_id") + ", " + q("event_id") + " FROM " + inbox + " WHERE "
                + q("status") + " = 'PROCESSED' AND " + q("processed_at_ms") + " < ? ORDER BY "
                + q("processed_at_ms") + ", " + q("consumer_id") + ", " + q("event_id") + " LIMIT ?";
    }

    /** Parameters: consumer-id, event-id, processed-before. */
    public String deleteProcessedInboxByKeySql() {
        return "DELETE FROM " + inbox + " WHERE " + q("consumer_id") + " = ? AND " + q("event_id")
                + " = ? AND " + q("status") + " = 'PROCESSED' AND " + q("processed_at_ms") + " < ?";
    }

    /** Parameters follow {@link #HEARTBEAT_COLUMNS}. */
    public String upsertHeartbeatSql() {
        return dialect.upsert(
                tables.heartbeats(),
                HEARTBEAT_COLUMNS,
                List.of("server_id"),
                List.of("instance_id", "started_at_ms", "last_seen_at_ms", "expires_at_ms", "metadata_json")
        );
    }

    /** Parameters: live-after. */
    public String selectLiveHeartbeatsSql() {
        return "SELECT " + joined(HEARTBEAT_COLUMNS) + " FROM " + heartbeats + " WHERE "
                + q("expires_at_ms") + " > ? ORDER BY " + q("server_id");
    }

    /** Parameters: expired-at-or-before. */
    public String deleteExpiredHeartbeatsSql() {
        return "DELETE FROM " + heartbeats + " WHERE " + q("expires_at_ms") + " <= ?";
    }

    /** Parameters: expired-before, limit. */
    public String selectExpiredHeartbeatIdsForPurgeSql() {
        return "SELECT " + q("server_id") + " FROM " + heartbeats + " WHERE " + q("expires_at_ms")
                + " < ? ORDER BY " + q("expires_at_ms") + ", " + q("server_id") + " LIMIT ?";
    }

    /** Parameters: server-id, expired-before. */
    public String deleteExpiredHeartbeatByIdSql() {
        return "DELETE FROM " + heartbeats + " WHERE " + q("server_id") + " = ? AND "
                + q("expires_at_ms") + " < ?";
    }

    /** Parameters follow {@link #LEASE_COLUMNS}; use fencing token 1 for a new lease. */
    public String insertLeaseIfAbsentSql() {
        return dialect.insertIgnore(tables.leases(), LEASE_COLUMNS, List.of("lease_name"));
    }

    /** Parameters: owner, expires-at, updated-at, lease-name, expired-at-or-before. */
    public String acquireExpiredLeaseSql() {
        return "UPDATE " + leases + " SET " + q("owner_id") + " = ?, " + q("fencing_token") + " = "
                + q("fencing_token") + " + 1, " + q("expires_at_ms") + " = ?, " + q("updated_at_ms")
                + " = ? WHERE " + q("lease_name") + " = ? AND " + q("expires_at_ms") + " <= ?";
    }

    /** Parameters: expires-at, updated-at, lease-name, owner, fencing-token, not-expired-after. */
    public String renewLeaseSql() {
        return "UPDATE " + leases + " SET " + q("expires_at_ms") + " = ?, " + q("updated_at_ms")
                + " = ? WHERE " + q("lease_name") + " = ? AND " + q("owner_id") + " = ? AND "
                + q("fencing_token") + " = ? AND " + q("expires_at_ms") + " > ?";
    }

    /** Parameters: released-at, released-at, lease-name, owner, fencing-token. */
    public String releaseLeaseSql() {
        return "UPDATE " + leases + " SET " + q("expires_at_ms") + " = ?, " + q("updated_at_ms")
                + " = ?, " + q("fencing_token") + " = " + q("fencing_token") + " + 1 WHERE "
                + q("lease_name") + " = ? AND " + q("owner_id") + " = ? AND "
                + q("fencing_token") + " = ?";
    }

    /** Parameters: lease-name. */
    public String selectLeaseSql() {
        return "SELECT " + joined(LEASE_COLUMNS) + " FROM " + leases + " WHERE " + q("lease_name") + " = ?";
    }

    private String identifierType(int maxLength) {
        return dialect == SqlDialect.MYSQL ? "VARCHAR(" + maxLength + ")" : "TEXT";
    }

    private String shortTextType() {
        return dialect == SqlDialect.MYSQL ? "VARCHAR(32)" : "TEXT";
    }

    private String textType() {
        return "TEXT";
    }

    private String longTextType() {
        return dialect == SqlDialect.MYSQL ? "LONGTEXT" : "TEXT";
    }

    private String joined(List<String> columns) {
        return columns.stream().map(this::q).collect(java.util.stream.Collectors.joining(", "));
    }

    private String quote(String table) {
        return SqlIdentifiers.quoteQualified(dialect, table);
    }

    private String q(String column) {
        return dialect.quoteSimpleIdentifier(column);
    }
}
