package org.itemedit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Registry;

import java.lang.reflect.Type;
import java.util.*;

/**
 * 物品 ↔ JSON 序列化 / 反序列化。
 * 用于网页编辑器与插件之间传递物品数据。
 */
public final class ItemSerializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private ItemSerializer() {}

    // ---- 序列化：ItemStack → JSON ----

    public static String toJson(ItemStack item, Player player) {
        ItemData data = toItemData(item, player);
        return GSON.toJson(data);
    }

    public static ItemData toItemData(ItemStack item, Player player) {
        ItemData data = new ItemData();
        if (item == null || item.getType().isAir()) return data;

        data.material = item.getType().name();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return data;

        // 名称（保留 & 格式码，避免丢失颜色/粗体/乱码等）
        if (meta.hasDisplayName()) {
            data.name = TextUtil.legacy(meta.displayName());
        }

        // Lore（仅用户自定义行，剥离 FE 生成行）
        // ★ 使用 legacy() 保留 & 格式码，PlainTextSerializer 会丢弃所有格式（颜色/粗体/乱码等）
        List<net.kyori.adventure.text.Component> userLore = ItemEdits.userLore(item, player);
        List<String> loreLines = new ArrayList<>();
        for (net.kyori.adventure.text.Component c : userLore) {
            loreLines.add(TextUtil.legacy(c));
        }
        data.lore = loreLines;

        // 原版附魔
        Map<Enchantment, Integer> enchants = meta.getEnchants();
        data.enchantments = new LinkedHashMap<>();
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            String key = e.getKey().getKey().asString(); // e.g. "minecraft:sharpness"
            data.enchantments.put(key, e.getValue());
        }

        // FotiaEnchantment 自定义附魔
        Map<String, Integer> feEnchants = FotiaEnchantmentHook.getCustomEnchantments(item);
        data.feEnchantments = new LinkedHashMap<>(feEnchants);

        // 不可破坏
        data.unbreakable = meta.isUnbreakable();

        // 发光
        data.glowing = ItemEdits.isGlowing(item);

        // 物品属性修饰符
        data.attributeModifiers = ItemEdits.getAttributeModifiers(item);

        // ItemsAdder 模型（不直接存，由前端选择后传回）

        return data;
    }

    // ---- 反序列化：JSON → ItemStack ----

    public static ItemStack fromJson(String json) {
        ItemData data = GSON.fromJson(json, ItemData.class);
        return fromItemData(data);
    }

    public static ItemStack fromItemData(ItemData data) {
        if (data == null || data.material == null) return null;

        Material mat = Material.getMaterial(data.material);
        if (mat == null) mat = Material.STONE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // 名称
        if (data.name != null && !data.name.isEmpty()) {
            meta.displayName(TextUtil.parse(data.name));
        }

        // Lore
        if (data.lore != null && !data.lore.isEmpty()) {
            List<net.kyori.adventure.text.Component> loreComps = new ArrayList<>();
            for (String line : data.lore) {
                if (line != null && !line.isEmpty()) {
                    loreComps.add(TextUtil.parse(line));
                }
            }
            if (!loreComps.isEmpty()) {
                meta.lore(loreComps);
            }
        }

        item.setItemMeta(meta);

        // 原版附魔
        if (data.enchantments != null) {
            for (Map.Entry<String, Integer> e : data.enchantments.entrySet()) {
                String keyStr = e.getKey();
                // 安全检查：必须是 namespace:value 格式且仅含小写字母数字
                if (keyStr == null || !keyStr.matches("^[a-z0-9_\\-./]+:[a-z0-9_\\-./]+$")) continue;
                try {
                    Enchantment ench = Registry.ENCHANTMENT.get(
                            net.kyori.adventure.key.Key.key(keyStr));
                    if (ench != null && e.getValue() > 0) {
                        ItemEdits.setEnchant(item, ench, e.getValue());
                    }
                } catch (Exception ignored) { /* 跳过无效附魔 key */ }
            }
        }

        // 不可破坏
        if (data.unbreakable) {
            ItemEdits.setUnbreakable(item, true);
        }

        // 发光
        if (data.glowing) {
            ItemEdits.setGlowing(item, true);
        }

        // 玩家属性修饰符
        if (data.attributeModifiers != null && !data.attributeModifiers.isEmpty()) {
            ItemEdits.setAttributeModifiers(item, data.attributeModifiers);
        }

        // FE 附魔在 ItemEdits 层之外处理（由调用方在获得 Player 上下文后设置）
        // IA 模型同理

        return item;
    }

    /**
     * 应用 FE 附魔和 IA 模型到物品（需要 Player 上下文）。
     */
    public static void applyExtendedData(ItemStack item, ItemData data) {
        // FotiaEnchantment 自定义附魔
        if (data.feEnchantments != null) {
            for (Map.Entry<String, Integer> e : data.feEnchantments.entrySet()) {
                String id = e.getKey();
                // 安全检查：FE 附魔 ID 只含小写字母数字下划线
                if (id == null || !id.matches("^[a-z0-9_]+$")) continue;
                FotiaEnchantmentHook.setCustomEnchantment(item, id, e.getValue());
            }
        }

        // ItemsAdder 模型（安全检查：必须是 namespace:id 格式）
        if (data.iaModel != null && !data.iaModel.isEmpty()
                && data.iaModel.matches("^[a-z0-9_\\-]+:[a-z0-9_\\-]+$")) {
            ItemStack ref = ItemsAdderHook.reference(data.iaModel);
            if (ref != null) {
                ItemEdits.applyModelKeepBody(item, ref);
            }
        }
    }
    /**
     * Apply template display/enchant data onto an existing item body.
     * Preserves amount, durability, container contents and third-party item identity.
     */
    public static ItemStack applyTemplatePreservingBody(ItemStack current, ItemData data) {
        if (current == null || current.getType().isAir() || data == null) return null;
        ItemStack result = current.clone();
        if (data.name == null || data.name.isBlank()) ItemEdits.clearName(result);
        else ItemEdits.setName(result, data.name);

        java.util.List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>();
        if (data.lore != null) {
            for (String line : data.lore) {
                if (line != null && !line.isEmpty()) lore.add(TextUtil.parse(line));
            }
        }
        ItemEdits.setLore(result, lore);

        ItemEdits.clearEnchants(result);
        if (data.enchantments != null) {
            for (Map.Entry<String, Integer> entry : data.enchantments.entrySet()) {
                String keyStr = entry.getKey();
                if (keyStr == null || !keyStr.matches("^[a-z0-9_\\-./]+:[a-z0-9_\\-./]+$")) continue;
                try {
                    Enchantment enchantment = Registry.ENCHANTMENT.get(
                            net.kyori.adventure.key.Key.key(keyStr));
                    if (enchantment != null && entry.getValue() > 0) {
                        ItemEdits.setEnchant(result, enchantment, entry.getValue());
                    }
                } catch (Exception ignored) {
                    // Ignore malformed template keys without replacing the held item body.
                }
            }
        }
        ItemEdits.setUnbreakable(result, data.unbreakable);
        ItemEdits.setGlowing(result, data.glowing);
        if (data.attributeModifiers != null && !data.attributeModifiers.isEmpty()) {
            ItemEdits.setAttributeModifiers(result, data.attributeModifiers);
        }
        applyExtendedData(result, data);
        return result;
    }


    // ---- 工具方法 ----

    /**
     * 解析 JSON body 为 Map。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseBody(String json) {
        try {
            return GSON.fromJson(json, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 将对象转为 JSON 字符串。
     */
    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    /**
     * 直接从 Map 构建 ItemData（避免 Gson 双转换污染静态内部类字段）。
     * 这是 WebApi 模板保存的推荐方式。
     */
    @SuppressWarnings("unchecked")
    public static ItemData fromMap(Map<String, Object> map) {
        ItemData data = new ItemData();
        if (map == null) return data;

        // material
        Object matObj = map.get("material");
        if (matObj instanceof String s && !s.isEmpty()) {
            data.material = s;
        }

        // name
        Object nameObj = map.get("name");
        if (nameObj instanceof String s) {
            data.name = s;
        }

        // lore
        Object loreObj = map.get("lore");
        if (loreObj instanceof List<?> list) {
            data.lore = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s) data.lore.add(s);
            }
        }

        // enchantments
        Object enchObj = map.get("enchantments");
        if (enchObj instanceof Map<?, ?> enchMap) {
            data.enchantments = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : enchMap.entrySet()) {
                if (e.getKey() instanceof String key) {
                    int v = toInt(e.getValue());
                    if (v > 0) data.enchantments.put(key, v);
                }
            }
        }

        // feEnchantments
        Object feObj = map.get("feEnchantments");
        if (feObj instanceof Map<?, ?> feMap) {
            data.feEnchantments = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : feMap.entrySet()) {
                if (e.getKey() instanceof String key) {
                    int v = toInt(e.getValue());
                    if (v > 0) data.feEnchantments.put(key, v);
                }
            }
        }

        // unbreakable
        data.unbreakable = Boolean.TRUE.equals(map.get("unbreakable"));

        // glowing
        data.glowing = Boolean.TRUE.equals(map.get("glowing"));

        // attributeModifiers — 属性修饰符列表
        Object attrObj = map.get("attributeModifiers");
        if (attrObj instanceof List<?> attrList) {
            data.attributeModifiers = new ArrayList<>();
            for (Object item : attrList) {
                if (item instanceof Map<?, ?> modMap) {
                    Map<String, Object> mod = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : modMap.entrySet()) {
                        if (e.getKey() instanceof String key) {
                            mod.put(key, e.getValue());
                        }
                    }
                    if (mod.containsKey("attribute") && mod.containsKey("amount")) {
                        data.attributeModifiers.add(mod);
                    }
                }
            }
        }

        // iaModel
        Object iaObj = map.get("iaModel");
        if (iaObj instanceof String s && !s.isEmpty()) {
            data.iaModel = s;
        }

        return data;
    }

    private static int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
