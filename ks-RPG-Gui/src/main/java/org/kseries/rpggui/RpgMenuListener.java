package org.kseries.rpggui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

final class RpgMenuListener implements Listener {
    @EventHandler
    void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RpgMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        menu.click(player, slot);
    }

    @EventHandler
    void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RpgMenu) event.setCancelled(true);
    }
}
