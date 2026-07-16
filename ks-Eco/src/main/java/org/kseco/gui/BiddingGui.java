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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.kseco.KsEco;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 招投标 GUI — 工程招标、采购招标、我的投标、发布/评标。
 * SQL 读 + 反射写（biddingManager publish/award）。
 */
public final class BiddingGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private int view = 0; // 0=projects, 1=procurement, 2=myBids, 3=publish, 4=procurement-bid-select
    private int page = 0;
    private final List<Map<String, Object>> items = new ArrayList<>();
    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 36;

    // Chat input for publishing
    record PendingPublish(int step, String title, double budget, int days, double prepay, double penalty, double deposit) {}
    static final Map<UUID, PendingPublish> pendingPublish = new ConcurrentHashMap<>();
    // Chat input for bidding
    record PendingBid(String projectId, String type) {} // type: "project" or "procurement"
    record PendingPublishProc(int step, String entId, String title, int quantity, double budget) {}

    private String selectedAwardId = null; // 正在选标的采购ID
    private final org.kseco.EnterprisePermissionService permService = new org.kseco.EnterprisePermissionService();
    static final Map<UUID, PendingBid> pendingBid = new ConcurrentHashMap<>();
    static final Map<UUID, PendingPublishProc> pendingPublishProc = new ConcurrentHashMap<>();

    public BiddingGui(KsEco plugin) {
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
        String uuid = player.getUniqueId().toString().replace("'", "''");

        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             Statement st = conn.createStatement()) {

            switch (view) {
                case 0 -> {
                    ResultSet rs = st.executeQuery(
                            "SELECT * FROM ks_ent_projects WHERE status IN ('OPEN','PENDING_DEPOSIT') ORDER BY created_at DESC LIMIT 200");
                    while (rs.next()) items.add(projectRow(rs));
                }
                case 1 -> {
                    ResultSet rs = st.executeQuery(
                            "SELECT p.*, e.name AS enterprise_name FROM ks_ent_procurements p LEFT JOIN ks_ent_enterprises e ON p.enterprise_id=e.id WHERE p.status='OPEN' ORDER BY p.created_at DESC LIMIT 200");
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("enterprise_id", rs.getString("enterprise_id"));
                        row.put("enterprise_name", rs.getString("enterprise_name"));
                        row.put("title", rs.getString("title"));
                        row.put("item_desc", rs.getString("item_desc"));
                        row.put("quantity", rs.getInt("quantity"));
                        row.put("budget", rs.getDouble("budget"));
                        row.put("status", rs.getString("status"));
                        row.put("created_at", rs.getLong("created_at"));
                        items.add(row);
                    }
                }
                case 2 -> {
                    // My project bids — 两条 SQL 使用独立 Statement，防止 SQLITE_BUSY_SNAPSHOT
                    try (Statement st2 = conn.createStatement()) {
                        ResultSet rs = st.executeQuery(
                                "SELECT b.*, p.title AS project_title FROM ks_ent_bids b JOIN ks_ent_projects p ON b.project_id=p.id WHERE b.bidder_uuid='"
                                        + uuid + "' OR b.enterprise_id IN (SELECT enterprise_id FROM ks_ent_members WHERE player_uuid='"
                                        + uuid + "') ORDER BY b.submitted_at DESC LIMIT 100");
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("type", "project_bid");
                            row.put("id", rs.getString("id"));
                            row.put("project_id", rs.getString("project_id"));
                            row.put("project_title", rs.getString("project_title"));
                            row.put("bid_amount", rs.getDouble("bid_amount"));
                            row.put("status", rs.getString("status"));
                            row.put("submitted_at", rs.getLong("submitted_at"));
                            items.add(row);
                        }
                        // My procurement bids — independent Statement
                        ResultSet rs2 = st2.executeQuery(
                                "SELECT pb.*, p.title AS procurement_title FROM ks_ent_procurement_bids pb JOIN ks_ent_procurements p ON pb.procurement_id=p.id WHERE pb.bidder_uuid='"
                                        + uuid + "' ORDER BY pb.submitted_at DESC LIMIT 100");
                        while (rs2.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("type", "procurement_bid");
                            row.put("id", rs2.getString("id"));
                            row.put("procurement_id", rs2.getString("procurement_id"));
                            row.put("procurement_title", rs2.getString("procurement_title"));
                            row.put("unit_price", rs2.getDouble("unit_price"));
                            row.put("total_price", rs2.getDouble("total_price"));
                            row.put("status", rs2.getString("status"));
                            row.put("submitted_at", rs2.getLong("submitted_at"));
                            items.add(row);
                        }
                    }
                }
                case 3 -> {
                    // My published projects — 两条 SQL 使用独立 Statement
                    try (Statement st2 = conn.createStatement()) {
                        ResultSet rs = st.executeQuery(
                                "SELECT * FROM ks_ent_projects WHERE publisher_uuid='" + uuid + "' OR publisher_uuid IN (SELECT id FROM ks_ent_enterprises WHERE owner_uuids LIKE '%" + uuid + "%') ORDER BY created_at DESC LIMIT 100");
                        while (rs.next()) items.add(projectRow(rs));
                        // My published procurements — independent Statement
                        ResultSet rs2 = st2.executeQuery(
                                "SELECT p.*, e.name AS enterprise_name FROM ks_ent_procurements p LEFT JOIN ks_ent_enterprises e ON p.enterprise_id=e.id WHERE p.enterprise_id IN (SELECT enterprise_id FROM ks_ent_members WHERE player_uuid='" + uuid + "') ORDER BY p.created_at DESC LIMIT 100");
                        while (rs2.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("type", "my_procurement");
                            row.put("id", rs2.getString("id"));
                            row.put("enterprise_name", rs2.getString("enterprise_name"));
                            row.put("title", rs2.getString("title"));
                            row.put("budget", rs2.getDouble("budget"));
                            row.put("status", rs2.getString("status"));
                            row.put("created_at", rs2.getLong("created_at"));
                            items.add(row);
                        }
                    }
                }
                case 4 -> {
                    // 选标：选中采购的全部 PENDING 投标
                    if (selectedAwardId != null) {
                        ResultSet rs = st.executeQuery(
                                "SELECT b.*, e.name AS enterprise_name FROM ks_ent_procurement_bids b "
                                        + "LEFT JOIN ks_ent_enterprises e ON b.enterprise_id=e.id "
                                        + "WHERE b.procurement_id='" + selectedAwardId.replace("'", "''")
                                        + "' AND b.status='PENDING' ORDER BY b.unit_price ASC LIMIT 200");
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("id", rs.getString("id"));
                            row.put("procurement_id", rs.getString("procurement_id"));
                            row.put("bidder_uuid", rs.getString("bidder_uuid"));
                            row.put("bidder_type", rs.getString("bidder_type"));
                            row.put("enterprise_id", rs.getString("enterprise_id"));
                            row.put("enterprise_name", rs.getString("enterprise_name"));
                            row.put("unit_price", rs.getDouble("unit_price"));
                            row.put("total_price", rs.getDouble("total_price"));
                            row.put("submitted_at", rs.getLong("submitted_at"));
                            items.add(row);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("BiddingGui 加载失败: " + e.getMessage());
        }
    }

    private Map<String, Object> projectRow(ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("title", rs.getString("title"));
        row.put("publisher_uuid", rs.getString("publisher_uuid"));
        row.put("publisher_type", rs.getString("publisher_type"));
        row.put("budget", rs.getDouble("budget"));
        row.put("prepayment_ratio", rs.getDouble("prepayment_ratio"));
        row.put("deposit_ratio", rs.getDouble("deposit_ratio"));
        row.put("deadline", rs.getLong("deadline"));
        row.put("status", rs.getString("status"));
        row.put("created_at", rs.getLong("created_at"));
        return row;
    }

    private void build() {
        String[] viewNames = {"工程招标", "采购招标", "我的投标", "发布/评标", "采购选标"};
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8招投标 — " + viewNames[view] + " 第" + (page + 1) + "页"));

        // View tabs
        Material[] tabIcons = {Material.PAPER, Material.HOPPER, Material.WRITABLE_BOOK, Material.ANVIL};
        String[] tabLabels = {"§e工程", "§6采购", "§a投标", "§c发布"};
        for (int v = 0; v < 4; v++) {
            String label = tabLabels[v] + (v == view ? " §l◀" : "");
            inventory.setItem(v, navButton(tabIcons[v], label, v == view ? "§7（当前）" : "§7点击切换"));
        }

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && (start + i) < items.size(); i++) {
            inventory.setItem(9 + i, buildItem(items.get(start + i)));
        }
        if (items.isEmpty()) inventory.setItem(22, emptyHint());

        // Publish buttons for view 3
        if (view == 3) {
            inventory.setItem(46, navButton(Material.OAK_SIGN, "§a📋 发布工程招标",
                    "§7点击后在聊天栏输入招标信息"));
            inventory.setItem(47, navButton(Material.HOPPER, "§6🛒 发布采购需求",
                    "§7从你有招投标权限的企业公户出预算",
                    "§7点击后在聊天栏依次输入 企业ID/标题/数量/预算"));
        }

        if (page > 0)
            inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        if (view == 4)
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回发布/评标", "§7回到我的发布列表"));
        else
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回主菜单", "§7回到经济面板"));
        if ((page + 1) * PAGE_SIZE < items.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));

        fillEmpty();
    }

    private ItemStack buildItem(Map<String, Object> item) {
        return switch (view) {
            case 0 -> buildProjectItem(item);
            case 1 -> buildProcurementItem(item);
            case 2 -> buildMyBidItem(item);
            case 3 -> buildPublishedItem(item);
            case 4 -> buildProcBidSelectItem(item);
            default -> new ItemStack(Material.BARRIER);
        };
    }

    private ItemStack buildProcBidSelectItem(Map<String, Object> b) {
        ItemStack stack = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        String bidderType = String.valueOf(b.getOrDefault("bidder_type", "PLAYER"));
        String bidderLabel = "ENTERPRISE".equals(bidderType)
                ? String.valueOf(b.getOrDefault("enterprise_name", b.get("enterprise_id")))
                : resolveNameOrUuid(String.valueOf(b.get("bidder_uuid")));
        meta.displayName(Component.text("§e📄 " + bidderLabel + " 的报价"));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("投标方类型: " + ("ENTERPRISE".equals(bidderType) ? "企业" : "个人"), NamedTextColor.GRAY));
        lore.add(Component.text("单价: §e" + plugin.vaultHook().format(num(b, "unit_price")), NamedTextColor.GRAY));
        lore.add(Component.text("报价总价: " + plugin.vaultHook().format(num(b, "total_price"))
                + " §8（定标按 单价×采购数量 重算）", NamedTextColor.GRAY));
        Object at = b.get("submitted_at");
        if (at instanceof Number n && n.longValue() > 0)
            lore.add(Component.text("投标时间: " + new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(n.longValue() * 1000)), NamedTextColor.DARK_GRAY));
        lore.add(Component.text("投标ID: " + b.get("id"), NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§6§lShift+左键 选定此标并从公户付款", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private String resolveNameOrUuid(String uuid) {
        try {
            var op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
            return op.getName() != null ? op.getName() : uuid.substring(0, 8);
        } catch (Exception e) { return uuid; }
    }

    private static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static String statusLabel(String status) {
        return switch (status == null ? "" : status.toUpperCase(Locale.ROOT)) {
            case "OPEN" -> "开放中";
            case "PENDING", "PENDING_DEPOSIT" -> "待处理";
            case "AWARDED" -> "已定标";
            case "REJECTED" -> "未中标";
            case "CLOSED" -> "已关闭";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            default -> status == null || status.isBlank() ? "未知" : status;
        };
    }

    private static String publisherLabel(Object publisherType) {
        String type = String.valueOf(publisherType);
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "PLAYER" -> "个人";
            case "ENTERPRISE" -> "企业";
            case "OFFICIAL" -> "官方";
            default -> type;
        };
    }

    private ItemStack buildProjectItem(Map<String, Object> p) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String title = String.valueOf(p.get("title"));
        double budget = ((Number) p.get("budget")).doubleValue();
        String status = String.valueOf(p.get("status"));
        long deadline = ((Number) p.get("deadline")).longValue();
        double deposit = ((Number) p.get("deposit_ratio")).doubleValue();

        meta.displayName(Component.text("§e📋 " + title));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("预算: " + plugin.vaultHook().format(budget), NamedTextColor.GOLD));
        lore.add(Component.text("发布方: " + publisherLabel(p.get("publisher_type")), NamedTextColor.GRAY));
        lore.add(Component.text("状态: " + statusLabel(status), NamedTextColor.YELLOW));
        lore.add(Component.text("截止: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                .format(new java.util.Date(deadline * 1000)), NamedTextColor.GRAY));
        if (deposit > 0)
            lore.add(Component.text("保证金: " + String.format("%.0f%%", deposit * 100), NamedTextColor.RED));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键投标", NamedTextColor.YELLOW));
        lore.add(Component.text("§7点击后在聊天栏输入投标金额", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildProcurementItem(Map<String, Object> p) {
        ItemStack stack = new ItemStack(Material.HOPPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String title = String.valueOf(p.get("title"));
        double budget = ((Number) p.get("budget")).doubleValue();
        int qty = ((Number) p.get("quantity")).intValue();

        meta.displayName(Component.text("§6📦 " + title));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("采购方: " + p.getOrDefault("enterprise_name", "?"), NamedTextColor.GRAY));
        lore.add(Component.text("数量: " + qty + " | 预算: " + plugin.vaultHook().format(budget), NamedTextColor.GRAY));
        lore.add(Component.text("描述: " + p.getOrDefault("item_desc", ""), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键投标", NamedTextColor.YELLOW));
        lore.add(Component.text("§7点击后在聊天栏输入单价和总价（如 500 5000）", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildMyBidItem(Map<String, Object> b) {
        String type = String.valueOf(b.getOrDefault("type", "project_bid"));
        boolean isProject = "project_bid".equals(type);

        ItemStack stack = new ItemStack(isProject ? Material.WRITABLE_BOOK : Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String title = isProject ? String.valueOf(b.get("project_title")) : String.valueOf(b.get("procurement_title"));
        String status = String.valueOf(b.get("status"));
        long time = ((Number) b.get("submitted_at")).longValue();

        meta.displayName(Component.text((isProject ? "§e" : "§6") + "投标: " + title));
        List<Component> lore = new ArrayList<>();
        if (isProject) {
            lore.add(Component.text("投标金额: " + plugin.vaultHook().format(((Number) b.get("bid_amount")).doubleValue()), NamedTextColor.GOLD));
        } else {
            lore.add(Component.text("单价: " + plugin.vaultHook().format(((Number) b.get("unit_price")).doubleValue()), NamedTextColor.GRAY));
            lore.add(Component.text("总价: " + plugin.vaultHook().format(((Number) b.get("total_price")).doubleValue()), NamedTextColor.GOLD));
        }
        lore.add(Component.text("状态: " + statusLabel(status), NamedTextColor.YELLOW));
        lore.add(Component.text("时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                .format(new java.util.Date(time * 1000)), NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildPublishedItem(Map<String, Object> p) {
        String type = String.valueOf(p.getOrDefault("type", "project"));
        boolean isProject = !"my_procurement".equals(type);

        ItemStack stack = new ItemStack(isProject ? Material.ANVIL : Material.HOPPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String title = String.valueOf(p.get("title"));
        String status = String.valueOf(p.get("status"));
        double budget = ((Number) p.get("budget")).doubleValue();

        meta.displayName(Component.text((isProject ? "§c" : "§6") + (isProject ? "工程: " : "采购: ") + title));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("预算: " + plugin.vaultHook().format(budget), NamedTextColor.GOLD));
        lore.add(Component.text("状态: " + statusLabel(status), NamedTextColor.YELLOW));
        if (!isProject)
            lore.add(Component.text("企业: " + p.getOrDefault("enterprise_name", "?"), NamedTextColor.GRAY));
        lore.add(Component.empty());
        if ("OPEN".equals(status))
            lore.add(Component.text("§a§l左键评标", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    // ---- Award operations (G5) ----

    private boolean hasEntBidPermission(Player player, String entId) {
        if (player.hasPermission("kseco.admin")) return true;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            return conn != null && permService.hasPermission(conn, entId, player.getUniqueId(),
                    org.kseco.EnterprisePermissionService.MANAGE_BIDDING);
        } catch (Exception e) { return false; }
    }

    /** 工程评标：走 BiddingManager.awardProject 的规范路径（自动评分+信誉加成+预付款+防重复评标）。 */
    private void doAwardProjectAuto(Player player, String projectId, String publisherUuid) {
        boolean allowed = player.hasPermission("kseco.admin")
                || player.getUniqueId().toString().equals(publisherUuid)
                || hasEntBidPermission(player, publisherUuid); // 企业名义发布时 publisher_uuid=企业ID
        if (!allowed) { player.sendMessage("§c你没有该项目的评标权限。"); return; }
        Object result = plugin.callExtraManager("ks-eco-enterprise", "biddingManager", "awardProject",
                new Class<?>[]{String.class}, projectId);
        if (result != null) {
            player.sendMessage("§a已按综合评分自动评标并发放预付款。");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        } else {
            player.sendMessage("§c评标失败：无待评投标、项目已评标，或企业模块未加载。");
        }
    }

    /**
     * 采购定标：镜像 web handleProcurementAward 的资金流转 ——
     * 总价按 中标单价 × 当前采购数量 重算（不信任存储的 total_price），
     * 从发布企业公户付款（同步 current_assets 与开户行 total_assets），供应方为企业则入公户、个人则入钱包。
     */
    private void doAwardProcurementBid(Player player, String procurementId, String bidId) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) { player.sendMessage("§c数据库未连接。"); return; }
            conn.setAutoCommit(false);
            org.bukkit.OfflinePlayer personalSupplier = null;
            double personalWalletPayout = 0.0;
            boolean personalWalletPaid = false;
            boolean databaseCommitted = false;
            try (Statement st = conn.createStatement()) {
                String pid = procurementId.replace("'", "''");
                String entId; double budget; int quantity; String status;
                try (ResultSet rs = st.executeQuery("SELECT * FROM ks_ent_procurements WHERE id='" + pid + "'")) {
                    if (!rs.next()) { player.sendMessage("§c采购不存在。"); conn.rollback(); return; }
                    entId = rs.getString("enterprise_id");
                    budget = rs.getDouble("budget");
                    quantity = rs.getInt("quantity");
                    status = rs.getString("status");
                }
                if (!"OPEN".equals(status)) { player.sendMessage("§c该采购已被处理。"); conn.rollback(); return; }
                if (!player.hasPermission("kseco.admin")
                        && !permService.hasPermission(conn, entId, player.getUniqueId(),
                        org.kseco.EnterprisePermissionService.MANAGE_BIDDING)) {
                    player.sendMessage("§c你没有该企业的评标权限。"); conn.rollback(); return;
                }
                String bid = bidId.replace("'", "''");
                String supplierEntId, supplierUuid, supplierType; double unitPrice;
                try (Statement st2 = conn.createStatement();
                     ResultSet rs = st2.executeQuery("SELECT * FROM ks_ent_procurement_bids WHERE id='" + bid
                             + "' AND procurement_id='" + pid + "' AND status='PENDING'")) {
                    if (!rs.next()) { player.sendMessage("§c投标不存在或已处理。"); conn.rollback(); return; }
                    unitPrice = rs.getDouble("unit_price");
                    supplierEntId = rs.getString("enterprise_id");
                    supplierUuid = rs.getString("bidder_uuid");
                    supplierType = rs.getString("bidder_type");
                }
                double totalPrice = unitPrice * quantity;
                if (quantity <= 0 || !Double.isFinite(totalPrice) || totalPrice <= 0 || totalPrice > 1_000_000_000_000_000d) {
                    player.sendMessage("§c采购总价超出允许范围。"); conn.rollback(); return;
                }
                if (totalPrice > budget) {
                    player.sendMessage("§c该报价按当前数量重算后超出预算（" + plugin.vaultHook().format(totalPrice)
                            + " > " + plugin.vaultHook().format(budget) + "）。"); conn.rollback(); return;
                }
                long now = System.currentTimeMillis() / 1000;
                String eid = entId.replace("'", "''");
                double corpBal = 0;
                try (Statement st3 = conn.createStatement();
                     ResultSet rs = st3.executeQuery("SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id='" + eid + "'")) {
                    if (rs.next()) corpBal = rs.getDouble("balance");
                }
                if (corpBal < totalPrice) {
                    player.sendMessage("§c企业公户余额不足（余额 " + plugin.vaultHook().format(corpBal) + "）。");
                    conn.rollback(); return;
                }
                String buyerBankId = null;
                try (Statement st4 = conn.createStatement();
                     ResultSet rs = st4.executeQuery("SELECT bank_id FROM ks_ent_corporate_accounts WHERE enterprise_id='" + eid + "'")) {
                    if (rs.next()) buyerBankId = rs.getString("bank_id");
                }
                if (buyerBankId == null) { player.sendMessage("§c付款企业未开立公户。"); conn.rollback(); return; }
                st.executeUpdate("UPDATE ks_ent_corporate_accounts SET balance = balance - " + totalPrice
                        + ", updated_at = " + now + " WHERE enterprise_id='" + eid + "'");
                st.executeUpdate("UPDATE ks_ent_enterprises SET current_assets = current_assets - " + totalPrice
                        + " WHERE id='" + eid + "'");
                st.executeUpdate("UPDATE ks_bank_banks SET total_assets = total_assets - " + totalPrice
                        + " WHERE id='" + buyerBankId.replace("'", "''") + "'");
                if ("ENTERPRISE".equals(supplierType) && supplierEntId != null && !supplierEntId.isEmpty()) {
                    String sid = supplierEntId.replace("'", "''");
                    st.executeUpdate("INSERT OR IGNORE INTO ks_ent_corporate_accounts (enterprise_id, bank_id, balance, updated_at) VALUES ('"
                            + sid + "', 'CORP-BANK', 0, " + now + ")");
                    st.executeUpdate("UPDATE ks_ent_corporate_accounts SET balance = balance + " + totalPrice
                            + ", updated_at = " + now + " WHERE enterprise_id='" + sid + "'");
                    String supplierBankId = null;
                    try (Statement st5 = conn.createStatement();
                         ResultSet rs = st5.executeQuery("SELECT bank_id FROM ks_ent_corporate_accounts WHERE enterprise_id='" + sid + "'")) {
                        if (rs.next()) supplierBankId = rs.getString("bank_id");
                    }
                    if (supplierBankId == null) { player.sendMessage("§c供应企业未开立公户。"); conn.rollback(); return; }
                    st.executeUpdate("UPDATE ks_ent_enterprises SET current_assets = current_assets + " + totalPrice
                            + " WHERE id='" + sid + "'");
                    st.executeUpdate("UPDATE ks_bank_banks SET total_assets = total_assets + " + totalPrice
                            + " WHERE id='" + supplierBankId.replace("'", "''") + "'");
                } else if (supplierUuid != null && !supplierUuid.isEmpty()) {
                    try {
                        personalSupplier = Bukkit.getOfflinePlayer(UUID.fromString(supplierUuid));
                    } catch (IllegalArgumentException invalidUuid) {
                        player.sendMessage("§c供应方玩家标识无效。"); conn.rollback(); return;
                    }
                    personalWalletPayout = totalPrice;
                    if (plugin.builtinEconomy().isRegistered()
                            && !plugin.builtinEconomy().depositInTransaction(conn, personalSupplier, totalPrice)) {
                        player.sendMessage("§c供应方内置钱包入账失败。"); conn.rollback(); return;
                    }
                } else {
                    player.sendMessage("§c投标缺少有效的供应方账户。"); conn.rollback(); return;
                }
                st.executeUpdate("UPDATE ks_ent_procurement_bids SET status='AWARDED' WHERE id='" + bid + "'");
                st.executeUpdate("UPDATE ks_ent_procurement_bids SET status='REJECTED' WHERE procurement_id='" + pid
                        + "' AND status='PENDING' AND id<>'" + bid + "'");
                if (st.executeUpdate("UPDATE ks_ent_procurements SET status='AWARDED' WHERE id='" + pid
                        + "' AND status='OPEN'") != 1) {
                    player.sendMessage("§c该采购已被并发定标。"); conn.rollback(); return;
                }
                // 外部 Vault 无法加入 SQLite 事务，只能在所有 SQL 校验完成后入账；若提交失败，catch 中补偿扣回。
                if (personalSupplier != null && !plugin.builtinEconomy().isRegistered()) {
                    if (!plugin.vaultHook().deposit(personalSupplier, personalWalletPayout)) {
                        player.sendMessage("§c供应方钱包入账失败。"); conn.rollback(); return;
                    }
                    personalWalletPaid = true;
                }
                conn.commit();
                databaseCommitted = true;
                player.sendMessage("§a采购定标完成！已从公户付款 " + plugin.vaultHook().format(totalPrice)
                        + "（按 单价×数量 重算） → " + ("ENTERPRISE".equals(supplierType) ? "供应企业公户" : "供应方钱包"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
            } catch (Exception inner) {
                try { conn.rollback(); } catch (Exception rollbackError) {
                    plugin.getLogger().severe("采购定标数据库回滚失败: " + rollbackError.getMessage());
                }
                if (personalWalletPaid && !databaseCommitted
                        && !plugin.vaultHook().withdraw(personalSupplier, personalWalletPayout)) {
                    plugin.getLogger().severe("采购定标回滚后无法扣回供应方钱包款项: player="
                            + personalSupplier.getUniqueId() + " amount=" + personalWalletPayout);
                }
                player.sendMessage("§c定标失败: " + inner.getMessage());
            } finally {
                try { conn.setAutoCommit(true); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            player.sendMessage("§c定标失败: " + e.getMessage());
        }
    }

    private ItemStack emptyHint() {
        String msg = switch (view) {
            case 0 -> "§7暂无进行中的工程招标";
            case 1 -> "§7暂无采购需求";
            case 2 -> "§7暂无投标记录";
            case 3 -> "§7你还没有发布招标";
            case 4 -> "§7该采购暂无待选投标";
            default -> "§7无数据";
        };
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text(msg)); item.setItemMeta(meta); }
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
            if (!(event.getView().getTopInventory().getHolder() instanceof BiddingGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;

            // View tabs (0-3)
            if (slot >= 0 && slot <= 3) {
                if (slot != gui.view) {
                    gui.view = slot; gui.page = 0; gui.loadData(player); gui.build();
                    player.openInventory(gui.getInventory());
                }
                return;
            }

            // Publish buttons
            if (gui.view == 3 && slot == 46) {
                player.closeInventory();
                pendingPublish.put(player.getUniqueId(), new PendingPublish(1, null, 0, 0, 0.3, 0.1, 0));
                player.sendMessage("§a请输入招标标题，或输入 cancel 取消：");
                return;
            }
            if (gui.view == 3 && slot == 47) {
                player.closeInventory();
                pendingPublishProc.put(player.getUniqueId(), new PendingPublishProc(1, null, null, 0, 0));
                player.sendMessage("§a请输入发布采购的企业ID（需要该企业的招投标权限），或输入 cancel 取消：");
                return;
            }

            // Content slots
            if (slot >= 9 && slot < 9 + PAGE_SIZE) {
                int index = gui.page * PAGE_SIZE + (slot - 9);
                if (index < gui.items.size()) {
                    Map<String, Object> item = gui.items.get(index);
                    switch (gui.view) {
                        case 0 -> {
                            // Bid on project
                            String projectId = String.valueOf(item.get("id"));
                            player.closeInventory();
                            pendingBid.put(player.getUniqueId(), new PendingBid(projectId, "project"));
                            player.sendMessage("§a请输入投标金额（如 5000），或 cancel 取消：");
                        }
                        case 1 -> {
                            // Bid on procurement
                            String pid = String.valueOf(item.get("id"));
                            player.closeInventory();
                            pendingBid.put(player.getUniqueId(), new PendingBid(pid, "procurement"));
                            player.sendMessage("§a请输入 单价 总价（如 500 5000），或 cancel 取消：");
                        }
                        case 3 -> {
                            // Award / view bids
                            String pid = String.valueOf(item.get("id"));
                            String status = String.valueOf(item.getOrDefault("status", ""));
                            String type = String.valueOf(item.getOrDefault("type", "project"));
                            if ("OPEN".equals(status)) {
                                if (type.equals("my_procurement")) {
                                    // 进入选标视图（点击投标项定标，不再手输 UUID）
                                    gui.view = 4; gui.page = 0; gui.selectedAwardId = pid;
                                    gui.loadData(player); gui.build();
                                    player.openInventory(gui.getInventory());
                                } else if (event.isShiftClick()) {
                                    // 工程：综合评分自动评标（Shift 确认）
                                    gui.doAwardProjectAuto(player, pid, String.valueOf(item.get("publisher_uuid")));
                                    gui.loadData(player); gui.build();
                                    player.openInventory(gui.getInventory());
                                } else {
                                    player.sendMessage("§e工程评标为综合评分自动定标：§6Shift+左键§e 确认执行。采购点击后进入选标列表。");
                                }
                            }
                        }
                        case 4 -> {
                            if (event.isShiftClick() && gui.selectedAwardId != null) {
                                gui.doAwardProcurementBid(player, gui.selectedAwardId, String.valueOf(item.get("id")));
                                gui.view = 3; gui.page = 0; gui.selectedAwardId = null;
                                gui.loadData(player); gui.build();
                                player.openInventory(gui.getInventory());
                            } else {
                                player.sendMessage("§e请用 §6Shift+左键§e 确认选定该投标（将立即从公户付款）。");
                            }
                        }
                    }
                }
                return;
            }

            switch (slot) {
                case 45 -> { if (gui.page > 0) { gui.page--; gui.build(); player.openInventory(gui.getInventory()); } }
                case 49 -> {
                    if (gui.view == 4) {
                        gui.view = 3; gui.page = 0; gui.selectedAwardId = null;
                        gui.loadData(player); gui.build();
                        player.openInventory(gui.getInventory());
                    } else {
                        player.closeInventory(); new EcoGuiMainMenu(plugin).open(player);
                    }
                }
                case 53 -> {
                    if ((gui.page + 1) * PAGE_SIZE < gui.items.size()) {
                        gui.page++; gui.build(); player.openInventory(gui.getInventory());
                    }
                }
            }
        }
    }

    // ---- Chat Listener ----

    public static class ChatListener implements org.bukkit.event.Listener {

        private final KsEco plugin;

        public ChatListener(KsEco plugin) { this.plugin = plugin; }

        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            pendingBid.remove(playerId);
            pendingPublish.remove(playerId);
            pendingPublishProc.remove(playerId);
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();

            // Check bid pending first
            PendingBid bidPending = pendingBid.remove(player.getUniqueId());
            if (bidPending != null) {
                event.setCancelled(true);
                String msg = event.getMessage().trim();
                if (msg.equalsIgnoreCase("cancel")) {
                    player.sendMessage("§c已取消。");
                    Bukkit.getScheduler().runTask(plugin, () -> new BiddingGui(plugin).open(player));
                    return;
                }
                handleBidChat(player, bidPending, msg);
                return;
            }

            // Check procurement publish wizard
            PendingPublishProc procPending = pendingPublishProc.remove(player.getUniqueId());
            if (procPending != null) {
                event.setCancelled(true);
                String msg = event.getMessage().trim();
                if (msg.equalsIgnoreCase("cancel")) {
                    player.sendMessage("§c已取消。");
                    Bukkit.getScheduler().runTask(plugin, () -> new BiddingGui(plugin).open(player));
                    return;
                }
                handlePublishProcChat(player, procPending, msg);
                return;
            }

            // Check publish pending
            PendingPublish pubPending = pendingPublish.remove(player.getUniqueId());
            if (pubPending == null) return;

            event.setCancelled(true);
            String msg = event.getMessage().trim();
            if (msg.equalsIgnoreCase("cancel")) {
                player.sendMessage("§c已取消。");
                Bukkit.getScheduler().runTask(plugin, () -> new BiddingGui(plugin).open(player));
                return;
            }
            handlePublishChat(player, pubPending, msg);
        }

        /** 发布采购聊天向导：企业ID → 标题 → 数量 → 预算，镜像 web handleProcurementPublish 的校验与授权。 */
        private void handlePublishProcChat(Player player, PendingPublishProc pending, String msg) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                switch (pending.step) {
                    case 1 -> {
                        String entId = msg.trim();
                        BiddingGui probe = new BiddingGui(plugin);
                        if (!probe.hasEntBidPermission(player, entId)) {
                            player.sendMessage("§c你没有企业 " + entId + " 的招投标权限（或企业不存在）。");
                            pendingPublishProc.put(player.getUniqueId(), pending);
                            return;
                        }
                        pendingPublishProc.put(player.getUniqueId(), new PendingPublishProc(2, entId, null, 0, 0));
                        player.sendMessage("§a请输入采购标题（≤128字）：");
                    }
                    case 2 -> {
                        if (msg.isEmpty() || msg.length() > 128) {
                            player.sendMessage("§c标题不能为空且不超过128字。");
                            pendingPublishProc.put(player.getUniqueId(), new PendingPublishProc(2, pending.entId, null, 0, 0));
                            return;
                        }
                        pendingPublishProc.put(player.getUniqueId(), new PendingPublishProc(3, pending.entId, msg, 0, 0));
                        player.sendMessage("§a请输入采购数量（1 ~ 1,000,000）：");
                    }
                    case 3 -> {
                        int qty;
                        try { qty = Integer.parseInt(msg); } catch (NumberFormatException e) { qty = -1; }
                        if (qty <= 0 || qty > 1_000_000) {
                            player.sendMessage("§c数量必须在 1 到 1,000,000 之间。");
                            pendingPublishProc.put(player.getUniqueId(), new PendingPublishProc(3, pending.entId, pending.title, 0, 0));
                            return;
                        }
                        pendingPublishProc.put(player.getUniqueId(), new PendingPublishProc(4, pending.entId, pending.title, qty, 0));
                        player.sendMessage("§a请输入采购预算（从企业公户支出，须 ≤ 公户余额）：");
                    }
                    case 4 -> {
                        double budget;
                        try { budget = Double.parseDouble(msg); } catch (NumberFormatException e) { budget = -1; }
                        if (!Double.isFinite(budget) || budget <= 0) {
                            player.sendMessage("§c无效预算金额。");
                            pendingPublishProc.put(player.getUniqueId(), new PendingPublishProc(4, pending.entId, pending.title, pending.quantity, 0));
                            return;
                        }
                        BiddingGui permissionProbe = new BiddingGui(plugin);
                        if (!permissionProbe.hasEntBidPermission(player, pending.entId)) {
                            player.sendMessage("§c你的企业招投标权限已失效，发布已取消。");
                            return;
                        }
                        try (var conn = plugin.ksCore().dataStore().getConnection();
                             Statement st = conn.createStatement()) {
                            double corpBal = 0;
                            try (ResultSet rs = st.executeQuery("SELECT balance FROM ks_ent_corporate_accounts WHERE enterprise_id='"
                                    + pending.entId.replace("'", "''") + "'")) {
                                if (rs.next()) corpBal = rs.getDouble("balance");
                            }
                            if (corpBal < budget) {
                                player.sendMessage("§c企业公户余额不足（余额 " + plugin.vaultHook().format(corpBal) + "）。");
                                pendingPublishProc.put(player.getUniqueId(), pending);
                                return;
                            }
                            String id = UUID.randomUUID().toString().substring(0, 8);
                            long now = System.currentTimeMillis() / 1000;
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO ks_ent_procurements (id, enterprise_id, title, item_desc, quantity, budget, status, created_at) VALUES (?,?,?,?,?,?,'OPEN',?)")) {
                                ps.setString(1, id);
                                ps.setString(2, pending.entId);
                                ps.setString(3, pending.title);
                                ps.setString(4, "");
                                ps.setInt(5, pending.quantity);
                                ps.setDouble(6, budget);
                                ps.setLong(7, now);
                                ps.executeUpdate();
                            }
                            player.sendMessage("§a采购需求已发布！ID: " + id + "，供应方投标后可在「发布/评标」中选标。");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
                        } catch (Exception e) {
                            player.sendMessage("§c发布采购失败: " + e.getMessage());
                        }
                        new BiddingGui(plugin).open(player);
                    }
                }
            });
        }

        private void handleBidChat(Player player, PendingBid pending, String msg) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try (var conn = plugin.ksCore().dataStore().getConnection()) {
                    long now = System.currentTimeMillis() / 1000;
                    String bidId = UUID.randomUUID().toString().substring(0, 8);

                    switch (pending.type) {
                        case "project" -> {
                            double amount = Double.parseDouble(msg);
                            if (!Double.isFinite(amount) || amount <= 0) {
                                player.sendMessage("§c金额必须是大于 0 的有效数字，请重新输入或输入 cancel 取消。");
                                pendingBid.put(player.getUniqueId(), pending);
                                return;
                            }
                            try (PreparedStatement check = conn.prepareStatement(
                                    "SELECT status, deadline FROM ks_ent_projects WHERE id=?")) {
                                check.setString(1, pending.projectId);
                                try (ResultSet rs = check.executeQuery()) {
                                    String status = rs.next() ? rs.getString("status") : null;
                                    if (!("OPEN".equals(status) || "PENDING_DEPOSIT".equals(status))
                                            || rs.getLong("deadline") <= now) {
                                        player.sendMessage("§c该项目已截止或不再接受投标。");
                                        return;
                                    }
                                }
                            }
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO ks_ent_bids (id, project_id, bidder_uuid, bidder_type, bid_amount, submitted_at) VALUES (?,?,?,'PLAYER',?,?)")) {
                                ps.setString(1, bidId);
                                ps.setString(2, pending.projectId);
                                ps.setString(3, player.getUniqueId().toString());
                                ps.setDouble(4, amount);
                                ps.setLong(5, now);
                                ps.executeUpdate();
                            }
                            player.sendMessage("§a投标成功！金额: " + plugin.vaultHook().format(amount));
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                        }
                        case "procurement" -> {
                            String[] parts = msg.split("\\s+");
                            if (parts.length < 2) {
                                player.sendMessage("§c格式: 单价 总价（如 500 5000），请重新输入或输入 cancel 取消。");
                                pendingBid.put(player.getUniqueId(), pending);
                                return;
                            }
                            double unit = Double.parseDouble(parts[0]);
                            double total = Double.parseDouble(parts[1]);
                            if (!Double.isFinite(unit) || !Double.isFinite(total) || unit <= 0 || total <= 0) {
                                player.sendMessage("§c单价和总价必须是大于 0 的有效数字，请重新输入或输入 cancel 取消。");
                                pendingBid.put(player.getUniqueId(), pending);
                                return;
                            }
                            try (PreparedStatement check = conn.prepareStatement(
                                    "SELECT status FROM ks_ent_procurements WHERE id=?")) {
                                check.setString(1, pending.projectId);
                                try (ResultSet rs = check.executeQuery()) {
                                    if (!rs.next() || !"OPEN".equals(rs.getString("status"))) {
                                        player.sendMessage("§c该采购已关闭，不再接受投标。");
                                        return;
                                    }
                                }
                            }
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO ks_ent_procurement_bids (id, procurement_id, bidder_uuid, bidder_type, unit_price, total_price, submitted_at) VALUES (?,?,?,'PLAYER',?,?,?)")) {
                                ps.setString(1, bidId);
                                ps.setString(2, pending.projectId);
                                ps.setString(3, player.getUniqueId().toString());
                                ps.setDouble(4, unit);
                                ps.setDouble(5, total);
                                ps.setLong(6, now);
                                ps.executeUpdate();
                            }
                            player.sendMessage("§a供应投标成功！");
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                        }
                        // 评标已改为 GUI 内点击选标/自动评分（含真实资金流转），不再走裸 UUID 聊天输入。
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage("§c无效数字格式，请重新输入或输入 cancel 取消。");
                    pendingBid.put(player.getUniqueId(), pending);
                    return;
                } catch (Exception e) {
                    player.sendMessage("§c操作失败: " + e.getMessage());
                }
                new BiddingGui(plugin).open(player);
            });
        }

        private void handlePublishChat(Player player, PendingPublish pending, String msg) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                switch (pending.step) {
                    case 1 -> {
                        if (msg.isBlank() || msg.length() > 128) {
                            player.sendMessage("§c标题不能为空且不能超过 128 字，请重新输入或输入 cancel 取消。");
                            pendingPublish.put(player.getUniqueId(), pending);
                            return;
                        }
                        pendingPublish.put(player.getUniqueId(), new PendingPublish(2, msg, 0, 0, 0.3, 0.1, 0));
                        player.sendMessage("§a请输入预算金额：");
                    }
                    case 2 -> {
                        try {
                            double budget = Double.parseDouble(msg);
                            if (!Double.isFinite(budget) || budget <= 0) throw new NumberFormatException();
                            pendingPublish.put(player.getUniqueId(), new PendingPublish(3, pending.title, budget, 0, 0.3, 0.1, 0));
                            player.sendMessage("§a请输入招标期限（天数）：");
                        } catch (NumberFormatException e) {
                            player.sendMessage("§c无效金额: " + msg);
                            pendingPublish.put(player.getUniqueId(), new PendingPublish(2, pending.title, 0, 0, 0.3, 0.1, 0));
                        }
                    }
                    case 3 -> {
                        try {
                            int days = Integer.parseInt(msg);
                            if (days <= 0 || days > 3650) throw new NumberFormatException();
                            long now = System.currentTimeMillis() / 1000;
                            long deadline = now + days * 86400L;
                            String projId = UUID.randomUUID().toString().substring(0, 8);
                            try (var conn = plugin.ksCore().dataStore().getConnection();
                                 PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO ks_ent_projects (id, title, publisher_uuid, publisher_type, budget, prepayment_ratio, penalty_ratio, deposit_ratio, deadline, created_at) VALUES (?,?,?,'PLAYER',?,?,?,?,?,?)")) {
                                ps.setString(1, projId);
                                ps.setString(2, pending.title);
                                ps.setString(3, player.getUniqueId().toString());
                                ps.setDouble(4, pending.budget);
                                ps.setDouble(5, pending.prepay);
                                ps.setDouble(6, pending.penalty);
                                ps.setDouble(7, pending.deposit);
                                ps.setLong(8, deadline);
                                ps.setLong(9, now);
                                ps.executeUpdate();
                            }
                            player.sendMessage("§a工程招标已发布！ID: " + projId);
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
                            new BiddingGui(plugin).open(player);
                        } catch (NumberFormatException e) {
                            player.sendMessage("§c期限必须是 1 到 3650 天，请重新输入或输入 cancel 取消。");
                            pendingPublish.put(player.getUniqueId(), new PendingPublish(3, pending.title, pending.budget, 0, 0.3, 0.1, 0));
                        } catch (Exception e) {
                            player.sendMessage("§c发布失败: " + e.getMessage());
                            new BiddingGui(plugin).open(player);
                        }
                    }
                }
            });
        }
    }
}
