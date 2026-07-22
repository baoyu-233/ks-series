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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;
import org.kseco.LimitedSaleManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LimitedSaleAdminGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private int view = 0; // 0=list, 1=edit
    private int page = 0;
    private String selectedSaleId;
    private final List<LimitedSaleManager.SaleItem> sales = new ArrayList<>();

    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 45;

    private record PendingInput(String saleId, String field, LimitedSaleAdminGui gui) {}
    private static final Map<UUID, PendingInput> pendingInput = new ConcurrentHashMap<>();
    private static final Map<UUID, LimitedSaleAdminGui> pendingDrop = new ConcurrentHashMap<>();

    public LimitedSaleAdminGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage(ChatColor.RED + "权限不足，需要 kseco.admin");
            return;
        }
        view = 0;
        page = 0;
        selectedSaleId = null;
        loadData();
        build();
        player.openInventory(inventory);
        pendingDrop.put(player.getUniqueId(), this);
    }

    private void loadData() {
        sales.clear();
        sales.addAll(plugin.limitedSaleManager().listSales(true));
    }

    private void build() {
        if (view == 0) buildList();
        else buildEdit();
        fillEmpty();
    }

    private void buildList() {
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8限时直售管理 第" + (page + 1) + "页"));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < sales.size(); i++) {
            inventory.setItem(i, adminSaleIcon(sales.get(start + i)));
        }
        if (sales.isEmpty()) {
            inventory.setItem(22, navButton(Material.PAPER, "§7暂无直售商品", "§7手持物品点下方按钮添加"));
        }

        if (page > 0) inventory.setItem(45, navButton(Material.ARROW, "§a上一页"));
        inventory.setItem(46, navButton(Material.EMERALD, "§a新增手持物品",
                "§7会保存完整 NBT 和当前堆叠数量",
                "§7默认价格 100，总量/个人限购不限，24小时后结束",
                "§7也可以按 Q 丢入物品创建"));
        inventory.setItem(47, navButton(Material.ENDER_CHEST, "§d新增限时盲盒",
                "§7输入现有盲盒池 ID 创建",
                "§7总量=最多可抽次数，个人限购=每人最多抽次数",
                "§7默认价格使用盲盒池价格，24小时后结束"));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c关闭"));
        if ((page + 1) * PAGE_SIZE < sales.size()) inventory.setItem(53, navButton(Material.ARROW, "§a下一页"));
    }

    private void buildEdit() {
        LimitedSaleManager.SaleItem sale = plugin.limitedSaleManager().getSale(selectedSaleId);
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8编辑直售 - " + (sale == null ? "?" : sale.name())));

        if (sale == null) {
            inventory.setItem(22, navButton(Material.BARRIER, "§c商品不存在"));
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c返回列表"));
            return;
        }

        inventory.setItem(4, exactItemPreview(sale));
        inventory.setItem(10, navButton(Material.NAME_TAG, "§f名称: §b" + sale.name(),
                "§7点击后在聊天栏输入新名称"));
        inventory.setItem(11, navButton(Material.GOLD_INGOT, "§6价格: §e" + plugin.limitedSaleManager().formatMoney(sale.price()),
                "§7左键 +100，右键 -100",
                "§7Shift+左键 +1000，Shift+右键 -1000"));
        inventory.setItem(12, navButton(Material.CHEST, "§e总量: §f" + plugin.limitedSaleManager().stockText(sale.totalStock()),
                "§7左键 +1，右键 -1",
                "§7Shift+左键 +10，Shift+右键 -10",
                "§7当前已售: §f" + sale.sold()));
        if (sale.blindBoxSale()) {
            inventory.setItem(13, navButton(Material.ENDER_CHEST, "§d绑定盲盒池: §f" + sale.blindBoxPoolId(),
                    "§7点击后在聊天栏输入新的盲盒池 ID",
                    "§7购买时会按限时价格抽这个池"));
        } else {
            inventory.setItem(13, navButton(Material.ITEM_FRAME, "§b替换物品",
                    "§7手持新物品点击这里替换",
                    "§7也可以按 Q 丢入物品替换"));
        }
        inventory.setItem(14, navButton(Material.PLAYER_HEAD, "§d个人限购: §f" + plugin.limitedSaleManager().limitText(sale.perPlayerLimit()),
                "§7左键 +1，右键 -1",
                "§7Shift+左键 +10，Shift+右键 -10",
                "§7-1 表示不限购"));
        inventory.setItem(15, navButton(Material.LIME_DYE, "§a开售时间: §f" + plugin.limitedSaleManager().formatTime(sale.startsAt()),
                "§7左键: 设为现在",
                "§7右键: 不限制开始时间"));
        inventory.setItem(16, navButton(Material.CLOCK, "§6下次刷新: §f" + plugin.limitedSaleManager().nextRefreshText(sale),
                "§7左键 +1小时，右键 -1小时",
                "§7Shift+左键 +1天，Shift+右键 -1天",
                "§7倒计时: §f" + plugin.limitedSaleManager().refreshCountdownText(sale)));

        inventory.setItem(19, navButton(Material.WRITABLE_BOOK, "§e精确设置价格", "§7点击后聊天栏输入数字"));
        inventory.setItem(20, navButton(Material.BARREL, "§e精确设置总量", "§7输入 -1 表示不限总量"));
        inventory.setItem(21, navButton(Material.PAPER, "§e精确设置个人限购", "§7输入 -1 表示不限购"));
        inventory.setItem(22, navButton(Material.COMPASS, "§e设置刷新周期",
                "§7输入 1h / 6h / 3d",
                "§7输入 0 表示暂无自动刷新"));
        inventory.setItem(23, navButton(sale.enabled() ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                sale.enabled() ? "§a已启用" : "§c已停用",
                "§7点击切换启用状态"));
        inventory.setItem(24, sale.blindBoxSale()
                ? navButton(Material.WRITABLE_BOOK, "§d精确设置盲盒池", "§7输入现有盲盒池 ID")
                : navButton(Material.HOPPER, "§b从手持物品替换",
                "§7和上方替换按钮一样，会保存完整 NBT"));
        inventory.setItem(25, navButton(Material.BARRIER, "§c删除此商品",
                "§7中键删除",
                "§8购买日志会保留，库存记录会删除"));
        if (!sale.blindBoxSale()) {
            inventory.setItem(26, navButton(sale.boxEnabled() ? Material.LIME_SHULKER_BOX : Material.GRAY_SHULKER_BOX,
                    sale.boxEnabled() ? "§a整盒购买已启用" : "§7整盒购买未启用",
                    "§7一盒装入 27 份当前保存的物品组",
                    "§7当前每份: §f" + sale.item().getAmount() + " 个",
                    "§7点击切换（新商品默认关闭）"));
            inventory.setItem(27, navButton(Material.GOLD_BLOCK,
                    "§6整盒价格: §e" + plugin.limitedSaleManager().formatMoney(sale.boxPrice()),
                    "§7左键: 按单份价 × 27 重新生成",
                    "§7右键: 在聊天栏精确输入盒价"));
        }

        inventory.setItem(31, statusIcon(sale));
        inventory.setItem(49, navButton(Material.OAK_DOOR, "§c返回列表"));
    }

    private ItemStack adminSaleIcon(LimitedSaleManager.SaleItem sale) {
        ItemStack icon = sale.item().clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        meta.displayName(Component.text((sale.enabled() ? (sale.blindBoxSale() ? "§d🎁 " : "§e⏳ ") : "§7⏳ ") + sale.name()));
        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        if (!lore.isEmpty()) lore.add(Component.empty());
        appendAdminLines(lore, sale);
        lore.add(Component.empty());
        lore.add(Component.text("§a左键编辑", NamedTextColor.GREEN));
        lore.add(Component.text("§e右键启用/停用", NamedTextColor.YELLOW));
        lore.add(Component.text("§c中键删除", NamedTextColor.RED));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack exactItemPreview(LimitedSaleManager.SaleItem sale) {
        ItemStack item = sale.item().clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text(sale.blindBoxSale() ? "§8上方为限时盲盒展示图标" : "§8上方为实际售卖物品预览", NamedTextColor.DARK_GRAY));
            lore.add(Component.text(sale.blindBoxSale() ? "§8购买时会抽取绑定盲盒池" : "§8购买时会复制这一份 ItemStack", NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack statusIcon(LimitedSaleManager.SaleItem sale) {
        ItemStack item = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text("§b当前配置"));
        List<Component> lore = new ArrayList<>();
        appendAdminLines(lore, sale);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void appendAdminLines(List<Component> lore, LimitedSaleManager.SaleItem sale) {
        LimitedSaleManager manager = plugin.limitedSaleManager();
        lore.add(Component.text("ID: §8" + sale.id(), NamedTextColor.DARK_GRAY));
        lore.add(Component.text("类型: §f" + (sale.blindBoxSale() ? "限时盲盒" : "直售商品"), NamedTextColor.GRAY));
        if (sale.blindBoxSale()) lore.add(Component.text("绑定池: §f" + sale.blindBoxPoolId(), NamedTextColor.GRAY));
        lore.add(Component.text("价格: §e" + manager.formatMoney(sale.price()), NamedTextColor.GRAY));
        if (!sale.blindBoxSale()) {
            lore.add(Component.text("整盒购买: " + (sale.boxEnabled() ? "§a启用" : "§7关闭")
                    + " §7/ 盒价 §e" + manager.formatMoney(sale.boxPrice()), NamedTextColor.GRAY));
        }
        lore.add(Component.text((sale.blindBoxSale() ? "总可抽: §f" : "总量: §f") + manager.stockText(sale.totalStock())
                + (sale.blindBoxSale() ? " §7/ 已抽 §f" : " §7/ 已售 §f") + sale.sold()
                + (sale.unlimitedStock() ? "" : " §7/ 剩余 §f" + sale.remainingStock()), NamedTextColor.GRAY));
        lore.add(Component.text((sale.blindBoxSale() ? "每人最多: §f" : "个人限购: §f") + manager.limitText(sale.perPlayerLimit()), NamedTextColor.GRAY));
        lore.add(Component.text("开售: §f" + manager.formatTime(sale.startsAt()), NamedTextColor.GRAY));
        lore.add(Component.text("下次刷新: §f" + manager.nextRefreshText(sale), NamedTextColor.GRAY));
        lore.add(Component.text("刷新倒计时: §f" + manager.refreshCountdownText(sale), NamedTextColor.GRAY));
        lore.add(Component.text("状态: §f" + manager.statusText(sale), NamedTextColor.GRAY));
    }

    private void createFromItem(Player player, ItemStack source) {
        if (source == null || source.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "请先手持要直售的物品");
            return;
        }
        long now = plugin.limitedSaleManager().now();
        String id = plugin.limitedSaleManager().addSale(source, plugin.limitedSaleManager().itemName(source),
                100.0, -1, -1, now, now + 86400);
        if (id == null) {
            player.sendMessage(ChatColor.RED + "创建失败");
            return;
        }
        selectedSaleId = id;
        view = 1;
        page = 0;
        loadData();
        build();
        player.openInventory(inventory);
        pendingDrop.put(player.getUniqueId(), this);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.8f);
        player.sendMessage(ChatColor.GREEN + "已创建限时直售商品，进入编辑界面");
    }

    private void askCreateBlindBox(Player player) {
        pendingInput.put(player.getUniqueId(), new PendingInput("", "createBlindBox", this));
        player.closeInventory();
        player.sendMessage(ChatColor.GREEN + "请输入要绑定的盲盒池 ID" + ChatColor.GRAY + "，输入 cancel 取消");
    }

    private boolean createBlindBoxSale(Player player, String poolId) {
        if (poolId == null || poolId.isBlank()) return false;
        var pool = plugin.blindBoxManager().getPool(poolId.trim());
        if (pool == null) {
            player.sendMessage(ChatColor.RED + "盲盒池不存在: " + poolId);
            return false;
        }
        // A pool attached here is intended for the event-only entry by default.
        plugin.blindBoxManager().setLimitedOnly(poolId.trim(), true);
        long now = plugin.limitedSaleManager().now();
        double price = pool.get("price") instanceof Number n ? n.doubleValue() : 100.0;
        String name = String.valueOf(pool.getOrDefault("name", poolId)) + " 限时盲盒";
        String id = plugin.limitedSaleManager().addBlindBoxSale(poolId.trim(), name, price, -1, -1, now, now + 86400);
        if (id == null) return false;
        selectedSaleId = id;
        view = 1;
        page = 0;
        loadData();
        build();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.8f);
        player.sendMessage(ChatColor.GREEN + "已创建限时盲盒，进入编辑界面");
        return true;
    }

    private void replaceFromItem(Player player, ItemStack source) {
        if (source == null || source.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "请先手持要替换的物品");
            return;
        }
        if (plugin.limitedSaleManager().replaceItem(selectedSaleId, source)) {
            player.sendMessage(ChatColor.GREEN + "物品已替换");
            reloadEdit(player);
        } else {
            player.sendMessage(ChatColor.RED + "替换失败");
        }
    }

    private void reloadEdit(Player player) {
        loadData();
        build();
        player.openInventory(inventory);
        pendingDrop.put(player.getUniqueId(), this);
    }

    private void askInput(Player player, String field, String prompt) {
        pendingInput.put(player.getUniqueId(), new PendingInput(selectedSaleId, field, this));
        player.closeInventory();
        player.sendMessage(ChatColor.GREEN + prompt + ChatColor.GRAY + "，输入 cancel 取消");
    }

    private int adjustLimitedInt(int current, int delta) {
        if (current < 0 && delta > 0) return Math.max(1, delta);
        return Math.max(-1, current + delta);
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
            if (!(event.getView().getTopInventory().getHolder() instanceof LimitedSaleAdminGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (!player.hasPermission("kseco.admin")) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;

            if (gui.view == 0) {
                if (slot >= 0 && slot < PAGE_SIZE) {
                    int index = gui.page * PAGE_SIZE + slot;
                    if (index < gui.sales.size()) {
                        LimitedSaleManager.SaleItem sale = gui.sales.get(index);
                        if (event.getClick() == ClickType.MIDDLE) {
                            plugin.limitedSaleManager().deleteSale(sale.id());
                            player.sendMessage(ChatColor.GREEN + "已删除直售商品: " + sale.name());
                            gui.loadData();
                            gui.build();
                            player.openInventory(gui.getInventory());
                            pendingDrop.put(player.getUniqueId(), gui);
                        } else if (event.isRightClick()) {
                            plugin.limitedSaleManager().setEnabled(sale.id(), !sale.enabled());
                            gui.loadData();
                            gui.build();
                            player.openInventory(gui.getInventory());
                            pendingDrop.put(player.getUniqueId(), gui);
                        } else {
                            gui.selectedSaleId = sale.id();
                            gui.view = 1;
                            gui.build();
                            player.openInventory(gui.getInventory());
                            pendingDrop.put(player.getUniqueId(), gui);
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
                            pendingDrop.put(player.getUniqueId(), gui);
                        }
                    }
                    case 46 -> gui.createFromItem(player, player.getInventory().getItemInMainHand());
                    case 47 -> gui.askCreateBlindBox(player);
                    case 49 -> player.closeInventory();
                    case 53 -> {
                        if ((gui.page + 1) * PAGE_SIZE < gui.sales.size()) {
                            gui.page++;
                            gui.build();
                            player.openInventory(gui.getInventory());
                            pendingDrop.put(player.getUniqueId(), gui);
                        }
                    }
                }
                return;
            }

            LimitedSaleManager.SaleItem sale = plugin.limitedSaleManager().getSale(gui.selectedSaleId);
            if (sale == null) {
                gui.view = 0;
                gui.loadData();
                gui.build();
                player.openInventory(gui.getInventory());
                pendingDrop.put(player.getUniqueId(), gui);
                return;
            }

            switch (slot) {
                case 10 -> gui.askInput(player, "name", "请输入新的商品名称");
                case 11 -> {
                    double delta = event.isShiftClick() ? (event.isLeftClick() ? 1000 : -1000)
                            : (event.isLeftClick() ? 100 : -100);
                    plugin.limitedSaleManager().setPrice(sale.id(), Math.max(0.01, sale.price() + delta));
                    gui.reloadEdit(player);
                }
                case 12 -> {
                    int delta = event.isShiftClick() ? (event.isLeftClick() ? 10 : -10)
                            : (event.isLeftClick() ? 1 : -1);
                    plugin.limitedSaleManager().setTotalStock(sale.id(), gui.adjustLimitedInt(sale.totalStock(), delta));
                    gui.reloadEdit(player);
                }
                case 13, 24 -> {
                    if (sale.blindBoxSale()) {
                        gui.askInput(player, "pool", "请输入新的盲盒池 ID");
                    } else {
                        gui.replaceFromItem(player, player.getInventory().getItemInMainHand());
                    }
                }
                case 14 -> {
                    int delta = event.isShiftClick() ? (event.isLeftClick() ? 10 : -10)
                            : (event.isLeftClick() ? 1 : -1);
                    plugin.limitedSaleManager().setPerPlayerLimit(sale.id(), gui.adjustLimitedInt(sale.perPlayerLimit(), delta));
                    gui.reloadEdit(player);
                }
                case 15 -> {
                    plugin.limitedSaleManager().setStartsAt(sale.id(), event.isRightClick() ? 0 : plugin.limitedSaleManager().now());
                    gui.reloadEdit(player);
                }
                case 16 -> {
                    long delta = event.isShiftClick() ? 86400L : 3600L;
                    if (event.isRightClick()) delta = -delta;
                    plugin.limitedSaleManager().shiftEndsAt(sale.id(), delta);
                    gui.reloadEdit(player);
                }
                case 19 -> gui.askInput(player, "price", "请输入价格，例如 1000");
                case 20 -> gui.askInput(player, "stock", "请输入总量，-1 表示不限");
                case 21 -> gui.askInput(player, "limit", "请输入每个玩家最大购买量，-1 表示不限");
                case 22 -> gui.askInput(player, "duration", "请输入刷新周期，例如 1h / 6h / 3d / 0");
                case 23 -> {
                    plugin.limitedSaleManager().setEnabled(sale.id(), !sale.enabled());
                    gui.reloadEdit(player);
                }
                case 25 -> {
                    if (event.getClick() == ClickType.MIDDLE) {
                        plugin.limitedSaleManager().deleteSale(sale.id());
                        player.sendMessage(ChatColor.GREEN + "已删除直售商品: " + sale.name());
                        gui.view = 0;
                        gui.selectedSaleId = null;
                        gui.loadData();
                        gui.build();
                        player.openInventory(gui.getInventory());
                        pendingDrop.put(player.getUniqueId(), gui);
                    }
                }
                case 26 -> {
                    if (!sale.blindBoxSale()) {
                        boolean ok = plugin.limitedSaleManager().setBoxEnabled(sale.id(), !sale.boxEnabled());
                        player.sendMessage(ok ? ChatColor.GREEN + "整盒购买开关已更新"
                                : ChatColor.RED + "无法启用：物品必须是一组可堆叠物品，且盒价有效");
                        gui.reloadEdit(player);
                    }
                }
                case 27 -> {
                    if (!sale.blindBoxSale()) {
                        if (event.isLeftClick()) {
                            plugin.limitedSaleManager().regenerateBoxPrice(sale.id());
                            gui.reloadEdit(player);
                        } else if (event.isRightClick()) {
                            gui.askInput(player, "boxPrice", "请输入一整盒的价格");
                        }
                    }
                }
                case 49 -> {
                    gui.view = 0;
                    gui.selectedSaleId = null;
                    gui.loadData();
                    gui.build();
                    player.openInventory(gui.getInventory());
                    pendingDrop.put(player.getUniqueId(), gui);
                }
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            if (!(event.getInventory().getHolder() instanceof LimitedSaleAdminGui)) return;
            if (event.getPlayer() instanceof Player player) pendingDrop.remove(player.getUniqueId());
        }
    }

    public static final class DropListener implements org.bukkit.event.Listener {
        private final KsEco plugin;

        public DropListener(KsEco plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onDrop(PlayerDropItemEvent event) {
            Player player = event.getPlayer();
            LimitedSaleAdminGui gui = pendingDrop.get(player.getUniqueId());
            if (gui == null || !player.hasPermission("kseco.admin")) return;
            ItemStack dropped = event.getItemDrop().getItemStack();
            if (dropped == null || dropped.getType().isAir()) return;
            event.setCancelled(true);

            if (gui.view == 1 && gui.selectedSaleId != null) {
                LimitedSaleManager.SaleItem sale = plugin.limitedSaleManager().getSale(gui.selectedSaleId);
                if (sale != null && sale.blindBoxSale()) {
                    player.sendMessage(ChatColor.RED + "限时盲盒不能用丢物品替换，请在编辑页修改绑定池");
                    return;
                }
                plugin.limitedSaleManager().replaceItem(gui.selectedSaleId, dropped);
                player.sendMessage(ChatColor.GREEN + "已用丢入物品替换直售商品");
                plugin.scheduler().runEntity(player, () -> gui.reloadEdit(player), () -> { });
            } else {
                plugin.scheduler().runEntity(player, () -> gui.createFromItem(player, dropped), () -> { });
            }
        }
    }

    public static final class ChatListener implements org.bukkit.event.Listener {
        private final KsEco plugin;

        public ChatListener(KsEco plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            pendingInput.remove(playerId);
            pendingDrop.remove(playerId);
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            if (!pendingInput.containsKey(playerId)) return;
            event.setCancelled(true);
            String msg = event.getMessage().trim();
            plugin.scheduler().runPlayer(playerId, () -> {
                PendingInput pending = pendingInput.remove(playerId);
                Player player = Bukkit.getPlayer(playerId);
                if (pending == null || player == null) return;
                if (!player.hasPermission("kseco.admin")) {
                    player.sendMessage(ChatColor.RED + "权限已失效，输入已取消");
                    return;
                }
                if (msg.equalsIgnoreCase("cancel")) {
                    player.sendMessage(ChatColor.RED + "已取消输入");
                    pending.gui().reloadEdit(player);
                    return;
                }
                boolean ok = applyInput(player, pending, msg);
                if (!ok) {
                    pendingInput.put(playerId, pending);
                    player.sendMessage(ChatColor.RED + "输入无效，请重新输入或输入 cancel 取消");
                    return;
                }
                player.sendMessage(ChatColor.GREEN + "已更新配置");
                pending.gui().reloadEdit(player);
            });
        }

        private boolean applyInput(Player player, PendingInput pending, String msg) {
            try {
                return switch (pending.field()) {
                    case "createBlindBox" -> pending.gui().createBlindBoxSale(player, msg);
                    case "name" -> plugin.limitedSaleManager().setName(pending.saleId(), msg);
                    case "pool" -> plugin.limitedSaleManager().setBlindBoxPool(pending.saleId(), msg);
                    case "price" -> plugin.limitedSaleManager().setPrice(pending.saleId(), Double.parseDouble(msg));
                    case "boxPrice" -> plugin.limitedSaleManager().setBoxPrice(pending.saleId(), Double.parseDouble(msg));
                    case "stock" -> plugin.limitedSaleManager().setTotalStock(pending.saleId(), Integer.parseInt(msg));
                    case "limit" -> plugin.limitedSaleManager().setPerPlayerLimit(pending.saleId(), Integer.parseInt(msg));
                    case "duration" -> {
                        long seconds = parseDurationSeconds(msg);
                        if (seconds < 0) yield false;
                        long endsAt = seconds == 0 ? 0 : plugin.limitedSaleManager().now() + seconds;
                        yield plugin.limitedSaleManager().setEndsAt(pending.saleId(), endsAt);
                    }
                    default -> false;
                };
            } catch (Exception e) {
                return false;
            }
        }

        private long parseDurationSeconds(String raw) {
            String s = raw.trim().toLowerCase();
            if (s.equals("0") || s.equals("none") || s.equals("no") || s.equals("不限")) return 0;
            double value;
            long unit;
            if (s.endsWith("d")) {
                unit = 86400L;
                value = Double.parseDouble(s.substring(0, s.length() - 1));
            } else if (s.endsWith("h")) {
                unit = 3600L;
                value = Double.parseDouble(s.substring(0, s.length() - 1));
            } else if (s.endsWith("m")) {
                unit = 60L;
                value = Double.parseDouble(s.substring(0, s.length() - 1));
            } else if (s.endsWith("s")) {
                unit = 1L;
                value = Double.parseDouble(s.substring(0, s.length() - 1));
            } else {
                unit = 3600L;
                value = Double.parseDouble(s);
            }
            if (value < 0) return -1;
            return Math.round(value * unit);
        }
    }
}
