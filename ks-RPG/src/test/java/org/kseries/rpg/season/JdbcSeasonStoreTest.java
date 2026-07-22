package org.kseries.rpg.season;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSeasonStoreTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDirectory;

    @Test
    void archiveIsIdempotentAndRejectsLaterProgressWrites() {
        String jdbcUrl = "jdbc:sqlite:" + tempDirectory.resolve("season.db").toAbsolutePath();
        JdbcSeasonStore store = new JdbcSeasonStore(() -> open(jdbcUrl));
        store.initialize();
        assertTrue(store.insertSeason(new SeasonRecord("frostline", "Frostline", SeasonState.ACTIVE,
                1, 10_000, "v1", 10, 10)));
        assertTrue(store.insertRegionReputation(new RegionReputation(
                "frostline", PLAYER, "north", 400, 0, 400, 0, 30, 0, 10)));

        SeasonStore.ArchiveResult first = store.archiveSeason("frostline", 20);
        SeasonStore.ArchiveResult repeated = store.archiveSeason("frostline", 21);

        assertTrue(first.applied());
        assertFalse(repeated.applied());
        assertEquals(400, first.entries().getFirst().totalReputation());
        assertEquals(20, repeated.entries().getFirst().archivedAt());
        RegionReputation lateUpdate = new RegionReputation(
                "frostline", PLAYER, "north", 500, 0, 500, 0, 40, 1, 21);
        assertThrows(IllegalStateException.class,
                () -> store.compareAndSetRegionReputation(0, lateUpdate));
        assertEquals(400, store.listArchive("frostline").getFirst().totalReputation());
    }

    @Test
    void concurrentArchiveAndProgressWriteHaveOneConsistentOrder() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + tempDirectory.resolve("season-race.db").toAbsolutePath();
        JdbcSeasonStore store = new JdbcSeasonStore(() -> open(jdbcUrl));
        store.initialize();
        assertTrue(store.insertSeason(new SeasonRecord("frostline", "Frostline", SeasonState.ACTIVE,
                1, 10_000, "v1", 10, 10)));
        RegionReputation progress = new RegionReputation(
                "frostline", PLAYER, "north", 400, 0, 400, 0, 30, 0, 10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> write = executor.submit(() -> {
                start.await();
                try {
                    return store.insertRegionReputation(progress);
                } catch (IllegalStateException archived) {
                    return false;
                }
            });
            Future<SeasonStore.ArchiveResult> archive = executor.submit(() -> {
                start.await();
                return store.archiveSeason("frostline", 20);
            });
            start.countDown();

            boolean writeApplied = write.get(10, TimeUnit.SECONDS);
            SeasonStore.ArchiveResult archived = archive.get(10, TimeUnit.SECONDS);
            long archivedReputation = archived.entries().stream()
                    .mapToLong(SeasonArchiveEntry::totalReputation)
                    .sum();
            assertEquals(writeApplied ? 400 : 0, archivedReputation);
            assertEquals(SeasonState.ARCHIVED, store.findSeason("frostline").orElseThrow().state());
        } finally {
            executor.shutdownNow();
        }
    }

    private Connection open(String jdbcUrl) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }
}
