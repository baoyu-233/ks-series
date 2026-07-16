package org.kseries.compat.vulcan;

import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.kseries.compat.CompatModule;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vulcan has no target-type exemption for KillAura checks. This module cancels
 * only flags immediately following a confirmed ArmorStand hit.
 */
public final class VulcanArmorStandCompatModule implements CompatModule, Listener {

    private static final String FLAG_EVENT = "me.frep.vulcan.api.event.VulcanFlagEvent";

    private final JavaPlugin plugin;
    private final Map<UUID, Long> lastArmorStandHit = new ConcurrentHashMap<>();
    private final AtomicLong ignoredFlags = new AtomicLong();

    private boolean enabled;
    private boolean registered;
    private boolean resetViolations;
    private boolean debug;
    private long hitWindowNanos;
    private Method getPlayer;
    private Method getCheck;
    private Method setCancelled;
    private Method getCheckName;
    private Method getCheckCategory;
    private Method setViolationLevel;

    public VulcanArmorStandCompatModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "vulcan-armorstand-compat";
    }

    @Override
    public String displayName() {
        return "Vulcan ArmorStand Compat";
    }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerVulcanFlagListener();
    }

    @Override
    public void reload() {
        String path = "modules.vulcan-armorstand-compat.";
        enabled = plugin.getConfig().getBoolean(path + "enabled", true);
        resetViolations = plugin.getConfig().getBoolean(path + "reset-killaura-violations", true);
        debug = plugin.getConfig().getBoolean(path + "debug", false);
        long windowMillis = Math.max(50L, plugin.getConfig().getLong(path + "armorstand-hit-window-millis", 200L));
        hitWindowNanos = windowMillis * 1_000_000L;
    }

    @Override
    public void disable() {
        lastArmorStandHit.clear();
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id());
        out.put("enabled", enabled);
        out.put("vulcanHooked", registered);
        out.put("hitWindowMillis", hitWindowNanos / 1_000_000L);
        out.put("ignoredFlags", ignoredFlags.get());
        return out;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onArmorStandDamage(EntityDamageByEntityEvent event) {
        if (!enabled || !(event.getDamager() instanceof Player player) || !(event.getEntity() instanceof ArmorStand)) {
            return;
        }
        lastArmorStandHit.put(player.getUniqueId(), System.nanoTime());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastArmorStandHit.remove(event.getPlayer().getUniqueId());
    }

    @SuppressWarnings("unchecked")
    private void registerVulcanFlagListener() {
        if (registered) return;
        Plugin vulcan = Bukkit.getPluginManager().getPlugin("Vulcan");
        if (vulcan == null || !vulcan.isEnabled()) {
            plugin.getLogger().info("Vulcan not found; ArmorStand KillAura compatibility is inactive.");
            return;
        }

        try {
            ClassLoader loader = vulcan.getClass().getClassLoader();
            Class<?> rawEventClass = Class.forName(FLAG_EVENT, true, loader);
            if (!Event.class.isAssignableFrom(rawEventClass)) {
                throw new IllegalStateException("VulcanFlagEvent is not a Bukkit event");
            }

            getPlayer = rawEventClass.getMethod("getPlayer");
            getCheck = rawEventClass.getMethod("getCheck");
            setCancelled = rawEventClass.getMethod("setCancelled", boolean.class);
            Class<?> checkClass = getCheck.getReturnType();
            getCheckName = checkClass.getMethod("getName");
            getCheckCategory = checkClass.getMethod("getCategory");
            setViolationLevel = checkClass.getMethod("setVl", int.class);

            EventExecutor executor = (listener, event) -> onVulcanFlag(event);
            Bukkit.getPluginManager().registerEvent(
                (Class<? extends Event>) rawEventClass,
                this,
                EventPriority.HIGHEST,
                executor,
                plugin,
                false
            );
            registered = true;
            plugin.getLogger().info("Vulcan ArmorStand KillAura compatibility enabled.");
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Could not hook Vulcan ArmorStand compatibility: " + ex.getMessage());
        }
    }

    private void onVulcanFlag(Event event) {
        if (!enabled) return;
        try {
            Object playerValue = getPlayer.invoke(event);
            Object check = getCheck.invoke(event);
            if (!(playerValue instanceof Player player) || check == null || !isKillAura(check)) return;

            Long hitAt = lastArmorStandHit.get(player.getUniqueId());
            if (hitAt == null || System.nanoTime() - hitAt > hitWindowNanos) return;

            setCancelled.invoke(event, true);
            if (resetViolations) setViolationLevel.invoke(check, 0);
            ignoredFlags.incrementAndGet();
            if (debug) {
                plugin.getLogger().info("Ignored " + getCheckName.invoke(check) + " flag after ArmorStand hit: " + player.getName());
            }
        } catch (ReflectiveOperationException ex) {
            if (debug) plugin.getLogger().warning("Vulcan ArmorStand compatibility failed: " + ex.getMessage());
        }
    }

    private boolean isKillAura(Object check) throws ReflectiveOperationException {
        String name = String.valueOf(getCheckName.invoke(check)).toLowerCase(Locale.ROOT);
        String category = String.valueOf(getCheckCategory.invoke(check)).toLowerCase(Locale.ROOT);
        return name.replace(" ", "").contains("killaura") || category.replace(" ", "").contains("killaura");
    }
}
