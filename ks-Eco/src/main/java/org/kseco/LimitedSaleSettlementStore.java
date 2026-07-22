package org.kseco;

import org.kseco.database.BusinessSchemaDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL-only settlement journal for limited-sale charge, counters, delivery and refund. */
final class LimitedSaleSettlementStore {

    static final String READY = "READY";
    static final String CHARGE_CLAIMED = "CHARGE_CLAIMED";
    static final String CHARGED = "CHARGED";
    static final String COUNTERS_COMMITTED = "COUNTERS_COMMITTED";
    static final String DELIVERING = "DELIVERING";
    static final String DELIVERED = "DELIVERED";
    static final String REFUND_READY = "REFUND_READY";
    static final String REFUND_CLAIMED = "REFUND_CLAIMED";
    static final String COMPENSATED = "COMPENSATED";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    record Settlement(String id, UUID playerUuid, String playerName, String saleId,
                      int quantity, boolean boxed, double amount, String backend,
                      String status, String lastError) { }

    private LimitedSaleSettlementStore() { }

    static void initialize(Connection connection) throws SQLException {
        String floatingPoint = BusinessSchemaDialect.floatingPointType(BusinessSchemaDialect.detect(connection));
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_limited_sale_settlements (
                        id VARCHAR(128) PRIMARY KEY,
                        player_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(64) NOT NULL,
                        sale_id VARCHAR(128) NOT NULL,
                        quantity INTEGER NOT NULL,
                        boxed INTEGER NOT NULL,
                        amount %s NOT NULL,
                        backend VARCHAR(32) NOT NULL DEFAULT '',
                        status VARCHAR(32) NOT NULL,
                        last_error VARCHAR(1024) NOT NULL DEFAULT '',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.formatted(floatingPoint));
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_limited_settlement_status",
                "ks_limited_sale_settlements", "status", "updated_at");
    }

    static void insertReady(Connection connection, Settlement settlement, long now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ks_limited_sale_settlements
                (id,player_uuid,player_name,sale_id,quantity,boxed,amount,backend,status,last_error,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, settlement.id());
            statement.setString(2, settlement.playerUuid().toString());
            statement.setString(3, settlement.playerName());
            statement.setString(4, settlement.saleId());
            statement.setInt(5, settlement.quantity());
            statement.setInt(6, settlement.boxed() ? 1 : 0);
            statement.setDouble(7, settlement.amount());
            statement.setString(8, "");
            statement.setString(9, READY);
            statement.setString(10, "");
            statement.setLong(11, now);
            statement.setLong(12, now);
            statement.executeUpdate();
        }
    }

    static boolean claimCharge(Connection connection, String id, String backend, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_limited_sale_settlements SET status=?,backend=?,last_error='',updated_at=? " +
                        "WHERE id=? AND status=?")) {
            statement.setString(1, CHARGE_CLAIMED);
            statement.setString(2, backend);
            statement.setLong(3, now);
            statement.setString(4, id);
            statement.setString(5, READY);
            return statement.executeUpdate() == 1;
        }
    }

    static boolean transition(Connection connection, String id, String expected,
                              String next, String error, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_limited_sale_settlements SET status=?,last_error=?,updated_at=? " +
                        "WHERE id=? AND status=?")) {
            statement.setString(1, next);
            statement.setString(2, error == null ? "" : error);
            statement.setLong(3, now);
            statement.setString(4, id);
            statement.setString(5, expected);
            return statement.executeUpdate() == 1;
        }
    }

    static Settlement find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id,player_uuid,player_name,sale_id,quantity,boxed,amount,backend,status,last_error " +
                        "FROM ks_limited_sale_settlements WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    static List<Settlement> open(Connection connection) throws SQLException {
        List<Settlement> settlements = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id,player_uuid,player_name,sale_id,quantity,boxed,amount,backend,status,last_error " +
                        "FROM ks_limited_sale_settlements WHERE status NOT IN (?,?,?) ORDER BY created_at,id")) {
            statement.setString(1, DELIVERED);
            statement.setString(2, COMPENSATED);
            statement.setString(3, REVIEW_REQUIRED);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) settlements.add(read(rows));
            }
        }
        return List.copyOf(settlements);
    }

    static int markUnknownCallsForReview(Connection connection, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_limited_sale_settlements SET status=?,last_error=?,updated_at=? WHERE status IN (?,?,?)")) {
            statement.setString(1, REVIEW_REQUIRED);
            statement.setString(2, "external wallet or delivery outcome unknown after restart");
            statement.setLong(3, now);
            statement.setString(4, CHARGE_CLAIMED);
            statement.setString(5, DELIVERING);
            statement.setString(6, REFUND_CLAIMED);
            return statement.executeUpdate();
        }
    }

    private static Settlement read(java.sql.ResultSet rows) throws SQLException {
        return new Settlement(rows.getString(1), UUID.fromString(rows.getString(2)), rows.getString(3),
                rows.getString(4), rows.getInt(5), rows.getInt(6) != 0, rows.getDouble(7),
                rows.getString(8), rows.getString(9), rows.getString(10));
    }
}
