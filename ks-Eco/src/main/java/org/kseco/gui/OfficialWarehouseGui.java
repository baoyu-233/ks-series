package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.OfficialWarehouseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/** Administrator-only inventory for inspecting and extracting official market acquisitions. */
public final class OfficialWarehouseGui implements InventoryHolder {
    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final Map<String, OfficialWarehouseGui> PENDING_LOADS = new ConcurrentHashMap<>();
    private static final Map<String, ClaimContext> PENDING_CLAIMS = new ConcurrentHashMap<>();

    private final KsEco plugin;
    private Inventory inventory;
    private List<OfficialWarehouseManager.WarehouseItem> items = List.of();
    private int page;
    private int total;

    public OfficialWarehouseGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage("§c只有管理员可以打开官方仓库。");
            return;
        }
        inventory = Bukkit.createInventory(this, SIZE, Component.text("官方仓库 — 加载中", NamedTextColor.DARK_AQUA));
        inventory.setItem(22, button(Material.CLOCK, "§e正在读取官方仓库..."));
        fillEmpty();
        player.openInventory(inventory);
        load(player.getUniqueId(), page);
    }

    private void load(UUID playerUuid, int requestedPage) {
        page = Math.max(0, requestedPage);
        String loadId = UUID.randomUUID().toString();
        PENDING_LOADS.put(loadId, this);
        int offset = page * PAGE_SIZE;
        try {
            plugin.asyncWorkPool().executeDatabase(() -> {
                OfficialWarehouseManager.WarehousePage loaded =
                        plugin.officialWarehouseManager().loadPage(offset, PAGE_SIZE);
                Bukkit.getScheduler().runTask(plugin, () -> finishLoad(loadId, playerUuid, loaded));
            });
        } catch (RejectedExecutionException rejected) {
            PENDING_LOADS.remove(loadId);
            showLoadError(playerUuid, "系统繁忙，请稍后重试");
        }
    }

    private static void finishLoad(String loadId, UUID playerUuid,
                                   OfficialWarehouseManager.WarehousePage loaded) {
        OfficialWarehouseGui gui = PENDING_LOADS.remove(loadId);
        if (gui == null) return;
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()
                || player.getOpenInventory().getTopInventory().getHolder() != gui) return;

        List<OfficialWarehouseManager.WarehouseItem> decoded = new ArrayList<>();
        for (OfficialWarehouseManager.WarehouseSnapshot snapshot : loaded.rows()) {
            OfficialWarehouseManager.WarehouseItem item = gui.plugin.officialWarehouseManager().materialize(snapshot);
            if (item != null) decoded.add(item);
        }
        gui.items = List.copyOf(decoded);
        gui.total = Math.max(0, loaded.total());
        int maxPage = gui.total == 0 ? 0 : (gui.total - 1) / PAGE_SIZE;
        if (gui.page > maxPage) {
            gui.load(playerUuid, maxPage);
            return;
        }
        gui.build(player);
        player.openInventory(gui.inventory);
    }

    private void showLoadError(UUID playerUuid, String message) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) return;
        inventory = Bukkit.createInventory(this, SIZE, Component.text("官方仓库", NamedTextColor.DARK_AQUA));
        inventory.setItem(22, button(Material.BARRIER, "§c" + message, "§7关闭后重新打开即可重试"));
        inventory.setItem(49, button(Material.OAK_DOOR, "§c返回经济面板"));
        fillEmpty();
        player.openInventory(inventory);
    }

    private void build(Player viewer) {
        inventory = Bukkit.createInventory(this, SIZE,
                Component.text("官方仓库 — 第 " + (page + 1) + " 页", NamedTextColor.DARK_AQUA));
        for (int slot = 0; slot < items.size() && slot < PAGE_SIZE; slot++) {
            inventory.setItem(slot, display(items.get(slot)));
        }
        if (items.isEmpty()) {
            inventory.setItem(22, button(Material.PAPER, "§7官方仓库暂无物品"));
        }
        if (page > 0) inventory.setItem(45, button(Material.ARROW, "§a上一页"));
        inventory.setItem(49, button(Material.OAK_DOOR, "§c返回经济面板",
                "§7仓库共 §f" + total + " §7条记录"));
        if ((page + 1) * PAGE_SIZE < total) inventory.setItem(53, button(Material.ARROW, "§a下一页"));
        fillEmpty();
    }

    private ItemStack display(OfficialWarehouseManager.WarehouseItem row) {
        ItemStack display = row.item().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() && meta.lore() != null
                    ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text("§7来源卖家: §f" + safe(row.snapshot().sellerName())));
            lore.add(Component.text("§7成交金额: §e" + plugin.vaultHook().format(row.snapshot().paidPrice())));
            lore.add(Component.text("§7来源: §f" + formatSource(row.snapshot().source())));
            if (row.snapshot().listingId() != null && !row.snapshot().listingId().isBlank()) {
                lore.add(Component.text("§8挂单: " + row.snapshot().listingId()));
            }
            lore.add(Component.text("§8入仓: " + formatTime(row.snapshot().storedAt())));
            lore.add(Component.empty());
            lore.add(Component.text("§a§l左键提取到管理员背包"));
            meta.lore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    private void claim(Player player, int index) {
        if (index < 0 || index >= items.size()) return;
        OfficialWarehouseManager.WarehouseItem row = items.get(index);
        if (!canFit(player, row.item())) {
            player.sendMessage("§c背包空间不足，无法提取该物品。");
            return;
        }
        String warehouseId = row.snapshot().id();
        if (PENDING_CLAIMS.values().stream().anyMatch(ctx -> ctx.warehouseId().equals(warehouseId))) {
            player.sendMessage("§e该物品正在处理，请稍候。");
            return;
        }

        String claimId = UUID.randomUUID().toString();
        PENDING_CLAIMS.put(claimId, new ClaimContext(this, player.getUniqueId(), warehouseId));
        player.sendMessage("§7正在提取官方仓库物品...");
        try {
            plugin.asyncWorkPool().executeDatabase(() -> {
                OfficialWarehouseManager.WarehouseSnapshot claimed =
                        plugin.officialWarehouseManager().claim(warehouseId);
                Bukkit.getScheduler().runTask(plugin, () -> finishClaim(claimId, claimed));
            });
        } catch (RejectedExecutionException rejected) {
            PENDING_CLAIMS.remove(claimId);
            player.sendMessage("§c系统繁忙，请稍后重试。");
        }
    }

    private static void finishClaim(String claimId, OfficialWarehouseManager.WarehouseSnapshot claimed) {
        ClaimContext context = PENDING_CLAIMS.remove(claimId);
        if (context == null) return;
        OfficialWarehouseGui gui = context.gui();
        Player player = Bukkit.getPlayer(context.playerUuid());
        if (claimed == null) {
            if (player != null) {
                player.sendMessage("§c提取失败，该记录可能已被其他管理员处理。");
                new OfficialWarehouseGui(gui.plugin).open(player);
            }
            return;
        }

        OfficialWarehouseManager.WarehouseItem decoded = gui.plugin.officialWarehouseManager().materialize(claimed);
        if (player == null || !player.isOnline() || decoded == null || !canFit(player, decoded.item())) {
            gui.restoreClaim(claimed, context.playerUuid());
            if (player != null && player.isOnline()) player.sendMessage("§c背包状态已变化，提取已取消。");
            return;
        }

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(decoded.item().clone());
        if (!overflow.isEmpty()) {
            gui.plugin.getLogger().severe("Official warehouse capacity check failed after claim " + claimed.id());
            for (ItemStack extra : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
        player.sendMessage("§a已从官方仓库提取物品。");
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.7f, 1.1f);
        new OfficialWarehouseGui(gui.plugin).open(player);
    }

    private void restoreClaim(OfficialWarehouseManager.WarehouseSnapshot snapshot, UUID playerUuid) {
        try {
            plugin.asyncWorkPool().executeDatabase(() -> {
                boolean restored = plugin.officialWarehouseManager().restore(snapshot);
                if (!restored) {
                    plugin.getLogger().severe("Official warehouse claim restoration requires review: " + snapshot.id());
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player != null && player.isOnline()) {
                        player.sendMessage(restored ? "§e物品已恢复到官方仓库。" : "§c仓库恢复失败，请联系管理员检查日志。");
                        new OfficialWarehouseGui(plugin).open(player);
                    }
                });
            });
        } catch (RejectedExecutionException rejected) {
            plugin.getLogger().severe("Official warehouse restore queue rejected: " + snapshot.id());
        }
    }

    private static boolean canFit(Player player, ItemStack item) {
        int remaining = item.getAmount();
        int maxStack = item.getMaxStackSize();
        for (ItemStack current : player.getInventory().getStorageContents()) {
            if (current == null || current.getType().isAir()) {
                remaining -= maxStack;
            } else if (current.isSimilar(item)) {
                remaining -= Math.max(0, current.getMaxStackSize() - current.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    private static String formatSource(String source) {
        if (source == null || source.isBlank()) return "官方收购";
        return switch (source) {
            case "MARKET_SWEEP" -> "低价挂单自动收购";
            case "OFFICIAL_BUY" -> "官方收购";
            default -> source;
        };
    }

    private static String formatTime(long unixSeconds) {
        long diff = Math.max(0, System.currentTimeMillis() / 1000 - unixSeconds);
        if (diff < 3600) return Math.max(0, diff / 60) + " 分钟前";
        if (diff < 86400) return diff / 3600 + " 小时前";
        return diff / 86400 + " 天前";
    }

    private static ItemStack button(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (loreLines.length > 0) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreLines) lore.add(Component.text(line));
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillEmpty() {
        ItemStack filler = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < SIZE; slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, filler.clone());
        }
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
            if (!(event.getView().getTopInventory().getHolder() instanceof OfficialWarehouseGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("kseco.admin")) return;
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= SIZE) return;
            if (slot == 45 && gui.page > 0) {
                gui.openLoading(player, gui.page - 1);
                return;
            }
            if (slot == 49) {
                new EcoGuiMainMenu(plugin).open(player);
                return;
            }
            if (slot == 53 && (gui.page + 1) * PAGE_SIZE < gui.total) {
                gui.openLoading(player, gui.page + 1);
                return;
            }
            if (slot < PAGE_SIZE && event.isLeftClick()) gui.claim(player, slot);
        }
    }

    private void openLoading(Player player, int targetPage) {
        page = Math.max(0, targetPage);
        inventory = Bukkit.createInventory(this, SIZE, Component.text("官方仓库 — 加载中", NamedTextColor.DARK_AQUA));
        inventory.setItem(22, button(Material.CLOCK, "§e正在读取官方仓库..."));
        fillEmpty();
        player.openInventory(inventory);
        load(player.getUniqueId(), page);
    }

    private record ClaimContext(OfficialWarehouseGui gui, UUID playerUuid, String warehouseId) {}
}
