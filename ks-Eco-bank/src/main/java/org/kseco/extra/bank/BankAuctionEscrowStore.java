package org.kseco.extra.bank;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.DatabaseDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL-only journal for auction bid holds and displaced-bid refunds. */
final class BankAuctionEscrowStore {

    static final String CHARGE_CLAIMED = "CHARGE_CLAIMED";
    static final String HELD = "HELD";
    static final String REFUND_READY = "REFUND_READY";
    static final String REFUND_CLAIMED = "REFUND_CLAIMED";
    static final String REFUNDED = "REFUNDED";
    static final String CONSUMED = "CONSUMED";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    record BidReservation(String escrowId, String auctionId, UUID bidderUuid,
                          String buyerEnterpriseId, double amount, long expectedVersion,
                          String previousEscrowId) { }

    record Escrow(String id, String auctionId, UUID bidderUuid, String buyerEnterpriseId,
                  double amount, String status, String lastError) { }

    private BankAuctionEscrowStore() { }

    static void initialize(Connection connection) throws SQLException {
        initialize(connection, BusinessSchemaDialect.detect(connection));
    }

    static void initialize(Connection connection, DatabaseDialect dialect) throws SQLException {
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_auction_bid_escrows (
                        id VARCHAR(128) PRIMARY KEY,
                        auction_id VARCHAR(128) NOT NULL,
                        bidder_uuid VARCHAR(36) NOT NULL,
                        buyer_enterprise_id VARCHAR(128),
                        amount %s NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        previous_escrow_id VARCHAR(128),
                        last_error VARCHAR(1024) NOT NULL DEFAULT '',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.formatted(number));
        }
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_bank_auction_escrow_status",
                "ks_bank_auction_bid_escrows", "status", "updated_at");
    }

    static BidReservation prepare(Connection connection, String auctionId, UUID bidderUuid,
                                  String buyerEnterpriseId, double amount, long now) throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            long version;
            String previousEscrowId;
            String previousBidderUuid;
            String assetType;
            double minimum;
            try (var statement = connection.prepareStatement(
                    "SELECT asset_type,starting_price,current_price,highest_bidder_uuid,highest_escrow_id," +
                            "closes_at,version FROM ks_bank_collateral_auctions WHERE id=? AND status='OPEN'")) {
                statement.setString(1, auctionId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getLong(6) <= now) {
                        connection.rollback();
                        return null;
                    }
                    assetType = rows.getString(1);
                    previousBidderUuid = rows.getString(4);
                    previousEscrowId = rows.getString(5);
                    minimum = previousEscrowId == null || previousEscrowId.isBlank()
                            ? rows.getDouble(2) : rows.getDouble(3) + 1.0;
                    version = rows.getLong(7);
                }
            }
            if (previousBidderUuid != null && !previousBidderUuid.isBlank()
                    && (previousEscrowId == null || previousEscrowId.isBlank())) {
                connection.rollback();
                return null;
            }
            if (!Double.isFinite(amount) || amount < minimum
                    || ("PROJECT_CONTRACT".equals(assetType)
                    && (buyerEnterpriseId == null || buyerEnterpriseId.isBlank()))) {
                connection.rollback();
                return null;
            }
            String escrowId = "AE-" + UUID.randomUUID();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO ks_bank_auction_bid_escrows
                    (id,auction_id,bidder_uuid,buyer_enterprise_id,amount,status,previous_escrow_id,last_error,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """)) {
                statement.setString(1, escrowId);
                statement.setString(2, auctionId);
                statement.setString(3, bidderUuid.toString());
                statement.setString(4, buyerEnterpriseId);
                statement.setDouble(5, amount);
                statement.setString(6, CHARGE_CLAIMED);
                statement.setString(7, previousEscrowId);
                statement.setString(8, "");
                statement.setLong(9, now);
                statement.setLong(10, now);
                statement.executeUpdate();
            }
            connection.commit();
            return new BidReservation(escrowId, auctionId, bidderUuid, buyerEnterpriseId,
                    amount, version, previousEscrowId);
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static boolean commitHold(Connection connection, BidReservation bid, long now) throws SQLException {
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            try (var auction = connection.prepareStatement("""
                    UPDATE ks_bank_collateral_auctions
                    SET current_price=?,highest_bidder_uuid=?,highest_escrow_id=?,buyer_enterprise_id=?,version=version+1
                    WHERE id=? AND status='OPEN' AND version=?
                    """)) {
                auction.setDouble(1, bid.amount());
                auction.setString(2, bid.bidderUuid().toString());
                auction.setString(3, bid.escrowId());
                auction.setString(4, bid.buyerEnterpriseId());
                auction.setString(5, bid.auctionId());
                auction.setLong(6, bid.expectedVersion());
                if (auction.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            if (!transition(connection, bid.escrowId(), CHARGE_CLAIMED, HELD, "", now)) {
                connection.rollback();
                return false;
            }
            if (bid.previousEscrowId() != null && !bid.previousEscrowId().isBlank()
                    && !transition(connection, bid.previousEscrowId(), HELD, REFUND_READY,
                    "outbid by " + bid.escrowId(), now)) {
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

    static boolean markChargeCompensated(Connection connection, String escrowId,
                                         boolean refunded, String error, long now) throws SQLException {
        return transition(connection, escrowId, CHARGE_CLAIMED,
                refunded ? REFUNDED : REVIEW_REQUIRED, error, now);
    }

    static boolean claimRefund(Connection connection, String escrowId, long now) throws SQLException {
        return transition(connection, escrowId, REFUND_READY, REFUND_CLAIMED, "", now);
    }

    static boolean finishRefund(Connection connection, String escrowId,
                                boolean refunded, String error, long now) throws SQLException {
        return transition(connection, escrowId, REFUND_CLAIMED,
                refunded ? REFUNDED : REVIEW_REQUIRED, error, now);
    }

    static boolean returnRefundReady(Connection connection, String escrowId,
                                     String error, long now) throws SQLException {
        return transition(connection, escrowId, REFUND_CLAIMED, REFUND_READY, error, now);
    }

    static List<Escrow> refundReady(Connection connection) throws SQLException {
        return list(connection, REFUND_READY);
    }

    static Escrow find(Connection connection, String escrowId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id,auction_id,bidder_uuid,buyer_enterprise_id,amount,status,last_error " +
                        "FROM ks_bank_auction_bid_escrows WHERE id=?")) {
            statement.setString(1, escrowId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        }
    }

    static int recoverUnknownExternalCalls(Connection connection, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_bank_auction_bid_escrows SET status=?,last_error=?,updated_at=? " +
                        "WHERE status IN (?,?)")) {
            statement.setString(1, REVIEW_REQUIRED);
            statement.setString(2, "external wallet outcome unknown after restart");
            statement.setLong(3, now);
            statement.setString(4, CHARGE_CLAIMED);
            statement.setString(5, REFUND_CLAIMED);
            return statement.executeUpdate();
        }
    }

    static boolean consumeHeld(Connection connection, String escrowId, long now) throws SQLException {
        return transition(connection, escrowId, HELD, CONSUMED, "", now);
    }

    private static List<Escrow> list(Connection connection, String status) throws SQLException {
        List<Escrow> rows = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id,auction_id,bidder_uuid,buyer_enterprise_id,amount,status,last_error " +
                        "FROM ks_bank_auction_bid_escrows WHERE status=? ORDER BY created_at,id")) {
            statement.setString(1, status);
            try (var result = statement.executeQuery()) {
                while (result.next()) rows.add(read(result));
            }
        }
        return List.copyOf(rows);
    }

    private static Escrow read(java.sql.ResultSet rows) throws SQLException {
        return new Escrow(rows.getString(1), rows.getString(2), UUID.fromString(rows.getString(3)),
                rows.getString(4), rows.getDouble(5), rows.getString(6), rows.getString(7));
    }

    private static boolean transition(Connection connection, String id, String expected, String next,
                                      String error, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_bank_auction_bid_escrows SET status=?,last_error=?,updated_at=? WHERE id=? AND status=?")) {
            statement.setString(1, next);
            statement.setString(2, error == null ? "" : error);
            statement.setLong(3, now);
            statement.setString(4, id);
            statement.setString(5, expected);
            return statement.executeUpdate() == 1;
        }
    }

    private static void requireAutoCommit(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Auction escrow transition requires auto-commit connection");
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
