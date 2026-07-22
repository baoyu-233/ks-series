package org.kseco.extra.realestatedungeon;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonTicketSettlementStoreTest {

    @Test
    void chargeAndAdmissionCommitAsDurableStates() throws Exception {
        try (Connection connection = database()) {
            var settlement = settlement("DI-admit");
            var participants = participants();

            DungeonTicketSettlementStore.insertChargeReady(connection, settlement);
            assertTrue(DungeonTicketSettlementStore.claimCharge(connection, settlement.instanceId(), 101));
            DungeonTicketSettlementStore.commitCharge(connection, settlement, 500, participants);

            assertEquals(DungeonTicketSettlementStore.CHARGED,
                    DungeonTicketSettlementStore.status(connection, settlement.instanceId()));
            assertEquals("WAITING", scalar(connection,
                    "SELECT status FROM ks_dungeon_instances WHERE id='DI-admit'"));
            assertEquals(2L, number(connection,
                    "SELECT COUNT(*) FROM ks_dungeon_participants WHERE instance_id='DI-admit'"));

            assertTrue(DungeonTicketSettlementStore.activateAdmission(connection, settlement.instanceId(), 102));
            assertEquals(DungeonTicketSettlementStore.ADMITTED,
                    DungeonTicketSettlementStore.status(connection, settlement.instanceId()));
            assertEquals("ACTIVE", scalar(connection,
                    "SELECT status FROM ks_dungeon_instances WHERE id='DI-admit'"));
            assertFalse(DungeonTicketSettlementStore.prepareRefund(
                    connection, settlement.instanceId(), "late failure", 103));
        }
    }

    @Test
    void preparationFailureCreatesOneClaimableRefund() throws Exception {
        try (Connection connection = database()) {
            var settlement = settlement("DI-refund");
            DungeonTicketSettlementStore.insertChargeReady(connection, settlement);
            assertTrue(DungeonTicketSettlementStore.claimCharge(connection, settlement.instanceId(), 101));
            DungeonTicketSettlementStore.commitCharge(connection, settlement, 500, participants());

            assertTrue(DungeonTicketSettlementStore.prepareRefund(
                    connection, settlement.instanceId(), "prepare failed", 102));
            assertTrue(DungeonTicketSettlementStore.prepareRefund(
                    connection, settlement.instanceId(), "duplicate callback", 103));
            assertEquals("ABANDONED", scalar(connection,
                    "SELECT status FROM ks_dungeon_instances WHERE id='DI-refund'"));
            assertEquals(0L, number(connection,
                    "SELECT COUNT(*) FROM ks_dungeon_participants WHERE instance_id='DI-refund' AND status<>'LEFT'"));

            assertTrue(DungeonTicketSettlementStore.claimRefund(connection, settlement.instanceId(), 104));
            assertFalse(DungeonTicketSettlementStore.claimRefund(connection, settlement.instanceId(), 105));
            assertTrue(DungeonTicketSettlementStore.markRefunded(connection, settlement.instanceId(), 106));
            assertEquals(DungeonTicketSettlementStore.REFUNDED,
                    DungeonTicketSettlementStore.status(connection, settlement.instanceId()));
        }
    }

    @Test
    void restartRetriesOnlyOperationsThatNeverStartedExternally() throws Exception {
        try (Connection connection = database()) {
            insertReady(connection, "DI-ready");

            insertReady(connection, "DI-charge-uncertain");
            assertTrue(DungeonTicketSettlementStore.claimCharge(connection, "DI-charge-uncertain", 101));

            insertCharged(connection, "DI-charged");
            insertCharged(connection, "DI-refund-ready");
            assertTrue(DungeonTicketSettlementStore.prepareRefund(
                    connection, "DI-refund-ready", "prepare failed", 102));

            insertCharged(connection, "DI-refund-uncertain");
            assertTrue(DungeonTicketSettlementStore.prepareRefund(
                    connection, "DI-refund-uncertain", "prepare failed", 102));
            assertTrue(DungeonTicketSettlementStore.claimRefund(connection, "DI-refund-uncertain", 103));

            DungeonTicketSettlementStore.Recovery recovery =
                    DungeonTicketSettlementStore.recoverInterrupted(connection, "server-a", 200);

            assertEquals(Set.of("DI-ready"), Set.copyOf(recovery.cancelled()));
            assertEquals(Set.of("DI-charge-uncertain", "DI-refund-uncertain"),
                    Set.copyOf(recovery.reviewRequired()));
            assertEquals(Set.of("DI-charged", "DI-refund-ready"), Set.copyOf(recovery.refundReady()));
            assertEquals(DungeonTicketSettlementStore.CANCELLED,
                    DungeonTicketSettlementStore.status(connection, "DI-ready"));
            assertEquals(DungeonTicketSettlementStore.REVIEW_REQUIRED,
                    DungeonTicketSettlementStore.status(connection, "DI-charge-uncertain"));
            assertEquals(DungeonTicketSettlementStore.REFUND_READY,
                    DungeonTicketSettlementStore.status(connection, "DI-charged"));
            assertEquals("ABANDONED", scalar(connection,
                    "SELECT status FROM ks_dungeon_instances WHERE id='DI-charged'"));
        }
    }

    private static void insertReady(Connection connection, String instanceId) throws SQLException {
        DungeonTicketSettlementStore.insertChargeReady(connection, settlement(instanceId));
    }

    private static void insertCharged(Connection connection, String instanceId) throws SQLException {
        var settlement = settlement(instanceId);
        DungeonTicketSettlementStore.insertChargeReady(connection, settlement);
        assertTrue(DungeonTicketSettlementStore.claimCharge(connection, instanceId, 101));
        DungeonTicketSettlementStore.commitCharge(connection, settlement, 500, participants());
    }

    private static DungeonTicketSettlementStore.Settlement settlement(String instanceId) {
        return new DungeonTicketSettlementStore.Settlement(instanceId, "template", UUID.randomUUID(),
                500.0, "server-a", "runtime-a", 100);
    }

    private static List<DungeonTicketSettlementStore.Participant> participants() {
        return List.of(
                new DungeonTicketSettlementStore.Participant(UUID.randomUUID(), "one"),
                new DungeonTicketSettlementStore.Participant(UUID.randomUUID(), "two"));
    }

    private static Connection database() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ks_dungeon_instances (
                        id TEXT PRIMARY KEY,
                        template_id TEXT NOT NULL,
                        grid_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        started_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        owner_uuid TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE ks_dungeon_participants (
                        instance_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        joined_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        PRIMARY KEY(instance_id,player_uuid)
                    )
                    """);
        }
        DungeonTicketSettlementStore.createSchema(connection);
        return connection;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static long number(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }
}
