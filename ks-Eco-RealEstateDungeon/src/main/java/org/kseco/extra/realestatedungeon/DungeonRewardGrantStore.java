package org.kseco.extra.realestatedungeon;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** SQL-only, per-player reward delivery state. Safe to invoke from database workers. */
final class DungeonRewardGrantStore {

    static final String STATUS_NONE = "NONE";
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_GRANTED = "GRANTED";
    static final String STATUS_RETRY_REQUIRED = "RETRY_REQUIRED";

    private static final int MAX_INSTANCE_ID_LENGTH = 128;
    private static final int MAX_REWARD_KEY_LENGTH = 191;
    private static final int MAX_ERROR_LENGTH = 1024;

    enum ClaimResult {
        CLAIMED,
        IN_PROGRESS,
        ALREADY_GRANTED
    }

    record Grant(String instanceId, UUID playerUuid, String rewardKey, String status,
                 int attemptCount, String lastError, long createdAt, long updatedAt) {
        Grant {
            lastError = lastError == null ? "" : lastError;
        }
    }

    private DungeonRewardGrantStore() { }

    static void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_dungeon_reward_grants (
                        instance_id VARCHAR(128) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        reward_key VARCHAR(191) NOT NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'NONE',
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        last_error VARCHAR(1024) NOT NULL DEFAULT '',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY(instance_id, player_uuid, reward_key)
                    )
                    """);
        }
    }

    static Grant ensure(Connection connection, String instanceId, UUID playerUuid,
                        String rewardKey, long now) throws SQLException {
        String normalizedInstanceId = requireInstanceId(instanceId);
        UUID requiredPlayerUuid = requirePlayerUuid(playerUuid);
        String normalizedRewardKey = normalizeRewardKey(rewardKey);

        Optional<Grant> existing = find(connection, normalizedInstanceId, requiredPlayerUuid,
                normalizedRewardKey);
        if (existing.isPresent()) return existing.get();

        Savepoint insertionSavepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_dungeon_reward_grants " +
                        "(instance_id, player_uuid, reward_key, status, attempt_count, last_error, created_at, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setString(1, normalizedInstanceId);
            statement.setString(2, requiredPlayerUuid.toString());
            statement.setString(3, normalizedRewardKey);
            statement.setString(4, STATUS_NONE);
            statement.setInt(5, 0);
            statement.setString(6, "");
            statement.setLong(7, now);
            statement.setLong(8, now);
            statement.executeUpdate();
        } catch (SQLException insertionFailure) {
            if (insertionSavepoint != null) {
                try {
                    connection.rollback(insertionSavepoint);
                } catch (SQLException rollbackFailure) {
                    insertionFailure.addSuppressed(rollbackFailure);
                    throw insertionFailure;
                }
            }
            Optional<Grant> raced = find(connection, normalizedInstanceId, requiredPlayerUuid,
                    normalizedRewardKey);
            if (raced.isPresent()) return raced.get();
            throw insertionFailure;
        } finally {
            if (insertionSavepoint != null) {
                try {
                    connection.releaseSavepoint(insertionSavepoint);
                } catch (SQLException ignored) {
                    // Some drivers release savepoints automatically after rollback.
                }
            }
        }
        return find(connection, normalizedInstanceId, requiredPlayerUuid, normalizedRewardKey)
                .orElseThrow(() -> new SQLException("Reward grant insert did not persist"));
    }

    static ClaimResult claim(Connection connection, String instanceId, UUID playerUuid,
                             String rewardKey, long now) throws SQLException {
        Grant grant = ensure(connection, instanceId, playerUuid, rewardKey, now);
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_reward_grants SET status=?, attempt_count=attempt_count+1, " +
                        "last_error='', updated_at=? WHERE instance_id=? AND player_uuid=? AND reward_key=? " +
                        "AND status IN (?,?)")) {
            statement.setString(1, STATUS_PENDING);
            statement.setLong(2, now);
            statement.setString(3, grant.instanceId());
            statement.setString(4, grant.playerUuid().toString());
            statement.setString(5, grant.rewardKey());
            statement.setString(6, STATUS_NONE);
            statement.setString(7, STATUS_RETRY_REQUIRED);
            if (statement.executeUpdate() == 1) return ClaimResult.CLAIMED;
        }

        String status = find(connection, grant.instanceId(), grant.playerUuid(), grant.rewardKey())
                .map(Grant::status)
                .orElseThrow(() -> new SQLException("Reward grant disappeared during claim"));
        return STATUS_GRANTED.equals(status) ? ClaimResult.ALREADY_GRANTED : ClaimResult.IN_PROGRESS;
    }

    static boolean complete(Connection connection, String instanceId, UUID playerUuid,
                            String rewardKey, long now) throws SQLException {
        GrantKey key = key(instanceId, playerUuid, rewardKey);
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_reward_grants SET status=?, last_error='', updated_at=? " +
                        "WHERE instance_id=? AND player_uuid=? AND reward_key=? AND status=?")) {
            bindTransition(statement, STATUS_GRANTED, now, key, STATUS_PENDING);
            if (statement.executeUpdate() == 1) return true;
        }
        return find(connection, key.instanceId(), key.playerUuid(), key.rewardKey())
                .map(grant -> STATUS_GRANTED.equals(grant.status()))
                .orElse(false);
    }

    static boolean fail(Connection connection, String instanceId, UUID playerUuid,
                        String rewardKey, String error, long now) throws SQLException {
        GrantKey key = key(instanceId, playerUuid, rewardKey);
        String normalizedError = normalizeError(error);
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_reward_grants SET status=?, last_error=?, updated_at=? " +
                        "WHERE instance_id=? AND player_uuid=? AND reward_key=? AND status=?")) {
            statement.setString(1, STATUS_RETRY_REQUIRED);
            statement.setString(2, normalizedError);
            statement.setLong(3, now);
            statement.setString(4, key.instanceId());
            statement.setString(5, key.playerUuid().toString());
            statement.setString(6, key.rewardKey());
            statement.setString(7, STATUS_PENDING);
            if (statement.executeUpdate() == 1) return true;
        }
        return find(connection, key.instanceId(), key.playerUuid(), key.rewardKey())
                .map(grant -> STATUS_RETRY_REQUIRED.equals(grant.status())
                        && normalizedError.equals(grant.lastError()))
                .orElse(false);
    }

    static boolean markReviewRequired(Connection connection, String instanceId, UUID playerUuid,
                                      String rewardKey, String error, long now) throws SQLException {
        GrantKey key = key(instanceId, playerUuid, rewardKey);
        String normalizedError = normalizeError(error);
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_reward_grants SET last_error=?, updated_at=? " +
                        "WHERE instance_id=? AND player_uuid=? AND reward_key=? AND status=?")) {
            statement.setString(1, normalizedError);
            statement.setLong(2, now);
            statement.setString(3, key.instanceId());
            statement.setString(4, key.playerUuid().toString());
            statement.setString(5, key.rewardKey());
            statement.setString(6, STATUS_PENDING);
            return statement.executeUpdate() == 1;
        }
    }

    static Optional<Grant> find(Connection connection, String instanceId, UUID playerUuid,
                                String rewardKey) throws SQLException {
        GrantKey key = key(instanceId, playerUuid, rewardKey);
        try (var statement = connection.prepareStatement(
                "SELECT instance_id, player_uuid, reward_key, status, attempt_count, last_error, " +
                        "created_at, updated_at FROM ks_dungeon_reward_grants " +
                        "WHERE instance_id=? AND player_uuid=? AND reward_key=?")) {
            statement.setString(1, key.instanceId());
            statement.setString(2, key.playerUuid().toString());
            statement.setString(3, key.rewardKey());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    static List<Grant> listClaimable(Connection connection, String instanceId) throws SQLException {
        String normalizedInstanceId = requireInstanceId(instanceId);
        List<Grant> grants = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT instance_id, player_uuid, reward_key, status, attempt_count, last_error, " +
                        "created_at, updated_at FROM ks_dungeon_reward_grants " +
                        "WHERE instance_id=? AND status IN (?,?) ORDER BY player_uuid, reward_key")) {
            statement.setString(1, normalizedInstanceId);
            statement.setString(2, STATUS_NONE);
            statement.setString(3, STATUS_RETRY_REQUIRED);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) grants.add(read(rows));
            }
        }
        return List.copyOf(grants);
    }

    static List<Grant> listForInstance(Connection connection, String instanceId) throws SQLException {
        String normalizedInstanceId = requireInstanceId(instanceId);
        List<Grant> grants = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT instance_id, player_uuid, reward_key, status, attempt_count, last_error, " +
                        "created_at, updated_at FROM ks_dungeon_reward_grants " +
                        "WHERE instance_id=? ORDER BY player_uuid, reward_key")) {
            statement.setString(1, normalizedInstanceId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) grants.add(read(rows));
            }
        }
        return List.copyOf(grants);
    }

    static List<String> rearmOfflineDeliveries(Connection connection, UUID playerUuid, long now) throws SQLException {
        UUID requiredPlayerUuid = requirePlayerUuid(playerUuid);
        List<String> instances = new ArrayList<>();
        try (var select = connection.prepareStatement(
                "SELECT DISTINCT instance_id FROM ks_dungeon_reward_grants " +
                        "WHERE player_uuid=? AND status IN (?,?) " +
                        "AND (last_error LIKE '%online_only_target_offline%' " +
                        "OR last_error LIKE '%proof_target_offline=%')")) {
            select.setString(1, requiredPlayerUuid.toString());
            select.setString(2, STATUS_PENDING);
            select.setString(3, STATUS_RETRY_REQUIRED);
            try (var rows = select.executeQuery()) {
                while (rows.next()) instances.add(rows.getString(1));
            }
        }
        if (instances.isEmpty()) return List.of();
        try (var update = connection.prepareStatement(
                "UPDATE ks_dungeon_reward_grants SET status=?, attempt_count=0, updated_at=? " +
                        "WHERE player_uuid=? AND status IN (?,?) " +
                        "AND (last_error LIKE '%online_only_target_offline%' " +
                        "OR last_error LIKE '%proof_target_offline=%')")) {
            update.setString(1, STATUS_RETRY_REQUIRED);
            update.setLong(2, now);
            update.setString(3, requiredPlayerUuid.toString());
            update.setString(4, STATUS_PENDING);
            update.setString(5, STATUS_RETRY_REQUIRED);
            update.executeUpdate();
        }
        return List.copyOf(instances);
    }

    static boolean allGranted(Connection connection, String instanceId) throws SQLException {
        String normalizedInstanceId = requireInstanceId(instanceId);
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*), SUM(CASE WHEN status=? THEN 0 ELSE 1 END) " +
                        "FROM ks_dungeon_reward_grants WHERE instance_id=?")) {
            statement.setString(1, STATUS_GRANTED);
            statement.setString(2, normalizedInstanceId);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getLong(1) > 0 && rows.getLong(2) == 0;
            }
        }
    }

    private static void bindTransition(java.sql.PreparedStatement statement, String nextStatus,
                                       long now, GrantKey key, String requiredStatus) throws SQLException {
        statement.setString(1, nextStatus);
        statement.setLong(2, now);
        statement.setString(3, key.instanceId());
        statement.setString(4, key.playerUuid().toString());
        statement.setString(5, key.rewardKey());
        statement.setString(6, requiredStatus);
    }

    private static Grant read(ResultSet rows) throws SQLException {
        return new Grant(rows.getString("instance_id"), UUID.fromString(rows.getString("player_uuid")),
                rows.getString("reward_key"), rows.getString("status"), rows.getInt("attempt_count"),
                rows.getString("last_error"), rows.getLong("created_at"), rows.getLong("updated_at"));
    }

    private static GrantKey key(String instanceId, UUID playerUuid, String rewardKey) {
        return new GrantKey(requireInstanceId(instanceId), requirePlayerUuid(playerUuid),
                normalizeRewardKey(rewardKey));
    }

    private static String requireInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId is required");
        }
        String normalized = instanceId.trim();
        if (normalized.length() > MAX_INSTANCE_ID_LENGTH) {
            throw new IllegalArgumentException("instanceId is too long");
        }
        return normalized;
    }

    private static UUID requirePlayerUuid(UUID playerUuid) {
        if (playerUuid == null) throw new IllegalArgumentException("playerUuid is required");
        return playerUuid;
    }

    private static String normalizeRewardKey(String rewardKey) {
        if (rewardKey == null || rewardKey.isBlank()) {
            throw new IllegalArgumentException("rewardKey is required");
        }
        String normalized = rewardKey.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_REWARD_KEY_LENGTH) {
            throw new IllegalArgumentException("rewardKey is too long");
        }
        if (!normalized.matches("[a-z0-9_.:/-]+")) {
            throw new IllegalArgumentException("rewardKey contains unsupported characters");
        }
        return normalized;
    }

    private static String normalizeError(String error) {
        if (error == null) return "";
        String normalized = error.trim();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized : normalized.substring(0, MAX_ERROR_LENGTH);
    }

    private record GrantKey(String instanceId, UUID playerUuid, String rewardKey) { }
}
