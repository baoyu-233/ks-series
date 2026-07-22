package org.kseco;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import org.junit.jupiter.api.Test;
import org.kseco.crossserver.JdbcCrossServerRepository;
import org.kseco.crossserver.sql.SqlDialect;
import org.kseco.crossserver.lock.ConnectionFactoryLeaseSqlExecutor;
import org.kseco.crossserver.lock.FencedExecutionResult;
import org.kseco.crossserver.lock.JdbcDistributedLeaseLock;
import org.kseco.crossserver.lock.LeaseAcquireResult;
import org.kseco.crossserver.transport.*;
import org.kseco.currency.CurrencySqlDialect;
import org.kseco.currency.JdbcCurrencyLedger;
import org.kseco.database.CoreBusinessSchema;
import org.kseco.database.DatabaseDialect;
import org.kseco.demand.JdbcDemandCampaignStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts a real MariaDB process; H2 compatibility mode is intentionally not used here. */
class RealMariaDbBusinessSchemaTest {
    @Test
    void initializesAndMutatesCoreBusinessSchemasOnRealMariaDb() throws Exception {
        DBConfigurationBuilder configuration = DBConfigurationBuilder.newBuilder()
                .setPort(0)
                .setDeletingTemporaryBaseAndDataDirsOnShutdown(true);
        DB database = DB.newEmbeddedDB(configuration.build());
        database.start();
        try {
            String databaseName = "ks_eco_test";
            database.createDB(databaseName);
            String jdbcUrl = "jdbc:mariadb://localhost:" + database.getConfiguration().getPort()
                    + "/" + databaseName;
            try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", "")) {
                assertEquals(DatabaseDialect.MARIADB, DatabaseDialect.detect(connection));
                initializeAll(connection);
                initializeAll(connection);
                BuiltinEconomy.ensureTable(connection, DatabaseDialect.MARIADB);
                var account = java.util.UUID.randomUUID();
                assertTrue(BuiltinEconomy.depositBalance(connection, account, "mariadb", 25.0D, 1L).success());
                assertTrue(BuiltinEconomy.withdrawBalance(connection, account, "mariadb", 5.0D, 2L).success());
                assertEquals(20.0D, BuiltinEconomy.readBalance(connection, account));

                var crossServer = new JdbcCrossServerRepository(SqlDialect.MYSQL);
                crossServer.initialize(connection);
                assertTrue(crossServer.currentDatabaseTime(connection).toEpochMilli() > 0L);
            }

            verifyCoordination(jdbcUrl);

            var ledger = new JdbcCurrencyLedger(
                    () -> DriverManager.getConnection(jdbcUrl, "root", ""),
                    CurrencySqlDialect.MARIADB,
                    "mariadb-test"
            );
            ledger.initializeSchema();
            ledger.initializeSchema();
            try (var demand = new JdbcDemandCampaignStore(
                    () -> DriverManager.getConnection(jdbcUrl, "root", ""))) {
                demand.initializeSchema();
                demand.initializeSchema();
            }
        } finally {
            database.stop();
        }
    }

    private static void verifyCoordination(String jdbcUrl) throws Exception {
        JdbcDatabaseTransportStore.ConnectionFactory connections =
                () -> DriverManager.getConnection(jdbcUrl, "root", "");
        var store = new JdbcDatabaseTransportStore(connections, SqlDialect.MYSQL);
        store.initialize();
        store.initialize();
        long now = System.currentTimeMillis();
        store.publish(new TransportEvent("maria-event", "cache", "maria-a", TransportEvent.BROADCAST_TARGET,
                now, now, 0L, "application/json", new byte[]{1}, Map.of()));
        var batch = store.poll(new PollRequest(
                "maria-b/runtime", "maria-b", PollCursor.initial(), 10, now + 1_000L));
        assertEquals(1, batch.events().size());

        var lock = new JdbcDistributedLeaseLock(
                new ConnectionFactoryLeaseSqlExecutor(connections::open),
                new JdbcCrossServerRepository(SqlDialect.MYSQL));
        lock.initializeSchema();
        var acquired = (LeaseAcquireResult.Acquired) lock.tryAcquire(
                "native-price-refresh", "maria-a", Duration.ofSeconds(30));
        assertTrue(lock.executeFenced(acquired.token(), (connection, token) -> token)
                instanceof FencedExecutionResult.Executed<?>);
    }

    private static void initializeAll(Connection connection) throws Exception {
        CoreBusinessSchema.initialize(connection);
        EcoWebBusinessSchema.initialize(connection);
        BlindBoxManager.initializeSchema(connection);
        LimitedSaleManager.initializeSchema(connection);
        MarketPurchaseSettlementStore.initialize(connection);
        PropertyMarketSettlementStore.initialize(connection);
    }
}
