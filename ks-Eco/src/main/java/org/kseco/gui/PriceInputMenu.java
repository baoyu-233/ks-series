package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家自定义价格上架 GUI。
 * 手持物品点击「上架」→ 选预设价格或自定义 → 创建挂单。
 */
public final class PriceInputMenu implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private final ItemStack handItem;

    // 跟踪正在等待聊天输入价格的玩家
    static final Map<UUID, ItemStack> pendingPriceInput = new ConcurrentHashMap<>();

    private static final double[] PRESETS = {1, 5, 10, 50, 100, 500, 1000, 5000, 10000, 50000, -1}; // -1 = custom
    private static final int AUTO_PRICE_SLOT = 31;

    public PriceInputMenu(KsEco plugin, ItemStack handItem) {
        this.plugin = plugin;
        this.handItem = handItem.clone();
    }

    public void open(Player player, ItemStack handItem) {
        var menu = new PriceInputMenu(plugin, handItem);
        menu.build();
        player.openInventory(menu.inventory);
    }

    private void build() {
        inventory = Bukkit.createInventory(this, 36, Component.text("§8设定单价 — " + getItemName(handItem)));

        // Slot 4: The item being listed
        ItemStack display = handItem.clone();
        display.setAmount(1);
        ItemMeta dm = display.getItemMeta();
        if (dm != null) {
            List<Component> lore = dm.hasLore() ? new ArrayList<>(dm.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text("数量: " + handItem.getAmount(), NamedTextColor.GRAY));
            lore.add(Component.text("选择预设价格 或 自定义输入", NamedTextColor.GREEN));
            dm.lore(lore);
            display.setItemMeta(dm);
        }
        inventory.setItem(4, display);

        // Row 2 (slots 9-17): first 9 preset price buttons
        // Row 3 (slots 18+): remaining presets
        for (int i = 0; i < PRESETS.length; i++) {
            double price = PRESETS[i];
            if (price < 0) {
                inventory.setItem(9 + i, priceButton(Material.OAK_SIGN, "§b✎ 自定义",
                        "§7点击后在聊天栏输入价格",
                        "§7例如输入: 250"));
            } else {
                String label = plugin.vaultHook().format(price);
                inventory.setItem(9 + i, priceButton(Material.GOLD_NUGGET, "§6" + label,
                        "§7点击以 " + label + " 单价上架"));
            }
        }

        double autoPrice = autoPrice();
        inventory.setItem(AUTO_PRICE_SLOT, priceButton(Material.COMPASS, "§b§l一键定价: " + plugin.vaultHook().format(autoPrice),
                "§7按当前材料成本自动定价",
                "§7官方材料使用实时官方收购价",
                "§7点击后以此单价直接上架"));
        inventory.setItem(27, navButton(Material.BARRIER, "§c取消", "§7返回市场"));

        fillEmpty();
    }

    private ItemStack priceButton(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (lore.length > 0) {
                List<Component> list = new ArrayList<>();
                for (String s : lore) list.add(Component.text(s, NamedTextColor.GRAY));
                meta.lore(list);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack navButton(Material mat, String name, String... lore) {
        return priceButton(mat, name, lore);
    }

    private void fillEmpty() {
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) { gm.displayName(Component.text(" ")); glass.setItemMeta(gm); }
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
        }
    }

    private String getItemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return item.getItemMeta().getDisplayName();
        return item.getType().name();
    }

    private double autoPrice() {
        double floor = plugin.marketValueService().marketFloorUnitValue(handItem);
        return Math.ceil(Math.max(0.01, floor) * 100.0) / 100.0;
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }

    // ---- Inventory Listener ----

    public static class Listener implements org.bukkit.event.Listener {

        private final KsEco plugin;
        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof PriceInputMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= menu.getInventory().getSize()) return;
            if (slot == 27) { // Cancel
                player.closeInventory();
                new MarketMenu(plugin).open(player);
                return;
            }
            if (slot == AUTO_PRICE_SLOT) {
                player.closeInventory();
                plugin.marketManager().listItemForSale(player, menu.handItem.clone(),
                        menu.handItem.getAmount(), menu.autoPrice());
                return;
            }

            // Price buttons (slot 9-17)
            int priceIdx = slot - 9;
            if (priceIdx >= 0 && priceIdx < PRESETS.length) {
                double price = PRESETS[priceIdx];
                player.closeInventory();
                if (price < 0) {
                    // Custom: prompt chat input
                    pendingPriceInput.put(player.getUniqueId(), menu.handItem.clone());
                    player.sendMessage("§a请在聊天栏输入单价（纯数字，如 250），或输入 cancel 取消");
                } else {
                    // Direct listing
                    plugin.marketManager().listItemForSale(player, menu.handItem.clone(),
                            menu.handItem.getAmount(), price);
                }
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            // No cleanup
        }
    }

    // ---- Chat Listener (for custom price input) ----

    public static class ChatListener implements org.bukkit.event.Listener {

        private final KsEco plugin;
        public ChatListener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            pendingPriceInput.remove(event.getPlayer().getUniqueId());
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            if (!pendingPriceInput.containsKey(playerId)) return; // Not in price input mode

            event.setCancelled(true); // Don't broadcast the price to chat
            String msg = event.getMessage().trim();
            plugin.scheduler().runPlayer(playerId, () -> {
                ItemStack handItem = pendingPriceInput.remove(playerId);
                Player player = Bukkit.getPlayer(playerId);
                if (handItem == null || player == null) return;
                if (msg.equalsIgnoreCase("cancel")) {
                    player.sendMessage("§c已取消上架。");
                    new MarketMenu(plugin).open(player);
                    return;
                }

                double price;
                try {
                    price = Double.parseDouble(msg);
                } catch (NumberFormatException e) {
                    String cleaned = msg.replaceAll("[^0-9.]", "");
                    if (cleaned.isEmpty()) {
                        player.sendMessage("§c无效价格: " + msg + "，请输入纯数字。");
                        pendingPriceInput.put(playerId, handItem);
                        return;
                    }
                    try {
                        price = Double.parseDouble(cleaned);
                    } catch (NumberFormatException ignored) {
                        player.sendMessage("§c无效价格: " + msg + "，请输入纯数字。");
                        pendingPriceInput.put(playerId, handItem);
                        return;
                    }
                }

                if (!Double.isFinite(price) || price <= 0 || price > 1_000_000_000_000d) {
                    player.sendMessage("§c价格必须大于0。");
                    pendingPriceInput.put(playerId, handItem);
                    return;
                }

                plugin.marketManager().listItemForSale(player, handItem, handItem.getAmount(), price);
            });
        }
    }
}
