package org.kseco.extra.realestatedungeon;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonLifecycleStoreTest {

    @Test
    void completionMarksRewardsPendingAndRetryKeepsRewardRoster() throws Exception {
        try (Connection connection = database()) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            insertFixture(connection, "ACTIVE", DungeonLifecycleStore.REWARD_NONE, first, second);

            DungeonLifecycleStore.EndPlan firstPlan = DungeonLifecycleStore.endInstance(
                    connection, "DI-test", DungeonInstanceManager.STATUS_COMPLETED).orElseThrow();

            assertTrue(firstPlan.transitioned());
            assertTrue(firstPlan.rewardsPending());
            assertEquals("IWI-test", firstPlan.worldInstanceId());
            assertEquals("{\"money\":100}", firstPlan.rewardConfig());
            assertEquals(Set.of(first, second), Set.copyOf(firstPlan.rewardParticipants()));
            assertEquals(2, firstPlan.remainingParticipants().size());
            assertEquals(1, firstPlan.plotsDeleted());
            assertEquals("COMPLETED", scalar(connection,
                    "SELECT status FROM ks_dungeon_instances WHERE id='DI-test'"));
            assertEquals(DungeonLifecycleStore.REWARD_PENDING, scalar(connection,
                    "SELECT reward_status FROM ks_dungeon_instances WHERE id='DI-test'"));
            assertEquals(0L, number(connection,
                    "SELECT COUNT(*) FROM ks_dungeon_participants WHERE status<>'LEFT'"));

            DungeonLifecycleStore.EndPlan retryPlan = DungeonLifecycleStore.endInstance(
                    connection, "DI-test", DungeonInstanceManager.STATUS_COMPLETED).orElseThrow();

            assertFalse(retryPlan.transitioned());
            assertTrue(retryPlan.rewardsPending());
            assertEquals("IWI-test", retryPlan.worldInstanceId());
            assertEquals("{\"money\":100}", retryPlan.rewardConfig());
            assertEquals(Set.of(first, second), Set.copyOf(retryPlan.rewardParticipants()));

            assertTrue(DungeonLifecycleStore.markRewardsGranted(connection, "DI-test"));
            DungeonLifecycleStore.EndPlan donePlan = DungeonLifecycleStore.endInstance(
                    connection, "DI-test", DungeonInstanceManager.STATUS_COMPLETED).orElseThrow();
            assertFalse(donePlan.rewardsPending());
            assertTrue(donePlan.rewardParticipants().isEmpty());
            assertEquals(DungeonLifecycleStore.REWARD_GRANTED, scalar(connection,
                    "SELECT reward_status FROM ks_dungeon_instances WHERE id='DI-test'"));
        }
    }

    @Test
    void requiredCleanupFailureRollsBackTerminalState() throws Exception {
        try (Connection connection = database()) {
            insertFixture(connection, "ACTIVE", DungeonLifecycleStore.REWARD_NONE,
                    UUID.randomUUID(), UUID.randomUUID());
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER fail_participant_cleanup
                        BEFORE UPDATE ON ks_dungeon_participants
                        BEGIN
                            SELECT RAISE(ABORT, 'forced participant cleanup failure');
                        END
                        """);
            }

            assertThrows(SQLException.class, () -> DungeonLifecycleStore.endInstance(
                    connection, "DI-test", DungeonInstanceManager.STATUS_ABANDONED));

            assertEquals("ACTIVE", scalar(connection,
                    "SELECT status FROM ks_dungeon_instances WHERE id='DI-test'"));
            assertEquals(2L, number(connection,
                    "SELECT COUNT(*) FROM ks_dungeon_participants WHERE status<>'LEFT'"));
            assertEquals(1L, number(connection, "SELECT COUNT(*) FROM ks_re_plots"));
        }
    }

    @Test
    void repeatedTerminalCallRepairsLegacyPartialCleanup() throws Exception {
        try (Connection connection = database()) {
            insertFixture(connection, "ABANDONED", DungeonLifecycleStore.REWARD_NONE,
                    UUID.randomUUID(), UUID.randomUUID());

            DungeonLifecycleStore.EndPlan plan = DungeonLifecycleStore.endInstance(
                    connection, "DI-test", DungeonInstanceManager.STATUS_ABANDONED).orElseThrow();

            assertFalse(plan.transitioned());
            assertFalse(plan.rewardsPending());
            assertEquals(2, plan.remainingParticipants().size());
            assertEquals(1, plan.plotsDeleted());
            assertEquals(0L, number(connection,
                    "SELECT COUNT(*) FROM ks_dungeon_participants WHERE status<>'LEFT'"));
        }
    }

    @Test
    void completionRewardsExcludeLeftParticipantsButKeepDeadParticipants() throws Exception {
        try (Connection connection = database()) {
            UUID left = UUID.randomUUID();
            UUID dead = UUID.randomUUID();
            insertFixture(connection, "ACTIVE", DungeonLifecycleStore.REWARD_NONE, left, dead);
            setParticipantStatus(connection, left, "LEFT");
            setParticipantStatus(connection, dead, "DEAD");

            DungeonLifecycleStore.EndPlan plan = DungeonLifecycleStore.endInstance(
                    connection, "DI-test", DungeonInstanceManager.STATUS_COMPLETED).orElseThrow();

            assertEquals(Set.of(dead), Set.copyOf(plan.rewardParticipants()));
            assertEquals(Set.of(dead.toString()), Set.copyOf(plan.remainingParticipants()));
        }
    }

    @Test
    void completedPendingInstanceWithMissingRosterDoesNotFailOpenToGranted() throws Exception {
        try (Connection connection = database()) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            insertFixture(connection, "COMPLETED", DungeonLifecycleStore.REWARD_PENDING, first, second);
            setParticipantStatus(connection, first, "LEFT");
            setParticipantStatus(connection, second, "LEFT");

            DungeonLifecycleStore.EndPlan plan = DungeonLifecycleStore.endInstance(
                    connection, "DI-test", DungeonInstanceManager.STATUS_COMPLETED).orElseThrow();

            assertTrue(plan.rewardsPending());
            assertTrue(plan.rewardParticipants().isEmpty());
            assertEquals(DungeonLifecycleStore.REWARD_PENDING, scalar(connection,
                    "SELECT reward_status FROM ks_dungeon_instances WHERE id='DI-test'"));
        }
    }

    private static Connection database() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ks_dungeon_templates (
                        id TEXT PRIMARY KEY,
                        reward_config TEXT DEFAULT ''
                    )
                    """);
            statement.execute("""
                    CREATE TABLE ks_dungeon_instances (
                        id TEXT PRIMARY KEY,
                        template_id TEXT NOT NULL,
                        grid_id TEXT NOT NULL,
                        instance_world_id TEXT DEFAULT '',
                        status TEXT NOT NULL,
                        reward_status TEXT DEFAULT 'NONE'
                    )
                    """);
            statement.execute("""
                    CREATE TABLE ks_dungeon_participants (
                        instance_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        status TEXT NOT NULL,
                        PRIMARY KEY(instance_id, player_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE ks_dungeon_reward_roster (
                        instance_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        PRIMARY KEY(instance_id, player_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE ks_re_plots (
                        id TEXT PRIMARY KEY,
                        instance_id TEXT
                    )
                    """);
        }
        return connection;
    }

    private static void insertFixture(Connection connection, String status, String rewardStatus,
                                      UUID first, UUID second) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_dungeon_templates (id, reward_config) VALUES ('template', ?)")) {
            statement.setString(1, "{\"money\":100}");
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_dungeon_instances " +
                        "(id, template_id, grid_id, instance_world_id, status, reward_status) VALUES (?,?,?,?,?,?)")) {
            statement.setString(1, "DI-test");
            statement.setString(2, "template");
            statement.setString(3, "legacy-grid");
            statement.setString(4, "IWI-test");
            statement.setString(5, status);
            statement.setString(6, rewardStatus);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_dungeon_participants (instance_id, player_uuid, status) VALUES (?,?,?)")) {
            for (UUID player : new UUID[]{first, second}) {
                statement.setString(1, "DI-test");
                statement.setString(2, player.toString());
                statement.setString(3, "ALIVE");
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_re_plots (id, instance_id) VALUES ('plot', 'DI-test')")) {
            statement.executeUpdate();
        }
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

    private static void setParticipantStatus(Connection connection, UUID playerUuid,
                                             String status) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE ks_dungeon_participants SET status=? WHERE player_uuid=?")) {
            statement.setString(1, status);
            statement.setString(2, playerUuid.toString());
            statement.executeUpdate();
        }
    }
}
