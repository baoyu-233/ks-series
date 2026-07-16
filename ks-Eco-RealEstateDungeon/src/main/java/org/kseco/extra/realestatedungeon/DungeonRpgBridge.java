package org.kseco.extra.realestatedungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.kseco.KsEco;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Built-in RPG bridge for dungeon completion rewards.
 *
 * reward_config example:
 * {
 *   "money": 1000,
 *   "commands": ["say {player} cleared {template}"],
 *   "mythicItems": [{"id":"RefineToken","amount":1,"chance":1.0}],
 *   "mmoitems": [{"type":"CONSUMABLE","id":"ROCK","amount":1,"chance":1.0}]
 * }
 */
public final class DungeonRpgBridge {

    @FunctionalInterface
    public interface RewardLogger {
        void log(UUID playerUuid, String detail);
    }

    private final KsEco eco;
    private final Random random = new Random();
    private volatile Boolean mmoItemsApiAvailable;

    public DungeonRpgBridge(KsEco eco) {
        this.eco = eco;
    }

    public void grantCompletionRewards(String instanceId, String templateId, String rewardConfig,
                                       Iterable<UUID> participants, RewardLogger logger) {
        if (rewardConfig == null || rewardConfig.isBlank()) return;
        Runnable work = () -> grantCompletionRewardsSync(instanceId, templateId, rewardConfig, participants, logger);
        if (Bukkit.isPrimaryThread()) work.run();
        else Bukkit.getScheduler().runTask(eco, work);
    }

    private void grantCompletionRewardsSync(String instanceId, String templateId, String rewardConfig,
                                            Iterable<UUID> participants, RewardLogger logger) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(rewardConfig);
            if (!parsed.isJsonObject()) {
                eco.getLogger().warning("[Dungeon RPG] reward_config is not a JSON object: template=" + templateId);
                return;
            }
            root = parsed.getAsJsonObject();
        } catch (Throwable t) {
            eco.getLogger().warning("[Dungeon RPG] invalid reward_config for template " + templateId + ": " + t.getMessage());
            return;
        }

        double money = number(root.get("money"), 0.0);
        JsonArray commands = array(root, "commands");
        JsonArray mythicItems = array(root, "mythicItems");
        if (mythicItems == null) mythicItems = array(root, "mythicmobs");
        if (mythicItems == null) mythicItems = array(root, "mmitems");
        JsonArray mmoItems = array(root, "mmoitems");
        if (mmoItems == null) mmoItems = array(root, "mmoItems");
        if (mmoItems == null) mmoItems = array(root, "items");

        for (UUID uuid : participants) {
            if (uuid == null) continue;
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            Player online = Bukkit.getPlayer(uuid);
            String playerName = online != null ? online.getName()
                    : (offline.getName() == null ? uuid.toString() : offline.getName());

            int commandCount = grantCommands(commands, uuid, playerName, instanceId, templateId);
            boolean paid = grantMoney(offline, uuid, playerName, money);
            int mythicItemCount = grantMythicItems(mythicItems, uuid, online, instanceId);
            int itemCount = grantMmoItems(mmoItems, uuid, online, instanceId);

            if (online != null && online.isOnline() && (money > 0 || commandCount > 0 || mythicItemCount > 0 || itemCount > 0)) {
                online.sendMessage("§6[副本] §a通关奖励已发放。");
            }
            if (logger != null && (money > 0 || commandCount > 0 || mythicItemCount > 0 || itemCount > 0)) {
                logger.log(uuid, "template=" + templateId + " money=" + (paid ? money : 0)
                        + " commands=" + commandCount + " mythicitems=" + mythicItemCount + " mmoitems=" + itemCount);
            }
        }
    }

    private int grantCommands(JsonArray commands, UUID uuid, String playerName, String instanceId, String templateId) {
        if (commands == null || commands.isEmpty()) return 0;
        int done = 0;
        for (JsonElement element : commands) {
            String command;
            double chance = 1.0;
            boolean onlineOnly = false;
            if (element.isJsonPrimitive()) {
                command = element.getAsString();
            } else if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                command = string(obj, "command", "");
                chance = chance(obj.get("chance"));
                onlineOnly = bool(obj, "onlineOnly", false);
            } else {
                continue;
            }
            Player online = Bukkit.getPlayer(uuid);
            if (onlineOnly && (online == null || !online.isOnline())) continue;
            if (command == null || command.isBlank() || !roll(chance)) continue;
            String resolved = placeholders(command, uuid, playerName, instanceId, templateId);
            if (resolved.startsWith("/")) resolved = resolved.substring(1);
            if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)) done++;
        }
        return done;
    }

    private boolean grantMoney(OfflinePlayer offline, UUID uuid, String name, double money) {
        if (money <= 0) return false;
        if (eco.vaultHook() != null && eco.vaultHook().isAvailable()) {
            return eco.vaultHook().deposit(offline, money);
        }
        if (eco.builtinEconomy() != null) {
            eco.builtinEconomy().deposit(uuid, name, money);
            return true;
        }
        return false;
    }

    private int grantMmoItems(JsonArray items, UUID uuid, Player online, String instanceId) {
        if (items == null || items.isEmpty()) return 0;
        int given = 0;
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            if (!roll(chance(obj.get("chance")))) continue;
            String type = string(obj, "type", "");
            String id = string(obj, "id", "");
            int amount = Math.max(1, (int) number(obj.get("amount"), 1));
            if (type.isBlank() || id.isBlank()) continue;

            ItemStack unit = createMmoItem(type, id);
            if (unit == null || unit.getType().isAir()) {
                eco.getLogger().warning("[Dungeon RPG] MMOItems reward not found: " + type + "." + id);
                continue;
            }
            given += giveItemCopies(uuid, online, unit, amount, "DUNGEON_REWARD:" + instanceId);
        }
        return given;
    }

    private int grantMythicItems(JsonArray items, UUID uuid, Player online, String instanceId) {
        if (items == null || items.isEmpty()) return 0;
        int given = 0;
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            if (!roll(chance(obj.get("chance")))) continue;
            String id = string(obj, "id", "");
            int amount = Math.max(1, (int) number(obj.get("amount"), 1));
            if (id.isBlank()) continue;

            ItemStack item = createMythicItem(id, amount);
            if (item == null || item.getType().isAir()) {
                eco.getLogger().warning("[Dungeon RPG] MythicMobs item reward not found: " + id);
                continue;
            }
            given += giveItemStack(uuid, online, item, "DUNGEON_REWARD:" + instanceId);
        }
        return given;
    }

    private int giveItemCopies(UUID uuid, Player online, ItemStack unit, int amount, String source) {
        int left = amount;
        int given = 0;
        int maxStack = Math.max(1, unit.getMaxStackSize());
        while (left > 0) {
            ItemStack copy = unit.clone();
            int stackAmount = Math.min(left, maxStack);
            copy.setAmount(stackAmount);
            left -= stackAmount;
            given += stackAmount;
            if (online != null && online.isOnline()) {
                Map<Integer, ItemStack> overflow = online.getInventory().addItem(copy);
                for (ItemStack item : overflow.values()) {
                    eco.storageManager().storeItem(uuid, item, source);
                }
            } else {
                eco.storageManager().storeItem(uuid, copy, source);
            }
        }
        return given;
    }

    private int giveItemStack(UUID uuid, Player online, ItemStack item, String source) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return 0;
        int amount = item.getAmount();
        if (online != null && online.isOnline()) {
            Map<Integer, ItemStack> overflow = online.getInventory().addItem(item);
            for (ItemStack left : overflow.values()) {
                eco.storageManager().storeItem(uuid, left, source);
            }
        } else {
            eco.storageManager().storeItem(uuid, item, source);
        }
        return amount;
    }

    private ItemStack createMmoItem(String typeId, String itemId) {
        if (Boolean.FALSE.equals(mmoItemsApiAvailable)) return null;
        if (Bukkit.getPluginManager().getPlugin("MMOItems") == null) {
            mmoItemsApiAvailable = false;
            return null;
        }
        try {
            Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
            Field pluginField = mmoItemsClass.getField("plugin");
            Object plugin = pluginField.get(null);
            if (plugin == null) return null;
            Method typeGet = typeClass.getMethod("get", String.class);
            Object type = typeGet.invoke(null, typeId.toUpperCase(Locale.ROOT));
            if (type == null) return null;
            Method getItem = mmoItemsClass.getMethod("getItem", typeClass, String.class);
            Object item = getItem.invoke(plugin, type, itemId);
            if (item == null) item = getItem.invoke(plugin, type, itemId.toUpperCase(Locale.ROOT));
            mmoItemsApiAvailable = true;
            return item instanceof ItemStack stack ? stack : null;
        } catch (Throwable t) {
            mmoItemsApiAvailable = false;
            eco.getLogger().warning("[Dungeon RPG] MMOItems API unavailable: " + t.getMessage());
            return null;
        }
    }

    private ItemStack createMythicItem(String itemId, int amount) {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) return null;
        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mythicBukkitClass.getMethod("inst").invoke(null);
            if (inst == null) return null;
            Object itemManager = inst.getClass().getMethod("getItemManager").invoke(inst);
            if (itemManager == null) return null;
            Method getItemStack = itemManager.getClass().getMethod("getItemStack", String.class, int.class);
            Object item = getItemStack.invoke(itemManager, itemId, amount);
            if (item == null) item = getItemStack.invoke(itemManager, itemId.toLowerCase(Locale.ROOT), amount);
            if (item == null) item = getItemStack.invoke(itemManager, itemId.toUpperCase(Locale.ROOT), amount);
            return item instanceof ItemStack stack ? stack : null;
        } catch (Throwable t) {
            eco.getLogger().warning("[Dungeon RPG] MythicMobs item API unavailable: " + t.getMessage());
            return null;
        }
    }

    private String placeholders(String text, UUID uuid, String playerName, String instanceId, String templateId) {
        return text
                .replace("{player}", playerName)
                .replace("{uuid}", uuid.toString())
                .replace("{instance}", instanceId)
                .replace("{template}", templateId);
    }

    private static JsonArray array(JsonObject root, String key) {
        JsonElement e = root.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String string(JsonObject obj, String key, String def) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static boolean bool(JsonObject obj, String key, boolean def) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : def;
    }

    private static double number(JsonElement e, double def) {
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def;
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static double chance(JsonElement e) {
        double chance = number(e, 1.0);
        if (chance > 1.0) chance = chance / 100.0;
        if (chance < 0.0) return 0.0;
        if (chance > 1.0) return 1.0;
        return chance;
    }

    private boolean roll(double chance) {
        return chance >= 1.0 || (chance > 0.0 && random.nextDouble() <= chance);
    }
}
