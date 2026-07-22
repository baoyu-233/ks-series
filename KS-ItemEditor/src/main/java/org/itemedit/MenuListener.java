package org.itemedit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class MenuListener implements Listener {

    private final ItemEditor plugin;

    public MenuListener(ItemEditor plugin) {
        this.plugin = plugin;
    }

    // ---------------- 普通菜单 ----------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof Menu menu) {
            event.setCancelled(true); // 锁定，避免移动正在编辑的物品
            if (!(event.getWhoClicked() instanceof Player player) || !menu.isOwnedBy(player)) return;
            // 用 rawSlot 判断是否点击了顶部菜单（Shift+点击时 getClickedInventory 可能指向玩家背包）
            int topSize = event.getInventory().getSize();
            if (event.getRawSlot() >= 0 && event.getRawSlot() < topSize) {
                menu.handle(event);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Menu) {
            event.setCancelled(true);
        }
    }

    // ---------------- 关闭处理 ----------------

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(event.getPlayer() instanceof Player player)) return;

        if (!(holder instanceof Menu menu) || !menu.isOwnedBy(player)) return;

        // ★ 如果会话正在等待聊天输入，不清理（聊天回调会重新打开菜单）
        EditSession session = plugin.session(player);
        if (session != null && session.isPendingChatInput()) return;

        // 下一 tick 判断：若切换到了另一个菜单，则保留会话；否则结束
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.session(player) != session) return;
            InventoryHolder top = player.getOpenInventory().getTopInventory().getHolder();
            if (top instanceof Menu) return;
            plugin.sessions().remove(player.getUniqueId(), session);
        });
    }
}
