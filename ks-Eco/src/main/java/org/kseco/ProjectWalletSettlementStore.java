package org.kseco;

import org.kseco.database.BusinessSchemaDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable state machine for personal project deposits and prepayment payouts. */
final class ProjectWalletSettlementStore {
    static final String DIRECT_AWARD = "DIRECT_AWARD";
    static final String DEPOSIT_AWARD = "DEPOSIT_AWARD";

    static final String DEPOSIT_CHARGE_READY = "DEPOSIT_CHARGE_READY";
    static final String DEPOSIT_CHARGE_CLAIMED = "DEPOSIT_CHARGE_CLAIMED";
    static final String DEPOSIT_HELD = "DEPOSIT_HELD";
    static final String PREPAYMENT_READY = "PREPAYMENT_READY";
    static final String PREPAYMENT_CLAIMED = "PREPAYMENT_CLAIMED";
    static final String FINALIZED = "FINALIZED";
    static final String COMPENSATED = "COMPENSATED";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    record Settlement(String id, String kind, String projectId, String bidId, UUID playerUuid,
                      double depositAmount, double prepaymentAmount, String status,
                      String reviewStage, String lastError) { }

    private ProjectWalletSettlementStore() { }

    static void initialize(Connection connection) throws SQLException {
        String floatingPoint = BusinessSchemaDialect.floatingPointType(BusinessSchemaDialect.detect(connection));
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_ent_project_wallet_settlements (
                        id VARCHAR(128) PRIMARY KEY,
                        kind VARCHAR(32) NOT NULL,
                        project_id VARCHAR(128) NOT NULL,
                        bid_id VARCHAR(128) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        deposit_amount %s NOT NULL,
                        prepayment_amount %s NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        review_stage VARCHAR(32) NOT NULL DEFAULT '',
                        last_error VARCHAR(1024) NOT NULL DEFAULT '',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.formatted(floatingPoint, floatingPoint));
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_project_wallet_settlement_status",
                "ks_ent_project_wallet_settlements", "status", "updated_at");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_project_wallet_settlement_bid",
                "ks_ent_project_wallet_settlements", "bid_id", "updated_at");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_ent_project_wallet_settlements", "review_stage",
                "VARCHAR(32) NOT NULL DEFAULT ''");
    }

    static void insert(Connection connection, Settlement settlement, long now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ks_ent_project_wallet_settlements
                (id,kind,project_id,bid_id,player_uuid,deposit_amount,prepayment_amount,status,review_stage,last_error,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, settlement.id());
            statement.setString(2, settlement.kind());
            statement.setString(3, settlement.projectId());
            statement.setString(4, settlement.bidId());
            statement.setString(5, settlement.playerUuid().toString());
            statement.setDouble(6, settlement.depositAmount());
            statement.setDouble(7, settlement.prepaymentAmount());
            statement.setString(8, settlement.status());
            statement.setString(9, settlement.reviewStage() == null ? "" : settlement.reviewStage());
            statement.setString(10, settlement.lastError() == null ? "" : settlement.lastError());
            statement.setLong(11, now);
            statement.setLong(12, now);
            statement.executeUpdate();
        }
    }

    static boolean transition(Connection connection, String id, String expected, String next,
                              String error, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_ent_project_wallet_settlements SET status=?,review_stage=?,last_error=?,updated_at=? "
                        + "WHERE id=? AND status=?")) {
            statement.setString(1, next);
            statement.setString(2, REVIEW_REQUIRED.equals(next) ? expected : "");
            statement.setString(3, error == null ? "" : error);
            statement.setLong(4, now);
            statement.setString(5, id);
            statement.setString(6, expected);
            return statement.executeUpdate() == 1;
        }
    }

    static Settlement find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement(selectColumns() + " WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    static Settlement findOpenByBid(Connection connection, String bidId) throws SQLException {
        try (var statement = connection.prepareStatement(selectColumns()
                + " WHERE bid_id=? AND status NOT IN (?,?) ORDER BY created_at DESC LIMIT 1")) {
            statement.setString(1, bidId);
            statement.setString(2, FINALIZED);
            statement.setString(3, COMPENSATED);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    static List<Settlement> recoverable(Connection connection) throws SQLException {
        List<Settlement> settlements = new ArrayList<>();
        try (var statement = connection.prepareStatement(selectColumns()
                + " WHERE status IN (?,?,?) ORDER BY created_at,id")) {
            statement.setString(1, DEPOSIT_CHARGE_READY);
            statement.setString(2, DEPOSIT_HELD);
            statement.setString(3, PREPAYMENT_READY);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) settlements.add(read(rows));
            }
        }
        return List.copyOf(settlements);
    }

    static List<Settlement> reviewRequired(Connection connection) throws SQLException {
        List<Settlement> settlements = new ArrayList<>();
        try (var statement = connection.prepareStatement(selectColumns()
                + " WHERE status=? ORDER BY updated_at,id")) {
            statement.setString(1, REVIEW_REQUIRED);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) settlements.add(read(rows));
            }
        }
        return List.copyOf(settlements);
    }

    static int markInterruptedClaimsForReview(Connection connection, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_ent_project_wallet_settlements SET review_stage=status,status=?,last_error=?,updated_at=? "
                        + "WHERE status IN (?,?)")) {
            statement.setString(1, REVIEW_REQUIRED);
            statement.setString(2, "external wallet outcome unknown after restart");
            statement.setLong(3, now);
            statement.setString(4, DEPOSIT_CHARGE_CLAIMED);
            statement.setString(5, PREPAYMENT_CLAIMED);
            return statement.executeUpdate();
        }
    }

    static boolean resolveReview(Connection connection, String id, String reviewStage,
                                 String nextStatus, String note, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_ent_project_wallet_settlements SET status=?,review_stage='',last_error=?,updated_at=? "
                        + "WHERE id=? AND status=? AND review_stage=?")) {
            statement.setString(1, nextStatus);
            statement.setString(2, note == null ? "" : note);
            statement.setLong(3, now);
            statement.setString(4, id);
            statement.setString(5, REVIEW_REQUIRED);
            statement.setString(6, reviewStage);
            return statement.executeUpdate() == 1;
        }
    }

    private static String selectColumns() {
        return "SELECT id,kind,project_id,bid_id,player_uuid,deposit_amount,prepayment_amount,status,review_stage,last_error "
                + "FROM ks_ent_project_wallet_settlements";
    }

    private static Settlement read(java.sql.ResultSet rows) throws SQLException {
        return new Settlement(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                UUID.fromString(rows.getString(5)), rows.getDouble(6), rows.getDouble(7),
                rows.getString(8), rows.getString(9), rows.getString(10));
    }
}
