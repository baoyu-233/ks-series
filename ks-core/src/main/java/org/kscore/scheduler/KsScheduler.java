package org.kscore.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Paper/Folia scheduler boundary used by ks-Series.
 *
 * <p>Callers must choose the owner of the state they are about to touch:
 * global server state, one region, one entity, or no Bukkit state at all.
 * The Paper scheduler APIs used here are available on both Paper and Folia.</p>
 */
public final class KsScheduler {
    private KsScheduler() {
    }

    public static void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(requirePlugin(plugin), requireTask(task));
    }

    public static ScheduledTask runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
        requirePositive(delayTicks, "delayTicks");
        return Bukkit.getGlobalRegionScheduler().runDelayed(
                requirePlugin(plugin), ignored -> requireTask(task).run(), delayTicks);
    }

    public static ScheduledTask runAsync(Plugin plugin, Runnable task) {
        return Bukkit.getAsyncScheduler().runNow(
                requirePlugin(plugin), ignored -> requireTask(task).run());
    }

    public static ScheduledTask runAsyncAtFixedRate(
            Plugin plugin, Runnable task, Duration initialDelay, Duration period) {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(period, "period");
        if (initialDelay.isNegative()) throw new IllegalArgumentException("initialDelay must not be negative");
        if (period.isZero() || period.isNegative()) throw new IllegalArgumentException("period must be positive");
        return Bukkit.getAsyncScheduler().runAtFixedRate(
                requirePlugin(plugin), ignored -> requireTask(task).run(),
                initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    public static void runRegion(Plugin plugin, Location location, Runnable task) {
        Objects.requireNonNull(location, "location");
        Bukkit.getRegionScheduler().execute(requirePlugin(plugin), location.clone(), requireTask(task));
    }

    /**
     * Runs against the entity's current owning region. The retired callback is
     * used when the entity is removed before execution and must not touch it.
     */
    public static boolean runEntity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
        Objects.requireNonNull(entity, "entity");
        return entity.getScheduler().execute(
                requirePlugin(plugin), requireTask(task), Objects.requireNonNull(retired, "retired"), 1L);
    }

    public static void cancelAsyncTasks(Plugin plugin) {
        Bukkit.getAsyncScheduler().cancelTasks(requirePlugin(plugin));
    }

    public static void cancelGlobalTasks(Plugin plugin) {
        Bukkit.getGlobalRegionScheduler().cancelTasks(requirePlugin(plugin));
    }

    private static Plugin requirePlugin(Plugin plugin) {
        return Objects.requireNonNull(plugin, "plugin");
    }

    private static Runnable requireTask(Runnable task) {
        return Objects.requireNonNull(task, "task");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
