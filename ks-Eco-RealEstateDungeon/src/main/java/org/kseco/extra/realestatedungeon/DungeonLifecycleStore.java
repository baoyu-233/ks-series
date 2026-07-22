package org.kseco.extra.realestatedungeon;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** SQL-only lifecycle transitions for dungeon termination. */
final class DungeonLifecycleStore {

    static final String REWARD_NONE = "NONE";
    static final String REWARD_PENDING = "PENDING";
    static final String REWARD_GRANTED = "GRANTED";

    record EndPlan(boolean transitioned, boolean rewardsPending, String worldInstanceId, String templateId,
                   String rewardConfig, List<String> remainingParticipants,
                   List<UUID> rewardParticipants, int plotsDeleted) {
        EndPlan {
            rewardConfig = rewardConfig == null ? "" : rewardConfig;
            remainingParticipants = List.copyOf(remainingParticipants);
            rewardParticipants = List.copyOf(rewardParticipants);
        }
    }

    private record InstanceRow(String status, String rewardStatus, String worldInstanceId, String templateId) { }

    private DungeonLifecycleStore() { }

    static Optional<EndPlan> endInstance(Connection connection, String instanceId,
                                         String finalStatus) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Dungeon lifecycle transition requires an auto-commit connection");
        }
        connection.setAutoCommit(false);
        try {
            InstanceRow row = loadInstance(connection, instanceId);
            if (row == null) {
                connection.rollback();
                return Optional.empty();
            }

            boolean transitioned;
            if (isOpen(row.status())) {
                transitioned = transitionToFinal(connection, instanceId, finalStatus);
                if (!transitioned) {
                    row = loadInstance(connection, instanceId);
                    if (row == null || !finalStatus.equals(row.status())) {
                        connection.rollback();
                        return Optional.empty();
                    }
                } else {
                    row = new InstanceRow(finalStatus, row.rewardStatus(), row.worldInstanceId(), row.templateId());
                }
            } else if (finalStatus.equals(row.status())) {
                transitioned = false;
            } else {
                connection.rollback();
                return Optional.empty();
            }

            List<String> remaining = loadRemainingParticipants(connection, instanceId);
            String rewardConfig = "";
            List<UUID> rewardParticipants = List.of();
            boolean rewardsPending = false;
            if (DungeonInstanceManager.STATUS_COMPLETED.equals(finalStatus)) {
                String rewardStatus = normalizeRewardStatus(row.rewardStatus());
                boolean rosterSnapshottedNow = false;
                if (transitioned && REWARD_NONE.equals(rewardStatus)) {
                    snapshotRewardParticipants(connection, instanceId);
                    markRewardsPending(connection, instanceId);
                    rewardStatus = REWARD_PENDING;
                    rosterSnapshottedNow = true;
                }
                if (REWARD_PENDING.equals(rewardStatus)) {
                    rewardConfig = loadRewardConfig(connection, row.templateId());
                    rewardParticipants = loadRewardParticipants(connection, instanceId);
                    if (rewardConfig.isBlank()
                            || (rosterSnapshottedNow && rewardParticipants.isEmpty() && remaining.isEmpty())) {
                        markRewardsGranted(connection, instanceId);
                    } else {
                        rewardsPending = true;
                    }
                }
            }

            markParticipantsLeft(connection, instanceId);
            int plotsDeleted = deleteInstancePlots(connection, instanceId);
            connection.commit();
            return Optional.of(new EndPlan(transitioned, rewardsPending, row.worldInstanceId(), row.templateId(),
                    rewardConfig, remaining, rewardParticipants, plotsDeleted));
        } catch (SQLException | RuntimeException failure) {
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

    static boolean markRewardsGranted(Connection connection, String instanceId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_instances SET reward_status=? WHERE id=? AND reward_status=?")) {
            statement.setString(1, REWARD_GRANTED);
            statement.setString(2, instanceId);
            statement.setString(3, REWARD_PENDING);
            return statement.executeUpdate() == 1;
        }
    }

    private static void markRewardsPending(Connection connection, String instanceId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_instances SET reward_status=? WHERE id=? AND (reward_status IS NULL OR reward_status=? OR reward_status='')")) {
            statement.setString(1, REWARD_PENDING);
            statement.setString(2, instanceId);
            statement.setString(3, REWARD_NONE);
            statement.executeUpdate();
        }
    }

    private static InstanceRow loadInstance(Connection connection, String instanceId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT status, COALESCE(NULLIF(instance_world_id,''), grid_id), template_id, " +
                        "COALESCE(reward_status, 'NONE') " +
                        "FROM ks_dungeon_instances WHERE id=?")) {
            statement.setString(1, instanceId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new InstanceRow(rows.getString(1), rows.getString(4), rows.getString(2), rows.getString(3));
            }
        } catch (SQLException missingColumn) {
            if (!isMissingColumn(missingColumn, "reward_status")) throw missingColumn;
            try (var statement = connection.prepareStatement(
                    "SELECT status, COALESCE(NULLIF(instance_world_id,''), grid_id), template_id " +
                            "FROM ks_dungeon_instances WHERE id=?")) {
                statement.setString(1, instanceId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return null;
                    return new InstanceRow(rows.getString(1), REWARD_NONE, rows.getString(2), rows.getString(3));
                }
            }
        }
    }

    private static boolean transitionToFinal(Connection connection, String instanceId,
                                             String finalStatus) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_instances SET status=? WHERE id=? AND status IN ('WAITING','ACTIVE')")) {
            statement.setString(1, finalStatus);
            statement.setString(2, instanceId);
            return statement.executeUpdate() == 1;
        }
    }

    private static List<String> loadRemainingParticipants(Connection connection,
                                                          String instanceId) throws SQLException {
        List<String> participants = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT player_uuid FROM ks_dungeon_participants " +
                        "WHERE instance_id=? AND status<>'LEFT'")) {
            statement.setString(1, instanceId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) participants.add(rows.getString(1));
            }
        }
        return participants;
    }

    private static List<UUID> loadRewardParticipants(Connection connection,
                                                     String instanceId) throws SQLException {
        List<UUID> participants = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT player_uuid FROM ks_dungeon_reward_roster WHERE instance_id=?")) {
            statement.setString(1, instanceId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        participants.add(UUID.fromString(rows.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore malformed legacy participant rows.
                    }
                }
            }
        }
        return participants;
    }

    private static void snapshotRewardParticipants(Connection connection,
                                                   String instanceId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_dungeon_reward_roster (instance_id, player_uuid) " +
                        "SELECT instance_id, player_uuid FROM ks_dungeon_participants " +
                        "WHERE instance_id=? AND status<>'LEFT'")) {
            statement.setString(1, instanceId);
            statement.executeUpdate();
        }
    }

    private static String loadRewardConfig(Connection connection, String templateId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT reward_config FROM ks_dungeon_templates WHERE id=?")) {
            statement.setString(1, templateId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return "";
                String value = rows.getString(1);
                return value == null ? "" : value;
            }
        }
    }

    private static void markParticipantsLeft(Connection connection, String instanceId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_participants SET status='LEFT' " +
                        "WHERE instance_id=? AND status<>'LEFT'")) {
            statement.setString(1, instanceId);
            statement.executeUpdate();
        }
    }

    private static int deleteInstancePlots(Connection connection, String instanceId) throws SQLException {
        if (!tableExists(connection, "ks_re_plots")) return 0;
        try (var statement = connection.prepareStatement(
                "DELETE FROM ks_re_plots WHERE instance_id=?")) {
            statement.setString(1, instanceId);
            return statement.executeUpdate();
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(
                null, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private static boolean isOpen(String status) {
        return DungeonInstanceManager.STATUS_WAITING.equals(status)
                || DungeonInstanceManager.STATUS_ACTIVE.equals(status);
    }

    private static String normalizeRewardStatus(String rewardStatus) {
        if (rewardStatus == null || rewardStatus.isBlank()) return REWARD_NONE;
        return rewardStatus;
    }

    private static boolean isMissingColumn(SQLException exception, String column) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(column.toLowerCase(Locale.ROOT));
    }
}
