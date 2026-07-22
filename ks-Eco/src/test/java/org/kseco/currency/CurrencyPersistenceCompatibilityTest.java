package org.kseco.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrencyPersistenceCompatibilityTest {
    @TempDir
    Path tempDirectory;

    @Test
    void canonicalizesOnlyLegacyCashSpellingsAndBlankRows() throws Exception {
        String url = "jdbc:sqlite:" + tempDirectory.resolve("legacy-cash.db").toAbsolutePath();
        try (var connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ks_test_currency_rows (id INTEGER PRIMARY KEY, currency_id TEXT)");
            statement.execute("INSERT INTO ks_test_currency_rows(currency_id) VALUES "
                    + "(NULL),(''),(' cash '),('Cash'),('GOLD_BAR'),('invalid:value')");

            CurrencyPersistenceCompatibility.canonicalizeLegacyCashRows(connection, "ks_test_currency_rows");

            try (ResultSet result = statement.executeQuery(
                    "SELECT currency_id FROM ks_test_currency_rows ORDER BY id")) {
                assertEquals("CASH", next(result));
                assertEquals("CASH", next(result));
                assertEquals("CASH", next(result));
                assertEquals("CASH", next(result));
                assertEquals("GOLD_BAR", next(result));
                assertEquals("invalid:value", next(result));
            }
        }
    }

    @Test
    void rejectsUnsafeDynamicTableNames() throws Exception {
        String url = "jdbc:sqlite:" + tempDirectory.resolve("unsafe-name.db").toAbsolutePath();
        try (var connection = DriverManager.getConnection(url)) {
            assertThrows(IllegalArgumentException.class,
                    () -> CurrencyPersistenceCompatibility.canonicalizeLegacyCashRows(
                            connection, "ks_rows; DROP TABLE ks_rows"));
        }
    }

    private static String next(ResultSet result) throws Exception {
        if (!result.next()) throw new AssertionError("missing migration row");
        return result.getString(1);
    }
}
