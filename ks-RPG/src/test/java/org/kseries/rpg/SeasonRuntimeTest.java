package org.kseries.rpg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kseries.rpg.api.RpgSeasonStatusApi;
import org.kseries.rpg.season.SeasonRules;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonRuntimeTest {
    @TempDir
    Path dataDirectory;

    @Test
    void disabledRuntimeDoesNotInitializeStorage() {
        Path database = dataDirectory.resolve("season.db");
        try (SeasonRuntime runtime = new SeasonRuntime(Logger.getAnonymousLogger())) {
            runtime.reload(new SeasonRuntimeConfig(false, database, 1_000, SeasonRules.defaults()));

            assertEquals(RpgSeasonStatusApi.RuntimeState.DISABLED, runtime.status().state());
            assertFalse(runtime.service().enabled());
            assertFalse(java.nio.file.Files.exists(database));
        }
    }

    @Test
    void enabledRuntimeInitializesOnlyEmptySeasonStorageOffThread() throws Exception {
        Path database = dataDirectory.resolve("season.db");
        try (SeasonRuntime runtime = new SeasonRuntime(Logger.getAnonymousLogger())) {
            runtime.reload(new SeasonRuntimeConfig(true, database, 1_000, SeasonRules.defaults()));
            awaitState(runtime, RpgSeasonStatusApi.RuntimeState.READY, Duration.ofSeconds(5));

            assertTrue(runtime.service().enabled());
            assertTrue(java.nio.file.Files.isRegularFile(database));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM ks_rpg_region_reputation")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            assertTrue(runtime.service().season("unconfigured").isEmpty());
        }
    }

    private void awaitState(SeasonRuntime runtime, RpgSeasonStatusApi.RuntimeState expected,
                            Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (runtime.status().state() == expected) return;
            if (runtime.status().state() == RpgSeasonStatusApi.RuntimeState.FAILED) {
                throw new AssertionError(runtime.status().detail());
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for season runtime: " + runtime.status());
    }
}
