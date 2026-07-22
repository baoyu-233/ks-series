package org.kseco.database;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableSqlMutationTest {
    @Test
    void upsertInsertsThenUpdatesWithoutVendorSyntax() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("CREATE TABLE settings (key TEXT PRIMARY KEY,value TEXT NOT NULL,updated_at INTEGER NOT NULL)");

            upsertSetting(connection, "limit", "10", 1);
            upsertSetting(connection, "limit", "20", 2);

            try (var result = connection.createStatement().executeQuery(
                    "SELECT value,updated_at FROM settings WHERE key='limit'")) {
                assertTrue(result.next());
                assertEquals("20", result.getString(1));
                assertEquals(2, result.getLong(2));
            }
        }
    }

    @Test
    void insertIfAbsentNeverOverwritesExistingBusinessState() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("CREATE TABLE accounts (id TEXT PRIMARY KEY,balance REAL NOT NULL)");
            assertTrue(PortableSqlMutation.insertIfAbsent(connection,
                    "SELECT 1 FROM accounts WHERE id=?", ps -> ps.setString(1, "A"),
                    "INSERT INTO accounts (id,balance) VALUES (?,?)",
                    ps -> { ps.setString(1, "A"); ps.setDouble(2, 100); }));
            assertFalse(PortableSqlMutation.insertIfAbsent(connection,
                    "SELECT 1 FROM accounts WHERE id=?", ps -> ps.setString(1, "A"),
                    "INSERT INTO accounts (id,balance) VALUES (?,?)",
                    ps -> { ps.setString(1, "A"); ps.setDouble(2, 999); }));

            try (var result = connection.createStatement().executeQuery("SELECT balance FROM accounts WHERE id='A'")) {
                assertTrue(result.next());
                assertEquals(100, result.getDouble(1));
            }
        }
    }

    @Test
    void rejectsNaturalKeyUpdateThatTouchesMultipleRows() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("CREATE TABLE broken_settings (key TEXT,value TEXT)");
            connection.createStatement().execute("INSERT INTO broken_settings VALUES ('x','1'),('x','2')");
            assertThrows(SQLException.class, () -> PortableSqlMutation.upsert(connection,
                    "UPDATE broken_settings SET value=? WHERE key=?",
                    ps -> { ps.setString(1, "3"); ps.setString(2, "x"); },
                    "INSERT INTO broken_settings (key,value) VALUES (?,?)",
                    ps -> { ps.setString(1, "x"); ps.setString(2, "3"); }));
        }
    }

    @Test
    void failedTransactionalInsertRollsBackToSavepointBeforeReturningFailure() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute(
                    "CREATE TABLE settings (key TEXT PRIMARY KEY,value TEXT NOT NULL,updated_at INTEGER NOT NULL)");
            connection.setAutoCommit(false);

            assertThrows(SQLException.class, () -> PortableSqlMutation.upsert(connection,
                    "UPDATE settings SET value=?,updated_at=? WHERE key=?",
                    ps -> { ps.setString(1, "bad"); ps.setLong(2, 1); ps.setString(3, "missing"); },
                    "INSERT INTO settings (key,value,updated_at) VALUES (?,?,?)",
                    ps -> { ps.setString(1, "missing"); ps.setNull(2, java.sql.Types.VARCHAR); ps.setLong(3, 1); }));

            connection.createStatement().executeUpdate("INSERT INTO settings VALUES ('usable','ok',2)");
            connection.commit();
            try (var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM settings")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    private static void upsertSetting(java.sql.Connection connection, String key, String value, long updatedAt)
            throws SQLException {
        PortableSqlMutation.upsert(connection,
                "UPDATE settings SET value=?,updated_at=? WHERE key=?",
                ps -> { ps.setString(1, value); ps.setLong(2, updatedAt); ps.setString(3, key); },
                "INSERT INTO settings (key,value,updated_at) VALUES (?,?,?)",
                ps -> { ps.setString(1, key); ps.setString(2, value); ps.setLong(3, updatedAt); });
    }
}
