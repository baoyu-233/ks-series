package org.kseries.botguard;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KsBotGuard extends JavaPlugin implements Listener {

    private static final long LISTENER_RECONCILE_PERIOD_TICKS = 20L;

    private final Map<RegisteredListener, WrappedListener> shieldWrappers = new IdentityHashMap<>();
    private final Map<RegisteredListener, RegisteredListener> wrappersByOriginal = new IdentityHashMap<>();

    private boolean shieldMythicLibFromServerBotDamage;
    private boolean shieldMythicLibFromServerBotActions;
    private boolean includeServerBotProjectiles;
    private boolean debug;
    private BukkitTask listenerReconcileTask;
    private BukkitTask pendingShieldRefresh;

    private record WrappedListener(HandlerList handlerList, RegisteredListener original) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalConfig();
        getServer().getPluginManager().registerEvents(this, this);
        requestMmoListenerShieldRefresh();
        listenerReconcileTask = getServer().getScheduler().runTaskTimer(
            this,
            this::installMmoListenerShield,
            LISTENER_RECONCILE_PERIOD_TICKS,
            LISTENER_RECONCILE_PERIOD_TICKS);
        getLogger().info("Enabled. Leaves ServerBot MMO listener shield damage=" + shieldMythicLibFromServerBotDamage
            + " actions=" + shieldMythicLibFromServerBotActions);
    }

    @Override
    public void onDisable() {
        cancelShieldTasks();
        restoreMmoListeners();
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        reloadLocalConfig();
        installMmoListenerShield();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("ksbotguard")) return false;
        if (!sender.hasPermission("ksbotguard.reload")) {
            sender.sendMessage("You do not have permission to configure ks-BotGuard.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("ks-BotGuard protection settings reloaded.");
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("protect")) {
            String path = getProtectionPath(args[1]);
            Boolean enabled = parseBoolean(args[2]);
            if (path == null || enabled == null) {
                sender.sendMessage("Unknown protection target or state. Use /" + label + " status.");
                return true;
            }
            getConfig().set(path, enabled);
            saveConfig();
            reloadLocalConfig();
            sender.sendMessage("Bot protection " + args[1].toLowerCase() + " = " + enabled + ".");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("Damage=" + shieldMythicLibFromServerBotDamage
                + ", actions=" + shieldMythicLibFromServerBotActions
                + ", item-consume=" + isActionProtectionEnabled("item-consume"));
            sender.sendMessage("Use /" + label + " protect <target> <on|off>.");
            return true;
        }
        sender.sendMessage("Usage: /" + label + " reload|status|protect <target> <on|off>");
        return true;
    }

    private String getProtectionPath(String target) {
        String key = target.toLowerCase(java.util.Locale.ROOT);
        if (key.equals("damage")) return "shield-mythiclib-from-leaves-serverbot-damage";
        if (key.equals("actions")) return "shield-mythiclib-from-leaves-serverbot-actions";
        if (key.equals("projectiles")) return "include-projectiles-shot-by-serverbots";
        if (key.equals("entity-damage") || key.equals("entity-damage-by-entity")) return "damage." + key;
        return switch (key) {
            case "block-break", "block-place", "interact", "interact-entity", "interact-at-entity",
                "bucket-empty", "bucket-fill", "item-drop", "fish", "swap-hand-items", "item-consume",
                "shear-entity", "projectile-launch", "shoot-bow", "toggle-sneak", "experience-change",
                "vehicle-enter", "vehicle-exit" -> "actions." + key;
            default -> null;
        };
    }

    private Boolean parseBoolean(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true", "enable", "enabled" -> true;
            case "off", "false", "disable", "disabled" -> false;
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (isProtectedMmoPlugin(event.getPlugin().getName())) {
            requestMmoListenerShieldRefresh();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (isProtectedMmoPlugin(event.getPlugin().getName())) {
            discardMmoListeners(event.getPlugin());
        }
    }

    private void reloadLocalConfig() {
        shieldMythicLibFromServerBotDamage = getConfig().getBoolean(
            "shield-mythiclib-from-leaves-serverbot-damage",
            getConfig().getBoolean("cancel-leaves-serverbot-damage", true));
        shieldMythicLibFromServerBotActions = getConfig().getBoolean(
            "shield-mythiclib-from-leaves-serverbot-actions", true);
        includeServerBotProjectiles = getConfig().getBoolean(
            "include-projectiles-shot-by-serverbots",
            getConfig().getBoolean("cancel-projectiles-shot-by-serverbots", true));
        debug = getConfig().getBoolean("debug", false);
    }

    private void installMmoListenerShield() {
        pruneStaleMmoListeners();
        if (!isPluginEnabled("MythicLib") && !isPluginEnabled("MMOCore")) return;

        int wrapped = 0;
        for (HandlerList handlers : protectedHandlerLists()) wrapped += wrapMmoListeners(handlers);
        if (wrapped > 0) {
            getLogger().info("Wrapped " + wrapped + " MMO listener(s) for Leaves ServerBot filtering.");
        }
    }

    private int wrapMmoListeners(HandlerList handlerList) {
        List<RegisteredListener> originals = new ArrayList<>();
        List<RegisteredListener> duplicateOriginals = new ArrayList<>();
        for (RegisteredListener registered : handlerList.getRegisteredListeners()) {
            if (shieldWrappers.containsKey(registered)) continue;
            RegisteredListener existingWrapper = wrappersByOriginal.get(registered);
            if (existingWrapper != null) {
                if (isListenerRegistered(handlerList, existingWrapper)) duplicateOriginals.add(registered);
                continue;
            }
            if (!isProtectedMmoPlugin(registered.getPlugin().getName())) continue;
            if (!registered.getPlugin().isEnabled()) continue;
            originals.add(registered);
        }

        for (RegisteredListener duplicate : duplicateOriginals) handlerList.unregister(duplicate);

        int wrappedCount = 0;
        for (RegisteredListener original : originals) {
            if (!isListenerRegistered(handlerList, original)) continue;
            handlerList.unregister(original);
            RegisteredListener wrapper = new RegisteredListener(
                original.getListener(),
                (listener, event) -> callMmoListenerUnlessServerBot(original, event),
                original.getPriority(),
                original.getPlugin(),
                original.isIgnoringCancelled());
            handlerList.register(wrapper);
            shieldWrappers.put(wrapper, new WrappedListener(handlerList, original));
            wrappersByOriginal.put(original, wrapper);
            wrappedCount++;
        }
        return wrappedCount;
    }

    private void callMmoListenerUnlessServerBot(RegisteredListener original, Event event) throws EventException {
        if (isLeavesServerBotEvent(event)) {
            if (debug) {
                getLogger().info("Skipped " + original.getPlugin().getName() + " listener "
                    + original.getListener().getClass().getName() + " for a Leaves ServerBot event.");
            }
            return;
        }
        original.callEvent(event);
    }

    private boolean isLeavesServerBotEvent(Event event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            return isDamageProtectionEnabled("entity-damage-by-entity")
                && (isLeavesServerBot(byEntity.getEntity()) || isLeavesServerBotDamageSource(byEntity.getDamager()));
        }
        if (event instanceof EntityDamageEvent damageEvent) {
            return isDamageProtectionEnabled("entity-damage") && isLeavesServerBot(damageEvent.getEntity());
        }
        String action = getActionKey(event);
        if (action == null || !isActionProtectionEnabled(action)) return false;
        if (event instanceof BlockBreakEvent blockBreak) return isLeavesServerBot(blockBreak.getPlayer());
        if (event instanceof BlockPlaceEvent blockPlace) return isLeavesServerBot(blockPlace.getPlayer());
        if (event instanceof PlayerEvent playerEvent) return isLeavesServerBot(playerEvent.getPlayer());
        if (event instanceof ProjectileLaunchEvent launch) return isLeavesServerBotDamageSource(launch.getEntity());
        if (event instanceof EntityShootBowEvent shoot) return isLeavesServerBot(shoot.getEntity());
        if (event instanceof VehicleEnterEvent enter) return isLeavesServerBot(enter.getEntered());
        return event instanceof VehicleExitEvent exit && isLeavesServerBot(exit.getExited());
    }

    private boolean isDamageProtectionEnabled(String key) {
        return shieldMythicLibFromServerBotDamage
            && getConfig().getBoolean("damage." + key, shieldMythicLibFromServerBotDamage);
    }

    private boolean isActionProtectionEnabled(String key) {
        return shieldMythicLibFromServerBotActions
            && getConfig().getBoolean("actions." + key, shieldMythicLibFromServerBotActions);
    }

    private String getActionKey(Event event) {
        if (event instanceof BlockBreakEvent) return "block-break";
        if (event instanceof BlockPlaceEvent) return "block-place";
        if (event instanceof PlayerInteractAtEntityEvent) return "interact-at-entity";
        if (event instanceof PlayerInteractEntityEvent) return "interact-entity";
        if (event instanceof PlayerInteractEvent) return "interact";
        if (event instanceof PlayerBucketEmptyEvent) return "bucket-empty";
        if (event instanceof PlayerBucketFillEvent) return "bucket-fill";
        if (event instanceof PlayerDropItemEvent) return "item-drop";
        if (event instanceof PlayerFishEvent) return "fish";
        if (event instanceof PlayerSwapHandItemsEvent) return "swap-hand-items";
        if (event instanceof PlayerToggleSneakEvent) return "toggle-sneak";
        if (event instanceof PlayerExpChangeEvent) return "experience-change";
        if (event instanceof PlayerItemConsumeEvent) return "item-consume";
        if (event instanceof PlayerShearEntityEvent) return "shear-entity";
        if (event instanceof ProjectileLaunchEvent) return "projectile-launch";
        if (event instanceof EntityShootBowEvent) return "shoot-bow";
        if (event instanceof VehicleEnterEvent) return "vehicle-enter";
        if (event instanceof VehicleExitEvent) return "vehicle-exit";
        return null;
    }

    private Set<HandlerList> protectedHandlerLists() {
        Set<HandlerList> lists = new LinkedHashSet<>();
        lists.add(EntityDamageEvent.getHandlerList());
        lists.add(EntityDamageByEntityEvent.getHandlerList());
        lists.add(BlockBreakEvent.getHandlerList());
        lists.add(BlockPlaceEvent.getHandlerList());
        lists.add(PlayerInteractEvent.getHandlerList());
        lists.add(PlayerInteractEntityEvent.getHandlerList());
        lists.add(PlayerInteractAtEntityEvent.getHandlerList());
        lists.add(PlayerBucketEmptyEvent.getHandlerList());
        lists.add(PlayerBucketFillEvent.getHandlerList());
        lists.add(PlayerDropItemEvent.getHandlerList());
        lists.add(PlayerFishEvent.getHandlerList());
        lists.add(PlayerSwapHandItemsEvent.getHandlerList());
        lists.add(PlayerToggleSneakEvent.getHandlerList());
        lists.add(PlayerExpChangeEvent.getHandlerList());
        lists.add(PlayerItemConsumeEvent.getHandlerList());
        lists.add(PlayerShearEntityEvent.getHandlerList());
        lists.add(ProjectileLaunchEvent.getHandlerList());
        lists.add(EntityShootBowEvent.getHandlerList());
        lists.add(VehicleEnterEvent.getHandlerList());
        lists.add(VehicleExitEvent.getHandlerList());
        return lists;
    }

    private void restoreMmoListeners() {
        for (Map.Entry<RegisteredListener, WrappedListener> entry : shieldWrappers.entrySet()) {
            WrappedListener wrapped = entry.getValue();
            boolean wrapperRegistered = isListenerRegistered(wrapped.handlerList(), entry.getKey());
            boolean originalRegistered = isListenerRegistered(wrapped.handlerList(), wrapped.original());
            if (wrapperRegistered) wrapped.handlerList().unregister(entry.getKey());
            if (shouldRestoreOriginal(
                wrapperRegistered,
                wrapped.original().getPlugin().isEnabled(),
                originalRegistered)) {
                wrapped.handlerList().register(wrapped.original());
            }
        }
        shieldWrappers.clear();
        wrappersByOriginal.clear();
    }

    private void discardMmoListeners(Plugin plugin) {
        var iterator = shieldWrappers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RegisteredListener, WrappedListener> entry = iterator.next();
            WrappedListener wrapped = entry.getValue();
            if (wrapped.original().getPlugin() != plugin) continue;
            if (isListenerRegistered(wrapped.handlerList(), entry.getKey())) {
                wrapped.handlerList().unregister(entry.getKey());
            }
            wrappersByOriginal.remove(wrapped.original());
            iterator.remove();
        }
    }

    private void pruneStaleMmoListeners() {
        var iterator = shieldWrappers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RegisteredListener, WrappedListener> entry = iterator.next();
            WrappedListener wrapped = entry.getValue();
            boolean wrapperRegistered = isListenerRegistered(wrapped.handlerList(), entry.getKey());
            if (wrapperRegistered && wrapped.original().getPlugin().isEnabled()) continue;

            if (wrapperRegistered) wrapped.handlerList().unregister(entry.getKey());
            wrappersByOriginal.remove(wrapped.original());
            iterator.remove();
        }
    }

    private void requestMmoListenerShieldRefresh() {
        if (pendingShieldRefresh != null) return;
        pendingShieldRefresh = getServer().getScheduler().runTask(this, () -> {
            pendingShieldRefresh = null;
            if (isEnabled()) installMmoListenerShield();
        });
    }

    private void cancelShieldTasks() {
        if (pendingShieldRefresh != null) {
            pendingShieldRefresh.cancel();
            pendingShieldRefresh = null;
        }
        if (listenerReconcileTask != null) {
            listenerReconcileTask.cancel();
            listenerReconcileTask = null;
        }
    }

    private static boolean isListenerRegistered(HandlerList handlerList, RegisteredListener target) {
        for (RegisteredListener registered : handlerList.getRegisteredListeners()) {
            if (registered == target) return true;
        }
        return false;
    }

    static boolean shouldRestoreOriginal(
        boolean wrapperRegistered,
        boolean pluginEnabled,
        boolean originalRegistered
    ) {
        return wrapperRegistered && pluginEnabled && !originalRegistered;
    }

    private boolean isPluginEnabled(String pluginName) {
        return Bukkit.getPluginManager().getPlugin(pluginName) != null
            && Bukkit.getPluginManager().getPlugin(pluginName).isEnabled();
    }

    private static boolean isProtectedMmoPlugin(String pluginName) {
        return "MythicLib".equals(pluginName) || "MMOCore".equals(pluginName);
    }

    private boolean isLeavesServerBotDamageSource(Entity entity) {
        if (isLeavesServerBot(entity)) return true;
        if (!includeServerBotProjectiles || !(entity instanceof Projectile projectile)) return false;

        ProjectileSource shooter = projectile.getShooter();
        return shooter instanceof Entity shooterEntity && isLeavesServerBot(shooterEntity);
    }

    private boolean isLeavesServerBot(Entity entity) {
        Class<?> type = entity.getClass();
        while (type != null) {
            String name = type.getName();
            if ("org.leavesmc.leaves.bot.ServerBot".equals(name)
                || name.startsWith("org.leavesmc.leaves.bot.")
                || name.startsWith("org.leavesmc.leaves.entity.bot.")) {
                return true;
            }
            for (Class<?> itf : type.getInterfaces()) {
                if ("org.leavesmc.leaves.entity.bot.Bot".equals(itf.getName())) return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }
}
