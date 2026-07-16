package org.kstitle;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** GUI 状态载体：当前页码 + 每页展示的称号ID（按槽位下标对应）。 */
public final class TitleMenuHolder implements InventoryHolder {

    private final UUID viewer;
    private final int page;
    private final List<Integer> pageTitleIds;
    private Inventory inventory;

    public TitleMenuHolder(UUID viewer, int page, List<Integer> pageTitleIds) {
        this.viewer = viewer;
        this.page = page;
        this.pageTitleIds = pageTitleIds;
    }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }

    public UUID viewer() { return viewer; }
    public int page() { return page; }

    /** 槽位下标(0~44) 对应的称号ID；越界或空位返回 null。 */
    public Integer titleIdAt(int slot) {
        if (slot < 0 || slot >= pageTitleIds.size()) return null;
        return pageTitleIds.get(slot);
    }

    public int totalOnPage() { return pageTitleIds.size(); }
}
