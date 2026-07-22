package org.kseco;

import org.kseco.database.BusinessSchemaDialect;
import org.kseco.extra.EnterpriseFundSettlementProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable SQL lifecycle for personal-wallet and enterprise-account property market settlement. */
final class PropertyMarketSettlementStore {
    static final String BUYER_CHARGE_READY = "BUYER_CHARGE_READY";
    static final String BUYER_CHARGE_CLAIMED = "BUYER_CHARGE_CLAIMED";
    static final String TRANSFER_READY = "TRANSFER_READY";
    static final String TRANSFER_CLAIMED = "TRANSFER_CLAIMED";
    static final String SELLER_PAYOUT_READY = "SELLER_PAYOUT_READY";
    static final String SELLER_PAYOUT_CLAIMED = "SELLER_PAYOUT_CLAIMED";
    static final String REFUND_READY = "REFUND_READY";
    static final String REFUND_CLAIMED = "REFUND_CLAIMED";
    static final String FINALIZED = "FINALIZED";
    static final String COMPENSATED = "COMPENSATED";
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    record Settlement(String id, String listingId, String houseId, UUID buyerUuid, String buyerName,
                      UUID sellerUuid, String expectedOwnerType, String expectedOwnerId,
                      double saleAmount, double taxRate, double taxAmount, double totalCharge,
                      String status, String reviewStage, String lastError) { }

    private PropertyMarketSettlementStore() { }

    static void initialize(Connection connection) throws SQLException {
        String floatingPoint = BusinessSchemaDialect.floatingPointType(BusinessSchemaDialect.detect(connection));
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_property_settlements (
                        id VARCHAR(128) PRIMARY KEY,
                        active_house_id VARCHAR(128),
                        listing_id VARCHAR(128) NOT NULL,
                        house_id VARCHAR(128) NOT NULL,
                        buyer_uuid VARCHAR(36) NOT NULL,
                        buyer_name VARCHAR(64) NOT NULL,
                        seller_uuid VARCHAR(36) NOT NULL,
                        expected_owner_type VARCHAR(32) NOT NULL,
                        expected_owner_id VARCHAR(128) NOT NULL,
                        sale_amount %s NOT NULL,
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
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_property_settlement_status",
                "ks_eco_property_settlements", "status", "updated_at");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_property_settlement_listing",
                "ks_eco_property_settlements", "listing_id", "updated_at");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_eco_property_settlements", "active_house_id",
                "VARCHAR(128)");
        try (var statement = connection.prepareStatement(
                "UPDATE ks_eco_property_settlements SET active_house_id=house_id "
                        + "WHERE active_house_id IS NULL AND status NOT IN (?,?)")) {
            statement.setString(1, FINALIZED);
            statement.setString(2, COMPENSATED);
            statement.executeUpdate();
        }
        BusinessSchemaDialect.createUniqueIndexIfMissing(connection, "uq_property_settlement_active_house",
                "ks_eco_property_settlements", "active_house_id");
    }

    static void prepare(Connection connection, Settlement settlement, long now) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (var listing = connection.prepareStatement(
                    "UPDATE ks_eco_listings SET status='SETTLING' WHERE id=? AND status='ACTIVE' "
                            + "AND listing_asset_type='PROPERTY' AND asset_ref=? AND seller_uuid=?")) {
                listing.setString(1, settlement.listingId());
                listing.setString(2, settlement.houseId());
                listing.setString(3, settlement.sellerUuid().toString());
                if (listing.executeUpdate() != 1) throw new SQLException("property listing changed before settlement");
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO ks_eco_property_settlements
                    (id,listing_id,house_id,active_house_id,buyer_uuid,buyer_name,seller_uuid,expected_owner_type,
                     expected_owner_id,sale_amount,tax_rate,tax_amount,total_charge,status,review_stage,
                     last_error,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                statement.setString(1, settlement.id());
                statement.setString(2, settlement.listingId());
                statement.setString(3, settlement.houseId());
                statement.setString(4, settlement.houseId());
                statement.setString(5, settlement.buyerUuid().toString());
                statement.setString(6, settlement.buyerName());
                statement.setString(7, settlement.sellerUuid().toString());
                statement.setString(8, settlement.expectedOwnerType());
                statement.setString(9, settlement.expectedOwnerId());
                statement.setDouble(10, settlement.saleAmount());
                statement.setDouble(11, settlement.taxRate());
                statement.setDouble(12, settlement.taxAmount());
                statement.setDouble(13, settlement.totalCharge());
                statement.setString(14, BUYER_CHARGE_READY);
                statement.setString(15, "");
                statement.setString(16, "");
                statement.setLong(17, now);
                statement.setLong(18, now);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    static boolean transition(Connection connection, String id, String expected, String next,
                              String error, long now) throws SQLException {
        boolean terminal = FINALIZED.equals(next) || COMPENSATED.equals(next);
        try (var statement = connection.prepareStatement(
                "UPDATE ks_eco_property_settlements SET status=?,review_stage=?,last_error=?,updated_at=?,"
                        + (terminal ? "active_house_id=NULL " : "active_house_id=active_house_id ")
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

    static boolean finishTransfer(Connection connection, Settlement settlement, long now) throws SQLException {
        return listingAndJournal(connection, settlement, TRANSFER_CLAIMED, SELLER_PAYOUT_READY,
                "SETTLING", "FILLED", "", now);
    }

    static boolean finishEnterprisePayout(Connection connection, Settlement settlement,
                                          EnterpriseFundSettlementProvider provider, long now) throws SQLException {
        if (!"ENTERPRISE".equalsIgnoreCase(settlement.expectedOwnerType()) || provider == null) {
            throw new SQLException("enterprise property settlement provider is unavailable");
        }
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (!transition(connection, settlement.id(), SELLER_PAYOUT_READY,
                    SELLER_PAYOUT_CLAIMED, "", now)) {
                connection.rollback();
                return false;
            }
            provider.creditPropertySale(connection, settlement.expectedOwnerId(), settlement.saleAmount(), now);
            if (!transition(connection, settlement.id(), SELLER_PAYOUT_CLAIMED, FINALIZED, "", now)) {
                throw new SQLException("enterprise property payout journal changed concurrently");
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    static boolean compensateBeforeTransfer(Connection connection, Settlement settlement,
                                            String expectedStatus, String reason, long now) throws SQLException {
        return listingAndJournal(connection, settlement, expectedStatus, COMPENSATED,
                "SETTLING", "ACTIVE", reason, now);
    }

    static boolean prepareRefund(Connection connection, Settlement settlement,
                                 String expectedStatus, String reason, long now) throws SQLException {
        return listingAndJournal(connection, settlement, expectedStatus, REFUND_READY,
                "SETTLING", "ACTIVE", reason, now);
    }

    private static boolean listingAndJournal(Connection connection, Settlement settlement,
                                             String expectedJournal, String nextJournal,
                                             String expectedListing, String nextListing,
                                             String reason, long now) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (var listing = connection.prepareStatement(
                    "UPDATE ks_eco_listings SET status=? WHERE id=? AND status=?")) {
                listing.setString(1, nextListing);
                listing.setString(2, settlement.listingId());
                listing.setString(3, expectedListing);
                if (listing.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            if (!transition(connection, settlement.id(), expectedJournal, nextJournal, reason, now)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
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

    static List<Settlement> open(Connection connection) throws SQLException {
        List<Settlement> settlements = new ArrayList<>();
        try (var statement = connection.prepareStatement(selectColumns()
                + " WHERE status NOT IN (?,?,?) ORDER BY created_at,id")) {
            statement.setString(1, FINALIZED);
            statement.setString(2, COMPENSATED);
            statement.setString(3, REVIEW_REQUIRED);
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

    static int markUnknownWalletCallsForReview(Connection connection, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_eco_property_settlements SET review_stage=status,status=?,last_error=?,updated_at=? "
                        + "WHERE status IN (?,?) OR (status=? AND expected_owner_type='PLAYER')")) {
            statement.setString(1, REVIEW_REQUIRED);
            statement.setString(2, "external wallet outcome unknown after restart");
            statement.setLong(3, now);
            statement.setString(4, BUYER_CHARGE_CLAIMED);
            statement.setString(5, REFUND_CLAIMED);
            statement.setString(6, SELLER_PAYOUT_CLAIMED);
            return statement.executeUpdate();
        }
    }

    static int resetAtomicEnterprisePayoutClaims(Connection connection, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_eco_property_settlements SET status=?,review_stage='',last_error=?,updated_at=? "
                        + "WHERE status=? AND expected_owner_type='ENTERPRISE'")) {
            statement.setString(1, SELLER_PAYOUT_READY);
            statement.setString(2, "recovered transaction-local enterprise payout claim");
            statement.setLong(3, now);
            statement.setString(4, SELLER_PAYOUT_CLAIMED);
            return statement.executeUpdate();
        }
    }

    static boolean resolveReview(Connection connection, String id, String reviewStage,
                                 String nextStatus, String note, long now) throws SQLException {
        boolean terminal = FINALIZED.equals(nextStatus) || COMPENSATED.equals(nextStatus);
        try (var statement = connection.prepareStatement(
                "UPDATE ks_eco_property_settlements SET status=?,review_stage='',last_error=?,updated_at=?,"
                        + (terminal ? "active_house_id=NULL " : "active_house_id=active_house_id ")
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
        return "SELECT id,listing_id,house_id,buyer_uuid,buyer_name,seller_uuid,expected_owner_type,"
                + "expected_owner_id,sale_amount,tax_rate,tax_amount,total_charge,status,review_stage,last_error "
                + "FROM ks_eco_property_settlements";
    }

    private static Settlement read(java.sql.ResultSet rows) throws SQLException {
        return new Settlement(rows.getString(1), rows.getString(2), rows.getString(3),
                UUID.fromString(rows.getString(4)), rows.getString(5), UUID.fromString(rows.getString(6)),
                rows.getString(7), rows.getString(8), rows.getDouble(9), rows.getDouble(10),
                rows.getDouble(11), rows.getDouble(12), rows.getString(13), rows.getString(14), rows.getString(15));
    }
}
