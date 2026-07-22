package org.kseco.crossserver;

import org.kseco.crossserver.sql.CrossServerSql;
import org.kseco.crossserver.sql.CrossServerTables;
import org.kseco.crossserver.sql.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** JDBC implementation of the shared-database cross-server repository. */
public final class JdbcCrossServerRepository implements CrossServerRepository {
    private static final int MAX_BATCH_SIZE = 10_000;
    private static final int MAX_ERROR_LENGTH = 65_536;

    private final CrossServerSql sql;

    public JdbcCrossServerRepository(SqlDialect dialect) {
        this(new CrossServerSql(dialect, CrossServerTables.defaults()));
    }

    public JdbcCrossServerRepository(SqlDialect dialect, CrossServerTables tables) {
        this(new CrossServerSql(dialect, tables));
    }

    public JdbcCrossServerRepository(CrossServerSql sql) {
        this.sql = Objects.requireNonNull(sql, "sql");
    }

    public CrossServerSql sql() {
        return sql;
    }

    @Override
    public void initialize(Connection connection) throws SQLException {
        requireConnection(connection);
        try (Statement statement = connection.createStatement()) {
            for (String schemaStatement : sql.createSchemaStatements()) {
                statement.execute(schemaStatement);
            }
        }
    }

    @Override
    public Instant currentDatabaseTime(Connection connection) throws SQLException {
        requireConnection(connection);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql.databaseTimeMillisSql())) {
            if (!result.next()) {
                throw new SQLException("database time query returned no row");
            }
            return Instant.ofEpochMilli(result.getLong(1));
        }
    }

    @Override
    public boolean enqueueOutbox(Connection connection, CrossServerEventEnvelope event, Instant availableAt)
            throws SQLException {
        requireConnection(connection);
        Objects.requireNonNull(event, "event");
        long availableAtMillis = epochMillis(availableAt, "availableAt");
        try (PreparedStatement statement = connection.prepareStatement(sql.insertOutboxIfAbsentSql())) {
            int index = bindEnvelope(statement, 1, event);
            statement.setLong(index++, availableAtMillis);
            statement.setNull(index++, Types.BIGINT);
            statement.setInt(index++, 0);
            statement.setNull(index++, Types.VARCHAR);
            statement.setNull(index++, Types.BIGINT);
            statement.setNull(index, Types.VARCHAR);
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public List<OutboxClaim> claimOutbox(
            Connection connection,
            String leaseOwnerId,
            Instant now,
            Duration leaseDuration,
            int limit
    ) throws SQLException {
        requireConnection(connection);
        String owner = CrossServerValidation.identifier(leaseOwnerId, "leaseOwnerId", 192);
        Instant checkedNow = CrossServerValidation.instant(now, "now");
        Instant leaseUntil = addDuration(checkedNow, leaseDuration, "leaseDuration");
        int checkedLimit = requireLimit(limit);
        return transactional(connection, () -> {
            List<OutboxRow> candidates = new ArrayList<>(checkedLimit);
            try (PreparedStatement statement = connection.prepareStatement(sql.pollAvailableOutboxSql())) {
                statement.setLong(1, epochMillis(checkedNow, "now"));
                statement.setLong(2, epochMillis(checkedNow, "now"));
                statement.setInt(3, checkedLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        candidates.add(readOutboxRow(result));
                    }
                }
            }

            List<OutboxClaim> claims = new ArrayList<>(candidates.size());
            try (PreparedStatement statement = connection.prepareStatement(sql.claimOutboxSql())) {
                for (OutboxRow candidate : candidates) {
                    statement.setString(1, owner);
                    statement.setLong(2, epochMillis(leaseUntil, "leaseUntil"));
                    statement.setString(3, candidate.event().eventId().toString());
                    statement.setLong(4, epochMillis(checkedNow, "now"));
                    statement.setLong(5, epochMillis(checkedNow, "now"));
                    if (statement.executeUpdate() == 1) {
                        claims.add(new OutboxClaim(
                                candidate.event(),
                                owner,
                                leaseUntil,
                                Math.addExact(candidate.attemptCount(), 1)
                        ));
                    }
                }
            }
            return List.copyOf(claims);
        });
    }

    @Override
    public boolean markOutboxPublished(
            Connection connection,
            UUID eventId,
            String leaseOwnerId,
            int expectedAttemptCount,
            Instant publishedAt
    ) throws SQLException {
        requireConnection(connection);
        requireAttempt(expectedAttemptCount);
        try (PreparedStatement statement = connection.prepareStatement(sql.markOutboxPublishedSql())) {
            statement.setLong(1, epochMillis(publishedAt, "publishedAt"));
            statement.setString(2, Objects.requireNonNull(eventId, "eventId").toString());
            statement.setString(3, CrossServerValidation.identifier(leaseOwnerId, "leaseOwnerId", 192));
            statement.setInt(4, expectedAttemptCount);
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public boolean retryOutbox(
            Connection connection,
            UUID eventId,
            String leaseOwnerId,
            int expectedAttemptCount,
            Instant availableAt,
            String lastError
    ) throws SQLException {
        requireConnection(connection);
        requireAttempt(expectedAttemptCount);
        try (PreparedStatement statement = connection.prepareStatement(sql.retryOutboxSql())) {
            statement.setLong(1, epochMillis(availableAt, "availableAt"));
            setNullableString(statement, 2, checkedError(lastError));
            statement.setString(3, Objects.requireNonNull(eventId, "eventId").toString());
            statement.setString(4, CrossServerValidation.identifier(leaseOwnerId, "leaseOwnerId", 192));
            statement.setInt(5, expectedAttemptCount);
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public int purgePublishedOutbox(Connection connection, Instant publishedBefore, int limit) throws SQLException {
        requireConnection(connection);
        long cutoff = epochMillis(publishedBefore, "publishedBefore");
        int checkedLimit = requireLimit(limit);
        return transactional(connection, () -> {
            List<String> eventIds = new ArrayList<>(checkedLimit);
            try (PreparedStatement statement = connection.prepareStatement(sql.selectPublishedOutboxIdsForPurgeSql())) {
                statement.setLong(1, cutoff);
                statement.setInt(2, checkedLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) eventIds.add(result.getString(1));
                }
            }
            int deleted = 0;
            try (PreparedStatement statement = connection.prepareStatement(sql.deletePublishedOutboxByIdSql())) {
                for (String eventId : eventIds) {
                    statement.setString(1, eventId);
                    statement.setLong(2, cutoff);
                    deleted += statement.executeUpdate();
                }
            }
            return deleted;
        });
    }

    @Override
    public InboxClaim claimInbox(
            Connection connection,
            CrossServerEventEnvelope event,
            String consumerId,
            String leaseOwnerId,
            Instant now,
            Duration leaseDuration
    ) throws SQLException {
        requireConnection(connection);
        CrossServerEventEnvelope checkedEvent = Objects.requireNonNull(event, "event");
        String consumer = CrossServerValidation.identifier(consumerId, "consumerId", 128);
        String owner = CrossServerValidation.identifier(leaseOwnerId, "leaseOwnerId", 192);
        Instant checkedNow = CrossServerValidation.instant(now, "now");
        Instant leaseUntil = addDuration(checkedNow, leaseDuration, "leaseDuration");
        return transactional(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.insertInboxIfAbsentSql())) {
                statement.setString(1, consumer);
                statement.setString(2, checkedEvent.eventId().toString());
                statement.setString(3, checkedEvent.sourceServerId());
                statement.setString(4, "PENDING");
                statement.setLong(5, epochMillis(checkedNow, "now"));
                statement.setNull(6, Types.BIGINT);
                statement.setNull(7, Types.VARCHAR);
                statement.setNull(8, Types.BIGINT);
                statement.setInt(9, 0);
                statement.setNull(10, Types.VARCHAR);
                statement.executeUpdate();
            }

            int claimed;
            try (PreparedStatement statement = connection.prepareStatement(sql.claimInboxSql())) {
                statement.setString(1, owner);
                statement.setLong(2, epochMillis(leaseUntil, "leaseUntil"));
                statement.setString(3, consumer);
                statement.setString(4, checkedEvent.eventId().toString());
                statement.setLong(5, epochMillis(checkedNow, "now"));
                claimed = statement.executeUpdate();
            }

            InboxRow row = findInbox(connection, consumer, checkedEvent.eventId());
            if (row == null) {
                throw new SQLException("inbox row disappeared while claiming " + checkedEvent.eventId());
            }
            if (claimed == 1) {
                return InboxClaim.claimed(row.eventId(), row.consumerId(), owner, leaseUntil, row.attemptCount());
            }
            return "PROCESSED".equals(row.status())
                    ? InboxClaim.alreadyProcessed(row.eventId(), row.consumerId(), row.attemptCount())
                    : InboxClaim.busy(row.eventId(), row.consumerId(), row.attemptCount());
        });
    }

    @Override
    public boolean markInboxProcessed(
            Connection connection,
            UUID eventId,
            String consumerId,
            String leaseOwnerId,
            int expectedAttemptCount,
            Instant processedAt
    ) throws SQLException {
        requireConnection(connection);
        requireAttempt(expectedAttemptCount);
        try (PreparedStatement statement = connection.prepareStatement(sql.markInboxProcessedSql())) {
            statement.setLong(1, epochMillis(processedAt, "processedAt"));
            statement.setString(2, CrossServerValidation.identifier(consumerId, "consumerId", 128));
            statement.setString(3, Objects.requireNonNull(eventId, "eventId").toString());
            statement.setString(4, CrossServerValidation.identifier(leaseOwnerId, "leaseOwnerId", 192));
            statement.setInt(5, expectedAttemptCount);
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public boolean markInboxFailed(
            Connection connection,
            UUID eventId,
            String consumerId,
            String leaseOwnerId,
            int expectedAttemptCount,
            Instant retryAt,
            String lastError
    ) throws SQLException {
        requireConnection(connection);
        requireAttempt(expectedAttemptCount);
        try (PreparedStatement statement = connection.prepareStatement(sql.markInboxFailedSql())) {
            statement.setLong(1, epochMillis(retryAt, "retryAt"));
            setNullableString(statement, 2, checkedError(lastError));
            statement.setString(3, CrossServerValidation.identifier(consumerId, "consumerId", 128));
            statement.setString(4, Objects.requireNonNull(eventId, "eventId").toString());
            statement.setString(5, CrossServerValidation.identifier(leaseOwnerId, "leaseOwnerId", 192));
            statement.setInt(6, expectedAttemptCount);
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public int purgeProcessedInbox(Connection connection, Instant processedBefore, int limit) throws SQLException {
        requireConnection(connection);
        long cutoff = epochMillis(processedBefore, "processedBefore");
        int checkedLimit = requireLimit(limit);
        return transactional(connection, () -> {
            List<InboxKey> keys = new ArrayList<>(checkedLimit);
            try (PreparedStatement statement = connection.prepareStatement(sql.selectProcessedInboxKeysForPurgeSql())) {
                statement.setLong(1, cutoff);
                statement.setInt(2, checkedLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) keys.add(new InboxKey(result.getString(1), result.getString(2)));
                }
            }
            int deleted = 0;
            try (PreparedStatement statement = connection.prepareStatement(sql.deleteProcessedInboxByKeySql())) {
                for (InboxKey key : keys) {
                    statement.setString(1, key.consumerId());
                    statement.setString(2, key.eventId());
                    statement.setLong(3, cutoff);
                    deleted += statement.executeUpdate();
                }
            }
            return deleted;
        });
    }

    @Override
    public void heartbeat(Connection connection, ServerHeartbeat heartbeat) throws SQLException {
        requireConnection(connection);
        ServerHeartbeat checked = Objects.requireNonNull(heartbeat, "heartbeat");
        try (PreparedStatement statement = connection.prepareStatement(sql.upsertHeartbeatSql())) {
            statement.setString(1, checked.serverId());
            statement.setString(2, checked.instanceId());
            statement.setLong(3, epochMillis(checked.startedAt(), "startedAt"));
            statement.setLong(4, epochMillis(checked.lastSeenAt(), "lastSeenAt"));
            statement.setLong(5, epochMillis(checked.expiresAt(), "expiresAt"));
            statement.setString(6, checked.metadataJson());
            statement.executeUpdate();
        }
    }

    @Override
    public List<ServerHeartbeat> findAliveServers(Connection connection, Instant now) throws SQLException {
        requireConnection(connection);
        List<ServerHeartbeat> heartbeats = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.selectLiveHeartbeatsSql())) {
            statement.setLong(1, epochMillis(now, "now"));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    heartbeats.add(new ServerHeartbeat(
                            result.getString(1),
                            result.getString(2),
                            Instant.ofEpochMilli(result.getLong(3)),
                            Instant.ofEpochMilli(result.getLong(4)),
                            Instant.ofEpochMilli(result.getLong(5)),
                            result.getString(6)
                    ));
                }
            }
        }
        return List.copyOf(heartbeats);
    }

    @Override
    public int purgeExpiredHeartbeats(Connection connection, Instant expiredBefore, int limit) throws SQLException {
        requireConnection(connection);
        long cutoff = epochMillis(expiredBefore, "expiredBefore");
        int checkedLimit = requireLimit(limit);
        return transactional(connection, () -> {
            List<String> serverIds = new ArrayList<>(checkedLimit);
            try (PreparedStatement statement = connection.prepareStatement(sql.selectExpiredHeartbeatIdsForPurgeSql())) {
                statement.setLong(1, cutoff);
                statement.setInt(2, checkedLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) serverIds.add(result.getString(1));
                }
            }
            int deleted = 0;
            try (PreparedStatement statement = connection.prepareStatement(sql.deleteExpiredHeartbeatByIdSql())) {
                for (String serverId : serverIds) {
                    statement.setString(1, serverId);
                    statement.setLong(2, cutoff);
                    deleted += statement.executeUpdate();
                }
            }
            return deleted;
        });
    }

    @Override
    public Optional<LeaseRecord> tryAcquireLease(
            Connection connection,
            String leaseName,
            String ownerId,
            Instant now,
            Duration leaseDuration
    ) throws SQLException {
        requireConnection(connection);
        String name = CrossServerValidation.identifier(leaseName, "leaseName", 128);
        String owner = CrossServerValidation.identifier(ownerId, "ownerId", 192);
        Instant checkedNow = CrossServerValidation.instant(now, "now");
        Instant expiresAt = addDuration(checkedNow, leaseDuration, "leaseDuration");
        return transactional(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.acquireExpiredLeaseSql())) {
                statement.setString(1, owner);
                statement.setLong(2, epochMillis(expiresAt, "expiresAt"));
                statement.setLong(3, epochMillis(checkedNow, "now"));
                statement.setString(4, name);
                statement.setLong(5, epochMillis(checkedNow, "now"));
                if (statement.executeUpdate() == 1) return findLease(connection, name);
            }

            LeaseRecord existing = findLease(connection, name).orElse(null);
            if (existing == null) {
                try (PreparedStatement statement = connection.prepareStatement(sql.insertLeaseIfAbsentSql())) {
                    statement.setString(1, name);
                    statement.setString(2, owner);
                    statement.setLong(3, 1L);
                    statement.setLong(4, epochMillis(expiresAt, "expiresAt"));
                    statement.setLong(5, epochMillis(checkedNow, "now"));
                    statement.executeUpdate();
                }
                existing = findLease(connection, name).orElse(null);
            }

            if (existing != null && existing.ownerId().equals(owner) && existing.expiresAt().isAfter(checkedNow)) {
                if (renewLease(connection, name, owner, existing.fencingToken(), checkedNow, leaseDuration)) {
                    return findLease(connection, name);
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public boolean renewLease(
            Connection connection,
            String leaseName,
            String ownerId,
            long fencingToken,
            Instant now,
            Duration leaseDuration
    ) throws SQLException {
        requireConnection(connection);
        requireFencingToken(fencingToken);
        Instant checkedNow = CrossServerValidation.instant(now, "now");
        Instant expiresAt = addDuration(checkedNow, leaseDuration, "leaseDuration");
        try (PreparedStatement statement = connection.prepareStatement(sql.renewLeaseSql())) {
            statement.setLong(1, epochMillis(expiresAt, "expiresAt"));
            statement.setLong(2, epochMillis(checkedNow, "now"));
            statement.setString(3, CrossServerValidation.identifier(leaseName, "leaseName", 128));
            statement.setString(4, CrossServerValidation.identifier(ownerId, "ownerId", 192));
            statement.setLong(5, fencingToken);
            statement.setLong(6, epochMillis(checkedNow, "now"));
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public boolean releaseLease(
            Connection connection,
            String leaseName,
            String ownerId,
            long fencingToken,
            Instant releasedAt
    ) throws SQLException {
        requireConnection(connection);
        requireFencingToken(fencingToken);
        long releasedAtMillis = epochMillis(releasedAt, "releasedAt");
        try (PreparedStatement statement = connection.prepareStatement(sql.releaseLeaseSql())) {
            statement.setLong(1, releasedAtMillis);
            statement.setLong(2, releasedAtMillis);
            statement.setString(3, CrossServerValidation.identifier(leaseName, "leaseName", 128));
            statement.setString(4, CrossServerValidation.identifier(ownerId, "ownerId", 192));
            statement.setLong(5, fencingToken);
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public Optional<LeaseRecord> findLease(Connection connection, String leaseName) throws SQLException {
        requireConnection(connection);
        try (PreparedStatement statement = connection.prepareStatement(sql.selectLeaseSql())) {
            statement.setString(1, CrossServerValidation.identifier(leaseName, "leaseName", 128));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new LeaseRecord(
                        result.getString(1),
                        result.getString(2),
                        result.getLong(3),
                        Instant.ofEpochMilli(result.getLong(4)),
                        Instant.ofEpochMilli(result.getLong(5))
                ));
            }
        }
    }

    private static int bindEnvelope(PreparedStatement statement, int start, CrossServerEventEnvelope event)
            throws SQLException {
        int index = start;
        statement.setString(index++, event.eventId().toString());
        statement.setString(index++, event.topic());
        statement.setString(index++, event.eventType());
        statement.setString(index++, event.aggregateKey());
        statement.setString(index++, event.sourceServerId());
        statement.setString(index++, event.sourceInstanceId());
        statement.setString(index++, event.payloadJson());
        statement.setInt(index++, event.schemaVersion());
        statement.setLong(index++, epochMillis(event.occurredAt(), "occurredAt"));
        return index;
    }

    private static OutboxRow readOutboxRow(ResultSet result) throws SQLException {
        try {
            CrossServerEventEnvelope event = new CrossServerEventEnvelope(
                    UUID.fromString(result.getString(1)),
                    result.getString(2),
                    result.getString(3),
                    result.getString(4),
                    result.getString(5),
                    result.getString(6),
                    result.getString(7),
                    result.getInt(8),
                    Instant.ofEpochMilli(result.getLong(9))
            );
            return new OutboxRow(event, result.getInt(12));
        } catch (IllegalArgumentException exception) {
            throw new SQLException("invalid cross-server outbox row", exception);
        }
    }

    private InboxRow findInbox(Connection connection, String consumerId, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql.selectInboxSql())) {
            statement.setString(1, consumerId);
            statement.setString(2, eventId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                try {
                    return new InboxRow(
                            result.getString(1),
                            UUID.fromString(result.getString(2)),
                            result.getString(4),
                            result.getInt(9)
                    );
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("invalid cross-server inbox row", exception);
                }
            }
        }
    }

    private static void requireConnection(Connection connection) {
        Objects.requireNonNull(connection, "connection");
    }

    private static int requireLimit(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        return limit;
    }

    private static void requireAttempt(int attemptCount) {
        if (attemptCount < 1) throw new IllegalArgumentException("expectedAttemptCount must be at least 1");
    }

    private static void requireFencingToken(long fencingToken) {
        if (fencingToken < 1L) throw new IllegalArgumentException("fencingToken must be at least 1");
    }

    private static String checkedError(String error) {
        if (error != null && error.length() > MAX_ERROR_LENGTH) {
            throw new IllegalArgumentException("lastError must be at most " + MAX_ERROR_LENGTH + " characters");
        }
        return error;
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static long epochMillis(Instant instant, String name) {
        try {
            return CrossServerValidation.instant(instant, name).toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is outside the supported epoch-millisecond range", exception);
        }
    }

    private static Instant addDuration(Instant now, Duration duration, String name) {
        Duration checked = CrossServerValidation.positiveDuration(duration, name);
        final long millis;
        try {
            millis = checked.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large", exception);
        }
        if (millis < 1L) throw new IllegalArgumentException(name + " must be at least one millisecond");
        try {
            return now.plusMillis(millis);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException(name + " exceeds the supported time range", exception);
        }
    }

    private static <T> T transactional(Connection connection, SqlWork<T> work) throws SQLException {
        if (!connection.getAutoCommit()) return work.run();
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private record OutboxRow(CrossServerEventEnvelope event, int attemptCount) {
    }

    private record InboxRow(String consumerId, UUID eventId, String status, int attemptCount) {
    }

    private record InboxKey(String consumerId, String eventId) {
    }
}
