package org.kseco.database;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentralBankBootstrapTest {
    @Test
    void repeatedBootstrapKeepsOneBankAndPreservesConfiguration() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            CoreBusinessSchema.initialize(connection, DatabaseDialect.SQLITE);
            var first = CentralBankBootstrap.ensure(connection, () -> "CB-FIRST", 1L);
            assertTrue(first.created());
            connection.createStatement().executeUpdate(
                    "UPDATE ks_bank_cb_config SET config_value='0.03' WHERE config_key='rate_adjust_limit'");

            var second = CentralBankBootstrap.ensure(connection, () -> "CB-SECOND", 2L);
            assertFalse(second.created());
            assertEquals("CB-FIRST", second.bankId());
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM ks_bank_banks WHERE type='CENTRAL'"));
            assertEquals("0.03", stringScalar(connection,
                    "SELECT config_value FROM ks_bank_cb_config WHERE config_key='rate_adjust_limit'"));
        }
    }

    @Test
    void adoptsAnExistingCentralBank() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:central_existing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
            CoreBusinessSchema.initialize(connection, DatabaseDialect.POSTGRESQL);
            connection.createStatement().executeUpdate(
                    "INSERT INTO ks_bank_banks (id,name,type,owner_uuids,total_assets,created_at) "
                            + "VALUES ('CB-LEGACY','Legacy','CENTRAL','SYSTEM',1,1)");
            var result = CentralBankBootstrap.ensure(connection, () -> "CB-NEW", 2L);
            assertFalse(result.created());
            assertEquals("CB-LEGACY", result.bankId());
            assertEquals("CB-LEGACY", stringScalar(connection,
                    "SELECT resource_id FROM ks_bank_system_singletons WHERE singleton_key='CENTRAL_BANK'"));
        }
    }

    @Test
    void concurrentBootstrapCreatesExactlyOneCentralBank() throws Exception {
        String url = "jdbc:h2:mem:central_race;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (var setup = DriverManager.getConnection(url)) {
            CoreBusinessSchema.initialize(setup, DatabaseDialect.MYSQL);
        }

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                try (var connection = DriverManager.getConnection(url)) {
                    return CentralBankBootstrap.ensure(connection, () -> "CB-A", 1L);
                }
            });
            var second = executor.submit(() -> {
                start.await();
                try (var connection = DriverManager.getConnection(url)) {
                    return CentralBankBootstrap.ensure(connection, () -> "CB-B", 1L);
                }
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        try (var verify = DriverManager.getConnection(url)) {
            assertEquals(1, scalar(verify, "SELECT COUNT(*) FROM ks_bank_banks WHERE type='CENTRAL'"));
            assertEquals(1, scalar(verify, "SELECT COUNT(*) FROM ks_bank_system_singletons"));
        }
    }

    private static int scalar(java.sql.Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static String stringScalar(java.sql.Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
