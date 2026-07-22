package org.kseco.extra.realestatedungeon;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRewardGrantStoreTest {

    @Test
    void compoundKeySeparatesPlayersAndRewardComponents() throws Exception {
        try (Connection connection = database()) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();

            DungeonRewardGrantStore.ensure(connection, "DI-test", first, "Money:CASH", 10);
            DungeonRewardGrantStore.ensure(connection, "DI-test", first, "proof:clear", 11);
            DungeonRewardGrantStore.ensure(connection, "DI-test", second, "money:cash", 12);
            DungeonRewardGrantStore.ensure(connection, "DI-test", first, "money:cash", 13);

            assertEquals(3L, count(connection));
            assertThrows(SQLException.class, () -> duplicateExistingRow(connection, first));
            DungeonRewardGrantStore.Grant grant = DungeonRewardGrantStore.find(
                    connection, "DI-test", first, "MONEY:CASH").orElseThrow();
            assertEquals("money:cash", grant.rewardKey());
            assertEquals(DungeonRewardGrantStore.STATUS_NONE, grant.status());
            assertEquals(0, grant.attemptCount());
            assertEquals(10, grant.createdAt());
        }
    }

    @Test
    void retryClaimsOnlyKnownFailuresAndNeverReclaimsGrantedPlayer() throws Exception {
        try (Connection connection = database()) {
            UUID grantedPlayer = UUID.randomUUID();
            UUID retryPlayer = UUID.randomUUID();

            assertEquals(DungeonRewardGrantStore.ClaimResult.CLAIMED,
                    DungeonRewardGrantStore.claim(connection, "DI-test", grantedPlayer, "money:cash", 10));
            assertEquals(DungeonRewardGrantStore.ClaimResult.CLAIMED,
                    DungeonRewardGrantStore.claim(connection, "DI-test", retryPlayer, "money:cash", 10));

            assertTrue(DungeonRewardGrantStore.complete(
                    connection, "DI-test", grantedPlayer, "money:cash", 20));
            assertTrue(DungeonRewardGrantStore.fail(
                    connection, "DI-test", retryPlayer, "money:cash", "Vault unavailable", 20));

            assertEquals(DungeonRewardGrantStore.ClaimResult.ALREADY_GRANTED,
                    DungeonRewardGrantStore.claim(connection, "DI-test", grantedPlayer, "money:cash", 30));
            assertEquals(DungeonRewardGrantStore.ClaimResult.CLAIMED,
                    DungeonRewardGrantStore.claim(connection, "DI-test", retryPlayer, "money:cash", 30));
            assertTrue(DungeonRewardGrantStore.complete(
                    connection, "DI-test", retryPlayer, "money:cash", 40));

            DungeonRewardGrantStore.Grant granted = DungeonRewardGrantStore.find(
                    connection, "DI-test", grantedPlayer, "money:cash").orElseThrow();
            DungeonRewardGrantStore.Grant retried = DungeonRewardGrantStore.find(
                    connection, "DI-test", retryPlayer, "money:cash").orElseThrow();
            assertEquals(DungeonRewardGrantStore.STATUS_GRANTED, granted.status());
            assertEquals(DungeonRewardGrantStore.STATUS_GRANTED, retried.status());
            assertEquals(1, granted.attemptCount());
            assertEquals(2, retried.attemptCount());
            assertTrue(DungeonRewardGrantStore.listClaimable(connection, "DI-test").isEmpty());
            assertEquals(2, DungeonRewardGrantStore.listForInstance(connection, "DI-test").size());
            assertTrue(DungeonRewardGrantStore.allGranted(connection, "DI-test"));
        }
    }

    @Test
    void pendingGrantRemainsExclusiveUntilCompletedOrExplicitlyFailed() throws Exception {
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();

            assertEquals(DungeonRewardGrantStore.ClaimResult.CLAIMED,
                    DungeonRewardGrantStore.claim(connection, "DI-test", player, "item:cache", 10));
            assertEquals(DungeonRewardGrantStore.ClaimResult.IN_PROGRESS,
                    DungeonRewardGrantStore.claim(connection, "DI-test", player, "item:cache", 20));
            assertTrue(DungeonRewardGrantStore.listClaimable(connection, "DI-test").isEmpty());

            assertTrue(DungeonRewardGrantStore.markReviewRequired(
                    connection, "DI-test", player, "item:cache", "outcome unknown", 25));
            DungeonRewardGrantStore.Grant review = DungeonRewardGrantStore.find(
                    connection, "DI-test", player, "item:cache").orElseThrow();
            assertEquals(DungeonRewardGrantStore.STATUS_PENDING, review.status());
            assertEquals("outcome unknown", review.lastError());

            assertTrue(DungeonRewardGrantStore.fail(
                    connection, "DI-test", player, "item:cache", "delivery rejected", 30));
            assertEquals(1, DungeonRewardGrantStore.listClaimable(connection, "DI-test").size());
            assertFalse(DungeonRewardGrantStore.allGranted(connection, "DI-test"));
            assertFalse(DungeonRewardGrantStore.complete(
                    connection, "DI-test", player, "item:cache", 40));
        }
    }

    @Test
    void mutationsParticipateInCallerTransaction() throws Exception {
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();
            connection.setAutoCommit(false);
            DungeonRewardGrantStore.claim(connection, "DI-test", player, "proof:clear", 10);
            connection.rollback();
            connection.setAutoCommit(true);

            assertTrue(DungeonRewardGrantStore.find(
                    connection, "DI-test", player, "proof:clear").isEmpty());
        }
    }

    @Test
    void offlineDeliveryIsRearmedWhenPlayerReturns() throws Exception {
        try (Connection connection = database()) {
            UUID player = UUID.randomUUID();
            DungeonRewardGrantStore.claim(connection, "DI-test", player, "command:proof", 10);
            DungeonRewardGrantStore.fail(connection, "DI-test", player, "command:proof",
                    "command:proof proof_target_offline=Alice", 20);
            DungeonRewardGrantStore.claim(connection, "DI-test", player, "command:proof", 30);
            DungeonRewardGrantStore.fail(connection, "DI-test", player, "command:proof",
                    "command:proof proof_target_offline=Alice", 40);

            assertEquals(java.util.List.of("DI-test"),
                    DungeonRewardGrantStore.rearmOfflineDeliveries(connection, player, 50));
            DungeonRewardGrantStore.Grant rearmed = DungeonRewardGrantStore.find(
                    connection, "DI-test", player, "command:proof").orElseThrow();
            assertEquals(DungeonRewardGrantStore.STATUS_RETRY_REQUIRED, rearmed.status());
            assertEquals(0, rearmed.attemptCount());
            assertEquals(DungeonRewardGrantStore.ClaimResult.CLAIMED,
                    DungeonRewardGrantStore.claim(connection, "DI-test", player, "command:proof", 60));
        }
    }

    private static Connection database() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        DungeonRewardGrantStore.initialize(connection);
        return connection;
    }

    private static long count(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM ks_dungeon_reward_grants")) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }

    private static void duplicateExistingRow(Connection connection, UUID playerUuid) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_dungeon_reward_grants " +
                        "(instance_id, player_uuid, reward_key, status, attempt_count, last_error, created_at, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setString(1, "DI-test");
            statement.setString(2, playerUuid.toString());
            statement.setString(3, "money:cash");
            statement.setString(4, DungeonRewardGrantStore.STATUS_NONE);
            statement.setInt(5, 0);
            statement.setString(6, "");
            statement.setLong(7, 20);
            statement.setLong(8, 20);
            statement.executeUpdate();
        }
    }
}
