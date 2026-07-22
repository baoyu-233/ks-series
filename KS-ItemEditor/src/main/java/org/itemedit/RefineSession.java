package org.itemedit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 玩家精炼会话。
 *
 * 与 EditSession 不同：
 * - 物品从玩家手中取出（防止编辑期间丢弃/转移）
 * - 所有编辑在快照（snapshot）上进行
 * - 确认 → snapshot 写回手中 + 扣券
 * - 取消 → originalItem 归还
 */
public final class RefineSession {

    private final ItemEditor plugin;
    private final UUID playerId;
    private final int handSlot;
    private final ItemStack originalItem;  // 原始件，取消时归还
    private ItemStack snapshot;            // 编辑中的快照，确认时写入
    private boolean handled = false;
    private boolean pendingChatInput = false;

    public RefineSession(ItemEditor plugin, Player player) {
        this.plugin = plugin;
        this.playerId = player.getUniqueId();
        this.handSlot = player.getInventory().getHeldItemSlot();

        // ★ 从玩家手中取出物品，防止编辑期间丢弃
        this.originalItem = player.getInventory().getItem(handSlot).clone();
        this.snapshot = originalItem.clone();
        player.getInventory().setItem(handSlot, null);
    }

    public Player player() {
        return Bukkit.getPlayer(playerId);
    }

    public UUID playerId() {
        return playerId;
    }

    public int handSlot() {
        return handSlot;
    }

    /** 编辑中的快照。RefineMenu 上的所有修改都作用于此。 */
    public ItemStack snapshot() {
        return snapshot;
    }

    public void setSnapshot(ItemStack stack) {
        this.snapshot = stack;
    }

    /** 原始物品（未修改）。 */
    public ItemStack originalItem() {
        return originalItem;
    }

    public boolean isHandled() {
        return handled;
    }

    /** 正在等待聊天输入——期间关闭 GUI 不应取消会话。 */
    public boolean isPendingChatInput() {
        return pendingChatInput;
    }

    public void setPendingChatInput(boolean v) {
        this.pendingChatInput = v;
    }

    /** 确认：将快照写回玩家手中，标记已处理。 */
    public void confirm() {
        if (handled) return;
        handled = true;
        Player p = player();
        if (p != null) {
            deliver(p, snapshot);
        }
        cleanup();
    }

    /** 取消：将原始件归还玩家手中，标记已处理。 */
    public void cancel() {
        if (handled) return;
        handled = true;
        Player p = player();
        if (p != null) {
            deliver(p, originalItem);
        }
        cleanup();
    }

    private void cleanup() {
        plugin.refineSessions().remove(playerId);
    }

    private void deliver(Player player, ItemStack item) {
        ItemStack returned = item.clone();
        ItemStack occupying = player.getInventory().getItem(handSlot);
        if (occupying == null || occupying.getType().isAir()) {
            player.getInventory().setItem(handSlot, returned);
            return;
        }
        for (ItemStack overflow : player.getInventory().addItem(returned).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }
}
