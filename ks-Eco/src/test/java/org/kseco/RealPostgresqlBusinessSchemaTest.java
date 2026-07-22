package org.kseco;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.kseco.crossserver.JdbcCrossServerRepository;
import org.kseco.crossserver.sql.SqlDialect;
import org.kseco.crossserver.lock.DataSourceLeaseSqlExecutor;
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
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts a real PostgreSQL process; H2 compatibility mode is intentionally not used here. */
class RealPostgresqlBusinessSchemaTest {
    @Test
    void initializesAndMutatesCoreBusinessSchemasOnRealPostgresql() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            var dataSource = postgres.getPostgresDatabase();
            try (Connection connection = dataSource.getConnection()) {
                assertEquals(DatabaseDialect.POSTGRESQL, DatabaseDialect.detect(connection));
                initializeAll(connection);
                initializeAll(connection);
                BuiltinEconomy.ensureTable(connection, DatabaseDialect.POSTGRESQL);
                var account = java.util.UUID.randomUUID();
                assertTrue(BuiltinEconomy.depositBalance(connection, account, "postgres", 25.0D, 1L).success());
                assertTrue(BuiltinEconomy.withdrawBalance(connection, account, "postgres", 5.0D, 2L).success());
                assertEquals(20.0D, BuiltinEconomy.readBalance(connection, account));

                var crossServer = new JdbcCrossServerRepository(SqlDialect.POSTGRESQL);
                crossServer.initialize(connection);
                assertTrue(crossServer.currentDatabaseTime(connection).toEpochMilli() > 0L);
            }

            verifyCoordination(dataSource, SqlDialect.POSTGRESQL);

            var ledger = new JdbcCurrencyLedger(dataSource::getConnection,
                    CurrencySqlDialect.POSTGRESQL, "postgres-test");
            ledger.initializeSchema();
            ledger.initializeSchema();
            try (var demand = new JdbcDemandCampaignStore(dataSource::getConnection)) {
                demand.initializeSchema();
                demand.initializeSchema();
            }
        }
    }

    private static void verifyCoordination(javax.sql.DataSource dataSource, SqlDialect dialect) throws Exception {
        var store = new JdbcDatabaseTransportStore(dataSource::getConnection, dialect);
        store.initialize();
        store.initialize();
        long now = System.currentTimeMillis();
        store.publish(new TransportEvent("pg-event", "cache", "pg-a", TransportEvent.BROADCAST_TARGET,
                now, now, 0L, "application/json", new byte[]{1}, Map.of()));
        var batch = store.poll(new PollRequest("pg-b/runtime", "pg-b", PollCursor.initial(), 10, now + 1_000L));
        assertEquals(1, batch.events().size());

        var lock = new JdbcDistributedLeaseLock(
                new DataSourceLeaseSqlExecutor(dataSource), new JdbcCrossServerRepository(dialect));
        lock.initializeSchema();
        var acquired = (LeaseAcquireResult.Acquired) lock.tryAcquire(
                "native-price-refresh", "pg-a", Duration.ofSeconds(30));
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
