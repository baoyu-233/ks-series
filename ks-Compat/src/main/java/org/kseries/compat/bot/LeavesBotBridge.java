package org.kseries.compat.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LeavesBotBridge {

    private final JavaPlugin plugin;
    private Object botManager;
    private boolean available;

    public LeavesBotBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        refresh();
    }

    public void refresh() {
        botManager = null;
        available = false;
        try {
            Method staticGetter = Bukkit.class.getMethod("getBotManager");
            botManager = staticGetter.invoke(null);
        } catch (Exception ignored) {
            try {
                botManager = Bukkit.getServer().getClass().getMethod("getBotManager").invoke(Bukkit.getServer());
            } catch (Exception e) {
                plugin.getLogger().fine("Leaves BotManager unavailable: " + e.getMessage());
            }
        }
        available = botManager != null;
    }

    public boolean isAvailable() {
        return available && botManager != null;
    }

    public Player spawn(String name, Location location, CommandSender creator, String skinName) {
        if (!isAvailable()) return null;
        try {
            Object creatorObj = botManager.getClass().getMethod("botCreator", String.class, Location.class)
                .invoke(botManager, name, location);
            creatorObj.getClass().getMethod("creator", CommandSender.class).invoke(creatorObj, creator);
            if (skinName != null && !skinName.isBlank()) {
                creatorObj.getClass().getMethod("skinName", String.class).invoke(creatorObj, skinName);
            }
            Object bot = creatorObj.getClass().getMethod("spawn").invoke(creatorObj);
            return bot instanceof Player player ? player : null;
        } catch (Exception e) {
            plugin.getLogger().warning("Leaves bot spawn failed: " + e.getMessage());
            return null;
        }
    }

    public Player getBot(String name) {
        if (!isAvailable() || name == null || name.isBlank()) return null;
        try {
            Object bot = botManager.getClass().getMethod("getBot", String.class).invoke(botManager, name);
            return bot instanceof Player player ? player : null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean remove(Player bot, boolean saveToLeaves) {
        if (bot == null) return false;
        try {
            Object result = bot.getClass().getMethod("remove", boolean.class).invoke(bot, saveToLeaves);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    public UUID creatorUuid(Player bot) {
        if (bot == null) return null;
        try {
            Object uuid = bot.getClass().getMethod("getCreatePlayerUUID").invoke(bot);
            return uuid instanceof UUID id ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean face(Player bot, float yaw, float pitch) {
        if (bot == null) return false;
        Location loc = bot.getLocation();
        loc.setYaw(yaw);
        loc.setPitch(pitch);
        return bot.teleport(loc);
    }

    public ActionResult startAction(Player bot, String key, int intervalTicks, int times, int delayTicks,
                                    String extra, String extra2) {
        if (bot == null) return ActionResult.fail("bot_not_found");
        String className = actionClassName(key);
        if (className == null) return ActionResult.fail("unknown_action");
        try {
            Object action = Class.forName("org.leavesmc.leaves.entity.bot.action." + className)
                .getMethod("create")
                .invoke(null);

            configureCommonAction(action, key, intervalTicks, times, delayTicks, extra, extra2);

            Class<?> botActionClass = Class.forName("org.leavesmc.leaves.entity.bot.action.BotAction");
            bot.getClass().getMethod("addAction", botActionClass).invoke(bot, action);

            String name = stringMethod(action, "getName", key);
            String uuid = stringMethod(action, "getUUID", "");
            return ActionResult.ok(name, uuid);
        } catch (Exception e) {
            return ActionResult.fail(e.getMessage());
        }
    }

    public List<Map<String, Object>> actions(Player bot) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (bot == null) return out;
        try {
            int size = ((Number) bot.getClass().getMethod("getActionSize").invoke(bot)).intValue();
            for (int i = 0; i < size; i++) {
                Object action = bot.getClass().getMethod("getAction", int.class).invoke(bot, i);
                if (action == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", i);
                row.put("name", stringMethod(action, "getName", action.getClass().getSimpleName()));
                row.put("uuid", stringMethod(action, "getUUID", ""));
                row.put("tickToNext", intMethod(action, "getTickToNext", -1));
                row.put("remaining", intMethod(action, "getDoNumberRemaining", -1));
                out.add(row);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public int actionCount(Player bot) {
        if (bot == null) return -1;
        try {
            return ((Number) bot.getClass().getMethod("getActionSize").invoke(bot)).intValue();
        } catch (Exception ignored) {
            return -1;
        }
    }

    public boolean hasActionType(Player bot, String key) {
        if (bot == null) return false;
        String expected = actionClassName(key);
        if (expected == null) return false;
        try {
            int size = actionCount(bot);
            if (size < 0) return true;
            for (int i = 0; i < size; i++) {
                Object action = bot.getClass().getMethod("getAction", int.class).invoke(bot, i);
                if (action == null) continue;
                String actual = action.getClass().getSimpleName();
                if (actual.startsWith("Craft")) actual = actual.substring("Craft".length());
                if (expected.equals(actual)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public boolean stopAction(Player bot, int index) {
        if (bot == null) return false;
        try {
            bot.getClass().getMethod("stopAction", int.class).invoke(bot, index);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean stopAllActions(Player bot) {
        if (bot == null) return false;
        try {
            bot.getClass().getMethod("stopAllActions").invoke(bot);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isBot(Entity entity) {
        if (!(entity instanceof Player)) return false;
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

    public Map<String, Object> leavesSettings() {
        Map<String, Object> out = new LinkedHashMap<>();
        File file = new File("leaves.yml");
        out.put("present", file.isFile());
        if (!file.isFile()) return out;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String base = "settings.modify.fakeplayer.";
        out.put("enable", cfg.getBoolean(base + "enable", false));
        out.put("limit", cfg.getInt(base + "limit", -1));
        out.put("residentFakeplayer", cfg.getBoolean(base + "resident-fakeplayer", false));
        out.put("manualSaveAndLoad", cfg.getBoolean(base + "manual-save-and-load", false));
        out.put("modifyConfig", cfg.getBoolean(base + "modify-config", false));
        return out;
    }

    private void configureCommonAction(Object action, String key, int intervalTicks, int times, int delayTicks,
                                       String extra, String extra2) throws Exception {
        invokeIfPresent(action, "setStartDelayTick", int.class, Math.max(0, delayTicks));
        invokeIfPresent(action, "setDoIntervalTick", int.class, Math.max(1, intervalTicks));
        invokeIfPresent(action, "setDoNumber", int.class, times);
        // Vanilla food and potions need 32 ticks to complete. Keep the setting
        // above that threshold so an action never releases use prematurely.
        int configuredUseTimeout = plugin.getConfig().getInt("modules.bot-manager.use-tick-timeout", 40);
        invokeIfPresent(action, "setUseTickTimeout", int.class, Math.max(32, configuredUseTimeout));

        if ("move".equalsIgnoreCase(key)) {
            String directionName = extra == null || extra.isBlank() ? "FORWARD" : extra.toUpperCase(java.util.Locale.ROOT);
            Class<?> directionClass = Class.forName("org.leavesmc.leaves.entity.bot.action.MoveAction$MoveDirection");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object direction = Enum.valueOf((Class<? extends Enum>) directionClass.asSubclass(Enum.class), directionName);
            action.getClass().getMethod("setDirection", directionClass).invoke(action, direction);
        } else if ("rotate".equalsIgnoreCase(key) || "rotation".equalsIgnoreCase(key)) {
            float yaw = parseFloat(extra, 0.0F);
            float pitch = parseFloat(extra2, 0.0F);
            action.getClass().getMethod("setYaw", float.class).invoke(action, yaw);
            action.getClass().getMethod("setPitch", float.class).invoke(action, pitch);
        }
    }

    private void invokeIfPresent(Object target, String name, Class<?> argType, Object value) {
        try {
            target.getClass().getMethod(name, argType).invoke(target, value);
        } catch (Exception ignored) {
        }
    }

    private String actionClassName(String key) {
        if (key == null) return null;
        return switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "attack" -> "AttackAction";
            case "break", "mine" -> "BreakBlockAction";
            case "drop" -> "DropAction";
            case "fish" -> "FishAction";
            case "jump" -> "JumpAction";
            case "use" -> "UseItemAction";
            case "use-auto", "auto-use" -> "UseItemAutoAction";
            case "use-offhand" -> "UseItemOffhandAction";
            case "use-on", "place" -> "UseItemOnAction";
            case "use-on-offhand" -> "UseItemOnOffhandAction";
            case "use-to" -> "UseItemToAction";
            case "use-to-offhand" -> "UseItemToOffhandAction";
            case "sneak" -> "SneakAction";
            case "swim" -> "SwimAction";
            case "move" -> "MoveAction";
            case "mount" -> "MountAction";
            case "swap" -> "SwapAction";
            case "rotate", "rotation" -> "RotationAction";
            default -> null;
        };
    }

    private String stringMethod(Object target, String name, String fallback) {
        try {
            Object value = target.getClass().getMethod(name).invoke(target);
            return value == null ? fallback : String.valueOf(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private int intMethod(Object target, String name, int fallback) {
        try {
            Object value = target.getClass().getMethod(name).invoke(target);
            return value instanceof Number n ? n.intValue() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private float parseFloat(String raw, float fallback) {
        try {
            return raw == null ? fallback : Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public record ActionResult(boolean ok, String name, String uuid, String error) {
        static ActionResult ok(String name, String uuid) {
            return new ActionResult(true, name, uuid, null);
        }

        static ActionResult fail(String error) {
            return new ActionResult(false, null, null, error);
        }
    }
}
