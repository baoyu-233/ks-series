package org.kseco.demand;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/** Pure JDBC reservation foundation for finite regional resource demand campaigns. */
public final class JdbcDemandCampaignStore implements AutoCloseable {
    private static final int MAX_WRITE_ATTEMPTS = 20;

    private final DemandConnectionSupplier connections;
    private final Clock clock;
    private volatile boolean closed;

    public JdbcDemandCampaignStore(DemandConnectionSupplier connections) {
        this(connections, Clock.systemUTC());
    }

    public JdbcDemandCampaignStore(DemandConnectionSupplier connections, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void initializeSchema() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ks_demand_campaigns (
                            campaign_id VARCHAR(128) NOT NULL PRIMARY KEY,
                            region_id VARCHAR(128) NOT NULL,
                            item_signature VARCHAR(512) NOT NULL,
                            target_quantity BIGINT NOT NULL,
                            remaining_quantity BIGINT NOT NULL,
                            budget_minor BIGINT NOT NULL,
                            remaining_budget_minor BIGINT NOT NULL,
                            unit_price_minor BIGINT NOT NULL,
                            per_player_limit BIGINT NOT NULL,
                            currency_id VARCHAR(32) NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            version BIGINT NOT NULL,
                            starts_at BIGINT NOT NULL,
                            ends_at BIGINT NOT NULL,
                            created_at BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL,
                            CHECK (target_quantity > 0),
                            CHECK (remaining_quantity >= 0 AND remaining_quantity <= target_quantity),
                            CHECK (budget_minor > 0),
                            CHECK (remaining_budget_minor >= 0 AND remaining_budget_minor <= budget_minor),
                            CHECK (unit_price_minor > 0),
                            CHECK (per_player_limit > 0 AND per_player_limit <= target_quantity),
                            CHECK (currency_id = 'CASH'),
                            CHECK (ends_at > starts_at)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ks_demand_player_usage (
                            campaign_id VARCHAR(128) NOT NULL,
                            player_uuid VARCHAR(36) NOT NULL,
                            accepted_quantity BIGINT NOT NULL,
                            payout_minor BIGINT NOT NULL,
                            version BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL,
                            PRIMARY KEY (campaign_id, player_uuid),
                            CHECK (accepted_quantity >= 0),
                            CHECK (payout_minor >= 0)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ks_demand_operations (
                            operation_hash VARCHAR(64) NOT NULL PRIMARY KEY,
                            operation_id VARCHAR(128) NOT NULL,
                            request_hash VARCHAR(64) NOT NULL,
                            campaign_id VARCHAR(128) NOT NULL,
                            player_uuid VARCHAR(36) NOT NULL,
                            item_signature VARCHAR(512) NOT NULL,
                            requested_quantity BIGINT NOT NULL,
                            unit_price_minor BIGINT NOT NULL,
                            currency_id VARCHAR(32) NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            result_code VARCHAR(32) NOT NULL,
                            accepted_quantity BIGINT NOT NULL,
                            payout_minor BIGINT NOT NULL,
                            created_at BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL,
                            CHECK (requested_quantity > 0),
                            CHECK (unit_price_minor > 0),
                            CHECK (accepted_quantity >= 0),
                            CHECK (payout_minor >= 0)
                        )
                        """);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    public void createCampaign(DemandCampaignDefinition definition) throws SQLException {
        Objects.requireNonNull(definition, "definition");
        long now = clock.millis();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ks_demand_campaigns (
                    campaign_id, region_id, item_signature, target_quantity, remaining_quantity,
                    budget_minor, remaining_budget_minor, unit_price_minor, per_player_limit,
                    currency_id, status, version, starts_at, ends_at, created_at, updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, definition.campaignId());
            statement.setString(2, definition.regionId());
            statement.setString(3, definition.itemSignature().value());
            statement.setLong(4, definition.targetQuantity());
            statement.setLong(5, definition.targetQuantity());
            statement.setLong(6, definition.budgetMinor());
            statement.setLong(7, definition.budgetMinor());
            statement.setLong(8, definition.unitPriceMinor());
            statement.setLong(9, definition.perPlayerLimit());
            statement.setString(10, definition.currencyId());
            statement.setString(11, definition.initialStatus().name());
            statement.setLong(12, 0L);
            statement.setLong(13, definition.startsAtEpochMillis());
            statement.setLong(14, definition.endsAtEpochMillis());
            statement.setLong(15, now);
            statement.setLong(16, now);
            statement.executeUpdate();
        }
    }

    public Optional<DemandCampaignSnapshot> findCampaign(String campaignId) throws SQLException {
        String checkedId = DemandValidation.requireIdentifier(campaignId, "campaignId", 128);
        try (Connection connection = openConnection()) {
            return Optional.ofNullable(readCampaign(connection, checkedId));
        }
    }

    public boolean compareAndSetStatus(
            String campaignId,
            long expectedVersion,
            DemandCampaignStatus expectedStatus,
            DemandCampaignStatus nextStatus
    ) throws SQLException {
        String checkedId = DemandValidation.requireIdentifier(campaignId, "campaignId", 128);
        if (expectedVersion < 0L) throw new IllegalArgumentException("expectedVersion cannot be negative");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(nextStatus, "nextStatus");
        if (!expectedStatus.canTransitionTo(nextStatus)) {
            throw new IllegalArgumentException("invalid demand campaign status transition");
        }
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_demand_campaigns SET status=?, version=version+1, updated_at=? "
                        + "WHERE campaign_id=? AND status=? AND version=?")) {
            statement.setString(1, nextStatus.name());
            statement.setLong(2, clock.millis());
            statement.setString(3, checkedId);
            statement.setString(4, expectedStatus.name());
            statement.setLong(5, expectedVersion);
            return statement.executeUpdate() == 1;
        }
    }

    public DemandSubmissionResult submit(DemandSubmission submission) throws SQLException {
        Objects.requireNonNull(submission, "submission");
        String requestHash = requestHash(submission);
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            try {
                return submitOnce(submission, requestHash);
            } catch (RetryableWriteConflict conflict) {
                // Re-open the transaction and evaluate against a fresh campaign and quota snapshot.
            } catch (SQLException exception) {
                if (!isRetryable(exception) || attempt == MAX_WRITE_ATTEMPTS) throw exception;
                lastFailure = exception;
            }
            long delayMillis = Math.min(50L, attempt * 5L);
            LockSupport.parkNanos(delayMillis * 1_000_000L);
        }
        throw lastFailure != null ? lastFailure : new SQLException("demand campaign write conflict");
    }

    public long playerAcceptedQuantity(String campaignId, UUID playerId) throws SQLException {
        String checkedId = DemandValidation.requireIdentifier(campaignId, "campaignId", 128);
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT accepted_quantity FROM ks_demand_player_usage WHERE campaign_id=? AND player_uuid=?")) {
            statement.setString(1, checkedId);
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    private DemandSubmissionResult submitOnce(DemandSubmission submission, String requestHash) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                long now = clock.millis();
                try {
                    insertPendingOperation(connection, submission, requestHash, now);
                } catch (SQLException exception) {
                    if (!isConstraintViolation(exception)) throw exception;
                    connection.rollback();
                    return loadExistingOperation(submission, requestHash);
                }

                if (!DemandCampaignDefinition.SUPPORTED_CURRENCY_ID.equals(submission.currencyId())) {
                    return reject(connection, submission.operationId(), DemandSubmissionResult.Code.UNSUPPORTED_CURRENCY, now);
                }

                DemandCampaignSnapshot campaign = readCampaign(connection, submission.campaignId());
                if (campaign == null) {
                    return reject(connection, submission.operationId(), DemandSubmissionResult.Code.CAMPAIGN_NOT_FOUND, now);
                }
                if (!DemandCampaignDefinition.SUPPORTED_CURRENCY_ID.equals(campaign.currencyId())) {
                    return reject(connection, submission.operationId(), DemandSubmissionResult.Code.UNSUPPORTED_CURRENCY, now);
                }
                if (!campaign.itemSignature().equals(submission.itemSignature())) {
                    return reject(connection, submission.operationId(), DemandSubmissionResult.Code.ITEM_MISMATCH, now);
                }
                if (campaign.unitPriceMinor() != submission.unitPriceMinor()) {
                    return reject(connection, submission.operationId(), DemandSubmissionResult.Code.PRICE_MISMATCH, now);
                }
                if (now >= campaign.endsAtEpochMillis()) {
                    if (campaign.status().canTransitionTo(DemandCampaignStatus.EXPIRED)
                            && !updateStatus(connection, campaign, DemandCampaignStatus.EXPIRED, now)) {
                        throw new RetryableWriteConflict();
                    }
                    return reject(connection, submission.operationId(), DemandSubmissionResult.Code.CAMPAIGN_EXPIRED, now);
                }
                if (now < campaign.startsAtEpochMillis()) {
                    return reject(connection, submission.operationId(), DemandSubmissionResult.Code.CAMPAIGN_NOT_STARTED, now);
                }
                if (campaign.status() != DemandCampaignStatus.ACTIVE) {
                    DemandSubmissionResult.Code code = campaign.status() == DemandCampaignStatus.COMPLETED
                            ? DemandSubmissionResult.Code.CAMPAIGN_EXHAUSTED
                            : DemandSubmissionResult.Code.CAMPAIGN_NOT_ACTIVE;
                    return reject(connection, submission.operationId(), code, now);
                }

                PlayerUsage usage = ensureAndReadPlayerUsage(connection, campaign.campaignId(), submission.playerId(), now);
                long playerRemaining = campaign.perPlayerLimit() - usage.acceptedQuantity();
                if (playerRemaining <= 0L) {
                    return reject(connection, submission.operationId(), DemandSubmissionResult.Code.PLAYER_LIMIT_REACHED, now);
                }
                long affordableQuantity = campaign.remainingBudgetMinor() / campaign.unitPriceMinor();
                long acceptedQuantity = minimum(
                        submission.quantity(), campaign.remainingQuantity(), playerRemaining, affordableQuantity);
                if (acceptedQuantity <= 0L) {
                    if (!updateStatus(connection, campaign, DemandCampaignStatus.COMPLETED, now)) {
                        throw new RetryableWriteConflict();
                    }
                    return reject(connection, submission.operationId(), DemandSubmissionResult.Code.CAMPAIGN_EXHAUSTED, now);
                }

                long payoutMinor = Math.multiplyExact(acceptedQuantity, campaign.unitPriceMinor());
                long nextRemaining = campaign.remainingQuantity() - acceptedQuantity;
                long nextBudget = campaign.remainingBudgetMinor() - payoutMinor;
                DemandCampaignStatus nextStatus = nextRemaining == 0L || nextBudget < campaign.unitPriceMinor()
                        ? DemandCampaignStatus.COMPLETED
                        : DemandCampaignStatus.ACTIVE;

                if (!updateCampaignReservation(connection, campaign, nextRemaining, nextBudget, nextStatus, now)) {
                    throw new RetryableWriteConflict();
                }
                if (!updatePlayerUsage(connection, campaign.campaignId(), submission.playerId(), usage,
                        acceptedQuantity, payoutMinor, now)) {
                    throw new RetryableWriteConflict();
                }

                DemandSubmissionResult.Code code = acceptedQuantity == submission.quantity()
                        ? DemandSubmissionResult.Code.ACCEPTED
                        : DemandSubmissionResult.Code.PARTIALLY_ACCEPTED;
                finishOperation(connection, submission.operationId(), "APPLIED", code,
                        acceptedQuantity, payoutMinor, now);
                connection.commit();
                return DemandSubmissionResult.accepted(code, false, acceptedQuantity, payoutMinor);
            } catch (RetryableWriteConflict conflict) {
                connection.rollback();
                throw conflict;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private void insertPendingOperation(
            Connection connection,
            DemandSubmission submission,
            String requestHash,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ks_demand_operations (
                    operation_hash, operation_id, request_hash, campaign_id, player_uuid,
                    item_signature, requested_quantity, unit_price_minor, currency_id,
                    status, result_code, accepted_quantity, payout_minor, created_at, updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, operationHash(submission.operationId()));
            statement.setString(2, submission.operationId().value());
            statement.setString(3, requestHash);
            statement.setString(4, submission.campaignId());
            statement.setString(5, submission.playerId().toString());
            statement.setString(6, submission.itemSignature().value());
            statement.setLong(7, submission.quantity());
            statement.setLong(8, submission.unitPriceMinor());
            statement.setString(9, submission.currencyId());
            statement.setString(10, "PENDING");
            statement.setString(11, "PENDING");
            statement.setLong(12, 0L);
            statement.setLong(13, 0L);
            statement.setLong(14, now);
            statement.setLong(15, now);
            statement.executeUpdate();
        }
    }

    private DemandSubmissionResult loadExistingOperation(DemandSubmission submission, String requestHash)
            throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT operation_id, request_hash, status, result_code, accepted_quantity, payout_minor "
                        + "FROM ks_demand_operations WHERE operation_hash=?")) {
            statement.setString(1, operationHash(submission.operationId()));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new RetryableWriteConflict();
                if (!submission.operationId().value().equals(result.getString(1))
                        || !requestHash.equals(result.getString(2))) {
                    return DemandSubmissionResult.conflict();
                }
                String status = result.getString(3);
                if ("PENDING".equals(status)) throw new RetryableWriteConflict();
                DemandSubmissionResult.Code code = DemandSubmissionResult.Code.valueOf(result.getString(4));
                if ("APPLIED".equals(status)) {
                    return DemandSubmissionResult.accepted(
                            code, true, result.getLong(5), result.getLong(6));
                }
                if ("REJECTED".equals(status)) return DemandSubmissionResult.rejected(code, true);
                throw new SQLException("unknown demand operation status: " + status);
            }
        }
    }

    private DemandSubmissionResult reject(
            Connection connection,
            DemandOperationId operationId,
            DemandSubmissionResult.Code code,
            long now
    ) throws SQLException {
        finishOperation(connection, operationId, "REJECTED", code, 0L, 0L, now);
        connection.commit();
        return DemandSubmissionResult.rejected(code, false);
    }

    private void finishOperation(
            Connection connection,
            DemandOperationId operationId,
            String status,
            DemandSubmissionResult.Code code,
            long acceptedQuantity,
            long payoutMinor,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_demand_operations SET status=?, result_code=?, accepted_quantity=?, "
                        + "payout_minor=?, updated_at=? WHERE operation_hash=? AND status='PENDING'")) {
            statement.setString(1, status);
            statement.setString(2, code.name());
            statement.setLong(3, acceptedQuantity);
            statement.setLong(4, payoutMinor);
            statement.setLong(5, now);
            statement.setString(6, operationHash(operationId));
            if (statement.executeUpdate() != 1) throw new SQLException("demand operation disappeared");
        }
    }

    private PlayerUsage ensureAndReadPlayerUsage(
            Connection connection,
            String campaignId,
            UUID playerId,
            long now
    ) throws SQLException {
        PlayerUsage existing = findPlayerUsage(connection, campaignId, playerId);
        if (existing != null) return existing;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ks_demand_player_usage "
                        + "(campaign_id, player_uuid, accepted_quantity, payout_minor, version, updated_at) "
                        + "VALUES (?,?,?,?,?,?)")) {
            insert.setString(1, campaignId);
            insert.setString(2, playerId.toString());
            insert.setLong(3, 0L);
            insert.setLong(4, 0L);
            insert.setLong(5, 0L);
            insert.setLong(6, now);
            try {
                insert.executeUpdate();
            } catch (SQLException exception) {
                if (!isConstraintViolation(exception)) throw exception;
                throw new RetryableWriteConflict();
            }
        }
        return readPlayerUsage(connection, campaignId, playerId);
    }

    private PlayerUsage findPlayerUsage(Connection connection, String campaignId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT accepted_quantity, payout_minor, version FROM ks_demand_player_usage "
                        + "WHERE campaign_id=? AND player_uuid=?")) {
            statement.setString(1, campaignId);
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new PlayerUsage(result.getLong(1), result.getLong(2), result.getLong(3)) : null;
            }
        }
    }

    private PlayerUsage readPlayerUsage(Connection connection, String campaignId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT accepted_quantity, payout_minor, version FROM ks_demand_player_usage "
                        + "WHERE campaign_id=? AND player_uuid=?")) {
            statement.setString(1, campaignId);
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("demand player quota row was not created");
                return new PlayerUsage(result.getLong(1), result.getLong(2), result.getLong(3));
            }
        }
    }

    private boolean updatePlayerUsage(
            Connection connection,
            String campaignId,
            UUID playerId,
            PlayerUsage usage,
            long acceptedQuantity,
            long payoutMinor,
            long now
    ) throws SQLException {
        long nextQuantity = Math.addExact(usage.acceptedQuantity(), acceptedQuantity);
        long nextPayout = Math.addExact(usage.payoutMinor(), payoutMinor);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_demand_player_usage SET accepted_quantity=?, payout_minor=?, version=?, updated_at=? "
                        + "WHERE campaign_id=? AND player_uuid=? AND accepted_quantity=? AND payout_minor=? AND version=?")) {
            statement.setLong(1, nextQuantity);
            statement.setLong(2, nextPayout);
            statement.setLong(3, Math.incrementExact(usage.version()));
            statement.setLong(4, now);
            statement.setString(5, campaignId);
            statement.setString(6, playerId.toString());
            statement.setLong(7, usage.acceptedQuantity());
            statement.setLong(8, usage.payoutMinor());
            statement.setLong(9, usage.version());
            return statement.executeUpdate() == 1;
        }
    }

    private boolean updateCampaignReservation(
            Connection connection,
            DemandCampaignSnapshot campaign,
            long nextRemaining,
            long nextBudget,
            DemandCampaignStatus nextStatus,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_demand_campaigns SET remaining_quantity=?, remaining_budget_minor=?, "
                        + "status=?, version=?, updated_at=? WHERE campaign_id=? AND target_quantity=? "
                        + "AND remaining_quantity=? AND budget_minor=? AND remaining_budget_minor=? "
                        + "AND status=? AND version=?")) {
            statement.setLong(1, nextRemaining);
            statement.setLong(2, nextBudget);
            statement.setString(3, nextStatus.name());
            statement.setLong(4, Math.incrementExact(campaign.version()));
            statement.setLong(5, now);
            statement.setString(6, campaign.campaignId());
            statement.setLong(7, campaign.targetQuantity());
            statement.setLong(8, campaign.remainingQuantity());
            statement.setLong(9, campaign.budgetMinor());
            statement.setLong(10, campaign.remainingBudgetMinor());
            statement.setString(11, DemandCampaignStatus.ACTIVE.name());
            statement.setLong(12, campaign.version());
            return statement.executeUpdate() == 1;
        }
    }

    private boolean updateStatus(
            Connection connection,
            DemandCampaignSnapshot campaign,
            DemandCampaignStatus nextStatus,
            long now
    ) throws SQLException {
        if (!campaign.status().canTransitionTo(nextStatus)) return campaign.status() == nextStatus;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_demand_campaigns SET status=?, version=?, updated_at=? "
                        + "WHERE campaign_id=? AND status=? AND version=?")) {
            statement.setString(1, nextStatus.name());
            statement.setLong(2, Math.incrementExact(campaign.version()));
            statement.setLong(3, now);
            statement.setString(4, campaign.campaignId());
            statement.setString(5, campaign.status().name());
            statement.setLong(6, campaign.version());
            return statement.executeUpdate() == 1;
        }
    }

    private DemandCampaignSnapshot readCampaign(Connection connection, String campaignId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT campaign_id, region_id, item_signature, target_quantity, remaining_quantity,
                       budget_minor, remaining_budget_minor, unit_price_minor, per_player_limit,
                       currency_id, status, version, starts_at, ends_at, created_at, updated_at
                FROM ks_demand_campaigns WHERE campaign_id=?
                """)) {
            statement.setString(1, campaignId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new DemandCampaignSnapshot(
                        result.getString(1),
                        result.getString(2),
                        new StandardItemSignature(result.getString(3)),
                        result.getLong(4),
                        result.getLong(5),
                        result.getLong(6),
                        result.getLong(7),
                        result.getLong(8),
                        result.getLong(9),
                        result.getString(10),
                        DemandCampaignStatus.valueOf(result.getString(11)),
                        result.getLong(12),
                        result.getLong(13),
                        result.getLong(14),
                        result.getLong(15),
                        result.getLong(16));
            }
        }
    }

    private Connection openConnection() throws SQLException {
        if (closed) throw new SQLException("demand campaign store is closed");
        Connection connection = connections.openConnection();
        if (connection == null) throw new SQLException("demand connection supplier returned null");
        return connection;
    }

    private static long minimum(long first, long second, long third, long fourth) {
        return Math.min(Math.min(first, second), Math.min(third, fourth));
    }

    private static String requestHash(DemandSubmission submission) {
        MessageDigest digest = sha256();
        update(digest, submission.campaignId());
        update(digest, submission.playerId().toString());
        update(digest, submission.itemSignature().value());
        update(digest, submission.quantity());
        update(digest, submission.unitPriceMinor());
        update(digest, submission.currencyId());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String operationHash(DemandOperationId operationId) {
        return HexFormat.of().formatHex(sha256().digest(operationId.value().getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static boolean isConstraintViolation(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("23")) return true;
            if (current.getErrorCode() == 19) return true;
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("unique constraint") || lower.contains("duplicate key")) return true;
            }
        }
        return false;
    }

    private static boolean isRetryable(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && (state.equals("40001") || state.equals("40P01"))) return true;
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("database is locked") || lower.contains("database is busy")) return true;
            }
        }
        return false;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record PlayerUsage(long acceptedQuantity, long payoutMinor, long version) {}

    private static final class RetryableWriteConflict extends RuntimeException {}
}
