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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.ListingManager.Listing;

import java.util.*;

/**
 * 市场 GUI — 只做金钱交易（卖钱）。
 * 显示真实 NBT 物品 + 价格，左键预览 → 确认购买。
 * 官方兑换走 /exchange。
 */
public final class MarketMenu implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    int page = 0;
    List<Listing> currentListings = List.of();
    List<ItemStack> builtItems = List.of(); // 主线程构建好的展示 ItemStack
    boolean showMyOnly = false;
    boolean showPropertyOnly = false;
    UUID myUuid; // 缓存当前玩家 UUID
    long loadGeneration = 0;

    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 45;

    public MarketMenu(KsEco plugin) { this.plugin = plugin; }

    public void open(Player player) {
        open(player, false, false);
    }

    public void open(Player player, boolean myOnly) {
        open(player, myOnly, false);
    }

    public void open(Player player, boolean myOnly, boolean propertyOnly) {
        this.showMyOnly = myOnly;
        this.showPropertyOnly = propertyOnly;
        this.myUuid = player.getUniqueId();
        this.page = 0;
        long generation = ++loadGeneration;
        UUID viewerUuid = this.myUuid;
        boolean requestedMyOnly = this.showMyOnly;
        boolean requestedPropertyOnly = this.showPropertyOnly;
        // 先打开占位 loading 界面，不阻塞主线程
        this.inventory = buildLoadingInventory();
        player.openInventory(inventory);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Listing> loaded = loadListings(viewerUuid, requestedMyOnly, requestedPropertyOnly);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && generation == loadGeneration
                        && player.getOpenInventory().getTopInventory().getHolder() == this) {
                    currentListings = loaded;
                    List<ItemStack> built = new ArrayList<>(loaded.size());
                    for (Listing listing : loaded) built.add(buildListingItem(listing));
                    builtItems = List.copyOf(built);
                    build();
                    player.openInventory(inventory);
                }
            });
        });
    }

    private Inventory buildLoadingInventory() {
        Inventory inv = Bukkit.createInventory(this, ROWS * 9, Component.text("§8市场 — 加载中..."));
        ItemStack clock = new ItemStack(Material.CLOCK);
        ItemMeta m = clock.getItemMeta();
        if (m != null) {
            m.displayName(Component.text("§e正在加载挂单..."));
            clock.setItemMeta(m);
        }
        inv.setItem(22, clock);
        return inv;
    }

    private List<Listing> loadListings(UUID viewerUuid, boolean myOnly, boolean propertyOnly) {
        List<Listing> listings;
        if (myOnly) {
            listings = plugin.listingManager().getPlayerListings(viewerUuid);
            if (propertyOnly) listings = listings.stream().filter(Listing::isProperty).toList();
        } else if (propertyOnly) {
            listings = plugin.listingManager().getActiveListings("SELL", null).stream()
                    .filter(Listing::isProperty).toList();
        } else {
            listings = plugin.listingManager().getActiveListings("SELL", null).stream()
                    .filter(l -> !l.isProperty()).toList();
        }
        return List.copyOf(listings);
    }

    private void build() {
        String title = showPropertyOnly ? "🏠 商品房" : "💰 卖钱";
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8市场 — " + title + (showMyOnly ? " [我的]" : "") + " 第" + (page + 1) + "页"));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int listingIdx = start + i;
            if (listingIdx < builtItems.size()) {
                inventory.setItem(i, builtItems.get(listingIdx));
            }
        }

        // Navigation row
        if (page > 0)
            inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        if (!showPropertyOnly) {
            inventory.setItem(46, navButton(Material.BOOK, "§b玩家求购", "§7创建普通物品或精确 NBT 求购单", "§7手持匹配物品即可出售"));
        }
        if (!showPropertyOnly) {
            inventory.setItem(47, navButton(Material.CHEST_MINECART, "§6📤 一键出售背包",
                    "§7出售背包内所有官方可收购的物品",
                    "§7会解析潜影盒内容并保留盒体与未收购物品"));
            inventory.setItem(48, navButton(Material.HOPPER, "§2🏪 官方收购",
                    "§7点击出售手中物品给官方",
                    "§8管理员 Shift+左键管理收购内容"));
        }
        inventory.setItem(49, navButton(Material.CHEST,
                showMyOnly ? "§a📦 全部挂单" : "§d📦 我的挂单",
                showMyOnly ? "§7点击查看全部挂单" : "§7点击只看我的"));
        if (!showPropertyOnly) {
            inventory.setItem(50, navButton(Material.WRITABLE_BOOK, "§e➕ 上架物品",
                    "§7手持物品点击 → 自动定价上架"));
        }
        inventory.setItem(51, navButton(Material.OAK_DOOR,
                showPropertyOnly ? "§a💰 物品市场" : "§d🏠 商品房",
                showPropertyOnly ? "§7点击查看物品挂单" : "§7点击查看在售商品房",
                "§7（上架房屋用 /house sell <房屋ID> <价格>）"));
        if (!showPropertyOnly)
            inventory.setItem(52, navButton(Material.BOOKSHELF, "§b📋 官方兑换", "§7管理员定义的物品兑换规则"));
        if (start + PAGE_SIZE < currentListings.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));

        fillEmpty();
    }

    private void rebuildAndOpen(Player player) {
        build();
        player.openInventory(inventory);
    }

    // ---- Item builders ----

    private ItemStack buildListingItem(Listing listing) {
        if (listing.isProperty()) return buildPropertyListingItem(listing);
        ItemStack item = listing.toItemStack();
        item.setAmount(Math.min(listing.quantity(), Math.max(1, item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("卖家: " + listing.sellerName(), NamedTextColor.GRAY));
        lore.add(Component.text("库存: " + listing.quantity(), NamedTextColor.GRAY));
        lore.add(Component.text("单价: " + plugin.vaultHook().format(listing.unitPrice()), NamedTextColor.GOLD));
        lore.add(Component.text("总价: " + plugin.vaultHook().format(listing.totalPrice()), NamedTextColor.GREEN));
        lore.add(Component.text("§e§l左键预览 §7| §a§l右键购买", NamedTextColor.YELLOW));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** 商品房挂单原始 item_data 是空 AIR（房屋不是物品），用门图标 + 房屋详情 lore 渲染，不直接用 toItemStack()。 */
    private ItemStack buildPropertyListingItem(Listing listing) {
        ItemStack item = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String houseId = listing.assetRef();
        Map<String, Object> house = plugin.marketManager().getHouseInfo(houseId);
        List<Component> lore = new ArrayList<>();
        if (house != null) {
            meta.displayName(Component.text("§e🏠 " + String.valueOf(house.get("name")), NamedTextColor.YELLOW));
            lore.add(Component.text("房屋ID: " + houseId, NamedTextColor.GRAY));
            lore.add(Component.text("世界: " + house.get("world"), NamedTextColor.GRAY));
            lore.add(Component.text("范围: [" + house.get("x1") + "," + house.get("y1") + "," + house.get("z1") + "] - ["
                    + house.get("x2") + "," + house.get("y2") + "," + house.get("z2") + "]", NamedTextColor.GRAY));
        } else {
            meta.displayName(Component.text("§e🏠 商品房 " + houseId, NamedTextColor.YELLOW));
            lore.add(Component.text("§c房屋详情读取失败（房地产模块未加载？）", NamedTextColor.RED));
        }
        lore.add(Component.empty());
        lore.add(Component.text("卖家: " + listing.sellerName(), NamedTextColor.GRAY));
        lore.add(Component.text("价格: " + plugin.vaultHook().format(listing.unitPrice()), NamedTextColor.GOLD));
        lore.add(Component.text("§e§l左键查看详情 §7| §a§l右键购买", NamedTextColor.YELLOW));
        meta.lore(lore);
        item.setItemMeta(meta);
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
            if (!(event.getView().getTopInventory().getHolder() instanceof MarketMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= menu.inventory.getSize()) return;

            // Content slot (0-44) — listing
            if (slot >= 0 && slot < PAGE_SIZE) {
                int index = menu.page * PAGE_SIZE + slot;
                if (index < menu.currentListings.size()) {
                    Listing listing = menu.currentListings.get(index);
                    player.closeInventory();
                    if (event.isRightClick()) {
                        // Fast buy
                        plugin.marketManager().buyListing(player, listing.id(), listing.quantity());
                    } else {
                        new ListingPreviewMenu(plugin).open(player, listing, menu.showMyOnly);
                    }
                }
                return;
            }

            switch (slot) {
                case 45 -> {
                    if (menu.page > 0) {
                        menu.page--;
                        menu.rebuildAndOpen(player);
                    }
                }
                case 46 -> {
                    if (menu.showPropertyOnly) return;
                    player.closeInventory();
                    new PurchaseOrderMenu(menu.plugin).open(player);
                }
                case 47 -> { // 一键出售背包（商品房视图不显示此按钮）
                    if (menu.showPropertyOnly) return;
                    player.closeInventory();
                    new SellAllMenu(plugin).open(player);
                }
                case 48 -> { // Official buy（商品房视图不显示此按钮）
                    if (menu.showPropertyOnly) return;
                    player.closeInventory();
                    if (event.isShiftClick() && event.isLeftClick() && player.hasPermission("kseco.admin")) {
                        new OfficialBuyAdminGui(plugin).open(player);
                    } else {
                        plugin.officialBuyManager().buyFromPlayer(player);
                    }
                }
                case 49 -> { // Toggle my listings
                    player.closeInventory();
                    menu.open(player, !menu.showMyOnly, menu.showPropertyOnly);
                }
                case 50 -> { // Create listing → price input（商品房视图不显示此按钮）
                    if (menu.showPropertyOnly) return;
                    player.closeInventory();
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    if (hand.getType().isAir()) {
                        player.sendMessage("§c手中没有物品。手持物品再点击「上架物品」。");
                        return;
                    }
                    new PriceInputMenu(plugin, hand.clone()).open(player, hand.clone());
                }
                case 51 -> { // Toggle 商品房/物品 视图
                    player.closeInventory();
                    menu.open(player, menu.showMyOnly, !menu.showPropertyOnly);
                }
                case 52 -> { // Official exchange（商品房视图不显示此按钮）
                    if (menu.showPropertyOnly) return;
                    player.closeInventory();
                    new ExchangeGui(plugin).openPlayerView(player);
                }
                case 53 -> {
                    if ((menu.page + 1) * PAGE_SIZE < menu.currentListings.size()) {
                        menu.page++;
                        menu.rebuildAndOpen(player);
                    }
                }
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            // No cleanup needed
        }
    }
}
