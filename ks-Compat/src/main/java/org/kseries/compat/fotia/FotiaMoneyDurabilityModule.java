package org.kseries.compat.fotia;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.kseries.compat.CompatModule;
import org.kseries.compat.bot.BotManagerModule;
import org.kseries.compat.service.EconomyBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FotiaMoneyDurabilityModule implements CompatModule, Listener {

    private final JavaPlugin plugin;
    private final EconomyBridge economy;
    private final FotiaBridge fotia;
    private final BotManagerModule botManager;
    private final Map<UUID, Long> lastNotify = new HashMap<>();
    private final Map<UUID, Long> nextRepairAttempt = new HashMap<>();

    private boolean enabled;
    private boolean debug;
    private String enchantId;
    private String insufficientMoney;
    private int notifyEveryTicks;
    private double repairThresholdRemainingPercent;
    private double repairTargetRemainingPercent;
    private int repairRetryTicks;
    private double levelPriceStep;
    private List<String> applicableItems = new ArrayList<>();
    private List<CostTier> tiers = new ArrayList<>();
    private List<LevelPriceTier> levelPriceTiers = new ArrayList<>();

    public FotiaMoneyDurabilityModule(JavaPlugin plugin, EconomyBridge economy, FotiaBridge fotia, BotManagerModule botManager) {
        this.plugin = plugin;
        this.economy = economy;
        this.fotia = fotia;
        this.botManager = botManager;
    }

    @Override
    public String id() {
        return "fotia-money-durability";
    }

    @Override
    public String displayName() {
        return "Fotia Money Durability";
    }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void reload() {
        String path = "modules.fotia-money-durability.";
        enabled = plugin.getConfig().getBoolean(path + "enabled", true);
        debug = plugin.getConfig().getBoolean(path + "debug", false);
        enchantId = plugin.getConfig().getString(path + "enchant-id", "coinbound_pickaxe");
        insufficientMoney = plugin.getConfig().getString(path + "insufficient-money", "allow-durability-damage");
        notifyEveryTicks = plugin.getConfig().getInt(path + "notify-every-ticks", 100);
        repairThresholdRemainingPercent = clamp(plugin.getConfig().getDouble(path + "repair-threshold-remaining-percent", 10.0), 0.0, 100.0);
        repairTargetRemainingPercent = clamp(plugin.getConfig().getDouble(path + "repair-target-remaining-percent", 100.0), 0.0, 100.0);
        repairRetryTicks = Math.max(1, plugin.getConfig().getInt(path + "repair-retry-ticks", 20));
        levelPriceStep = plugin.getConfig().getDouble(path + "level-price-step", 0.25);
        applicableItems = plugin.getConfig().getStringList(path + "applicable-items");
        tiers = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList(path + "cost-tiers")) {
            tiers.add(new CostTier(
                number(raw.get("from-remaining-percent"), 0.0),
                number(raw.get("to-remaining-percent"), 100.0),
                number(raw.get("cost-per-damage"), 1.0)
            ));
        }
        if (tiers.isEmpty()) tiers.add(new CostTier(0.0, 100.0, 1.0));
        levelPriceTiers = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList(path + "level-price-tiers")) {
            int minLevel = Math.max(1, (int) Math.round(number(raw.get("min-level"), 1.0)));
            int maxLevel = Math.max(minLevel, (int) Math.round(number(raw.get("max-level"), minLevel)));
            levelPriceTiers.add(new LevelPriceTier(minLevel, maxLevel, Math.max(0.0, number(raw.get("multiplier"), 1.0))));
        }
    }

    @Override
    public void disable() {
        nextRepairAttempt.clear();
        lastNotify.clear();
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id());
        out.put("enabled", enabled);
        out.put("enchantId", enchantId);
        out.put("tiers", tiers.size());
        out.put("levelPriceTiers", levelPriceTiers.size());
        out.put("fotiaAvailable", fotia.isAvailable());
        out.put("economyAvailable", economy.isAvailable());
        out.put("repairThresholdRemainingPercent", repairThresholdRemainingPercent);
        out.put("repairTargetRemainingPercent", repairTargetRemainingPercent);
        return out;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (!enabled || !fotia.isAvailable() || !economy.isAvailable()) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir() || !isApplicable(item.getType())) return;
        int maxDurability = item.getType().getMaxDurability();
        if (maxDurability <= 0) return;

        int level = fotia.getLevel(item, enchantId);
        if (level <= 0) return;

        int currentDamage = currentDamage(item);
        int projectedDamage = Math.min(maxDurability, currentDamage + Math.max(1, event.getDamage()));
        if (remainingPercent(maxDurability, projectedDamage) > repairThresholdRemainingPercent) return;

        int targetDamage = targetRepairDamage(maxDurability);
        if (projectedDamage <= targetDamage) return;

        RepairAttempt attempt = tryRepair(event.getPlayer(), item, projectedDamage, "item-damage");
        if (attempt.coolingDown()) {
            if ("cancel-item-damage".equalsIgnoreCase(insufficientMoney)) event.setCancelled(true);
            return;
        }
        if (attempt.repaired()) event.setCancelled(true);
        else if ("cancel-item-damage".equalsIgnoreCase(insufficientMoney)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotBlockBreak(BlockBreakEvent event) {
        if (!enabled || !fotia.isAvailable() || !economy.isAvailable()) return;
        Player player = event.getPlayer();
        if (!botManager.isBotActor(player)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item == null || item.getType().isAir() || !isApplicable(item.getType())) return;
            int maxDurability = item.getType().getMaxDurability();
            if (maxDurability <= 0) return;
            int level = fotia.getLevel(item, enchantId);
            if (level <= 0) return;
            int currentDamage = currentDamage(item);
            if (remainingPercent(maxDurability, currentDamage) > repairThresholdRemainingPercent) return;
            tryRepair(player, item, currentDamage, "bot-block-break-fallback");
        });
    }

    private RepairAttempt tryRepair(Player actor, ItemStack item, int fromDamage, String source) {
        int maxDurability = item.getType().getMaxDurability();
        int level = fotia.getLevel(item, enchantId);
        if (maxDurability <= 0 || level <= 0) return RepairAttempt.noneAttempt();

        int targetDamage = targetRepairDamage(maxDurability);
        int effectiveDamage = Math.max(0, Math.min(maxDurability, fromDamage));
        if (effectiveDamage <= targetDamage) return RepairAttempt.noneAttempt();
        if (remainingPercent(maxDurability, effectiveDamage) > repairThresholdRemainingPercent) {
            return RepairAttempt.noneAttempt();
        }

        OfflinePlayer payer = botManager.payerFor(actor);
        UUID payerId = payer.getUniqueId();
        if (isRepairRetryCoolingDown(payerId)) return RepairAttempt.coolingDownAttempt();

        double cost = calculateRepairCost(maxDurability, effectiveDamage, targetDamage, level);
        int repairedDamage = effectiveDamage - targetDamage;
        if (cost <= 0.0 || economy.withdraw(payer, cost)) {
            nextRepairAttempt.remove(payerId);
            setDamage(item, targetDamage);
            notifyRepaired(actor, payer, cost, repairedDamage);
            if (debug) {
                plugin.getLogger().info("Money durability repaired " + repairedDamage + " damage for " + cost
                    + " payer=" + payerId + " actor=" + actor.getUniqueId() + " source=" + source + " item=" + item.getType());
            }
            return RepairAttempt.repairedAttempt();
        }

        nextRepairAttempt.put(payerId, System.currentTimeMillis() + repairRetryTicks * 50L);
        notifyInsufficient(actor, payer, cost);
        if (debug) {
            plugin.getLogger().info("Money durability insufficient funds payer=" + payerId + " actor="
                + actor.getUniqueId() + " source=" + source + " cost=" + cost);
        }
        return RepairAttempt.failedAttempt();
    }

    private double calculateRepairCost(int maxDurability, int fromDamage, int targetDamage, int level) {
        double total = 0.0;
        int from = Math.max(0, Math.min(maxDurability, fromDamage));
        int to = Math.max(0, Math.min(from, targetDamage));
        for (int damage = from; damage > to; damage--) {
            total += costFor(remainingPercent(maxDurability, damage));
        }
        double multiplier = multiplierForLevel(level);
        return total * multiplier;
    }

    private double multiplierForLevel(int level) {
        for (LevelPriceTier tier : levelPriceTiers) {
            if (tier.matches(level)) return tier.multiplier;
        }
        return Math.max(0.0, 1.0 + Math.max(0, level - 1) * levelPriceStep);
    }

    private double costFor(double remainingPercent) {
        for (CostTier tier : tiers) {
            if (tier.matches(remainingPercent)) return tier.costPerDamage;
        }
        return tiers.get(tiers.size() - 1).costPerDamage;
    }

    private boolean isApplicable(Material material) {
        if (applicableItems == null || applicableItems.isEmpty()) return true;
        String name = material.name();
        for (String raw : applicableItems) {
            String key = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (key.isBlank()) continue;
            if (name.equals(key) || name.endsWith("_" + key)) return true;
        }
        return false;
    }

    private int currentDamage(ItemStack item) {
        if (item.getItemMeta() instanceof Damageable damageable) {
            return Math.max(0, damageable.getDamage());
        }
        return 0;
    }

    private void setDamage(ItemStack item, int damage) {
        if (!(item.getItemMeta() instanceof Damageable damageable)) return;
        damageable.setDamage(Math.max(0, Math.min(item.getType().getMaxDurability(), damage)));
        item.setItemMeta(damageable);
    }

    private int targetRepairDamage(int maxDurability) {
        double target = Math.max(repairThresholdRemainingPercent, repairTargetRemainingPercent);
        target = clamp(target, 0.0, 100.0);
        return (int) Math.floor(maxDurability * (100.0 - target) / 100.0);
    }

    private double remainingPercent(int maxDurability, int damage) {
        if (maxDurability <= 0) return 100.0;
        return clamp((maxDurability - Math.max(0, damage)) * 100.0 / maxDurability, 0.0, 100.0);
    }

    private boolean isRepairRetryCoolingDown(UUID payer) {
        Long next = nextRepairAttempt.get(payer);
        if (next == null) return false;
        if (System.currentTimeMillis() < next) return true;
        nextRepairAttempt.remove(payer);
        return false;
    }

    private void notifyRepaired(Player actor, OfflinePlayer payer, double cost, int repairedDamage) {
        if (!shouldNotify(payer.getUniqueId())) return;
        Player target = actor;
        if (botManager.isManagedBot(actor) && payer.getPlayer() != null) target = payer.getPlayer();
        target.sendMessage("§6[金钱耐久] §7耐久到达阈值，已扣除 §e" + economy.format(cost)
            + " §7修复 §f" + repairedDamage + " §7点耐久。");
    }

    private void notifyInsufficient(Player actor, OfflinePlayer payer, double cost) {
        if (!shouldNotify(payer.getUniqueId())) return;
        Player target = actor;
        if (botManager.isManagedBot(actor) && payer.getPlayer() != null) target = payer.getPlayer();
        target.sendMessage("§c[金钱耐久] 余额不足，修复需要 " + economy.format(cost) + "。");
    }

    private boolean shouldNotify(UUID uuid) {
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1, notifyEveryTicks) * 50L;
        Long last = lastNotify.get(uuid);
        if (last != null && now - last < intervalMs) return false;
        lastNotify.put(uuid, now);
        return true;
    }

    public Map<String, Object> settingsView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("enchantId", enchantId);
        out.put("insufficientMoney", insufficientMoney);
        out.put("repairThresholdRemainingPercent", repairThresholdRemainingPercent);
        out.put("repairTargetRemainingPercent", repairTargetRemainingPercent);
        out.put("repairRetryTicks", repairRetryTicks);
        out.put("levelPriceStep", levelPriceStep);
        List<Map<String, Object>> tierViews = new ArrayList<>();
        for (CostTier tier : tiers) tierViews.add(tier.view());
        out.put("costTiers", tierViews);
        List<Map<String, Object>> levelTierViews = new ArrayList<>();
        for (LevelPriceTier tier : levelPriceTiers) levelTierViews.add(tier.view());
        out.put("levelPriceTiers", levelTierViews);
        return out;
    }

    @SuppressWarnings("unchecked")
    public void updateSettingsFromWeb(Map<String, Object> body) {
        String path = "modules.fotia-money-durability.";
        if (body.containsKey("enabled")) plugin.getConfig().set(path + "enabled", Boolean.TRUE.equals(body.get("enabled")));
        if (body.get("enchantId") instanceof String s && !s.isBlank()) plugin.getConfig().set(path + "enchant-id", s);
        if (body.get("insufficientMoney") instanceof String s && !s.isBlank()) plugin.getConfig().set(path + "insufficient-money", s);
        if (body.get("repairThresholdRemainingPercent") instanceof Number n) plugin.getConfig().set(path + "repair-threshold-remaining-percent", n.doubleValue());
        if (body.get("repairTargetRemainingPercent") instanceof Number n) plugin.getConfig().set(path + "repair-target-remaining-percent", n.doubleValue());
        if (body.get("repairRetryTicks") instanceof Number n) plugin.getConfig().set(path + "repair-retry-ticks", n.intValue());
        if (body.get("levelPriceStep") instanceof Number n) plugin.getConfig().set(path + "level-price-step", n.doubleValue());
        if (body.get("costTiers") instanceof List<?> rawTiers) {
            List<Map<String, Object>> save = new ArrayList<>();
            for (Object raw : rawTiers) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                Map<String, Object> tier = new LinkedHashMap<>();
                tier.put("from-remaining-percent", number(map.get("fromRemainingPercent"), 0.0));
                tier.put("to-remaining-percent", number(map.get("toRemainingPercent"), 100.0));
                tier.put("cost-per-damage", number(map.get("costPerDamage"), 1.0));
                save.add(tier);
            }
            plugin.getConfig().set(path + "cost-tiers", save);
        }
        if (body.get("levelPriceTiers") instanceof List<?> rawTiers) {
            List<Map<String, Object>> save = new ArrayList<>();
            for (Object raw : rawTiers) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                int minLevel = Math.max(1, (int) Math.round(number(map.get("minLevel"), 1.0)));
                int maxLevel = Math.max(minLevel, (int) Math.round(number(map.get("maxLevel"), minLevel)));
                Map<String, Object> tier = new LinkedHashMap<>();
                tier.put("min-level", minLevel);
                tier.put("max-level", maxLevel);
                tier.put("multiplier", Math.max(0.0, number(map.get("multiplier"), 1.0)));
                save.add(tier);
            }
            plugin.getConfig().set(path + "level-price-tiers", save);
        }
        plugin.saveConfig();
        reload();
    }

    private double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record CostTier(double fromRemainingPercent, double toRemainingPercent, double costPerDamage) {
        boolean matches(double percent) {
            double min = Math.min(fromRemainingPercent, toRemainingPercent);
            double max = Math.max(fromRemainingPercent, toRemainingPercent);
            return percent >= min && percent <= max;
        }

        Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fromRemainingPercent", fromRemainingPercent);
            out.put("toRemainingPercent", toRemainingPercent);
            out.put("costPerDamage", costPerDamage);
            return out;
        }
    }

    private record LevelPriceTier(int minLevel, int maxLevel, double multiplier) {
        boolean matches(int level) {
            return level >= minLevel && level <= maxLevel;
        }

        Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("minLevel", minLevel);
            out.put("maxLevel", maxLevel);
            out.put("multiplier", multiplier);
            return out;
        }
    }

    private record RepairAttempt(boolean repaired, boolean coolingDown) {
        static RepairAttempt noneAttempt() {
            return new RepairAttempt(false, false);
        }

        static RepairAttempt repairedAttempt() {
            return new RepairAttempt(true, false);
        }

        static RepairAttempt failedAttempt() {
            return new RepairAttempt(false, false);
        }

        static RepairAttempt coolingDownAttempt() {
            return new RepairAttempt(false, true);
        }
    }
}
