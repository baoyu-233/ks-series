package org.itemedit;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class ItemEdits {

    private ItemEdits() {}

    // ---------------- 名称 ----------------

    public static void setName(ItemStack item, String raw) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.displayName(TextUtil.parse(raw));
        item.setItemMeta(meta);
    }

    public static void clearName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.displayName(null);
        item.setItemMeta(meta);
    }

    // ---------------- Lore ----------------

    public static List<Component> lore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        List<Component> l = (meta == null) ? null : meta.lore();
        return (l == null) ? new ArrayList<>() : new ArrayList<>(l);
    }

    public static void setLore(ItemStack item, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.lore(lore.isEmpty() ? null : lore);
        item.setItemMeta(meta);
    }

    public static void addLoreLine(ItemStack item, String raw) {
        List<Component> lore = lore(item);
        lore.add(TextUtil.parse(raw));
        setLore(item, lore);
    }

    public static void editLoreLine(ItemStack item, int index, String raw) {
        List<Component> lore = lore(item);
        if (index < 0 || index >= lore.size()) return;
        lore.set(index, TextUtil.parse(raw));
        setLore(item, lore);
    }

    public static void removeLoreLine(ItemStack item, int index) {
        List<Component> lore = lore(item);
        if (index < 0 || index >= lore.size()) return;
        lore.remove(index);
        setLore(item, lore);
    }

    public static void swapLore(ItemStack item, int a, int b) {
        List<Component> lore = lore(item);
        if (a < 0 || b < 0 || a >= lore.size() || b >= lore.size()) return;
        Component tmp = lore.get(a);
        lore.set(a, lore.get(b));
        lore.set(b, tmp);
        setLore(item, lore);
    }

    public static void clearLore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.lore(null);
        item.setItemMeta(meta);
    }

    // ---------------- 附魔（突破原版上限） ----------------

    /** level <= 0 视为移除。使用 ignoreLevelRestriction=true 突破原版等级上限。 */
    public static void setEnchant(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (level <= 0) {
            meta.removeEnchant(ench);
        } else {
            meta.addEnchant(ench, level, true);
        }
        item.setItemMeta(meta);
    }

    public static void clearEnchants(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        for (Enchantment e : new ArrayList<>(meta.getEnchants().keySet())) {
            meta.removeEnchant(e);
        }
        item.setItemMeta(meta);
    }

    // ---------------- ItemsAdder 套模型 ----------------

    /**
     * 模式一：保留本体，只把 IA 物品的“外观”复制过来。
     * 复制 item_model 组件（1.21.4+ 与材质无关，最适合武器）以及 custom_model_data。
     * 武器还是原来的武器（伤害/可附魔/耐久都不变），只是长相变了。
     * @return 是否复制到了任何外观数据
     */
    public static boolean applyModelKeepBody(ItemStack target, ItemStack reference) {
        ItemMeta rm = reference.getItemMeta();
        ItemMeta tm = target.getItemMeta();
        if (rm == null || tm == null) return false;

        boolean any = false;
        if (rm.hasItemModel()) {
            tm.setItemModel(rm.getItemModel());
            any = true;
        }
        if (rm.hasCustomModelDataComponent()) {
            tm.setCustomModelDataComponent(rm.getCustomModelDataComponent());
            any = true;
        } else if (rm.hasCustomModelData()) {
            //noinspection deprecation
            tm.setCustomModelData(rm.getCustomModelData());
            any = true;
        }
        target.setItemMeta(tm);
        return any;
    }

    /**
     * 模式二：直接以 IA 物品为本体，把当前物品的名称/Lore/附魔搬过去。
     * 适合“我想要的就是这个 IA 物品本身，只是改个名字/词条”。
     */
    public static ItemStack applyModelReplace(ItemStack reference, ItemStack current) {
        ItemStack out = reference.clone();
        ItemMeta om = out.getItemMeta();
        ItemMeta cm = current.getItemMeta();
        if (om != null && cm != null) {
            if (cm.hasDisplayName()) om.displayName(cm.displayName());
            if (cm.hasLore()) om.lore(cm.lore());
            for (Map.Entry<Enchantment, Integer> e : cm.getEnchants().entrySet()) {
                om.addEnchant(e.getKey(), e.getValue(), true);
            }
            out.setItemMeta(om);
        }
        out.setAmount(current.getAmount());
        return out;
    }

    // ---------------- 不可破坏 ----------------

    public static boolean isUnbreakable(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.isUnbreakable();
    }

    public static void setUnbreakable(ItemStack item, boolean unbreakable) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setUnbreakable(unbreakable);
        item.setItemMeta(meta);
    }

    // ---------------- 发光 ----------------

    public static boolean isGlowing(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        if (!meta.hasEnchantmentGlintOverride()) return false;
        return Boolean.TRUE.equals(meta.getEnchantmentGlintOverride());
    }

    public static void setGlowing(ItemStack item, boolean glowing) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        // null = 默认行为（有附魔才发光），true = 强制发光
        meta.setEnchantmentGlintOverride(glowing ? true : null);
        item.setItemMeta(meta);
    }

    // ---------------- 物品属性修饰符 (Attribute Modifiers) ----------------

    /**
     * 读取物品的所有属性修饰符，转为可序列化的 Map 列表。
     * 每个 Map 包含：attribute, amount, operation, slot。
     */
    public static List<Map<String, Object>> getAttributeModifiers(ItemStack item) {
        List<Map<String, Object>> result = new ArrayList<>();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return result;

        Multimap<Attribute, AttributeModifier> attrMap = meta.getAttributeModifiers();
        if (attrMap == null || attrMap.isEmpty()) return result;

        for (Map.Entry<Attribute, AttributeModifier> entry : attrMap.entries()) {
            AttributeModifier mod = entry.getValue();
            Map<String, Object> modData = new LinkedHashMap<>();
            modData.put("attribute", entry.getKey().getKey().asString());
            modData.put("amount", mod.getAmount());
            modData.put("operation", mod.getOperation().name().toLowerCase());
            modData.put("slot", mod.getSlotGroup().toString().toLowerCase());
            result.add(modData);
        }
        return result;
    }

    /**
     * 设置物品的属性修饰符（先清除原有的，再逐个添加）。
     *
     * @param item       目标物品
     * @param modifiers  修饰符列表，每个 Map 需含 attribute/amount/operation/slot
     */
    public static void setAttributeModifiers(ItemStack item, List<Map<String, Object>> modifiers) {
        Logger log = Bukkit.getLogger();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            log.warning("[ItemEditor] setAttributeModifiers: ItemMeta 为 null");
            return;
        }

        // 清除原有属性修饰符
        meta.setAttributeModifiers(null);

        if (modifiers == null || modifiers.isEmpty()) {
            item.setItemMeta(meta);
            log.info("[ItemEditor] setAttributeModifiers: 无修饰符，已清除");
            return;
        }

        log.info("[ItemEditor] setAttributeModifiers: 开始设置 " + modifiers.size() + " 个属性修饰符");
        int index = 0;
        for (Map<String, Object> modData : modifiers) {
            try {
                String attrId = (String) modData.get("attribute");
                if (attrId == null || attrId.isEmpty()) {
                    log.warning("[ItemEditor] setAttributeModifiers: 跳过 — attribute 为空");
                    continue;
                }

                Attribute attr = Registry.ATTRIBUTE.get(
                        net.kyori.adventure.key.Key.key(attrId));
                if (attr == null) {
                    log.warning("[ItemEditor] setAttributeModifiers: 跳过 — 未知属性 ID: " + attrId);
                    continue;
                }

                double amount = toDouble(modData.get("amount"));
                AttributeModifier.Operation op = parseOperation((String) modData.get("operation"));
                EquipmentSlotGroup slot = parseSlotGroup((String) modData.get("slot"));

                NamespacedKey key = new NamespacedKey("itemeditor", "attr_" + index);
                AttributeModifier modifier = new AttributeModifier(key, amount, op, slot);
                meta.addAttributeModifier(attr, modifier);
                log.info("[ItemEditor] setAttributeModifiers: [" + index + "] " + attrId
                        + " amount=" + amount + " op=" + op + " slot=" + slot);
                index++;
            } catch (Exception e) {
                log.warning("[ItemEditor] setAttributeModifiers: 条目 " + index + " 失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
        item.setItemMeta(meta);
        log.info("[ItemEditor] setAttributeModifiers: 完成，共设置 " + index + " 个");
    }

    /** 清除物品的所有属性修饰符。 */
    public static void clearAttributeModifiers(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setAttributeModifiers(null);
        item.setItemMeta(meta);
    }

    /** 安全地将 Object 转为 double。 */
    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return 0.0;
    }

    /** 将前端字符串映射为 AttributeModifier.Operation 枚举。 */
    private static AttributeModifier.Operation parseOperation(String op) {
        if (op == null) return AttributeModifier.Operation.ADD_NUMBER;
        return switch (op.toLowerCase()) {
            case "add_number", "add" -> AttributeModifier.Operation.ADD_NUMBER;
            case "add_multiplied_base", "multiply_base", "add_scalar" -> AttributeModifier.Operation.ADD_SCALAR;
            case "add_multiplied_total", "multiply_total", "multiply_scalar_1" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            default -> AttributeModifier.Operation.ADD_NUMBER;
        };
    }

    /** 将前端字符串映射为 EquipmentSlotGroup 枚举。 */
    private static EquipmentSlotGroup parseSlotGroup(String slot) {
        if (slot == null) return EquipmentSlotGroup.ANY;
        return switch (slot.toLowerCase()) {
            case "any" -> EquipmentSlotGroup.ANY;
            case "mainhand" -> EquipmentSlotGroup.MAINHAND;
            case "offhand" -> EquipmentSlotGroup.OFFHAND;
            case "head" -> EquipmentSlotGroup.HEAD;
            case "chest" -> EquipmentSlotGroup.CHEST;
            case "legs" -> EquipmentSlotGroup.LEGS;
            case "feet" -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.ANY;
        };
    }

    // ---------------- FE 附魔 Lore 剥除 ----------------

    /**
     * 剥除 FE 生成的附魔 Lore 行（附魔名称/描述 + 可附魔槽位），
     * 仅保留玩家及其他插件添加的自定义行。直接修改传入的 item。
     */
    public static void stripEnchantmentLore(ItemStack item, Player player) {
        FotiaEnchantmentHook.stripGeneratedLore(player, item);
    }

    /**
     * 获取仅含用户自定义行的 Lore 列表（在 clone 上操作，不污染原物品）。
     */
    public static List<Component> userLore(ItemStack item, Player player) {
        ItemStack clone = item.clone();
        FotiaEnchantmentHook.stripGeneratedLore(player, clone);
        return lore(clone);
    }
}
