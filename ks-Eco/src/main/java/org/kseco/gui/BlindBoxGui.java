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

import java.util.*;

/**
 * 盲盒 GUI — 池子浏览、单抽/十连、保底查询、抽取记录。
 * 直接使用 BlindBoxManager（核心模块，无反射）。
 */
public final class BlindBoxGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private int view = 0; // 0=pools, 1=pity, 2=history
    private int page = 0;
    private final List<Map<String, Object>> items = new ArrayList<>();
    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 45;

    public BlindBoxGui(KsEco plugin) {
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
        UUID uuid = player.getUniqueId();
        switch (view) {
            case 0 -> {
                // 公共盲盒池
                List<Map<String, Object>> pools = plugin.blindBoxManager().listPools();
                for (var p : pools) {
                    if (Boolean.TRUE.equals(p.get("limitedOnly"))) continue;
                    String ownerType = String.valueOf(p.getOrDefault("owner_type", "PUBLIC"));
                    if (!"PUBLIC".equalsIgnoreCase(ownerType)) continue;
                    items.add(p);
                }
            }
            case 1 -> {
                // 保底信息
                List<Map<String, Object>> pools = plugin.blindBoxManager().listPools();
                for (var p : pools) {
                    if (Boolean.TRUE.equals(p.get("limitedOnly"))) continue;
                    String poolId = String.valueOf(p.get("id"));
                    int pityCount = plugin.blindBoxManager().getPityCount(uuid, poolId);
                    Map<String, Integer> pityCounts = plugin.blindBoxManager().getPityCounts(uuid, poolId);
                    Object pityMaxObj = p.get("pityMax");
                    int pityMax = pityMaxObj instanceof Number n ? n.intValue() : 50;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("pool_id", poolId);
                    row.put("pool_name", p.get("name"));
                    row.put("pity_count", pityCount);
                    row.put("pity_max", pityMax);
                    row.put("pity_counts", pityCounts);
                    row.put("pity_rules_text", p.getOrDefault("pityRulesText", ""));
                    items.add(row);
                }
            }
            case 2 -> {
                // 抽取记录
                List<Map<String, Object>> pulls = plugin.blindBoxManager().recentPulls(uuid, 200);
                items.addAll(pulls);
            }
        }
    }

    private void build() {
        String[] viewNames = {"盲盒池", "保底进度", "抽取记录"};
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8盲盒 — " + viewNames[view] + " 第" + (page + 1) + "页"));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && (start + i) < items.size(); i++) {
            inventory.setItem(i, buildItem(items.get(start + i)));
        }

        if (items.isEmpty()) {
            inventory.setItem(22, emptyHint());
        }

        // View tabs
        for (int v = 0; v < 3; v++) {
            Material tabMat = v == 0 ? Material.SHULKER_BOX : v == 1 ? Material.CLOCK : Material.WRITABLE_BOOK;
            String tabName = v == 0 ? "§d盲盒池" : v == 1 ? "§6保底进度" : "§a抽取记录";
            if (v == view) tabName = "§l" + tabName;
            inventory.setItem(46 + v, navButton(tabMat, tabName,
                    v == view ? "§7（当前）" : "§7点击切换"));
        }

        if (page > 0)
            inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回主菜单", "§7回到经济面板"));
        if ((page + 1) * PAGE_SIZE < items.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));

        fillEmpty();
    }

    private ItemStack buildItem(Map<String, Object> item) {
        return switch (view) {
            case 0 -> buildPoolItem(item);
            case 1 -> buildPityItem(item);
            case 2 -> buildHistoryItem(item);
            default -> new ItemStack(Material.BARRIER);
        };
    }

    private boolean isEnabled(Map<String, Object> p) {
        Object e = p.get("enabled");
        if (e instanceof Boolean b) return b;
        if (e instanceof Number n) return n.intValue() != 0;
        return true; // default enabled
    }

    private ItemStack buildPoolItem(Map<String, Object> p) {
        // NOTE: BlindBoxManager.listPools() returns camelCase keys
        String poolType = String.valueOf(p.getOrDefault("poolType", "ITEM"));
        Material icon = switch (poolType) {
            case "EQUIPMENT" -> Material.DIAMOND_CHESTPLATE;
            case "MATERIAL" -> Material.IRON_INGOT;
            default -> Material.SHULKER_BOX;
        };

        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String name = String.valueOf(p.getOrDefault("name", p.get("id")));
        double price = p.get("price") instanceof Number n ? n.doubleValue() : 100;
        boolean enabled = isEnabled(p);
        Object pityMaxObj = p.get("pityMax");
        int pityMax = pityMaxObj instanceof Number n ? n.intValue() : 50;
        Object lootCount = p.get("lootCount");

        meta.displayName(Component.text((!enabled ? "§7" : "§d") + "🎁 " + name));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("类型: " + poolType + " | 价格: " + plugin.vaultHook().format(price), NamedTextColor.GRAY));
        lore.add(Component.text("保底: " + p.getOrDefault("pityRulesText", pityMax + "抽 Rare+"), NamedTextColor.GRAY));
        if (lootCount instanceof Number n)
            lore.add(Component.text("战利品条目: " + n.intValue(), NamedTextColor.GRAY));
        String desc = String.valueOf(p.getOrDefault("description", ""));
        if (!desc.isEmpty()) lore.add(Component.text(desc, NamedTextColor.DARK_GRAY));
        String allowedInd = String.valueOf(p.getOrDefault("allowedIndustries", ""));
        if (!allowedInd.isEmpty() && !"null".equals(allowedInd))
            lore.add(Component.text("行业限定: " + allowedInd, NamedTextColor.GOLD));
        String reqZones = String.valueOf(p.getOrDefault("requiredLandZoneTypes", ""));
        if (!reqZones.isEmpty() && !"null".equals(reqZones))
            lore.add(Component.text("需拥有地块: " + reqZones, NamedTextColor.GOLD));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键单抽 §7| §6§l右键十连抽", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildPityItem(Map<String, Object> p) {
        // NOTE: Our pity items map has keys: pity_count, pity_max, pool_name
        int count = p.get("pity_count") instanceof Number n ? n.intValue() : 0;
        int max = p.get("pity_max") instanceof Number n ? n.intValue() : 50;
        int remaining = Math.max(0, max - count);

        ItemStack stack = new ItemStack(Material.CLOCK);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(Component.text("§6📊 " + p.get("pool_name")));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("规则: " + p.getOrDefault("pity_rules_text", ""), NamedTextColor.GRAY));
        lore.add(Component.text("已连续未出Rare: " + count + " 抽", NamedTextColor.GRAY));
        lore.add(Component.text("距保底还差: " + remaining + " 抽", NamedTextColor.GOLD));
        lore.add(Component.text("保底上限: " + max + " 抽", NamedTextColor.DARK_GRAY));
        Object countsObj = p.get("pity_counts");
        if (countsObj instanceof Map<?, ?> counts && !counts.isEmpty()) {
            lore.add(Component.empty());
            lore.add(Component.text("分级保底:", NamedTextColor.GRAY));
            for (var e : counts.entrySet()) {
                lore.add(Component.text(String.valueOf(e.getKey()) + ": 已垫 " + e.getValue() + " 抽", NamedTextColor.DARK_GRAY));
            }
        }
        if (remaining <= 5)
            lore.add(Component.text("§c§l即将触发保底！", NamedTextColor.RED));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildHistoryItem(Map<String, Object> h) {
        // NOTE: BlindBoxManager.recentPulls() returns camelCase keys
        String material = String.valueOf(h.getOrDefault("itemMaterial", "STONE"));
        String rarity = String.valueOf(h.getOrDefault("rarity", "COMMON"));
        long time = h.get("pulledAt") instanceof Number n ? n.longValue() : 0;

        Material icon;
        try { icon = Material.valueOf(material); } catch (Exception e) { icon = Material.SHULKER_SHELL; }

        NamedTextColor rarityColor = switch (rarity) {
            case "LEGENDARY" -> NamedTextColor.GOLD;
            case "EPIC" -> NamedTextColor.DARK_PURPLE;
            case "RARE" -> NamedTextColor.BLUE;
            case "UNCOMMON" -> NamedTextColor.GREEN;
            default -> NamedTextColor.GRAY;
        };

        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String displayName = String.valueOf(h.getOrDefault("itemDisplayName", material));
        meta.displayName(Component.text(rarity + ": " + displayName, rarityColor));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("池子: " + h.getOrDefault("poolId", "?"), NamedTextColor.GRAY));
        lore.add(Component.text("稀有度: " + rarity, rarityColor));
        if (time > 0)
            lore.add(Component.text("时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                    .format(new java.util.Date(time * 1000)), NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack emptyHint() {
        String msg = switch (view) {
            case 0 -> "§7暂无能抽的盲盒池";
            case 1 -> "§7暂无保底数据（还没抽过盲盒）";
            case 2 -> "§7暂无抽取记录";
            default -> "§7无数据";
        };
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(msg));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 执行抽卡并反馈结果（异步，避免卡主线程） */
    private void doPull(Player player, String poolId, boolean tenPull) {
        UUID uuid = player.getUniqueId();
        if (tenPull) {
            plugin.blindBoxManager().pullTenAsync(uuid, poolId, results -> {
                if (results.isEmpty()) {
                    player.sendMessage("§c抽取失败，请检查盲盒池是否可用。");
                } else {
                    player.sendMessage("§d=== 十连抽结果 ===");
                    int epicPlus = 0;
                    for (var r : results) {
                        if (!r.success) { player.sendMessage("§c✗ " + r.error); continue; }
                        player.sendMessage(getRarityColor(r.rarity) + "★" + r.rarity + "§r " + r.itemDisplayName + " x" + r.quantity
                                + (r.pityTriggered != 0 ? " §6§l[保底触发!]" : "")
                                + (r.storedToBox ? " §7(背包满→暂存箱)" : ""));
                        if (r.rarity.equals("EPIC") || r.rarity.equals("LEGENDARY")) epicPlus++;
                    }
                    player.sendMessage("§d共 " + results.size() + " 抽，Epic+：§e" + epicPlus);
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
                }
                // 刷新 GUI（已在主线程由 pullTenAsync 回调）
                if (player.isOnline()
                        && player.getOpenInventory().getTopInventory().getHolder() == this) {
                    loadData(player);
                    build();
                    player.openInventory(getInventory());
                }
            });
        } else {
            plugin.blindBoxManager().pullAsync(uuid, poolId, result -> {
                if (!result.success) {
                    player.sendMessage("§c" + result.error);
                } else {
                    player.sendMessage(getRarityColor(result.rarity) + "★" + result.rarity + "§r 抽到: "
                            + result.itemDisplayName + " x" + result.quantity
                            + (result.pityTriggered != 0 ? " §6§l[保底触发!]" : "")
                            + (result.storedToBox ? " §7(背包满→暂存箱)" : ""));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
                }
                if (player.isOnline()
                        && player.getOpenInventory().getTopInventory().getHolder() == this) {
                    loadData(player);
                    build();
                    player.openInventory(getInventory());
                }
            });
        }
    }

    private String getRarityColor(String rarity) {
        return switch (rarity) {
            case "LEGENDARY" -> "§6";
            case "EPIC" -> "§5";
            case "RARE" -> "§9";
            case "UNCOMMON" -> "§a";
            default -> "§7";
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
            if (!(event.getView().getTopInventory().getHolder() instanceof BlindBoxGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;

            // Content slot
            if (slot >= 0 && slot < PAGE_SIZE) {
                int index = gui.page * PAGE_SIZE + slot;
                if (index < gui.items.size()) {
                    Map<String, Object> item = gui.items.get(index);
                    if (gui.view == 0) {
                        // Pool view: left=1x, right=10x
                        String poolId = String.valueOf(item.get("id"));
                        if (event.isRightClick()) {
                            gui.doPull(player, poolId, true);
                        } else {
                            gui.doPull(player, poolId, false);
                        }
                    }
                    // Pity/history views: no click action
                }
                return;
            }

            // Tab buttons
            if (slot >= 46 && slot <= 48) {
                int v = slot - 46;
                if (v != gui.view) { gui.view = v; gui.page = 0; gui.loadData(player); gui.build(); player.openInventory(gui.getInventory()); }
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
