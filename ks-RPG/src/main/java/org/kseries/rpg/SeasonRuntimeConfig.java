package org.kseries.rpg;

import org.bukkit.configuration.ConfigurationSection;
import org.kseries.rpg.season.SeasonRules;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

record SeasonRuntimeConfig(
        boolean enabled,
        Path databasePath,
        int busyTimeoutMillis,
        SeasonRules rules
) {
    static SeasonRuntimeConfig load(ConfigurationSection config, Path dataDirectory) {
        boolean enabled = config.getBoolean("season.enabled", false);
        Path root = dataDirectory.toAbsolutePath().normalize();
        String configuredFile = config.getString("season.storage.sqlite-file", "season.db");
        if (configuredFile == null || configuredFile.isBlank()) {
            throw new IllegalArgumentException("season.storage.sqlite-file must not be blank");
        }
        final Path relative;
        try {
            relative = Path.of(configuredFile.trim());
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("season.storage.sqlite-file is invalid", invalid);
        }
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("season.storage.sqlite-file must be relative to the plugin folder");
        }
        Path database = root.resolve(relative).normalize();
        if (!database.startsWith(root)) {
            throw new IllegalArgumentException("season.storage.sqlite-file escapes the plugin folder");
        }

        int busyTimeout = config.getInt("season.storage.busy-timeout-ms", 5_000);
        if (busyTimeout < 100 || busyTimeout > 60_000) {
            throw new IllegalArgumentException("season.storage.busy-timeout-ms must be between 100 and 60000");
        }
        SeasonRules rules = new SeasonRules(
                config.getInt("season.reputation.weekly-cap", 1_000),
                config.getInt("season.reputation.catchup-cap", 3_000),
                config.getInt("season.reputation.late-join-catchup-per-week", 600));
        return new SeasonRuntimeConfig(enabled, database, busyTimeout, rules);
    }
}
