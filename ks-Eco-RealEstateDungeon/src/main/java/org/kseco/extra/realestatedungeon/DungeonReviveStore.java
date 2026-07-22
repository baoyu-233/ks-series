package org.kseco.extra.realestatedungeon;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL-only state machine for paid revive return and compensation. */
final class DungeonReviveStore {

    static final String CHARGE_READY = "CHARGE_READY";
    static final String CHARGE_CLAIMED = "CHARGE_CLAIMED";
    static final String PAID_PENDING = "PAID_PENDING";
    static final String REFUND_READY = "REFUND_READY";
    static final String REFUND_CLAIMED = "REFUND_CLAIMED";
    static final String RETURNED = "RETURNED";
    static final String CHARGE_REJECTED = "CHARGE_REJECTED";
    static final String REFUNDED = "REFUNDED";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    record Revival(String id, String instanceId, UUID playerUuid, int reviveCount,
                   double costPaid, String returnStatus, String lastError) { }
    record Candidate(String instanceStatus, String participantStatus, int reviveCount) { }

    private DungeonReviveStore() { }

    static Candidate candidate(Connection connection, String instanceId, UUID playerUuid) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT i.status,p.status,p.revive_count FROM ks_dungeon_instances i " +
                        "JOIN ks_dungeon_participants p ON p.instance_id=i.id " +
                        "WHERE i.id=? AND p.player_uuid=?")) {
            statement.setString(1, instanceId);
            statement.setString(2, playerUuid.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new Candidate(rows.getString(1), rows.getString(2), rows.getInt(3));
            }
        }
    }

    static Revival reserve(Connection connection, String instanceId, UUID playerUuid,
                           int expectedReviveCount, int nextReviveCount,
                           double formulaCost, long now) throws SQLException {
        requireAutoCommit(connection);
        String revivalId = "DR-" + UUID.randomUUID();
        connection.setAutoCommit(false);
        try {
            try (var participant = connection.prepareStatement(
                    "UPDATE ks_dungeon_participants SET status='REVIVE_PENDING', revive_count=? " +
                            "WHERE instance_id=? AND player_uuid=? AND status='DEAD' AND revive_count=? " +
                            "AND EXISTS (SELECT 1 FROM ks_dungeon_instances i WHERE i.id=? AND i.status='ACTIVE')")) {
                participant.setInt(1, nextReviveCount);
                participant.setString(2, instanceId);
                participant.setString(3, playerUuid.toString());
                participant.setInt(4, expectedReviveCount);
                participant.setString(5, instanceId);
                if (participant.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
            }
            try (var revival = connection.prepareStatement(
                    "INSERT INTO ks_dungeon_revivals " +
                            "(id,instance_id,player_uuid,revive_count,cost_paid,formula_cost,revived_at,return_status,last_error) " +
                            "VALUES (?,?,?,?,?,?,?,?,?)")) {
                revival.setString(1, revivalId);
                revival.setString(2, instanceId);
                revival.setString(3, playerUuid.toString());
                revival.setInt(4, nextReviveCount);
                revival.setDouble(5, formulaCost);
                revival.setDouble(6, formulaCost);
                revival.setLong(7, now);
                revival.setString(8, CHARGE_READY);
                revival.setString(9, "");
                revival.executeUpdate();
            }
            connection.commit();
            return new Revival(revivalId, instanceId, playerUuid, nextReviveCount,
                    formulaCost, CHARGE_READY, "");
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static boolean claimCharge(Connection connection, Revival revival, long now) throws SQLException {
        return transition(connection, revival.id(), CHARGE_READY, CHARGE_CLAIMED, "", now);
    }

    static boolean confirmPaid(Connection connection, Revival revival, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_revivals SET return_status=?, last_error='', revived_at=? " +
                        "WHERE id=? AND return_status=? AND EXISTS " +
                        "(SELECT 1 FROM ks_dungeon_instances i WHERE i.id=? AND i.status='ACTIVE')")) {
            statement.setString(1, PAID_PENDING);
            statement.setLong(2, now);
            statement.setString(3, revival.id());
            statement.setString(4, CHARGE_CLAIMED);
            statement.setString(5, revival.instanceId());
            return statement.executeUpdate() == 1;
        }
    }

    static boolean rejectCharge(Connection connection, Revival revival, String error, long now) throws SQLException {
        return finishWithParticipantState(connection, revival, CHARGE_CLAIMED,
                CHARGE_REJECTED, "DEAD", error, now);
    }

    static boolean cancelUnclaimedCharge(Connection connection, Revival revival,
                                         String error, long now) throws SQLException {
        return finishWithParticipantState(connection, revival, CHARGE_READY,
                CHARGE_REJECTED, "DEAD", error, now);
    }

    static boolean markReview(Connection connection, Revival revival, String expectedStatus,
                              String error, long now) throws SQLException {
        return transition(connection, revival.id(), expectedStatus, REVIEW_REQUIRED, error, now);
    }

    static boolean prepareRefund(Connection connection, Revival revival, String expectedStatus,
                                 String error, long now) throws SQLException {
        return transition(connection, revival.id(), expectedStatus, REFUND_READY, error, now);
    }

    static boolean claimRefund(Connection connection, Revival revival, long now) throws SQLException {
        return transitionPreservingError(connection, revival.id(), REFUND_READY, REFUND_CLAIMED, now);
    }

    static boolean finishRefund(Connection connection, Revival revival, boolean refunded,
                                String error, long now) throws SQLException {
        if (refunded) {
            return finishWithParticipantState(connection, revival, REFUND_CLAIMED,
                    REFUNDED, "DEAD", error, now);
        }
        return transition(connection, revival.id(), REFUND_CLAIMED, REVIEW_REQUIRED, error, now);
    }

    static boolean completeReturn(Connection connection, Revival revival, long now) throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            int participantRows;
            try (var participant = connection.prepareStatement(
                    "UPDATE ks_dungeon_participants SET status='ALIVE', died_at=0 " +
                            "WHERE instance_id=? AND player_uuid=? AND status='REVIVE_PENDING' AND revive_count=? " +
                            "AND EXISTS (SELECT 1 FROM ks_dungeon_instances i WHERE i.id=? AND i.status='ACTIVE')")) {
                participant.setString(1, revival.instanceId());
                participant.setString(2, revival.playerUuid().toString());
                participant.setInt(3, revival.reviveCount());
                participant.setString(4, revival.instanceId());
                participantRows = participant.executeUpdate();
            }
            int revivalRows;
            try (var statement = connection.prepareStatement(
                    "UPDATE ks_dungeon_revivals SET return_status=?, last_error='', revived_at=? " +
                            "WHERE id=? AND return_status=?")) {
                statement.setString(1, RETURNED);
                statement.setLong(2, now);
                statement.setString(3, revival.id());
                statement.setString(4, PAID_PENDING);
                revivalRows = statement.executeUpdate();
            }
            if (participantRows != 1 || revivalRows != 1) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static List<Revival> pending(Connection connection) throws SQLException {
        List<Revival> rows = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id,instance_id,player_uuid,revive_count,cost_paid,return_status,last_error " +
                        "FROM ks_dungeon_revivals WHERE return_status=? ORDER BY revived_at,id")) {
            statement.setString(1, PAID_PENDING);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Revival(result.getString(1), result.getString(2),
                            UUID.fromString(result.getString(3)), result.getInt(4), result.getDouble(5),
                            result.getString(6), result.getString(7)));
                }
            }
        }
        return List.copyOf(rows);
    }

    static List<Revival> pendingRefunds(Connection connection) throws SQLException {
        return listByStatus(connection, REFUND_READY);
    }

    static int recoverUncertainClaims(Connection connection, long now) throws SQLException {
        int changed = 0;
        changed += bulkReview(connection, CHARGE_CLAIMED,
                "server stopped after charge claim; Vault withdrawal outcome requires review", now);
        changed += bulkReview(connection, REFUND_CLAIMED,
                "server stopped after refund claim; Vault refund outcome requires review", now);
        return changed;
    }

    static int cancelUnclaimedCharges(Connection connection, long now) throws SQLException {
        List<Revival> ready = listByStatus(connection, CHARGE_READY);
        int changed = 0;
        for (Revival revival : ready) {
            if (finishWithParticipantState(connection, revival, CHARGE_READY,
                    CHARGE_REJECTED, "DEAD", "charge was never claimed", now)) changed++;
        }
        return changed;
    }

    private static List<Revival> listByStatus(Connection connection, String status) throws SQLException {
        List<Revival> rows = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id,instance_id,player_uuid,revive_count,cost_paid,return_status,last_error " +
                        "FROM ks_dungeon_revivals WHERE return_status=? ORDER BY revived_at,id")) {
            statement.setString(1, status);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Revival(result.getString(1), result.getString(2),
                            UUID.fromString(result.getString(3)), result.getInt(4), result.getDouble(5),
                            result.getString(6), result.getString(7)));
                }
            }
        }
        return List.copyOf(rows);
    }

    private static boolean transition(Connection connection, String revivalId,
                                      String expectedStatus, String nextStatus,
                                      String error, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_revivals SET return_status=?, last_error=?, revived_at=? " +
                        "WHERE id=? AND return_status=?")) {
            statement.setString(1, nextStatus);
            statement.setString(2, error == null ? "" : error);
            statement.setLong(3, now);
            statement.setString(4, revivalId);
            statement.setString(5, expectedStatus);
            return statement.executeUpdate() == 1;
        }
    }

    private static boolean transitionPreservingError(Connection connection, String revivalId,
                                                     String expectedStatus, String nextStatus,
                                                     long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_revivals SET return_status=?, revived_at=? " +
                        "WHERE id=? AND return_status=?")) {
            statement.setString(1, nextStatus);
            statement.setLong(2, now);
            statement.setString(3, revivalId);
            statement.setString(4, expectedStatus);
            return statement.executeUpdate() == 1;
        }
    }

    private static boolean finishWithParticipantState(Connection connection, Revival revival,
                                                      String expectedStatus, String journalStatus,
                                                      String participantStatus, String error,
                                                      long now) throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            try (var participant = connection.prepareStatement(
                    "UPDATE ks_dungeon_participants SET status=? " +
                            "WHERE instance_id=? AND player_uuid=? AND status='REVIVE_PENDING' AND revive_count=?")) {
                participant.setString(1, participantStatus);
                participant.setString(2, revival.instanceId());
                participant.setString(3, revival.playerUuid().toString());
                participant.setInt(4, revival.reviveCount());
                if (participant.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            if (!transition(connection, revival.id(), expectedStatus, journalStatus, error, now)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static int bulkReview(Connection connection, String expectedStatus,
                                  String error, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_revivals SET return_status=?,last_error=?,revived_at=? " +
                        "WHERE return_status=?")) {
            statement.setString(1, REVIEW_REQUIRED);
            statement.setString(2, error);
            statement.setLong(3, now);
            statement.setString(4, expectedStatus);
            return statement.executeUpdate();
        }
    }

    private static void requireAutoCommit(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Revive transition requires auto-commit connection");
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
