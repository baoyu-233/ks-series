package org.kscore;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;

/**
 * Shared JDBC connection owner for ks-core and its consumers.
 *
 * <p>The public {@link #getConnection()} contract is intentionally unchanged:
 * callers own the returned connection and must close it with try-with-resources.
 * Closing a connection returns it to the internal pool.</p>
 */
public final class KsDataStore {

    private final JavaPlugin plugin;
    private final KsConfig config;

    private volatile HikariDataSource dataSource;
    private volatile DatabaseDialect effectiveDialect;

    public KsDataStore(JavaPlugin plugin, KsConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Starts the pool, verifies a connection, and initializes only ks-core's own tables.
     * Child plugins remain responsible for their schemas.
     *
     * @throws IllegalStateException when the configured database cannot be initialized
     */
    public synchronized void init() {
        if (dataSource != null && !dataSource.isClosed()) {
            return;
        }

        DatabaseDialect requested = resolveDialect(config.getDatabaseType(), config.getJdbcUrl());
        try {
            installDataSource(requested, false);
        } catch (RuntimeException primaryFailure) {
            closeDataSource();
            if (requested == DatabaseDialect.SQLITE || !config.isFallbackToSqlite()) {
                throw initializationFailure(requested, primaryFailure);
            }

            plugin.getLogger().log(Level.SEVERE,
                    "Configured " + requested + " database is unavailable. Cross-server storage is offline.",
                    primaryFailure);
            plugin.getLogger().warning(
                    "database.fallback-to-sqlite is enabled; using node-local SQLite. " +
                    "This node is NOT sharing data with other servers.");
            try {
                installDataSource(DatabaseDialect.SQLITE, true);
            } catch (RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                closeDataSource();
                throw initializationFailure(DatabaseDialect.SQLITE, fallbackFailure);
            }
        }
    }

    private void installDataSource(DatabaseDialect dialect, boolean fallback) {
        ConnectionSpec spec = connectionSpec(dialect, fallback);
        loadDriver(spec.driverClassName());

        HikariDataSource candidate = null;
        try {
            candidate = new HikariDataSource(hikariConfig(spec));
            verifyConnection(candidate, dialect);
            createCoreSchema(candidate, dialect);
            dataSource = candidate;
            effectiveDialect = dialect;
            candidate = null;
            plugin.getLogger().info("Database ready: dialect=" + dialect.configName()
                    + ", pool=" + config.getPoolMaximumSize());
        } finally {
            if (candidate != null) {
                candidate.close();
            }
        }
    }

    private ConnectionSpec connectionSpec(DatabaseDialect dialect, boolean fallback) {
        if (fallback || dialect == DatabaseDialect.SQLITE) {
            String configuredUrl = fallback ? "" : config.getJdbcUrl();
            String url = configuredUrl.isBlank() ? sqliteUrl() : configuredUrl;
            String driver = fallback || config.getJdbcDriver().isBlank()
                    ? DatabaseDialect.SQLITE.defaultDriverClassName()
                    : config.getJdbcDriver();
            return new ConnectionSpec(url, driver, "", "", sqliteProperties());
        }

        String url = config.getJdbcUrl();
        if (url.isBlank()) {
            url = legacyRemoteUrl(dialect);
        }
        String driver = config.getJdbcDriver().isBlank()
                ? dialect.defaultDriverClassName()
                : config.getJdbcDriver();
        return new ConnectionSpec(url, driver, config.getDatabaseUsername(),
                config.getDatabasePassword(), new Properties());
    }

    private String sqliteUrl() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs() && !dataFolder.isDirectory()) {
            throw new IllegalStateException("Cannot create ks-core data directory: " + dataFolder);
        }
        File dbFile = new File(dataFolder, config.getSqliteFile());
        File parent = dbFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Cannot create SQLite database directory: " + parent);
        }
        return "jdbc:sqlite:" + dbFile.getAbsolutePath();
    }

    private Properties sqliteProperties() {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setBusyTimeout(config.getSqliteBusyTimeoutMs());
        return sqlite.toProperties();
    }

    private String legacyRemoteUrl(DatabaseDialect dialect) {
        String host = config.getMysqlHost();
        int port = config.getMysqlPort();
        String database = config.getMysqlDatabase();
        return switch (dialect) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + config.isMysqlUseSsl()
                    + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + database
                    + "?useSsl=" + config.isMysqlUseSsl();
            case POSTGRESQL -> throw new IllegalArgumentException(
                    "PostgreSQL requires database.jdbc-url, for example " +
                    "jdbc:postgresql://localhost:5432/ks_core");
            case SQLITE -> throw new IllegalArgumentException("SQLite URL requested through remote URL builder");
        };
    }

    private HikariConfig hikariConfig(ConnectionSpec spec) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("ks-core-" + effectivePoolSuffix(spec.url()));
        hikari.setJdbcUrl(spec.url());
        hikari.setDriverClassName(spec.driverClassName());
        if (!spec.username().isBlank()) {
            hikari.setUsername(spec.username());
            hikari.setPassword(spec.password());
        }
        spec.properties().forEach((key, value) -> hikari.addDataSourceProperty(String.valueOf(key), value));
        hikari.setMaximumPoolSize(config.getPoolMaximumSize());
        hikari.setMinimumIdle(Math.min(config.getPoolMinimumIdle(), config.getPoolMaximumSize()));
        hikari.setConnectionTimeout(config.getPoolConnectionTimeoutMs());
        hikari.setValidationTimeout(Math.min(config.getPoolValidationTimeoutMs(), config.getPoolConnectionTimeoutMs()));
        hikari.setIdleTimeout(normalizeOptionalTimeout(config.getPoolIdleTimeoutMs(), 10000L));
        hikari.setMaxLifetime(normalizeOptionalTimeout(config.getPoolMaxLifetimeMs(), 30000L));
        hikari.setLeakDetectionThreshold(normalizeOptionalTimeout(config.getPoolLeakDetectionThresholdMs(), 2000L));
        hikari.setInitializationFailTimeout(config.getPoolConnectionTimeoutMs());
        hikari.setAutoCommit(true);
        return hikari;
    }

    private static String effectivePoolSuffix(String jdbcUrl) {
        DatabaseDialect dialect = DatabaseDialect.fromJdbcUrl(jdbcUrl);
        return dialect == null ? "jdbc" : dialect.configName();
    }

    private static long normalizeOptionalTimeout(long value, long minimumEnabledValue) {
        return value == 0L ? 0L : Math.max(value, minimumEnabledValue);
    }

    private void loadDriver(String driverClassName) {
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("JDBC driver is not available: " + driverClassName, e);
        }
    }

    private static void verifyConnection(HikariDataSource source, DatabaseDialect expectedDialect) {
        try (Connection connection = source.getConnection()) {
            if (!connection.isValid(5)) {
                throw new SQLException("JDBC Connection.isValid returned false");
            }
            String product = connection.getMetaData().getDatabaseProductName();
            DatabaseDialect actualDialect = DatabaseDialect.fromProductName(product);
            if (actualDialect != null && actualDialect != expectedDialect
                    && !(expectedDialect == DatabaseDialect.MYSQL && actualDialect == DatabaseDialect.MARIADB)) {
                throw new SQLException("Configured dialect " + expectedDialect.configName()
                        + " does not match database product " + product);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database connection validation failed for "
                    + expectedDialect.configName(), e);
        }
    }

    private static void createCoreSchema(HikariDataSource source, DatabaseDialect dialect) {
        try (Connection connection = source.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS ks_sessions (
                            token VARCHAR(255) PRIMARY KEY,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(64) NOT NULL,
                            is_admin INTEGER DEFAULT 0,
                            created_at BIGINT NOT NULL,
                            server_id VARCHAR(128) DEFAULT 'main'
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS ks_registry (
                            plugin_id VARCHAR(191) PRIMARY KEY,
                            plugin_name VARCHAR(255) NOT NULL,
                            route_path VARCHAR(512) NOT NULL,
                            enabled INTEGER DEFAULT 1,
                            registered_at BIGINT NOT NULL
                        )
                        """);
                String keyColumn = dialect.quoteIdentifier("key");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS ks_kv_store (
                            namespace VARCHAR(191) NOT NULL,
                            %s VARCHAR(191) NOT NULL,
                            value TEXT NOT NULL,
                            updated_at BIGINT NOT NULL,
                            PRIMARY KEY (namespace, %s)
                        )
                        """.formatted(keyColumn, keyColumn));
                connection.commit();
            } catch (SQLException schemaFailure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    schemaFailure.addSuppressed(rollbackFailure);
                }
                throw schemaFailure;
            } finally {
                try {
                    connection.setAutoCommit(oldAutoCommit);
                } catch (SQLException ignored) {
                    // The connection is being closed immediately.
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ks-core schema initialization failed for "
                    + dialect.configName(), e);
        }
    }

    /**
     * Returns a pooled connection. The caller must close it.
     */
    public Connection getConnection() {
        HikariDataSource current = dataSource;
        if (current == null || current.isClosed()) {
            throw new IllegalStateException("ks-core database is not initialized or has been shut down");
        }
        try {
            return current.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot acquire a " + dialect().configName()
                    + " database connection", e);
        }
    }

    /** Returns the effective dialect, including an explicitly configured SQLite fallback. */
    public DatabaseDialect dialect() {
        DatabaseDialect current = effectiveDialect;
        if (current == null) {
            throw new IllegalStateException("ks-core database is not initialized");
        }
        return current;
    }

    /** Remote databases are shareable between servers; SQLite is always node-local. */
    public boolean isSharedDatabase() {
        return dialect().isShared();
    }

    public boolean isInitialized() {
        HikariDataSource current = dataSource;
        return current != null && !current.isClosed();
    }

    public synchronized void shutdown() {
        closeDataSource();
        effectiveDialect = null;
        plugin.getLogger().info("Database pool closed.");
    }

    private void closeDataSource() {
        HikariDataSource current = dataSource;
        dataSource = null;
        if (current != null) {
            current.close();
        }
    }

    private static IllegalStateException initializationFailure(DatabaseDialect dialect, RuntimeException cause) {
        return new IllegalStateException("Failed to initialize ks-core database (dialect="
                + dialect.configName() + "). Check database.jdbc-url, driver, credentials, and reachability.", cause);
    }

    static DatabaseDialect resolveDialect(String configuredType, String jdbcUrl) {
        String normalizedType = configuredType == null ? "" : configuredType.trim().toLowerCase(Locale.ROOT);
        DatabaseDialect fromUrl = DatabaseDialect.fromJdbcUrl(jdbcUrl);
        if (normalizedType.isEmpty() || "auto".equals(normalizedType)) {
            if (fromUrl == null) {
                return DatabaseDialect.SQLITE;
            }
            return fromUrl;
        }

        DatabaseDialect fromType = DatabaseDialect.fromConfigName(normalizedType);
        if (fromType == null) {
            throw new IllegalArgumentException("Unsupported database.type '" + configuredType
                    + "'. Supported values: sqlite, mysql, mariadb, postgresql, auto");
        }
        if (fromUrl != null && fromUrl != fromType) {
            throw new IllegalArgumentException("database.type '" + configuredType
                    + "' does not match database.jdbc-url dialect '" + fromUrl.configName() + "'");
        }
        return fromType;
    }

    private record ConnectionSpec(String url, String driverClassName, String username,
                                  String password, Properties properties) {
    }

    public enum DatabaseDialect {
        SQLITE("sqlite", "org.sqlite.JDBC", false, "\""),
        MYSQL("mysql", "com.mysql.cj.jdbc.Driver", true, "`"),
        MARIADB("mariadb", "org.mariadb.jdbc.Driver", true, "`"),
        POSTGRESQL("postgresql", "org.postgresql.Driver", true, "\"");

        private final String configName;
        private final String defaultDriverClassName;
        private final boolean shared;
        private final String identifierQuote;

        DatabaseDialect(String configName, String defaultDriverClassName,
                        boolean shared, String identifierQuote) {
            this.configName = configName;
            this.defaultDriverClassName = defaultDriverClassName;
            this.shared = shared;
            this.identifierQuote = identifierQuote;
        }

        public String configName() { return configName; }
        public String defaultDriverClassName() { return defaultDriverClassName; }
        public boolean isShared() { return shared; }

        public String quoteIdentifier(String identifier) {
            if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
            }
            return identifierQuote + identifier + identifierQuote;
        }

        static DatabaseDialect fromConfigName(String name) {
            if (name == null) return null;
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "sqlite" -> SQLITE;
                case "mysql" -> MYSQL;
                case "mariadb", "maria" -> MARIADB;
                case "postgresql", "postgres", "pgsql" -> POSTGRESQL;
                default -> null;
            };
        }

        static DatabaseDialect fromJdbcUrl(String url) {
            if (url == null || url.isBlank()) return null;
            String normalized = url.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("jdbc:sqlite:")) return SQLITE;
            if (normalized.startsWith("jdbc:mysql:")) return MYSQL;
            if (normalized.startsWith("jdbc:mariadb:")) return MARIADB;
            if (normalized.startsWith("jdbc:postgresql:")) return POSTGRESQL;
            throw new IllegalArgumentException("Unsupported JDBC URL: expected sqlite, mysql, mariadb, or postgresql");
        }

        static DatabaseDialect fromProductName(String productName) {
            if (productName == null) return null;
            String normalized = productName.toLowerCase(Locale.ROOT);
            if (normalized.contains("sqlite")) return SQLITE;
            if (normalized.contains("mariadb")) return MARIADB;
            if (normalized.contains("mysql")) return MYSQL;
            if (normalized.contains("postgresql")) return POSTGRESQL;
            return null;
        }
    }
}
