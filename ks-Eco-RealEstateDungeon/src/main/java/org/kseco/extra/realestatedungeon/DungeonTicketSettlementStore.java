package org.kseco.extra.realestatedungeon;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL-only state machine around external Vault ticket settlement. */
final class DungeonTicketSettlementStore {

    static final String CHARGE_READY = "CHARGE_READY";
    static final String CHARGE_IN_PROGRESS = "CHARGE_IN_PROGRESS";
    static final String CHARGED = "CHARGED";
    static final String ADMITTED = "ADMITTED";
    static final String REFUND_READY = "REFUND_READY";
    static final String REFUND_IN_PROGRESS = "REFUND_IN_PROGRESS";
    static final String REFUNDED = "REFUNDED";
    static final String REJECTED = "REJECTED";
    static final String CANCELLED = "CANCELLED";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    record Settlement(String instanceId, String templateId, UUID payerUuid, double amount,
                      String ownerServerId, String ownerInstanceId, long createdAt) { }

    record Participant(UUID uuid, String name) {
        Participant {
            name = name == null ? "" : name;
        }
    }

    record Recovery(List<String> refundReady, List<String> reviewRequired, List<String> cancelled) {
        Recovery {
            refundReady = List.copyOf(refundReady);
            reviewRequired = List.copyOf(reviewRequired);
            cancelled = List.copyOf(cancelled);
        }
    }

    private DungeonTicketSettlementStore() { }

    static void createSchema(Connection connection) throws SQLException {
        DungeonSchema.initializeTicketSettlements(connection);
    }

    static void insertChargeReady(Connection connection, Settlement settlement) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ks_dungeon_ticket_settlements
                (instance_id,template_id,payer_uuid,amount,status,owner_server_id,owner_instance_id,
                 error_message,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,NULL,?,?)
                """)) {
            statement.setString(1, settlement.instanceId());
            statement.setString(2, settlement.templateId());
            statement.setString(3, settlement.payerUuid().toString());
            statement.setDouble(4, settlement.amount());
            statement.setString(5, CHARGE_READY);
            statement.setString(6, settlement.ownerServerId());
            statement.setString(7, settlement.ownerInstanceId());
            statement.setLong(8, settlement.createdAt());
            statement.setLong(9, settlement.createdAt());
            statement.executeUpdate();
        }
    }

    static boolean claimCharge(Connection connection, String instanceId, long now) throws SQLException {
        return transition(connection, instanceId, CHARGE_READY, CHARGE_IN_PROGRESS, null, now);
    }

    static void commitCharge(Connection connection, Settlement settlement, long expiresAt,
                             List<Participant> participants) throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            if (!transition(connection, settlement.instanceId(), CHARGE_IN_PROGRESS, CHARGED, null,
                    System.currentTimeMillis() / 1000)) {
                throw new SQLException("Ticket charge state changed for " + settlement.instanceId());
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ks_dungeon_instances (id,template_id,grid_id,status,started_at,expires_at,owner_uuid,created_at) "
                            + "VALUES (?,?,?,'WAITING',?,?,?,?)")) {
                statement.setString(1, settlement.instanceId());
                statement.setString(2, settlement.templateId());
                statement.setString(3, "PENDING");
                statement.setLong(4, settlement.createdAt());
                statement.setLong(5, expiresAt);
                statement.setString(6, settlement.payerUuid().toString());
                statement.setLong(7, settlement.createdAt());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ks_dungeon_participants "
                            + "(instance_id,player_uuid,player_name,joined_at,status) VALUES (?,?,?,?,'ALIVE')")) {
                for (Participant participant : participants) {
                    statement.setString(1, settlement.instanceId());
                    statement.setString(2, participant.uuid().toString());
                    statement.setString(3, participant.name());
                    statement.setLong(4, settlement.createdAt());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static boolean markChargeRejected(Connection connection, String instanceId, String error, long now)
            throws SQLException {
        return transition(connection, instanceId, CHARGE_IN_PROGRESS, REJECTED, error, now);
    }

    static boolean markChargeForRefund(Connection connection, String instanceId, String error, long now)
            throws SQLException {
        return transition(connection, instanceId, CHARGE_IN_PROGRESS, REFUND_READY, error, now);
    }

    static boolean activateAdmission(Connection connection, String instanceId, long now) throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            int activated;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_dungeon_instances SET status='ACTIVE' WHERE id=? AND status='WAITING'")) {
                statement.setString(1, instanceId);
                activated = statement.executeUpdate();
            }
            if (activated != 1 || !transition(connection, instanceId, CHARGED, ADMITTED, null, now)) {
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

    static boolean prepareRefund(Connection connection, String instanceId, String error, long now)
            throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            String status = status(connection, instanceId);
            if (REFUND_READY.equals(status)) {
                connection.commit();
                return true;
            }
            if (!CHARGED.equals(status)) {
                connection.rollback();
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ks_dungeon_instances SET status='ABANDONED' WHERE id=? AND status='WAITING'")) {
                statement.setString(1, instanceId);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            markParticipantsLeft(connection, instanceId);
            if (!transition(connection, instanceId, CHARGED, REFUND_READY, error, now)) {
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

    static boolean claimRefund(Connection connection, String instanceId, long now) throws SQLException {
        return transition(connection, instanceId, REFUND_READY, REFUND_IN_PROGRESS, null, now);
    }

    static boolean markRefunded(Connection connection, String instanceId, long now) throws SQLException {
        return transition(connection, instanceId, REFUND_IN_PROGRESS, REFUNDED, null, now);
    }

    static boolean returnRefundReady(Connection connection, String instanceId, String error, long now)
            throws SQLException {
        return transition(connection, instanceId, REFUND_IN_PROGRESS, REFUND_READY, error, now);
    }

    static boolean markReviewRequired(Connection connection, String instanceId, String error, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ks_dungeon_ticket_settlements
                SET status=?,error_message=?,updated_at=?
                WHERE instance_id=? AND status IN
                ('CHARGE_READY','CHARGE_IN_PROGRESS','CHARGED','REFUND_READY','REFUND_IN_PROGRESS')
                """)) {
            statement.setString(1, REVIEW_REQUIRED);
            statement.setString(2, error);
            statement.setLong(3, now);
            statement.setString(4, instanceId);
            return statement.executeUpdate() == 1;
        }
    }

    static Recovery recoverInterrupted(Connection connection, String ownerServerId, long now) throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        List<String> refundReady = new ArrayList<>();
        List<String> review = new ArrayList<>();
        List<String> cancelled = new ArrayList<>();
        try {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT instance_id,status FROM ks_dungeon_ticket_settlements
                    WHERE owner_server_id=? AND status IN
                    ('CHARGE_READY','CHARGE_IN_PROGRESS','CHARGED','REFUND_READY','REFUND_IN_PROGRESS')
                    ORDER BY created_at
                    """)) {
                statement.setString(1, ownerServerId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) rows.add(new String[]{result.getString(1), result.getString(2)});
                }
            }
            for (String[] row : rows) {
                String instanceId = row[0];
                switch (row[1]) {
                    case CHARGE_READY -> {
                        transition(connection, instanceId, CHARGE_READY, CANCELLED,
                                "Plugin stopped before Vault charge started", now);
                        cancelled.add(instanceId);
                    }
                    case CHARGE_IN_PROGRESS, REFUND_IN_PROGRESS -> {
                        markReviewRequired(connection, instanceId,
                                "Plugin stopped while an external Vault operation was in progress", now);
                        review.add(instanceId);
                    }
                    case CHARGED -> {
                        String instanceStatus = instanceStatus(connection, instanceId);
                        if (DungeonInstanceManager.STATUS_WAITING.equals(instanceStatus)
                                || DungeonInstanceManager.STATUS_ABANDONED.equals(instanceStatus)) {
                            if (DungeonInstanceManager.STATUS_WAITING.equals(instanceStatus)) {
                                try (PreparedStatement statement = connection.prepareStatement(
                                        "UPDATE ks_dungeon_instances SET status='ABANDONED' WHERE id=? AND status='WAITING'")) {
                                    statement.setString(1, instanceId);
                                    statement.executeUpdate();
                                }
                                markParticipantsLeft(connection, instanceId);
                            }
                            transition(connection, instanceId, CHARGED, REFUND_READY,
                                    "Recovered charged ticket before admission", now);
                            refundReady.add(instanceId);
                        } else {
                            markReviewRequired(connection, instanceId,
                                    "Charged ticket has inconsistent instance state: " + instanceStatus, now);
                            review.add(instanceId);
                        }
                    }
                    case REFUND_READY -> refundReady.add(instanceId);
                    default -> { }
                }
            }
            connection.commit();
            return new Recovery(refundReady, review, cancelled);
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static Settlement load(Connection connection, String instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT template_id,payer_uuid,amount,owner_server_id,owner_instance_id,created_at
                FROM ks_dungeon_ticket_settlements WHERE instance_id=?
                """)) {
            statement.setString(1, instanceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new Settlement(instanceId, result.getString(1), UUID.fromString(result.getString(2)),
                        result.getDouble(3), result.getString(4), result.getString(5), result.getLong(6));
            }
        }
    }

    static String status(Connection connection, String instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM ks_dungeon_ticket_settlements WHERE instance_id=?")) {
            statement.setString(1, instanceId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static String instanceStatus(Connection connection, String instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM ks_dungeon_instances WHERE id=?")) {
            statement.setString(1, instanceId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static void markParticipantsLeft(Connection connection, String instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_dungeon_participants SET status='LEFT' WHERE instance_id=? AND status<>'LEFT'")) {
            statement.setString(1, instanceId);
            statement.executeUpdate();
        }
    }

    private static boolean transition(Connection connection, String instanceId, String expected, String next,
                                      String error, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ks_dungeon_ticket_settlements
                SET status=?,error_message=?,updated_at=? WHERE instance_id=? AND status=?
                """)) {
            statement.setString(1, next);
            statement.setString(2, error);
            statement.setLong(3, now);
            statement.setString(4, instanceId);
            statement.setString(5, expected);
            return statement.executeUpdate() == 1;
        }
    }

    private static void requireAutoCommit(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Ticket settlement requires an auto-commit connection");
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
