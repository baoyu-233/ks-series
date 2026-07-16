package org.itemedit;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * 所有对 MythicMobs 的访问通过反射，零编译期依赖。
 * MM 版本升级只需替换运行时的 MM jar，ItemEditor 无需重编译。
 */
public final class MythicMobsHook {

    private MythicMobsHook() {}

    // ==================== 反射缓存 ====================

    private static Object mythicBukkit;   // MythicBukkit 单例
    private static Method getItemStack;   // ItemManager.getItemStack(String)
    private static boolean initAttempted;
    private static boolean initialized;

    private static synchronized void init() {
        if (initAttempted) return;
        initAttempted = true;
        try {
            Plugin mm = Bukkit.getPluginManager().getPlugin("MythicMobs");
            if (mm == null) return;

            // MythicBukkit.inst() — 静态方法
            Class<?> mbCls = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Method inst = mbCls.getMethod("inst");
            mythicBukkit = inst.invoke(null);

            // ItemManager.getItemStack(String)
            Method getIM = mythicBukkit.getClass().getMethod("getItemManager");
            Object itemManager = getIM.invoke(mythicBukkit);
            getItemStack = itemManager.getClass().getMethod("getItemStack", String.class);

            initialized = true;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[ItemEditor] MythicMobs 反射初始化失败: " + e.getMessage());
        }
    }

    public static boolean isAvailable() {
        if (!initAttempted) init();
        return initialized;
    }

    /**
     * 获取 MythicMobs 物品的 ItemStack 参考。
     */
    public static ItemStack getItemStack(String mmId) {
        if (!isAvailable() || mmId == null) return null;
        try {
            return (ItemStack) getItemStack.invoke(mythicBukkit.getClass()
                    .getMethod("getItemManager").invoke(mythicBukkit), mmId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过 PersistentDataContainer 检测物品是否为指定 ID 的 MM 物品。
     */
    public static boolean isMythicMobsItem(ItemStack item, String mmId) {
        if (!isAvailable() || item == null || mmId == null) return false;
        return matchesByPdc(item, mmId);
    }

    // ==================== PDC 匹配（不依赖 MM API） ====================

    private static boolean matchesByPdc(ItemStack item, String targetId) {
        var meta = item.getItemMeta();
        if (meta == null) return false;
        var pdc = meta.getPersistentDataContainer();

        // MM 5.x 可能使用的 PDC 键
        String[][] candidates = {
            {"mythicmobs", "mythic_type"},
            {"mythicmobs", "item_type"},
            {"mythicmobs", "type"},
            {"mythicmobs", "mythicmobs_type"},
            {"mmoitems", "type"},
            {"mythicmobs", "items_type"},
        };

        for (String[] pair : candidates) {
            try {
                NamespacedKey nk = new NamespacedKey(pair[0], pair[1]);
                String val = pdc.get(nk, PersistentDataType.STRING);
                if (targetId.equals(val)) return true;
            } catch (IllegalArgumentException ignored) {}
        }

        // 遍历 PDC 所有键兜底
        try {
            for (NamespacedKey existingKey : pdc.getKeys()) {
                if (!"mythicmobs".equals(existingKey.getNamespace())
                    && !"mmoitems".equals(existingKey.getNamespace())) continue;
                String val = pdc.get(existingKey, PersistentDataType.STRING);
                if (targetId.equals(val)) return true;
            }
        } catch (Exception ignored) {}

        return false;
    }
}
