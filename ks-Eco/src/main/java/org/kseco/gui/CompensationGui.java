package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.CompensationManager;
import org.kseco.KsEco;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Player claims and admin configuration under the /kseco gui entry. */
public final class CompensationGui implements InventoryHolder {
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Map<UUID, PendingInput> PENDING_INPUT = new ConcurrentHashMap<>();

    private final KsEco plugin;
    private Inventory inventory;
    private View view = View.PLAYER;
    private int page;
    private String selectedId;
    private boolean claiming;
    private boolean adminViewer;
    private List<CompensationManager.Plan> plans = List.of();

    private enum View { PLAYER, ADMIN_LIST, ADMIN_EDIT }
    private record PendingInput(CompensationGui gui, String planId, String field) {}

    public CompensationGui(KsEco plugin) { this.plugin = plugin; }

    public void open(Player player) {
        adminViewer = player.hasPermission("kseco.admin");
        view = View.PLAYER;
        page = 0;
        selectedId = null;
        load(player);
    }

    private void load(Player player) {
        showLoading(player);
        if (view == View.PLAYER) {
            plugin.compensationManager().listForPlayer(player.getUniqueId(), rows -> applyRows(player, rows));
        } else {
            plugin.compensationManager().listAll(rows -> applyRows(player, rows));
        }
    }

    private void showLoading(Player player) {
        inventory = Bukkit.createInventory(this, 54, Component.text("§8服务器补偿 · 加载中"));
        inventory.setItem(22, button(Material.CLOCK, "§b正在读取补偿计划..."));
        fillEmpty();
        player.openInventory(inventory);
    }

    private void applyRows(Player player, List<CompensationManager.Plan> rows) {
        if (!plugin.scheduler().isEntityThread(player)) {
            plugin.scheduler().runEntity(player, () -> applyRows(player, rows), () -> { });
            return;
        }
        if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != this) return;
        plans = List.copyOf(rows);
        if (view == View.ADMIN_EDIT && selectedPlan() == null) view = View.ADMIN_LIST;
        build();
        player.openInventory(inventory);
    }

    private void build() {
        switch (view) {
            case PLAYER -> buildPlayer();
            case ADMIN_LIST -> buildAdminList();
            case ADMIN_EDIT -> buildAdminEdit();
        }
        fillEmpty();
    }

    private void buildPlayer() {
        inventory = Bukkit.createInventory(this, 54, Component.text("§8服务器补偿 第" + (page + 1) + "页"));
        int start = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < plans.size(); slot++) {
            inventory.setItem(slot, playerPlanIcon(plans.get(start + slot)));
        }
        if (plans.isEmpty()) inventory.setItem(22, button(Material.PAPER, "§7当前没有可领取的补偿"));
        if (page > 0) inventory.setItem(45, button(Material.ARROW, "§a上一页"));
        if (adminViewer) inventory.setItem(46, button(Material.COMMAND_BLOCK, "§c补偿管理", "§7管理员配置特殊物品与有效期"));
        inventory.setItem(49, button(Material.OAK_DOOR, "§c返回经济面板"));
        if ((page + 1) * PAGE_SIZE < plans.size()) inventory.setItem(53, button(Material.ARROW, "§a下一页"));
    }

    private void buildAdminList() {
        inventory = Bukkit.createInventory(this, 54, Component.text("§8服务器补偿管理 第" + (page + 1) + "页"));
        int start = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < plans.size(); slot++) {
            inventory.setItem(slot, adminPlanIcon(plans.get(start + slot)));
        }
        if (plans.isEmpty()) inventory.setItem(22, button(Material.PAPER, "§7暂无补偿计划", "§7手持特殊物品后点击下方新增"));
        if (page > 0) inventory.setItem(45, button(Material.ARROW, "§a上一页"));
        inventory.setItem(46, button(Material.EMERALD, "§a新增手持物品补偿",
                "§7保留完整 NBT/PDC 与当前堆叠数量", "§7默认所有人可领一次，7 天后过期"));
        inventory.setItem(49, button(Material.OAK_DOOR, "§c返回领取页"));
        if ((page + 1) * PAGE_SIZE < plans.size()) inventory.setItem(53, button(Material.ARROW, "§a下一页"));
    }

    private void buildAdminEdit() {
        CompensationManager.Plan plan = selectedPlan();
        inventory = Bukkit.createInventory(this, 54, Component.text("§8编辑补偿 - " + (plan == null ? "?" : plan.name())));
        if (plan == null) {
            inventory.setItem(22, button(Material.BARRIER, "§c补偿计划不存在"));
            inventory.setItem(49, button(Material.OAK_DOOR, "§c返回列表"));
            return;
        }
        ItemStack preview = plan.item().clone();
        preview.setAmount(Math.max(1, Math.min(preview.getMaxStackSize(), plan.amount())));
        inventory.setItem(4, preview);
        inventory.setItem(10, button(Material.NAME_TAG, "§f名称: §b" + plan.name(), "§7点击后在聊天栏输入新名称"));
        inventory.setItem(11, button(Material.CHEST, "§e每人数量: §f" + plan.amount(),
                "§7左键 +1 / 右键 -1", "§7Shift + 左右键调整 10"));
        inventory.setItem(12, button(Material.CLOCK, "§d有效天数: §f" + plan.expiryDays(),
                "§7左键 +1 天 / 右键 -1 天", "§7Shift + 左右键调整 7 天", "§7点击中键可精确输入"));
        inventory.setItem(13, button(Material.LIME_DYE, "§a开始时间: §f现在", "§7修改有效天数时重新从现在计时"));
        inventory.setItem(14, button(plan.enabled() ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                plan.enabled() ? "§a已启用" : "§c已停用", "§7点击切换领取入口"));
        inventory.setItem(15, button(Material.ITEM_FRAME, "§b替换为手持物品", "§7保留手持物品完整 NBT/PDC"));
        inventory.setItem(23, button(Material.BARRIER, "§c删除计划", "§7中键确认删除"));
        inventory.setItem(31, statusIcon(plan));
        inventory.setItem(49, button(Material.OAK_DOOR, "§c返回管理列表"));
    }

    private ItemStack playerPlanIcon(CompensationManager.Plan plan) {
        ItemStack icon = plan.item().clone();
        icon.setAmount(Math.max(1, Math.min(icon.getMaxStackSize(), plan.amount())));
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        meta.displayName(Component.text((plan.claimed() ? "§7✔ " : "§6✦ ") + plan.name()));
        List<Component> lore = copyLore(meta);
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.add(Component.text("§7每人获得: §f" + plan.amount()));
        lore.add(Component.text("§7领取截止: §f" + formatTime(plan.endsAt())));
        lore.add(Component.text(plan.claimed() ? "§a你已领取至暂存箱" : "§e点击领取（每人一次）"));
        meta.lore(lore); icon.setItemMeta(meta); return icon;
    }

    private ItemStack adminPlanIcon(CompensationManager.Plan plan) {
        ItemStack icon = plan.item().clone();
        icon.setAmount(Math.max(1, Math.min(icon.getMaxStackSize(), plan.amount())));
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        meta.displayName(Component.text((plan.enabled() ? "§a" : "§7") + plan.name()));
        List<Component> lore = copyLore(meta);
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.add(Component.text("§7ID: §8" + plan.id()));
        lore.add(Component.text("§7每人数量: §f" + plan.amount()));
        lore.add(Component.text("§7有效天数: §f" + plan.expiryDays()));
        lore.add(Component.text("§7已领取: §f" + plan.claimCount()));
        lore.add(Component.text("§7截止: §f" + formatTime(plan.endsAt())));
        lore.add(Component.empty()); lore.add(Component.text("§a点击编辑"));
        meta.lore(lore); icon.setItemMeta(meta); return icon;
    }

    private ItemStack statusIcon(CompensationManager.Plan plan) {
        return button(Material.KNOWLEDGE_BOOK, "§b当前配置", "§7ID: §8" + plan.id(),
                "§7所有玩家可领取: §a是", "§7每人限领一次: §a是",
                "§7已领取人数: §f" + plan.claimCount(), "§7领取截止: §f" + formatTime(plan.endsAt()));
    }

    private List<Component> copyLore(ItemMeta meta) {
        return meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
    }

    private CompensationManager.Plan selectedPlan() {
        for (CompensationManager.Plan plan : plans) if (plan.id().equals(selectedId)) return plan;
        return null;
    }

    private CompensationManager.Plan planAt(int rawSlot) {
        int index = page * PAGE_SIZE + rawSlot;
        return index >= 0 && index < plans.size() ? plans.get(index) : null;
    }

    private void fillEmpty() {
        ItemStack glass = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) if (inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(Component.text(line, NamedTextColor.GRAY));
            if (!lines.isEmpty()) meta.lore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatTime(long epochSeconds) { return epochSeconds <= 0 ? "不限" : TIME.format(Instant.ofEpochSecond(epochSeconds)); }
    @Override public @NotNull Inventory getInventory() { return inventory; }

    public static final class Listener implements org.bukkit.event.Listener {
        private final KsEco plugin;
        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof CompensationGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.inventory.getSize()) return;
            switch (gui.view) {
                case PLAYER -> handlePlayer(gui, player, slot);
                case ADMIN_LIST -> handleAdminList(gui, player, slot);
                case ADMIN_EDIT -> handleAdminEdit(gui, player, slot, event);
            }
        }

        private void handlePlayer(CompensationGui gui, Player player, int slot) {
            if (slot < PAGE_SIZE) {
                CompensationManager.Plan plan = gui.planAt(slot);
                if (plan == null || gui.claiming) return;
                if (plan.claimed()) { player.sendMessage("§e你已经领取过这份补偿。"); return; }
                gui.claiming = true;
                plugin.compensationManager().claim(player.getUniqueId(), player.getName(), plan.id(), result -> {
                    plugin.scheduler().runEntity(player, () -> {
                        gui.claiming = false;
                        if (!player.isOnline()) return;
                        player.sendMessage((result.success() ? "§a" : "§c") + result.message());
                        if (result.success()) player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                        if (player.getOpenInventory().getTopInventory().getHolder() == gui) gui.load(player);
                    }, () -> gui.claiming = false);
                });
                return;
            }
            if (slot == 45 && gui.page > 0) { gui.page--; gui.load(player); }
            else if (slot == 46 && player.hasPermission("kseco.admin")) { gui.view = View.ADMIN_LIST; gui.page = 0; gui.load(player); }
            else if (slot == 49) { player.closeInventory(); new EcoGuiMainMenu(plugin).open(player); }
            else if (slot == 53 && (gui.page + 1) * PAGE_SIZE < gui.plans.size()) { gui.page++; gui.load(player); }
        }

        private void handleAdminList(CompensationGui gui, Player player, int slot) {
            if (!player.hasPermission("kseco.admin")) { player.closeInventory(); return; }
            if (slot < PAGE_SIZE) {
                CompensationManager.Plan plan = gui.planAt(slot);
                if (plan != null) { gui.selectedId = plan.id(); gui.view = View.ADMIN_EDIT; gui.build(); player.openInventory(gui.inventory); }
            } else if (slot == 45 && gui.page > 0) { gui.page--; gui.load(player); }
            else if (slot == 46) {
                plugin.compensationManager().create(player.getInventory().getItemInMainHand(), 7, result -> finish(gui, player, result));
            } else if (slot == 49) { gui.view = View.PLAYER; gui.page = 0; gui.load(player); }
            else if (slot == 53 && (gui.page + 1) * PAGE_SIZE < gui.plans.size()) { gui.page++; gui.load(player); }
        }

        private void handleAdminEdit(CompensationGui gui, Player player, int slot, InventoryClickEvent event) {
            if (!player.hasPermission("kseco.admin")) { player.closeInventory(); return; }
            CompensationManager.Plan plan = gui.selectedPlan();
            if (plan == null) return;
            int step = event.isShiftClick() ? 10 : 1;
            if (slot == 10) ask(gui, player, plan.id(), "name", "请输入新的补偿名称");
            else if (slot == 11) {
                int delta = event.isRightClick() ? -step : step;
                plugin.compensationManager().updateAmount(plan.id(), plan.amount() + delta, result -> finish(gui, player, result));
            } else if (slot == 12 && event.getClick() == ClickType.MIDDLE) ask(gui, player, plan.id(), "days", "请输入有效天数（1-3650）");
            else if (slot == 12) {
                int daysStep = event.isShiftClick() ? 7 : 1;
                int days = plan.expiryDays() + (event.isRightClick() ? -daysStep : daysStep);
                plugin.compensationManager().updateExpiryDays(plan.id(), Math.max(1, days), result -> finish(gui, player, result));
            } else if (slot == 14) plugin.compensationManager().updateEnabled(plan.id(), !plan.enabled(), result -> finish(gui, player, result));
            else if (slot == 15) plugin.compensationManager().replaceItem(plan.id(), player.getInventory().getItemInMainHand(), result -> finish(gui, player, result));
            else if (slot == 23 && event.getClick() == ClickType.MIDDLE) {
                plugin.compensationManager().delete(plan.id(), result -> { gui.view = View.ADMIN_LIST; gui.selectedId = null; finish(gui, player, result); });
            } else if (slot == 49) { gui.view = View.ADMIN_LIST; gui.selectedId = null; gui.load(player); }
        }

        private static void ask(CompensationGui gui, Player player, String planId, String field, String prompt) {
            PENDING_INPUT.put(player.getUniqueId(), new PendingInput(gui, planId, field));
            player.closeInventory();
            player.sendMessage("§a" + prompt + "§7，输入 cancel 取消");
        }

        private static void finish(CompensationGui gui, Player player, CompensationManager.OperationResult result) {
            gui.plugin.scheduler().runEntity(player, () -> {
                if (!player.isOnline()) return;
                player.sendMessage((result.success() ? "§a" : "§c") + result.message());
                gui.load(player);
            }, () -> { });
        }
    }

    public static final class ChatListener implements org.bukkit.event.Listener {
        private final KsEco plugin;
        public ChatListener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler public void onChat(AsyncPlayerChatEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            if (!PENDING_INPUT.containsKey(playerId)) return;
            event.setCancelled(true);
            String text = event.getMessage().trim();
            plugin.scheduler().runPlayer(playerId, () -> {
                PendingInput pending = PENDING_INPUT.remove(playerId);
                Player player = Bukkit.getPlayer(playerId);
                if (pending == null || player == null) return;
                if (text.equalsIgnoreCase("cancel")) { player.sendMessage(ChatColor.YELLOW + "已取消输入"); pending.gui.load(player); return; }
                if (pending.field.equals("name")) {
                    plugin.compensationManager().updateName(pending.planId, text, result -> Listener.finish(pending.gui, player, result));
                    return;
                }
                try {
                    int days = Integer.parseInt(text);
                    if (days < 1 || days > 3650) throw new NumberFormatException();
                    plugin.compensationManager().updateExpiryDays(pending.planId, days, result -> Listener.finish(pending.gui, player, result));
                } catch (NumberFormatException e) {
                    player.sendMessage("§c请输入 1-3650 之间的整数");
                    pending.gui.load(player);
                }
            });
        }
    }
}
