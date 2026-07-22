package org.kseco.crossserver.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CacheInvalidationCoordinatorTest {

    @Test
    void appliesUniqueInvalidationsEvenWhenTheirNodeClockIsBehind() {
        AtomicLong clock = new AtomicLong(1_000L);
        CrossServerCacheInvalidationCoordinator coordinator = coordinator("local", clock);
        CacheKey key = new CacheKey("balance", "player");
        AtomicInteger notifications = new AtomicInteger();
        coordinator.subscribe(key, ignored -> notifications.incrementAndGet());

        CacheInvalidationMessage future = message(key, "fast", 5_000L);
        CacheInvalidationMessage laterButSlower = message(key, "slow", 2_000L);

        assertEquals(CrossServerCacheInvalidationCoordinator.ApplyStatus.APPLIED,
                coordinator.receive(future).status());
        CrossServerCacheInvalidationCoordinator.VersionSnapshot snapshot = coordinator.snapshot(key);
        assertEquals(CrossServerCacheInvalidationCoordinator.ApplyStatus.APPLIED,
                coordinator.receive(laterButSlower).status());
        assertFalse(coordinator.isUnchanged(snapshot));
        assertEquals(2, notifications.get());
    }

    @Test
    void listenerFailureCanBeRetriedWithTheSameEventId() {
        AtomicLong clock = new AtomicLong(1_000L);
        CrossServerCacheInvalidationCoordinator coordinator = coordinator("local", clock);
        CacheKey key = new CacheKey("listing", "one");
        AtomicInteger attempts = new AtomicInteger();
        coordinator.subscribe(key, ignored -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("first attempt");
        });
        CacheInvalidationMessage message = message(key, "remote", 900L);

        assertEquals(CrossServerCacheInvalidationCoordinator.ApplyStatus.RETRY_REQUIRED,
                coordinator.receive(message).status());
        assertEquals(CrossServerCacheInvalidationCoordinator.ApplyStatus.APPLIED,
                coordinator.receive(message).status());
        assertEquals(2, attempts.get());
    }

    @Test
    void localPhysicalCreationTimeDoesNotInheritObservedFutureClock() {
        AtomicLong clock = new AtomicLong(1_000L);
        CrossServerCacheInvalidationCoordinator coordinator = coordinator("local", clock);
        coordinator.receive(message(new CacheKey("balance", "remote"), "future", 50_000L));

        CacheInvalidationMessage local = coordinator.invalidate("balance", "local").message();

        assertTrue(local.version().epochMillis() >= 50_000L);
        assertEquals(1_000L, local.createdAtEpochMillis());
    }

    private static CrossServerCacheInvalidationCoordinator coordinator(String node, AtomicLong clock) {
        return new CrossServerCacheInvalidationCoordinator(node, UUID.randomUUID(), 32,
                Duration.ofMinutes(1), clock::get, System::nanoTime);
    }

    private static CacheInvalidationMessage message(CacheKey key, String node, long epoch) {
        return new CacheInvalidationMessage(UUID.randomUUID(), key,
                new CacheVersionStamp(epoch, 0L, node, UUID.randomUUID()), epoch);
    }
}
