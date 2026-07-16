package org.itemedit;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EnchantMenu extends Menu {

    private static final int PAGE_SIZE = 45;
    private static final int MAX_LEVEL = 32767;

    private static final int PREV = 45;
    private static final int BACK = 47;
    private static final int INFO = 49;
    private static final int CLEAR = 51;
    private static final int NEXT = 53;

    private final int page;

    public EnchantMenu(ItemEditor plugin, EditSession session, int page) {
        super(plugin, session);
        this.page = Math.max(0, page);
    }

    /**
     * 单个附魔条目，统一表示原版附魔与 FotiaEnchantment 自定义附魔。
     * FE 附魔以 PDC 形式存储，不能通过 item.getEnchantmentLevel() 读取。
     *
     * @param fullKey      "minecraft:sharpness" 或 "fotia:blaze_resist"
     * @param displayName  显示名称（中文优先）
     * @param vanilla      原版 Enchantment 对象，FE 条目为 null
     * @param currentLevel 当前等级
     * @param fotiaId      FE 原始 id（如 "blaze_resist"），原版条目为 null
     * @param description  附魔描述行（中文），可为空
     */
    private record EnchantEntry(String fullKey, String displayName, Enchantment vanilla,
                                int currentLevel, String fotiaId, List<String> description) {

        boolean isFotia() {
            return fotiaId != null;
        }
    }

    /** 列出所有可编辑的附魔：原版注册表 + FotiaEnchantment 已启用列表。 */
    private List<EnchantEntry> allEntries(ItemStack item) {
        List<EnchantEntry> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 原版 / Paper 注册表附魔
        List<Enchantment> vanilla = new ArrayList<>();
        try {
            Registry<Enchantment> reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
            reg.forEach(vanilla::add);
        } catch (Exception ignored) { /* ignore */ }
        try {
            for (Enchantment e : Registry.ENCHANTMENT) {
                if (!vanilla.contains(e)) vanilla.add(e);
            }
        } catch (Exception ignored) { /* ignore */ }

        for (Enchantment e : vanilla) {
            String key = e.getKey().toString();
            if (!seen.add(key)) continue;
            int level = item != null ? item.getEnchantmentLevel(e) : 0;
            String name = e.getKey().getKey().replace('_', ' ');
            if (!name.isEmpty()) name = name.substring(0, 1).toUpperCase() + name.substring(1);
            list.add(new EnchantEntry(key, name, e, level, null, Collections.emptyList()));
        }

        // FotiaEnchantment 自定义附魔（PDC 存储）
        if (FotiaEnchantmentHook.isAvailable()) {
            Player p = session.player();
            for (FeEnchantData data : FotiaEnchantmentHook.getEnabledEnchantments()) {
                String id = data.getId();
                String fullKey = "fotia:" + id;
                if (!seen.add(fullKey)) continue;
                int level = item != null ? FotiaEnchantmentHook.getCustomEnchantmentLevel(item, id) : 0;
                // 使用 FE 的 LanguageManager 获取中文名
                String display = FotiaEnchantmentHook.getEnchantName(p, id);
                if (display == null || display.isEmpty()) {
                    display = id.replace('_', ' ');
                    if (!display.isEmpty()) display = display.substring(0, 1).toUpperCase() + display.substring(1);
                }
                list.add(new EnchantEntry(fullKey, display, null, level, id,
                        FotiaEnchantmentHook.getEnchantDescription(p, id)));
            }

            // 补上物品已有但不在启用列表里的 FE 附魔
            if (item != null) {
                for (Map.Entry<String, Integer> e : FotiaEnchantmentHook.getCustomEnchantments(item).entrySet()) {
                    String fullKey = "fotia:" + e.getKey();
                    if (seen.add(fullKey)) {
                        String display = FotiaEnchantmentHook.getEnchantName(p, e.getKey());
                        if (display == null || display.isEmpty()) {
                            display = e.getKey().replace('_', ' ');
                            if (!display.isEmpty()) display = display.substring(0, 1).toUpperCase() + display.substring(1);
                        }
                        list.add(new EnchantEntry(fullKey, display, null, e.getValue(), e.getKey(),
                                FotiaEnchantmentHook.getEnchantDescription(p, e.getKey())));
                    }
                }
            }
        }

        list.sort(Comparator.comparing(EnchantEntry::fullKey));
        return list;
    }

    @Override
    protected void build() {
        inventory = create(54, Component.text("修改附魔", NamedTextColor.DARK_AQUA));

        ItemStack item = session.item();
        List<EnchantEntry> all = allEntries(item);
        int totalPages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.min(page, totalPages - 1);
        int from = p * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, all.size());

        for (int i = from; i < to; i++) {
            EnchantEntry entry = all.get(i);
            int level = entry.currentLevel();
            boolean has = level > 0;
            Component name = Component.text(entry.displayName(), has ? NamedTextColor.GREEN : NamedTextColor.WHITE);

            // 组装按钮 Lore：等级 + 操作提示 + 中文描述 + key
            List<Component> btnLore = new ArrayList<>();
            btnLore.add(Component.text("当前等级: ", NamedTextColor.GRAY)
                    .append(Component.text(level, has ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY)));
            // 中文描述
            if (entry.description() != null && !entry.description().isEmpty()) {
                for (String descLine : entry.description()) {
                    btnLore.add(Component.text(descLine, NamedTextColor.GRAY));
                }
            }
            btnLore.add(Component.text("左键 +1   Shift+左键 +5", NamedTextColor.YELLOW));
            btnLore.add(Component.text("右键 -1   Shift+右键 移除", NamedTextColor.RED));
            btnLore.add(Component.text(entry.fullKey(), NamedTextColor.DARK_GRAY));

            inventory.setItem(i - from, button(Material.ENCHANTED_BOOK, name, btnLore));
        }

        inventory.setItem(PREV, p > 0
                ? button(Material.ARROW, Component.text("上一页", NamedTextColor.WHITE))
                : filler());
        inventory.setItem(NEXT, p < totalPages - 1
                ? button(Material.ARROW, Component.text("下一页", NamedTextColor.WHITE))
                : filler());
        inventory.setItem(INFO, button(Material.PAPER,
                Component.text("第 " + (p + 1) + " / " + totalPages + " 页", NamedTextColor.GRAY)));
        inventory.setItem(BACK, button(Material.BARRIER, Component.text("返回", NamedTextColor.WHITE)));
        inventory.setItem(CLEAR, button(Material.RED_DYE, Component.text("清空所有附魔", NamedTextColor.RED)));

        fillEmpty();
    }

    @Override
    public void handle(InventoryClickEvent event) {
        int slot = event.getSlot();

        if (slot < PAGE_SIZE) {
            ItemStack item = session.item();
            List<EnchantEntry> all = allEntries(item);
            int idx = page * PAGE_SIZE + slot;
            if (idx >= all.size()) return;
            EnchantEntry entry = all.get(idx);
            int level = entry.currentLevel();

            ClickType c = event.getClick();
            if (c == ClickType.SHIFT_LEFT) level += 5;
            else if (c == ClickType.SHIFT_RIGHT) level = 0;
            else if (c.isLeftClick()) level += 1;
            else if (c.isRightClick()) level -= 1;

            level = Math.max(0, Math.min(MAX_LEVEL, level));

            if (entry.isFotia()) {
                FotiaEnchantmentHook.setCustomEnchantment(item, entry.fotiaId(), level);
            } else {
                ItemEdits.setEnchant(item, entry.vanilla(), level);
            }
            session.setItem(item);
            open();
            return;
        }

        switch (slot) {
            case PREV -> new EnchantMenu(plugin, session, page - 1).open();
            case NEXT -> new EnchantMenu(plugin, session, page + 1).open();
            case BACK -> new MainMenu(plugin, session).open();
            case CLEAR -> {
                ItemStack item = session.item();
                ItemEdits.clearEnchants(item);
                // 同时清除所有 FotiaEnchantment 自定义附魔
                if (FotiaEnchantmentHook.isAvailable()) {
                    for (Map.Entry<String, Integer> e : FotiaEnchantmentHook.getCustomEnchantments(item).entrySet()) {
                        FotiaEnchantmentHook.removeCustomEnchantment(item, e.getKey());
                    }
                }
                session.setItem(item);
                open();
            }
            default -> { /* info / filler */ }
        }
    }
}
