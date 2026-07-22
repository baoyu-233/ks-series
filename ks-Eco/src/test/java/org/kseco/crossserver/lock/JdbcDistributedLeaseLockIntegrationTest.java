package org.kseco.crossserver.lock;

import org.junit.jupiter.api.Test;
import org.kseco.crossserver.JdbcCrossServerRepository;
import org.kseco.crossserver.sql.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDistributedLeaseLockIntegrationTest {

    @Test
    void onlyOneNodeExecutesAndTakeoverRejectsStaleToken() throws Exception {
        String url = "jdbc:sqlite:file:lease-runtime-" + UUID.randomUUID() + "?mode=memory&cache=shared";
        try (Connection keeper = DriverManager.getConnection(url)) {
            var lock = new JdbcDistributedLeaseLock(
                    new ConnectionFactoryLeaseSqlExecutor(() -> DriverManager.getConnection(url)),
                    new JdbcCrossServerRepository(SqlDialect.SQLITE));
            lock.initializeSchema();

            var first = assertInstanceOf(LeaseAcquireResult.Acquired.class,
                    lock.tryAcquire("price-refresh", "server-a", Duration.ofSeconds(30)));
            assertInstanceOf(LeaseAcquireResult.Busy.class,
                    lock.tryAcquire("price-refresh", "server-b", Duration.ofSeconds(30)));
            var executed = assertInstanceOf(FencedExecutionResult.Executed.class,
                    lock.executeFenced(first.token(), (connection, token) -> token));
            assertEquals(first.token().fencingToken(), executed.value());
            assertEquals(LeaseReleaseResult.RELEASED, lock.release(first.token()));

            var takeover = assertInstanceOf(LeaseAcquireResult.Acquired.class,
                    lock.tryAcquire("price-refresh", "server-b", Duration.ofSeconds(30)));
            assertTrue(takeover.token().fencingToken() > first.token().fencingToken());
            assertInstanceOf(FencedExecutionResult.LeaseLost.class,
                    lock.executeFenced(first.token(), (connection, token) -> "must-not-run"));
        }
    }
}
