package org.itemedit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 精炼 Lore 子菜单（54 槽）。
 *
 * 布局：
 * Slots 0-44: Lore 行（逐行编辑/删除/移动）
 * Slot  45: 返回精炼菜单
 * Slot  48: 新增一行
 * Slot  50: 清空全部 Lore
 * Slot  53: 关闭（等同返回）
 */
public final class RefineLoreMenu implements InventoryHolder {

    private static final int CONTENT = 45;

    private static final int SLOT_BACK  = 45;
    private static final int SLOT_ADD   = 48;
    private static final int SLOT_CLEAR = 50;
    private static final int SLOT_CLOSE = 53;

    private final ItemEditor plugin;
    private final RefineSession session;
    private final Runnable onBack; // 返回 RefineMenu
    private Inventory inventory;

    public RefineLoreMenu(ItemEditor plugin, RefineSession session, Runnable onBack) {
        this.plugin = plugin;
        this.session = session;
        this.onBack = onBack;
    }

    public void open() {
        Player p = session.player();
        if (p == null) return;

        inventory = plugin.getServer().createInventory(this, 54,
                Component.text("武器描述 (Lore)", NamedTextColor.DARK_AQUA));

        build();
        p.openInventory(inventory);
    }

    private void build() {
        List<Component> lore = ItemEdits.lore(session.snapshot());
        int shown = Math.min(lore.size(), CONTENT);
        for (int i = 0; i < shown; i++) {
            inventory.setItem(i, button(Material.PAPER,
                    Component.text("第 " + (i + 1) + " 行: ", NamedTextColor.GRAY).append(lore.get(i)),
                    Component.text("左键: 编辑  右键: 删除", NamedTextColor.YELLOW),
                    Component.text("Shift+左: 上移  Shift+右: 下移", NamedTextColor.DARK_GRAY)));
        }

        inventory.setItem(SLOT_BACK, button(Material.ARROW,
                Component.text("返回精炼菜单", NamedTextColor.WHITE)));
        inventory.setItem(SLOT_ADD, button(Material.LIME_DYE,
                Component.text("新增一行", NamedTextColor.GREEN),
                Component.text("在聊天框输入内容", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_CLEAR, button(Material.RED_DYE,
                Component.text("清空全部 Lore", NamedTextColor.RED)));
        inventory.setItem(SLOT_CLOSE, button(Material.BARRIER,
                Component.text("关闭", NamedTextColor.RED)));

        int size = inventory.getSize();
        ItemStack f = button(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < size; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, f);
        }
    }

    public void handle(InventoryClickEvent event) {
        int slot = event.getSlot();

        if (slot < CONTENT) {
            List<Component> lore = ItemEdits.lore(session.snapshot());
            if (slot >= lore.size()) return;
            ClickType c = event.getClick();
            if (c == ClickType.SHIFT_LEFT) {
                ItemEdits.swapLore(session.snapshot(), slot, slot - 1);
                refresh();
            } else if (c == ClickType.SHIFT_RIGHT) {
                ItemEdits.swapLore(session.snapshot(), slot, slot + 1);
                refresh();
            } else if (c.isRightClick()) {
                ItemEdits.removeLoreLine(session.snapshot(), slot);
                refresh();
            } else {
                // 编辑本行 —— 聊天输入
                final int idx = slot;
                String current = TextUtil.plain(lore.get(idx));
                session.setPendingChatInput(true);
                ChatInput.prompt(session.player(), "编辑第 " + (idx + 1) + " 行", current, text -> {
                    session.setPendingChatInput(false);
                    if (!text.isBlank()) {
                        ItemEdits.editLoreLine(session.snapshot(), idx, text);
                    }
                    open();
                }, () -> {
                    session.setPendingChatInput(false);
                    open();
                });
            }
            return;
        }

        switch (slot) {
            case SLOT_BACK, SLOT_CLOSE -> onBack.run();
            case SLOT_ADD -> {
                session.setPendingChatInput(true);
                ChatInput.prompt(session.player(), "新增一行 Lore", "", text -> {
                    session.setPendingChatInput(false);
                    if (!text.isBlank()) {
                        ItemEdits.addLoreLine(session.snapshot(), text);
                    }
                    open();
                }, () -> {
                    session.setPendingChatInput(false);
                    open();
                });
            }
            case SLOT_CLEAR -> {
                ItemEdits.clearLore(session.snapshot());
                refresh();
            }
            default -> {}
        }
    }

    private void refresh() {
        int size = inventory.getSize();
        for (int i = 0; i < size; i++) inventory.setItem(i, null);
        build();
    }

    private ItemStack button(Material material, Component name, Component... lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(TextUtil.clean(name));
            if (lore.length > 0) {
                List<Component> cleaned = new ArrayList<>();
                for (Component c : lore) cleaned.add(TextUtil.clean(c));
                meta.lore(cleaned);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
