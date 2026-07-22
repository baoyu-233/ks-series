package org.kscore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Typed view of ks-core's configuration.
 */
public final class KsConfig {

    private final JavaPlugin plugin;

    private boolean webEnabled;
    private int port;
    private String bindAddress;
    private String publicAddress;
    private int tokenTimeout;
    private int maxThreads;
    private boolean allowTestToken;

    private String dbType;
    private String sqliteFile;
    private int sqliteBusyTimeoutMs;
    private String jdbcUrl;
    private String jdbcDriver;
    private String databaseUsername;
    private String databasePassword;
    private boolean fallbackToSqlite;
    private int poolMaximumSize;
    private int poolMinimumIdle;
    private long poolConnectionTimeoutMs;
    private long poolValidationTimeoutMs;
    private long poolIdleTimeoutMs;
    private long poolMaxLifetimeMs;
    private long poolLeakDetectionThresholdMs;

    // Legacy MySQL settings remain supported when database.jdbc-url is empty.
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private boolean mysqlUseSsl;
    private int mysqlPoolSize;

    public KsConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        var web = cfg.getConfigurationSection("web-gateway");
        if (web != null) {
            webEnabled = web.getBoolean("enabled", true);
            port = web.getInt("port", 8123);
            bindAddress = web.getString("bind-address", "0.0.0.0");
            publicAddress = web.getString("public-address", "");
            tokenTimeout = web.getInt("token-timeout-seconds", 600);
            maxThreads = web.getInt("max-threads", 8);
            allowTestToken = web.getBoolean("allow-test-token", false);
        } else {
            webEnabled = true;
            port = 8123;
            bindAddress = "0.0.0.0";
            publicAddress = "";
            tokenTimeout = 600;
            maxThreads = 8;
            allowTestToken = false;
        }

        if (publicAddress == null || publicAddress.isEmpty()) {
            publicAddress = bindAddress.equals("0.0.0.0") ? "127.0.0.1" : bindAddress;
        }

        loadDatabase(cfg.getConfigurationSection("database"));
    }

    private void loadDatabase(ConfigurationSection db) {
        dbType = value(db, "type", "sqlite");
        jdbcUrl = value(db, "jdbc-url", value(db, "url", ""));
        jdbcDriver = value(db, "driver-class-name", value(db, "driver", ""));
        databaseUsername = value(db, "username", value(db, "user", ""));
        String configuredPassword = value(db, "password", "");
        fallbackToSqlite = db != null && db.getBoolean("fallback-to-sqlite", false);

        ConfigurationSection sqlite = db == null ? null : db.getConfigurationSection("sqlite");
        sqliteFile = value(sqlite, "file", "data.db");
        sqliteBusyTimeoutMs = boundedInt(sqlite, "busy-timeout-ms", 5000, 0, Integer.MAX_VALUE);

        ConfigurationSection mysql = db == null ? null : db.getConfigurationSection("mysql");
        mysqlHost = value(mysql, "host", "localhost");
        mysqlPort = boundedInt(mysql, "port", 3306, 1, 65535);
        mysqlDatabase = value(mysql, "database", "ks_core");
        mysqlUsername = value(mysql, "username", "");
        mysqlPassword = value(mysql, "password", "");
        mysqlUseSsl = mysql != null && mysql.getBoolean("use-ssl", false);
        mysqlPoolSize = boundedInt(mysql, "pool-size", 5, 1, 128);

        // Preserve the old mysql.username/password and mysql.pool-size behavior.
        if (databaseUsername.isBlank()) {
            databaseUsername = mysqlUsername;
        }
        databasePassword = resolveDatabasePassword(
                configuredPassword,
                value(db, "password-env", ""),
                value(db, "password-file", ""),
                mysqlPassword);

        ConfigurationSection pool = db == null ? null : db.getConfigurationSection("pool");
        int legacyPoolSize = "sqlite".equalsIgnoreCase(dbType) ? 4 : mysqlPoolSize;
        poolMaximumSize = boundedInt(pool, "maximum-pool-size", legacyPoolSize, 1, 128);
        poolMinimumIdle = boundedInt(pool, "minimum-idle", Math.min(1, poolMaximumSize), 0, poolMaximumSize);
        poolConnectionTimeoutMs = boundedLong(pool, "connection-timeout-ms", 10000L, 250L, Long.MAX_VALUE);
        poolValidationTimeoutMs = boundedLong(pool, "validation-timeout-ms", 5000L, 250L, poolConnectionTimeoutMs);
        poolIdleTimeoutMs = boundedLong(pool, "idle-timeout-ms", 600000L, 0L, Long.MAX_VALUE);
        poolMaxLifetimeMs = boundedLong(pool, "max-lifetime-ms", 1800000L, 0L, Long.MAX_VALUE);
        poolLeakDetectionThresholdMs = boundedLong(pool, "leak-detection-threshold-ms", 0L, 0L, Long.MAX_VALUE);
    }

    private String resolveDatabasePassword(String configuredPassword,
                                           String environmentVariable,
                                           String passwordFile,
                                           String legacyPassword) {
        if (!configuredPassword.isEmpty()) {
            return configuredPassword;
        }
        if (!environmentVariable.isBlank()) {
            String value = System.getenv(environmentVariable);
            if (value == null || value.isEmpty()) {
                throw new IllegalStateException(
                        "Configured database password environment variable is missing or blank: "
                                + environmentVariable);
            }
            return value;
        }
        if (!passwordFile.isBlank()) {
            final Path configuredPath;
            try {
                Path raw = Path.of(passwordFile);
                configuredPath = (raw.isAbsolute() ? raw : plugin.getDataFolder().toPath().resolve(raw))
                        .normalize();
            } catch (InvalidPathException ex) {
                throw new IllegalStateException("Configured database password file path is invalid", ex);
            }
            try {
                String value = Files.readString(configuredPath, StandardCharsets.UTF_8);
                if (value.startsWith("\uFEFF")) {
                    value = value.substring(1);
                }
                value = value.replaceFirst("[\\r\\n]+\\z", "");
                if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                    throw new IllegalStateException(
                            "Configured database password file must contain exactly one non-empty line");
                }
                return value;
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to read configured database password file", ex);
            }
        }
        return legacyPassword;
    }

    private static String value(ConfigurationSection section, String path, String fallback) {
        if (section == null) {
            return fallback;
        }
        String value = section.getString(path, fallback);
        return value == null ? fallback : value.trim();
    }

    private static int boundedInt(ConfigurationSection section, String path, int fallback, int minimum, int maximum) {
        int value = section == null ? fallback : section.getInt(path, fallback);
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long boundedLong(ConfigurationSection section, String path, long fallback, long minimum, long maximum) {
        long value = section == null ? fallback : section.getLong(path, fallback);
        return Math.max(minimum, Math.min(maximum, value));
    }

    public boolean isWebEnabled() { return webEnabled; }
    public int getPort() { return port; }
    public String getBindAddress() { return bindAddress; }
    public String getPublicAddress() { return publicAddress; }
    public int getTokenTimeout() { return tokenTimeout; }
    public int getMaxThreads() { return maxThreads; }
    public boolean isTestTokenAllowed() { return allowTestToken; }

    public String getDatabaseType() { return dbType; }
    public String getSqliteFile() { return sqliteFile; }
    public int getSqliteBusyTimeoutMs() { return sqliteBusyTimeoutMs; }
    public String getJdbcUrl() { return jdbcUrl; }
    public String getJdbcDriver() { return jdbcDriver; }
    public String getDatabaseUsername() { return databaseUsername; }
    public String getDatabasePassword() { return databasePassword; }
    public boolean isFallbackToSqlite() { return fallbackToSqlite; }
    public int getPoolMaximumSize() { return poolMaximumSize; }
    public int getPoolMinimumIdle() { return poolMinimumIdle; }
    public long getPoolConnectionTimeoutMs() { return poolConnectionTimeoutMs; }
    public long getPoolValidationTimeoutMs() { return poolValidationTimeoutMs; }
    public long getPoolIdleTimeoutMs() { return poolIdleTimeoutMs; }
    public long getPoolMaxLifetimeMs() { return poolMaxLifetimeMs; }
    public long getPoolLeakDetectionThresholdMs() { return poolLeakDetectionThresholdMs; }

    public String getMysqlHost() { return mysqlHost; }
    public int getMysqlPort() { return mysqlPort; }
    public String getMysqlDatabase() { return mysqlDatabase; }
    public String getMysqlUsername() { return mysqlUsername; }
    public String getMysqlPassword() { return mysqlPassword; }
    public boolean isMysqlUseSsl() { return mysqlUseSsl; }
    public int getMysqlPoolSize() { return mysqlPoolSize; }

    public boolean isSubPluginEnabled(String pluginId) {
        var sp = plugin.getConfig().getConfigurationSection("sub-plugins." + pluginId);
        return sp == null || sp.getBoolean("enabled", true);
    }

    public String getSubPluginRoute(String pluginId) {
        var sp = plugin.getConfig().getConfigurationSection("sub-plugins." + pluginId);
        if (sp == null) return "/" + pluginId.toUpperCase();
        return sp.getString("route", "/" + pluginId.toUpperCase());
    }
}
