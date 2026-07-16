package org.kseries.compat.bot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.kseries.compat.CompatModule;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps a zombified piglin angry at a KS-managed Leaves bot after the bot attacks it. */
public final class ZombifiedPiglinAggroModule implements CompatModule, Listener {

    private final JavaPlugin plugin;
    private final BotManagerModule botManager;
    private final Map<UUID, UUID> targets = new LinkedHashMap<>();
    private BukkitTask refreshTask;

    private boolean enabled;
    private boolean debug;
    private int refreshTicks;
    private int angerTicks;
    private int maxTrackedPiglins;

    public ZombifiedPiglinAggroModule(JavaPlugin plugin, BotManagerModule botManager) {
        this.plugin = plugin;
        this.botManager = botManager;
    }

    @Override
    public String id() {
        return "zombified-piglin-aggro";
    }

    @Override
    public String displayName() {
        return "Zombified Piglin Bot Aggro";
    }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        restartTask();
    }

    @Override
    public void reload() {
        String path = "modules.zombified-piglin-aggro.";
        enabled = plugin.getConfig().getBoolean(path + "enabled", true);
        debug = plugin.getConfig().getBoolean(path + "debug", false);
        refreshTicks = Math.max(1, plugin.getConfig().getInt(path + "refresh-ticks", 20));
        angerTicks = Math.max(20, plugin.getConfig().getInt(path + "anger-ticks", 1200));
        maxTrackedPiglins = Math.max(1, plugin.getConfig().getInt(path + "max-tracked-piglins", 128));
        if (refreshTask != null) restartTask();
    }

    @Override
    public void disable() {
        if (refreshTask != null) refreshTask.cancel();
        refreshTask = null;
        targets.clear();
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id());
        out.put("enabled", enabled);
        out.put("trackedPiglins", targets.size());
        out.put("refreshTicks", refreshTicks);
        out.put("angerTicks", angerTicks);
        return out;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBotDamagesPiglin(EntityDamageByEntityEvent event) {
        if (!enabled || !(event.getDamager() instanceof Player bot) || !(event.getEntity() instanceof PigZombie piglin)) {
            return;
        }
        if (!botManager.isManagedBot(bot)) return;
        track(piglin, bot);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPiglinTargetsBot(EntityTargetLivingEntityEvent event) {
        if (!enabled || !(event.getEntity() instanceof PigZombie piglin) || !(event.getTarget() instanceof Player bot)) {
            return;
        }
        if (botManager.isManagedBot(bot)) track(piglin, bot);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void keepTrackedTarget(EntityTargetLivingEntityEvent event) {
        if (!enabled || !(event.getEntity() instanceof PigZombie piglin)) return;
        UUID botId = targets.get(piglin.getUniqueId());
        if (botId == null) return;
        Player bot = Bukkit.getPlayer(botId);
        if (bot == null || !bot.isOnline() || !botManager.isManagedBot(bot)) {
            targets.remove(piglin.getUniqueId());
            return;
        }
        if (!bot.equals(event.getTarget())) event.setTarget(bot);
    }

    @EventHandler
    public void onPiglinDeath(EntityDeathEvent event) {
        targets.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onBotQuit(PlayerQuitEvent event) {
        UUID botId = event.getPlayer().getUniqueId();
        targets.entrySet().removeIf(entry -> entry.getValue().equals(botId));
    }

    private void restartTask() {
        if (refreshTask != null) refreshTask.cancel();
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshTargets, refreshTicks, refreshTicks);
    }

    private void track(PigZombie piglin, Player bot) {
        if (!targets.containsKey(piglin.getUniqueId()) && targets.size() >= maxTrackedPiglins) {
            if (debug) plugin.getLogger().warning("Piglin aggro tracker is full; ignoring " + piglin.getUniqueId());
            return;
        }
        targets.put(piglin.getUniqueId(), bot.getUniqueId());
        applyTarget(piglin, bot);
    }

    private void refreshTargets() {
        if (!enabled || targets.isEmpty()) return;
        Iterator<Map.Entry<UUID, UUID>> iterator = targets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, UUID> entry = iterator.next();
            Entity rawPiglin = Bukkit.getEntity(entry.getKey());
            Player bot = Bukkit.getPlayer(entry.getValue());
            if (!(rawPiglin instanceof PigZombie piglin) || piglin.isDead() || bot == null || !bot.isOnline()
                || !botManager.isManagedBot(bot) || !piglin.getWorld().equals(bot.getWorld())) {
                iterator.remove();
                continue;
            }
            applyTarget(piglin, bot);
        }
    }

    private void applyTarget(PigZombie piglin, Player bot) {
        piglin.setAngry(true);
        piglin.setAnger(angerTicks);
        if (!bot.equals(piglin.getTarget())) piglin.setTarget(bot);
    }
}
