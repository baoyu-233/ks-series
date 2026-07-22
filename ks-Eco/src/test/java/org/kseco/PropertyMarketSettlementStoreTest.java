package org.kseco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class PropertyMarketSettlementStoreTest {
    @Test
    void locksListingAndUsesStageCheckedReviewResolution() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("""
                    CREATE TABLE ks_eco_listings (
                        id TEXT PRIMARY KEY, seller_uuid TEXT NOT NULL, listing_asset_type TEXT,
                        asset_ref TEXT, status TEXT NOT NULL)
                    """);
            UUID seller = UUID.randomUUID();
            connection.createStatement().execute("INSERT INTO ks_eco_listings VALUES "
                    + "('listing','" + seller + "','PROPERTY','house','ACTIVE')");
            PropertyMarketSettlementStore.initialize(connection);
            PropertyMarketSettlementStore.Settlement settlement = new PropertyMarketSettlementStore.Settlement(
                    "settlement", "listing", "house", UUID.randomUUID(), "buyer", seller,
                    "PLAYER", seller.toString(), 100.0d, 0.1d, 10.0d, 110.0d,
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY, "", "");

            PropertyMarketSettlementStore.prepare(connection, settlement, 1L);
            assertEquals("SETTLING", listingStatus(connection));
            assertTrue(PropertyMarketSettlementStore.transition(connection, settlement.id(),
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY,
                    PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED, "", 2L));
            assertTrue(PropertyMarketSettlementStore.transition(connection, settlement.id(),
                    PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                    PropertyMarketSettlementStore.REVIEW_REQUIRED, "unknown", 3L));
            PropertyMarketSettlementStore.Settlement review =
                    PropertyMarketSettlementStore.find(connection, settlement.id());
            assertFalse(PropertyMarketSettlementStore.resolveReview(connection, settlement.id(), "wrong",
                    PropertyMarketSettlementStore.TRANSFER_READY, "", 4L));
            assertTrue(PropertyMarketSettlementStore.resolveReview(connection, settlement.id(), review.reviewStage(),
                    PropertyMarketSettlementStore.TRANSFER_READY, "confirmed", 5L));
        }
    }

    @Test
    void transferCompletionAndRefundChangeListingAtomically() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("""
                    CREATE TABLE ks_eco_listings (
                        id TEXT PRIMARY KEY, seller_uuid TEXT NOT NULL, listing_asset_type TEXT,
                        asset_ref TEXT, status TEXT NOT NULL)
                    """);
            UUID seller = UUID.randomUUID();
            connection.createStatement().execute("INSERT INTO ks_eco_listings VALUES "
                    + "('listing','" + seller + "','PROPERTY','house','ACTIVE')");
            PropertyMarketSettlementStore.initialize(connection);
            PropertyMarketSettlementStore.Settlement settlement = new PropertyMarketSettlementStore.Settlement(
                    "settlement", "listing", "house", UUID.randomUUID(), "buyer", seller,
                    "PLAYER", seller.toString(), 100.0d, 0.1d, 10.0d, 110.0d,
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY, "", "");
            PropertyMarketSettlementStore.prepare(connection, settlement, 1L);
            PropertyMarketSettlementStore.transition(connection, settlement.id(),
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY,
                    PropertyMarketSettlementStore.TRANSFER_CLAIMED, "", 2L);
            assertTrue(PropertyMarketSettlementStore.finishTransfer(connection, settlement, 3L));
            assertEquals("FILLED", listingStatus(connection));
        }
    }

    @Test
    void settlingListingCannotBeClaimedByASecondBuyer() throws Exception {
        try (Connection connection = database()) {
            UUID seller = UUID.randomUUID();
            insertListing(connection, "listing", "house", seller);
            PropertyMarketSettlementStore.Settlement first = settlement(
                    "first", "listing", "house", seller, PropertyMarketSettlementStore.BUYER_CHARGE_READY);
            PropertyMarketSettlementStore.Settlement second = settlement(
                    "second", "listing", "house", seller, PropertyMarketSettlementStore.BUYER_CHARGE_READY);

            PropertyMarketSettlementStore.prepare(connection, first, 1L);
            assertThrows(SQLException.class,
                    () -> PropertyMarketSettlementStore.prepare(connection, second, 2L));

            assertEquals("SETTLING", listingStatus(connection));
            assertNotNull(PropertyMarketSettlementStore.find(connection, first.id()));
            assertNull(PropertyMarketSettlementStore.find(connection, second.id()));
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    void failedAtomicJournalTransitionDoesNotChangeListing() throws Exception {
        try (Connection connection = database()) {
            UUID seller = UUID.randomUUID();
            insertListing(connection, "listing", "house", seller);
            PropertyMarketSettlementStore.Settlement settlement = settlement(
                    "settlement", "listing", "house", seller,
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY);
            PropertyMarketSettlementStore.prepare(connection, settlement, 1L);

            assertFalse(PropertyMarketSettlementStore.finishTransfer(connection, settlement, 2L));

            assertEquals("SETTLING", listingStatus(connection));
            assertEquals(PropertyMarketSettlementStore.BUYER_CHARGE_READY,
                    PropertyMarketSettlementStore.find(connection, settlement.id()).status());
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    void chargeRejectionRestoresListingForAReplacementSettlement() throws Exception {
        try (Connection connection = database()) {
            UUID seller = UUID.randomUUID();
            insertListing(connection, "listing", "house", seller);
            PropertyMarketSettlementStore.Settlement rejected = settlement(
                    "rejected", "listing", "house", seller,
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY);
            PropertyMarketSettlementStore.prepare(connection, rejected, 1L);
            assertTrue(PropertyMarketSettlementStore.transition(connection, rejected.id(),
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY,
                    PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED, "", 2L));

            assertTrue(PropertyMarketSettlementStore.compensateBeforeTransfer(connection, rejected,
                    PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED, "charge rejected", 3L));
            assertEquals("ACTIVE", listingStatus(connection));
            assertEquals(PropertyMarketSettlementStore.COMPENSATED,
                    PropertyMarketSettlementStore.find(connection, rejected.id()).status());

            PropertyMarketSettlementStore.Settlement replacement = settlement(
                    "replacement", "listing", "house", seller,
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY);
            PropertyMarketSettlementStore.prepare(connection, replacement, 4L);
            assertEquals("SETTLING", listingStatus(connection));
            assertNotNull(PropertyMarketSettlementStore.find(connection, replacement.id()));
        }
    }

    @Test
    void startupRecoveryQuarantinesOnlyUnknownWalletCallsWithTheirExactStage() throws Exception {
        try (Connection connection = database()) {
            UUID seller = UUID.randomUUID();
            prepareAtStatus(connection, seller, "charge", PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED);
            prepareAtStatus(connection, seller, "payout", PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED);
            prepareAtStatus(connection, seller, "refund", PropertyMarketSettlementStore.REFUND_CLAIMED);
            prepareAtStatus(connection, seller, "transfer", PropertyMarketSettlementStore.TRANSFER_CLAIMED);

            assertEquals(3, PropertyMarketSettlementStore.markUnknownWalletCallsForReview(connection, 10L));

            List<PropertyMarketSettlementStore.Settlement> reviews =
                    PropertyMarketSettlementStore.reviewRequired(connection);
            assertEquals(3, reviews.size());
            assertEquals(List.of(
                            PropertyMarketSettlementStore.BUYER_CHARGE_CLAIMED,
                            PropertyMarketSettlementStore.REFUND_CLAIMED,
                            PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED),
                    reviews.stream().map(PropertyMarketSettlementStore.Settlement::reviewStage).sorted().toList());
            assertTrue(reviews.stream().allMatch(row ->
                    row.lastError().contains("external wallet outcome unknown")));

            List<PropertyMarketSettlementStore.Settlement> open = PropertyMarketSettlementStore.open(connection);
            assertEquals(1, open.size());
            assertEquals("transfer", open.getFirst().id());
            assertEquals(PropertyMarketSettlementStore.TRANSFER_CLAIMED, open.getFirst().status());
        }
    }

    @Test
    void startupRecoveryRetriesTransactionLocalEnterprisePayoutClaim() throws Exception {
        try (Connection connection = database()) {
            UUID seller = UUID.randomUUID();
            insertListing(connection, "listing", "house", seller);
            PropertyMarketSettlementStore.Settlement settlement = enterpriseSettlement(seller);
            PropertyMarketSettlementStore.prepare(connection, settlement, 1L);
            assertTrue(PropertyMarketSettlementStore.transition(connection, settlement.id(),
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY,
                    PropertyMarketSettlementStore.SELLER_PAYOUT_CLAIMED, "", 2L));

            assertEquals(1, PropertyMarketSettlementStore.resetAtomicEnterprisePayoutClaims(connection, 3L));
            assertEquals(0, PropertyMarketSettlementStore.markUnknownWalletCallsForReview(connection, 4L));
            assertEquals(PropertyMarketSettlementStore.SELLER_PAYOUT_READY,
                    PropertyMarketSettlementStore.find(connection, settlement.id()).status());
        }
    }

    @Test
    void enterprisePayoutCreditsCorporateAccountAndFinalizesInOneTransaction() throws Exception {
        try (Connection connection = database()) {
            connection.createStatement().execute(
                    "CREATE TABLE corporate_account (enterprise_id TEXT PRIMARY KEY,balance REAL NOT NULL)");
            connection.createStatement().execute("INSERT INTO corporate_account VALUES ('enterprise-1',0)");
            UUID seller = UUID.randomUUID();
            insertListing(connection, "listing", "house", seller);
            PropertyMarketSettlementStore.Settlement settlement = enterpriseSettlement(seller);
            PropertyMarketSettlementStore.prepare(connection, settlement, 1L);
            assertTrue(PropertyMarketSettlementStore.transition(connection, settlement.id(),
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY,
                    PropertyMarketSettlementStore.TRANSFER_CLAIMED, "", 2L));
            assertTrue(PropertyMarketSettlementStore.finishTransfer(connection, settlement, 3L));

            assertTrue(PropertyMarketSettlementStore.finishEnterprisePayout(connection, settlement,
                    (conn, enterpriseId, amount, now) -> {
                        try (var credit = conn.prepareStatement(
                                "UPDATE corporate_account SET balance=balance+? WHERE enterprise_id=?")) {
                            credit.setDouble(1, amount);
                            credit.setString(2, enterpriseId);
                            if (credit.executeUpdate() != 1) throw new SQLException("account missing");
                        }
                    }, 4L));
            assertFalse(PropertyMarketSettlementStore.finishEnterprisePayout(connection, settlement,
                    (conn, enterpriseId, amount, now) -> {
                        throw new AssertionError("a finalized payout must not execute again");
                    }, 5L));
            assertEquals(PropertyMarketSettlementStore.FINALIZED,
                    PropertyMarketSettlementStore.find(connection, settlement.id()).status());
            assertEquals(100.0d, scalar(connection, "SELECT balance FROM corporate_account"));
        }
    }

    @Test
    void failedEnterprisePayoutRollsBackClaimAndCredit() throws Exception {
        try (Connection connection = database()) {
            UUID seller = UUID.randomUUID();
            insertListing(connection, "listing", "house", seller);
            PropertyMarketSettlementStore.Settlement settlement = enterpriseSettlement(seller);
            PropertyMarketSettlementStore.prepare(connection, settlement, 1L);
            assertTrue(PropertyMarketSettlementStore.transition(connection, settlement.id(),
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY,
                    PropertyMarketSettlementStore.TRANSFER_CLAIMED, "", 2L));
            assertTrue(PropertyMarketSettlementStore.finishTransfer(connection, settlement, 3L));

            assertThrows(SQLException.class, () -> PropertyMarketSettlementStore.finishEnterprisePayout(
                    connection, settlement, (conn, enterpriseId, amount, now) -> {
                        throw new SQLException("bank mirror unavailable");
                    }, 4L));
            assertEquals(PropertyMarketSettlementStore.SELLER_PAYOUT_READY,
                    PropertyMarketSettlementStore.find(connection, settlement.id()).status());
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    void concurrentEnterprisePayoutClaimCreditsExactlyOnce() throws Exception {
        String url = "jdbc:h2:mem:property-payout-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        UUID seller = UUID.randomUUID();
        PropertyMarketSettlementStore.Settlement settlement = enterpriseSettlement(seller);
        try (Connection setup = DriverManager.getConnection(url)) {
            setup.createStatement().execute("""
                    CREATE TABLE ks_eco_listings (
                        id VARCHAR(128) PRIMARY KEY, seller_uuid VARCHAR(36) NOT NULL,
                        listing_asset_type VARCHAR(32), asset_ref VARCHAR(128), status VARCHAR(32) NOT NULL)
                    """);
            setup.createStatement().execute(
                    "CREATE TABLE corporate_account (enterprise_id VARCHAR(128) PRIMARY KEY,balance DOUBLE PRECISION NOT NULL)");
            setup.createStatement().execute("INSERT INTO corporate_account VALUES ('enterprise-1',0)");
            PropertyMarketSettlementStore.initialize(setup);
            insertListing(setup, "listing", "house", seller);
            PropertyMarketSettlementStore.prepare(setup, settlement, 1L);
            assertTrue(PropertyMarketSettlementStore.transition(setup, settlement.id(),
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY,
                    PropertyMarketSettlementStore.TRANSFER_CLAIMED, "", 2L));
            assertTrue(PropertyMarketSettlementStore.finishTransfer(setup, settlement, 3L));
        }
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var provider = (org.kseco.extra.EnterpriseFundSettlementProvider)
                    (conn, enterpriseId, amount, now) -> {
                        try (var credit = conn.prepareStatement(
                                "UPDATE corporate_account SET balance=balance+? WHERE enterprise_id=?")) {
                            credit.setDouble(1, amount);
                            credit.setString(2, enterpriseId);
                            if (credit.executeUpdate() != 1) throw new SQLException("account missing");
                        }
                    };
            var first = executor.submit(() -> {
                start.await();
                try (Connection connection = DriverManager.getConnection(url)) {
                    return PropertyMarketSettlementStore.finishEnterprisePayout(
                            connection, settlement, provider, 4L);
                }
            });
            var second = executor.submit(() -> {
                start.await();
                try (Connection connection = DriverManager.getConnection(url)) {
                    return PropertyMarketSettlementStore.finishEnterprisePayout(
                            connection, settlement, provider, 4L);
                }
            });
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }
        try (Connection verification = DriverManager.getConnection(url)) {
            assertEquals(100.0d, scalar(verification, "SELECT balance FROM corporate_account"));
            assertEquals(PropertyMarketSettlementStore.FINALIZED,
                    PropertyMarketSettlementStore.find(verification, settlement.id()).status());
        }
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.createStatement().execute("""
                CREATE TABLE ks_eco_listings (
                    id TEXT PRIMARY KEY, seller_uuid TEXT NOT NULL, listing_asset_type TEXT,
                    asset_ref TEXT, status TEXT NOT NULL)
                """);
        PropertyMarketSettlementStore.initialize(connection);
        return connection;
    }

    private static void insertListing(Connection connection, String listingId, String houseId, UUID seller)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_eco_listings VALUES (?,?, 'PROPERTY', ?, 'ACTIVE')")) {
            statement.setString(1, listingId);
            statement.setString(2, seller.toString());
            statement.setString(3, houseId);
            statement.executeUpdate();
        }
    }

    private static PropertyMarketSettlementStore.Settlement settlement(
            String id, String listingId, String houseId, UUID seller, String status) {
        return new PropertyMarketSettlementStore.Settlement(
                id, listingId, houseId, UUID.randomUUID(), "buyer", seller,
                "PLAYER", seller.toString(), 100.0d, 0.1d, 10.0d, 110.0d,
                status, "", "");
    }

    private static PropertyMarketSettlementStore.Settlement enterpriseSettlement(UUID seller) {
        return new PropertyMarketSettlementStore.Settlement(
                "settlement", "listing", "house", UUID.randomUUID(), "buyer", seller,
                "ENTERPRISE", "enterprise-1", 100.0d, 0.1d, 10.0d, 110.0d,
                PropertyMarketSettlementStore.BUYER_CHARGE_READY, "", "");
    }

    private static double scalar(Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getDouble(1);
        }
    }

    private static void prepareAtStatus(Connection connection, UUID seller, String id, String status)
            throws Exception {
        String listingId = id + "-listing";
        insertListing(connection, listingId, id + "-house", seller);
        PropertyMarketSettlementStore.Settlement settlement = settlement(
                id, listingId, id + "-house", seller, PropertyMarketSettlementStore.BUYER_CHARGE_READY);
        PropertyMarketSettlementStore.prepare(connection, settlement, 1L);
        if (!PropertyMarketSettlementStore.BUYER_CHARGE_READY.equals(status)) {
            assertTrue(PropertyMarketSettlementStore.transition(connection, id,
                    PropertyMarketSettlementStore.BUYER_CHARGE_READY, status, "", 2L));
        }
    }

    private static String listingStatus(Connection connection) throws Exception {
        return listingStatus(connection, "listing");
    }

    private static String listingStatus(Connection connection, String listingId) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT status FROM ks_eco_listings WHERE id=?")) {
            statement.setString(1, listingId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

}
