package org.kscore;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KsDataStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesSupportedTypesAndJdbcUrls() {
        assertEquals(KsDataStore.DatabaseDialect.SQLITE,
                KsDataStore.resolveDialect("sqlite", ""));
        assertEquals(KsDataStore.DatabaseDialect.MYSQL,
                KsDataStore.resolveDialect("auto", "jdbc:mysql://localhost/ks"));
        assertEquals(KsDataStore.DatabaseDialect.MARIADB,
                KsDataStore.resolveDialect("mariadb", "jdbc:mariadb://localhost/ks"));
        assertEquals(KsDataStore.DatabaseDialect.POSTGRESQL,
                KsDataStore.resolveDialect("postgres", "jdbc:postgresql://localhost/ks"));

        assertThrows(IllegalArgumentException.class,
                () -> KsDataStore.resolveDialect("sqlite", "jdbc:mysql://localhost/ks"));
        assertThrows(IllegalArgumentException.class,
                () -> KsDataStore.resolveDialect("oracle", ""));
    }

    @Test
    void announcementSchemaUsesPortableIdentityAndIndexSyntax() {
        assertTrue(AnnouncementManager.createTableSql(KsDataStore.DatabaseDialect.SQLITE)
                .contains("INTEGER PRIMARY KEY AUTOINCREMENT"));
        assertTrue(AnnouncementManager.createTableSql(KsDataStore.DatabaseDialect.MYSQL)
                .contains("BIGINT AUTO_INCREMENT PRIMARY KEY"));
        assertTrue(AnnouncementManager.createTableSql(KsDataStore.DatabaseDialect.POSTGRESQL)
                .contains("BIGSERIAL PRIMARY KEY"));
        assertFalse(AnnouncementManager.createIndexSql(KsDataStore.DatabaseDialect.MYSQL)
                .contains("IF NOT EXISTS"));
        assertTrue(AnnouncementManager.createIndexSql(KsDataStore.DatabaseDialect.POSTGRESQL)
                .contains("IF NOT EXISTS"));
    }

    @Test
    void sqlitePoolInitializesCoreSchemaAndClosesCleanly() throws Exception {
        KsDataStore store = new KsDataStore(plugin(), sqliteConfig(false));
        store.init();

        assertTrue(store.isInitialized());
        assertEquals(KsDataStore.DatabaseDialect.SQLITE, store.dialect());
        assertFalse(store.isSharedDatabase());
        try (Connection connection = store.getConnection()) {
            assertTrue(tableExists(connection, "ks_sessions"));
            assertTrue(tableExists(connection, "ks_registry"));
            assertTrue(tableExists(connection, "ks_kv_store"));
        }

        store.shutdown();
        assertFalse(store.isInitialized());
        assertThrows(IllegalStateException.class, store::getConnection);
    }

    @Test
    void explicitFallbackUsesNodeLocalSqlite() throws Exception {
        KsConfig config = sqliteConfig(true);
        when(config.getDatabaseType()).thenReturn("mysql");
        when(config.getJdbcUrl()).thenReturn("jdbc:mysql://localhost/ks");
        when(config.getJdbcDriver()).thenReturn("missing.test.Driver");

        KsDataStore store = new KsDataStore(plugin(), config);
        store.init();

        assertEquals(KsDataStore.DatabaseDialect.SQLITE, store.dialect());
        assertFalse(store.isSharedDatabase());
        try (Connection connection = store.getConnection()) {
            assertTrue(tableExists(connection, "ks_sessions"));
        }
        store.shutdown();
    }

    @Test
    void remoteFailureIsFatalWithoutExplicitFallback() {
        KsConfig config = sqliteConfig(false);
        when(config.getDatabaseType()).thenReturn("mysql");
        when(config.getJdbcUrl()).thenReturn("jdbc:mysql://localhost/ks");
        when(config.getJdbcDriver()).thenReturn("missing.test.Driver");

        KsDataStore store = new KsDataStore(plugin(), config);
        IllegalStateException failure = assertThrows(IllegalStateException.class, store::init);

        assertTrue(failure.getMessage().contains("dialect=mysql"));
        assertFalse(store.isInitialized());
    }

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("KsDataStoreTest"));
        return plugin;
    }

    private KsConfig sqliteConfig(boolean fallback) {
        KsConfig config = mock(KsConfig.class);
        when(config.getDatabaseType()).thenReturn("sqlite");
        when(config.getSqliteFile()).thenReturn("data.db");
        when(config.getSqliteBusyTimeoutMs()).thenReturn(1000);
        when(config.getJdbcUrl()).thenReturn("");
        when(config.getJdbcDriver()).thenReturn("");
        when(config.getDatabaseUsername()).thenReturn("");
        when(config.getDatabasePassword()).thenReturn("");
        when(config.isFallbackToSqlite()).thenReturn(fallback);
        when(config.getPoolMaximumSize()).thenReturn(2);
        when(config.getPoolMinimumIdle()).thenReturn(1);
        when(config.getPoolConnectionTimeoutMs()).thenReturn(1000L);
        when(config.getPoolValidationTimeoutMs()).thenReturn(500L);
        when(config.getPoolIdleTimeoutMs()).thenReturn(10000L);
        when(config.getPoolMaxLifetimeMs()).thenReturn(30000L);
        when(config.getPoolLeakDetectionThresholdMs()).thenReturn(0L);
        return config;
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
