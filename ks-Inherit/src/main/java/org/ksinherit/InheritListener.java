package org.ksinherit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 背包事件监听器。
 *
 * <p>负责：
 *   - GUI 关闭时自动保存
 *   - 阻止潜影盒放入
 *   - 阻止锁定槽位交互
 *   - 按钮点击处理
 */
public final class InheritListener implements Listener {

    private final KsInherit plugin;

    InheritListener(KsInherit plugin) {
        this.plugin = plugin;
    }

    /** GUI 关闭时自动保存 */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof InheritGUI gui)) return;

        gui.saveAll();
        Player player = (Player) event.getPlayer();
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_CHEST_CLOSE, 0.5f, 1.0f);
    }

    /** 点击事件：按钮、锁定格、潜影盒检测 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof InheritGUI gui)) return;

        // 取消所有操作，由我们判断
        event.setCancelled(true);

        int rawSlot = event.getRawSlot();
        ClickType clickType = event.getClick();

        // 如果是玩家背包的点击，放行
        if (rawSlot >= 54 || rawSlot < 0) {
            // 但阻止通过 Shift 把潜影盒移入
            if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                ItemStack current = event.getCurrentItem();
                if (current != null && KsInherit.isShulkerBox(current.getType())) {
                    event.setCancelled(true);
                    ((Player) event.getWhoClicked()).sendMessage("§c📦 不允许存入潜影盒！");
                    return;
                }
            }
            // 允许玩家背包内的操作
            event.setCancelled(false);
            return;
        }

        // 处理 GUI 按钮点击
        gui.handleClick(rawSlot, clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT);

        // 如果点击的是可用槽位（非按钮，非锁定），允许操作
        // handleClick 对锁定格和按钮返回 true（已取消），对普通槽返回 false
        if (!event.isCancelled()) {
            return;
        }
    }

    /** 拖拽事件：阻止跨锁定格拖拽 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof InheritGUI gui)) return;

        Player player = (Player) event.getWhoClicked();

        // 检测拖拽是否涉及锁定格
        int maxSlots = plugin.getMaxSlots(player);
        for (int slot : event.getRawSlots()) {
            if (slot < 54 && slot >= maxSlots) {
                // 涉及锁定格：拒绝整个拖拽
                event.setCancelled(true);
                player.sendMessage("§c🔒 拖拽涉及锁定槽位");
                return;
            }
        }

        // 检测是否有潜影盒
        ItemStack cursor = event.getOldCursor();
        if (cursor != null && KsInherit.isShulkerBox(cursor.getType())) {
            // 检查目标是否在 GUI 内
            for (int slot : event.getRawSlots()) {
                if (slot < 54) {
                    event.setCancelled(true);
                    player.sendMessage("§c📦 不允许存入潜影盒！");
                    return;
                }
            }
        }
    }

    /** 检测放入物品是否为潜影盒 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCursorPut(InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof InheritGUI)) return;

        // 如果物品在光标上，检查潜影盒
        ItemStack cursor = event.getCursor();
        if (cursor != null && KsInherit.isShulkerBox(cursor.getType())) {
            int rawSlot = event.getRawSlot();
            if (rawSlot >= 0 && rawSlot < 54) {
                event.setCancelled(true);
                ((Player) event.getWhoClicked()).sendMessage("§c📦 不允许存入潜影盒！");
            }
        }
    }

    /** 数字键切换：防止潜影盒通过快捷键放入 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onNumberKeySwap(InventoryClickEvent event) {
        if (event.getClick() != ClickType.NUMBER_KEY) return;

        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof InheritGUI)) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= 54) return;

        // 数字键从 hotbar 对应槽位获取物品
        int hotbarSlot = event.getHotbarButton();
        Player player = (Player) event.getWhoClicked();
        ItemStack hotbarItem = player.getInventory().getItem(hotbarSlot);
        if (hotbarItem != null && KsInherit.isShulkerBox(hotbarItem.getType())) {
            event.setCancelled(true);
            player.sendMessage("§c📦 不允许存入潜影盒！");
        }
    }
}
