package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.StorageManager;

import java.util.*;

/**
 * 暂存箱 GUI — 玩家查看/提取暂存物品。
 */
public final class StorageMenu implements InventoryHolder {

    private final KsEco plugin;
    private final Player player;
    private Inventory inventory;
    private List<StorageManager.StoredItem> items = new ArrayList<>();
    private int page = 0;

    private static final int SIZE = 54;

    public StorageMenu(KsEco plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void open() {
        build();
        player.openInventory(inventory);
    }

    private void build() {
        inventory = Bukkit.createInventory(this, SIZE,
                Component.text("暂存箱 — " + player.getName(), NamedTextColor.DARK_GREEN));

        items = plugin.storageManager().getPlayerItems(player.getUniqueId());

        int start = page * 45;
        int end = Math.min(start + 45, items.size());

        for (int i = start; i < end; i++) {
            var stored = items.get(i);
            int slot = i - start;
            ItemStack display = stored.item().clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.text(""));
                lore.add(Component.text("§e来源: " + formatSource(stored.source()), NamedTextColor.GRAY));
                lore.add(Component.text("§e存放时间: " + formatTime(stored.storedAt()), NamedTextColor.GRAY));
                lore.add(Component.text("§a§l点击提取", NamedTextColor.GREEN));
                meta.lore(lore);
                display.setItemMeta(meta);
            }
            inventory.setItem(slot, display);
        }

        // 导航
        if (page > 0) {
            inventory.setItem(45, navButton(Material.ARROW, "§a上一页"));
        }
        if (end < items.size()) {
            inventory.setItem(53, navButton(Material.ARROW, "§a下一页"));
        }

        // 信息
        inventory.setItem(49, navButton(Material.BOOK,
                "§e暂存箱 §7(" + items.size() + " 件)"));

        fillEmpty();
    }

    private ItemStack navButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text(name)); item.setItemMeta(meta); }
        return item;
    }

    private void fillEmpty() {
        ItemStack f = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = f.getItemMeta();
        if (m != null) { m.displayName(Component.text(" ")); f.setItemMeta(m); }
        for (int i = 0; i < SIZE; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, f);
        }
    }

    private String formatSource(String src) {
        if (src == null || src.isBlank()) return "未知来源";
        if (src.startsWith("MARKET_PURCHASE")) return "市场购买";
        if (src.startsWith("PURCHASE_ORDER")) return "求购交付";
        if (src.startsWith("EXCHANGE")) return "官方兑换所得";
        if (src.startsWith("BLIND_BOX") || src.startsWith("BLINDBOX")) return "盲盒奖励";
        if (src.startsWith("LIMITED_SALE")) return "限时直售购买";
        return switch (src) {
            case "PURCHASE" -> "购买所得";
            case "LISTING_RETURN" -> "下架退回";
            case "ADMIN" -> "管理员发放";
            default -> "系统发放";
        };
    }

    private String formatTime(long unixSeconds) {
        long diff = System.currentTimeMillis() / 1000 - unixSeconds;
        if (diff < 3600) return (diff / 60) + " 分钟前";
        if (diff < 86400) return (diff / 3600) + " 小时前";
        return (diff / 86400) + " 天前";
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }

    // ---- 事件监听 ----

    public static class Listener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof StorageMenu menu)) return;
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= SIZE) return;

            // 导航
            if (slot == 45 && menu.page > 0) {
                menu.page--;
                menu.build();
                player.openInventory(menu.getInventory());
                return;
            }
            if (slot == 53) {
                int maxPage = (menu.items.size() - 1) / 45;
                if (menu.page < maxPage) {
                    menu.page++;
                    menu.build();
                    player.openInventory(menu.getInventory());
                }
                return;
            }

            // 仅 0-44 是物品展示区；底栏信息/填充槽不得映射到未展示的条目。
            if (slot >= 45) return;

            // 提取物品
            int index = menu.page * 45 + slot;
            if (index < menu.items.size()) {
                var stored = menu.items.get(index);
                player.closeInventory();
                boolean ok = plugin.storageManager().withdrawItem(
                        player.getUniqueId(), stored.id(), player);
                if (ok) {
                    player.sendMessage("§a物品已提取到背包！");
                } else {
                    player.sendMessage("§c提取失败，请重试。");
                }
            }
        }
    }
}
