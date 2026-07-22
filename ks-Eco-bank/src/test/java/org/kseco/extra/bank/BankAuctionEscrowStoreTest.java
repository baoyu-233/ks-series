package org.kseco.extra.bank;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankAuctionEscrowStoreTest {

    @Test
    void replacementBidAtomicallyHoldsNewMoneyAndQueuesOldRefund() throws Exception {
        try (Connection connection = database("PLOT")) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            BankAuctionEscrowStore.BidReservation one = BankAuctionEscrowStore.prepare(
                    connection, "AU-1", first, null, 100, 10);
            assertNotNull(one);
            assertTrue(BankAuctionEscrowStore.commitHold(connection, one, 11));

            BankAuctionEscrowStore.BidReservation two = BankAuctionEscrowStore.prepare(
                    connection, "AU-1", second, null, 120, 12);
            assertNotNull(two);
            assertEquals(one.escrowId(), two.previousEscrowId());
            assertTrue(BankAuctionEscrowStore.commitHold(connection, two, 13));
            assertEquals(BankAuctionEscrowStore.REFUND_READY,
                    BankAuctionEscrowStore.find(connection, one.escrowId()).status());
            assertEquals(BankAuctionEscrowStore.HELD,
                    BankAuctionEscrowStore.find(connection, two.escrowId()).status());
        }
    }

    @Test
    void staleBidCannotReplaceNewerWinner() throws Exception {
        try (Connection connection = database("PLOT")) {
            BankAuctionEscrowStore.BidReservation stale = BankAuctionEscrowStore.prepare(
                    connection, "AU-1", UUID.randomUUID(), null, 100, 10);
            BankAuctionEscrowStore.BidReservation winner = BankAuctionEscrowStore.prepare(
                    connection, "AU-1", UUID.randomUUID(), null, 110, 10);
            assertTrue(BankAuctionEscrowStore.commitHold(connection, winner, 11));
            assertFalse(BankAuctionEscrowStore.commitHold(connection, stale, 12));
        }
    }

    @Test
    void projectContractRequiresExplicitEnterpriseTarget() throws Exception {
        try (Connection connection = database("PROJECT_CONTRACT")) {
            assertEquals(null, BankAuctionEscrowStore.prepare(
                    connection, "AU-1", UUID.randomUUID(), null, 100, 10));
            assertNotNull(BankAuctionEscrowStore.prepare(
                    connection, "AU-1", UUID.randomUUID(), "ENT-2", 100, 10));
        }
    }

    private static Connection database(String assetType) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_bank_collateral_auctions (id TEXT PRIMARY KEY,asset_type TEXT,starting_price REAL,current_price REAL,highest_bidder_uuid TEXT,highest_escrow_id TEXT,buyer_enterprise_id TEXT,status TEXT,closes_at INTEGER,version INTEGER)");
            statement.execute("INSERT INTO ks_bank_collateral_auctions VALUES ('AU-1','" + assetType + "',100,100,NULL,NULL,NULL,'OPEN',1000,0)");
        }
        BankAuctionEscrowStore.initialize(connection);
        return connection;
    }
}
