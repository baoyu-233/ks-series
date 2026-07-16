package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.BlindBoxManager;
import org.kseco.KsEco;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * 企业盲盒 GUI — 企业专属盲盒池与企业账户抽取。
 */
public final class EntBlindBoxGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private int page = 0;
    private String selectedEnterpriseId = null;
    private final List<Map<String, Object>> items = new ArrayList<>();
    private final List<Map<String, Object>> myEnterprises = new ArrayList<>();
    private boolean enterpriseSelected = false; // 是否已选择企业
    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 36; // smaller to leave room for enterprise selector

    public EntBlindBoxGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        this.page = 0;
        this.enterpriseSelected = false;
        this.selectedEnterpriseId = null;
        loadEnterprises(player);
        if (myEnterprises.size() == 1) {
            // Auto-select the only enterprise
            selectEnterprise(0, player);
        }
        build();
        player.openInventory(inventory);
    }

    private void loadEnterprises(Player player) {
        myEnterprises.clear();
        String uuid = player.getUniqueId().toString().replace("'", "''");
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM ks_ent_enterprises WHERE owner_uuids LIKE '%" + uuid
                             + "%' OR id IN (SELECT enterprise_id FROM ks_ent_members WHERE player_uuid='"
                             + uuid + "') ORDER BY name")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("name", rs.getString("name"));
                row.put("type", rs.getString("type"));
                myEnterprises.add(row);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("EntBlindBoxGui 加载企业失败: " + e.getMessage());
        }
    }

    private void selectEnterprise(int index, Player player) {
        if (index < 0 || index >= myEnterprises.size()) return;
        Map<String, Object> ent = myEnterprises.get(index);
        this.selectedEnterpriseId = String.valueOf(ent.get("id"));
        this.enterpriseSelected = true;
        this.page = 0;
        loadData(player);
    }

    private void loadData(Player player) {
        items.clear();
        if (selectedEnterpriseId == null) return;
        List<Map<String, Object>> pools = plugin.blindBoxManager().listPools();
        String industry = plugin.blindBoxManager().getIndustry(selectedEnterpriseId);
        for (var p : pools) {
            if (Boolean.TRUE.equals(p.get("limitedOnly"))) continue;
            String ownerType = String.valueOf(p.getOrDefault("ownerType", "PUBLIC"));
            if (!"PUBLIC".equalsIgnoreCase(ownerType) && !"ENTERPRISE".equalsIgnoreCase(ownerType)) continue;
            String allowedIndustries = String.valueOf(p.getOrDefault("allowedIndustries", ""));
            if (allowedIndustries.isEmpty() || industry.isEmpty()
                    || !BlindBoxManager.isIndustryBlocked(industry, allowedIndustries)) {
                items.add(p);
            }
        }
    }

    private void build() {
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8企业盲盒 — 卡池 第" + (page + 1) + "页"));

        // Enterprise selector row (first row)
        for (int i = 0; i < Math.min(myEnterprises.size(), 8); i++) {
            Map<String, Object> ent = myEnterprises.get(i);
            boolean isSelected = selectedEnterpriseId != null
                    && selectedEnterpriseId.equals(String.valueOf(ent.get("id")));
            Material mat = isSelected ? Material.IRON_BLOCK : Material.IRON_INGOT;
            String prefix = isSelected ? "§b§l" : "§7";
            inventory.setItem(i, navButton(mat, prefix + ent.get("name"),
                    isSelected ? "§7（已选择）" : "§7点击选择此企业"));
        }

        if (!enterpriseSelected) {
            inventory.setItem(22, entSelectHint());
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回主菜单", "§7回到经济面板"));
            fillEmpty();
            return;
        }

        // Content area (rows 1-4, slots 9-44)
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && (start + i) < items.size(); i++) {
            inventory.setItem(9 + i, buildItem(items.get(start + i)));
        }

        if (items.isEmpty()) {
            inventory.setItem(22, emptyHint());
        }

        inventory.setItem(46, navButton(Material.PURPLE_SHULKER_BOX,
                "§5§l企业卡池", "§7使用企业公户直接结算"));

        if (page > 0)
            inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回主菜单", "§7回到经济面板"));
        if ((page + 1) * PAGE_SIZE < items.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));

        fillEmpty();
    }

    private ItemStack buildItem(Map<String, Object> item) {
        return buildPoolItem(item);
    }

    private boolean isEnabled(Map<String, Object> p) {
        Object e = p.get("enabled");
        if (e instanceof Boolean b) return b;
        if (e instanceof Number n) return n.intValue() != 0;
        return true;
    }

    private ItemStack buildPoolItem(Map<String, Object> p) {
        // NOTE: BlindBoxManager.listPools() returns camelCase keys
        int enterpriseLevel = plugin.enterpriseLevelManager().getLevel(selectedEnterpriseId);
        int requiredLevel = p.get("minEnterpriseLevel") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        boolean levelLocked = enterpriseLevel < requiredLevel;
        Material icon = levelLocked ? Material.BARRIER : Material.PURPLE_SHULKER_BOX;
        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String name = String.valueOf(p.getOrDefault("name", p.get("id")));
        double price = p.get("price") instanceof Number n ? n.doubleValue() : 100;
        boolean enabled = isEnabled(p);

        meta.displayName(Component.text((!enabled ? "§7" : "§d") + "🎁 " + name));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("类型: " + p.getOrDefault("poolType", "ITEM"), NamedTextColor.GRAY));
        lore.add(Component.text("价格: " + plugin.vaultHook().format(price) + "（从企业公户扣）", NamedTextColor.GRAY));
        lore.add(Component.text("保底: " + p.getOrDefault("pityRulesText", p.getOrDefault("pityMax", "")), NamedTextColor.GRAY));
        lore.add(Component.text("企业等级: " + enterpriseLevel + " / 要求 " + requiredLevel,
                levelLocked ? NamedTextColor.RED : NamedTextColor.GREEN));
        String desc = String.valueOf(p.getOrDefault("description", ""));
        if (!desc.isEmpty()) lore.add(Component.text(desc, NamedTextColor.DARK_GRAY));
        String allowedInd = String.valueOf(p.getOrDefault("allowedIndustries", ""));
        if (!allowedInd.isEmpty() && !"null".equals(allowedInd))
            lore.add(Component.text("行业限定: " + allowedInd, NamedTextColor.GOLD));
        String reqZones = String.valueOf(p.getOrDefault("requiredLandZoneTypes", ""));
        if (!reqZones.isEmpty() && !"null".equals(reqZones))
            lore.add(Component.text("需企业拥有地块: " + reqZones, NamedTextColor.GOLD));
        lore.add(Component.empty());
        lore.add(Component.text(levelLocked ? "§c§l等级不足，暂不可抽取" : "§a§l左键抽取", NamedTextColor.YELLOW));
        lore.add(Component.text("§7企业公户余额: §f" + plugin.vaultHook()
                .format(plugin.blindBoxManager().getCorporateBalance(selectedEnterpriseId)), NamedTextColor.GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack entSelectHint() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§e请先选择企业"));
            meta.lore(myEnterprises.isEmpty()
                    ? List.of(Component.text("§7你还没有加入任何企业", NamedTextColor.GRAY))
                    : List.of(Component.text("§7点击上方铁锭选择要操作的企业", NamedTextColor.GRAY)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack emptyHint() {
        String msg = "§7暂无企业可用的盲盒池";
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text(msg)); item.setItemMeta(meta); }
        return item;
    }

    private void doEnterprisePull(Player player, String poolId) {
        if (selectedEnterpriseId == null) return;
        var result = plugin.blindBoxManager().pullForEnterprise(selectedEnterpriseId, player.getUniqueId(), poolId);
        if (!result.success) {
            player.sendMessage("§c" + result.error);
            return;
        }
        String rarityColor = switch (result.rarity) {
            case "LEGENDARY" -> "§6"; case "EPIC" -> "§5";
            case "RARE" -> "§9"; case "UNCOMMON" -> "§a";
            default -> "§7";
        };
        player.sendMessage(rarityColor + "★" + result.rarity + "§r 抽到: " + result.itemDisplayName + " x" + result.quantity
                + (result.storedToBox ? " §7(背包满→暂存箱)" : ""));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
        loadData(player);
        build();
        // 注意：openInventory 由 Listener 在 doEnterprisePull 调用后负责
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
            if (!(event.getView().getTopInventory().getHolder() instanceof EntBlindBoxGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;

            // Enterprise selector (slots 0-7)
            if (slot >= 0 && slot < Math.min(gui.myEnterprises.size(), 8)) {
                gui.selectEnterprise(slot, player);
                gui.build();
                player.openInventory(gui.getInventory());
                return;
            }

            // Content slots
            if (slot >= 9 && slot < 9 + PAGE_SIZE) {
                int index = gui.page * PAGE_SIZE + (slot - 9);
                if (index < gui.items.size()) {
                    Map<String, Object> item = gui.items.get(index);
                    gui.doEnterprisePull(player, String.valueOf(item.get("id")));
                    player.openInventory(gui.getInventory());
                }
                return;
            }

            switch (slot) {
                case 45 -> { if (gui.page > 0) { gui.page--; gui.build(); player.openInventory(gui.getInventory()); } }
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
