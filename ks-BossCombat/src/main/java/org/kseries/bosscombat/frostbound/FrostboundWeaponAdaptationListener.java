package org.kseries.bosscombat.frostbound;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Applies Frostbound Conductor's MMOItems-type-specific weapon adaptation. */
public final class FrostboundWeaponAdaptationListener implements Listener {

    private final JavaPlugin plugin;
    private final AtomicLong reducedHits = new AtomicLong();

    private boolean enabled;
    private double reduction;
    private String bossTag;
    private Set<String> weaponTypes = Set.of();
    private Constructor<?> liveItemConstructor;
    private Method itemTypeMethod;
    private Method typeIdMethod;

    public FrostboundWeaponAdaptationListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        String path = "frostbound-weapon-adaptation.";
        enabled = plugin.getConfig().getBoolean(path + "enabled", true);
        reduction = Math.clamp(plugin.getConfig().getDouble(path + "damage-multiplier", 0.5D), 0D, 1D);
        bossTag = plugin.getConfig().getString(path + "boss-tag", "Frostbound_WeaponAdaptation");
        var configuredTypes = plugin.getConfig().getStringList(path + "weapon-types");
        if (configuredTypes.isEmpty()) {
            configuredTypes = java.util.List.of("HAMMER", "GREATHAMMER", "SPEAR", "LANCE");
        }
        weaponTypes = configuredTypes.stream()
            .map(type -> type.toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        hookMmoItems();
    }

    public void disable() {
        reducedHits.set(0L);
        liveItemConstructor = null;
        itemTypeMethod = null;
        typeIdMethod = null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossDamaged(EntityDamageByEntityEvent event) {
        if (!enabled || !event.getEntity().getScoreboardTags().contains(bossTag)) return;

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || !isAdaptedWeapon(attacker.getInventory().getItemInMainHand())) return;

        event.setDamage(event.getDamage() * reduction);
        reducedHits.incrementAndGet();
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (!(damager instanceof Projectile projectile)) return null;

        ProjectileSource shooter = projectile.getShooter();
        return shooter instanceof Player player ? player : null;
    }

    private boolean isAdaptedWeapon(ItemStack item) {
        if (item == null || item.getType().isAir() || liveItemConstructor == null) return false;

        try {
            Object liveItem = liveItemConstructor.newInstance(item);
            Object itemType = itemTypeMethod.invoke(liveItem);
            Object typeId = typeIdMethod.invoke(itemType);
            return typeId instanceof String value && weaponTypes.contains(value.toUpperCase(Locale.ROOT));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private void hookMmoItems() {
        liveItemConstructor = null;
        itemTypeMethod = null;
        typeIdMethod = null;

        Plugin mmoItems = Bukkit.getPluginManager().getPlugin("MMOItems");
        if (mmoItems == null || !mmoItems.isEnabled()) {
            plugin.getLogger().warning("MMOItems is unavailable; Frostbound weapon adaptation is inactive.");
            return;
        }

        try {
            ClassLoader loader = mmoItems.getClass().getClassLoader();
            Class<?> liveItemClass = Class.forName("net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem", true, loader);
            Class<?> mmoItemClass = Class.forName("net.Indyuce.mmoitems.api.item.mmoitem.MMOItem", true, loader);
            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type", true, loader);
            liveItemConstructor = liveItemClass.getConstructor(ItemStack.class);
            itemTypeMethod = mmoItemClass.getMethod("getType");
            typeIdMethod = typeClass.getMethod("getId");
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Could not hook MMOItems for Frostbound weapon adaptation: " + ex.getMessage());
        }
    }
}
