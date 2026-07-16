package org.kseries.rpg.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

/** Server-thread API for administrator previews and grants of currently configured RPG items. */
public interface RpgContentApi {
    List<ContentItem> items();

    Optional<ItemStack> preview(String key);

    GrantResult grant(Player player, String key);

    record ContentItem(String key, String category, String type, String id) { }

    enum GrantResult {
        GRANTED,
        DROPPED_AT_PLAYER,
        UNKNOWN_ITEM,
        ITEM_UNAVAILABLE
    }
}
