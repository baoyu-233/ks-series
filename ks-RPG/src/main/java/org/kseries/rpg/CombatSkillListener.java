package org.kseries.rpg;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Server-thread-only generic mechanics driven entirely by the external content catalog. */
final class CombatSkillListener implements Listener {
    private final KsRpg plugin;
    private final MmoItemsBridge mmoItems;
    private final MmoInventoryBridge mmoInventory;
    private final NamespacedKey arrowSource;
    private final NamespacedKey arrowMarkDuration;
    private final NamespacedKey arrowAllyMultiplier;
    private final NamespacedKey arrowPierce;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Mark> marks = new HashMap<>();
    private final Map<UUID, Relay> relays = new HashMap<>();
    private final Map<UUID, TimedMultiplier> nextAttackBonus = new HashMap<>();
    private final Map<UUID, TimedMultiplier> anchorUntil = new HashMap<>();
    private final Map<UUID, TimedMultiplier> weaknessUntil = new HashMap<>();
    private final Map<UUID, Long> bastionUntil = new HashMap<>();
    private final Map<UUID, Long> resolveUntil = new HashMap<>();

    CombatSkillListener(KsRpg plugin, MmoItemsBridge mmoItems, MmoInventoryBridge mmoInventory) {
        this.plugin = plugin;
        this.mmoItems = mmoItems;
        this.mmoInventory = mmoInventory;
        this.arrowSource = new NamespacedKey(plugin, "configured_arrow_source");
        this.arrowMarkDuration = new NamespacedKey(plugin, "configured_arrow_mark_duration");
        this.arrowAllyMultiplier = new NamespacedKey(plugin, "configured_arrow_ally_multiplier");
        this.arrowPierce = new NamespacedKey(plugin, "configured_arrow_pierce");
    }

    @EventHandler(ignoreCancelled = true)
    void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getPlayer().isSneaking()) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        CombatCatalog catalog = plugin.combatCatalog();
        Optional<CombatCatalog.CacheDefinition> cache = catalog.cache(item, mmoItems);
        if (cache.isPresent()) {
            event.setCancelled(true);
            openCache(player, item, cache.get());
            return;
        }
        if (!player.hasPermission("ksrpg.use")) return;
        catalog.active(item, CombatCatalog.Trigger.SNEAK_RIGHT_CLICK, mmoItems).ifPresent(definition -> {
            event.setCancelled(true);
            if (!ready(player, definition)) return;
            definition.mechanics().forEach(mechanic -> execute(player, definition.key(), mechanic));
        });
    }

    @EventHandler(ignoreCancelled = true)
    void onTalismanActivate(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.hasPermission("ksrpg.use")) return;
        plugin.combatCatalog().equippedActive(mmoInventory.equipped(player), CombatCatalog.Trigger.SNEAK_SWAP_HANDS, mmoItems)
                .ifPresent(definition -> {
                    event.setCancelled(true);
                    if (!ready(player, definition)) return;
                    definition.mechanics().forEach(mechanic -> execute(player, definition.key(), mechanic));
                });
    }

    @EventHandler(ignoreCancelled = true)
    void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker == null || !(event.getEntity() instanceof LivingEntity target)) return;
        long now = now();
        Mark mark = marks.get(target.getUniqueId());
        if (mark != null && mark.expiresAt < now) {
            marks.remove(target.getUniqueId());
            mark = null;
        }
        List<ItemStack> attackerGear = mmoInventory.equipped(attacker);
        if (mark != null && !mark.owner.equals(attacker.getUniqueId())) {
            event.setDamage(event.getDamage() * mark.allyDamageMultiplier);
            Player owner = plugin.getServer().getPlayer(mark.owner);
            if (owner != null) {
                for (CombatCatalog.Mechanic resonance : passives(owner, "MARK_RESONANCE")) {
                    reduceCooldown(owner, mark.sourceKey, resonance.milliseconds("cooldown-reduction-millis", 0L));
                }
            }
        }
        if (mark != null) event.setDamage(event.getDamage() * multiplier(attackerGear, "MARK_FOCUS_DAMAGE", "multiplier", 1.0));

        Relay relay = relays.get(target.getUniqueId());
        if (relay != null && relay.expiresAt >= now && !relay.owner.equals(attacker.getUniqueId())) {
            event.setDamage(event.getDamage() * relay.allyDamageMultiplier);
            relays.remove(target.getUniqueId());
        }
        TimedMultiplier bonus = nextAttackBonus.remove(attacker.getUniqueId());
        if (bonus != null && bonus.expiresAt >= now) event.setDamage(event.getDamage() * bonus.multiplier);
        TimedMultiplier weakness = weaknessUntil.get(target.getUniqueId());
        if (weakness != null) {
            if (weakness.expiresAt >= now) event.setDamage(event.getDamage() * weakness.multiplier);
            else weaknessUntil.remove(target.getUniqueId());
        }

        if (event.getDamager() instanceof Arrow arrow && arrow.getPersistentDataContainer().has(arrowSource, PersistentDataType.STRING)) {
            String sourceKey = arrow.getPersistentDataContainer().get(arrowSource, PersistentDataType.STRING);
            Long duration = arrow.getPersistentDataContainer().get(arrowMarkDuration, PersistentDataType.LONG);
            Double allyMultiplier = arrow.getPersistentDataContainer().get(arrowAllyMultiplier, PersistentDataType.DOUBLE);
            Integer pierce = arrow.getPersistentDataContainer().get(arrowPierce, PersistentDataType.INTEGER);
            if (sourceKey != null && duration != null && allyMultiplier != null) {
                marks.put(target.getUniqueId(), new Mark(attacker.getUniqueId(), sourceKey, now + duration, allyMultiplier));
                if (pierce != null && pierce > 0) arrow.setPierceLevel(pierce);
                attacker.getWorld().playSound(target.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, .8f, 1.6f);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    void onAnyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        long now = now();
        TimedMultiplier anchor = anchorUntil.get(player.getUniqueId());
        if (anchor != null && anchor.expiresAt >= now) event.setDamage(event.getDamage() * anchor.multiplier);
        else anchorUntil.remove(player.getUniqueId());

        double projected = player.getHealth() - event.getFinalDamage();
        List<ItemStack> gear = mmoInventory.equipped(player);
        for (CombatCatalog.Mechanic bastion : passives(gear, "LAST_STAND_ABSORPTION")) {
            if (projected > player.getMaxHealth() * bastion.number("health-threshold", .35)) continue;
            long cooldown = bastion.milliseconds("cooldown-millis", 60_000L);
            if (bastionUntil.getOrDefault(player.getUniqueId(), 0L) >= now) continue;
            bastionUntil.put(player.getUniqueId(), now + cooldown);
            player.setAbsorptionAmount(Math.max(player.getAbsorptionAmount(), bastion.number("absorption", 6.0)));
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, .8f, 1.2f);
            break;
        }
        for (CombatCatalog.Mechanic resolve : passives(gear, "LOW_HEALTH_RESISTANCE")) {
            if (projected > player.getMaxHealth() * resolve.number("health-threshold", .35)) continue;
            if (resolveUntil.getOrDefault(player.getUniqueId(), 0L) >= now) continue;
            resolveUntil.put(player.getUniqueId(), now + resolve.milliseconds("cooldown-millis", 30_000L));
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, resolve.whole("duration-ticks", 60),
                    resolve.whole("amplifier", 0), true, false, true));
            break;
        }
    }

    @EventHandler(ignoreCancelled = true)
    void onSprint(PlayerToggleSprintEvent event) {
        TimedMultiplier anchor = anchorUntil.get(event.getPlayer().getUniqueId());
        if (event.isSprinting() && anchor != null && anchor.expiresAt >= now()) event.setCancelled(true);
    }

    @EventHandler
    void onDeath(PlayerDeathEvent event) {
        clear(event.getEntity().getUniqueId());
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    private void execute(Player player, String sourceKey, CombatCatalog.Mechanic mechanic) {
        switch (mechanic.type()) {
            case "ARC_SLASH" -> arcSlash(player, mechanic);
            case "MARK_ARROW" -> markedArrow(player, sourceKey, mechanic);
            case "HAMMER_QUAKE", "HEAVY_QUAKE" -> hammer(player, mechanic);
            case "RELAY_DASH" -> relay(player, mechanic);
            case "ANCHOR_GUARD" -> anchor(player, mechanic);
            case "SIGNAL_AURA" -> signal(player, mechanic);
            case "PIONEER_RESCUE" -> pioneer(player, mechanic);
            default -> plugin.getLogger().warning("Unknown combat mechanic ignored: " + mechanic.type());
        }
    }

    private void arcSlash(Player player, CombatCatalog.Mechanic mechanic) {
        List<LivingEntity> targets = cone(player, mechanic.number("range", 5.0), mechanic.number("cone-cosine", .50));
        for (LivingEntity target : targets) damage(player, target, mechanic.number("flat-damage", 9.0) + attackDamage(player) * mechanic.number("attack-scale", .75));
        if (!targets.isEmpty()) {
            int haste = mechanic.whole("haste-ticks", 40);
            int speed = mechanic.whole("speed-ticks", 40);
            if (haste > 0) player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, haste, 0, true, false, true));
            if (speed > 0) player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, speed, 0, true, false, true));
            grantNextAttack(player, mechanic);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1, .8f);
        player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, player.getLocation().add(player.getLocation().getDirection().multiply(2)), 3, .6, .4, .6, 0);
    }

    private void hammer(Player player, CombatCatalog.Mechanic mechanic) {
        for (LivingEntity target : cone(player, mechanic.number("range", 3.0), mechanic.number("cone-cosine", .35))) {
            double amount = mechanic.number("flat-damage", 11.0) + attackDamage(player) * mechanic.number("attack-scale", .70);
            String exposureTag = mechanic.text("exposure-scoreboard-tag", "");
            if (!exposureTag.isBlank() && target.getScoreboardTags().contains(exposureTag)) amount *= mechanic.number("exposure-multiplier", 1.0);
            damage(player, target, amount);
            int slowTicks = mechanic.whole("slow-ticks", 0);
            if (slowTicks > 0) target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks,
                    mechanic.whole("slow-amplifier", 8), true, false, true));
            long weaknessDuration = mechanic.milliseconds("weakness-duration-millis", 0L);
            if (weaknessDuration > 0) weaknessUntil.put(target.getUniqueId(), new TimedMultiplier(now() + weaknessDuration,
                    mechanic.number("weakness-damage-multiplier", 1.0)));
        }
        grantNextAttack(player, mechanic);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, .75f, .65f);
        player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation(), 28, 1.2, .15, 1.2, org.bukkit.Material.DEEPSLATE.createBlockData());
    }

    private void relay(Player player, CombatCatalog.Mechanic mechanic) {
        Vector direction = player.getEyeLocation().getDirection().normalize();
        player.setVelocity(direction.multiply(mechanic.number("dash-velocity", 1.15)).setY(mechanic.number("dash-y", .28)));
        RayTraceResult result = player.getWorld().rayTraceEntities(player.getEyeLocation(), direction,
                mechanic.number("range", 4.5), mechanic.number("ray-radius", .65), entity -> entity instanceof LivingEntity && entity != player);
        if (result != null && result.getHitEntity() instanceof LivingEntity target) {
            damage(player, target, mechanic.number("flat-damage", 10.0) + attackDamage(player) * mechanic.number("attack-scale", .70));
            relays.put(target.getUniqueId(), new Relay(player.getUniqueId(), now() + mechanic.milliseconds("relay-duration-millis", 3_000L),
                    mechanic.number("ally-damage-multiplier", 1.12)));
            grantNextAttack(player, mechanic);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1, 1.1f);
    }

    private void markedArrow(Player player, String sourceKey, CombatCatalog.Mechanic mechanic) {
        Arrow arrow = player.launchProjectile(Arrow.class);
        arrow.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(mechanic.number("arrow-speed", 2.8)));
        arrow.getPersistentDataContainer().set(arrowSource, PersistentDataType.STRING, sourceKey);
        arrow.getPersistentDataContainer().set(arrowMarkDuration, PersistentDataType.LONG, mechanic.milliseconds("mark-duration-millis", 5_000L));
        arrow.getPersistentDataContainer().set(arrowAllyMultiplier, PersistentDataType.DOUBLE, mechanic.number("ally-damage-multiplier", 1.10));
        int pierce = mechanic.whole("pierce-level", 0);
        arrow.getPersistentDataContainer().set(arrowPierce, PersistentDataType.INTEGER, pierce);
        if (pierce > 0) arrow.setPierceLevel(pierce);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1, pierce > 0 ? 1.25f : 1.0f);
    }

    private void anchor(Player player, CombatCatalog.Mechanic mechanic) {
        anchorUntil.put(player.getUniqueId(), new TimedMultiplier(now() + mechanic.milliseconds("duration-millis", 3_000L),
                mechanic.number("damage-multiplier", .75)));
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, .9f, .7f);
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 28, .45, .8, .45,
                new Particle.DustOptions(Color.fromRGB(79, 174, 255), 1.2f));
    }

    private void signal(Player player, CombatCatalog.Mechanic mechanic) {
        double rangeSquared = Math.pow(mechanic.number("range", 8.0), 2);
        for (Player ally : player.getWorld().getPlayers()) {
            if (ally.getLocation().distanceSquared(player.getLocation()) > rangeSquared) continue;
            ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, mechanic.whole("duration-ticks", 120),
                    mechanic.whole("speed-amplifier", 0), true, false, true));
            ally.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, mechanic.whole("duration-ticks", 120),
                    mechanic.whole("haste-amplifier", 0), true, false, true));
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1, 1.25f);
    }

    private void pioneer(Player player, CombatCatalog.Mechanic mechanic) {
        double rangeSquared = Math.pow(mechanic.number("range", 8.0), 2);
        for (Player ally : player.getWorld().getPlayers()) {
            if (ally.getLocation().distanceSquared(player.getLocation()) > rangeSquared
                    || ally.getHealth() > ally.getMaxHealth() * mechanic.number("health-threshold", .40)) continue;
            ally.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, mechanic.whole("duration-ticks", 80),
                    mechanic.whole("amplifier", 0), true, false, true));
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .9f, 1.4f);
    }

    private void grantNextAttack(Player player, CombatCatalog.Mechanic mechanic) {
        long duration = mechanic.milliseconds("next-attack-duration-millis", 0L);
        if (passives(player, "NEXT_ATTACK_ENABLE").isEmpty()) return;
        if (duration > 0) nextAttackBonus.put(player.getUniqueId(), new TimedMultiplier(now() + duration,
                mechanic.number("next-attack-multiplier", 1.0)));
    }

    private void openCache(Player player, ItemStack cache, CombatCatalog.CacheDefinition definition) {
        double total = definition.rewards().stream().mapToDouble(CombatCatalog.WeightedItem::weight).sum();
        double roll = Math.random() * total;
        CombatCatalog.WeightedItem selected = definition.rewards().getLast();
        for (CombatCatalog.WeightedItem reward : definition.rewards()) {
            roll -= reward.weight();
            if (roll <= 0.0) {
                selected = reward;
                break;
            }
        }
        ItemStack reward = selected.item().create(mmoItems);
        if (reward == null) {
            player.sendActionBar("§c该装备箱的奖励物品不存在，请联系管理员。");
            return;
        }
        if (cache.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else cache.setAmount(cache.getAmount() - 1);
        player.getInventory().addItem(reward).values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.25f);
    }

    private List<LivingEntity> cone(Player player, double range, double cosine) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        return player.getWorld().getNearbyLivingEntities(player.getLocation(), range, range, range, entity -> entity != player).stream()
                .filter(entity -> {
                    Vector toTarget = entity.getEyeLocation().toVector().subtract(eye.toVector());
                    return toTarget.lengthSquared() > .01 && toTarget.normalize().dot(direction) >= cosine;
                }).toList();
    }

    private void damage(Player source, LivingEntity target, double amount) {
        amount *= multiplier(mmoInventory.equipped(source), "HUNTER_MARK_DAMAGE", "multiplier", marks.containsKey(target.getUniqueId()) ? 1.0 : 1.0,
                marks.containsKey(target.getUniqueId()));
        target.damage(amount, source);
    }

    private boolean ready(Player player, CombatCatalog.ActiveDefinition definition) {
        String key = player.getUniqueId() + ":" + definition.key();
        long expiry = cooldowns.getOrDefault(key, 0L);
        if (expiry > now()) {
            player.sendActionBar("§7技能冷却：§f" + Math.max(1, Math.ceil((expiry - now()) / 1000.0)) + " 秒");
            return false;
        }
        double cooldownMultiplier = multiplier(mmoInventory.equipped(player), "COOLDOWN_MULTIPLIER", "multiplier", 1.0);
        cooldowns.put(key, now() + Math.round(definition.cooldownSeconds() * 1_000L * cooldownMultiplier));
        return true;
    }

    private List<CombatCatalog.Mechanic> passives(Player player, String type) {
        return passives(mmoInventory.equipped(player), type);
    }

    private List<CombatCatalog.Mechanic> passives(List<ItemStack> equipped, String type) {
        return plugin.combatCatalog().passives(equipped, type, mmoItems);
    }

    private double multiplier(List<ItemStack> equipped, String passiveType, String valueKey, double fallback) {
        return multiplier(equipped, passiveType, valueKey, fallback, true);
    }

    private double multiplier(List<ItemStack> equipped, String passiveType, String valueKey, double fallback, boolean enabled) {
        if (!enabled) return fallback;
        double result = fallback;
        for (CombatCatalog.Mechanic passive : passives(equipped, passiveType)) result *= passive.number(valueKey, 1.0);
        return result;
    }

    private void reduceCooldown(Player player, String skill, long reduction) {
        if (reduction <= 0L) return;
        String key = player.getUniqueId() + ":" + skill;
        Long current = cooldowns.get(key);
        if (current != null) cooldowns.put(key, Math.max(now(), current - reduction));
    }

    private Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private void clear(UUID player) {
        cooldowns.keySet().removeIf(key -> key.startsWith(player + ":"));
        anchorUntil.remove(player);
        nextAttackBonus.remove(player);
        marks.remove(player);
        relays.remove(player);
        weaknessUntil.remove(player);
    }

    private static double attackDamage(Player player) {
        return player.getAttribute(Attribute.ATTACK_DAMAGE) == null ? 0.0 : player.getAttribute(Attribute.ATTACK_DAMAGE).getValue();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private record Mark(UUID owner, String sourceKey, long expiresAt, double allyDamageMultiplier) { }
    private record Relay(UUID owner, long expiresAt, double allyDamageMultiplier) { }
    private record TimedMultiplier(long expiresAt, double multiplier) { }
}
