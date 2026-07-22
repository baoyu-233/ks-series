package org.kseries.instanceworld.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kseries.instanceworld.api.InstanceBounds;
import org.kseries.instanceworld.api.InstanceGridSpec;
import org.kseries.instanceworld.api.InstancePoint;
import org.kseries.instanceworld.api.InstancePrepareRequest;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceStoreTest {
    @TempDir
    Path temp;

    @Test
    void reusesReleasedGridAndRepeatedReleaseIsIdempotent() throws Exception {
        InstanceStore store = new InstanceStore(temp.resolve("instance.db"), Logger.getAnonymousLogger());
        store.initialize();
        InstancePrepareRequest request = request(1);

        InstanceStore.GridLease first = store.allocate("instance-a", "session", request);
        assertThrows(IllegalStateException.class, () -> store.allocate("instance-b", "session", request));

        InstanceBounds bounds = new InstanceBounds(0, 60, 0, 10, 80, 10);
        InstancePoint point = new InstancePoint(5.5, 65, 5.5, 0, 0);
        store.markReady("instance-a", bounds, point, point);
        store.finishRelease("instance-a", false, "");
        store.finishRelease("instance-a", false, "");

        InstanceStore.GridLease reused = store.allocate("instance-c", "session", request);
        assertEquals(first.gridId(), reused.gridId());
    }


    @Test
    void delayedReleaseDoesNotFreeGridReusedByAnotherInstance() throws Exception {
        InstanceStore store = new InstanceStore(temp.resolve("delayed-release.db"), Logger.getAnonymousLogger());
        store.initialize();
        InstancePrepareRequest request = request(1);

        InstanceStore.GridLease first = store.allocate("instance-a", "session", request);
        InstanceBounds bounds = new InstanceBounds(0, 60, 0, 10, 80, 10);
        InstancePoint point = new InstancePoint(5.5, 65, 5.5, 0, 0);
        store.markReady("instance-a", bounds, point, point);
        store.finishRelease("instance-a", false, "");

        InstanceStore.GridLease second = store.allocate("instance-b", "session", request);
        assertEquals(first.gridId(), second.gridId());
        store.markReady("instance-b", bounds, point, point);

        // A delayed finishRelease for the already-released instance must not free B's live grid.
        store.finishRelease("instance-a", false, "late");
        assertEquals("OCCUPIED", store.loadGrids().getFirst().status());
        assertThrows(IllegalStateException.class, () -> store.allocate("instance-c", "session", request));
    }

    @Test
    void legacyImportDoesNotModifySourceRows() throws Exception {
        Path source = temp.resolve("legacy.db");
        Class.forName(org.sqlite.JDBC.class.getName());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_dungeon_grids (id TEXT, world TEXT, grid_x INTEGER, grid_z INTEGER, status TEXT, last_used_at INTEGER)");
            statement.execute("INSERT INTO ks_dungeon_grids VALUES ('G-old', 'ks-dungeon-world', 0, 0, 'OCCUPIED', 42)");
        }

        InstanceStore store = new InstanceStore(temp.resolve("target.db"), Logger.getAnonymousLogger());
        store.initialize();
        assertEquals(1, store.importLegacyGrids(source, "ks_dungeon_grids"));
        assertEquals("FREE", store.loadGrids().getFirst().status());
        assertEquals(0, store.importLegacyGrids(source, "ks_dungeon_grids"));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT status FROM ks_dungeon_grids WHERE id='G-old'")) {
            rows.next();
            assertEquals("OCCUPIED", rows.getString(1));
        }
    }

    @Test
    void legacyImportSkipsExistingDatabaseWithoutLegacyTable() throws Exception {
        Path source = temp.resolve("core-without-legacy-table.db");
        Class.forName(org.sqlite.JDBC.class.getName());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)");
        }

        InstanceStore store = new InstanceStore(temp.resolve("target-without-legacy.db"), Logger.getAnonymousLogger());
        store.initialize();
        assertEquals(0, store.importLegacyGrids(source, "ks_dungeon_grids"));
        assertTrue(store.loadGrids().isEmpty());
    }

    @Test
    void restartRecoveryPreservesReadyInstancesAndFreesOnlyInterruptedOnes() throws Exception {
        InstanceStore store = new InstanceStore(temp.resolve("recovery.db"), Logger.getAnonymousLogger());
        store.initialize();
        InstancePrepareRequest request = request(3);
        InstanceBounds bounds = new InstanceBounds(0, 60, 0, 10, 80, 10);
        InstancePoint point = new InstancePoint(5.5, 65, 5.5, 0, 0);

        store.allocate("ready", "old-session", request);
        store.markReady("ready", bounds, point, point);
        store.allocate("preparing", "old-session", request);

        assertEquals(1, store.recoverInterruptedInstances());
        assertEquals("READY", store.loadInstances().stream()
                .filter(row -> row.instanceId().equals("ready")).findFirst().orElseThrow().state().name());
        assertEquals("FAILED", store.loadInstances().stream()
                .filter(row -> row.instanceId().equals("preparing")).findFirst().orElseThrow().state().name());
        assertEquals(1, store.loadRecoverableInstances().size());
        assertTrue(store.claimReady("ready", "new-session"));
        assertEquals(1, store.loadGrids().stream().filter(grid -> "OCCUPIED".equals(grid.status())).count());
        assertEquals(1, store.loadGrids().stream().filter(grid -> "FREE".equals(grid.status())).count());
    }

    private static InstancePrepareRequest request(int maxGrids) {
        return new InstancePrepareRequest("test", "template", "arena",
                new InstanceGridSpec("ks-dungeon-world", 5000, maxGrids),
                64, 256, Duration.ofSeconds(30));
    }
}
