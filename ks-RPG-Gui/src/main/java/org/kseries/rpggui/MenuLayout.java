package org.kseries.rpggui;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MenuLayout {
    record ItemSpec(Material material, String name, List<String> lore) {
        ItemSpec replace(Map<String, String> values) {
            String replacedName = replaceText(name, values);
            List<String> replacedLore = lore.stream().map(line -> replaceText(line, values)).toList();
            return new ItemSpec(material, color(replacedName), replacedLore.stream().map(MenuLayout::color).toList());
        }

        private static String replaceText(String text, Map<String, String> values) {
            String result = text;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
            return result;
        }
    }

    private static final Map<String, Integer> DEFAULT_SLOTS = Map.ofEntries(
            Map.entry("profile", 4), Map.entry("proofs", 10), Map.entry("materials", 12),
            Map.entry("accessories", 14), Map.entry("gates", 16), Map.entry("refresh", 45),
            Map.entry("close", 49), Map.entry("admin-items", 51), Map.entry("admin-reload", 53), Map.entry("back", 45),
            Map.entry("previous", 47), Map.entry("list-refresh", 49), Map.entry("next", 51),
            Map.entry("list-close", 53));

    private final String title;
    private final int rows;
    private final Material frameMaterial;
    private final Material fillMaterial;
    private final Map<String, Integer> slots;
    private final Map<String, ItemSpec> items;

    private MenuLayout(String title, int rows, Material frameMaterial, Material fillMaterial,
                       Map<String, Integer> slots, Map<String, ItemSpec> items) {
        this.title = color(title);
        this.rows = rows;
        this.frameMaterial = frameMaterial;
        this.fillMaterial = fillMaterial;
        this.slots = Map.copyOf(slots);
        this.items = Map.copyOf(items);
    }

    static MenuLayout load(File file) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        ConfigurationSection root = config.getConfigurationSection("menu");
        if (root == null) throw new InvalidConfigurationException("Missing menu section");
        int rows = Math.clamp(root.getInt("rows", 6), 3, 6);
        int size = rows * 9;
        Map<String, Integer> slots = new LinkedHashMap<>();
        ConfigurationSection slotRoot = root.getConfigurationSection("slots");
        for (Map.Entry<String, Integer> entry : DEFAULT_SLOTS.entrySet()) {
            int slot = slotRoot == null ? entry.getValue() : slotRoot.getInt(entry.getKey(), entry.getValue());
            slots.put(entry.getKey(), slot >= 0 && slot < size ? slot : entry.getValue());
        }

        Map<String, ItemSpec> items = new LinkedHashMap<>();
        ConfigurationSection itemRoot = root.getConfigurationSection("items");
        if (itemRoot != null) {
            for (String id : itemRoot.getKeys(false)) {
                ConfigurationSection section = itemRoot.getConfigurationSection(id);
                if (section == null) continue;
                items.put(id, new ItemSpec(material(section.getString("material"), Material.PAPER),
                        color(section.getString("name", id)),
                        section.getStringList("lore").stream().map(MenuLayout::color).toList()));
            }
        }
        return new MenuLayout(root.getString("title", "&1&lRPG 进度面板"), rows,
                material(root.getString("frame-material"), Material.LIGHT_BLUE_STAINED_GLASS_PANE),
                material(root.getString("fill-material"), Material.BLACK_STAINED_GLASS_PANE), slots, items);
    }

    String title() { return title; }
    int size() { return rows * 9; }
    Material frameMaterial() { return frameMaterial; }
    Material fillMaterial() { return fillMaterial; }
    int slot(String id) { return slots.getOrDefault(id, -1); }

    ItemSpec item(String id, Material fallbackMaterial, String fallbackName) {
        return items.getOrDefault(id, new ItemSpec(fallbackMaterial, color(fallbackName), List.of()));
    }

    private static Material material(String raw, Material fallback) {
        if (raw == null) return fallback;
        Material material = Material.matchMaterial(raw);
        return material == null ? fallback : material;
    }

    static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
