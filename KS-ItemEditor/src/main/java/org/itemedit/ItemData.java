package org.itemedit;

import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;

import java.util.*;

/**
 * 物品模板数据 —— 网页编辑器与插件之间的物品数据交换格式。
 * 注意：必须为顶层类（非内部类），否则 Gson 2.8 反射序列化时
 * 会将外部类信息（Maven GAV）污染到 Map 类型字段中。
 */
public class ItemData {
    public String material;
    public String name;
    public List<String> lore;
    public Map<String, Integer> enchantments;
    public Map<String, Integer> feEnchantments;
    public boolean unbreakable;
    public boolean glowing;
    public String iaModel;
    /**
     * 物品属性修饰符列表。每个元素为 Map，包含：
     * <ul>
     *   <li>{@code attribute}  — 属性 ID，如 {@code minecraft:generic.attack_damage}</li>
     *   <li>{@code amount}     — 修饰数值（double，可正可负可小数）</li>
     *   <li>{@code operation}  — 运算方式：{@code add_number} / {@code add_multiplied_base} / {@code add_multiplied_total}</li>
     *   <li>{@code slot}       — 生效插槽：{@code any} / {@code mainhand} / {@code offhand} / {@code head} / {@code chest} / {@code legs} / {@code feet}</li>
     * </ul>
     */
    public List<Map<String, Object>> attributeModifiers;

    public ItemData() {
        this.material = "STONE";
        this.name = "";
        this.lore = new ArrayList<>();
        this.enchantments = new LinkedHashMap<>();
        this.feEnchantments = new LinkedHashMap<>();
        this.attributeModifiers = new ArrayList<>();
        this.unbreakable = false;
        this.glowing = false;
        this.iaModel = null;
    }

    /**
     * 将原版附魔等级截断到自然上限。
     * 玩家模板不应携带管理员突破的超出上限的附魔等级。
     */
    public void capEnchantmentLevels() {
        if (enchantments == null || enchantments.isEmpty()) return;
        Map<String, Integer> capped = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
            int level = e.getValue();
            if (level <= 0) continue;
            Enchantment ench = Registry.ENCHANTMENT.get(
                    net.kyori.adventure.key.Key.key(e.getKey()));
            int max = ench != null ? ench.getMaxLevel() : 255;
            capped.put(e.getKey(), Math.min(level, max));
        }
        this.enchantments = capped;
    }

    /** 转为纯 Map（避免 Gson 2.8 序列化时的字段污染）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("material", material);
        if (name != null && !name.isEmpty()) m.put("name", name);
        if (lore != null && !lore.isEmpty()) m.put("lore", new ArrayList<>(lore));
        else m.put("lore", new ArrayList<>());
        m.put("enchantments", enchantments != null ? new LinkedHashMap<>(enchantments) : new LinkedHashMap<>());
        m.put("feEnchantments", feEnchantments != null ? new LinkedHashMap<>(feEnchantments) : new LinkedHashMap<>());
        m.put("unbreakable", unbreakable);
        m.put("glowing", glowing);
        if (attributeModifiers != null && !attributeModifiers.isEmpty()) {
            // 深拷贝每个修饰符条目
            List<Map<String, Object>> attrs = new ArrayList<>();
            for (Map<String, Object> mod : attributeModifiers) {
                attrs.add(new LinkedHashMap<>(mod));
            }
            m.put("attributeModifiers", attrs);
        }
        if (iaModel != null && !iaModel.isEmpty()) m.put("iaModel", iaModel);
        return m;
    }
}
