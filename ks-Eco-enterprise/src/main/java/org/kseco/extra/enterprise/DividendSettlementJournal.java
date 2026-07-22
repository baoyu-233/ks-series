package org.kseco.extra.enterprise;

import org.kseco.database.BusinessSchemaDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable boundary between committed enterprise debits and external Vault deposits. */
final class DividendSettlementJournal {

    static final String PENDING = "PENDING";
    static final String PAID = "PAID";
    static final String COMPENSATED = "COMPENSATED";
    static final String COMPENSATION_REQUIRED = "COMPENSATION_REQUIRED";

    private DividendSettlementJournal() {}

    static void createSchema(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_dividend_settlements (
                        id VARCHAR(64) PRIMARY KEY,
                        dividend_id VARCHAR(64) NOT NULL,
                        enterprise_id VARCHAR(64) NOT NULL,
                        recipient_uuid VARCHAR(64) NOT NULL,
                        share_percent DOUBLE NOT NULL,
                        gross_amount DOUBLE NOT NULL,
                        tax_amount DOUBLE NOT NULL,
                        net_amount DOUBLE NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        error_message TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE (dividend_id, recipient_uuid)
                    )
                    """);
            try {
                statement.execute("ALTER TABLE ks_ent_dividend_payouts ADD COLUMN settlement_status VARCHAR(32) NOT NULL DEFAULT 'PAID'");
            } catch (SQLException ignored) {
                // Existing installations may already have the column.
            }
            try {
                statement.execute("ALTER TABLE ks_ent_dividend_settlements ADD COLUMN owner_server_id VARCHAR(128)");
            } catch (SQLException ignored) {
                // Existing installations may already have the column.
            }
            try {
                statement.execute("ALTER TABLE ks_ent_dividend_settlements ADD COLUMN owner_instance_id VARCHAR(128)");
            } catch (SQLException ignored) {
                // Existing installations may already have the column.
            }
        }
        BusinessSchemaDialect.createIndexIfMissing(conn, "idx_ent_dividend_settlement_status",
                "ks_ent_dividend_settlements", "enterprise_id", "status", "created_at");
    }

    static void insertPending(Connection conn, Settlement settlement) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO ks_ent_dividend_settlements
                (id,dividend_id,enterprise_id,recipient_uuid,share_percent,gross_amount,tax_amount,net_amount,
                 status,error_message,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,NULL,?,?)
                """)) {
            statement.setString(1, settlement.id());
            statement.setString(2, settlement.dividendId());
            statement.setString(3, settlement.enterpriseId());
            statement.setString(4, settlement.recipientUuid().toString());
            statement.setDouble(5, settlement.sharePercent());
            statement.setDouble(6, settlement.grossAmount());
            statement.setDouble(7, settlement.taxAmount());
            statement.setDouble(8, settlement.netAmount());
            statement.setString(9, PENDING);
            statement.setLong(10, settlement.createdAt());
            statement.setLong(11, settlement.createdAt());
            statement.executeUpdate();
        }
    }

    static boolean hasUnresolved(Connection conn, String enterpriseId) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                SELECT 1 FROM ks_ent_dividend_settlements
                WHERE enterprise_id=? AND status IN ('PENDING','COMPENSATION_REQUIRED') LIMIT 1
                """)) {
            statement.setString(1, enterpriseId);
            if (statement.executeQuery().next()) return true;
        }
        try (PreparedStatement statement = conn.prepareStatement("""
                SELECT 1 FROM ks_ent_dividends
                WHERE enterprise_id=? AND status IN ('SETTLING','PENDING','PARTIAL','COMPENSATION_REQUIRED') LIMIT 1
                """)) {
            statement.setString(1, enterpriseId);
            return statement.executeQuery().next();
        }
    }

    static void markPaid(Connection conn, String dividendId, UUID recipientUuid, long now) throws SQLException {
        updateStatus(conn, dividendId, recipientUuid, PAID, null, now);
    }

    static void markCompensated(Connection conn, String dividendId, UUID recipientUuid,
                                String errorMessage, long now) throws SQLException {
        updateStatus(conn, dividendId, recipientUuid, COMPENSATED, errorMessage, now);
    }

    static void markReviewRequired(Connection conn, String dividendId, String errorMessage, long now) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                UPDATE ks_ent_dividend_settlements
                SET status='COMPENSATION_REQUIRED',error_message=?,updated_at=?
                WHERE dividend_id=? AND status='PENDING'
                """)) {
            statement.setString(1, errorMessage);
            statement.setLong(2, now);
            statement.setString(3, dividendId);
            statement.executeUpdate();
        }
    }

    static void markReviewRequired(Connection conn, String dividendId, UUID recipientUuid,
                                   String errorMessage, long now) throws SQLException {
        updateStatus(conn, dividendId, recipientUuid, COMPENSATION_REQUIRED, errorMessage, now);
    }

    static List<String> markInterruptedForReview(Connection conn, long now) throws SQLException {
        return markInterruptedForReview(conn, now, null, null);
    }

    /**
     * Marks only the PENDING settlements owned by this server/instance for human review.
     * When ownership columns are absent, the method is a no-op so shared databases cannot
     * steal another node's in-flight Vault payouts during startup recovery.
     */
    static List<String> markInterruptedForReview(Connection conn, long now,
                                                 String ownerServerId, String ownerInstanceId) throws SQLException {
        ensureOwnershipColumns(conn);
        if (ownerServerId == null || ownerServerId.isBlank()
                || ownerInstanceId == null || ownerInstanceId.isBlank()) {
            return List.of();
        }
        List<String> dividendIds = new ArrayList<>();
        try (PreparedStatement query = conn.prepareStatement("""
                SELECT DISTINCT dividend_id FROM ks_ent_dividend_settlements
                WHERE status='PENDING' AND owner_server_id=? AND owner_instance_id=?
                """)) {
            query.setString(1, ownerServerId);
            query.setString(2, ownerInstanceId);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) dividendIds.add(rs.getString(1));
            }
        }
        if (dividendIds.isEmpty()) return List.of();
        try (PreparedStatement update = conn.prepareStatement("""
                UPDATE ks_ent_dividend_settlements
                SET status='COMPENSATION_REQUIRED',
                    error_message='Plugin restarted before Vault settlement was finalized',updated_at=?
                WHERE status='PENDING' AND owner_server_id=? AND owner_instance_id=?
                """)) {
            update.setLong(1, now);
            update.setString(2, ownerServerId);
            update.setString(3, ownerInstanceId);
            update.executeUpdate();
        }
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE ks_ent_dividend_payouts SET settlement_status='COMPENSATION_REQUIRED' " +
                        "WHERE dividend_id=? AND settlement_status='PENDING'")) {
            for (String dividendId : dividendIds) {
                update.setString(1, dividendId);
                update.addBatch();
            }
            update.executeBatch();
        }
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE ks_ent_dividends SET status='COMPENSATION_REQUIRED' WHERE id=? AND status IN ('SETTLING','PENDING')")) {
            for (String dividendId : dividendIds) {
                update.setString(1, dividendId);
                update.addBatch();
            }
            update.executeBatch();
        }
        return List.copyOf(dividendIds);
    }

    static void ensureOwnershipColumns(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            try {
                statement.execute("ALTER TABLE ks_ent_dividend_settlements ADD COLUMN owner_server_id TEXT");
            } catch (SQLException ignored) {
                // Column already exists.
            }
            try {
                statement.execute("ALTER TABLE ks_ent_dividend_settlements ADD COLUMN owner_instance_id TEXT");
            } catch (SQLException ignored) {
                // Column already exists.
            }
        }
    }

    static void stampOwnership(Connection conn, String dividendId, String ownerServerId,
                               String ownerInstanceId) throws SQLException {
        ensureOwnershipColumns(conn);
        try (PreparedStatement statement = conn.prepareStatement("""
                UPDATE ks_ent_dividend_settlements
                SET owner_server_id=?, owner_instance_id=?
                WHERE dividend_id=? AND status='PENDING'
                """)) {
            statement.setString(1, ownerServerId);
            statement.setString(2, ownerInstanceId);
            statement.setString(3, dividendId);
            statement.executeUpdate();
        }
    }

    private static void updateStatus(Connection conn, String dividendId, UUID recipientUuid,
                                     String status, String errorMessage, long now) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                UPDATE ks_ent_dividend_settlements SET status=?,error_message=?,updated_at=?
                WHERE dividend_id=? AND recipient_uuid=? AND status='PENDING'
                """)) {
            statement.setString(1, status);
            statement.setString(2, errorMessage);
            statement.setLong(3, now);
            statement.setString(4, dividendId);
            statement.setString(5, recipientUuid.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Dividend settlement state changed for " + dividendId + "/" + recipientUuid);
            }
        }
    }

    record Settlement(String id, String dividendId, String enterpriseId, UUID recipientUuid,
                      double sharePercent, double grossAmount, double taxAmount, double netAmount,
                      long createdAt) {}
}
