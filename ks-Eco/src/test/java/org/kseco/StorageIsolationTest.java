package org.kseco;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageIsolationTest {

    @Test
    void marketPendingRowsStayHiddenFromCountDeleteAndExpiry() throws Exception {
        UUID owner = UUID.randomUUID();
        try (Connection connection = database()) {
            insert(connection, "visible", owner, "MARKET_PURCHASE:L-1", 10);
            insert(connection, "pending", owner, "MARKET_PENDING:S-1:L-1", 10);

            assertEquals(1, StorageManager.countVisible(connection));
            assertFalse(StorageManager.deleteVisible(connection, owner, "pending"));
            assertEquals(1, StorageManager.deleteExpiredVisible(connection, 20));
            assertEquals(1, total(connection));
            assertTrue(exists(connection, "pending"));
        }
    }

    @Test
    void visibleDeleteRequiresMatchingOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        try (Connection connection = database()) {
            insert(connection, "visible", owner, "PURCHASE_ORDER:O-1", 10);
            assertFalse(StorageManager.deleteVisible(connection, UUID.randomUUID(), "visible"));
            assertTrue(StorageManager.deleteVisible(connection, owner, "visible"));
        }
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_eco_storage (id TEXT PRIMARY KEY,owner_uuid TEXT,item_data BLOB,"
                    + "item_material TEXT,quantity INTEGER,source TEXT,stored_at INTEGER)");
        }
        return connection;
    }

    private static void insert(Connection connection, String id, UUID owner, String source, long storedAt)
            throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO ks_eco_storage VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, owner.toString());
            statement.setBytes(3, new byte[]{1});
            statement.setString(4, "STONE");
            statement.setInt(5, 1);
            statement.setString(6, source);
            statement.setLong(7, storedAt);
            statement.executeUpdate();
        }
    }

    private static int total(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM ks_eco_storage")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static boolean exists(Connection connection, String id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT 1 FROM ks_eco_storage WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
