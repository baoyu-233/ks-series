package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.ListingManager.Listing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 挂单预览确认界面。
 * 左键点击挂单 → 进入此界面查看完整物品属性、选择数量、确认购买。
 */
public final class ListingPreviewMenu implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private final Listing listing;
    private int selectedQty;
    private final boolean returnMyOnly;
    private UUID viewerUuid;

    public ListingPreviewMenu(KsEco plugin) {
        this.plugin = plugin;
        this.listing = null;
        this.returnMyOnly = false;
    }

    public ListingPreviewMenu(KsEco plugin, Listing listing, boolean returnMyOnly) {
        this.plugin = plugin;
        this.listing = listing;
        this.selectedQty = 1;
        this.returnMyOnly = returnMyOnly;
    }

    public void open(Player player, Listing listing, boolean returnMyOnly) {
        var menu = new ListingPreviewMenu(plugin, listing, returnMyOnly);
        menu.viewerUuid = player.getUniqueId();
        menu.build();
        player.openInventory(menu.inventory);
    }

    private boolean isSeller() {
        return viewerUuid != null && listing != null && listing.sellerUuid().equals(viewerUuid);
    }

    private void build() {
        boolean property = listing.isProperty();
        boolean seller = isSeller();
        inventory = Bukkit.createInventory(this, 54,
                Component.text(seller ? "§8我的挂单 — 撤单" : (property ? "§8商品房预览 — 购买确认" : "§8物品预览 — 购买确认")));

        // Slot 13: 实物挂单显示完整 NBT；商品房没有实体物品，显示房屋图标 + 详情
        ItemStack display = property ? buildPropertyDisplay() : listing.toItemStack();
        if (!property) {
            display.setAmount(Math.min(listing.quantity(), Math.max(1, display.getMaxStackSize())));
            ItemMeta displayMeta = display.getItemMeta();
            if (displayMeta != null) {
                List<Component> lore = displayMeta.hasLore() ? new ArrayList<>(displayMeta.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(Component.text("§7━━━━ 挂单信息 ━━━━"));
                lore.add(Component.text("卖家: " + listing.sellerName(), NamedTextColor.GRAY));
                lore.add(Component.text("库存: " + listing.quantity(), NamedTextColor.GRAY));
                lore.add(Component.text("单价: " + plugin.vaultHook().format(listing.unitPrice()), NamedTextColor.GOLD));
                displayMeta.lore(lore);
                display.setItemMeta(displayMeta);
            }
        }
        inventory.setItem(13, display);

        if (!property) {
            // Quantity selector（商品房只能整套购买，数量固定为 1，不显示选择器）
            inventory.setItem(28, qtyButton(Material.RED_STAINED_GLASS_PANE, "§c-64"));
            inventory.setItem(29, qtyButton(Material.ORANGE_STAINED_GLASS_PANE, "§c-16"));
            inventory.setItem(30, qtyButton(Material.YELLOW_STAINED_GLASS_PANE, "§e-1"));

            ItemStack qtyDisplay = new ItemStack(Material.PAPER, Math.min(selectedQty, 64));
            ItemMeta qm = qtyDisplay.getItemMeta();
            if (qm != null) {
                qm.displayName(Component.text("§e数量: " + selectedQty, NamedTextColor.YELLOW));
                double cost = listing.unitPrice() * selectedQty;
                List<Component> ql = new ArrayList<>();
                ql.add(Component.text("总价: " + plugin.vaultHook().format(cost), NamedTextColor.GOLD));
                qm.lore(ql);
                qtyDisplay.setItemMeta(qm);
            }
            inventory.setItem(31, qtyDisplay);

            inventory.setItem(32, qtyButton(Material.LIME_STAINED_GLASS_PANE, "§a+1"));
            inventory.setItem(33, qtyButton(Material.GREEN_STAINED_GLASS_PANE, "§a+16"));
            inventory.setItem(34, qtyButton(Material.EMERALD_BLOCK, "§a+64"));
            inventory.setItem(35, qtyButton(Material.CHEST, "§b全部: " + listing.quantity()));
        }

        // Bottom row
        double cost = listing.unitPrice() * selectedQty;
        double tax = Math.max(cost * plugin.getCategoryTaxRate(property ? "PROPERTY_TRADE" : "MARKET_TRADE"),
                plugin.ecoConfig().getMinTax());
        if (seller) {
            inventory.setItem(45, navButton(Material.TNT, "§c✖ 撤单",
                    "§7点击撤销此挂单",
                    "§7物品将退回至暂存箱"));
        } else {
            inventory.setItem(45, navButton(Material.GOLD_INGOT, property ? "§a✔ 确认购房" : "§a✔ 确认购买",
                    "§7总价: " + plugin.vaultHook().format(cost),
                    "§7税费(预估): " + plugin.vaultHook().format(tax),
                    "§7实际支付(预估): " + plugin.vaultHook().format(cost + tax)));
        }

        inventory.setItem(47, navButton(Material.PLAYER_HEAD, "§7卖家: " + listing.sellerName()));
        inventory.setItem(49, navButton(Material.BARRIER, "§c取消", "§7返回市场列表"));
        inventory.setItem(53, navButton(Material.OAK_DOOR, "§7返回", "§7返回市场列表"));

        fillEmpty();
    }

    private ItemStack buildPropertyDisplay() {
        ItemStack item = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String houseId = listing.assetRef();
        var house = plugin.marketManager().getHouseInfo(houseId);
        List<Component> lore = new ArrayList<>();
        if (house != null) {
            meta.displayName(Component.text("§e🏠 " + String.valueOf(house.get("name")), NamedTextColor.YELLOW));
            lore.add(Component.text("房屋ID: " + houseId, NamedTextColor.GRAY));
            lore.add(Component.text("世界: " + house.get("world"), NamedTextColor.GRAY));
            lore.add(Component.text("范围: [" + house.get("x1") + "," + house.get("y1") + "," + house.get("z1") + "] - ["
                    + house.get("x2") + "," + house.get("y2") + "," + house.get("z2") + "]", NamedTextColor.GRAY));
        } else {
            meta.displayName(Component.text("§e🏠 商品房 " + houseId, NamedTextColor.YELLOW));
            lore.add(Component.text("§c房屋详情读取失败（房地产模块未加载？）", NamedTextColor.RED));
        }
        lore.add(Component.empty());
        lore.add(Component.text("卖家: " + listing.sellerName(), NamedTextColor.GRAY));
        lore.add(Component.text("价格: " + plugin.vaultHook().format(listing.unitPrice()), NamedTextColor.GOLD));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack qtyButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text(name)); item.setItemMeta(meta); }
        return item;
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
            if (!(event.getView().getTopInventory().getHolder() instanceof ListingPreviewMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (menu.listing == null) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= menu.getInventory().getSize()) return;
            boolean property = menu.listing.isProperty();
            switch (slot) {
                case 28 -> { if (!property) { menu.selectedQty = Math.max(1, menu.selectedQty - 64); menu.build(); player.openInventory(menu.getInventory()); } }
                case 29 -> { if (!property) { menu.selectedQty = Math.max(1, menu.selectedQty - 16); menu.build(); player.openInventory(menu.getInventory()); } }
                case 30 -> { if (!property) { menu.selectedQty = Math.max(1, menu.selectedQty - 1); menu.build(); player.openInventory(menu.getInventory()); } }
                case 32 -> { if (!property) { menu.selectedQty = Math.min(menu.listing.quantity(), menu.selectedQty + 1); menu.build(); player.openInventory(menu.getInventory()); } }
                case 33 -> { if (!property) { menu.selectedQty = Math.min(menu.listing.quantity(), menu.selectedQty + 16); menu.build(); player.openInventory(menu.getInventory()); } }
                case 34 -> { if (!property) { menu.selectedQty = Math.min(menu.listing.quantity(), menu.selectedQty + 64); menu.build(); player.openInventory(menu.getInventory()); } }
                case 35 -> { if (!property) { menu.selectedQty = menu.listing.quantity(); menu.build(); player.openInventory(menu.getInventory()); } }
                case 45 -> {
                    player.closeInventory();
                    if (menu.isSeller()) {
                        boolean ok = plugin.listingManager().cancelListing(menu.listing.id(), player.getUniqueId());
                        player.sendMessage(ok ? "§a挂单已撤销，物品已退回暂存箱。" : "§c撤单失败（挂单不存在或无权操作）。");
                        if (ok) new MarketMenu(plugin).open(player, true, menu.listing.isProperty());
                    } else {
                        plugin.marketManager().buyListing(player, menu.listing.id(), menu.selectedQty);
                    }
                }
                case 49, 53 -> { // Cancel / Back
                    player.closeInventory();
                    new MarketMenu(plugin).open(player, menu.returnMyOnly, property);
                }
            }
        }
    }
}
