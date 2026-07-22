package org.kseco;

import org.kseco.database.BusinessSchemaDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL-only lifecycle for player-market wallet, stock and delivery settlement. */
final class MarketPurchaseSettlementStore {
    static final String BUYER_CHARGE_CLAIMED = "BUYER_CHARGE_CLAIMED";
    static final String BUYER_CHARGED = "BUYER_CHARGED";
    static final String RESERVED = "RESERVED";
    static final String SELLER_PAYOUT_CLAIMED = "SELLER_PAYOUT_CLAIMED";
    static final String REFUND_READY = "REFUND_READY";
    static final String REFUND_CLAIMED = "REFUND_CLAIMED";
    static final String FINALIZED = "FINALIZED";
    static final String COMPENSATED = "COMPENSATED";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    record Settlement(String id, String listingId, String storageId, UUID buyerUuid, String buyerName,
                      UUID sellerUuid, int quantity, double totalCost, double taxRate, double tax,
                      double totalCharge, String status, String reviewStage, String lastError) { }

    private MarketPurchaseSettlementStore() { }

    static void initialize(Connection connection) throws SQLException {
        String floatingPoint = BusinessSchemaDialect.floatingPointType(BusinessSchemaDialect.detect(connection));
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_market_settlements (
                        id VARCHAR(128) PRIMARY KEY,
                        listing_id VARCHAR(128) NOT NULL,
                        storage_id VARCHAR(128),
                        buyer_uuid VARCHAR(36) NOT NULL,
                        buyer_name VARCHAR(64) NOT NULL,
                        seller_uuid VARCHAR(36) NOT NULL,
                        quantity INTEGER NOT NULL,
                        total_cost %s NOT NULL,
                        tax_rate %s NOT NULL,
                        tax_amount %s NOT NULL,
                        total_charge %s NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        review_stage VARCHAR(32) NOT NULL DEFAULT '',
                        last_error VARCHAR(1024) NOT NULL DEFAULT '',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.formatted(floatingPoint, floatingPoint, floatingPoint, floatingPoint));
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_market_settlement_status",
                "ks_eco_market_settlements", "status", "updated_at");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_eco_market_settlements", "review_stage",
                "VARCHAR(32) NOT NULL DEFAULT ''");
    }

    static void insertChargeClaim(Connection connection, Settlement settlement, long now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ks_eco_market_settlements
                (id,listing_id,storage_id,buyer_uuid,buyer_name,seller_uuid,quantity,total_cost,tax_rate,
                 tax_amount,total_charge,status,review_stage,last_error,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, settlement.id());
            statement.setString(2, settlement.listingId());
            statement.setString(3, null);
            statement.setString(4, settlement.buyerUuid().toString());
            statement.setString(5, settlement.buyerName());
            statement.setString(6, settlement.sellerUuid().toString());
            statement.setInt(7, settlement.quantity());
            statement.setDouble(8, settlement.totalCost());
            statement.setDouble(9, settlement.taxRate());
            statement.setDouble(10, settlement.tax());
            statement.setDouble(11, settlement.totalCharge());
            statement.setString(12, BUYER_CHARGE_CLAIMED);
            statement.setString(13, "");
            statement.setString(14, "");
            statement.setLong(15, now);
            statement.setLong(16, now);
            statement.executeUpdate();
        }
    }

    static boolean transition(Connection connection, String id, String expected, String next,
                              String error, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_eco_market_settlements SET status=?,review_stage=?,last_error=?,updated_at=? "
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

    static List<Settlement> open(Connection connection) throws SQLException {
        List<Settlement> settlements = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id,listing_id,storage_id,buyer_uuid,buyer_name,seller_uuid,quantity,total_cost,tax_rate,"
                        + "tax_amount,total_charge,status,review_stage,last_error FROM ks_eco_market_settlements "
                        + "WHERE status NOT IN (?,?,?) ORDER BY created_at,id")) {
            statement.setString(1, FINALIZED);
            statement.setString(2, COMPENSATED);
            statement.setString(3, REVIEW_REQUIRED);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) settlements.add(read(rows));
            }
        }
        return List.copyOf(settlements);
    }

    static Settlement find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id,listing_id,storage_id,buyer_uuid,buyer_name,seller_uuid,quantity,total_cost,tax_rate,"
                        + "tax_amount,total_charge,status,review_stage,last_error FROM ks_eco_market_settlements WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    static int markUnknownCallsForReview(Connection connection, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_eco_market_settlements SET review_stage=status,status=?,last_error=?,updated_at=? "
                        + "WHERE status IN (?,?,?)")) {
            statement.setString(1, REVIEW_REQUIRED);
            statement.setString(2, "external wallet outcome unknown after restart");
            statement.setLong(3, now);
            statement.setString(4, BUYER_CHARGE_CLAIMED);
            statement.setString(5, SELLER_PAYOUT_CLAIMED);
            statement.setString(6, REFUND_CLAIMED);
            return statement.executeUpdate();
        }
    }

    static List<Settlement> reviewRequired(Connection connection) throws SQLException {
        List<Settlement> settlements = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id,listing_id,storage_id,buyer_uuid,buyer_name,seller_uuid,quantity,total_cost,tax_rate,"
                        + "tax_amount,total_charge,status,review_stage,last_error FROM ks_eco_market_settlements "
                        + "WHERE status=? ORDER BY updated_at,id")) {
            statement.setString(1, REVIEW_REQUIRED);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) settlements.add(read(rows));
            }
        }
        return List.copyOf(settlements);
    }

    static boolean resolveReview(Connection connection, String id, String reviewStage,
                                 String nextStatus, String note, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_eco_market_settlements SET status=?,review_stage='',last_error=?,updated_at=? "
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

    private static Settlement read(java.sql.ResultSet rows) throws SQLException {
        return new Settlement(rows.getString(1), rows.getString(2), rows.getString(3),
                UUID.fromString(rows.getString(4)), rows.getString(5), UUID.fromString(rows.getString(6)),
                rows.getInt(7), rows.getDouble(8), rows.getDouble(9), rows.getDouble(10), rows.getDouble(11),
                rows.getString(12), rows.getString(13), rows.getString(14));
    }
}
