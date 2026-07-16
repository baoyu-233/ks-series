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
 * 税收记录 GUI — 纯只读浏览纳税历史。
 */
public final class TaxGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private final List<Map<String, Object>> records = new ArrayList<>();
    private int page = 0;
    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 45;

    public TaxGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        this.page = 0;
        records.clear();
        loadRecords(player.getUniqueId().toString());
        build();
        player.openInventory(inventory);
    }

    private void loadRecords(String uuid) {
        String safeUuid = uuid.replace("'", "''");
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM ks_tax_records WHERE payer_uuid='" + safeUuid
                             + "' ORDER BY collected_at DESC LIMIT 200")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("category", rs.getString("category"));
                row.put("base_amount", rs.getDouble("base_amount"));
                row.put("tax_rate", rs.getDouble("tax_rate"));
                row.put("tax_amount", rs.getDouble("tax_amount"));
                row.put("description", rs.getString("description"));
                row.put("collected_at", rs.getLong("collected_at"));
                records.add(row);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("TaxGui 加载失败: " + e.getMessage());
        }
    }

    private void build() {
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8税收记录 — 第" + (page + 1) + "页"));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && (start + i) < records.size(); i++) {
            inventory.setItem(i, buildRecordItem(records.get(start + i)));
        }

        // Navigation
        if (page > 0)
            inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回主菜单", "§7回到经济面板"));
        if ((page + 1) * PAGE_SIZE < records.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));

        fillEmpty();
    }

    private ItemStack buildRecordItem(Map<String, Object> r) {
        String cat = String.valueOf(r.get("category"));
        Material icon = switch (cat) {
            case "MARKET_TRADE" -> Material.GOLD_NUGGET;
            case "PROPERTY_TRADE" -> Material.OAK_DOOR;
            case "OFFICIAL_TRADE" -> Material.HOPPER;
            case "ENTERPRISE_TAX", "DIVIDEND_TAX" -> Material.IRON_INGOT;
            case "BANK_INTEREST" -> Material.GOLD_INGOT;
            case "PLAYER_TRANSFER" -> Material.PAPER;
            default -> Material.PAPER;
        };
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        double base = ((Number) r.get("base_amount")).doubleValue();
        double rate = ((Number) r.get("tax_rate")).doubleValue();
        double amount = ((Number) r.get("tax_amount")).doubleValue();
        long time = ((Number) r.get("collected_at")).longValue();
        String desc = r.get("description") != null ? String.valueOf(r.get("description")) : "";

        meta.displayName(Component.text("§e" + categoryLabel(cat)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("应税基数: " + plugin.vaultHook().format(base), NamedTextColor.GRAY));
        lore.add(Component.text("税率: " + String.format("%.1f%%", rate * 100), NamedTextColor.GRAY));
        lore.add(Component.text("实缴税额: §c" + plugin.vaultHook().format(amount), NamedTextColor.GRAY));
        if (!desc.isEmpty()) lore.add(Component.text("说明: " + desc, NamedTextColor.DARK_GRAY));
        lore.add(Component.text("时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                .format(new java.util.Date(time * 1000)), NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "MARKET_TRADE" -> "玩家市场交易税";
            case "PROPERTY_TRADE" -> "房产交易税";
            case "OFFICIAL_TRADE" -> "官方交易税";
            case "ENTERPRISE_TAX" -> "企业税";
            case "DIVIDEND_TAX" -> "分红税";
            case "BANK_INTEREST" -> "银行利息税";
            case "PLAYER_TRANSFER" -> "玩家转账税";
            default -> "其他税收";
        };
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
            if (!(event.getView().getTopInventory().getHolder() instanceof TaxGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;
            switch (slot) {
                case 45 -> { if (gui.page > 0) { gui.page--; gui.build(); player.openInventory(gui.getInventory()); } }
                case 49 -> { player.closeInventory(); new EcoGuiMainMenu(plugin).open(player); }
                case 53 -> {
                    if ((gui.page + 1) * PAGE_SIZE < gui.records.size()) {
                        gui.page++; gui.build(); player.openInventory(gui.getInventory());
                    }
                }
            }
        }
    }
}
