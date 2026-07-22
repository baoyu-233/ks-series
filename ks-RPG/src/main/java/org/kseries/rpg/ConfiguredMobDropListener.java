package org.kseries.rpg;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Server-thread drop delivery for every configured scoreboard-tagged mob table. */
final class ConfiguredMobDropListener implements Listener {
    private static final double MAX_REWARD_DISTANCE_SQUARED = 64.0 * 64.0;
    private static final long CONTRIBUTION_TTL_NANOS = Duration.ofMinutes(10).toNanos();
    private static final long PRUNE_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final KsRpg plugin;
    private final MmoItemsBridge mmoItems;
    private final Map<UUID, EncounterContributions> contributions = new HashMap<>();
    private final DropRecipientRotation recipientRotation = new DropRecipientRotation();
    private long nextPruneNanos;

    ConfiguredMobDropListener(KsRpg plugin, MmoItemsBridge mmoItems) {
        this.plugin = plugin;
        this.mmoItems = mmoItems;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || event.getFinalDamage() <= 0.0) return;
        Player attacker = playerDamager(event);
        if (attacker == null || attacker.getUniqueId().equals(entity.getUniqueId()) || !hasConfiguredDrops(entity)) {
            return;
        }

        long now = System.nanoTime();
        pruneExpired(now);
        contributions.computeIfAbsent(entity.getUniqueId(), ignored -> new EncounterContributions())
                .record(attacker.getUniqueId(), now);
    }

    @EventHandler
    void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        EncounterContributions encounter = contributions.remove(entity.getUniqueId());
        Player killer = entity.getKiller();
        if (killer == null) return;
        Map<UUID, Player> eligiblePlayers = eligiblePlayers(entity, encounter, killer);
        if (eligiblePlayers.isEmpty()) return;

        for (String tag : entity.getScoreboardTags()) {
            for (CombatCatalog.MobDropDefinition table : plugin.combatCatalog().dropsForTag(tag)) {
                for (CombatCatalog.DropEntry drop : table.drops()) {
                    if (ThreadLocalRandom.current().nextDouble() >= drop.chance()) continue;
                    ItemStack item = drop.item().create(mmoItems);
                    if (item == null) continue;
                    String tableId = table.scoreboardTag() + "\u0000" + table.key();
                    Player recipient = recipientRotation.next(tableId, List.copyOf(eligiblePlayers.keySet()))
                            .map(eligiblePlayers::get).orElse(null);
                    if (recipient == null) continue;
                    give(recipient, item, ThreadLocalRandom.current().nextInt(drop.minimum(), drop.maximum() + 1));
                }
            }
        }
    }

    private boolean hasConfiguredDrops(LivingEntity entity) {
        return entity.getScoreboardTags().stream()
                .anyMatch(tag -> !plugin.combatCatalog().dropsForTag(tag).isEmpty());
    }

    private Map<UUID, Player> eligiblePlayers(LivingEntity entity, EncounterContributions encounter, Player killer) {
        List<UUID> participantIds = new ArrayList<>();
        if (encounter != null) participantIds.addAll(encounter.participants());
        if (!participantIds.contains(killer.getUniqueId())) participantIds.add(killer.getUniqueId());

        Map<UUID, Player> eligible = new LinkedHashMap<>();
        for (UUID playerId : participantIds) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (!player.getWorld().equals(entity.getWorld())) continue;
            if (player.getLocation().distanceSquared(entity.getLocation()) > MAX_REWARD_DISTANCE_SQUARED) continue;
            eligible.put(playerId, player);
        }
        return eligible;
    }

    private static Player playerDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private void pruneExpired(long now) {
        if (now < nextPruneNanos) return;
        contributions.values().removeIf(encounter -> now - encounter.lastTouchedNanos() > CONTRIBUTION_TTL_NANOS);
        nextPruneNanos = now + PRUNE_INTERVAL_NANOS;
    }

    private void give(Player player, ItemStack item, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            ItemStack stack = item.clone();
            int count = Math.min(remaining, stack.getMaxStackSize());
            stack.setAmount(count);
            remaining -= count;
            player.getInventory().addItem(stack).values().forEach(leftover -> player.getWorld().dropItemNaturally(
                    player.getLocation(), leftover));
        }
    }

    private static final class EncounterContributions {
        private final LinkedHashMap<UUID, Boolean> participants = new LinkedHashMap<>();
        private long lastTouchedNanos;

        void record(UUID playerId, long now) {
            participants.putIfAbsent(playerId, Boolean.TRUE);
            lastTouchedNanos = now;
        }

        List<UUID> participants() {
            return List.copyOf(participants.keySet());
        }

        long lastTouchedNanos() {
            return lastTouchedNanos;
        }
    }
}
