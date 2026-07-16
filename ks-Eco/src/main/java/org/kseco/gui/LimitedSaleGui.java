package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.kseco.LimitedSaleManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LimitedSaleGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private UUID viewerUuid;
    private int view = 0; // 0=list, 1=preview
    private int page = 0;
    private int detailPage = 0;
    private String selectedSaleId;
    private boolean purchasePending;
    private final List<LimitedSaleManager.SaleItem> sales = new ArrayList<>();

    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 45;
    private static final int DETAIL_PAGE_SIZE = 20;

    public LimitedSaleGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        viewerUuid = player.getUniqueId();
        view = 0;
        page = 0;
        detailPage = 0;
        selectedSaleId = null;
        purchasePending = false;
        loadData();
        build();
        player.openInventory(inventory);
    }

    private void loadData() {
        sales.clear();
        sales.addAll(plugin.limitedSaleManager().listSales(false));
    }

    private void build() {
        if (view == 0) buildList();
        else buildPreview();
        fillEmpty();
    }

    private void buildList() {
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8限时直售 第" + (page + 1) + "页"));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < sales.size(); i++) {
            inventory.setItem(i, saleIcon(sales.get(start + i)));
        }
        if (sales.isEmpty()) {
            inventory.setItem(22, navButton(Material.PAPER, "§7暂无正在出售的商品"));
        }

        if (page > 0) inventory.setItem(45, navButton(Material.ARROW, "§a上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c返回主菜单"));
        if ((page + 1) * PAGE_SIZE < sales.size()) inventory.setItem(53, navButton(Material.ARROW, "§a下一页"));
    }

    private void buildPreview() {
        LimitedSaleManager.SaleItem sale = plugin.limitedSaleManager().getSale(selectedSaleId);
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8限时直售 - 预览"));

        if (sale == null) {
            inventory.setItem(22, navButton(Material.BARRIER, "§c商品不存在"));
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c返回列表"));
            return;
        }

        ItemStack actual = sale.item().clone();
        inventory.setItem(13, actual);
        inventory.setItem(22, saleInfoIcon(sale));
        inventory.setItem(31, detailBook(sale));

        boolean active = sale.active(plugin.limitedSaleManager().now());
        String verb = sale.blindBoxSale() ? "抽取" : "购买";
        String unit = sale.blindBoxSale() ? "次" : "份";
        inventory.setItem(40, navButton(active ? Material.EMERALD : Material.BARRIER,
                active ? "§a" + verb + " 1 " + unit : "§c无法" + verb,
                active ? "§7左键" + verb + " 1 " + unit : "§7状态: " + plugin.limitedSaleManager().statusText(sale),
                active ? "§7Shift+左键" + verb + " 10 " + unit : "§8请等待开售或联系管理员"));
        if (!sale.blindBoxSale() && sale.boxEnabled()) {
            int bought = viewerUuid != null ? plugin.limitedSaleManager().getPurchased(viewerUuid, sale.id()) : 0;
            boolean enoughStock = sale.unlimitedStock() || sale.remainingStock() >= LimitedSaleManager.BOX_UNITS;
            boolean withinLimit = sale.unlimitedPerPlayer()
                    || bought + LimitedSaleManager.BOX_UNITS <= sale.perPlayerLimit();
            boolean boxAvailable = active && sale.boxPurchaseAvailable() && enoughStock && withinLimit;
            inventory.setItem(41, navButton(boxAvailable ? Material.SHULKER_BOX : Material.GRAY_SHULKER_BOX,
                    boxAvailable ? "§d购买 1 整盒" : "§7整盒暂不可购",
                    "§7内含 27 份，每份 §f" + sale.item().getAmount() + " 个",
                    "§7整盒价格: §e" + plugin.limitedSaleManager().formatMoney(sale.boxPrice()),
                    boxAvailable ? "§a点击购买" : "§8库存不足、达到限购或商品状态不可用"));
        }

        List<String> detail = plugin.limitedSaleManager().describeItem(sale, viewerUuid);
        int maxPage = Math.max(0, (detail.size() - 1) / DETAIL_PAGE_SIZE);
        if (detailPage > 0) inventory.setItem(45, navButton(Material.ARROW, "§aNBT上一页"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c返回列表"));
        if (detailPage < maxPage) inventory.setItem(53, navButton(Material.ARROW, "§aNBT下一页"));
    }

    private ItemStack saleIcon(LimitedSaleManager.SaleItem sale) {
        ItemStack icon = sale.item().clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        meta.displayName(Component.text((sale.blindBoxSale() ? "§d🎁 " : "§e⏳ ") + sale.name()));
        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        if (!lore.isEmpty()) lore.add(Component.empty());
        appendSaleLines(lore, sale);
        lore.add(Component.empty());
        lore.add(Component.text(sale.blindBoxSale() ? "§a左键预览奖池/奖品" : "§a左键预览完整物品/NBT", NamedTextColor.GREEN));
        lore.add(Component.text(sale.blindBoxSale() ? "§e右键抽 1 次，Shift+右键抽 10 次" : "§e右键购买 1 份，Shift+右键购买 10 份", NamedTextColor.YELLOW));
        if (!sale.blindBoxSale() && sale.boxEnabled()) {
            lore.add(Component.text("§dShift+左键购买 1 整盒（27 份）", NamedTextColor.LIGHT_PURPLE));
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack saleInfoIcon(LimitedSaleManager.SaleItem sale) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text("§6" + sale.name()));
        List<Component> lore = new ArrayList<>();
        appendSaleLines(lore, sale);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void appendSaleLines(List<Component> lore, LimitedSaleManager.SaleItem sale) {
        LimitedSaleManager manager = plugin.limitedSaleManager();
        int bought = viewerUuid != null ? manager.getPurchased(viewerUuid, sale.id()) : 0;
        lore.add(Component.text("类型: §f" + (sale.blindBoxSale() ? "限时盲盒" : "直售商品"), NamedTextColor.GRAY));
        if (sale.blindBoxSale()) lore.add(Component.text("绑定池: §f" + sale.blindBoxPoolId(), NamedTextColor.GRAY));
        lore.add(Component.text("价格: §e" + manager.formatMoney(sale.price()), NamedTextColor.GRAY));
        if (!sale.blindBoxSale() && sale.boxEnabled()) {
            lore.add(Component.text("整盒价: §d" + manager.formatMoney(sale.boxPrice())
                    + " §7(27 份)", NamedTextColor.GRAY));
        }
        lore.add(Component.text((sale.blindBoxSale() ? "总可抽: §f" : "总量: §f") + manager.stockText(sale.totalStock())
                + (sale.blindBoxSale() ? " §7/ 已抽 §f" : " §7/ 已售 §f") + sale.sold()
                + (sale.unlimitedStock() ? "" : " §7/ 剩余 §f" + sale.remainingStock()), NamedTextColor.GRAY));
        lore.add(Component.text((sale.blindBoxSale() ? "每人最多: §f" : "个人限购: §f") + manager.limitText(sale.perPlayerLimit())
                + (sale.blindBoxSale() ? " §7/ 已抽 §f" : " §7/ 已买 §f") + bought, NamedTextColor.GRAY));
        lore.add(Component.text("开售: §f" + manager.formatTime(sale.startsAt()), NamedTextColor.GRAY));
        lore.add(Component.text("下次刷新: §f" + manager.nextRefreshText(sale), NamedTextColor.GRAY));
        lore.add(Component.text("刷新倒计时: §f" + manager.refreshCountdownText(sale), NamedTextColor.GRAY));
        lore.add(Component.text("状态: §f" + manager.statusText(sale), NamedTextColor.GRAY));
    }

    private ItemStack detailBook(LimitedSaleManager.SaleItem sale) {
        List<String> lines = plugin.limitedSaleManager().describeItem(sale, viewerUuid);
        int maxPage = Math.max(0, (lines.size() - 1) / DETAIL_PAGE_SIZE);
        detailPage = Math.max(0, Math.min(detailPage, maxPage));
        int start = detailPage * DETAIL_PAGE_SIZE;

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta == null) return book;
        meta.displayName(Component.text((sale.blindBoxSale() ? "§b奖池预览 " : "§b完整NBT预览 ")
                + "§7(" + (detailPage + 1) + "/" + (maxPage + 1) + ")"));
        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < DETAIL_PAGE_SIZE && start + i < lines.size(); i++) {
            lore.add(Component.text(lines.get(start + i), NamedTextColor.GRAY));
        }
        meta.lore(lore);
        book.setItemMeta(meta);
        return book;
    }

    private void buy(Player player, String saleId, int quantity) {
        if (purchasePending) {
            player.sendMessage(ChatColor.YELLOW + "上一笔购买仍在结算，请稍候。");
            return;
        }
        purchasePending = true;
        plugin.limitedSaleManager().purchaseAsync(player, saleId, quantity, result -> finishPurchase(player, result));
    }

    private void buyBox(Player player, String saleId) {
        if (purchasePending) {
            player.sendMessage(ChatColor.YELLOW + "上一笔购买仍在结算，请稍候。");
            return;
        }
        purchasePending = true;
        plugin.limitedSaleManager().purchaseBoxAsync(player, saleId, result -> finishPurchase(player, result));
    }

    private void finishPurchase(Player player, LimitedSaleManager.PurchaseResult result) {
        purchasePending = false;
        if (!player.isOnline()) return;
        player.sendMessage((result.success() ? ChatColor.GREEN : ChatColor.RED) + result.message());
        player.playSound(player.getLocation(), result.success()
                ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.2f);
        if (player.getOpenInventory().getTopInventory().getHolder() == this) {
            loadData();
            build();
            player.openInventory(inventory);
        }
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
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            glass.setItemMeta(meta);
        }
        for (int i = 0; i < ROWS * 9; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
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
            if (!(event.getView().getTopInventory().getHolder() instanceof LimitedSaleGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;

            if (gui.view == 0) {
                if (slot >= 0 && slot < PAGE_SIZE) {
                    int index = gui.page * PAGE_SIZE + slot;
                    if (index < gui.sales.size()) {
                        LimitedSaleManager.SaleItem sale = gui.sales.get(index);
                        if (event.isShiftClick() && event.isLeftClick() && sale.boxEnabled()) {
                            gui.buyBox(player, sale.id());
                        } else if (event.isRightClick()) {
                            gui.buy(player, sale.id(), event.isShiftClick() ? 10 : 1);
                        } else {
                            gui.selectedSaleId = sale.id();
                            gui.view = 1;
                            gui.detailPage = 0;
                            gui.build();
                            player.openInventory(gui.getInventory());
                        }
                    }
                    return;
                }
                switch (slot) {
                    case 45 -> {
                        if (gui.page > 0) {
                            gui.page--;
                            gui.build();
                            player.openInventory(gui.getInventory());
                        }
                    }
                    case 49 -> {
                        player.closeInventory();
                        new EcoGuiMainMenu(plugin).open(player);
                    }
                    case 53 -> {
                        if ((gui.page + 1) * PAGE_SIZE < gui.sales.size()) {
                            gui.page++;
                            gui.build();
                            player.openInventory(gui.getInventory());
                        }
                    }
                }
                return;
            }

            if (gui.view == 1) {
                switch (slot) {
                    case 40 -> gui.buy(player, gui.selectedSaleId, event.isShiftClick() ? 10 : 1);
                    case 41 -> gui.buyBox(player, gui.selectedSaleId);
                    case 45 -> {
                        if (gui.detailPage > 0) {
                            gui.detailPage--;
                            gui.build();
                            player.openInventory(gui.getInventory());
                        }
                    }
                    case 49 -> {
                        gui.view = 0;
                        gui.detailPage = 0;
                        gui.loadData();
                        gui.build();
                        player.openInventory(gui.getInventory());
                    }
                    case 53 -> {
                        LimitedSaleManager.SaleItem sale = plugin.limitedSaleManager().getSale(gui.selectedSaleId);
                        if (sale != null) {
                            int maxPage = Math.max(0,
                                    (plugin.limitedSaleManager().describeItem(sale, player.getUniqueId()).size() - 1) / DETAIL_PAGE_SIZE);
                            if (gui.detailPage < maxPage) {
                                gui.detailPage++;
                                gui.build();
                                player.openInventory(gui.getInventory());
                            }
                        }
                    }
                }
            }
        }
    }
}
