package org.kseco;

import org.kseco.database.BusinessSchemaDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL-only lifecycle for purchase-order fulfillment reservations. */
final class PurchaseOrderReservationStore {

    static final String RESERVED = "RESERVED";
    static final String BUYER_CHARGE_CLAIMED = "BUYER_CHARGE_CLAIMED";
    static final String BUYER_CHARGED = "BUYER_CHARGED";
    static final String SELLER_PAYOUT_CLAIMED = "SELLER_PAYOUT_CLAIMED";
    static final String SELLER_PAID = "SELLER_PAID";
    static final String BUYER_REFUND_READY = "BUYER_REFUND_READY";
    static final String BUYER_REFUND_CLAIMED = "BUYER_REFUND_CLAIMED";
    static final String FINALIZED = "FINALIZED";
    static final String COMPENSATED = "COMPENSATED";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    record Reservation(String id, String orderId, UUID buyerUuid, UUID sellerUuid,
                       int quantity, double payment, boolean unlimited, String status,
                       String lastError) { }

    private PurchaseOrderReservationStore() { }

    static void initialize(Connection connection) throws SQLException {
        var dialect = BusinessSchemaDialect.detect(connection);
        String floatingPoint = BusinessSchemaDialect.floatingPointType(dialect);
        String binary = BusinessSchemaDialect.binaryType(dialect);
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_purchase_order_reservations (
                        id VARCHAR(128) PRIMARY KEY,
                        order_id VARCHAR(128) NOT NULL,
                        buyer_uuid VARCHAR(36) NOT NULL,
                        seller_uuid VARCHAR(36) NOT NULL,
                        quantity INTEGER NOT NULL,
                        payment %s NOT NULL,
                        unlimited INTEGER NOT NULL,
                        original_hand_data %s NOT NULL,
                        updated_hand_data %s,
                        status VARCHAR(32) NOT NULL,
                        last_error VARCHAR(1024) NOT NULL DEFAULT '',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.formatted(floatingPoint, binary, binary));
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_po_reservation_status",
                "ks_eco_purchase_order_reservations", "status", "updated_at");
    }

    static void insert(Connection connection, Reservation reservation,
                       byte[] originalHandData, byte[] updatedHandData, long now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ks_eco_purchase_order_reservations
                (id,order_id,buyer_uuid,seller_uuid,quantity,payment,unlimited,original_hand_data,
                 updated_hand_data,status,last_error,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, reservation.id());
            statement.setString(2, reservation.orderId());
            statement.setString(3, reservation.buyerUuid().toString());
            statement.setString(4, reservation.sellerUuid().toString());
            statement.setInt(5, reservation.quantity());
            statement.setDouble(6, reservation.payment());
            statement.setInt(7, reservation.unlimited() ? 1 : 0);
            statement.setBytes(8, originalHandData);
            statement.setBytes(9, updatedHandData);
            statement.setString(10, RESERVED);
            statement.setString(11, "");
            statement.setLong(12, now);
            statement.setLong(13, now);
            statement.executeUpdate();
        }
    }

    static boolean transition(Connection connection, String id, String expected,
                              String next, String error, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_eco_purchase_order_reservations SET status=?,last_error=?,updated_at=? " +
                        "WHERE id=? AND status=?")) {
            statement.setString(1, next);
            statement.setString(2, error == null ? "" : error);
            statement.setLong(3, now);
            statement.setString(4, id);
            statement.setString(5, expected);
            return statement.executeUpdate() == 1;
        }
    }

    static List<Reservation> open(Connection connection) throws SQLException {
        List<Reservation> reservations = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id,order_id,buyer_uuid,seller_uuid,quantity,payment,unlimited,status,last_error " +
                        "FROM ks_eco_purchase_order_reservations " +
                        "WHERE status NOT IN (?,?,?) ORDER BY created_at,id")) {
            statement.setString(1, FINALIZED);
            statement.setString(2, COMPENSATED);
            statement.setString(3, REVIEW_REQUIRED);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) reservations.add(read(rows));
            }
        }
        return List.copyOf(reservations);
    }

    static Reservation find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id,order_id,buyer_uuid,seller_uuid,quantity,payment,unlimited,status,last_error " +
                        "FROM ks_eco_purchase_order_reservations WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    static List<String> pendingItemIds(Connection connection, String reservationId) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id FROM ks_eco_purchase_order_pending_items WHERE reservation_id=? ORDER BY id")) {
            statement.setString(1, reservationId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) ids.add(rows.getString(1));
            }
        }
        return List.copyOf(ids);
    }

    static int markInterruptedClaimsForReview(Connection connection, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_eco_purchase_order_reservations SET status=?,last_error=?,updated_at=? " +
                        "WHERE status IN (?,?,?)")) {
            statement.setString(1, REVIEW_REQUIRED);
            statement.setString(2, "external inventory or wallet outcome unknown after restart");
            statement.setLong(3, now);
            statement.setString(4, BUYER_CHARGE_CLAIMED);
            statement.setString(5, SELLER_PAYOUT_CLAIMED);
            statement.setString(6, BUYER_REFUND_CLAIMED);
            return statement.executeUpdate();
        }
    }

    private static Reservation read(java.sql.ResultSet rows) throws SQLException {
        return new Reservation(rows.getString(1), rows.getString(2),
                UUID.fromString(rows.getString(3)), UUID.fromString(rows.getString(4)),
                rows.getInt(5), rows.getDouble(6), rows.getInt(7) != 0,
                rows.getString(8), rows.getString(9));
    }
}
