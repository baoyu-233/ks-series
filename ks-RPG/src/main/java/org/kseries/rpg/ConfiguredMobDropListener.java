package org.kseries.rpg;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/** Server-thread drop delivery for every configured scoreboard-tagged mob table. */
final class ConfiguredMobDropListener implements Listener {
    private final KsRpg plugin;
    private final MmoItemsBridge mmoItems;

    ConfiguredMobDropListener(KsRpg plugin, MmoItemsBridge mmoItems) {
        this.plugin = plugin;
        this.mmoItems = mmoItems;
    }

    @EventHandler(ignoreCancelled = true)
    void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null) return;
        for (String tag : entity.getScoreboardTags()) {
            for (CombatCatalog.MobDropDefinition table : plugin.combatCatalog().dropsForTag(tag)) {
                for (CombatCatalog.DropEntry drop : table.drops()) {
                    if (ThreadLocalRandom.current().nextDouble() >= drop.chance()) continue;
                    give(killer, drop.item(), ThreadLocalRandom.current().nextInt(drop.minimum(), drop.maximum() + 1));
                }
            }
        }
    }

    private void give(Player player, CombatCatalog.ItemRef definition, int amount) {
        ItemStack item = definition.create(mmoItems);
        if (item == null) return;
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
}
