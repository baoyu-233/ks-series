package org.kseco.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * ks-Eco JDBC boundary shared by local and multi-server deployments.
 *
 * <p>The provider remains owned by ks-core. This class adds dialect discovery, a stable server
 * identity, request-idempotency claims and fenced leases without depending on vendor-specific SQL.
 * Callers must use it from the database worker lane, except for startup/shutdown lifecycle calls.</p>
 */
public final class EcoDatabase {
    private static final Pattern SERVER_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern OPERATION_TYPE_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");
    private static final int MAX_OPERATION_ID_LENGTH = 191;
    private static final int MAX_LEASE_KEY_LENGTH = 191;

    private final Logger logger;
    private final Supplier<Connection> connectionSupplier;
    private final String serverId;
    private final String instanceId = UUID.randomUUID().toString().replace("-", "");
    private final Duration heartbeatInterval;
    private final Duration serverStaleAfter;
    private final long serverStaleAfterNanos;

    private volatile DatabaseDialect dialect = DatabaseDialect.UNKNOWN;
    private volatile String databaseProduct = "unknown";
    private volatile boolean initialized;
    private volatile boolean identityHealthy;
    private volatile boolean identityPermanentlyLost;
    private volatile boolean identityLossLogged;
    private volatile long lastVerifiedHeartbeatNanos;

    public EcoDatabase(Logger logger, Supplier<Connection> connectionSupplier, String serverId,
                       Duration heartbeatInterval, Duration serverStaleAfter) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.connectionSupplier = Objects.requireNonNull(connectionSupplier, "connectionSupplier");
        this.serverId = validateServerId(serverId);
        this.heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
        this.serverStaleAfter = requirePositive(serverStaleAfter, "serverStaleAfter");
        this.serverStaleAfterNanos = requirePositiveNanos(serverStaleAfter, "serverStaleAfter");
        if (serverStaleAfter.minus(heartbeatInterval).compareTo(heartbeatInterval) < 0) {
            throw new IllegalArgumentException("serverStaleAfter must be at least twice heartbeatInterval");
        }
    }

    /** Initializes portable metadata tables and exclusively claims the configured server ID. */
    public synchronized boolean initialize() {
        if (initialized) return identityHealthy();
        try (Connection connection = openConnection()) {
            dialect = DatabaseDialect.detect(connection);
            databaseProduct = connection.getMetaData().getDatabaseProductName() + " "
                    + connection.getMetaData().getDatabaseProductVersion();
            createFoundationTables(connection);
            long now = currentDatabaseTime(connection);
            if (!claimServerIdentity(connection, now)) return false;
            initialized = true;
            lastVerifiedHeartbeatNanos = System.nanoTime();
            identityHealthy = true;
            identityPermanentlyLost = false;
            identityLossLogged = false;
            logger.info("ks-Eco database boundary ready: " + databaseProduct + ", dialect=" + dialect
                    + ", server-id=" + serverId + ", instance=" + instanceId);
            if (!dialect.sharedDatabaseCapable()) {
                logger.warning("Database dialect " + dialect
                        + " is local-only for ks-Eco; use a shared MySQL/MariaDB/PostgreSQL provider for cross-server mode.");
            }
            return true;
        } catch (SQLException | RuntimeException exception) {
            logger.log(Level.SEVERE, "Failed to initialize ks-Eco database boundary", exception);
            return false;
        }
    }

    /** Opens a connection through the ks-core provider. The caller owns the returned connection. */
    public Connection openConnection() throws SQLException {
        Connection connection;
        try {
            connection = connectionSupplier.get();
        } catch (RuntimeException exception) {
            throw new SQLException("ks-core connection provider failed", exception);
        }
        if (connection == null) throw new SQLException("ks-core connection provider returned null");
        return connection;
    }

    public DatabaseDialect dialect() {
        return dialect;
    }

    public String databaseProduct() {
        return databaseProduct;
    }

    public String serverId() {
        return serverId;
    }

    public String instanceId() {
        return instanceId;
    }

    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    public Duration serverStaleAfter() {
        return serverStaleAfter;
    }

    public boolean initialized() {
        return initialized;
    }

    public boolean identityHealthy() {
        return initialized && identityHealthy && heartbeatVerificationFresh();
    }

    /** Returns the database server clock in epoch milliseconds using the supplied connection. */
    public long currentDatabaseTime(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        DatabaseDialect activeDialect = dialect == DatabaseDialect.UNKNOWN
                ? DatabaseDialect.detect(connection) : dialect;
        String sql = switch (activeDialect) {
            case SQLITE -> "SELECT CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)";
            case MYSQL, MARIADB ->
                    "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)";
            case POSTGRESQL ->
                    "SELECT CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)";
            case H2 ->
                    "SELECT DATEDIFF('MILLISECOND', TIMESTAMP '1970-01-01 00:00:00', CURRENT_TIMESTAMP)";
            case UNKNOWN -> "SELECT CURRENT_TIMESTAMP";
        };
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Database clock query returned no row");
            if (activeDialect != DatabaseDialect.UNKNOWN) return result.getLong(1);
            Timestamp timestamp = result.getTimestamp(1);
            if (timestamp == null) throw new SQLException("Database clock query returned null");
            return timestamp.getTime();
        }
    }

    /** Refreshes the server identity claim. Intended for the serialized database worker lane. */
    public boolean heartbeat() {
        if (!initialized || identityPermanentlyLost) return false;
        try (Connection connection = openConnection()) {
            long now = currentDatabaseTime(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ks_eco_servers
                    SET heartbeat_at_ms=?, stopped_at_ms=0
                    WHERE server_id=? AND instance_id=?
                    """)) {
                statement.setLong(1, now);
                statement.setString(2, serverId);
                statement.setString(3, instanceId);
                boolean retained = statement.executeUpdate() == 1;
                if (retained) {
                    lastVerifiedHeartbeatNanos = System.nanoTime();
                    identityHealthy = true;
                    return true;
                }
                identityHealthy = false;
                identityPermanentlyLost = true;
                if (!retained && !identityLossLogged) {
                    identityLossLogged = true;
                    logger.severe("ks-Eco lost its shared database server identity: " + serverId
                            + ". Cross-server mutations must fail closed until restart.");
                }
                return false;
            }
        } catch (SQLException exception) {
            identityHealthy = false;
            logger.log(Level.WARNING, "Failed to heartbeat ks-Eco server identity " + serverId, exception);
            return false;
        }
    }

    /** Creates a globally unique request ID for operation-level idempotency. */
    public String newOperationId(String operationType) {
        String type = validateOperationType(operationType);
        return type.toLowerCase(Locale.ROOT) + ":" + serverId + ":" + UUID.randomUUID();
    }

    /**
     * Atomically claims an operation ID. A duplicate claim never overwrites the original payload
     * or owner, which makes the ID the request-idempotency boundary across servers.
     *
     * <p>This is deliberately not a recoverable external-settlement journal: a crashed PENDING
     * claim is not taken over. Vault, inventory and item-delivery workflows must use the dedicated
     * settlement journal with explicit steps, attempts and recovery ownership.</p>
     */
    public OperationClaim claimOperation(String operationId, String operationType, String payloadHash)
            throws SQLException {
        requireInitialized();
        String id = validateKey("operationId", operationId, MAX_OPERATION_ID_LENGTH);
        String type = validateOperationType(operationType);
        String hash = normalizePayloadHash(payloadHash);
        try (Connection connection = openConnection()) {
            long now = currentDatabaseTime(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ks_eco_operations (
                        operation_id, operation_type, payload_hash, owner_server_id, owner_instance_id,
                        status, created_at_ms, updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, id);
                statement.setString(2, type);
                statement.setString(3, hash);
                statement.setString(4, serverId);
                statement.setString(5, instanceId);
                statement.setString(6, OperationStatus.PENDING.name());
                statement.setLong(7, now);
                statement.setLong(8, now);
                statement.executeUpdate();
                return new OperationClaim(true, true, OperationStatus.PENDING, serverId, instanceId);
            } catch (SQLException exception) {
                if (!isConstraintViolation(exception)) throw exception;
                OperationRow existing = findOperation(connection, id);
                if (existing == null) throw new SQLException("Operation claim collided but row was not found", exception);
                boolean compatible = type.equals(existing.operationType()) && hash.equals(existing.payloadHash());
                return new OperationClaim(false, compatible, existing.status(),
                        existing.ownerServerId(), existing.ownerInstanceId());
            }
        }
    }

    public boolean markOperationCompleted(String operationId) throws SQLException {
        return transitionOperation(operationId, OperationStatus.COMPLETED);
    }

    public boolean markOperationFailed(String operationId) throws SQLException {
        return transitionOperation(operationId, OperationStatus.FAILED);
    }

    /** Attempts to acquire or renew a fenced lease without vendor-specific upsert syntax. */
    public Optional<LeaseHandle> tryAcquireLease(String leaseKey, Duration ttl) throws SQLException {
        requireInitialized();
        String key = validateKey("leaseKey", leaseKey, MAX_LEASE_KEY_LENGTH);
        long ttlMillis = requirePositiveMillis(ttl, "ttl");
        try (Connection connection = openConnection()) {
            long now = currentDatabaseTime(connection);
            long expiresAt = Math.addExact(now, ttlMillis);
            try (PreparedStatement renew = connection.prepareStatement("""
                    UPDATE ks_eco_leases
                    SET expires_at_ms=?, released_at_ms=0, updated_at_ms=?
                    WHERE lease_key=? AND owner_instance_id=? AND released_at_ms=0 AND expires_at_ms>?
                    """)) {
                renew.setLong(1, expiresAt);
                renew.setLong(2, now);
                renew.setString(3, key);
                renew.setString(4, instanceId);
                renew.setLong(5, now);
                if (renew.executeUpdate() == 1) return findOwnedLease(connection, key, expiresAt);
            }

            try (PreparedStatement takeover = connection.prepareStatement("""
                    UPDATE ks_eco_leases
                    SET owner_server_id=?, owner_instance_id=?, fencing_token=fencing_token+1,
                        expires_at_ms=?, released_at_ms=0, updated_at_ms=?
                    WHERE lease_key=? AND expires_at_ms<=?
                    """)) {
                takeover.setString(1, serverId);
                takeover.setString(2, instanceId);
                takeover.setLong(3, expiresAt);
                takeover.setLong(4, now);
                takeover.setString(5, key);
                takeover.setLong(6, now);
                if (takeover.executeUpdate() == 1) return findOwnedLease(connection, key, expiresAt);
            }

            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO ks_eco_leases (
                        lease_key, owner_server_id, owner_instance_id, fencing_token,
                        expires_at_ms, released_at_ms, updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, key);
                insert.setString(2, serverId);
                insert.setString(3, instanceId);
                insert.setLong(4, 1L);
                insert.setLong(5, expiresAt);
                insert.setLong(6, 0L);
                insert.setLong(7, now);
                insert.executeUpdate();
                return Optional.of(new LeaseHandle(key, 1L, expiresAt));
            } catch (SQLException exception) {
                if (isConstraintViolation(exception)) return Optional.empty();
                throw exception;
            }
        }
    }

    public boolean renewLease(LeaseHandle lease, Duration ttl) throws SQLException {
        requireInitialized();
        Objects.requireNonNull(lease, "lease");
        long ttlMillis = requirePositiveMillis(ttl, "ttl");
        try (Connection connection = openConnection()) {
            long now = currentDatabaseTime(connection);
            long expiresAt = Math.addExact(now, ttlMillis);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ks_eco_leases
                    SET expires_at_ms=?, released_at_ms=0, updated_at_ms=?
                    WHERE lease_key=? AND owner_instance_id=? AND fencing_token=?
                      AND released_at_ms=0 AND expires_at_ms>?
                    """)) {
                statement.setLong(1, expiresAt);
                statement.setLong(2, now);
                statement.setString(3, lease.leaseKey());
                statement.setString(4, instanceId);
                statement.setLong(5, lease.fencingToken());
                statement.setLong(6, now);
                return statement.executeUpdate() == 1;
            }
        }
    }

    public boolean releaseLease(LeaseHandle lease) throws SQLException {
        requireInitialized();
        Objects.requireNonNull(lease, "lease");
        try (Connection connection = openConnection()) {
            long now = currentDatabaseTime(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ks_eco_leases
                    SET expires_at_ms=0, released_at_ms=?, updated_at_ms=?, fencing_token=fencing_token+1
                    WHERE lease_key=? AND owner_instance_id=? AND fencing_token=? AND released_at_ms=0
                    """)) {
                statement.setLong(1, now);
                statement.setLong(2, now);
                statement.setString(3, lease.leaseKey());
                statement.setString(4, instanceId);
                statement.setLong(5, lease.fencingToken());
                return statement.executeUpdate() == 1;
            }
        }
    }

    /** Releases only this process identity and leases; durable operation rows remain intact. */
    public synchronized void close() {
        if (!initialized) return;
        try (Connection connection = openConnection()) {
            long now = currentDatabaseTime(connection);
            SQLException releaseFailure = null;
            try {
                releaseOwnedLeases(connection, now);
            } catch (SQLException exception) {
                releaseFailure = exception;
            }
            try {
                stopServerIdentity(connection, now);
            } catch (SQLException exception) {
                if (releaseFailure == null) releaseFailure = exception;
                else releaseFailure.addSuppressed(exception);
            }
            if (releaseFailure != null) throw releaseFailure;
        } catch (SQLException exception) {
            logger.log(Level.WARNING, "Failed to release ks-Eco database identity " + serverId, exception);
        } finally {
            identityHealthy = false;
            identityPermanentlyLost = true;
            lastVerifiedHeartbeatNanos = 0L;
            initialized = false;
        }
    }

    private void releaseOwnedLeases(Connection connection, long now) throws SQLException {
        try (PreparedStatement leases = connection.prepareStatement(
                "UPDATE ks_eco_leases SET expires_at_ms=0, released_at_ms=?, updated_at_ms=?, "
                        + "fencing_token=fencing_token+1 "
                        + "WHERE owner_instance_id=? AND released_at_ms=0")) {
            leases.setLong(1, now);
            leases.setLong(2, now);
            leases.setString(3, instanceId);
            leases.executeUpdate();
        }
    }

    private void stopServerIdentity(Connection connection, long now) throws SQLException {
        try (PreparedStatement server = connection.prepareStatement(
                "UPDATE ks_eco_servers SET heartbeat_at_ms=0, stopped_at_ms=? "
                        + "WHERE server_id=? AND instance_id=?")) {
            server.setLong(1, now);
            server.setString(2, serverId);
            server.setString(3, instanceId);
            server.executeUpdate();
        }
    }

    private void createFoundationTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_servers (
                        server_id VARCHAR(64) PRIMARY KEY,
                        instance_id VARCHAR(64) NOT NULL,
                        started_at_ms BIGINT NOT NULL,
                        heartbeat_at_ms BIGINT NOT NULL,
                        stopped_at_ms BIGINT NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_operations (
                        operation_id VARCHAR(191) PRIMARY KEY,
                        operation_type VARCHAR(64) NOT NULL,
                        payload_hash VARCHAR(128) NOT NULL,
                        owner_server_id VARCHAR(64) NOT NULL,
                        owner_instance_id VARCHAR(64) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        created_at_ms BIGINT NOT NULL,
                        updated_at_ms BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_leases (
                        lease_key VARCHAR(191) PRIMARY KEY,
                        owner_server_id VARCHAR(64) NOT NULL,
                        owner_instance_id VARCHAR(64) NOT NULL,
                        fencing_token BIGINT NOT NULL,
                        expires_at_ms BIGINT NOT NULL,
                        released_at_ms BIGINT NOT NULL DEFAULT 0,
                        updated_at_ms BIGINT NOT NULL
                    )
                    """);
        }
        ensureColumn(connection, "ks_eco_servers", "stopped_at_ms", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn(connection, "ks_eco_leases", "released_at_ms", "BIGINT NOT NULL DEFAULT 0");
    }

    private boolean claimServerIdentity(Connection connection, long now) throws SQLException {
        ServerRegistration existing = findServerRegistration(connection);
        if (existing == null) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO ks_eco_servers (
                        server_id, instance_id, started_at_ms, heartbeat_at_ms, stopped_at_ms
                    ) VALUES (?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, serverId);
                insert.setString(2, instanceId);
                insert.setLong(3, now);
                insert.setLong(4, now);
                insert.setLong(5, 0L);
                insert.executeUpdate();
                return true;
            } catch (SQLException exception) {
                if (!isConstraintViolation(exception)) throw exception;
                existing = findServerRegistration(connection);
            }
        }
        if (existing == null) return false;
        if (instanceId.equals(existing.instanceId())) {
            try (PreparedStatement reactivate = connection.prepareStatement("""
                    UPDATE ks_eco_servers
                    SET started_at_ms=?, heartbeat_at_ms=?, stopped_at_ms=0
                    WHERE server_id=? AND instance_id=? AND heartbeat_at_ms=?
                    """)) {
                reactivate.setLong(1, now);
                reactivate.setLong(2, now);
                reactivate.setString(3, serverId);
                reactivate.setString(4, instanceId);
                reactivate.setLong(5, existing.heartbeatAtMillis());
                return reactivate.executeUpdate() == 1;
            }
        }

        long staleBefore = now - serverStaleAfter.toMillis();
        if (existing.heartbeatAtMillis() > staleBefore) {
            logger.severe("Shared database server-id '" + serverId + "' is already active as instance "
                    + existing.instanceId() + " (last heartbeat " + (now - existing.heartbeatAtMillis())
                    + " ms ago). Configure a unique database.server-id per Paper server.");
            return false;
        }

        try (PreparedStatement takeover = connection.prepareStatement("""
                UPDATE ks_eco_servers
                SET instance_id=?, started_at_ms=?, heartbeat_at_ms=?, stopped_at_ms=0
                WHERE server_id=? AND instance_id=? AND heartbeat_at_ms=?
                """)) {
            takeover.setString(1, instanceId);
            takeover.setLong(2, now);
            takeover.setLong(3, now);
            takeover.setString(4, serverId);
            takeover.setString(5, existing.instanceId());
            takeover.setLong(6, existing.heartbeatAtMillis());
            if (takeover.executeUpdate() == 1) return true;
        }
        logger.severe("Shared database server-id '" + serverId + "' was claimed concurrently by another instance.");
        return false;
    }

    private ServerRegistration findServerRegistration(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT instance_id, heartbeat_at_ms FROM ks_eco_servers WHERE server_id=?
                """)) {
            statement.setString(1, serverId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new ServerRegistration(result.getString(1), result.getLong(2));
            }
        }
    }

    private boolean transitionOperation(String operationId, OperationStatus target) throws SQLException {
        requireInitialized();
        String id = validateKey("operationId", operationId, MAX_OPERATION_ID_LENGTH);
        try (Connection connection = openConnection()) {
            long now = currentDatabaseTime(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ks_eco_operations
                    SET status=?, updated_at_ms=?
                    WHERE operation_id=? AND owner_instance_id=? AND status=?
                    """)) {
                statement.setString(1, target.name());
                statement.setLong(2, now);
                statement.setString(3, id);
                statement.setString(4, instanceId);
                statement.setString(5, OperationStatus.PENDING.name());
                if (statement.executeUpdate() == 1) return true;
            }
            OperationRow existing = findOperation(connection, id);
            return existing != null && target == existing.status()
                    && instanceId.equals(existing.ownerInstanceId());
        }
    }

    private OperationRow findOperation(Connection connection, String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type, payload_hash, owner_server_id, owner_instance_id, status
                FROM ks_eco_operations WHERE operation_id=?
                """)) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new OperationRow(result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), parseStatus(result.getString(5)));
            }
        }
    }

    private Optional<LeaseHandle> findOwnedLease(Connection connection, String key, long expectedExpiry)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT fencing_token, expires_at_ms
                FROM ks_eco_leases
                WHERE lease_key=? AND owner_instance_id=? AND released_at_ms=0
                """)) {
            statement.setString(1, key);
            statement.setString(2, instanceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                long expiresAt = result.getLong(2);
                if (expiresAt != expectedExpiry) return Optional.empty();
                return Optional.of(new LeaseHandle(key, result.getLong(1), expiresAt));
            }
        }
    }

    private void ensureColumn(Connection connection, String tableName, String columnName, String definition)
            throws SQLException {
        if (hasColumn(connection, tableName, columnName)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        } catch (SQLException exception) {
            if (!hasColumn(connection, tableName, columnName)) throw exception;
        }
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        String[] patterns = {tableName, tableName.toUpperCase(Locale.ROOT), tableName.toLowerCase(Locale.ROOT)};
        for (String pattern : patterns) {
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, pattern, null)) {
                while (columns.next()) {
                    if (tableName.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                            && columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
                }
            }
        }
        return false;
    }

    private void requireInitialized() {
        if (!initialized) throw new IllegalStateException("EcoDatabase is not initialized");
        if (!identityHealthy()) {
            throw new IllegalStateException("EcoDatabase server identity is no longer verified");
        }
    }

    private boolean heartbeatVerificationFresh() {
        long verifiedAt = lastVerifiedHeartbeatNanos;
        return verifiedAt != 0L && System.nanoTime() - verifiedAt < serverStaleAfterNanos;
    }

    private static String validateServerId(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT);
        if (!SERVER_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "database.server-id must match " + SERVER_ID_PATTERN.pattern());
        }
        return normalized;
    }

    private static String validateOperationType(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (!OPERATION_TYPE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("operationType must match " + OPERATION_TYPE_PATTERN.pattern());
        }
        return normalized;
    }

    private static String validateKey(String label, String value, int maxLength) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + " must contain 1-" + maxLength + " characters");
        }
        return normalized;
    }

    private static String normalizePayloadHash(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("payloadHash must not exceed 128 characters");
        }
        return normalized;
    }

    private static Duration requirePositive(Duration duration, String label) {
        Objects.requireNonNull(duration, label);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return duration;
    }

    private static long requirePositiveMillis(Duration duration, String label) {
        requirePositive(duration, label);
        long millis;
        try {
            millis = duration.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(label + " is too large", exception);
        }
        if (millis <= 0) throw new IllegalArgumentException(label + " must be at least one millisecond");
        return millis;
    }

    private static long requirePositiveNanos(Duration duration, String label) {
        requirePositive(duration, label);
        long nanos;
        try {
            nanos = duration.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(label + " is too large", exception);
        }
        if (nanos <= 0L) throw new IllegalArgumentException(label + " must be at least one nanosecond");
        return nanos;
    }

    private static boolean isConstraintViolation(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("23")) return true;
            int code = current.getErrorCode();
            if (code == 19 || code == 1062) return true;
        }
        return false;
    }

    private static OperationStatus parseStatus(String value) {
        try {
            return OperationStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return OperationStatus.UNKNOWN;
        }
    }

    public enum OperationStatus {
        PENDING,
        COMPLETED,
        FAILED,
        UNKNOWN
    }

    public record OperationClaim(
            boolean claimed,
            boolean payloadCompatible,
            OperationStatus status,
            String ownerServerId,
            String ownerInstanceId
    ) {
        public boolean ownedBy(String instanceId) {
            return Objects.equals(ownerInstanceId, instanceId);
        }
    }

    public record LeaseHandle(String leaseKey, long fencingToken, long expiresAtMillis) {}

    private record ServerRegistration(String instanceId, long heartbeatAtMillis) {}

    private record OperationRow(
            String operationType,
            String payloadHash,
            String ownerServerId,
            String ownerInstanceId,
            OperationStatus status
    ) {}
}
