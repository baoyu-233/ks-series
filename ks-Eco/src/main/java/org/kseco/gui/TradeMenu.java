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

import java.util.*;

/**
 * 玩家间交易 GUI — 双方面对面交易。
 * 使用二分栏布局：左半为发起者物品，右半为目标物品。
 */
public final class TradeMenu implements InventoryHolder {

    private final KsEco plugin;
    private final Player sender;
    private final Player target;
    private Inventory inventory;
    private boolean senderConfirmed = false;
    private boolean targetConfirmed = false;

    private static final int SIZE = 54;

    public TradeMenu(KsEco plugin, Player sender, Player target) {
        this.plugin = plugin;
        this.sender = sender;
        this.target = target;
    }

    public void open() {
        build();
        sender.openInventory(inventory);
        target.openInventory(inventory);
        plugin.tradeManager().requestTrade(sender, target);
    }

    private void build() {
        // 使用共享标题
        inventory = Bukkit.createInventory(this, SIZE,
                Component.text(sender.getName() + " §8⇄ §r" + target.getName(), NamedTextColor.GOLD));

        // 左半区域（0-3 列）：sender 的物品槽
        // 右半区域（5-8 列）：target 的物品槽
        // 中间分隔：第 4 列

        // 分隔线
        for (int row = 0; row < 6; row++) {
            inventory.setItem(row * 9 + 4, divider());
        }

        // 确认按钮
        inventory.setItem(48, button(Material.RED_STAINED_GLASS_PANE,
                "§c等待确认...", "§7双方点击确认后完成交易"));
        inventory.setItem(50, button(Material.RED_STAINED_GLASS_PANE,
                "§c等待确认...", "§7双方点击确认后完成交易"));

        // 取消按钮
        inventory.setItem(49, button(Material.BARRIER, "§c取消交易", "§7关闭界面即可取消"));

        fillEmpty();
    }

    private ItemStack button(Material mat, String name, String... loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (loreLines.length > 0) {
                List<Component> lore = new ArrayList<>();
                for (String s : loreLines) lore.add(Component.text(s));
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack divider() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text("§8│")); item.setItemMeta(meta); }
        return item;
    }

    private void fillEmpty() {
        ItemStack f = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = f.getItemMeta();
        if (m != null) { m.displayName(Component.text(" ")); f.setItemMeta(m); }
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = row * 9 + col;
                if (inventory.getItem(slot) == null && col != 4) {
                    inventory.setItem(slot, f);
                }
            }
        }
    }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }

    // ---- 事件监听 ----

    public static class Listener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder() instanceof TradeMenu menu)) return;
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player clicker)) return;

            int slot = event.getSlot();
            if (slot < 0) return;

            // 取消按钮
            if (slot == 49) {
                clicker.closeInventory();
                plugin.tradeManager().cancel(clicker);
                return;
            }

            // 确认按钮
            if (slot == 48 || slot == 50) {
                boolean ok = plugin.tradeManager().confirm(clicker);
                if (ok) {
                    menu.sender.closeInventory();
                    menu.target.closeInventory();
                } else if (clicker.equals(menu.sender)) {
                    menu.senderConfirmed = true;
                    menu.inventory.setItem(48, menu.button(Material.GREEN_STAINED_GLASS_PANE,
                            "§a已确认", "§7等待对方确认..."));
                    clicker.sendMessage("§a已确认，等待对方...");
                } else if (clicker.equals(menu.target)) {
                    menu.targetConfirmed = true;
                    menu.inventory.setItem(50, menu.button(Material.GREEN_STAINED_GLASS_PANE,
                            "§a已确认", "§7等待对方确认..."));
                    clicker.sendMessage("§a已确认，等待对方...");
                }
                return;
            }

            // 物品槽（允许玩家放入/取出物品）
            // 左半区域（col 0-3）仅 sender 可操作
            int col = slot % 9;
            if (col < 4) {
                if (!clicker.equals(menu.sender)) return;
            } else if (col > 4) {
                if (!clicker.equals(menu.target)) return;
            }
            // 允许物品交互
            event.setCancelled(false);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            if (!(event.getInventory().getHolder() instanceof TradeMenu menu)) return;
            // 任一方关闭 → 取消交易
            if (!menu.senderConfirmed || !menu.targetConfirmed) {
                plugin.tradeManager().cancel((Player) event.getPlayer());
            }
        }
    }
}
