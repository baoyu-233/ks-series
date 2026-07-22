package org.kseco.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Explicit Paper/Folia scheduling boundary for ks-Eco.
 *
 * <p>Global tasks may only touch global lifecycle state. Player, inventory,
 * message and Vault work for an online player belongs to that player's entity
 * scheduler. SQL and pure computation belong to the async/database lanes.</p>
 */
public final class EcoScheduler {
    @FunctionalInterface
    public interface TaskHandle {
        void cancel();
    }

    interface Backend {
        boolean isGlobalThread();
        boolean isEntityThread(Entity entity);
        TaskHandle global(Runnable task);
        TaskHandle globalLater(Runnable task, long delayTicks);
        TaskHandle globalTimer(Runnable task, long initialDelayTicks, long periodTicks);
        TaskHandle async(Runnable task);
        TaskHandle asyncLater(Runnable task, Duration delay);
        TaskHandle asyncTimer(Runnable task, Duration initialDelay, Duration period);
        boolean entity(Entity entity, Runnable task, Runnable retired);
        TaskHandle entityLater(Entity entity, Runnable task, Runnable retired, long delayTicks);
    }

    private final Backend backend;

    public EcoScheduler(Plugin plugin) {
        this(new PaperBackend(Objects.requireNonNull(plugin, "plugin")));
    }

    EcoScheduler(Backend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public boolean isGlobalThread() {
        return backend.isGlobalThread();
    }

    public boolean isEntityThread(Entity entity) {
        return entity != null && backend.isEntityThread(entity);
    }

    public void requireEntityThread(Entity entity, String operation) {
        if (!isEntityThread(entity)) {
            throw new IllegalStateException(operation + " must run on the entity-owning region");
        }
    }

    public void runGlobal(Runnable task) {
        backend.global(Objects.requireNonNull(task, "task"));
    }

    public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
        return backend.globalLater(Objects.requireNonNull(task, "task"), positive(delayTicks, "delayTicks"));
    }

    public TaskHandle runGlobalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        return backend.globalTimer(Objects.requireNonNull(task, "task"),
                positive(initialDelayTicks, "initialDelayTicks"), positive(periodTicks, "periodTicks"));
    }

    public TaskHandle runAsync(Runnable task) {
        return backend.async(Objects.requireNonNull(task, "task"));
    }

    public TaskHandle runAsyncLater(Runnable task, Duration delay) {
        return backend.asyncLater(Objects.requireNonNull(task, "task"), positive(delay, "delay"));
    }

    public TaskHandle runAsyncTimer(Runnable task, Duration initialDelay, Duration period) {
        return backend.asyncTimer(Objects.requireNonNull(task, "task"),
                nonNegative(initialDelay, "initialDelay"), positive(period, "period"));
    }

    public boolean runEntity(Entity entity, Runnable task, Runnable retired) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(retired, "retired");
        if (backend.isEntityThread(entity)) {
            task.run();
            return true;
        }
        return backend.entity(entity, task, retired);
    }

    public boolean runPlayer(UUID playerId, Consumer<Player> task, Runnable unavailable) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unavailable, "unavailable");
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            unavailable.run();
            return false;
        }
        return runEntity(player, () -> task.accept(player), unavailable);
    }

    public boolean runPlayer(UUID playerId, Runnable task) {
        Objects.requireNonNull(task, "task");
        Player player = Bukkit.getPlayer(Objects.requireNonNull(playerId, "playerId"));
        return player != null && player.isOnline() && runEntity(player, task, () -> { });
    }

    /** Uses an online player's owner, or the global scheduler for offline-account work. */
    public void runEntityOrGlobal(UUID playerId, Runnable task) {
        Objects.requireNonNull(task, "task");
        Player player = Bukkit.getPlayer(Objects.requireNonNull(playerId, "playerId"));
        if (player != null && player.isOnline()) {
            if (!runEntity(player, task, () -> runGlobal(task))) runGlobal(task);
        } else {
            runGlobal(task);
        }
    }

    public TaskHandle runEntityLater(Entity entity, Runnable task, Runnable retired, long delayTicks) {
        return backend.entityLater(Objects.requireNonNull(entity, "entity"),
                Objects.requireNonNull(task, "task"), Objects.requireNonNull(retired, "retired"),
                positive(delayTicks, "delayTicks"));
    }

    public <T> T callGlobal(Callable<T> action, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(action, "action");
        if (backend.isGlobalThread()) return invoke(action);
        CompletableFuture<T> future = new CompletableFuture<>();
        TaskHandle scheduled = backend.global(() -> complete(future, action));
        return await(future, timeout, scheduled);
    }

    public <T> T callEntity(Entity entity, Callable<T> action, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(action, "action");
        if (backend.isEntityThread(entity)) return invoke(action);
        CompletableFuture<T> future = new CompletableFuture<>();
        TaskHandle scheduled = backend.entityLater(entity, () -> complete(future, action),
                () -> future.completeExceptionally(new IllegalStateException("entity retired before task execution")), 1L);
        return await(future, timeout, scheduled);
    }

    private static <T> T await(CompletableFuture<T> future, Duration timeout, TaskHandle scheduled)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return future.get(positive(timeout, "timeout").toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException failure) {
            future.cancel(true);
            scheduled.cancel();
            throw failure;
        }
    }

    private static <T> void complete(CompletableFuture<T> future, Callable<T> action) {
        try {
            future.complete(action.call());
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
        }
    }

    private static <T> T invoke(Callable<T> action) throws ExecutionException {
        try {
            return action.call();
        } catch (Throwable failure) {
            throw new ExecutionException(failure);
        }
    }

    private static long positive(long value, String name) {
        if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private static final class PaperBackend implements Backend {
        private final Plugin plugin;

        private PaperBackend(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override public boolean isGlobalThread() { return Bukkit.isGlobalTickThread(); }
        @Override public boolean isEntityThread(Entity entity) { return Bukkit.isOwnedByCurrentRegion(entity); }
        @Override public TaskHandle global(Runnable task) {
            ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
            return scheduled::cancel;
        }
        @Override public TaskHandle globalLater(Runnable task, long delayTicks) {
            ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), delayTicks);
            return scheduled::cancel;
        }
        @Override public TaskHandle globalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
            ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    plugin, ignored -> task.run(), initialDelayTicks, periodTicks);
            return scheduled::cancel;
        }
        @Override public TaskHandle async(Runnable task) {
            ScheduledTask scheduled = Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
            return scheduled::cancel;
        }
        @Override public TaskHandle asyncLater(Runnable task, Duration delay) {
            ScheduledTask scheduled = Bukkit.getAsyncScheduler().runDelayed(
                    plugin, ignored -> task.run(), delay.toMillis(), TimeUnit.MILLISECONDS);
            return scheduled::cancel;
        }
        @Override public TaskHandle asyncTimer(Runnable task, Duration initialDelay, Duration period) {
            ScheduledTask scheduled = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> task.run(),
                    initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
            return scheduled::cancel;
        }
        @Override public boolean entity(Entity entity, Runnable task, Runnable retired) {
            return entity.getScheduler().execute(plugin, task, retired, 1L);
        }
        @Override public TaskHandle entityLater(Entity entity, Runnable task, Runnable retired, long delayTicks) {
            ScheduledTask scheduled = entity.getScheduler().runDelayed(plugin, ignored -> task.run(), retired, delayTicks);
            return scheduled::cancel;
        }
    }
}
