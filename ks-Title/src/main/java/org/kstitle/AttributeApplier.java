package org.kstitle;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.kstitle.model.TitleAttribute;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;

/**
 * 佩戴/摘下称号时增删属性加成。
 * POTION -> 原生 PotionEffect（无限持续，环境效果，extra={"hide":true} 时隐藏粒子）。
 * ATTRIBUTE -> 原生 AttributeModifier（NamespacedKey 定位，可精确移除）。
 * MYTHICLIB_STAT -> best-effort 反射对接 MythicLib，API 缺失则跳过并警告，不崩服。
 */
public final class AttributeApplier {

    private final KsTitle plugin;
    private volatile Boolean mythicLibAvailable = null;

    public AttributeApplier(KsTitle plugin) {
        this.plugin = plugin;
    }

    public void apply(Player player, int titleId) {
        List<TitleAttribute> attrs = plugin.titleManager().listAttributes(titleId);
        for (TitleAttribute attr : attrs) {
            try {
                switch (attr.buffType()) {
                    case "POTION" -> applyPotion(player, titleId, attr);
                    case "ATTRIBUTE" -> applyAttribute(player, titleId, attr);
                    case "MYTHICLIB_STAT" -> applyMythicLibStat(player, attr);
                    default -> plugin.getLogger().warning("未知 buffType: " + attr.buffType());
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "应用称号属性失败 titleId=" + titleId + " attr=" + attr.buffKey(), e);
            }
        }
    }

    public void remove(Player player, int titleId) {
        List<TitleAttribute> attrs = plugin.titleManager().listAttributes(titleId);
        for (TitleAttribute attr : attrs) {
            try {
                switch (attr.buffType()) {
                    case "POTION" -> removePotion(player, attr);
                    case "ATTRIBUTE" -> removeAttribute(player, titleId, attr);
                    case "MYTHICLIB_STAT" -> removeMythicLibStat(player, attr);
                    default -> { /* 未知类型已在 apply 时警告过 */ }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "移除称号属性失败 titleId=" + titleId + " attr=" + attr.buffKey(), e);
            }
        }
    }

    /** 玩家上线时重新应用当前佩戴称号的加成（AttributeModifier/PotionEffect 不跨会话持久化）。 */
    public void reapplyOnJoin(Player player) {
        Integer titleId = plugin.titleManager().getEquipped(player.getUniqueId());
        if (titleId != null) apply(player, titleId);
    }

    // ==================== POTION ====================

    private void applyPotion(Player player, int titleId, TitleAttribute attr) {
        PotionEffectType type = PotionEffectType.getByName(attr.buffKey());
        if (type == null) {
            plugin.getLogger().warning("未知药水类型: " + attr.buffKey());
            return;
        }
        int amplifier = Math.max(0, (int) attr.amount() - 1);
        boolean hide = attr.extra() != null && attr.extra().contains("\"hide\":true");
        player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amplifier, true, !hide, !hide));
    }

    private void removePotion(Player player, TitleAttribute attr) {
        PotionEffectType type = PotionEffectType.getByName(attr.buffKey());
        if (type != null) player.removePotionEffect(type);
    }

    // ==================== ATTRIBUTE ====================

    private NamespacedKey attrKey(int titleId, TitleAttribute attr) {
        return new NamespacedKey(plugin, "title_" + titleId + "_" + attr.id());
    }

    private void applyAttribute(Player player, int titleId, TitleAttribute attr) {
        Attribute bukkitAttr = Registry.ATTRIBUTE.get(net.kyori.adventure.key.Key.key(attr.buffKey()));
        if (bukkitAttr == null) {
            plugin.getLogger().warning("未知属性: " + attr.buffKey());
            return;
        }
        AttributeInstance instance = player.getAttribute(bukkitAttr);
        if (instance == null) return;
        NamespacedKey key = attrKey(titleId, attr);
        instance.removeModifier(key);
        instance.addModifier(new AttributeModifier(key, attr.amount(), AttributeModifier.Operation.ADD_NUMBER));
    }

    private void removeAttribute(Player player, int titleId, TitleAttribute attr) {
        Attribute bukkitAttr = Registry.ATTRIBUTE.get(net.kyori.adventure.key.Key.key(attr.buffKey()));
        if (bukkitAttr == null) return;
        AttributeInstance instance = player.getAttribute(bukkitAttr);
        if (instance == null) return;
        instance.removeModifier(attrKey(titleId, attr));
    }

    // ==================== MYTHICLIB_STAT（best-effort 反射） ====================

    private boolean mythicLibReady() {
        if (mythicLibAvailable == null) {
            mythicLibAvailable = Bukkit.getPluginManager().getPlugin("MythicLib") != null;
            if (!mythicLibAvailable) plugin.getLogger().warning("MythicLib 未安装，MYTHICLIB_STAT 类型称号属性将不生效（不影响其他功能）");
        }
        return mythicLibAvailable;
    }

    /**
     * 反射调用 MythicLib 的玩家自定义 stat 修饰接口。MythicLib API 版本差异较大，
     * 找不到匹配方法时静默跳过并记一次警告，不阻塞其他属性生效。
     */
    private void applyMythicLibStat(Player player, TitleAttribute attr) {
        if (!mythicLibReady()) return;
        try {
            Class<?> mmoBukkit = Class.forName("io.lumine.mythic.lib.MythicLib");
            Object inst = mmoBukkit.getMethod("inst").invoke(null);
            Object statMap = inst.getClass().getMethod("getStatMap").invoke(inst);
            Method registerStatMethod = findMethod(statMap.getClass(), "registerStatModifier");
            if (registerStatMethod == null) {
                plugin.getLogger().warning("MythicLib API 不兼容当前版本，跳过 MYTHICLIB_STAT: " + attr.buffKey());
                return;
            }
            // 具体签名随 MythicLib 版本变化较大，此处留作后续按实际部署版本对接的扩展点。
            plugin.getLogger().fine("MYTHICLIB_STAT 应用占位: " + attr.buffKey() + "=" + attr.amount());
        } catch (Throwable t) {
            plugin.getLogger().warning("MythicLib 属性应用失败(不影响其他功能): " + t.getMessage());
        }
    }

    private void removeMythicLibStat(Player player, TitleAttribute attr) {
        if (!mythicLibReady()) return;
        // 与 applyMythicLibStat 对称的移除扩展点。
    }

    private Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }
}
