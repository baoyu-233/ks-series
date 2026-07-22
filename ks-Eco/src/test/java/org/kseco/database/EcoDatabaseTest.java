package org.kseco.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcoDatabaseTest {
    @TempDir
    Path tempDirectory;

    @Test
    void heartbeatFailureFailsClosedAndSuccessfulProbeRestoresHealth() throws Exception {
        String url = sqliteUrl("heartbeat.db");
        AtomicBoolean unavailable = new AtomicBoolean();
        EcoDatabase database = database(url, unavailable);

        assertTrue(database.initialize());
        assertTrue(database.identityHealthy());

        unavailable.set(true);
        assertFalse(database.heartbeat());
        assertFalse(database.identityHealthy());
        assertThrows(IllegalStateException.class,
                () -> database.tryAcquireLease("settlement", Duration.ofSeconds(30)));

        unavailable.set(false);
        assertTrue(database.heartbeat());
        assertTrue(database.identityHealthy());
        assertTrue(database.tryAcquireLease("settlement", Duration.ofSeconds(30)).isPresent());
        database.close();
    }

    @Test
    void expiredLocalHeartbeatVerificationFailsClosedUntilRevalidated() throws Exception {
        String url = sqliteUrl("heartbeat-expiry.db");
        EcoDatabase database = new EcoDatabase(Logger.getLogger("EcoDatabaseTest-expiry"),
                connectionSupplier(url, new AtomicBoolean()), "test-server",
                Duration.ofMillis(50), Duration.ofMillis(100));
        assertTrue(database.initialize());

        Thread.sleep(250L);
        assertFalse(database.identityHealthy());
        assertThrows(IllegalStateException.class,
                () -> database.tryAcquireLease("settlement", Duration.ofSeconds(30)));

        assertTrue(database.heartbeat());
        assertTrue(database.identityHealthy());
        database.close();
    }

    @Test
    void confirmedIdentityLossCannotBeHealedByAStaleInstance() throws Exception {
        String url = sqliteUrl("identity-loss.db");
        EcoDatabase database = database(url, new AtomicBoolean());
        assertTrue(database.initialize());

        try (Connection connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement(
                     "UPDATE ks_eco_servers SET instance_id=? WHERE server_id=?")) {
            statement.setString(1, "replacement-instance");
            statement.setString(2, database.serverId());
            assertEquals(1, statement.executeUpdate());
        }

        assertFalse(database.heartbeat());
        assertFalse(database.identityHealthy());
        try (Connection connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement(
                     "UPDATE ks_eco_servers SET instance_id=? WHERE server_id=?")) {
            statement.setString(1, database.instanceId());
            statement.setString(2, database.serverId());
            assertEquals(1, statement.executeUpdate());
        }
        assertFalse(database.heartbeat());
        assertFalse(database.identityHealthy());
        database.close();
    }

    @Test
    void closeStillStopsIdentityWhenLeaseReleaseFails() throws Exception {
        String url = sqliteUrl("close-failure.db");
        EcoDatabase database = database(url, new AtomicBoolean());
        assertTrue(database.initialize());
        assertTrue(database.tryAcquireLease("owned", Duration.ofSeconds(30)).isPresent());

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER reject_lease_release
                    BEFORE UPDATE ON ks_eco_leases
                    BEGIN
                        SELECT RAISE(ABORT, 'lease release rejected');
                    END
                    """);
        }

        database.close();
        assertFalse(database.initialized());
        try (Connection connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement(
                     "SELECT heartbeat_at_ms, stopped_at_ms FROM ks_eco_servers WHERE server_id=?")) {
            statement.setString(1, database.serverId());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0L, result.getLong(1));
                assertTrue(result.getLong(2) > 0L);
            }
        }
    }

    @Test
    void reinitializeSameInstanceRefreshesStoppedRegistration() throws Exception {
        String url = sqliteUrl("reinitialize.db");
        EcoDatabase database = database(url, new AtomicBoolean());
        assertTrue(database.initialize());
        database.close();

        assertTrue(database.initialize());
        assertTrue(database.identityHealthy());
        try (Connection connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement(
                     "SELECT heartbeat_at_ms, stopped_at_ms FROM ks_eco_servers WHERE server_id=?")) {
            statement.setString(1, database.serverId());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertTrue(result.getLong(1) > 0L);
                assertEquals(0L, result.getLong(2));
            }
        }
        database.close();
    }

    @Test
    void releaseAndShutdownAdvanceFencingTokens() throws Exception {
        String url = sqliteUrl("fencing.db");
        EcoDatabase database = database(url, new AtomicBoolean());
        assertTrue(database.initialize());

        EcoDatabase.LeaseHandle first = database.tryAcquireLease("resource", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(database.releaseLease(first));
        assertEquals(first.fencingToken() + 1L, fencingToken(url, "resource"));

        EcoDatabase.LeaseHandle second = database.tryAcquireLease("resource", Duration.ofSeconds(30)).orElseThrow();
        assertEquals(first.fencingToken() + 2L, second.fencingToken());
        database.close();
        assertEquals(second.fencingToken() + 1L, fencingToken(url, "resource"));
    }

    private EcoDatabase database(String url, AtomicBoolean unavailable) {
        return new EcoDatabase(Logger.getLogger("EcoDatabaseTest-" + url), connectionSupplier(url, unavailable),
                "test-server", Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private Supplier<Connection> connectionSupplier(String url, AtomicBoolean unavailable) {
        return () -> {
            if (unavailable.get()) throw new IllegalStateException("database unavailable");
            try {
                return DriverManager.getConnection(url);
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        };
    }

    private String sqliteUrl(String fileName) {
        return "jdbc:sqlite:" + tempDirectory.resolve(fileName).toAbsolutePath();
    }

    private long fencingToken(String url, String leaseKey) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement(
                     "SELECT fencing_token FROM ks_eco_leases WHERE lease_key=?")) {
            statement.setString(1, leaseKey);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }
}
