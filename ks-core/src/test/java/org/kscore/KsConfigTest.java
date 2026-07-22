package org.kscore;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KsConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void readsRelativePasswordFileWithoutPublishingTheSecretInYaml() throws Exception {
        Files.writeString(tempDir.resolve("database.password"), "file-secret\n");
        YamlConfiguration yaml = databaseConfig("", "", "database.password", "legacy-secret");

        assertEquals("file-secret", new KsConfig(plugin(yaml)).getDatabasePassword());
    }

    @Test
    void explicitPasswordTakesPrecedenceOverExternalSources() {
        YamlConfiguration yaml = databaseConfig(
                "explicit-secret", "MISSING_KS_TEST_ENV", "missing.password", "legacy-secret");

        assertEquals("explicit-secret", new KsConfig(plugin(yaml)).getDatabasePassword());
    }

    @Test
    void configuredMissingPasswordFileFailsClosed() {
        YamlConfiguration yaml = databaseConfig("", "", "missing.password", "legacy-secret");

        assertThrows(IllegalStateException.class, () -> new KsConfig(plugin(yaml)));
    }

    @Test
    void legacyMysqlPasswordRemainsBackwardCompatible() {
        YamlConfiguration yaml = databaseConfig("", "", "", "legacy-secret");

        assertEquals("legacy-secret", new KsConfig(plugin(yaml)).getDatabasePassword());
    }

    private JavaPlugin plugin(YamlConfiguration yaml) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getConfig()).thenReturn(yaml);
        return plugin;
    }

    private static YamlConfiguration databaseConfig(String password,
                                                     String passwordEnv,
                                                     String passwordFile,
                                                     String legacyPassword) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("database.type", "mariadb");
        yaml.set("database.password", password);
        yaml.set("database.password-env", passwordEnv);
        yaml.set("database.password-file", passwordFile);
        yaml.set("database.mysql.password", legacyPassword);
        return yaml;
    }
}
