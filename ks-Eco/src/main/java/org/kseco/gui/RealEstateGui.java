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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * 房地产 GUI — 我的地块 + 商品房市场（纯文字，无地图）。
 */
public final class RealEstateGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private int view = 0; // 0=plots, 1=houses
    private int page = 0;
    private final List<Map<String, Object>> items = new ArrayList<>();
    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 45;

    public RealEstateGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        this.page = 0;
        this.view = 0;
        loadData(player);
        build();
        player.openInventory(inventory);
    }

    private void loadData(Player player) {
        items.clear();
        String uuid = player.getUniqueId().toString().replace("'", "''");
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             Statement st = conn.createStatement()) {
            if (view == 0) {
                // My plots: owned by player or player's enterprises
                ResultSet rs = st.executeQuery(
                        "SELECT p.*, z.name AS zone_name FROM ks_re_plots p LEFT JOIN ks_re_zones z ON p.zone_id = z.id WHERE p.owner_id='"
                                + uuid + "' OR p.owner_id IN (SELECT enterprise_id FROM ks_ent_members WHERE player_uuid='"
                                + uuid + "') ORDER BY p.purchased_at DESC LIMIT 200");
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("world", rs.getString("world"));
                    row.put("x1", rs.getInt("x1"));
                    row.put("z1", rs.getInt("z1"));
                    row.put("x2", rs.getInt("x2"));
                    row.put("z2", rs.getInt("z2"));
                    row.put("owner_type", rs.getString("owner_type"));
                    row.put("owner_id", rs.getString("owner_id"));
                    row.put("price", rs.getDouble("price"));
                    row.put("zone_name", rs.getString("zone_name"));
                    row.put("zone_id", rs.getString("zone_id"));
                    row.put("property_function", rs.getString("property_function"));
                    items.add(row);
                }
            } else {
                // Houses for sale: via listingManager
                var listings = plugin.listingManager().getActiveListings("SELL", null);
                for (var listing : listings) {
                    if (!listing.isProperty()) continue;
                    Map<String, Object> house = plugin.marketManager().getHouseInfo(listing.assetRef());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("listing_id", listing.id());
                    row.put("house_id", listing.assetRef());
                    row.put("seller", listing.sellerName());
                    row.put("price", listing.unitPrice());
                    if (house != null) {
                        row.put("name", house.getOrDefault("name", listing.assetRef()));
                        row.put("world", house.get("world"));
                        row.put("x1", house.get("x1")); row.put("y1", house.get("y1"));
                        row.put("z1", house.get("z1"));
                        row.put("x2", house.get("x2")); row.put("y2", house.get("y2"));
                        row.put("z2", house.get("z2"));
                    } else {
                        row.put("name", listing.assetRef());
                    }
                    items.add(row);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("RealEstateGui 加载失败: " + e.getMessage());
        }
    }

    private void build() {
        String title = view == 0 ? "§8房地产 — 我的地块" : "§8房地产 — 商品房市场";
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text(title + " 第" + (page + 1) + "页"));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && (start + i) < items.size(); i++) {
            inventory.setItem(i, buildItem(items.get(start + i)));
        }

        if (items.isEmpty()) {
            inventory.setItem(22, emptyHint());
        }

        // Toggle button
        inventory.setItem(47, navButton(Material.OAK_DOOR,
                view == 0 ? "§6🏠 商品房市场" : "§a🏠 我的地块",
                view == 0 ? "§7点击查看在售商品房" : "§7点击查看我的地块"));
        // /land command hint
        inventory.setItem(48, navButton(Material.GRASS_BLOCK, "§a🌍 /land 领地管理",
                "§7游戏内输入 /land 打开领地信任管理"));
        // Navigation
        if (page > 0)
            inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回主菜单", "§7回到经济面板"));
        if ((page + 1) * PAGE_SIZE < items.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));

        fillEmpty();
    }

    private ItemStack buildItem(Map<String, Object> item) {
        if (view == 0) return buildPlotItem(item);
        else return buildHouseItem(item);
    }

    private ItemStack buildPlotItem(Map<String, Object> p) {
        ItemStack stack = new ItemStack(Material.GRASS_BLOCK);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String id = String.valueOf(p.get("id"));
        String world = String.valueOf(p.get("world"));
        Object fn = p.get("property_function");
        String func = fn != null ? String.valueOf(fn) : "RESIDENTIAL";
        double price = ((Number) p.get("price")).doubleValue();

        meta.displayName(Component.text("§a🏠 地块 " + id.substring(0, Math.min(8, id.length()))));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("世界: " + world, NamedTextColor.GRAY));
        lore.add(Component.text("范围: [" + p.get("x1") + "," + p.get("z1") + "] → [" + p.get("x2") + "," + p.get("z2") + "]", NamedTextColor.GRAY));
        lore.add(Component.text("功能: " + func, NamedTextColor.GRAY));
        if (p.get("zone_name") != null)
            lore.add(Component.text("区域: " + p.get("zone_name"), NamedTextColor.GRAY));
        lore.add(Component.text("购入价: " + plugin.vaultHook().format(price), NamedTextColor.GOLD));
        lore.add(Component.text("§e点击查看详情 §7| §e/land 管理领地", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildHouseItem(Map<String, Object> h) {
        ItemStack stack = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String name = String.valueOf(h.getOrDefault("name", "房屋"));
        double price = ((Number) h.get("price")).doubleValue();

        meta.displayName(Component.text("§e🏠 " + name));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("卖家: " + h.get("seller"), NamedTextColor.GRAY));
        if (h.get("world") != null) {
            lore.add(Component.text("世界: " + h.get("world"), NamedTextColor.GRAY));
            lore.add(Component.text("范围: [" + h.get("x1") + "," + h.get("y1") + "," + h.get("z1") + "] → ["
                    + h.get("x2") + "," + h.get("y2") + "," + h.get("z2") + "]", NamedTextColor.GRAY));
        }
        lore.add(Component.text("价格: " + plugin.vaultHook().format(price), NamedTextColor.GOLD));
        lore.add(Component.text("房屋ID: " + h.get("house_id"), NamedTextColor.DARK_GRAY));
        lore.add(Component.text("§e§l点击购买 §7（在游戏内 /market 亦可购买）", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack emptyHint() {
        String msg = view == 0 ? "§7暂无地块" : "§7暂无可售商品房";
        String sub = view == 0 ? "§7用网页版地图浏览并购买地块" : "§7上架房屋用 /house sell <房屋ID> <价格>";
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(msg));
            meta.lore(List.of(Component.text(sub, NamedTextColor.GRAY)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack navButton(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (lore.length > 0) {
                List<Component> loreList = new ArrayList<>();
                for (String s : lore) loreList.add(Component.text(s, NamedTextColor.GRAY));
                meta.lore(loreList);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillEmpty() {
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) { gm.displayName(Component.text(" ")); glass.setItemMeta(gm); }
        for (int i = 0; i < ROWS * 9; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
        }
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }

    // ---- Listener ----

    public static class Listener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getInventory().getHolder() instanceof RealEstateGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

            // Content slots
            if (slot >= 0 && slot < PAGE_SIZE) {
                int index = gui.page * PAGE_SIZE + slot;
                if (index < gui.items.size()) {
                    Map<String, Object> item = gui.items.get(index);
                    if (gui.view == 1) {
                        // Buy house
                        String listingId = String.valueOf(item.get("listing_id"));
                        player.closeInventory();
                        plugin.marketManager().buyListing(player, listingId, 1);
                    } else {
                        // Plot: show details
                        player.sendMessage("§e地块详情: " + item.get("id") + " §7— 输入 §e/land §7进行领地管理");
                    }
                }
                return;
            }

            switch (slot) {
                case 45 -> { if (gui.page > 0) { gui.page--; gui.build(); player.openInventory(gui.getInventory()); } }
                case 47 -> { gui.view = gui.view == 0 ? 1 : 0; gui.page = 0; gui.loadData(player); gui.build(); player.openInventory(gui.getInventory()); }
                case 48 -> {
                    player.closeInventory();
                    Bukkit.dispatchCommand(player, "land");
                }
                case 49 -> { player.closeInventory(); new EcoGuiMainMenu(plugin).open(player); }
                case 53 -> {
                    if ((gui.page + 1) * PAGE_SIZE < gui.items.size()) {
                        gui.page++; gui.build(); player.openInventory(gui.getInventory());
                    }
                }
            }
        }
    }
}
