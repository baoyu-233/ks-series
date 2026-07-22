package org.itemedit;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class Menu implements InventoryHolder {

    protected final ItemEditor plugin;
    protected final EditSession session;
    protected Inventory inventory;

    protected Menu(ItemEditor plugin, EditSession session) {
        this.plugin = plugin;
        this.session = session;
    }

    protected abstract void build();

    public abstract void handle(InventoryClickEvent event);

    boolean isOwnedBy(Player player) {
        return player != null
                && session.player() == player
                && plugin.session(player) == session;
    }

    public void open() {
        Player p = session.player();
        if (p == null) return;
        ItemStack editing = session.item();
        if (editing == null || editing.getType().isAir()) {
            p.sendMessage(TextUtil.parse("&c你主手里已经没有物品了，编辑结束。"));
            plugin.sessions().remove(p.getUniqueId());
            p.closeInventory();
            return;
        }
        build();
        p.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    // ---------------- 构建工具 ----------------

    protected Inventory create(int size, Component title) {
        return Bukkit.createInventory(this, size, title);
    }

    protected ItemStack button(Material material, Component name, Component... lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(TextUtil.clean(name));
            if (lore.length > 0) {
                List<Component> l = new ArrayList<>();
                for (Component c : lore) l.add(TextUtil.clean(c));
                meta.lore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    protected ItemStack button(Material material, Component name, List<Component> lore) {
        return button(material, name, lore.toArray(new Component[0]));
    }

    protected ItemStack filler() {
        return button(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
    }

    /** 把所有空槽填上灰色玻璃板。 */
    protected void fillEmpty() {
        ItemStack f = filler();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, f);
        }
    }

    protected static List<Component> lines(Component... c) {
        return new ArrayList<>(Arrays.asList(c));
    }
}
