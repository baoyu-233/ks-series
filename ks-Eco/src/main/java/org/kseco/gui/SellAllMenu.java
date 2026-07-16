package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.OfficialBuyManager.SellAllLine;
import org.kseco.OfficialBuyManager.SellAllPreview;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键出售确认界面 — 预览背包内所有可被官方收购的物品（按材质汇总）与总价，
 * 确认后批量出售；潜影盒仅移出可被官方收购的内容，箱体和其余内容保留。
 */
public final class SellAllMenu implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private SellAllPreview preview;

    public SellAllMenu(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        this.preview = plugin.officialBuyManager().previewSellAll(player);
        build();
        player.openInventory(inventory);
    }

    private void build() {
        inventory = Bukkit.createInventory(this, 54, Component.text("§8一键出售 — 背包确认"));

        List<SellAllLine> lines = preview.lines();
        for (int i = 0; i < lines.size() && i < 45; i++) {
            inventory.setItem(i, buildLineItem(lines.get(i)));
        }

        if (lines.isEmpty()) {
            inventory.setItem(22, navButton(Material.BARRIER, "§c背包中没有可被官方收购的物品",
                    "§7普通背包和潜影盒中均未发现可收购物品"));
        }

        inventory.setItem(45, navButton(Material.BARRIER, "§c取消", "§7不出售，关闭窗口"));

        List<String> confirmLore = new ArrayList<>();
        confirmLore.add("§7种类: " + lines.size());
        confirmLore.add("§7总价: §e" + plugin.vaultHook().format(preview.total()));
        if (preview.shulkerBoxes() > 0) {
            confirmLore.add("§7已检查 " + preview.shulkerBoxes() + " 个潜影盒（箱体和未收购物品会保留）");
        }
        inventory.setItem(49, navButton(Material.GOLD_INGOT, "§a✔ 确认出售全部", confirmLore.toArray(new String[0])));

        fillEmpty();
    }

    private ItemStack buildLineItem(SellAllLine line) {
        Material mat = Material.matchMaterial(line.material());
        ItemStack item = new ItemStack(mat != null ? mat : Material.BARRIER, Math.min(line.quantity(), 64));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("数量: " + line.quantity(), NamedTextColor.GRAY));
            lore.add(Component.text("单价: " + plugin.vaultHook().format(line.unitPrice()), NamedTextColor.GOLD));
            lore.add(Component.text("小计: " + plugin.vaultHook().format(line.lineTotal()), NamedTextColor.GREEN));
            lore.add(trendLine(line.material()));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Component trendLine(String material) {
        String trend = plugin.priceEngine().getTrend(material);
        return switch (trend) {
            case "UP" -> Component.text("趋势: ▲ 涨", NamedTextColor.GREEN);
            case "DOWN" -> Component.text("趋势: ▼ 跌", NamedTextColor.RED);
            default -> Component.text("趋势: — 平", NamedTextColor.GRAY);
        };
    }

    private ItemStack navButton(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (lore.length > 0) {
                List<Component> list = new ArrayList<>();
                for (String s : lore) list.add(Component.text(s, NamedTextColor.GRAY));
                meta.lore(list);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillEmpty() {
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) { gm.displayName(Component.text(" ")); glass.setItemMeta(gm); }
        for (int i = 0; i < 54; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
        }
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }

    // ---- Listener ----

    public static class Listener implements org.bukkit.event.Listener {

        private final KsEco plugin;
        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof SellAllMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= menu.getInventory().getSize()) return;
            switch (slot) {
                case 45 -> player.closeInventory();
                case 49 -> {
                    player.closeInventory();
                    plugin.officialBuyManager().executeSellAll(player);
                }
                default -> { /* 物品展示格，不可点击 */ }
            }
        }
    }
}
