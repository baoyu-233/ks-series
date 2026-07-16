package org.itemedit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class LoreMenu extends Menu {

    private static final int CONTENT = 45;     // 0..44 显示 Lore 行
    private static final int BACK = 45;
    private static final int ADD = 48;
    private static final int CLEAR = 50;
    private static final int CLOSE = 53;

    private final int page; // 预留分页（一般 Lore 不会超过 45 行）

    public LoreMenu(ItemEditor plugin, EditSession session, int page) {
        super(plugin, session);
        this.page = Math.max(0, page);
    }

    @Override
    protected void build() {
        inventory = create(54, Component.text("修改描述 (Lore)", NamedTextColor.DARK_AQUA));

        // ★ 只显示用户自定义 Lore——用 clone 剥除 FE 附魔行与槽位行，不污染原物品
        List<Component> lore = ItemEdits.userLore(session.item(), session.player());
        int shown = Math.min(lore.size(), CONTENT);
        for (int i = 0; i < shown; i++) {
            inventory.setItem(i, button(Material.PAPER,
                    Component.text("第 " + (i + 1) + " 行: ", NamedTextColor.GRAY).append(lore.get(i)),
                    Component.text("左键: 编辑本行", NamedTextColor.YELLOW),
                    Component.text("右键: 删除本行", NamedTextColor.RED),
                    Component.text("Shift+左键: 上移   Shift+右键: 下移", NamedTextColor.DARK_GRAY)));
        }

        inventory.setItem(BACK + 0, button(Material.ARROW,
                Component.text("返回", NamedTextColor.WHITE)));
        inventory.setItem(ADD, button(Material.LIME_DYE,
                Component.text("新增一行", NamedTextColor.GREEN),
                Component.text("点击在聊天框输入内容", NamedTextColor.GRAY)));
        inventory.setItem(CLEAR, button(Material.RED_DYE,
                Component.text("清空全部 Lore", NamedTextColor.RED)));
        inventory.setItem(CLOSE, button(Material.BARRIER,
                Component.text("关闭", NamedTextColor.RED)));

        fillEmpty();
    }

    @Override
    public void handle(InventoryClickEvent event) {
        int slot = event.getSlot();
        ItemStack item = session.item();

        if (slot < CONTENT) {
            // ★ 先剥除 FE 附魔行，确保索引对应的是用户自定义行
            ItemEdits.stripEnchantmentLore(item, session.player());
            List<Component> lore = ItemEdits.lore(item);
            if (slot >= lore.size()) return;
            ClickType c = event.getClick();
            if (c == ClickType.SHIFT_LEFT) {
                ItemEdits.swapLore(item, slot, slot - 1);
                session.setItem(item);
                open();
            } else if (c == ClickType.SHIFT_RIGHT) {
                ItemEdits.swapLore(item, slot, slot + 1);
                session.setItem(item);
                open();
            } else if (c.isRightClick()) {
                ItemEdits.removeLoreLine(item, slot);
                session.setItem(item);
                open();
            } else {
                // 编辑前也先清理
                ItemEdits.stripEnchantmentLore(item, session.player());
                session.setItem(item);
                session.promptLoreEdit(slot);
            }
            return;
        }

        switch (slot) {
            case BACK -> new MainMenu(plugin, session).open();
            case ADD -> session.promptLoreAdd();
            case CLEAR -> {
                ItemEdits.clearLore(item);
                session.setItem(item);
                open();
            }
            case CLOSE -> session.player().closeInventory();
            default -> { /* filler */ }
        }
    }
}
