package org.kseco.scheduler;

import org.bukkit.entity.Entity;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EcoSchedulerTest {
    @Test
    void ownedEntityRunsInlineWithoutRedispatch() {
        FakeBackend backend = new FakeBackend();
        backend.entityOwned = true;
        EcoScheduler scheduler = new EcoScheduler(backend);
        AtomicInteger calls = new AtomicInteger();

        assertTrue(scheduler.runEntity(entity(), calls::incrementAndGet, () -> fail("retired")));
        assertEquals(1, calls.get());
        assertEquals(0, backend.entityDispatches);
    }

    @Test
    void foreignEntityUsesEntityScheduler() {
        FakeBackend backend = new FakeBackend();
        EcoScheduler scheduler = new EcoScheduler(backend);
        AtomicInteger calls = new AtomicInteger();

        assertTrue(scheduler.runEntity(entity(), calls::incrementAndGet, () -> fail("retired")));
        assertEquals(1, calls.get());
        assertEquals(1, backend.entityDispatches);
    }

    @Test
    void timedOutGlobalCallCancelsScheduledTask() {
        FakeBackend backend = new FakeBackend();
        backend.deferGlobal = true;
        EcoScheduler scheduler = new EcoScheduler(backend);

        assertThrows(TimeoutException.class,
                () -> scheduler.callGlobal(() -> "late", Duration.ofMillis(5)));
        assertTrue(backend.cancelled.get());
    }

    @Test
    void rejectsInvalidPeriods() {
        EcoScheduler scheduler = new EcoScheduler(new FakeBackend());
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.runGlobalTimer(() -> { }, 1L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.runAsyncTimer(() -> { }, Duration.ZERO, Duration.ZERO));
    }

    private static Entity entity() {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "test-entity";
                    default -> null;
                });
    }

    private static final class FakeBackend implements EcoScheduler.Backend {
        boolean entityOwned;
        boolean deferGlobal;
        int entityDispatches;
        final AtomicBoolean cancelled = new AtomicBoolean();

        @Override public boolean isGlobalThread() { return false; }
        @Override public boolean isEntityThread(Entity entity) { return entityOwned; }
        @Override public boolean isRegionThread(Location location) { return false; }
        @Override public EcoScheduler.TaskHandle global(Runnable task) {
            if (!deferGlobal) task.run();
            return () -> cancelled.set(true);
        }
        @Override public EcoScheduler.TaskHandle globalLater(Runnable task, long delayTicks) {
            task.run(); return () -> cancelled.set(true);
        }
        @Override public EcoScheduler.TaskHandle globalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
            return () -> cancelled.set(true);
        }
        @Override public EcoScheduler.TaskHandle async(Runnable task) {
            task.run(); return () -> cancelled.set(true);
        }
        @Override public EcoScheduler.TaskHandle asyncLater(Runnable task, Duration delay) {
            task.run(); return () -> cancelled.set(true);
        }
        @Override public EcoScheduler.TaskHandle asyncTimer(Runnable task, Duration initialDelay, Duration period) {
            return () -> cancelled.set(true);
        }
        @Override public boolean entity(Entity entity, Runnable task, Runnable retired) {
            entityDispatches++; task.run(); return true;
        }
        @Override public EcoScheduler.TaskHandle entityLater(
                Entity entity, Runnable task, Runnable retired, long delayTicks) {
            entityDispatches++;
            if (!deferGlobal) task.run();
            return () -> cancelled.set(true);
        }
        @Override public EcoScheduler.TaskHandle region(Location location, Runnable task) {
            task.run(); return () -> cancelled.set(true);
        }
        @Override public EcoScheduler.TaskHandle regionLater(Location location, Runnable task, long delayTicks) {
            task.run(); return () -> cancelled.set(true);
        }
    }
}
