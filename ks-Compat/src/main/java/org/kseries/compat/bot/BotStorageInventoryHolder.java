package org.kseries.compat.bot;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class BotStorageInventoryHolder implements InventoryHolder {
    final UUID ownerUuid;
    final int page;
    final Map<Integer, String> storageIds = new LinkedHashMap<>();
    private Inventory inventory;

    BotStorageInventoryHolder(UUID ownerUuid, int page) {
        this.ownerUuid = ownerUuid;
        this.page = page;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Storage inventory has not been created yet.");
        return inventory;
    }
}
