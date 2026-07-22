package org.kseries.rpg;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonRuntimeConfigTest {
    @TempDir
    Path dataDirectory;

    @Test
    void missingSeasonSectionIsDisabledWithBoundedDefaults() {
        SeasonRuntimeConfig config = SeasonRuntimeConfig.load(new YamlConfiguration(), dataDirectory);

        assertFalse(config.enabled());
        assertEquals(dataDirectory.resolve("season.db").toAbsolutePath().normalize(), config.databasePath());
        assertEquals(5_000, config.busyTimeoutMillis());
        assertEquals(1_000, config.rules().weeklyReputationCap());
        assertEquals(3_000, config.rules().catchupCap());
        assertEquals(600, config.rules().lateJoinCatchupPerWeek());
    }

    @Test
    void explicitEnableLoadsRulesWithoutStartingContent() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("season.enabled", true);
        yaml.set("season.storage.sqlite-file", "state/season.db");
        yaml.set("season.storage.busy-timeout-ms", 2_500);
        yaml.set("season.reputation.weekly-cap", 800);
        yaml.set("season.reputation.catchup-cap", 2_400);
        yaml.set("season.reputation.late-join-catchup-per-week", 400);

        SeasonRuntimeConfig config = SeasonRuntimeConfig.load(yaml, dataDirectory);

        assertTrue(config.enabled());
        assertEquals(dataDirectory.resolve("state/season.db").toAbsolutePath().normalize(), config.databasePath());
        assertEquals(800, config.rules().weeklyReputationCap());
    }

    @Test
    void storagePathCannotEscapePluginDirectory() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("season.storage.sqlite-file", "../outside.db");

        assertThrows(IllegalArgumentException.class,
                () -> SeasonRuntimeConfig.load(yaml, dataDirectory));
    }
}
