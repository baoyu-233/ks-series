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

import java.util.*;

/**
 * ks-Eco 统一 GUI 主菜单。
 * 由 /eco gui 命令打开，聚合所有玩家经济功能入口。
 */
public final class EcoGuiMainMenu implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private static final int ROWS = 6;

    public EcoGuiMainMenu(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        build(player);
        player.openInventory(inventory);
    }

    /** 该功能是否对此玩家开放（kseco.admin 自动豁免）。 */
    private boolean open(Player player, String featureId) {
        return player.hasPermission("kseco.admin") || plugin.featureGate().isOpen(featureId);
    }

    private void build(Player player) {
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§9§lKS §8· §7经济面板"));

        // Row 1 (slots 10-16): banks, market, storage, exchange, trade, realestate, politics
        if (open(player, "bank")) {
            inventory.setItem(10, icon(Material.GOLD_INGOT, "§6🏦 我的银行",
                    "§7账户总览 · 存取款 · 贷款",
                    "§7浏览银行 · 创建银行 · 银行管理",
                    "", "§b▸ §7点击打开"));
        }
        if (open(player, "market")) {
            inventory.setItem(11, icon(Material.CHEST, "§e💰 市场",
                    "§7浏览物品挂单 · 买卖交易",
                    "§7上架物品 · 商品房",
                    "", "§b▸ §7点击打开"));
        }
        // 暂存箱：始终开放
        inventory.setItem(12, icon(Material.ENDER_CHEST, "§5📦 暂存箱",
                "§7领取退回/溢出的物品",
                "", "§b▸ §7点击打开"));
        if (open(player, "exchange")) {
            inventory.setItem(13, icon(Material.HOPPER, "§b🔄 官方兑换",
                    "§7管理员定义的多物品兑换规则",
                    "§7Shift+左键批量兑换 · 右键自定义数量",
                    "", "§b▸ §7点击打开"));
        }
        if (open(player, "trade")) {
            inventory.setItem(14, icon(Material.MINECART, "§a📦 物品物流",
                    "§7向在线玩家发送一组物品",
                    "", "§b▸ §7用法: /trade <玩家名>"));
        }
        if (open(player, "realestate")) {
            inventory.setItem(15, icon(Material.OAK_DOOR, "§6🏠 房地产",
                    "§7我的地块 · 商品房市场",
                    "§7（地图浏览请用网页版）",
                    "", "§b▸ §7点击打开"));
        }
        if (open(player, "politic")) {
            inventory.setItem(16, icon(Material.BOOK, "§5🏛 元老院",
                    "§7政治身份 · 保民官选举",
                    "§7提案列表 · 立法投票",
                    "", "§b▸ §7点击打开"));
        }

        // Row 2 (slots 19-25): enterprises, bidding, invites, tax, blindbox, ent-blindbox, limited-sale
        if (open(player, "enterprise")) {
            inventory.setItem(19, icon(Material.IRON_INGOT, "§f🏢 企业",
                    "§7我的企业 · 浏览所有企业",
                    "§7创建企业 · 成员管理",
                    "", "§b▸ §7点击打开"));
        }
        if (open(player, "bidding")) {
            inventory.setItem(20, icon(Material.PAPER, "§e📋 招投标",
                    "§7工程招标 · 采购招标",
                    "§7我的投标 · 发布评标",
                    "", "§b▸ §7点击打开"));
        }
        if (open(player, "invites")) {
            inventory.setItem(21, icon(Material.WRITABLE_BOOK, "§d🤝 合资邀请",
                    "§7待处理的银行/企业联合邀请",
                    "", "§b▸ §7点击打开"));
        }
        // 税收记录：始终开放
        inventory.setItem(22, icon(Material.MAP, "§c📊 税收记录",
                "§7查看我的纳税历史",
                "", "§b▸ §7点击打开"));
        if (open(player, "blindbox")) {
            inventory.setItem(23, icon(Material.SHULKER_BOX, "§d🎁 盲盒",
                    "§7浏览盲盒池 · 单抽/十连抽",
                    "§7保底查询 · 抽取记录",
                    "", "§b▸ §7点击打开"));
        }
        if (open(player, "ent_blindbox")) {
            inventory.setItem(24, icon(Material.PURPLE_SHULKER_BOX, "§5🎪 企业盲盒",
                    "§7企业专属盲盒池",
                    "§7企业等级 · 企业账户抽卡",
                    "", "§b▸ §7点击打开"));
        }
        if (open(player, "limited_sale")) {
            inventory.setItem(25, icon(Material.CLOCK, "§6⏳ 限时直售",
                    "§7限时商品 · 直接购买",
                    "§7左键预览完整 NBT · 右键购买",
                    "", "§b▸ §7点击打开"));
        }

        // Row 3: 银行附属转账使用独立门控；银行本身关闭时仍可单独开放。
        if (open(player, "transfer")) {
            inventory.setItem(28, icon(Material.PAPER, "§a💸 玩家转账",
                    "§7向其他玩家转账 · 自动计算税费",
                    "§7免税额以内不收转账税",
                    "", "§b▸ §7点击打开"));
        }
        if (open(player, "compensation")) {
            inventory.setItem(29, icon(Material.NETHER_STAR, "§b✦ 服务器补偿",
                    "§7领取管理员发布的全服特殊物品补偿",
                    "§7每份补偿每人限领一次，过期自动关闭",
                    "", "§b▶ §7点击打开"));
        }
        // 主任务 MO（只读进度，核心侧 MajorOrderManager）
        inventory.setItem(31, buildMoItem());

        // Top center: player head with live balance
        inventory.setItem(4, playerHead(player));

        // Bottom bar（始终开放）
        inventory.setItem(45, icon(Material.SUNFLOWER, "§6💰 余额: §e" + safeBalance(player),
                "§7点击刷新面板与余额"));
        inventory.setItem(49, icon(Material.BARRIER, "§c✕ 关闭", "§7关闭面板"));
        inventory.setItem(53, icon(Material.KNOWLEDGE_BOOK, "§a🌐 Web 面板",
                "§7点击获取网页版链接",
                "§7（含地图浏览、3D查看器等高级功能）"));

        drawFrame();
        fillEmpty();
    }

    /** 主任务（MO）只读进度项：展示进行中的城邦主目标与进度条。 */
    private ItemStack buildMoItem() {
        List<String> lore = new ArrayList<>();
        try {
            var orders = plugin.majorOrderManager().listOrders(false);
            int shown = 0;
            for (var o : orders) {
                if (shown >= 5) { lore.add("§8… 更多请见网页端"); break; }
                String status = String.valueOf(o.getOrDefault("status", ""));
                double pct = o.get("progressPct") instanceof Number n ? n.doubleValue() : 0;
                int bars = (int) Math.round(pct * 10);
                StringBuilder bar = new StringBuilder("§a");
                for (int i = 0; i < 10; i++) {
                    if (i == bars) bar.append("§8");
                    bar.append("▮");
                }
                lore.add("§f" + o.getOrDefault("title", "?")
                        + " §7[" + ("COMPLETED".equals(status) ? "§a已完成" : "§e进行中") + "§7]");
                lore.add(" " + bar + " §7" + String.format(java.util.Locale.ROOT, "%.1f%%", pct * 100));
                shown++;
            }
            if (shown == 0) lore.add("§7当前没有进行中的主任务");
        } catch (Exception e) {
            lore.add("§7主任务数据暂不可用");
        }
        lore.add("");
        lore.add("§b▸ §7点击刷新进度");
        return icon(Material.LODESTONE, "§b🎯 城邦主任务 (MO)", lore.toArray(new String[0]));
    }

    private String safeBalance(Player player) {
        try {
            return plugin.vaultHook().format(plugin.vaultHook().getBalance(player));
        } catch (Exception e) {
            return "§8-";
        }
    }

    private ItemStack playerHead(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            skull.setOwningPlayer(player);
            skull.displayName(Component.text("§b§l" + player.getName()));
            skull.lore(List.of(
                    Component.text("§7余额: §e" + safeBalance(player)),
                    Component.text("§8欢迎使用 KS 经济面板")));
            head.setItemMeta(skull);
        }
        return head;
    }

    private ItemStack icon(Material mat, String name, String... lore) {
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

    /** 青/紫交替的顶部与底部边框，呼应 Web 面板的渐变主题色。 */
    private void drawFrame() {
        ItemStack cyan = pane(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        ItemStack purple = pane(Material.PURPLE_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, (i % 2 == 0) ? cyan.clone() : purple.clone());
            int bottom = (ROWS - 1) * 9 + i;
            if (inventory.getItem(bottom) == null) inventory.setItem(bottom, (i % 2 == 0) ? purple.clone() : cyan.clone());
        }
    }

    private ItemStack pane(Material mat) {
        ItemStack glass = new ItemStack(mat);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) { gm.displayName(Component.text(" ")); glass.setItemMeta(gm); }
        return glass;
    }

    private void fillEmpty() {
        ItemStack glass = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < ROWS * 9; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
        }
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }

    // ---- Listener ----

    public static class Listener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        /** 槛位 -> feature key，仅含受门控管理的槛位（暂存箱/税收/余额/关闭/Web 始终开放，不在此表）。 */
        private static final Map<Integer, String> SLOT_FEATURE = Map.ofEntries(
                Map.entry(10, "bank"),
                Map.entry(28, "transfer"),
                Map.entry(11, "market"),
                Map.entry(13, "exchange"),
                Map.entry(14, "trade"),
                Map.entry(15, "realestate"),
                Map.entry(16, "politic"),
                Map.entry(19, "enterprise"),
                Map.entry(20, "bidding"),
                Map.entry(21, "invites"),
                Map.entry(23, "blindbox"),
                Map.entry(24, "ent_blindbox"),
                Map.entry(25, "limited_sale"),
                Map.entry(29, "compensation")
        );

        public Listener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof EcoGuiMainMenu menu)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= menu.getInventory().getSize()) return;
            String feature = SLOT_FEATURE.get(slot);
            if (feature != null && !player.hasPermission("kseco.admin") && !plugin.featureGate().isOpen(feature)) {
                player.sendMessage("§c该功能暂未开放。");
                return;
            }
            // 只有真正的功能槽位才关闭菜单；点边框/填充玻璃不应关闭
            if (feature == null && slot != 12 && slot != 22 && slot != 31
                    && slot != 45 && slot != 49 && slot != 53) return;
            if (slot == 45) { // 刷新面板与余额，不关闭
                menu.open(player);
                return;
            }
            player.closeInventory();

            switch (slot) {
                case 10 -> new BankGui(plugin).open(player);
                case 11 -> new MarketMenu(plugin).open(player);
                case 12 -> new StorageMenu(plugin, player).open();
                case 13 -> new ExchangeGui(plugin).openPlayerView(player);
                case 14 -> player.sendMessage("§e用法: /trade <玩家名>");
                case 15 -> new RealEstateGui(plugin).open(player);
                case 16 -> new PoliticGui(plugin).open(player);
                case 19 -> new EnterpriseGui(plugin).open(player);
                case 20 -> new BiddingGui(plugin).open(player);
                case 21 -> new InvitesGui(plugin).open(player);
                case 22 -> new TaxGui(plugin).open(player);
                case 23 -> new BlindBoxGui(plugin).open(player);
                case 24 -> new EntBlindBoxGui(plugin).open(player);
                case 25 -> new LimitedSaleGui(plugin).open(player);
                case 28 -> new TransferGui(plugin).open(player);
                case 29 -> new CompensationGui(plugin).open(player);
                case 31, 45 -> new EcoGuiMainMenu(plugin).open(player); // MO 进度 / 余额刷新
                case 49 -> { /* close */ }
                case 53 -> handlePlayerWebCommand(player);
            }
        }

        private void handlePlayerWebCommand(Player player) {
            if (!plugin.bridge().isPluginRouteEnabled("ks-eco")) {
                player.sendMessage("§c经济 Web 面板未启用。");
                return;
            }
            String link = plugin.bridge().createWebLink(player, false, "/ks-Eco/player");
            player.sendMessage("§a经济 Web 玩家面板: " + link);
            player.sendMessage(net.kyori.adventure.text.Component.text("§e点击打开玩家经济面板")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link)));
        }
    }
}
