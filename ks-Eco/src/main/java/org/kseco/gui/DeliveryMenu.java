package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.TransportManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sender-only batch delivery menu backed by the sender's first 36 inventory slots. */
public final class DeliveryMenu implements InventoryHolder {
    private static final int SIZE = 54;
    private static final int QUOTE_SLOT = 45;
    private static final int SUMMARY_SLOT = 47;
    private static final int CONFIRM_SLOT = 49;
    private static final int CANCEL_SLOT = 53;

    private final KsEco plugin;
    private final Player sender;
    private final Player target;
    private final Map<Integer, ItemStack> selected = new LinkedHashMap<>();
    private Inventory inventory;

    public DeliveryMenu(KsEco plugin, Player sender, Player target) {
        this.plugin = plugin;
        this.sender = sender;
        this.target = target;
    }

    public void open() {
        build();
        sender.openInventory(inventory);
    }

    private void build() {
        inventory = Bukkit.createInventory(this, SIZE,
                Component.text("物品物流 -> " + target.getName(), NamedTextColor.GOLD));
        for (int slot = 0; slot < 36; slot++) {
            ItemStack source = sender.getInventory().getItem(slot);
            if (source != null && !source.getType().isAir()) {
                inventory.setItem(slot, displayItem(slot, source));
            }
        }

        TransportManager.Quote quote = target.isOnline() ? plugin.transportManager().quote(sender, target) : null;
        String quoteText = quote == null ? "收件人已离线"
                : quote.free() ? "免费范围内"
                : "费用: " + plugin.vaultHook().format(quote.fee());
        inventory.setItem(QUOTE_SLOT, button(Material.BOOK, "物流报价", quoteText));
        inventory.setItem(SUMMARY_SLOT, button(Material.CHEST, "已选择: " + selected.size() + " 组",
                "点击上方物品选择或取消选择"));
        inventory.setItem(CONFIRM_SLOT, button(Material.EMERALD, "确认发送", quoteText));
        inventory.setItem(CANCEL_SLOT, button(Material.BARRIER, "取消"));
        fillEmpty();
    }

    private ItemStack displayItem(int slot, ItemStack source) {
        ItemStack display = source.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            if (selected.containsKey(slot)) {
                lore.add(Component.text("已选择发送", NamedTextColor.GREEN));
            } else {
                lore.add(Component.text("点击选择发送", NamedTextColor.YELLOW));
            }
            meta.lore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    private ItemStack button(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) lore.add(Component.text(line, NamedTextColor.GRAY));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillEmpty() {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            filler.setItemMeta(meta);
        }
        for (int slot = 0; slot < SIZE; slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, filler);
        }
    }

    private void refresh() {
        build();
        sender.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static final class Listener implements org.bukkit.event.Listener {
        private final KsEco plugin;

        public Listener(KsEco plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof DeliveryMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || !player.equals(menu.sender)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= SIZE) return;
            if (slot < 36) {
                ItemStack source = player.getInventory().getItem(slot);
                if (source == null || source.getType().isAir()) {
                    menu.selected.remove(slot);
                } else if (menu.selected.containsKey(slot)) {
                    menu.selected.remove(slot);
                } else {
                    menu.selected.put(slot, source.clone());
                }
                menu.refresh();
                return;
            }
            if (slot == CANCEL_SLOT) {
                player.closeInventory();
                return;
            }
            if (slot == CONFIRM_SLOT) {
                TransportManager.DeliveryResult result = plugin.transportManager()
                        .sendBatch(player, menu.target, menu.selected);
                if (result.success()) player.closeInventory();
                else menu.refresh();
            }
        }
    }
}
