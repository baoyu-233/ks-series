package org.kseco.crossserver.runtime;

import org.junit.jupiter.api.Test;
import org.kseco.crossserver.cache.CacheInvalidationWireAdapter;
import org.kseco.crossserver.cache.CrossServerCacheInvalidationCoordinator;
import org.kseco.crossserver.sql.SqlDialect;
import org.kseco.crossserver.transport.DatabasePollingTransport;
import org.kseco.crossserver.transport.JdbcDatabaseTransportStore;
import org.kseco.crossserver.transport.PollBackoffPolicy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerRuntimeIntegrationTest {

    @Test
    void twoNodesBroadcastExactlyOnceAndStopCleanly() throws Exception {
        String url = "jdbc:sqlite:file:runtime-" + UUID.randomUUID() + "?mode=memory&cache=shared";
        try (Connection keeper = DriverManager.getConnection(url)) {
            JdbcDatabaseTransportStore store = new JdbcDatabaseTransportStore(
                    () -> DriverManager.getConnection(url), SqlDialect.SQLITE);
            store.initialize();
            ExecutorService dbA = Executors.newSingleThreadExecutor();
            ExecutorService dbB = Executors.newSingleThreadExecutor();
            ScheduledExecutorService pollA = Executors.newSingleThreadScheduledExecutor();
            ScheduledExecutorService pollB = Executors.newSingleThreadScheduledExecutor();
            CrossServerRuntime nodeA = runtime("server-a", store, dbA, pollA);
            CrossServerRuntime nodeB = runtime("server-b", store, dbB, pollB);
            try {
                AtomicInteger localNotifications = new AtomicInteger();
                AtomicInteger remoteNotifications = new AtomicInteger();
                CountDownLatch remoteReceived = new CountDownLatch(1);
                nodeA.subscribeNamespace("price", ignored -> localNotifications.incrementAndGet());
                nodeB.subscribeNamespace("price", ignored -> {
                    remoteNotifications.incrementAndGet();
                    remoteReceived.countDown();
                });
                nodeA.start();
                nodeB.start();

                nodeA.invalidate("price", "DIAMOND").toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertTrue(remoteReceived.await(5, TimeUnit.SECONDS));
                Thread.sleep(250L);
                assertEquals(1, localNotifications.get(), "local echo must be deduplicated");
                assertEquals(1, remoteNotifications.get(), "remote invalidation must be delivered once");

                nodeB.stop().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertFalse(nodeB.isRunning());
            } finally {
                nodeA.stop().toCompletableFuture().get(3, TimeUnit.SECONDS);
                nodeB.stop().toCompletableFuture().get(3, TimeUnit.SECONDS);
                dbA.shutdownNow();
                dbB.shutdownNow();
            }
        }
    }

    private static CrossServerRuntime runtime(
            String serverId,
            JdbcDatabaseTransportStore store,
            ExecutorService databaseExecutor,
            ScheduledExecutorService scheduler
    ) {
        PollBackoffPolicy fastPoll = new PollBackoffPolicy(1L, 5L, 25L, 5L, 25L, 1.5d, 0.0d);
        var transport = new DatabasePollingTransport(
                serverId, "integration-v1", store, databaseExecutor, scheduler, Runnable::run,
                32, 65_536, fastPoll);
        return new CrossServerRuntime(
                transport,
                new CrossServerCacheInvalidationCoordinator(serverId),
                new CacheInvalidationWireAdapter(),
                scheduler,
                Duration.ofDays(1),
                failure -> { throw new AssertionError(failure); });
    }
}
