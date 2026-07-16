package org.itemedit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;

/**
 * 所有对 FotiaEnchantment 的访问都通过反射，零编译期依赖。
 * FE 版本升级只需替换运行时的 FE jar，ItemEditor 无需重编译。
 */
public final class FotiaEnchantmentHook {

    private FotiaEnchantmentHook() {}

    // ==================== 反射缓存 ====================

    private static Object fePlugin;          // FotiaEnchantment 主类实例
    private static Object enchantManager;    // EnchantmentManager 实例
    private static Object pdcManager;        // PDCManager 实例
    private static Object languageManager;   // LanguageManager 实例
    private static boolean initialized;
    private static boolean initAttempted;

    private static Method pdcGetEnchants;
    private static Method pdcGetLevel;
    private static Method pdcAdd;
    private static Method pdcRemove;
    private static Method emGetEnabled;
    private static Method emGetEnchantment;
    private static Method emGetApplicable;
    private static Method lmGetName;
    private static Method lmGetDesc;
    private static Method loreStrip;
    private static Method loreApply;
    private static Method edGetId;
    private static Method edGetMaxLevel;
    private static Method edGetCategory;
    private static Method edGetConflicts;

    /** 尝试初始化所有反射句柄（仅执行一次）。 */
    private static synchronized void init() {
        if (initAttempted) return;
        initAttempted = true;
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("FotiaEnchantment");
            if (p == null) return;
            fePlugin = p;

            // --- 主类方法 ---
            Class<?> feCls = fePlugin.getClass();
            // EnchantmentManager getEnchantmentManager()
            Method getEM = feCls.getMethod("getEnchantmentManager");
            enchantManager = getEM.invoke(fePlugin);
            Class<?> emCls = enchantManager.getClass();

            // PDCManager getPdcManager()
            Method getPDC = emCls.getMethod("getPdcManager");
            pdcManager = getPDC.invoke(enchantManager);
            Class<?> pdcCls = pdcManager.getClass();

            // LanguageManager getLanguageManager()
            Method getLM = feCls.getMethod("getLanguageManager");
            languageManager = getLM.invoke(fePlugin);
            Class<?> lmCls = languageManager.getClass();

            // --- PDCManager 方法 ---
            pdcGetEnchants = pdcCls.getMethod("getEnchantments", ItemStack.class);
            pdcGetLevel = pdcCls.getMethod("getEnchantmentLevel", ItemStack.class, String.class);
            pdcAdd = pdcCls.getMethod("addEnchantment", ItemStack.class, String.class, int.class);
            pdcRemove = pdcCls.getMethod("removeEnchantment", ItemStack.class, String.class);

            // --- EnchantmentManager 方法 ---
            emGetEnabled = emCls.getMethod("getEnabled");
            emGetEnchantment = emCls.getMethod("getEnchantment", String.class);
            emGetApplicable = emCls.getMethod("getApplicable", ItemStack.class);

            // --- LanguageManager 方法 ---
            lmGetName = lmCls.getMethod("getEnchantName", Player.class, String.class);
            lmGetDesc = lmCls.getMethod("getEnchantDescription", Player.class, String.class);

            // --- EnchantmentData 方法 ---
            // 先取一个实例来获取 Class
            List<?> enabled = (List<?>) emGetEnabled.invoke(enchantManager);
            if (!enabled.isEmpty()) {
                Object sample = enabled.get(0);
                Class<?> edCls = sample.getClass();
                edGetId = edCls.getMethod("getId");
                edGetMaxLevel = edCls.getMethod("getMaxLevel");
                // getCategory / getConflicts 可能不存在
                try { edGetCategory = edCls.getMethod("getCategory"); } catch (NoSuchMethodException ignored) {}
                try { edGetConflicts = edCls.getMethod("getConflicts"); } catch (NoSuchMethodException ignored) {}
            }

            // --- EnchantmentLoreCleaner 静态方法 ---
            Class<?> cleanerCls = Class.forName("gg.fotia.enchantment.lore.item.EnchantmentLoreCleaner");
            loreStrip = cleanerCls.getMethod("stripGeneratedLore", feCls, Player.class, ItemStack.class);
            loreApply = cleanerCls.getMethod("applyGeneratedLore", feCls, Player.class, ItemStack.class);

            initialized = true;
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING,
                    "[ItemEditor] FotiaEnchantment 反射初始化失败: " + e.getMessage());
        }
    }

    public static boolean isAvailable() {
        if (!initAttempted) init();
        return initialized;
    }

    // ==================== Lore 合并 ====================

    public static boolean stripGeneratedLore(Player player, ItemStack item) {
        if (!isAvailable()) return false;
        try { return (boolean) loreStrip.invoke(null, fePlugin, player, item); }
        catch (Exception e) { return false; }
    }

    public static boolean applyGeneratedLore(Player player, ItemStack item) {
        if (!isAvailable()) return false;
        try { return (boolean) loreApply.invoke(null, fePlugin, player, item); }
        catch (Exception e) { return false; }
    }

    // ==================== PDC 自定义附魔 ====================

    @SuppressWarnings("unchecked")
    public static Map<String, Integer> getCustomEnchantments(ItemStack item) {
        if (!isAvailable()) return Collections.emptyMap();
        try { return (Map<String, Integer>) pdcGetEnchants.invoke(pdcManager, item); }
        catch (Exception e) { return Collections.emptyMap(); }
    }

    public static int getCustomEnchantmentLevel(ItemStack item, String id) {
        if (!isAvailable()) return 0;
        try { return (int) pdcGetLevel.invoke(pdcManager, item, id); }
        catch (Exception e) { return 0; }
    }

    public static void setCustomEnchantment(ItemStack item, String id, int level) {
        if (!isAvailable()) return;
        try {
            if (level <= 0) pdcRemove.invoke(pdcManager, item, id);
            else pdcAdd.invoke(pdcManager, item, id, level);
        } catch (Exception ignored) {}
    }

    public static void removeCustomEnchantment(ItemStack item, String id) {
        if (!isAvailable()) return;
        try { pdcRemove.invoke(pdcManager, item, id); }
        catch (Exception ignored) {}
    }

    // ==================== 本地化 ====================

    public static String getEnchantName(Player player, String enchantId) {
        if (!isAvailable()) return enchantId;
        try { return (String) lmGetName.invoke(languageManager, player, enchantId); }
        catch (Exception e) { return enchantId; }
    }

    public static String getDefaultEnchantName(String enchantId) {
        if (!isAvailable()) return enchantId;
        try {
            Player any = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            return (String) lmGetName.invoke(languageManager, any, enchantId);
        } catch (Exception e) { return enchantId; }
    }

    @SuppressWarnings("unchecked")
    public static List<String> getEnchantDescription(Player player, String enchantId) {
        if (!isAvailable()) return Collections.emptyList();
        try { return (List<String>) lmGetDesc.invoke(languageManager, player, enchantId); }
        catch (Exception e) { return Collections.emptyList(); }
    }

    // ==================== 附魔列表 (返回反射数据) ====================

    /** 获取所有已启用的 FE 附魔，返回不依赖 FE API 的纯数据对象列表。 */
    public static List<FeEnchantData> getEnabledEnchantments() {
        if (!isAvailable()) return Collections.emptyList();
        try {
            List<?> list = (List<?>) emGetEnabled.invoke(enchantManager);
            List<FeEnchantData> result = new ArrayList<>();
            for (Object ed : list) {
                String id = (String) edGetId.invoke(ed);
                int maxLevel = (int) edGetMaxLevel.invoke(ed);
                String cat = invokeCategory(ed);
                List<String> conflicts = invokeConflicts(ed);
                result.add(new FeEnchantData(id, maxLevel, cat, conflicts));
            }
            return result;
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "[ItemEditor] 获取 FE 附魔列表失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 获取单个 FE 附魔数据。 */
    public static FeEnchantData getEnchantmentData(String id) {
        if (!isAvailable()) return null;
        try {
            Object ed = emGetEnchantment.invoke(enchantManager, id);
            if (ed == null) return null;
            int maxLevel = (int) edGetMaxLevel.invoke(ed);
            return new FeEnchantData(id, maxLevel, invokeCategory(ed), invokeConflicts(ed));
        } catch (Exception e) { return null; }
    }

    /** 获取适用于某物品的 FE 附魔列表。 */
    public static List<FeEnchantData> getApplicableEnchantments(ItemStack item) {
        if (!isAvailable()) return Collections.emptyList();
        try {
            List<?> list = (List<?>) emGetApplicable.invoke(enchantManager, item);
            List<FeEnchantData> result = new ArrayList<>();
            for (Object ed : list) {
                String id = (String) edGetId.invoke(ed);
                int maxLevel = (int) edGetMaxLevel.invoke(ed);
                result.add(new FeEnchantData(id, maxLevel, invokeCategory(ed), invokeConflicts(ed)));
            }
            return result;
        } catch (Exception e) { return Collections.emptyList(); }
    }

    /** 尝试获取分类（反射，兼容所有 FE 版本）。 */
    public static String getEnchantmentCategory(FeEnchantData data) {
        return data != null ? data.getCategory() : "unknown";
    }

    /** 尝试获取冲突列表（反射，兼容所有 FE 版本）。 */
    @SuppressWarnings("unchecked")
    public static List<String> getEnchantmentConflicts(FeEnchantData data) {
        return data != null ? data.getConflicts() : Collections.emptyList();
    }

    // ==================== 反射内部工具 ====================

    private static String invokeCategory(Object ed) {
        if (edGetCategory == null) return "unknown";
        try { return (String) edGetCategory.invoke(ed); } catch (Exception e) { return "unknown"; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeConflicts(Object ed) {
        if (edGetConflicts == null) return Collections.emptyList();
        try {
            List<?> raw = (List<?>) edGetConflicts.invoke(ed);
            if (raw == null) return Collections.emptyList();
            List<String> out = new ArrayList<>();
            for (Object o : raw) if (o instanceof String) out.add((String) o);
            return out;
        } catch (Exception e) { return Collections.emptyList(); }
    }
}
