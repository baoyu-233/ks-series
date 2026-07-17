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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.ExchangeManager.ExchangeRule;
import org.kseco.ExchangeManager.RuleItem;
import org.kseco.KsEco;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品兑换 GUI — 双模式。
 *
 * Admin 模式 (/exchangeadmin):
 *   多物品输入/输出（各最多4槽），Q-丢入设定物品，命名规则，保存/编辑/删除。
 *
 * Player 模式 (/exchange):
 *   显示所有启用规则，点击执行多物品兑换。
 */
public final class ExchangeGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private boolean adminMode;
    private int page;
    private String ruleName;
    private final List<RuleItem> adminInputs = new ArrayList<>();   // 最多4
    private final List<RuleItem> adminOutputs = new ArrayList<>();  // 最多4
    private String editingRuleId;
    private List<ExchangeRule> rules = List.of();
    private boolean editingEnabled = true;
    private Player viewer; // 玩家模式下当前查看者，用于计算可兑换次数

    // 槽位常量
    private static final int INPUT_START = 1;   // 输入物品槽 1-4
    private static final int INPUT_QTY_START = 9; // 输入数量控 9-12
    private static final int OUTPUT_START = 5;   // 输出物品槽 5-8
    private static final int OUTPUT_QTY_START = 14; // 输出数量控 14-17

    private static final int ROWS = 6;
    private static final int RULES_PER_PAGE = 8;

    // ---- 静态 pending 状态（Q-丢入 + 聊天输入） ----

    record PendingSet(ExchangeGui gui, boolean isInput, int slotIndex) {}
    static final Map<UUID, PendingSet> pendingSet = new ConcurrentHashMap<>();
    static final Map<UUID, ExchangeGui> pendingName = new ConcurrentHashMap<>();

    /** 玩家模式：等待在聊天栏输入批量兑换次数 */
    record PendingBatch(ExchangeGui gui, String ruleId) {}
    static final Map<UUID, PendingBatch> pendingBatchQty = new ConcurrentHashMap<>();

    // ---- 构造 ----

    public ExchangeGui(KsEco plugin) {
        this.plugin = plugin;
    }

    // ---- Open ----

    public void openPlayerView(Player player) {
        this.adminMode = false;
        this.page = 0;
        this.viewer = player;
        this.rules = plugin.exchangeManager().listEnabledRules();
        build();
        player.openInventory(inventory);
    }

    public void openAdminView(Player player) {
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage("§c无权限。");
            return;
        }
        this.adminMode = true;
        this.page = 0;
        this.rules = plugin.exchangeManager().listAllRules();
        build();
        player.openInventory(inventory);
    }

    /** 从 pending 状态重新打开（Q-丢入 / 改名后 / 批量兑换数量输入后） */
    void reopen(Player player) {
        Runnable reopenTask = () -> {
            if (adminMode && !player.hasPermission("kseco.admin")) {
                player.sendMessage("§c权限已失效。");
                return;
            }
            if (!adminMode) {
                this.viewer = player;
                this.rules = plugin.exchangeManager().listEnabledRules();
            }
            build();
            if (player.isOnline()) player.openInventory(inventory);
        };
        if (Bukkit.isPrimaryThread()) reopenTask.run();
        else Bukkit.getScheduler().runTask(plugin, reopenTask);
    }

    // ---- Build ----

    private void build() {
        String title = adminMode ? "§8兑换规则管理" : "§8物品兑换";
        inventory = Bukkit.createInventory(this, ROWS * 9, Component.text(title));
        if (adminMode) buildAdminMode(); else buildPlayerMode();
        fillEmpty();
    }

    private void buildAdminMode() {
        // === 输入物品槽 (slot 1-4) ===
        inventory.setItem(0, labelItem(Material.CYAN_STAINED_GLASS_PANE, "§b兑换物品设置",
                "§c左侧 1-4：玩家支付", "§a右侧 5-8：玩家获得"));
        for (int i = 0; i < 4; i++) {
            if (i < adminInputs.size()) {
                inventory.setItem(INPUT_START + i, buildRuleItemIcon(adminInputs.get(i)));
            } else {
                inventory.setItem(INPUT_START + i, hintItem(Material.HOPPER, "§7[空] 点击设定输入物品",
                        "§7点击后在主界面按 Q 丢出物品"));
            }
        }

        // === 输出物品槽 (slot 5-8) ===
        for (int i = 0; i < 4; i++) {
            if (i < adminOutputs.size()) {
                inventory.setItem(OUTPUT_START + i, buildRuleItemIcon(adminOutputs.get(i)));
            } else {
                inventory.setItem(OUTPUT_START + i, hintItem(Material.HOPPER, "§7[空] 点击设定输出物品",
                        "§7点击后在主界面按 Q 丢出物品"));
            }
        }

        // === 箭头 ===
        inventory.setItem(22, labelItem(Material.ARROW, "§b→ 兑换 →"));

        // === 数量控件：左键 +1，右键 -1，Shift 调整 10 ===
        for (int i = 0; i < 4; i++) {
            if (i < adminInputs.size()) {
                RuleItem item = adminInputs.get(i);
                inventory.setItem(INPUT_QTY_START + i, qtyControl(item.quantity(), "§c输入#" + (i + 1)));
            }
        }
        inventory.setItem(13, labelItem(Material.ARROW, "§b数量调整"));
        for (int i = 0; i < 4; i++) {
            if (i < adminOutputs.size()) {
                RuleItem item = adminOutputs.get(i);
                inventory.setItem(OUTPUT_QTY_START + i, qtyControl(item.quantity(), "§a输出#" + (i + 1)));
            }
        }

        // === 名称按钮 ===
        String displayName = (ruleName != null && !ruleName.isEmpty()) ? ruleName : "§7(未命名，点击设置)";
        inventory.setItem(27, labelItem(Material.NAME_TAG, "§d📛 规则名称: " + displayName,
                "§7点击后在聊天栏输入名称"));

        // === 启用/禁用 ===
        inventory.setItem(28, editingEnabled
                ? labelItem(Material.LIME_DYE, "§a✔ 启用", "§7点击切换")
                : labelItem(Material.GRAY_DYE, "§c✖ 禁用", "§7点击切换"));

        // === 保存 / 清空 ===
        boolean canSave = !adminInputs.isEmpty() && !adminOutputs.isEmpty();
        inventory.setItem(31, navButton(canSave ? Material.EMERALD : Material.REDSTONE,
                canSave ? "§a✔ 保存规则" : "§c请放入输入和输出物品",
                editingRuleId != null ? "§7更新规则" : "§7创建新规则"));

        inventory.setItem(32, navButton(Material.BARRIER, "§c🗑 清空", "§7清除当前编辑"));

        // === 规则列表 (slot 36-43) ===
        int start = page * RULES_PER_PAGE;
        for (int i = 0; i < RULES_PER_PAGE && (start + i) < rules.size(); i++) {
            inventory.setItem(36 + i, buildRuleListIcon(rules.get(start + i)));
        }

        // 分页
        if (page > 0) inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§7返回"));
        if ((page + 1) * RULES_PER_PAGE < rules.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));

        // 删除当前编辑的规则
        if (editingRuleId != null) {
            inventory.setItem(35, navButton(Material.LAVA_BUCKET, "§c❌ 删除此规则", "§7Shift+点击确认删除"));
        }
    }

    private void buildPlayerMode() {
        int start = page * 36; // 4 rows
        for (int i = 0; i < 36 && (start + i) < rules.size(); i++) {
            inventory.setItem(i, buildPlayerRuleIcon(rules.get(start + i)));
        }

        if (page > 0) inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§7返回市场"));
        if ((page + 1) * 36 < rules.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));
    }

    // ---- Icon builders ----

    private ItemStack buildRuleItemIcon(RuleItem item) {
        ItemStack is = item.toItemStack();
        is.setAmount(Math.min(item.quantity(), 64));
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.text("§7数量: " + item.quantity()));
            lore.add(Component.text("§e点击=重新设定 §cShift+点击=删除此槽位"));
            meta.lore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    private ItemStack buildRuleListIcon(ExchangeRule rule) {
        ItemStack item = rule.iconItem();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String status = rule.enabled() ? "§a● 启用" : "§c● 禁用";
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("§d" + rule.displayName()));
        lore.add(Component.text("§7入: " + rule.inputSummary()));
        lore.add(Component.text("§7出: " + rule.outputSummary()));
        lore.add(Component.text("§7创建者: " + (rule.createdBy() != null ? rule.createdBy() : "?")));
        lore.add(Component.text("§7状态: " + status));
        lore.add(Component.text("§e左键=编辑 §6右键=切换 §cQ/丢=删除"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPlayerRuleIcon(ExchangeRule rule) {
        ItemStack item = rule.iconItem();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        int maxTimes = viewer != null ? plugin.exchangeManager().computeMaxTimes(viewer, rule) : 0;

        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("§d" + rule.displayName()));
        lore.add(Component.text("§7━━━━ 兑换条件 ━━━━"));
        lore.add(Component.text("§c支付: " + rule.inputSummary()));
        lore.add(Component.text("§a获得: " + rule.outputSummary()));
        lore.add(Component.text("§7当前背包最多可兑换: " + (maxTimes > 0 ? "§b" + maxTimes + " §7次" : "§c0 次")));
        lore.add(Component.empty());
        lore.add(Component.text("§e左键=兑换1次 §6Shift+左键=全部兑换 §b右键=输入数量批量兑换"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ---- Button helpers ----

    private ItemStack qtyControl(int qty, String name) {
        ItemStack item = new ItemStack(Material.PAPER, Math.min(qty, 64));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name + " §e×" + qty));
            meta.lore(List.of(
                    Component.text("左键 +1 / 右键 -1", NamedTextColor.GRAY),
                    Component.text("Shift+左键 +10 / Shift+右键 -10", NamedTextColor.DARK_GRAY)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack hintItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, NamedTextColor.GRAY));
            List<Component> list = new ArrayList<>();
            for (String s : lore) list.add(Component.text(s, NamedTextColor.DARK_GRAY));
            meta.lore(list);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack labelItem(Material mat, String name, String... lore) {
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
        return labelItem(mat, name, lore);
    }

    private void fillEmpty() {
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) { gm.displayName(Component.text(" ")); glass.setItemMeta(gm); }
        for (int i = 0; i < ROWS * 9; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
        }
    }

    // ---- Quantity helpers ----

    private void adjustInputQty(int index, int delta) {
        if (index < 0 || index >= adminInputs.size()) return;
        RuleItem old = adminInputs.get(index);
        int newQty = Math.max(1, Math.min(2304, old.quantity() + delta)); // 36*64
        adminInputs.set(index, new RuleItem(old.material(), newQty, old.itemData()));
    }

    private void adjustOutputQty(int index, int delta) {
        if (index < 0 || index >= adminOutputs.size()) return;
        RuleItem old = adminOutputs.get(index);
        int newQty = Math.max(1, Math.min(2304, old.quantity() + delta));
        adminOutputs.set(index, new RuleItem(old.material(), newQty, old.itemData()));
    }

    private void clearEditor() {
        adminInputs.clear();
        adminOutputs.clear();
        ruleName = null;
        editingRuleId = null;
        editingEnabled = true;
    }

    // ---- InventoryHolder ----

    @Override public @NotNull Inventory getInventory() { return inventory; }

    // ====================== Inventory Listener ======================

    public static class Listener implements org.bukkit.event.Listener {

        private final KsEco plugin;
        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof ExchangeGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (gui.adminMode && !player.hasPermission("kseco.admin")) {
                player.closeInventory();
                player.sendMessage("§c权限已失效。");
                return;
            }

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;
            if (gui.adminMode) handleAdminClick(gui, player, slot, event);
            else handlePlayerClick(gui, player, slot, event);
        }

        private void handleAdminClick(ExchangeGui gui, Player player, int slot, InventoryClickEvent event) {
            // --- 输入物品槽 (1-4) ---
            if (slot >= INPUT_START && slot < INPUT_START + 4) {
                int idx = slot - INPUT_START;
                if (event.isShiftClick()) {
                    // Shift+click = 删除此槽位
                    if (idx < gui.adminInputs.size()) {
                        gui.adminInputs.remove(idx);
                        gui.reopen(player);
                    }
                    return;
                }
                // 点击 = 进入 Q-丢入模式
                player.closeInventory();
                pendingSet.put(player.getUniqueId(), new PendingSet(gui, true, idx));
                player.sendMessage("§a请手持物品按 §eQ §a丢出以设定输入#" + (idx+1) + "（Q=1个，Ctrl+Q=整组），或输入 §ccancel");
                return;
            }

            // --- 输出物品槽 (5-8) ---
            if (slot >= OUTPUT_START && slot < OUTPUT_START + 4) {
                int idx = slot - OUTPUT_START;
                if (event.isShiftClick()) {
                    if (idx < gui.adminOutputs.size()) {
                        gui.adminOutputs.remove(idx);
                        gui.reopen(player);
                    }
                    return;
                }
                player.closeInventory();
                pendingSet.put(player.getUniqueId(), new PendingSet(gui, false, idx));
                player.sendMessage("§a请手持物品按 §eQ §a丢出以设定输出#" + (idx+1) + "（Q=1个，Ctrl+Q=整组），或输入 §ccancel");
                return;
            }

            // --- 输入/输出数量控件（左加右减，Shift 调整 10） ---
            if (slot >= INPUT_QTY_START && slot < INPUT_QTY_START + 4) {
                int index = slot - INPUT_QTY_START;
                if (index < gui.adminInputs.size()) {
                    int delta = event.isRightClick() ? -1 : 1;
                    if (event.isShiftClick()) delta *= 10;
                    gui.adjustInputQty(index, delta);
                    gui.reopen(player);
                }
                return;
            }
            if (slot >= OUTPUT_QTY_START && slot < OUTPUT_QTY_START + 4) {
                int index = slot - OUTPUT_QTY_START;
                if (index < gui.adminOutputs.size()) {
                    int delta = event.isRightClick() ? -1 : 1;
                    if (event.isShiftClick()) delta *= 10;
                    gui.adjustOutputQty(index, delta);
                    gui.reopen(player);
                }
                return;
            }

            // --- 名称按钮 (27) ---
            if (slot == 27) {
                player.closeInventory();
                pendingName.put(player.getUniqueId(), gui);
                player.sendMessage("§a请在聊天栏输入规则名称（或输入 §ccancel §a取消）");
                return;
            }

            // --- 启用/禁用 (28) ---
            if (slot == 28) { gui.editingEnabled = !gui.editingEnabled; gui.reopen(player); return; }

            // --- 保存 (31) ---
            if (slot == 31 && !gui.adminInputs.isEmpty() && !gui.adminOutputs.isEmpty()) {
                ExchangeRule saved = plugin.exchangeManager().upsertRule(
                        gui.editingRuleId, gui.ruleName,
                        new ArrayList<>(gui.adminInputs), new ArrayList<>(gui.adminOutputs),
                        player.getName());
                if (saved != null) {
                    player.sendMessage("§a规则已保存: " + saved.displayName() +
                            " (" + gui.adminInputs.size() + "入 → " + gui.adminOutputs.size() + "出)");
                    if (!gui.editingEnabled) {
                        plugin.exchangeManager().toggleRule(saved.id(), false);
                    }
                }
                gui.clearEditor();
                gui.rules = plugin.exchangeManager().listAllRules();
                gui.reopen(player);
                return;
            }

            // --- 清空 (32) ---
            if (slot == 32) { gui.clearEditor(); gui.reopen(player); return; }

            // --- 删除当前规则 (35) ---
            if (slot == 35 && gui.editingRuleId != null && event.isShiftClick()) {
                plugin.exchangeManager().deleteRule(gui.editingRuleId);
                player.sendMessage("§c规则已删除。");
                gui.clearEditor();
                gui.rules = plugin.exchangeManager().listAllRules();
                gui.reopen(player);
                return;
            }

            // --- 规则列表 (36-43) ---
            if (slot >= 36 && slot < 44) {
                int idx = gui.page * RULES_PER_PAGE + (slot - 36);
                if (idx < gui.rules.size()) {
                    ExchangeRule rule = gui.rules.get(idx);
                    if (event.isRightClick()) {
                        // 切换启用
                        plugin.exchangeManager().toggleRule(rule.id(), !rule.enabled());
                        gui.rules = plugin.exchangeManager().listAllRules();
                        gui.reopen(player);
                    } else if (event.isShiftClick() || (event.getClick() != null && event.getClick().name().contains("DROP"))) {
                        // Q / drop = 删除
                        plugin.exchangeManager().deleteRule(rule.id());
                        player.sendMessage("§c规则已删除。");
                        gui.rules = plugin.exchangeManager().listAllRules();
                        gui.reopen(player);
                    } else {
                        // 左键 = 加载编辑
                        gui.editingRuleId = rule.id();
                        gui.ruleName = rule.name();
                        gui.adminInputs.clear();
                        gui.adminInputs.addAll(rule.inputs());
                        gui.adminOutputs.clear();
                        gui.adminOutputs.addAll(rule.outputs());
                        gui.editingEnabled = rule.enabled();
                        player.sendMessage("§e已加载规则: " + rule.displayName());
                        gui.reopen(player);
                    }
                }
                return;
            }

            // --- 返回 (49) ---
            if (slot == 49) { player.closeInventory(); return; }

            // --- 分页 ---
            if (slot == 45 && gui.page > 0) { gui.page--; gui.reopen(player); }
            if (slot == 53 && (gui.page + 1) * RULES_PER_PAGE < gui.rules.size()) { gui.page++; gui.reopen(player); }
        }

        private void handlePlayerClick(ExchangeGui gui, Player player, int slot, InventoryClickEvent event) {
            if (slot == 49) {
                player.closeInventory();
                new MarketMenu(plugin).open(player);
                return;
            }
            if (slot == 45 && gui.page > 0) { gui.page--; gui.reopen(player); return; }
            if (slot == 53 && (gui.page + 1) * 36 < gui.rules.size()) { gui.page++; gui.reopen(player); return; }

            // 规则执行
            if (slot < 0 || slot >= 36) return;
            int idx = gui.page * 36 + slot;
            if (idx < 0 || idx >= gui.rules.size()) return;
            ExchangeRule rule = gui.rules.get(idx);

            if (event.isRightClick()) {
                // 右键 = 在聊天栏输入自定义批量兑换次数
                int maxTimes = plugin.exchangeManager().computeMaxTimes(player, rule);
                player.closeInventory();
                pendingBatchQty.put(player.getUniqueId(), new PendingBatch(gui, rule.id()));
                player.sendMessage("§a请在聊天栏输入兑换次数（当前背包最多可兑换 §b" + maxTimes + " §a次），或输入 §ccancel §a取消");
                return;
            }

            int times = event.isShiftClick() ? plugin.exchangeManager().computeMaxTimes(player, rule) : 1;
            if (times <= 0) {
                player.sendMessage("§c材料不足，无法兑换。");
                return;
            }
            String error = plugin.exchangeManager().executeExchangeBatch(
                    player.getUniqueId(), player.getName(), rule.id(), times);
            if (error == null) {
                player.sendMessage(times > 1
                        ? "§a批量兑换成功！共兑换 §b" + times + " §a次，每次获得: " + rule.outputSummary()
                        : "§a兑换成功！你获得了: " + rule.outputSummary());
            } else {
                player.sendMessage("§c" + error);
            }
            player.closeInventory();
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            // No cleanup
        }
    }

    // ====================== Drop Listener (Q-丢入) ======================

    public static class DropListener implements org.bukkit.event.Listener {

        private final KsEco plugin;
        public DropListener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onDrop(PlayerDropItemEvent event) {
            Player player = event.getPlayer();
            PendingSet ps = pendingSet.remove(player.getUniqueId());
            if (ps == null) return;
            if (!player.hasPermission("kseco.admin")) {
                player.sendMessage("§c权限已失效，物品设定已取消。");
                return;
            }

            event.setCancelled(true); // 不让物品真的丢出

            ItemStack dropped = event.getItemDrop().getItemStack();
            if (dropped.getType().isAir()) {
                player.sendMessage("§c无效物品，已取消设定。");
                ps.gui.reopen(player);
                return;
            }

            // 捕获物品信息
            ItemStack captured = dropped.clone();
            int qty = dropped.getAmount(); // Q=1, Ctrl+Q=整组数
            byte[] itemData = null;
            try { itemData = captured.serializeAsBytes(); } catch (Exception ignored) {}

            RuleItem item = new RuleItem(captured.getType().name(), qty, itemData);

            if (ps.isInput) {
                // 放入输入列表
                if (ps.slotIndex < ps.gui.adminInputs.size()) {
                    ps.gui.adminInputs.set(ps.slotIndex, item); // 替换
                } else {
                    // 追加到列表（对齐 slotIndex）
                    while (ps.gui.adminInputs.size() < ps.slotIndex)
                        ps.gui.adminInputs.add(new RuleItem("STONE", 1, null)); // 占位
                    ps.gui.adminInputs.add(item);
                }
                player.sendMessage("§a已设输入[" + (ps.slotIndex+1) + "]: " +
                        item.display() + " x" + qty);
            } else {
                if (ps.slotIndex < ps.gui.adminOutputs.size()) {
                    ps.gui.adminOutputs.set(ps.slotIndex, item);
                } else {
                    while (ps.gui.adminOutputs.size() < ps.slotIndex)
                        ps.gui.adminOutputs.add(new RuleItem("STONE", 1, null));
                    ps.gui.adminOutputs.add(item);
                }
                player.sendMessage("§a已设输出[" + (ps.slotIndex+1) + "]: " +
                        item.display() + " x" + qty);
            }

            ps.gui.reopen(player);
        }
    }

    // ====================== Chat Listener (命名 + cancel pending) ======================

    public static class ChatListener implements org.bukkit.event.Listener {

        private final KsEco plugin;
        public ChatListener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            pendingName.remove(playerId);
            pendingSet.remove(playerId);
            pendingBatchQty.remove(playerId);
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            String msg = event.getMessage().trim();
            boolean hasPending = pendingName.containsKey(playerId)
                    || pendingBatchQty.containsKey(playerId)
                    || (msg.equalsIgnoreCase("cancel") && pendingSet.containsKey(playerId));
            if (!hasPending) return;

            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null) {
                    pendingName.remove(playerId);
                    pendingSet.remove(playerId);
                    pendingBatchQty.remove(playerId);
                    return;
                }

                // 1. 改名 pending
                ExchangeGui gui = pendingName.remove(playerId);
                if (gui != null) {
                    if (!player.hasPermission("kseco.admin")) {
                        player.sendMessage("§c权限已失效，命名已取消。");
                        return;
                    }
                    if (msg.equalsIgnoreCase("cancel")) {
                        player.sendMessage("§c已取消命名。");
                        gui.reopen(player);
                        return;
                    }
                    gui.ruleName = msg;
                    player.sendMessage("§a规则名称已设为: " + msg);
                    gui.reopen(player);
                    return;
                }

                // 2. cancel Q-丢入 pending
                if (msg.equalsIgnoreCase("cancel")) {
                    PendingSet ps = pendingSet.remove(playerId);
                    if (ps != null) {
                        player.sendMessage("§c已取消设定。");
                        ps.gui.reopen(player);
                        return;
                    }
                }

                // 3. 批量兑换数量输入 pending
                PendingBatch pb = pendingBatchQty.remove(playerId);
                if (pb == null) return;
                if (msg.equalsIgnoreCase("cancel")) {
                    player.sendMessage("§c已取消批量兑换。");
                    pb.gui.reopen(player);
                    return;
                }
                int times;
                try {
                    times = Integer.parseInt(msg.trim());
                } catch (NumberFormatException e) {
                    player.sendMessage("§c请输入有效的数字，或输入 §ccancel §c取消。");
                    pendingBatchQty.put(playerId, pb);
                    return;
                }
                if (times <= 0) {
                    player.sendMessage("§c兑换次数必须大于0，请重新输入，或输入 §ccancel §c取消。");
                    pendingBatchQty.put(playerId, pb);
                    return;
                }
                String error = plugin.exchangeManager().executeExchangeBatch(
                        playerId, player.getName(), pb.ruleId(), times);
                if (error == null) {
                    player.sendMessage("§a批量兑换成功！共兑换 §b" + times + " §a次。");
                } else {
                    player.sendMessage("§c" + error);
                }
                pb.gui.reopen(player);
            });
        }
    }
}
