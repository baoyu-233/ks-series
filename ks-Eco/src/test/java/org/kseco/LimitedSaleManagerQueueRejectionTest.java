package org.kseco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class LimitedSaleManagerQueueRejectionTest {
    @Test
    void rejectedPostChargeTaskUsesPinnedBackendClearsActivePurchaseAndCompletes() {
        AsyncWorkPool pool = new AsyncWorkPool(1, Logger.getAnonymousLogger());
        pool.shutdown();

        String operationKey = "player:sale";
        Set<String> activePurchases = ConcurrentHashMap.newKeySet();
        activePurchases.add(operationKey);
        AtomicBoolean databaseTaskRan = new AtomicBoolean();
        AtomicReference<LimitedSaleManager.CashBackend> refundedBackend = new AtomicReference<>();
        AtomicReference<String> callbackResult = new AtomicReference<>();
        AtomicInteger callbackCount = new AtomicInteger();
        Consumer<String> completion = LimitedSaleManager.completionClearingPurchase(
                activePurchases, operationKey, result -> {
                    callbackResult.set(result);
                    callbackCount.incrementAndGet();
                });
        LimitedSaleManager.CashBackend chargedBackend = LimitedSaleManager.CashBackend.VAULT;

        boolean submitted = LimitedSaleManager.executeDatabaseOrReject(
                pool,
                () -> databaseTaskRan.set(true),
                () -> {
                    refundedBackend.set(chargedBackend);
                    completion.accept("rejected");
                });

        assertFalse(submitted);
        assertFalse(databaseTaskRan.get());
        assertSame(chargedBackend, refundedBackend.get());
        assertFalse(activePurchases.contains(operationKey));
        assertEquals("rejected", callbackResult.get());
        assertEquals(1, callbackCount.get());
    }

    @Test
    void completionClearsActivePurchaseBeforeCallingUserCallback() {
        String operationKey = "player:sale";
        Set<String> activePurchases = ConcurrentHashMap.newKeySet();
        activePurchases.add(operationKey);
        Consumer<String> completion = LimitedSaleManager.completionClearingPurchase(
                activePurchases, operationKey, ignored -> {
                    throw new IllegalStateException("callback failure");
                });

        assertThrows(IllegalStateException.class, () -> completion.accept("result"));
        assertFalse(activePurchases.contains(operationKey));
    }

    @Test
    void rejectedDatabaseTaskUsesEmergencyFallbackBeforeReportingFailure() {
        AsyncWorkPool pool = new AsyncWorkPool(1, Logger.getAnonymousLogger());
        pool.shutdown();
        AtomicBoolean taskRan = new AtomicBoolean();
        AtomicInteger fallbackCount = new AtomicInteger();
        AtomicBoolean rejected = new AtomicBoolean();

        boolean submitted = LimitedSaleManager.executeDatabaseOrFallback(
                pool,
                () -> taskRan.set(true),
                task -> {
                    fallbackCount.incrementAndGet();
                    task.run();
                },
                () -> rejected.set(true));

        assertTrue(submitted);
        assertTrue(taskRan.get());
        assertEquals(1, fallbackCount.get());
        assertFalse(rejected.get());
    }

    @Test
    void rejectedDatabaseAndFallbackReportFailureWithoutRunningTask() {
        AsyncWorkPool pool = new AsyncWorkPool(1, Logger.getAnonymousLogger());
        pool.shutdown();
        AtomicBoolean taskRan = new AtomicBoolean();
        AtomicInteger rejected = new AtomicInteger();

        boolean submitted = LimitedSaleManager.executeDatabaseOrFallback(
                pool,
                () -> taskRan.set(true),
                task -> { throw new RejectedExecutionException("fallback unavailable"); },
                rejected::incrementAndGet);

        assertFalse(submitted);
        assertFalse(taskRan.get());
        assertEquals(1, rejected.get());
    }

    @Test
    void rejectedServerThreadHandoffRunsCompensationExactlyOnce() {
        AtomicBoolean taskRan = new AtomicBoolean();
        AtomicInteger compensated = new AtomicInteger();

        boolean dispatched = LimitedSaleManager.dispatchOrReject(
                task -> { throw new IllegalStateException("plugin disabled"); },
                () -> taskRan.set(true),
                compensated::incrementAndGet);

        assertFalse(dispatched);
        assertFalse(taskRan.get());
        assertEquals(1, compensated.get());
    }

    @Test
    void emergencyFallbackRestoresStockAndPlayerLimitBeforeRefundBecomesReady() throws Exception {
        AsyncWorkPool pool = new AsyncWorkPool(1, Logger.getAnonymousLogger());
        pool.shutdown();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute(
                    "CREATE TABLE ks_limited_sale_items(id TEXT PRIMARY KEY,sold INTEGER,updated_at INTEGER)");
            connection.createStatement().execute(
                    "CREATE TABLE ks_limited_sale_players(sale_id TEXT,player_uuid TEXT,purchased INTEGER," +
                            "updated_at INTEGER,PRIMARY KEY(sale_id,player_uuid))");
            LimitedSaleSettlementStore.initialize(connection);
            UUID playerUuid = UUID.randomUUID();
            connection.createStatement().execute(
                    "INSERT INTO ks_limited_sale_items VALUES('sale',5,1)");
            try (var statement = connection.prepareStatement(
                    "INSERT INTO ks_limited_sale_players VALUES('sale',?,5,1)")) {
                statement.setString(1, playerUuid.toString());
                statement.executeUpdate();
            }
            LimitedSaleSettlementStore.insertReady(connection,
                    new LimitedSaleSettlementStore.Settlement("LS-fallback", playerUuid, "Alice", "sale",
                            2, false, 20.0d, "VAULT", LimitedSaleSettlementStore.READY, ""), 1L);
            assertTrue(LimitedSaleSettlementStore.transition(connection, "LS-fallback",
                    LimitedSaleSettlementStore.READY, LimitedSaleSettlementStore.DELIVERING, "", 2L));
            AtomicBoolean rejected = new AtomicBoolean();

            boolean submitted = LimitedSaleManager.executeDatabaseOrFallback(pool, () -> {
                try {
                    LimitedSaleManager.rollbackPurchaseCounters(connection, "LS-fallback", "sale", playerUuid,
                            2, LimitedSaleSettlementStore.DELIVERING, 3L);
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
            }, Runnable::run, () -> rejected.set(true));

            assertTrue(submitted);
            assertFalse(rejected.get());
            assertEquals(3, scalar(connection, "SELECT sold FROM ks_limited_sale_items"));
            assertEquals(3, scalar(connection, "SELECT purchased FROM ks_limited_sale_players"));
            assertEquals(LimitedSaleSettlementStore.REFUND_READY,
                    LimitedSaleSettlementStore.find(connection, "LS-fallback").status());
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
