package org.itemedit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class ModelMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private static final int PREV = 45;
    private static final int INPUT_KEEP = 47;
    private static final int BACK = 48;
    private static final int INFO = 49;
    private static final int CLOSE = 50;
    private static final int INPUT_REPLACE = 51;
    private static final int NEXT = 53;

    private final int page;

    public ModelMenu(ItemEditor plugin, EditSession session, int page) {
        super(plugin, session);
        this.page = Math.max(0, page);
    }

    @Override
    protected void build() {
        inventory = create(54, Component.text("ItemsAdder 套模型", NamedTextColor.DARK_AQUA));

        List<String> ids = plugin.itemsAdderEnabled() ? ItemsAdderHook.allIds() : new ArrayList<>();
        int totalPages = Math.max(1, (ids.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.min(page, totalPages - 1);
        int from = p * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, ids.size());

        for (int i = from; i < to; i++) {
            String id = ids.get(i);
            ItemStack icon = ItemsAdderHook.reference(id);
            if (icon == null) icon = new ItemStack(Material.PAPER);
            icon = icon.clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(TextUtil.clean(Component.text(id, NamedTextColor.LIGHT_PURPLE)));
                List<Component> lore = new ArrayList<>();
                lore.add(TextUtil.clean(Component.text("左键: 套用模型(保留本体)", NamedTextColor.GREEN)));
                lore.add(TextUtil.clean(Component.text("Shift+左键: 替换为该物品", NamedTextColor.YELLOW)));
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            inventory.setItem(i - from, icon);
        }

        if (ids.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER,
                    Component.text("没有可浏览的 ItemsAdder 物品", NamedTextColor.RED),
                    Component.text("可改用下方“输入ID”按钮", NamedTextColor.GRAY)));
        }

        inventory.setItem(PREV, p > 0
                ? button(Material.ARROW, Component.text("上一页", NamedTextColor.WHITE)) : filler());
        inventory.setItem(NEXT, p < totalPages - 1
                ? button(Material.ARROW, Component.text("下一页", NamedTextColor.WHITE)) : filler());
        inventory.setItem(INFO, button(Material.PAPER,
                Component.text("第 " + (p + 1) + " / " + totalPages + " 页", NamedTextColor.GRAY)));
        inventory.setItem(INPUT_KEEP, button(Material.NAME_TAG,
                Component.text("输入ID套用 (保留本体)", NamedTextColor.GREEN),
                Component.text("武器还是武器，只换外观", NamedTextColor.GRAY)));
        inventory.setItem(INPUT_REPLACE, button(Material.NAME_TAG,
                Component.text("输入ID替换为IA物品", NamedTextColor.YELLOW),
                Component.text("以IA物品为本体并搬移词条", NamedTextColor.GRAY)));
        inventory.setItem(BACK, button(Material.ARROW, Component.text("返回", NamedTextColor.WHITE)));
        inventory.setItem(CLOSE, button(Material.BARRIER, Component.text("关闭", NamedTextColor.RED)));

        fillEmpty();
    }

    @Override
    public void handle(InventoryClickEvent event) {
        int slot = event.getSlot();

        if (slot < PAGE_SIZE) {
            List<String> ids = plugin.itemsAdderEnabled() ? ItemsAdderHook.allIds() : new ArrayList<>();
            int idx = page * PAGE_SIZE + slot;
            if (idx >= ids.size()) return;
            String id = ids.get(idx);
            ItemStack ref = ItemsAdderHook.reference(id);
            if (ref == null) {
                session.player().sendMessage(TextUtil.parse("&c该 ID 已失效: &f" + id));
                open();
                return;
            }
            ItemStack item = session.item();
            if (event.getClick().isShiftClick()) {
                ItemStack out = ItemEdits.applyModelReplace(ref, item);
                session.setItem(out);
                session.player().sendMessage(TextUtil.parse("&a已替换为 IA 物品: &f" + id));
            } else {
                boolean ok = ItemEdits.applyModelKeepBody(item, ref);
                session.setItem(item);
                session.player().sendMessage(ok
                        ? TextUtil.parse("&a已套用模型(保留本体): &f" + id)
                        : TextUtil.parse("&e该物品无可复制外观，试试 Shift+左键 替换。"));
            }
            open();
            return;
        }

        switch (slot) {
            case PREV -> new ModelMenu(plugin, session, page - 1).open();
            case NEXT -> new ModelMenu(plugin, session, page + 1).open();
            case BACK -> new MainMenu(plugin, session).open();
            case INPUT_KEEP -> session.promptModel(true);
            case INPUT_REPLACE -> session.promptModel(false);
            case CLOSE -> session.player().closeInventory();
            default -> { /* info / filler */ }
        }
    }
}
