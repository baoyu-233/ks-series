package org.kseco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kseco.database.DatabaseDialect;

class BuiltinEconomyTest {
    @Test
    void depositReportsSuccessOnlyAfterBalancePersistenceSucceeds() {
        BuiltinEconomy.BalanceMutationResult persisted =
                BuiltinEconomy.balanceMutationResult(10.0d, 25.0d, true);
        BuiltinEconomy.BalanceMutationResult rejected =
                BuiltinEconomy.balanceMutationResult(10.0d, 25.0d, false);

        assertTrue(persisted.success());
        assertEquals(25.0d, persisted.balance());
        assertNull(persisted.error());
        assertFalse(rejected.success());
        assertEquals(10.0d, rejected.balance());
        assertEquals("Balance persistence failed", rejected.error());
    }

    @ParameterizedTest
    @MethodSource("databaseModes")
    void schemaAndAtomicMutationsWorkInRemoteDatabaseCompatibilityModes(
            String url, DatabaseDialect dialect) throws Exception {
        UUID account = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url)) {
            BuiltinEconomy.ensureTable(connection, dialect);
            connection.setAutoCommit(false);

            assertTrue(BuiltinEconomy.ensureAccount(connection, account, "original", 100L));
            assertTrue(BuiltinEconomy.depositBalance(connection, account, "renamed", 25.0d, 101L).success());
            assertTrue(BuiltinEconomy.withdrawBalance(connection, account, "", 7.5d, 102L).success());
            BuiltinEconomy.BalanceMutationResult insufficient =
                    BuiltinEconomy.withdrawBalance(connection, account, "", 100.0d, 103L);
            connection.commit();

            assertFalse(insufficient.success());
            assertEquals(17.5d, insufficient.balance());
            assertEquals(17.5d, BuiltinEconomy.readBalance(connection, account));
            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery(
                         "SELECT name, updated_at FROM ks_builtin_economy WHERE uuid='" + account + "'")) {
                assertTrue(row.next());
                assertEquals("renamed", row.getString(1));
                assertEquals(102L, row.getLong(2));
            }

            assertTrue(BuiltinEconomy.ensureAccount(connection, account, "renamed", 104L));
            connection.commit();
            assertEquals(17.5d, BuiltinEconomy.readBalance(connection, account),
                    "creating an existing account must not reset its balance");
        }
    }

    @ParameterizedTest
    @MethodSource("databaseModes")
    void concurrentDepositsDoNotLoseUpdates(String url, DatabaseDialect dialect) throws Exception {
        UUID account = UUID.randomUUID();
        try (Connection keeper = DriverManager.getConnection(url)) {
            BuiltinEconomy.ensureTable(keeper, dialect);
            assertTrue(BuiltinEconomy.ensureAccount(keeper, account, "player", 1L));

            int workers = 6;
            int depositsPerWorker = 30;
            var executor = Executors.newFixedThreadPool(workers);
            var start = new CountDownLatch(1);
            @SuppressWarnings("unchecked")
            Future<Void>[] futures = new Future[workers];
            try {
                for (int worker = 0; worker < workers; worker++) {
                    futures[worker] = executor.submit(() -> {
                        start.await();
                        try (Connection connection = DriverManager.getConnection(url)) {
                            connection.setAutoCommit(false);
                            for (int index = 0; index < depositsPerWorker; index++) {
                                assertTrue(BuiltinEconomy.depositBalance(
                                        connection, account, "player", 1.0d, 10L + index).success());
                                connection.commit();
                            }
                        }
                        return null;
                    });
                }
                start.countDown();
                for (Future<Void> future : futures) future.get();
            } finally {
                executor.shutdownNow();
            }

            assertEquals((double) workers * depositsPerWorker,
                    BuiltinEconomy.readBalance(keeper, account));
        }
    }

    @ParameterizedTest
    @MethodSource("databaseModes")
    void callerOwnedTransactionCanRollBackDeposit(String url, DatabaseDialect dialect) throws Exception {
        UUID account = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url)) {
            BuiltinEconomy.ensureTable(connection, dialect);
            connection.setAutoCommit(false);
            assertTrue(BuiltinEconomy.depositBalance(connection, account, "player", 10.0d, 1L).success());
            connection.rollback();
            assertEquals(0.0d, BuiltinEconomy.readBalance(connection, account));
        }
    }

    private static Stream<Arguments> databaseModes() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return Stream.of(
                Arguments.of("jdbc:h2:mem:builtin_mysql_" + suffix
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", DatabaseDialect.MYSQL),
                Arguments.of("jdbc:h2:mem:builtin_pg_" + suffix
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", DatabaseDialect.POSTGRESQL));
    }
}
