package org.kseco.extra.realestatedungeon;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonReviveStoreTest {

    @Test
    void participantBecomesAliveOnlyAfterReturnCompletes() throws Exception {
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();
            seed(connection, player);
            DungeonReviveStore.Revival revival = reserveAndPay(connection, player);

            assertNotNull(revival);
            assertEquals("REVIVE_PENDING", participantStatus(connection));
            assertEquals(DungeonReviveStore.PAID_PENDING, revivalStatus(connection));
            assertTrue(DungeonReviveStore.completeReturn(connection, revival, 20));
            assertEquals("ALIVE", participantStatus(connection));
            assertEquals(DungeonReviveStore.RETURNED, revivalStatus(connection));
        }
    }

    @Test
    void claimedRefundIsIdempotentAndOnlySuccessfulRefundReopensDeath() throws Exception {
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();
            seed(connection, player);
            DungeonReviveStore.Revival revival = reserveAndPay(connection, player);

            assertTrue(DungeonReviveStore.prepareRefund(connection, revival,
                    DungeonReviveStore.PAID_PENDING, "teleport failed", 20));
            assertTrue(DungeonReviveStore.claimRefund(connection, revival, 21));
            assertFalse(DungeonReviveStore.claimRefund(connection, revival, 22));
            assertTrue(DungeonReviveStore.finishRefund(connection, revival, true, "refunded", 23));
            assertEquals("DEAD", participantStatus(connection));
            assertEquals(DungeonReviveStore.REFUNDED, revivalStatus(connection));
            assertTrue(DungeonReviveStore.pending(connection).isEmpty());
        }
    }

    @Test
    void concurrentOrRepeatedReservationCannotChargeSameDeathTwice() throws Exception {
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();
            seed(connection, player);
            assertNotNull(DungeonReviveStore.reserve(connection, "DI-test", player, 0, 1, 200, 10));
            assertNull(DungeonReviveStore.reserve(connection, "DI-test", player, 0, 1, 200, 11));
            assertEquals(DungeonReviveStore.CHARGE_READY, revivalStatus(connection));
            assertFalse("ALIVE".equals(participantStatus(connection)));
        }
    }

    @Test
    void uncertainVaultOutcomeRequiresReviewAndDoesNotReopenCharging() throws Exception {
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();
            seed(connection, player);
            DungeonReviveStore.Revival revival = DungeonReviveStore.reserve(
                    connection, "DI-test", player, 0, 1, 200, 10);
            assertNotNull(revival);
            assertTrue(DungeonReviveStore.claimCharge(connection, revival, 11));
            assertTrue(DungeonReviveStore.markReview(connection, revival,
                    DungeonReviveStore.CHARGE_CLAIMED, "unknown", 12));

            assertEquals(DungeonReviveStore.REVIEW_REQUIRED, revivalStatus(connection));
            assertEquals("REVIVE_PENDING", participantStatus(connection));
            assertNull(DungeonReviveStore.reserve(connection, "DI-test", player, 1, 2, 360, 13));
        }
    }

    @Test
    void rejectedRefundRequiresReviewAndDoesNotReopenCharging() throws Exception {
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();
            seed(connection, player);
            DungeonReviveStore.Revival revival = reserveAndPay(connection, player);
            assertTrue(DungeonReviveStore.prepareRefund(connection, revival,
                    DungeonReviveStore.PAID_PENDING, "world released", 20));
            assertTrue(DungeonReviveStore.claimRefund(connection, revival, 21));
            assertTrue(DungeonReviveStore.finishRefund(connection, revival, false,
                    "Vault rejected refund", 22));

            assertEquals(DungeonReviveStore.REVIEW_REQUIRED, revivalStatus(connection));
            assertEquals("REVIVE_PENDING", participantStatus(connection));
            assertNull(DungeonReviveStore.reserve(connection, "DI-test", player, 1, 2, 360, 23));
        }
    }

    @Test
    void startupCancelsUnclaimedChargeButReviewsClaimedCharge() throws Exception {
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();
            seed(connection, player);
            DungeonReviveStore.reserve(connection, "DI-test", player, 0, 1, 200, 10);
            assertEquals(1, DungeonReviveStore.cancelUnclaimedCharges(connection, 20));
            assertEquals("DEAD", participantStatus(connection));
            assertEquals(DungeonReviveStore.CHARGE_REJECTED, revivalStatus(connection));
        }
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();
            seed(connection, player);
            DungeonReviveStore.Revival revival = DungeonReviveStore.reserve(
                    connection, "DI-test", player, 0, 1, 200, 10);
            assertTrue(DungeonReviveStore.claimCharge(connection, revival, 11));
            assertEquals(1, DungeonReviveStore.recoverUncertainClaims(connection, 20));
            assertEquals("REVIVE_PENDING", participantStatus(connection));
            assertEquals(DungeonReviveStore.REVIEW_REQUIRED, revivalStatus(connection));
        }
    }

    @Test
    void inactiveInstanceCannotConfirmPaidOrCompleteReturn() throws Exception {
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();
            seed(connection, player);
            DungeonReviveStore.Revival revival = DungeonReviveStore.reserve(
                    connection, "DI-test", player, 0, 1, 200, 10);
            assertTrue(DungeonReviveStore.claimCharge(connection, revival, 11));
            connection.createStatement().executeUpdate("UPDATE ks_dungeon_instances SET status='ABANDONED'");
            assertFalse(DungeonReviveStore.confirmPaid(connection, revival, 12));
            assertEquals("REVIVE_PENDING", participantStatus(connection));
            assertEquals(DungeonReviveStore.CHARGE_CLAIMED, revivalStatus(connection));
        }
    }

    private static DungeonReviveStore.Revival reserveAndPay(Connection connection, UUID player) throws Exception {
        DungeonReviveStore.Revival revival = DungeonReviveStore.reserve(
                connection, "DI-test", player, 0, 1, 200, 10);
        assertNotNull(revival);
        assertTrue(DungeonReviveStore.claimCharge(connection, revival, 11));
        assertTrue(DungeonReviveStore.confirmPaid(connection, revival, 12));
        return revival;
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_dungeon_instances (id TEXT PRIMARY KEY,status TEXT NOT NULL)");
            statement.execute("CREATE TABLE ks_dungeon_participants (instance_id TEXT,player_uuid TEXT,status TEXT,revive_count INTEGER,died_at INTEGER,PRIMARY KEY(instance_id,player_uuid))");
            statement.execute("CREATE TABLE ks_dungeon_revivals (id TEXT PRIMARY KEY,instance_id TEXT,player_uuid TEXT,revive_count INTEGER,cost_paid REAL,formula_cost REAL,revived_at INTEGER,return_status TEXT,last_error TEXT)");
        }
        return connection;
    }

    private static void seed(Connection connection, UUID player) throws Exception {
        try (var instance = connection.prepareStatement("INSERT INTO ks_dungeon_instances VALUES ('DI-test','ACTIVE')");
             var participant = connection.prepareStatement("INSERT INTO ks_dungeon_participants VALUES ('DI-test',?,'DEAD',0,1)")) {
            instance.executeUpdate();
            participant.setString(1, player.toString());
            participant.executeUpdate();
        }
    }

    private static String participantStatus(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT status FROM ks_dungeon_participants")) {
            return rows.next() ? rows.getString(1) : "";
        }
    }

    private static String revivalStatus(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT return_status FROM ks_dungeon_revivals")) {
            return rows.next() ? rows.getString(1) : "";
        }
    }
}
