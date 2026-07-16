package org.kseries.compat.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.kseries.compat.CompatModule;
import org.kseries.compat.service.EconomyBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BotManagerModule implements CompatModule, Listener, CommandExecutor, TabCompleter {

    private static final Set<String> MANAGEABLE_SETTINGS = Set.of(
        "enabled", "restore-on-start", "restore-delay-ticks", "prefer-rented-slots", "max-name-length",
        "economy-enabled", "summon-price-per-day", "permanent-slot-price", "max-rent-days-per-purchase",
        "player-default-permanent-slots", "protect-owned-bots", "default-invincible", "invulnerable",
        "cancel-damage", "cancel-knockback", "resistance-255", "block-external-teleports",
        "allow-portal-teleports", "action-default-interval-ticks", "action-default-times",
        "action-default-delay-ticks", "action-min-interval-ticks", "action-use-min-interval-ticks",
        "action-max-times", "action-max-delay-ticks", "action-max-concurrent",
        "action-start-cooldown-millis", "action-allow-infinite", "action-prevent-duplicate-types",
        "use-tick-timeout"
    );

    private static final Set<String> USE_ACTION_KEYS = Set.of(
        "use", "use-auto", "auto-use", "use-offhand", "use-on", "place",
        "use-on-offhand", "use-to", "use-to-offhand"
    );

    private final JavaPlugin plugin;
    private final EconomyBridge economy;
    private final LeavesBotBridge leaves;
    private final BotAccountStore store;
    private final BotStorageStore storage;
    private final Set<UUID> deathRelease = new HashSet<>();
    private final Set<UUID> authorizedTeleports = new HashSet<>();
    private final Map<UUID, Long> actionStartAllowedAt = new HashMap<>();
    private final Map<UUID, Long> actionCooldownNoticeAt = new HashMap<>();
    private BukkitTask cleanupTask;
    private BukkitTask hungerTask;
    private boolean shuttingDown;
    private boolean shutdownPrepared;

    private boolean enabled;
    private boolean restoreOnStart;
    private boolean preferRentedSlots;
    private boolean protectOwnedBots;
    private boolean defaultInvincible;
    private boolean invulnerable;
    private boolean cancelDamage;
    private boolean cancelKnockback;
    private boolean resistance255;
    private boolean economyEnabled;
    private boolean blockExternalTeleports;
    private boolean allowPortalTeleports;
    private int maxNameLength;
    private int maxRentDays;
    private int defaultPermanentSlots;
    private double summonPricePerDay;
    private double permanentSlotPrice;
    private int actionDefaultIntervalTicks;
    private int actionDefaultTimes;
    private int actionDefaultDelayTicks;
    private int actionMinIntervalTicks;
    private int actionUseMinIntervalTicks;
    private int actionMaxTimes;
    private int actionMaxDelayTicks;
    private int actionMaxConcurrent;
    private long actionStartCooldownMillis;
    private boolean actionAllowInfinite;
    private boolean actionPreventDuplicateTypes;

    public BotManagerModule(JavaPlugin plugin, EconomyBridge economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.leaves = new LeavesBotBridge(plugin);
        this.store = new BotAccountStore(plugin);
        this.storage = new BotStorageStore(plugin);
    }

    @Override
    public String id() {
        return "bot-manager";
    }

    @Override
    public String displayName() {
        return "KSBot";
    }

    @Override
    public void enable() {
        store.load();
        storage.load();
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("KSBot shutdown inventory protection active (pre-stop and disable fallback).");
        if (restoreOnStart) {
            long delay = plugin.getConfig().getLong("modules.bot-manager.restore-delay-ticks", 100L);
            Bukkit.getScheduler().runTaskLater(plugin, this::restoreManagedBots, delay);
        }
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int purged = purgeExpiredBots();
            updateOnlineBotLocations();
            if (purged > 0) plugin.getLogger().info("Purged " + purged + " expired bot rental(s).");
        }, 20L * 60L, 20L * 60L);
        hungerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::maintainHungerLocks, 1L, 20L);
    }

    @Override
    public void reload() {
        String path = "modules.bot-manager.";
        enabled = plugin.getConfig().getBoolean(path + "enabled", true);
        restoreOnStart = plugin.getConfig().getBoolean(path + "restore-on-start", true);
        preferRentedSlots = plugin.getConfig().getBoolean(path + "prefer-rented-slots", true);
        protectOwnedBots = plugin.getConfig().getBoolean(path + "protect-owned-bots", true);
        defaultInvincible = plugin.getConfig().getBoolean(path + "default-invincible", true);
        invulnerable = plugin.getConfig().getBoolean(path + "invulnerable", true);
        cancelDamage = plugin.getConfig().getBoolean(path + "cancel-damage", true);
        cancelKnockback = plugin.getConfig().getBoolean(path + "cancel-knockback", true);
        resistance255 = plugin.getConfig().getBoolean(path + "resistance-255", true);
        economyEnabled = plugin.getConfig().getBoolean(path + "economy-enabled", false);
        blockExternalTeleports = plugin.getConfig().getBoolean(path + "block-external-teleports", true);
        allowPortalTeleports = plugin.getConfig().getBoolean(path + "allow-portal-teleports", true);
        maxNameLength = plugin.getConfig().getInt(path + "max-name-length", 16);
        maxRentDays = plugin.getConfig().getInt(path + "max-rent-days-per-purchase", 30);
        defaultPermanentSlots = plugin.getConfig().getInt(path + "player-default-permanent-slots", 0);
        summonPricePerDay = plugin.getConfig().getDouble(path + "summon-price-per-day", 10000.0);
        permanentSlotPrice = plugin.getConfig().getDouble(path + "permanent-slot-price", 500000.0);
        actionDefaultIntervalTicks = Math.max(1, plugin.getConfig().getInt(path + "action-default-interval-ticks", 20));
        actionDefaultTimes = Math.max(1, plugin.getConfig().getInt(path + "action-default-times", 1));
        actionDefaultDelayTicks = Math.max(0, plugin.getConfig().getInt(path + "action-default-delay-ticks", 0));
        actionMinIntervalTicks = Math.max(1, plugin.getConfig().getInt(path + "action-min-interval-ticks", 2));
        actionUseMinIntervalTicks = Math.max(actionMinIntervalTicks,
            plugin.getConfig().getInt(path + "action-use-min-interval-ticks", 4));
        actionMaxTimes = Math.max(1, plugin.getConfig().getInt(path + "action-max-times", 100000));
        actionMaxDelayTicks = Math.max(0, plugin.getConfig().getInt(path + "action-max-delay-ticks", 1200));
        actionMaxConcurrent = Math.max(1, Math.min(32,
            plugin.getConfig().getInt(path + "action-max-concurrent", 4)));
        actionStartCooldownMillis = Math.max(0L, Math.min(60000L,
            plugin.getConfig().getLong(path + "action-start-cooldown-millis", 1000L)));
        actionAllowInfinite = plugin.getConfig().getBoolean(path + "action-allow-infinite", true);
        actionPreventDuplicateTypes = plugin.getConfig().getBoolean(path + "action-prevent-duplicate-types", true);
        leaves.refresh();
    }

    @Override
    public void disable() {
        shuttingDown = true;
        if (cleanupTask != null) cleanupTask.cancel();
        if (hungerTask != null) hungerTask.cancel();
        prepareForShutdown("plugin-disable");
        updateOnlineBotLocations();
        store.save();
        storage.save();
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id());
        out.put("enabled", enabled);
        out.put("leavesAvailable", leaves.isAvailable());
        out.put("records", store.records().size());
        out.put("dailyPrice", summonPricePerDay);
        out.put("permanentSlotPrice", permanentSlotPrice);
        out.put("economyEnabled", economyEnabled);
        out.put("blockExternalTeleports", blockExternalTeleports);
        out.put("allowPortalTeleports", allowPortalTeleports);
        out.put("defaultInvincible", defaultInvincible);
        out.put("invulnerable", invulnerable);
        out.put("resistance255", resistance255);
        out.put("cancelDamage", cancelDamage);
        out.put("cancelKnockback", cancelKnockback);
        out.put("leavesSettings", leaves.leavesSettings());
        return out;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if ("ksbotstorage".equalsIgnoreCase(command.getName())) {
            handleStorageCommand(sender, args);
            return true;
        }
        if (!enabled) {
            sender.sendMessage("§cKSBot 模块已关闭。");
            return true;
        }
        if (!canUseBotCommands(sender)) {
            sender.sendMessage("§c你尚未获准使用 KSBot。请联系管理员申请机器人名额与使用权限。");
            return true;
        }
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            handleStatus(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "buy" -> handleBuy(sender, args);
            case "rent" -> handleRent(sender, args);
            case "summon", "spawn" -> handleSummon(sender, args);
            case "list" -> handleList(sender);
            case "tp" -> handleTp(sender, args);
            case "face" -> handleFace(sender, args);
            case "action" -> handleAction(sender, args);
            case "invincible", "inv", "god", "protect" -> handleInvincible(sender, args);
            case "nohunger", "dishunger", "hungerlock" -> handleNoHunger(sender, args);
            case "remove", "delete" -> handleRemove(sender, args);
            case "storage", "stash" -> handleStorageCommand(sender, slice(args));
            case "setslots", "slots", "grant" -> handleSetSlots(sender, args);
            case "reset", "resetslots" -> handleResetSlots(sender, args);
            case "bind" -> handleBind(sender, args);
            case "settings", "setting", "config" -> handleSettings(sender, args);
            case "restore" -> handleRestore(sender);
            case "purgeexpired" -> handlePurge(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if ("ksbotstorage".equalsIgnoreCase(command.getName())) {
            return args.length == 1 ? complete(args[0], List.of("open", "status")) : List.of();
        }
        if (!canUseBotCommands(sender)) {
            return List.of();
        }
        if (args.length == 0) return rootCompletions(sender, "");
        if (args.length == 1) return rootCompletions(sender, args[0]);

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "buy" -> args.length == 2 ? complete(args[1], List.of("permanent")) : List.of();
            case "rent" -> args.length == 2 ? complete(args[1], List.of("1", "3", "7", "15", String.valueOf(maxRentDays))) : List.of();
            case "tp", "face", "remove", "delete", "action",
                 "invincible", "inv", "god", "protect", "nohunger", "dishunger", "hungerlock" -> completeBotCommand(sender, sub, args);
            case "summon", "spawn" -> completeSummon(args);
            case "setslots", "slots", "grant" -> completeGrant(args);
            case "reset", "resetslots" -> args.length == 2 ? complete(args[1], List.of("confirm")) : List.of();
            case "bind" -> completeBind(args);
            default -> List.of();
        };
    }

    private List<String> rootCompletions(CommandSender sender, String prefix) {
        List<String> commands = new ArrayList<>(List.of(
            "status", "list", "summon", "spawn", "tp", "face",
            "invincible", "inv", "nohunger", "dishunger", "action", "remove", "delete", "storage"
        ));
        if (economyEnabled) commands.addAll(List.of("rent", "buy"));
        if (sender.hasPermission("kscompat.bot.slots")) commands.add("setslots");
        if (sender.hasPermission("kscompat.bot.admin")) {
            commands.addAll(List.of("bind", "restore", "purgeexpired", "reset"));
        }
        return complete(prefix, commands);
    }

    private List<String> completeBotCommand(CommandSender sender, String sub, String[] args) {
        if (args.length == 2) {
            return "tp".equals(sub) ? teleportBotNameCompletions(sender, args[1]) : botNameCompletions(sender, args[1]);
        }
        if ("face".equals(sub)) {
            if (args.length == 3) return complete(args[2], List.of("0", "90", "180", "-90"));
            if (args.length == 4) return complete(args[3], List.of("0", "30", "45", "-30", "-45"));
        }
        if ("invincible".equals(sub) || "inv".equals(sub) || "god".equals(sub) || "protect".equals(sub)
            || "nohunger".equals(sub) || "dishunger".equals(sub) || "hungerlock".equals(sub)) {
            return args.length == 3 ? complete(args[2], List.of("on", "off", "toggle")) : List.of();
        }
        if ("settings".equals(sub) || "setting".equals(sub) || "config".equals(sub)) {
            if (args.length == 2) return complete(args[1], List.of("list", "get", "set"));
            if (args.length == 3 && ("get".equalsIgnoreCase(args[1]) || "set".equalsIgnoreCase(args[1]))) {
                return complete(args[2], new ArrayList<>(MANAGEABLE_SETTINGS));
            }
            if (args.length == 4 && "set".equalsIgnoreCase(args[1])) {
                String key = args[2].toLowerCase(java.util.Locale.ROOT);
                Object current = plugin.getConfig().get("modules.bot-manager." + key);
                if (current instanceof Boolean) return complete(args[3], List.of("on", "off"));
            }
            return List.of();
        }
        if (!"action".equals(sub)) return List.of();

        if (args.length == 3) return complete(args[2], List.of("list", "start", "stop"));
        if (args.length == 4 && "start".equalsIgnoreCase(args[2])) {
            return complete(args[3], List.of(
                "attack", "break", "mine", "use", "use-on", "place", "use-auto",
                "use-offhand", "use-on-offhand", "use-to", "use-to-offhand",
                "drop", "fish", "jump", "sneak", "swim",
                "move", "mount", "swap", "rotate"
            ));
        }
        if (args.length == 4 && "stop".equalsIgnoreCase(args[2])) {
            return actionIndexCompletions(sender, args[1], args[3]);
        }
        if ("start".equalsIgnoreCase(args[2]) && args.length >= 5) {
            String action = args[3].toLowerCase();
            if ("move".equals(action)) {
                return args.length == 5 ? complete(args[4], List.of("forward", "backward", "left", "right")) : List.of();
            }
            if ("rotate".equals(action) || "rotation".equals(action)) {
                if (args.length == 5) return complete(args[4], List.of("0", "90", "180", "-90"));
                if (args.length == 6) return complete(args[5], List.of("0", "30", "45", "-30", "-45"));
                return List.of();
            }
            if (args.length == 5) return complete(args[4], List.of("1", "5", "10", "20"));
            if (args.length == 6) return complete(args[5], actionAllowInfinite
                ? List.of("1", "10", "1000", "10000", "-1")
                : List.of("1", "10", "1000", "10000"));
            if (args.length == 7) return complete(args[6], List.of("0", "20", "100"));
        }
        return List.of();
    }

    private List<String> completeSummon(String[] args) {
        if (args.length == 3) return onlinePlayerCompletions(args[2]);
        return List.of();
    }

    private List<String> completeGrant(String[] args) {
        if (args.length == 2) return onlinePlayerCompletions(args[1]);
        if (args.length == 3) return complete(args[2], List.of("1", "2", "5", "10"));
        return List.of();
    }

    private List<String> completeBind(String[] args) {
        if (args.length == 2) return onlineBotCompletions(args[1]);
        if (args.length == 3) return onlinePlayerCompletions(args[2]);
        return List.of();
    }

    private List<String> botNameCompletions(CommandSender sender, String prefix) {
        List<String> names = new ArrayList<>();
        if (sender.hasPermission("kscompat.bot.admin")) {
            for (BotSlot slot : store.records()) addBotName(names, slot);
        } else if (sender instanceof Player player) {
            for (BotSlot slot : store.recordsFor(player.getUniqueId())) addBotName(names, slot);
        }
        return complete(prefix, names);
    }

    private List<String> teleportBotNameCompletions(CommandSender sender, String prefix) {
        List<String> names = new ArrayList<>();
        if (sender.hasPermission("kscompat.bot.tp.others")) {
            for (BotSlot slot : store.records()) addBotName(names, slot);
        } else if (sender instanceof Player player) {
            for (BotSlot slot : store.recordsFor(player.getUniqueId())) addBotName(names, slot);
        }
        return complete(prefix, names);
    }

    private void addBotName(List<String> names, BotSlot slot) {
        if (slot.botName != null && !slot.botName.isBlank() && !names.contains(slot.botName)) {
            names.add(slot.botName);
        }
    }

    private List<String> onlinePlayerCompletions(String prefix) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
        return complete(prefix, names);
    }

    private List<String> onlineBotCompletions(String prefix) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (leaves.isBot(player)) names.add(player.getName());
        }
        return complete(prefix, names);
    }

    private List<String> actionIndexCompletions(CommandSender sender, String botName, String prefix) {
        List<String> options = new ArrayList<>(List.of("all"));
        BotSlot slot = store.findByBotName(botName);
        if (slot != null && canAccessSlot(sender, slot)) {
            Player bot = leaves.getBot(slot.botName);
            for (Map<String, Object> action : leaves.actions(bot)) {
                Object index = action.get("index");
                if (index != null) options.add(String.valueOf(index));
            }
        }
        return complete(prefix, options);
    }

    private boolean canAccessSlot(CommandSender sender, BotSlot slot) {
        if (sender.hasPermission("kscompat.bot.admin")) return true;
        return sender instanceof Player player && slot.ownerUuid != null && slot.ownerUuid.equals(player.getUniqueId());
    }

    private boolean canUseBotCommands(CommandSender sender) {
        if (sender.hasPermission("kscompat.bot.use") || sender.hasPermission("kscompat.bot.admin")
                || sender.hasPermission("kscompat.bot.slots")) return true;
        if (!(sender instanceof Player player)) return false;
        UUID uuid = player.getUniqueId();
        int configuredSlots = defaultPermanentSlots + store.adminSlots(uuid);
        int purchasedSlots = economyEnabled ? store.purchasedSlots(uuid) : 0;
        return configuredSlots + purchasedSlots > 0 || store.activeBotCount(uuid) > 0;
    }

    private List<String> complete(String prefix, List<String> candidates) {
        String needle = prefix == null ? "" : prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            if (candidate.toLowerCase().startsWith(needle) && !out.contains(candidate)) {
                out.add(candidate);
            }
        }
        return out;
    }

    private void handleStatus(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§6[KSBot] §7Leaves可用: §f" + leaves.isAvailable());
            sender.sendMessage("§7Leaves配置: §f" + leaves.leavesSettings());
            return;
        }
        int permanentTotal = defaultPermanentSlots + store.adminSlots(player.getUniqueId())
            + (economyEnabled ? store.purchasedSlots(player.getUniqueId()) : 0);
        int permanentUsed = economyEnabled ? store.usedPermanentSlots(player.getUniqueId())
            : store.activeBotCount(player.getUniqueId());
        int preservedBots = store.revokedAssignedBots(player.getUniqueId());
        int rentedFree = 0;
        int rentedUsed = 0;
        long now = System.currentTimeMillis();
        for (BotSlot slot : store.recordsFor(player.getUniqueId())) {
            if (economyEnabled && slot.kind == BotSlot.Kind.RENTED && !slot.revokedOnRelease && !slot.isExpired(now)) {
                if (slot.hasBot()) rentedUsed++; else rentedFree++;
            }
        }
        player.sendMessage("§6[KSBot] §7永久名额: §f" + permanentUsed + "/" + permanentTotal
            + " §7租赁名额: §f" + rentedUsed + "使用 / " + rentedFree + "空闲");
        if (preservedBots > 0) {
            player.sendMessage("§7重置后保留在场: §f" + preservedBots + " §7（离场后不再可召唤）");
        }
        if (economyEnabled) {
            player.sendMessage("§7租一天: §e" + economy.format(summonPricePerDay)
                + " §7永久买断: §e" + economy.format(permanentSlotPrice));
        } else {
            player.sendMessage("§7经济购买功能: §c关闭 §7请联系管理员申请名额。");
        }
        player.sendMessage("§7Leaves假人: §f" + (leaves.isAvailable() ? "可用" : "不可用"));
    }

    private void handleBuy(CommandSender sender, String[] args) {
        if (!economyEnabled) {
            sender.sendMessage("§cKSBot 经济购买功能已关闭，请联系管理员申请名额。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以购买名额。");
            return;
        }
        if (args.length < 2 || !"permanent".equalsIgnoreCase(args[1])) {
            sender.sendMessage("§c用法: /ksbot buy permanent");
            return;
        }
        if (!economy.has(player, permanentSlotPrice) || !economy.withdraw(player, permanentSlotPrice)) {
            sender.sendMessage("§c余额不足，永久名额价格: " + economy.format(permanentSlotPrice));
            return;
        }
        store.addPurchasedSlots(player.getUniqueId(), 1);
        sender.sendMessage("§a已购买 1 个永久假人名额。");
    }

    private void handleRent(CommandSender sender, String[] args) {
        if (!economyEnabled) {
            sender.sendMessage("§cKSBot 经济租赁功能已关闭，请联系管理员申请名额。");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以租赁名额。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /ksbot rent <天数>");
            return;
        }
        int days = parseInt(args[1], -1);
        if (days <= 0 || days > maxRentDays) {
            sender.sendMessage("§c天数必须在 1-" + maxRentDays + " 之间。");
            return;
        }
        double price = days * summonPricePerDay;
        if (!economy.has(player, price) || !economy.withdraw(player, price)) {
            sender.sendMessage("§c余额不足，租赁费用: " + economy.format(price));
            return;
        }
        BotSlot slot = store.createRental(player, days);
        sender.sendMessage("§a已租赁 1 个假人名额，有效期 " + days + " 天。ID: " + slot.id.substring(0, 8));
    }

    private void handleSummon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以召唤假人。");
            return;
        }
        if (!leaves.isAvailable()) {
            sender.sendMessage("§cLeaves 假人 API 不可用。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /ksbot summon <名字> [皮肤名]");
            return;
        }
        String name = args[1];
        String skinName = args.length >= 3 ? args[2] : null;
        if (!validBotName(name)) {
            sender.sendMessage("§c假人名只能包含英文、数字、下划线，长度最多 " + maxNameLength + "。");
            return;
        }
        if (leaves.getBot(name) != null || store.findByBotName(name) != null) {
            sender.sendMessage("§c这个假人名已经被使用。");
            return;
        }
        BotSlot slot = store.claimSlot(player, preferRentedSlots, defaultPermanentSlots, economyEnabled);
        if (slot == null) {
            sender.sendMessage(economyEnabled
                ? "§c你没有可用假人名额。可用 /ksbot rent <天数> 或 /ksbot buy permanent。"
                : "§c你没有可用假人名额，请联系管理员使用 /ksbot setslots 设置名额。");
            return;
        }
        Player bot = leaves.spawn(name, player.getLocation(), player, skinName);
        if (bot == null) {
            if (slot.kind == BotSlot.Kind.PERMANENT) store.delete(slot);
            sender.sendMessage("§cLeaves 生成假人失败。");
            return;
        }
        slot.invincible = defaultInvincible;
        store.assign(slot, player, bot.getName(), bot.getUniqueId().toString(), bot.getLocation(), skinName);
        applyProtection(bot);
        sender.sendMessage("§a已召唤假人 §f" + bot.getName() + "§a，占用 " + (slot.kind == BotSlot.Kind.RENTED ? "租赁" : "永久") + " 名额。");
    }

    private void handleList(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以查看自己的假人。");
            return;
        }
        List<BotSlot> records = store.recordsFor(player.getUniqueId());
        if (records.isEmpty()) {
            sender.sendMessage("§7你还没有假人名额或假人。");
            return;
        }
        sender.sendMessage("§6[KSBot] 你的假人/名额:");
        for (BotSlot slot : records) {
            String name = slot.botName == null ? "空闲名额" : slot.botName;
            String expire = slot.expiresAt > 0L
                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(slot.expiresAt))
                : "永久";
            sender.sendMessage("§e- " + name + " §7" + slot.kind + " §7到期: §f" + expire
                + " §7无敌: §f" + (slot.invincible ? "开" : "关")
                + " §7无饥饿: §f" + (slot.hungerLocked ? "开" : "关"));
        }
    }

    private void handleTp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以使用。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /ksbot tp <名字>");
            return;
        }
        BotSlot slot = requireTeleportSlot(player, args[1]);
        if (slot == null) return;
        Player bot = leaves.getBot(slot.botName);
        if (bot == null) {
            player.sendMessage("§c假人当前不在线。");
            return;
        }
        if (!teleportManagedBot(bot, player.getLocation())) {
            player.sendMessage("§c假人传送失败。");
            return;
        }
        store.setLocation(slot, bot.getLocation());
        store.save();
        applyProtection(bot);
        player.sendMessage("§a已传送假人到当前位置。");
    }

    private void handleFace(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以使用。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /ksbot face <名字> [yaw] [pitch]");
            return;
        }
        BotSlot slot = requireOwnedSlot(player, args[1]);
        if (slot == null) return;
        Player bot = leaves.getBot(slot.botName);
        if (bot == null) {
            player.sendMessage("§c假人当前不在线。");
            return;
        }
        float yaw = args.length >= 3 ? (float) parseDouble(args[2], player.getLocation().getYaw()) : player.getLocation().getYaw();
        float pitch = args.length >= 4 ? (float) parseDouble(args[3], player.getLocation().getPitch()) : player.getLocation().getPitch();
        Location target = bot.getLocation();
        target.setYaw(yaw);
        target.setPitch(pitch);
        if (!teleportManagedBot(bot, target)) {
            player.sendMessage("§c假人朝向调整失败。");
            return;
        }
        store.setLocation(slot, bot.getLocation());
        store.save();
        player.sendMessage("§a已调整假人朝向。");
    }

    private void handleInvincible(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以使用。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /ksbot invincible <名字> [on|off|toggle]");
            return;
        }
        BotSlot slot = requireOwnedSlot(player, args[1]);
        if (slot == null) return;

        boolean toggle = args.length < 3 || isToggleMode(args[2]);
        Boolean requested = args.length >= 3 && !toggle ? parseToggle(args[2]) : null;
        if (args.length >= 3 && !toggle && requested == null) {
            player.sendMessage("§c用法: /ksbot invincible <名字> [on|off|toggle]");
            return;
        }

        boolean next = toggle ? !slot.invincible : requested;
        store.setInvincible(slot, next);

        Player bot = slot.botName == null ? null : leaves.getBot(slot.botName);
        if (bot != null) applyProtection(bot);

        player.sendMessage("§a已" + (next ? "开启" : "关闭") + "假人 §f" + slot.botName + "§a 的无敌保护"
            + (bot == null ? "，下次上线生效。" : "。"));
    }

    private void handleNoHunger(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以使用。");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("§c用法: /ksbot nohunger <名字> [on|off|toggle]");
            return;
        }
        BotSlot slot = requireOwnedSlot(player, args[1]);
        if (slot == null) return;

        boolean toggle = args.length < 3 || isToggleMode(args[2]);
        Boolean requested = args.length >= 3 && !toggle ? parseToggle(args[2]) : null;
        if (args.length >= 3 && !toggle && requested == null) {
            player.sendMessage("§c用法: /ksbot nohunger <名字> [on|off|toggle]");
            return;
        }

        boolean next = toggle ? !slot.hungerLocked : requested;
        store.setHungerLocked(slot, next);
        Player bot = slot.botName == null ? null : leaves.getBot(slot.botName);
        if (next && bot != null) applyHungerLock(bot, slot);

        player.sendMessage("§a已" + (next ? "开启" : "关闭") + "假人 §f" + slot.botName + "§a 的无饥饿保护"
            + (bot == null ? "，下次上线生效。" : "。"));
    }

    private void handleSettings(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kscompat.bot.admin")) {
            sender.sendMessage("You do not have permission to change KSBot settings.");
            return;
        }
        if (args.length == 1 || "list".equalsIgnoreCase(args[1])) {
            sender.sendMessage("KSBot settings: " + String.join(", ", MANAGEABLE_SETTINGS));
            return;
        }
        if (args.length == 3 && "get".equalsIgnoreCase(args[1])) {
            String key = args[2].toLowerCase(java.util.Locale.ROOT);
            if (!MANAGEABLE_SETTINGS.contains(key)) {
                sender.sendMessage("Unknown KSBot setting. Use /ksbot settings list.");
                return;
            }
            sender.sendMessage(key + " = " + plugin.getConfig().get("modules.bot-manager." + key));
            return;
        }
        if (args.length == 4 && "set".equalsIgnoreCase(args[1])) {
            String key = args[2].toLowerCase(java.util.Locale.ROOT);
            String path = "modules.bot-manager." + key;
            if (!MANAGEABLE_SETTINGS.contains(key)) {
                sender.sendMessage("Unknown KSBot setting. Use /ksbot settings list.");
                return;
            }
            Object value = parseSettingValue(plugin.getConfig().get(path), args[3]);
            if (value == null) {
                sender.sendMessage("Invalid value for " + key + ".");
                return;
            }
            if ("use-tick-timeout".equals(key) && value instanceof Integer timeout && timeout < 32) {
                sender.sendMessage("use-tick-timeout must be at least 32 ticks.");
                return;
            }
            plugin.getConfig().set(path, value);
            plugin.saveConfig();
            plugin.reloadConfig();
            reload();
            sender.sendMessage("KSBot setting " + key + " = " + value + ". Saved and applied.");
            return;
        }
        sender.sendMessage("Usage: /ksbot settings list|get <key>|set <key> <value>");
    }

    private Object parseSettingValue(Object current, String raw) {
        try {
            if (current instanceof Boolean) return parseToggle(raw);
            if (current instanceof Integer) return Integer.parseInt(raw);
            if (current instanceof Long) return Long.parseLong(raw);
            if (current instanceof Float) return Float.parseFloat(raw);
            if (current instanceof Double) return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以使用。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /ksbot remove <名字>");
            return;
        }
        BotSlot slot = requireOwnedSlot(player, args[1]);
        if (slot == null) return;
        Player bot = leaves.getBot(slot.botName);
        int stored = captureBotInventory(slot, bot, "removed");
        if (bot != null) leaves.remove(bot, false);
        store.release(slot);
        player.sendMessage("§a已移除假人，名额已释放。" + storageMessage(stored));
    }

    private void handleAction(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ksbot action <名字> <list|start|stop>");
            return;
        }
        BotSlot slot = requireActionSlot(sender, args[1]);
        if (slot == null) return;
        Player bot = leaves.getBot(slot.botName);
        if (bot == null) {
            sender.sendMessage("§c假人当前不在线。");
            return;
        }

        switch (args[2].toLowerCase()) {
            case "list" -> {
                List<Map<String, Object>> actions = leaves.actions(bot);
                if (actions.isEmpty()) {
                    sender.sendMessage("§7这个假人当前没有动作。");
                    return;
                }
                sender.sendMessage("§6[KSBot] §7" + bot.getName() + " 当前动作:");
                for (Map<String, Object> action : actions) {
                    sender.sendMessage("§e#" + action.get("index") + " §f" + action.get("name")
                        + " §7剩余=" + action.get("remaining") + " 下次tick=" + action.get("tickToNext"));
                }
            }
            case "stop" -> {
                if (args.length < 4) {
                    sender.sendMessage("§c用法: /ksbot action <名字> stop <序号|all>");
                    return;
                }
                if ("all".equalsIgnoreCase(args[3])) {
                    leaves.stopAllActions(bot);
                    sender.sendMessage("§a已停止全部动作。");
                    return;
                }
                int index = parseInt(args[3], -1);
                if (index < 0 || !leaves.stopAction(bot, index)) {
                    sender.sendMessage("§c停止失败，请先用 /ksbot action " + bot.getName() + " list 查看序号。");
                    return;
                }
                sender.sendMessage("§a已停止动作 #" + index + "。");
            }
            case "start" -> handleActionStart(sender, bot, args);
            default -> sender.sendMessage("§c用法: /ksbot action <名字> <list|start|stop>");
        }
    }

    private void handleActionStart(CommandSender sender, Player bot, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§c用法: /ksbot action <名字> start <动作> [参数]");
            sender.sendMessage("§7动作: attack, break, use, use-on, use-on-offhand, use-to, use-to-offhand, drop, fish, jump, sneak, swim, move, swap, rotate");
            return;
        }
        String action = args[3].toLowerCase();
        int interval = actionDefaultIntervalTicks;
        int times = actionDefaultTimes;
        int delay = actionDefaultDelayTicks;
        String extra = null;
        String extra2 = null;

        if ("move".equals(action)) {
            extra = args.length >= 5 ? args[4] : "forward";
        } else if ("rotate".equals(action) || "rotation".equals(action)) {
            extra = args.length >= 5 ? args[4] : String.valueOf(bot.getLocation().getYaw());
            extra2 = args.length >= 6 ? args[5] : String.valueOf(bot.getLocation().getPitch());
        } else {
            interval = args.length >= 5 ? parseInt(args[4], actionDefaultIntervalTicks) : actionDefaultIntervalTicks;
            times = args.length >= 6 ? parseInt(args[5], actionDefaultTimes) : actionDefaultTimes;
            delay = args.length >= 7 ? parseInt(args[6], actionDefaultDelayTicks) : actionDefaultDelayTicks;
            extra = args.length >= 8 ? args[7] : null;
            extra2 = args.length >= 9 ? args[8] : null;

            int minimumInterval = USE_ACTION_KEYS.contains(action)
                ? actionUseMinIntervalTicks : actionMinIntervalTicks;
            if (interval < minimumInterval) {
                sender.sendMessage("§c该动作间隔不能低于 " + minimumInterval + " tick。");
                return;
            }
            if (times == -1) {
                if (!actionAllowInfinite) {
                    sender.sendMessage("§c当前不允许无限次数动作。");
                    return;
                }
            } else if (times < 1 || times > actionMaxTimes) {
                sender.sendMessage("§c动作次数必须在 1-" + actionMaxTimes + " 之间；-1 表示无限。");
                return;
            }
            if (delay < 0 || delay > actionMaxDelayTicks) {
                sender.sendMessage("§c动作延迟必须在 0-" + actionMaxDelayTicks + " tick 之间。");
                return;
            }
        }

        if (!acquireActionStartPermit(sender, bot)) return;
        int activeActions = leaves.actionCount(bot);
        if (activeActions < 0) {
            sender.sendMessage("§c无法读取该假人的动作状态，已拒绝启动以保护服务器。");
            return;
        }
        if (activeActions >= actionMaxConcurrent) {
            sender.sendMessage("§c该假人已有 " + activeActions + " 个动作，已达到上限 " + actionMaxConcurrent
                + "。请先执行 /ksbot action " + bot.getName() + " stop all。");
            return;
        }
        if (actionPreventDuplicateTypes && leaves.hasActionType(bot, action)) {
            sender.sendMessage("§c该假人已有同类型动作，请先停止后再启动。");
            return;
        }

        LeavesBotBridge.ActionResult result = leaves.startAction(bot, action, interval, times, delay, extra, extra2);
        if (!result.ok()) {
            sender.sendMessage("§c动作启动失败: " + result.error());
            return;
        }
        sender.sendMessage("§a已启动动作 §f" + result.name() + "§a。用 §e/ksbot action "
            + bot.getName() + " list §a查看序号。");
    }

    private boolean acquireActionStartPermit(CommandSender sender, Player bot) {
        long now = System.currentTimeMillis();
        UUID botId = bot.getUniqueId();
        long allowedAt = actionStartAllowedAt.getOrDefault(botId, 0L);
        if (now < allowedAt) {
            long lastNotice = actionCooldownNoticeAt.getOrDefault(botId, 0L);
            if (now - lastNotice >= 1000L) {
                sender.sendMessage("§c动作启动过快，请等待 " + Math.max(1L, allowedAt - now) + " 毫秒。");
                actionCooldownNoticeAt.put(botId, now);
            }
            return false;
        }
        actionStartAllowedAt.put(botId, now + actionStartCooldownMillis);
        return true;
    }

    private void handleSetSlots(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kscompat.bot.slots")) {
            sender.sendMessage("§c权限不足。");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ksbot setslots <玩家> <最大假人数量>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        int amount = parseInt(args[2], -1);
        if (amount < 0) {
            sender.sendMessage("§c最大假人数量必须是 0 或更大的整数。");
            return;
        }
        store.setAdminSlots(target.getUniqueId(), amount);
        sender.sendMessage("§a已将 " + args[1] + " 的最大假人数量设为: " + amount);
    }

    private void handleResetSlots(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kscompat.bot.admin")) {
            sender.sendMessage("§c权限不足。");
            return;
        }
        if (args.length < 2 || !"confirm".equalsIgnoreCase(args[1])) {
            sender.sendMessage("§c这会清空所有现有假人名额。已在场的假人保留，离场后不可再用。");
            sender.sendMessage("§e确认执行: /ksbot reset confirm");
            return;
        }
        BotAccountStore.SlotResetResult result = store.resetSlotsPreservingAssignedBots();
        plugin.getConfig().set("modules.bot-manager.player-default-permanent-slots", 0);
        plugin.saveConfig();
        defaultPermanentSlots = 0;
        sender.sendMessage("§a已重置所有假人名额。保留 " + result.preservedAssignedBots()
            + " 个在场假人，删除 " + result.removedUnusedSlots() + " 个闲置名额。");
    }

    private void handleBind(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kscompat.bot.admin")) {
            sender.sendMessage("§c权限不足。");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ksbot bind <在线假人名> <玩家>");
            return;
        }
        Player bot = leaves.getBot(args[1]);
        if (bot == null) {
            sender.sendMessage("§c找不到在线 Leaves 假人。");
            return;
        }
        Player owner = Bukkit.getPlayerExact(args[2]);
        if (owner == null) {
            sender.sendMessage("§c目标玩家需要在线，方便写入名称和名额。");
            return;
        }
        BotSlot slot = store.claimSlot(owner, preferRentedSlots, defaultPermanentSlots, economyEnabled);
        if (slot == null) {
            sender.sendMessage("§c目标玩家没有可用名额。");
            return;
        }
        slot.invincible = defaultInvincible;
        store.assign(slot, owner, bot.getName(), bot.getUniqueId().toString(), bot.getLocation(), null);
        applyProtection(bot);
        sender.sendMessage("§a已绑定假人 " + bot.getName() + " -> " + owner.getName());
    }

    private void handleRestore(CommandSender sender) {
        if (!sender.hasPermission("kscompat.bot.admin")) {
            sender.sendMessage("§c权限不足。");
            return;
        }
        int count = restoreManagedBots();
        sender.sendMessage("§a恢复任务完成，处理 " + count + " 个假人记录。");
    }

    private void handlePurge(CommandSender sender) {
        if (!sender.hasPermission("kscompat.bot.admin")) {
            sender.sendMessage("§c权限不足。");
            return;
        }
        int count = purgeExpiredBots();
        sender.sendMessage("§a已清理过期租赁名额: " + count);
    }

    private void handleStorageCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以打开假人暂存箱。");
            return;
        }
        if (!player.hasPermission("kscompat.bot.storage") && !player.hasPermission("kscompat.bot.admin")) {
            player.sendMessage("§c你没有打开假人暂存箱的权限。");
            return;
        }
        String mode = args.length == 0 ? "open" : args[0].toLowerCase();
        if ("status".equals(mode)) {
            player.sendMessage("§6[KSBot] §7暂存箱中有 §f" + storage.count(player.getUniqueId()) + " §7组物品。");
            return;
        }
        if (!"open".equals(mode)) {
            player.sendMessage("§c用法: /ksbotstorage [open|status]");
            return;
        }
        openStorage(player, 0);
    }

    private void openStorage(Player player, int requestedPage) {
        UUID owner = player.getUniqueId();
        int count = storage.count(owner);
        int pages = Math.max(1, (count + 44) / 45);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        BotStorageInventoryHolder holder = new BotStorageInventoryHolder(owner, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, "§6KSBot 暂存箱 §7" + (page + 1) + "/" + pages);
        holder.bind(inventory);
        List<BotStorageStore.StoredItem> items = storage.page(owner, page, 45);
        for (int slot = 0; slot < items.size(); slot++) {
            BotStorageStore.StoredItem item = items.get(slot);
            inventory.setItem(slot, item.item().clone());
            holder.storageIds.put(slot, item.id());
        }
        if (page > 0) inventory.setItem(45, menuItem(Material.ARROW, "§e上一页"));
        inventory.setItem(49, menuItem(Material.CHEST, "§7点击物品取回到背包"));
        if (page + 1 < pages) inventory.setItem(53, menuItem(Material.ARROW, "§e下一页"));
        player.openInventory(inventory);
    }

    private ItemStack menuItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onStorageClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BotStorageInventoryHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.ownerUuid)) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) return;
        if (rawSlot == 45 && holder.page > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> openStorage(player, holder.page - 1));
            return;
        }
        if (rawSlot == 53) {
            Bukkit.getScheduler().runTask(plugin, () -> openStorage(player, holder.page + 1));
            return;
        }
        String storageId = holder.storageIds.get(rawSlot);
        if (storageId == null) return;
        ItemStack stored = storage.take(holder.ownerUuid, storageId);
        if (stored == null) {
            Bukkit.getScheduler().runTask(plugin, () -> openStorage(player, holder.page));
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stored.clone());
        if (!leftovers.isEmpty()) {
            ItemStack remainder = leftovers.values().iterator().next();
            storage.restore(holder.ownerUuid, storageId, remainder, "inventory-full");
            player.sendMessage("§c背包空间不足，未能取回完整物品。");
        }
        Bukkit.getScheduler().runTask(plugin, () -> openStorage(player, holder.page));
    }

    @EventHandler(ignoreCancelled = true)
    public void onStorageDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BotStorageInventoryHolder) event.setCancelled(true);
    }

    private BotSlot requireOwnedSlot(Player player, String name) {
        BotSlot slot = store.findByBotName(name);
        if (slot == null) {
            player.sendMessage("§c找不到这个假人记录。");
            return null;
        }
        if (!player.hasPermission("kscompat.bot.admin") && (slot.ownerUuid == null || !slot.ownerUuid.equals(player.getUniqueId()))) {
            player.sendMessage("§c这不是你的假人。");
            return null;
        }
        return slot;
    }

    private BotSlot requireActionSlot(CommandSender sender, String name) {
        BotSlot slot = store.findByBotName(name);
        if (slot == null) {
            sender.sendMessage("§c找不到这个假人记录。");
            return null;
        }
        if (!canAccessSlot(sender, slot)) {
            sender.sendMessage("§c你无权控制这个假人。");
            return null;
        }
        return slot;
    }

    private BotSlot requireTeleportSlot(Player player, String name) {
        BotSlot slot = store.findByBotName(name);
        if (slot == null) {
            player.sendMessage("§c找不到这个假人记录。");
            return null;
        }
        if (slot.ownerUuid != null && slot.ownerUuid.equals(player.getUniqueId())) return slot;
        if (!player.hasPermission("kscompat.bot.tp.others")) {
            player.sendMessage("§c你只能传送自己的假人。");
            return null;
        }
        return slot;
    }

    private boolean teleportManagedBot(Player bot, Location target) {
        UUID id = bot.getUniqueId();
        authorizedTeleports.add(id);
        try {
            return bot.teleport(target);
        } finally {
            authorizedTeleports.remove(id);
        }
    }

    public OfflinePlayer payerFor(Player actor) {
        BotSlot slot = store.findByBot(actor);
        if (slot != null && slot.ownerUuid != null) return Bukkit.getOfflinePlayer(slot.ownerUuid);
        if (!leaves.isBot(actor)) return actor;
        UUID owner = leaves.creatorUuid(actor);
        return owner == null ? actor : Bukkit.getOfflinePlayer(owner);
    }

    public boolean isManagedBot(Entity entity) {
        return managedBotSlot(entity) != null;
    }

    public boolean isBotActor(Player player) {
        return player != null && (store.findByBot(player) != null || leaves.isBot(player));
    }

    public List<Map<String, Object>> recordViews() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BotSlot slot : store.records()) out.add(slot.view());
        return out;
    }

    public Map<String, Object> settingsView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("economyEnabled", economyEnabled);
        out.put("summonPricePerDay", summonPricePerDay);
        out.put("permanentSlotPrice", permanentSlotPrice);
        out.put("maxRentDaysPerPurchase", maxRentDays);
        out.put("defaultInvincible", defaultInvincible);
        out.put("records", recordViews());
        out.put("leaves", leaves.leavesSettings());
        return out;
    }

    public void updateSettingsFromWeb(Map<String, Object> body) {
        String path = "modules.bot-manager.";
        if (body.containsKey("enabled")) plugin.getConfig().set(path + "enabled", Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("economyEnabled")) plugin.getConfig().set(path + "economy-enabled", Boolean.TRUE.equals(body.get("economyEnabled")));
        if (body.containsKey("defaultInvincible")) plugin.getConfig().set(path + "default-invincible", Boolean.TRUE.equals(body.get("defaultInvincible")));
        if (body.get("summonPricePerDay") instanceof Number n) plugin.getConfig().set(path + "summon-price-per-day", n.doubleValue());
        if (body.get("permanentSlotPrice") instanceof Number n) plugin.getConfig().set(path + "permanent-slot-price", n.doubleValue());
        if (body.get("maxRentDaysPerPurchase") instanceof Number n) plugin.getConfig().set(path + "max-rent-days-per-purchase", n.intValue());
        plugin.saveConfig();
        reload();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerCommand(ServerCommandEvent event) {
        prepareForShutdownCommand(event.getCommand(), "server-command");
    }

    /** PlayerCommandPreprocessEvent is the only early Bukkit hook for an OP typing /stop. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerStopCommand(PlayerCommandPreprocessEvent event) {
        prepareForShutdownCommand(event.getMessage(), "player-command");
    }

    private void prepareForShutdownCommand(String command, String source) {
        if (command == null) return;
        String root = command.trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        while (root.startsWith("/")) root = root.substring(1);
        int namespace = root.indexOf(':');
        if (namespace >= 0) root = root.substring(namespace + 1);
        if ("stop".equals(root) || "restart".equals(root)) prepareForShutdown(source + "-" + root);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBotDamage(EntityDamageEvent event) {
        BotSlot slot = managedBotSlot(event.getEntity());
        if (!protectOwnedBots || !cancelDamage || slot == null || !slot.invincible) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBotFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player bot)) return;
        BotSlot slot = managedBotSlot(bot);
        if (slot == null || !slot.hungerLocked) return;
        event.setCancelled(true);
        applyHungerLock(bot, slot);
    }

    /**
     * Prevents generic /tp, TPA and other plugin teleports from turning a bot
     * inventory into a remote item transport channel. KSBot's own command marks
     * its teleport for this synchronous event before calling Player#teleport.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onManagedBotTeleport(PlayerTeleportEvent event) {
        Player bot = event.getPlayer();
        if (managedBotSlot(bot) == null || authorizedTeleports.contains(bot.getUniqueId())) return;
        if (allowPortalTeleports && isPortalTeleport(event.getCause())) return;
        if (blockExternalTeleports) event.setCancelled(true);
    }

    private boolean isPortalTeleport(PlayerTeleportEvent.TeleportCause cause) {
        return cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
            || cause == PlayerTeleportEvent.TeleportCause.END_PORTAL
            || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBotVelocity(PlayerVelocityEvent event) {
        BotSlot slot = managedBotSlot(event.getPlayer());
        if (!protectOwnedBots || !cancelKnockback || slot == null || !slot.invincible) return;
        event.setCancelled(true);
        event.getPlayer().setVelocity(new org.bukkit.util.Vector(0, 0, 0));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBotDeath(PlayerDeathEvent event) {
        if (managedBotSlot(event.getPlayer()) == null) return;
        event.setKeepInventory(true);
        recoverBotDeath(event.getPlayer(), event.getDrops(), "player-death");
    }

    /**
     * Some Leaves bot deaths skip PlayerDeathEvent and go straight to a disconnect.
     * Mark the slot at the final damage stage so the disconnect path cannot retain it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player bot)) return;
        BotSlot slot = managedBotSlot(bot);
        if (slot == null || bot.getHealth() - event.getFinalDamage() > 0.0D) return;
        if (!deathRelease.add(bot.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin,
            () -> finishBotDeath(slot, bot, bot.getUniqueId(), "lethal-damage"), 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBotEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player bot) {
            recoverBotDeath(bot, event.getDrops(), "entity-death");
        }
    }

    private void recoverBotDeath(Player bot, List<ItemStack> drops, String source) {
        BotSlot slot = managedBotSlot(bot);
        if (slot == null) return;
        drops.clear();
        deathRelease.add(bot.getUniqueId());
        Bukkit.getScheduler().runTask(plugin,
            () -> finishBotDeath(slot, bot, bot.getUniqueId(), source));
    }

    private void finishBotDeath(BotSlot slot, Player bot, UUID botId, String source) {
        if (!deathRelease.contains(botId)) return;
        int stored = captureBotInventory(slot, bot, source);
        Player onlineBot = slot.botName == null ? null : leaves.getBot(slot.botName);
        if (onlineBot != null) leaves.remove(onlineBot, false);
        if (deathRelease.remove(botId)) {
            store.release(slot);
            plugin.getLogger().info("Released KSBot slot after death: " + slot.botName
                + " (recovered " + stored + " item stack(s)).");
        }
    }

    @EventHandler
    public void onBotQuit(PlayerQuitEvent event) {
        actionStartAllowedAt.remove(event.getPlayer().getUniqueId());
        actionCooldownNoticeAt.remove(event.getPlayer().getUniqueId());
        if (!leaves.isBot(event.getPlayer())) return;
        BotSlot slot = store.findByBot(event.getPlayer());
        if (slot != null) {
            captureBotInventory(slot, event.getPlayer(), shuttingDown ? "server-stop" : "disconnect");
            store.setLocation(slot, event.getPlayer().getLocation());
            UUID botId = event.getPlayer().getUniqueId();
            if (event.getPlayer().isDead() || event.getPlayer().getHealth() <= 0.0D) deathRelease.add(botId);
            if (deathRelease.remove(botId)) {
                store.release(slot);
                plugin.getLogger().info("Released KSBot slot after bot death disconnect: " + slot.botName);
            }
            store.save();
        }
    }

    private int captureBotInventory(BotSlot slot, Player bot, String source) {
        if (slot == null || slot.ownerUuid == null || bot == null) return 0;
        List<ItemStack> recovered = new ArrayList<>();
        boolean readLeavesInventory = addLeavesInternalInventory(recovered, bot);
        if (!readLeavesInventory) addInventoryItems(recovered, bot.getInventory().getStorageContents());
        addInventoryItems(recovered, bot.getInventory().getArmorContents());
        ItemStack offHand = bot.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() != Material.AIR && offHand.getAmount() > 0) recovered.add(offHand.clone());
        if (recovered.isEmpty()) return 0;
        int stored = storage.store(slot.ownerUuid, recovered, source);
        if (stored == recovered.size()) {
            if (readLeavesInventory) clearLeavesInternalInventory(bot);
            bot.getInventory().clear();
            bot.getInventory().setArmorContents(new ItemStack[4]);
            bot.getInventory().setItemInOffHand(null);
            bot.getInventory().setItemInMainHand(null);
        } else if (stored > 0) {
            plugin.getLogger().warning("KSBot storage write was incomplete; left bot inventory untouched for retry.");
        }
        return stored;
    }

    /** Leaves keeps ServerBot items in an NMS list that can be absent from Bukkit storage contents. */
    private boolean addLeavesInternalInventory(List<ItemStack> recovered, Player bot) {
        try {
            Object handle = bot.getClass().getMethod("getHandle").invoke(bot);
            Object inventory = handle.getClass().getMethod("getInventory").invoke(handle);
            Object rawItems = inventory.getClass().getMethod("getNonEquipmentItems").invoke(inventory);
            if (!(rawItems instanceof List<?> items)) return false;
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            boolean hasRawItems = false;
            int convertedCount = 0;
            for (Object raw : items) {
                if (raw == null || Boolean.TRUE.equals(raw.getClass().getMethod("isEmpty").invoke(raw))) continue;
                hasRawItems = true;
                Object convertedItem = craftItemStack.getMethod("asBukkitCopy", raw.getClass()).invoke(null, raw);
                if (convertedItem instanceof ItemStack item && item.getType() != Material.AIR && item.getAmount() > 0) {
                    recovered.add(item.clone());
                    convertedCount++;
                }
            }
            return !hasRawItems || convertedCount > 0;
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().fine("Could not read Leaves internal bot inventory: " + e.getMessage());
            return false;
        }
    }

    private void clearLeavesInternalInventory(Player bot) {
        try {
            Object handle = bot.getClass().getMethod("getHandle").invoke(bot);
            Object inventory = handle.getClass().getMethod("getInventory").invoke(handle);
            Object rawItems = inventory.getClass().getMethod("getNonEquipmentItems").invoke(inventory);
            if (!(rawItems instanceof List<?> items)) return;
            Object empty = Class.forName("net.minecraft.world.item.ItemStack").getField("EMPTY").get(null);
            @SuppressWarnings("unchecked") List<Object> mutableItems = (List<Object>) items;
            for (int i = 0; i < mutableItems.size(); i++) mutableItems.set(i, empty);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("Could not clear Leaves internal bot inventory: " + e.getMessage());
        }
    }

    private boolean hasLeavesInternalItems(Player bot) {
        try {
            Object handle = bot.getClass().getMethod("getHandle").invoke(bot);
            Object inventory = handle.getClass().getMethod("getInventory").invoke(handle);
            Object rawItems = inventory.getClass().getMethod("getNonEquipmentItems").invoke(inventory);
            if (!(rawItems instanceof List<?> items)) return false;
            for (Object raw : items) {
                if (raw != null && !Boolean.TRUE.equals(raw.getClass().getMethod("isEmpty").invoke(raw))) return true;
            }
        } catch (ReflectiveOperationException ignored) {
            // Bukkit inventory is used as the fallback when Leaves internals are unavailable.
        }
        return false;
    }

    private void addInventoryItems(List<ItemStack> out, ItemStack[] contents) {
        if (contents == null) return;
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) out.add(item.clone());
        }
    }

    private String storageMessage(int stored) {
        return stored > 0 ? " §7物品已存入你的暂存箱: §f" + stored + " §7组。" : "";
    }

    private void saveManagedBotInventories(String source) {
        int stored = 0;
        int despawned = 0;
        for (BotSlot slot : store.records()) {
            if (!slot.hasBot()) continue;
            Player bot = leaves.getBot(slot.botName);
            if (bot == null) continue;
            store.setLocation(slot, bot.getLocation());
            stored += captureBotInventory(slot, bot, source);

            // Do not leave a managed bot for Leaves' shutdown handler to save or drop.
            if (hasRecoverableInventory(bot)) {
                plugin.getLogger().warning("KSBot storage was incomplete; kept " + bot.getName()
                    + " in Leaves emergency storage so its inventory is not dropped during shutdown.");
                if (leaves.remove(bot, true)) despawned++;
                continue;
            }
            // Leaves' remove(false) invokes ServerBot.dropAll(true). Keep the bot in
            // Leaves' own save path after our independent recovery so nothing reaches
            // the world if a non-standard inventory slot was missed.
            if (leaves.remove(bot, true)) {
                despawned++;
            } else {
                plugin.getLogger().warning("Could not safely despawn KSBot " + bot.getName()
                    + " before shutdown; its restored record was retained.");
            }
        }
        if (stored > 0 || despawned > 0) {
            plugin.getLogger().info("Recovered " + stored + " KSBot item stack(s) and safely despawned "
                + despawned + " bot(s) before shutdown.");
        }
    }

    private void prepareForShutdown(String source) {
        if (shutdownPrepared) return;
        shutdownPrepared = true;
        shuttingDown = true;
        plugin.getLogger().info("Shutdown signal received; securing KSBot inventories before Leaves disconnects bots.");
        saveManagedBotInventories(source);
    }

    private boolean hasRecoverableInventory(Player bot) {
        if (bot == null) return false;
        if (hasLeavesInternalItems(bot)) return true;
        for (ItemStack item : bot.getInventory().getStorageContents()) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) return true;
        }
        for (ItemStack item : bot.getInventory().getArmorContents()) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) return true;
        }
        ItemStack offHand = bot.getInventory().getItemInOffHand();
        return offHand != null && offHand.getType() != Material.AIR && offHand.getAmount() > 0;
    }

    private int purgeExpiredBots() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (BotSlot slot : store.records()) {
            if (slot.revokedOnRelease || !slot.isExpired(now)) continue;
            Player bot = slot.hasBot() ? leaves.getBot(slot.botName) : null;
            captureBotInventory(slot, bot, "rental-expired");
            if (bot != null) leaves.remove(bot, false);
            store.delete(slot);
            removed++;
        }
        return removed;
    }

    private int restoreManagedBots() {
        if (!enabled || !leaves.isAvailable()) return 0;
        int handled = 0;
        long now = System.currentTimeMillis();
        for (BotSlot slot : store.records()) {
            if (!slot.hasBot() || (!slot.revokedOnRelease && slot.isExpired(now))) continue;
            Player existing = leaves.getBot(slot.botName);
            if (existing != null) {
                store.assignBot(slot, existing.getName(), existing.getUniqueId().toString(), existing.getLocation(), slot.skinName);
                applyProtection(existing);
                handled++;
                continue;
            }
            Location loc = slot.location();
            if (loc == null) continue;
            Player owner = slot.ownerUuid == null ? null : Bukkit.getPlayer(slot.ownerUuid);
            CommandSender creator = owner != null ? owner : Bukkit.getConsoleSender();
            Player bot = leaves.spawn(slot.botName, loc, creator, slot.skinName);
            if (bot != null) {
                OfflinePlayer offlineOwner = slot.ownerUuid == null ? null : Bukkit.getOfflinePlayer(slot.ownerUuid);
                String ownerName = offlineOwner != null && offlineOwner.getName() != null ? offlineOwner.getName() : slot.ownerName;
                slot.ownerName = ownerName;
                store.assignBot(slot, bot.getName(), bot.getUniqueId().toString(), bot.getLocation(), slot.skinName);
                applyProtection(bot);
                handled++;
            }
        }
        return handled;
    }

    private void updateOnlineBotLocations() {
        for (BotSlot slot : store.records()) {
            if (!slot.hasBot()) continue;
            Player bot = leaves.getBot(slot.botName);
            if (bot != null) store.setLocation(slot, bot.getLocation());
        }
        store.save();
    }

    private BotSlot managedBotSlot(Entity entity) {
        if (!(entity instanceof Player player)) return null;
        return store.findByBot(player);
    }

    private void applyProtection(Player bot) {
        if (bot == null) return;
        BotSlot slot = managedBotSlot(bot);
        boolean active = protectOwnedBots && slot != null && slot.invincible;
        bot.setInvulnerable(active && invulnerable);
        if (!active || !resistance255) {
            bot.removePotionEffect(PotionEffectType.RESISTANCE);
        }
        if (active && resistance255) {
            bot.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 254, true, false, false));
        }
        applyHungerLock(bot, slot);
    }

    private void maintainHungerLocks() {
        for (BotSlot slot : store.records()) {
            if (!slot.hungerLocked || !slot.hasBot()) continue;
            Player bot = leaves.getBot(slot.botName);
            if (bot != null) applyHungerLock(bot, slot);
        }
    }

    private void applyHungerLock(Player bot, BotSlot slot) {
        if (bot == null || slot == null || !slot.hungerLocked) return;
        bot.setFoodLevel(20);
        bot.setSaturation(20.0f);
        bot.setExhaustion(0.0f);
    }

    private boolean validBotName(String name) {
        return name != null && name.length() <= maxNameLength && name.matches("[A-Za-z0-9_]+");
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String[] slice(String[] args) {
        if (args == null || args.length < 2) return new String[0];
        String[] out = new String[args.length - 1];
        System.arraycopy(args, 1, out, 0, out.length);
        return out;
    }

    private double parseDouble(String raw, double fallback) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isToggleMode(String raw) {
        String normalized = raw.toLowerCase();
        return "toggle".equals(normalized) || "切换".equals(raw);
    }

    private Boolean parseToggle(String raw) {
        return switch (raw.toLowerCase()) {
            case "on", "true", "enable", "enabled", "1", "yes", "y", "open", "开", "开启" -> true;
            case "off", "false", "disable", "disabled", "0", "no", "n", "close", "关", "关闭" -> false;
            case "toggle", "切换" -> null;
            default -> null;
        };
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6[KSBot]");
        sender.sendMessage("§e/ksbot status §7- 查看名额");
        if (sender.hasPermission("kscompat.bot.slots")) {
            sender.sendMessage("§e/ksbot setslots <玩家> <数量> §7- 设置玩家最大假人数量");
        }
        if (economyEnabled) {
            sender.sendMessage("§e/ksbot rent <天数> §7- 租一个临时名额");
            sender.sendMessage("§e/ksbot buy permanent §7- 买断一个永久名额");
        }
        sender.sendMessage("§e/ksbot summon <名字> [皮肤名] §7- 召唤假人");
        sender.sendMessage("§e/ksbot tp <名字> §7- 传送假人到你这里");
        sender.sendMessage("§e/ksbot face <名字> [yaw] [pitch] §7- 调整朝向");
        sender.sendMessage("§e/ksbot invincible <名字> [on|off|toggle] §7- 开关假人无敌");
        sender.sendMessage("§e/ksbot nohunger <名字> [on|off|toggle] §7- 开关假人无饥饿");
        sender.sendMessage("§e/ksbot action <名字> list §7- 查看动作");
        sender.sendMessage("§e/ksbot action <名字> start <动作> [间隔] [次数] [延迟] §7- 执行动作");
        sender.sendMessage("§e/ksbot action <名字> stop <序号|all> §7- 停止动作");
        if (sender.hasPermission("kscompat.bot.admin")) {
            sender.sendMessage("§e/ksbot reset confirm §7- 清空所有名额，保留已在场假人到离场");
        }
        sender.sendMessage("§e/ksbot remove <名字> §7- 移除假人并释放名额");
    }
}
