package org.kstitle;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.kstitle.model.TitleDef;

/** 称号 GUI 点击处理：翻页/关闭/佩戴切换/购买。 */
public final class TitleMenuListener implements Listener {

    private final KsTitle plugin;

    public TitleMenuListener(KsTitle plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TitleMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TitleMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return; // 点击在下方玩家背包，忽略

        if (slot == TitleMenu.NAV_CLOSE) { player.closeInventory(); return; }
        if (slot == TitleMenu.NAV_PREV) { TitleMenu.open(plugin, player, holder.page() - 1); return; }
        if (slot == TitleMenu.NAV_NEXT) { TitleMenu.open(plugin, player, holder.page() + 1); return; }

        Integer titleId = holder.titleIdAt(slot);
        if (titleId == null) return;

        boolean owned = plugin.titleManager().hasOwnership(player.getUniqueId(), titleId);
        if (owned) {
            Integer equipped = plugin.titleManager().getEquipped(player.getUniqueId());
            if (equipped != null && equipped == titleId) {
                plugin.titleManager().unequip(player);
                player.sendMessage("§7已摘下称号");
            } else {
                boolean ok = plugin.titleManager().equip(player, titleId);
                player.sendMessage(ok ? "§a已佩戴称号" : "§c佩戴失败");
            }
        } else {
            TitleDef def = plugin.titleManager().getDef(titleId);
            if (def != null && def.isPurchase()) {
                var result = plugin.titleManager().buy(player, titleId);
                player.sendMessage((result.success() ? "§a" : "§c") + result.message());
            } else if (def != null && def.isCondition()) {
                player.sendMessage("§c尚未达成解锁条件: " + def.conditionValue());
            } else {
                player.sendMessage("§c该称号仅可由管理员发放");
            }
        }
        TitleMenu.open(plugin, player, holder.page());
    }
}
