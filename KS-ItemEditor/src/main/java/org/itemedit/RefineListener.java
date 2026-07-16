package org.itemedit;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 精炼会话期间的防滥用监听。
 *
 * 关闭逻辑采用 1-tick 延迟检测——同一 tick 内切换菜单时不会误取消会话。
 */
public final class RefineListener implements Listener {

    private final ItemEditor plugin;

    public RefineListener(ItemEditor plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();

        if (holder instanceof RefineMenu menu) {
            event.setCancelled(true);
            if (event.getRawSlot() < top.getSize()) menu.handle(event);
            return;
        }
        if (holder instanceof RefineLoreMenu loreMenu) {
            event.setCancelled(true);
            if (event.getRawSlot() < top.getSize()) loreMenu.handle(event);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof RefineMenu || holder instanceof RefineLoreMenu) {
            event.setCancelled(true);
        }
    }

    // ---- 关闭：1-tick 延迟检测，区分“切菜单”与“真正关闭” ----

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();

        RefineSession session = plugin.refineSession(player);
        if (session == null || session.isHandled()) return;

        if (holder instanceof RefineMenu) {
            // 聊天输入中 → 等待回调
            if (session.isPendingChatInput()) return;

            // 1 tick 后检查：若切到了 RefineLoreMenu 则保留，否则取消
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.refineSession(player) != session || session.isHandled()) return;
                Inventory top = player.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof RefineLoreMenu) return; // 切到子菜单
                session.cancel();
                player.sendMessage(TextUtil.parse("&7精炼已取消，物品已归还。"));
            });
            return;
        }

        if (holder instanceof RefineLoreMenu) {
            // 聊天输入中 → 等待回调
            if (session.isPendingChatInput()) return;

            // 1 tick 后检查：若已切回 RefineMenu 则无需操作，否则自动打开
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.refineSession(player) != session || session.isHandled()) return;
                Inventory top = player.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof RefineMenu) return; // 已由回调切回
                // Escape 关闭 → 回到精炼主菜单
                new RefineMenu(plugin, session,
                        plugin.getConfig().getConfigurationSection("refine")).open();
            });
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        RefineSession session = plugin.refineSession(event.getPlayer());
        if (session != null && !session.isHandled()) event.setCancelled(true);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        RefineSession session = plugin.refineSession(event.getPlayer());
        if (session != null && !session.isHandled()) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        RefineSession session = plugin.refineSession(event.getPlayer());
        if (session != null && !session.isHandled()) session.cancel();
    }
}
