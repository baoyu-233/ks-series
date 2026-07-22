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
import org.kseco.database.PortableSqlMutation;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 银行 GUI — 账户总览、浏览银行、创建银行、贷款管理。
 * SQL 读 + 反射写（bankManager）。
 */
public final class BankGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private int view = 0; // 0=accounts, 1=browse, 2=create, 3=loans, 4=manage, 5=requests, 6=members, 7=member-perms, 8=auctions
    private int page = 0;
    private String selectedBankId = null;
    private String selectedMemberUuid = null;
    private String selectedMemberName = null;
    private final List<Map<String, Object>> items = new ArrayList<>();
    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 36;
    private static final int TAB_COUNT = 5;

    // Chat input pending states
    record PendingDeposit(String bankId) {}
    record PendingWithdraw(String bankId) {}
    record PendingCreateBank(int step, String name, int capital) {} // step: 1=name, 2=capital
    record PendingLoanApply(String bankId) {}
    record PendingLoanRepay(String loanId, double remaining) {}
    record PendingRateSet(String bankId) {}
    record PendingAddBankMember(String bankId) {}
    record PendingAuctionBid(String auctionId, double currentPrice) {}
    static final Map<UUID, Object> pendingBank = new ConcurrentHashMap<>();

    /** 银行成员可分配角色循环（MEMBER 为基础行默认值，其余与 BankAccessProviderImpl.ROLES 一致）。 */
    private static final List<String> MEMBER_ROLE_CYCLE = List.of("MEMBER", "TELLER", "MANAGER", "DIRECTOR");
    private static final List<String> BANK_PERMISSIONS = List.of(
            "MANAGE_MEMBERS", "MANAGE_PERMISSIONS", "VIEW_FINANCE", "SET_RATES", "ISSUE_LOAN", "APPROVE_LOAN");
    private static final Map<String, String> BANK_PERM_CN = Map.of(
            "MANAGE_MEMBERS", "管理成员", "MANAGE_PERMISSIONS", "管理权限", "VIEW_FINANCE", "查看财务",
            "SET_RATES", "设置利率", "ISSUE_LOAN", "直接放贷", "APPROVE_LOAN", "批准贷款");

    public BankGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        this.page = 0;
        this.view = 0;
        this.selectedBankId = null;
        loadData(player);
        build();
        player.openInventory(inventory);
    }

    private double vaultBalance = 0;

    private void loadData(Player player) {
        items.clear();
        this.viewer = player;
        vaultBalance = plugin.vaultHook().getBalance(player);
        String uuid = player.getUniqueId().toString().replace("'", "''");

        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             Statement st = conn.createStatement()) {

            switch (view) {
                case 0 -> {
                    // 我的银行账户
                    ResultSet rs = st.executeQuery(
                            "SELECT a.*, b.name AS bank_name, b.type AS bank_type, b.interest_rate AS deposit_rate FROM ks_bank_accounts a JOIN ks_bank_banks b ON a.bank_id = b.id WHERE a.player_uuid='"
                                    + uuid + "' ORDER BY a.balance DESC");
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("bank_id", rs.getString("bank_id"));
                        row.put("bank_name", rs.getString("bank_name"));
                        row.put("bank_type", rs.getString("bank_type"));
                        row.put("balance", rs.getDouble("balance"));
                        row.put("deposit_rate", rs.getDouble("deposit_rate"));
                        row.put("interest_earned", rs.getDouble("interest_earned"));
                        items.add(row);
                    }
                }
                case 1 -> {
                    // 浏览所有银行
                    ResultSet rs = st.executeQuery(
                            "SELECT b.*, (SELECT COUNT(*) FROM ks_bank_members WHERE bank_id=b.id) AS member_count FROM ks_bank_banks b WHERE b.type='COMMERCIAL' ORDER BY b.total_assets DESC LIMIT 200");
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("name", rs.getString("name"));
                        row.put("type", rs.getString("type"));
                        row.put("total_assets", rs.getDouble("total_assets"));
                        row.put("interest_rate", rs.getDouble("interest_rate"));
                        row.put("loan_rate", rs.getDouble("loan_rate"));
                        row.put("member_count", rs.getInt("member_count"));
                        items.add(row);
                    }
                }
                case 2 -> {
                    // 创建银行页面 — 不加载数据，显示创建表单
                }
                case 3 -> {
                    // 我的贷款 — 两条 SQL 各用独立 Statement，避免同一 Statement 上堆叠 ResultSet
                    try (Statement st2 = conn.createStatement()) {
                        ResultSet rs = st.executeQuery(
                                "SELECT l.*, b.name AS bank_name FROM ks_bank_loans l JOIN ks_bank_banks b ON l.bank_id = b.id WHERE l.borrower_uuid='"
                                        + uuid + "' ORDER BY l.status, l.issued_at DESC LIMIT 200");
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("id", rs.getString("id"));
                            row.put("bank_id", rs.getString("bank_id"));
                            row.put("bank_name", rs.getString("bank_name"));
                            row.put("principal", rs.getDouble("principal"));
                            row.put("remaining", rs.getDouble("remaining"));
                            row.put("interest_rate", rs.getDouble("interest_rate"));
                            row.put("term_days", rs.getInt("term_days"));
                            row.put("status", rs.getString("status"));
                            row.put("issued_at", rs.getLong("issued_at"));
                            row.put("due_at", rs.getLong("due_at"));
                            items.add(row);
                        }
                        // 贷款申请记录使用独立 Statement，防止 SQLITE_BUSY_SNAPSHOT
                        ResultSet rs2 = st2.executeQuery(
                                "SELECT * FROM ks_bank_loan_requests WHERE borrower_uuid='" + uuid + "' ORDER BY requested_at DESC LIMIT 50");
                        while (rs2.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("type", "request");
                            row.put("id", rs2.getString("id"));
                            row.put("bank_id", rs2.getString("bank_id"));
                            row.put("principal", rs2.getDouble("principal"));
                            row.put("term_days", rs2.getInt("term_days"));
                            row.put("status", rs2.getString("status"));
                            row.put("requested_at", rs2.getLong("requested_at"));
                            items.add(row);
                        }
                    }
                }
                case 4 -> {
                    // 我经营的银行（owner_uuids 含本人）
                    ResultSet rs = st.executeQuery(
                            "SELECT b.*, (SELECT COUNT(*) FROM ks_bank_loan_requests r WHERE r.bank_id=b.id AND r.status='PENDING') AS pending_count "
                                    + "FROM ks_bank_banks b WHERE b.owner_uuids LIKE '%" + uuid + "%' ORDER BY b.created_at DESC LIMIT 100");
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("name", rs.getString("name"));
                        row.put("total_assets", rs.getDouble("total_assets"));
                        row.put("interest_rate", rs.getDouble("interest_rate"));
                        row.put("loan_rate", rs.getDouble("loan_rate"));
                        row.put("status", rs.getString("status"));
                        row.put("pending_count", rs.getInt("pending_count"));
                        items.add(row);
                    }
                }
                case 5 -> {
                    // 选中银行的待审批贷款申请
                    if (selectedBankId != null) {
                        ResultSet rs = st.executeQuery(
                                "SELECT * FROM ks_bank_loan_requests WHERE bank_id='" + selectedBankId.replace("'", "''")
                                        + "' AND status='PENDING' ORDER BY requested_at DESC LIMIT 200");
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("id", rs.getString("id"));
                            row.put("bank_id", rs.getString("bank_id"));
                            row.put("borrower_name", rs.getString("borrower_name"));
                            row.put("principal", rs.getDouble("principal"));
                            row.put("term_days", rs.getInt("term_days"));
                            row.put("requested_at", rs.getLong("requested_at"));
                            items.add(row);
                        }
                    }
                }
                case 6 -> {
                    // 选中银行的成员列表（含所有者标记 + 个人授权汇总）
                    if (selectedBankId != null) {
                        String bid = selectedBankId.replace("'", "''");
                        String ownerUuids = "";
                        try (ResultSet rs = st.executeQuery("SELECT owner_uuids FROM ks_bank_banks WHERE id='" + bid + "'")) {
                            if (rs.next()) ownerUuids = String.valueOf(rs.getString("owner_uuids"));
                        }
                        Map<String, List<String>> grants = new HashMap<>();
                        try (Statement st2 = conn.createStatement();
                             ResultSet rs2 = st2.executeQuery("SELECT player_uuid, permission FROM ks_bank_permissions WHERE bank_id='" + bid + "'")) {
                            while (rs2.next()) grants.computeIfAbsent(rs2.getString(1), k -> new ArrayList<>()).add(rs2.getString(2));
                        }
                        try (Statement st3 = conn.createStatement();
                             ResultSet rs3 = st3.executeQuery("SELECT * FROM ks_bank_members WHERE bank_id='" + bid + "' ORDER BY joined_at ASC LIMIT 200")) {
                            while (rs3.next()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                String memberUuid = rs3.getString("player_uuid");
                                row.put("player_uuid", memberUuid);
                                row.put("player_name", rs3.getString("player_name"));
                                row.put("role", rs3.getString("role"));
                                row.put("joined_at", rs3.getLong("joined_at"));
                                row.put("is_owner", ownerUuids.contains(memberUuid));
                                row.put("grants", grants.getOrDefault(memberUuid, List.of()));
                                items.add(row);
                            }
                        }
                    }
                }
                case 7 -> {
                    // 选中成员的 6 项权限开关
                    if (selectedBankId != null && selectedMemberUuid != null) {
                        Set<String> granted = new HashSet<>();
                        try (ResultSet rs = st.executeQuery("SELECT permission FROM ks_bank_permissions WHERE bank_id='"
                                + selectedBankId.replace("'", "''") + "' AND player_uuid='" + selectedMemberUuid.replace("'", "''") + "'")) {
                            while (rs.next()) granted.add(rs.getString(1));
                        }
                        for (String perm : BANK_PERMISSIONS) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("permission", perm);
                            row.put("granted", granted.contains(perm));
                            items.add(row);
                        }
                    }
                }
                case 8 -> {
                    // 抵押物拍卖（只读列表 + 出价）
                    try (ResultSet rs = st.executeQuery(
                            "SELECT * FROM ks_bank_collateral_auctions WHERE status='OPEN' ORDER BY closes_at ASC LIMIT 200")) {
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("id", rs.getString("id"));
                            row.put("asset_type", rs.getString("asset_type"));
                            row.put("asset_ref", rs.getString("asset_ref"));
                            row.put("current_price", rs.getDouble("current_price"));
                            row.put("closes_at", rs.getLong("closes_at"));
                            items.add(row);
                        }
                    } catch (Exception ignored) {
                        // 拍卖表不存在（bank extra 未加载）时静默为空列表
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("BankGui 加载失败: " + e.getMessage());
        }
    }

    private void build() {
        String[] viewNames = {"账户总览", "浏览银行", "创建银行", "贷款管理", "我的银行", "贷款审批", "成员管理", "成员权限", "抵押拍卖"};
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8银行 — " + viewNames[view] + " 第" + (page + 1) + "页"));

        // View tabs (first row)
        Material[] tabIcons = {Material.GOLD_BLOCK, Material.BOOKSHELF, Material.OAK_SIGN, Material.EMERALD, Material.WRITABLE_BOOK};
        String[] tabLabels = {"§6账户", "§e浏览", "§a创建", "§b贷款", "§d经营"};
        for (int v = 0; v < TAB_COUNT; v++) {
            String label = tabLabels[v] + (v == view ? " §l◀" : "");
            inventory.setItem(v, navButton(tabIcons[v], label, v == view ? "§7（当前）" : "§7点击切换"));
        }

        // Create bank view special layout
        if (view == 2) {
            buildCreateView();
        } else {
            // Content
            int start = page * PAGE_SIZE;
            for (int i = 0; i < PAGE_SIZE && (start + i) < items.size(); i++) {
                inventory.setItem(9 + i, buildItem(items.get(start + i)));
            }
            if (items.isEmpty()) inventory.setItem(22, emptyHint());
        }

        if (page > 0)
            inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        if (view == 5 || view == 6)
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回我的银行", "§7回到经营列表"));
        else if (view == 7)
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回成员列表", "§7回到成员管理"));
        else if (view == 8)
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回账户总览", "§7回到账户页"));
        else
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回主菜单", "§7回到经济面板"));
        if (view == 6)
            inventory.setItem(47, navButton(Material.EMERALD, "§a➕ 添加成员",
                    "§7点击后在聊天栏输入玩家名", "§7新成员初始岗位固定为 §f普通成员"));
        if (view == 0) {
            if (viewer != null && (viewer.hasPermission("kseco.admin") || plugin.featureGate().isOpen("transfer"))) {
                inventory.setItem(46, navButton(Material.PAPER, "§a💸 玩家转账",
                        "§7银行附属功能 · 使用独立开放开关"));
            }
            inventory.setItem(47, navButton(Material.GOLDEN_HORSE_ARMOR, "§6🏷 抵押物拍卖",
                    "§7浏览进行中的抵押物拍卖并出价"));
            inventory.setItem(48, buildGuidanceButton());
        }
        if (view != 2 && (page + 1) * PAGE_SIZE < items.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));

        fillEmpty();
    }

    private void buildCreateView() {
        inventory.setItem(13, navButton(Material.OAK_SIGN, "§a➕ 创建商业银行",
                "§7点击后在聊天栏输入银行名称",
                "§7然后输入初始资本金额"));
        inventory.setItem(22, navButton(Material.KNOWLEDGE_BOOK, "§e创建说明",
                "§71. 点击上方按钮",
                "§72. 在聊天栏输入银行名称",
                "§73. 再输入初始资本（§c真实从余额扣除§7）",
                "§74. 初始资本最低 §e50,000§7（管理员可调）",
                "§75. 默认利率：存款1% 贷款5%"));
        inventory.setItem(31, navButton(Material.GOLD_INGOT, "§6当前余额: §e" + plugin.vaultHook().format(vaultBalance),
                "§7初始资本从这里扣除，余额不足会被拒绝"));
    }

    private ItemStack buildItem(Map<String, Object> item) {
        return switch (view) {
            case 0 -> buildAccountItem(item);
            case 1 -> buildBankItem(item);
            case 3 -> buildLoanItem(item);
            case 4 -> buildManageBankItem(item);
            case 5 -> buildLoanRequestItem(item);
            case 6 -> buildMemberItem(item);
            case 7 -> buildPermToggleItem(item);
            case 8 -> buildAuctionItem(item);
            default -> new ItemStack(Material.BARRIER);
        };
    }

    private ItemStack buildMemberItem(Map<String, Object> m) {
        boolean isOwner = Boolean.TRUE.equals(m.get("is_owner"));
        String role = String.valueOf(m.getOrDefault("role", "MEMBER"));
        ItemStack stack = new ItemStack(isOwner ? Material.GOLDEN_HELMET
                : "DIRECTOR".equalsIgnoreCase(role) ? Material.DIAMOND_HELMET
                : "MANAGER".equalsIgnoreCase(role) ? Material.IRON_HELMET
                : "TELLER".equalsIgnoreCase(role) ? Material.CHAINMAIL_HELMET : Material.LEATHER_HELMET);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        String name = String.valueOf(m.getOrDefault("player_name", m.get("player_uuid")));
        meta.displayName(Component.text((isOwner ? "§6👑 " : "§f👤 ") + name));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("岗位: " + (isOwner ? "所有者" : roleLabel(role)), isOwner ? NamedTextColor.GOLD : NamedTextColor.GRAY));
        Object joined = m.get("joined_at");
        if (joined instanceof Number n && n.longValue() > 0)
            lore.add(Component.text("加入: " + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(n.longValue() * 1000)), NamedTextColor.GRAY));
        @SuppressWarnings("unchecked")
        List<String> grants = (List<String>) m.getOrDefault("grants", List.of());
        if (!grants.isEmpty()) {
            lore.add(Component.text("个人授权:", NamedTextColor.GRAY));
            for (String g : grants) lore.add(Component.text(" · " + BANK_PERM_CN.getOrDefault(g, g), NamedTextColor.DARK_AQUA));
        }
        lore.add(Component.text("UUID: " + m.get("player_uuid"), NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        if (isOwner) {
            lore.add(Component.text("§7所有者不可被修改/移除", NamedTextColor.GRAY));
        } else {
            lore.add(Component.text("§a§l左键循环岗位 §7| §c§l右键移除", NamedTextColor.YELLOW));
            lore.add(Component.text("§e§lShift+左键 编辑个人权限", NamedTextColor.YELLOW));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildPermToggleItem(Map<String, Object> p) {
        boolean granted = Boolean.TRUE.equals(p.get("granted"));
        String perm = String.valueOf(p.get("permission"));
        ItemStack stack = new ItemStack(granted ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(Component.text((granted ? "§a✔ " : "§7✘ ") + BANK_PERM_CN.getOrDefault(perm, perm)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("成员: " + (selectedMemberName != null ? selectedMemberName : selectedMemberUuid), NamedTextColor.GRAY));
        lore.add(Component.text("权限键: " + perm, NamedTextColor.DARK_GRAY));
        lore.add(Component.text("状态: " + (granted ? "已单独授予" : "未单独授予（可能仍由岗位模板赋予）"), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text(granted ? "§c§l点击撤销该授权" : "§a§l点击授予该权限", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildAuctionItem(Map<String, Object> a) {
        ItemStack stack = new ItemStack(Material.GOLDEN_HORSE_ARMOR);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(Component.text("§6🏷 拍卖 " + assetTypeLabel(a.get("asset_type")) + ": " + a.get("asset_ref")));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("当前价: §e" + plugin.vaultHook().format(item(a, "current_price")), NamedTextColor.GRAY));
        Object closes = a.get("closes_at");
        if (closes instanceof Number n && n.longValue() > 0)
            lore.add(Component.text("截止: " + new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(n.longValue() * 1000)), NamedTextColor.GRAY));
        lore.add(Component.text("拍卖ID: " + a.get("id"), NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l点击出价（聊天输入金额）", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildAccountItem(Map<String, Object> a) {
        ItemStack stack = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String bankName = String.valueOf(a.get("bank_name"));
        double balance = ((Number) a.get("balance")).doubleValue();
        String bankId = String.valueOf(a.get("bank_id"));

        double depositRate = item(a, "deposit_rate");
        double interestEarned = item(a, "interest_earned");

        meta.displayName(Component.text("§6🏦 " + bankName));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("余额: §e" + plugin.vaultHook().format(balance), NamedTextColor.GRAY));
        lore.add(Component.text("存款利率: " + String.format("%.2f%%", depositRate * 100) + " §8/结算周期", NamedTextColor.GRAY));
        lore.add(Component.text("累计利息: " + plugin.vaultHook().format(interestEarned), NamedTextColor.GRAY));
        lore.add(Component.text("银行ID: " + bankId, NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键存款 §7| §c§l右键取款", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static double item(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static String roleLabel(String role) {
        return switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "MEMBER" -> "普通成员";
            case "TELLER" -> "柜员";
            case "MANAGER" -> "经理";
            case "DIRECTOR" -> "董事";
            default -> role == null ? "未知" : role;
        };
    }

    private static String statusLabel(String status) {
        return switch (status == null ? "" : status.toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "正常还款中";
            case "PENDING" -> "待审批";
            case "APPROVED" -> "已批准";
            case "REJECTED" -> "已拒绝";
            case "OVERDUE" -> "已逾期";
            case "REPAID", "COMPLETED" -> "已结清";
            case "CANCELLED" -> "已取消";
            default -> status == null ? "未知" : status;
        };
    }

    private static String assetTypeLabel(Object raw) {
        String type = String.valueOf(raw);
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "PROPERTY", "HOUSE" -> "房产";
            case "LAND", "PLOT" -> "地块";
            case "ITEM" -> "物品";
            default -> type;
        };
    }

    private ItemStack buildBankItem(Map<String, Object> b) {
        ItemStack stack = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String name = String.valueOf(b.get("name"));
        double assets = ((Number) b.get("total_assets")).doubleValue();
        double depositRate = ((Number) b.get("interest_rate")).doubleValue();
        double loanRate = ((Number) b.get("loan_rate")).doubleValue();
        int members = ((Number) b.get("member_count")).intValue();
        String bankId = String.valueOf(b.get("id"));

        meta.displayName(Component.text("§e🏦 " + name));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("总资产: " + plugin.vaultHook().format(assets), NamedTextColor.GRAY));
        lore.add(Component.text("存款利率: " + String.format("%.2f%%", depositRate * 100), NamedTextColor.GRAY));
        lore.add(Component.text("贷款利率: " + String.format("%.2f%%", loanRate * 100), NamedTextColor.GRAY));
        lore.add(Component.text("成员数: " + members, NamedTextColor.GRAY));
        lore.add(Component.text("ID: " + bankId, NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键存款/开户 §7| §e§l右键申请贷款", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildManageBankItem(Map<String, Object> b) {
        int pending = (int) item(b, "pending_count");
        ItemStack stack = new ItemStack(pending > 0 ? Material.GOLDEN_HELMET : Material.IRON_HELMET);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(Component.text("§d🏛 " + b.get("name") + (pending > 0 ? " §c(" + pending + "待审)" : "")));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("总资产: " + plugin.vaultHook().format(item(b, "total_assets")), NamedTextColor.GRAY));
        lore.add(Component.text("存款利率: " + String.format("%.2f%%", item(b, "interest_rate") * 100) + " §8/周期", NamedTextColor.GRAY));
        lore.add(Component.text("贷款利率: " + String.format("%.2f%%", item(b, "loan_rate") * 100), NamedTextColor.GRAY));
        lore.add(Component.text("待审批贷款: " + pending + " 笔", pending > 0 ? NamedTextColor.RED : NamedTextColor.GRAY));
        lore.add(Component.text("ID: " + b.get("id"), NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键审批贷款 §7| §e§lShift+左键设置利率", NamedTextColor.YELLOW));
        lore.add(Component.text("§b§l右键管理成员与权限", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildLoanRequestItem(Map<String, Object> r) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String borrower = String.valueOf(r.getOrDefault("borrower_name", "?"));
        meta.displayName(Component.text("§e📋 " + borrower + " 的贷款申请"));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("金额: §e" + plugin.vaultHook().format(item(r, "principal")), NamedTextColor.GRAY));
        lore.add(Component.text("期限: " + r.get("term_days") + " 天", NamedTextColor.GRAY));
        lore.add(Component.text("申请ID: " + r.get("id"), NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键批准放款 §7| §c§l右键拒绝", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildLoanItem(Map<String, Object> l) {
        String type = String.valueOf(l.getOrDefault("type", "loan"));
        boolean isRequest = "request".equals(type);

        ItemStack stack = new ItemStack(isRequest ? Material.CLOCK : Material.EMERALD);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String status = String.valueOf(l.get("status"));
        double principal = ((Number) l.get("principal")).doubleValue();
        double remaining = isRequest ? 0 : ((Number) l.get("remaining")).doubleValue();

        if (isRequest) {
            meta.displayName(Component.text("§e📋 贷款申请 " + l.get("id").toString().substring(0, Math.min(8, l.get("id").toString().length()))));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("金额: " + plugin.vaultHook().format(principal), NamedTextColor.GRAY));
            lore.add(Component.text("期限: " + l.get("term_days") + " 天", NamedTextColor.GRAY));
            lore.add(Component.text("状态: " + statusLabel(status), NamedTextColor.YELLOW));
            meta.lore(lore);
        } else {
            meta.displayName(Component.text((status.equals("ACTIVE") ? "§c" : "§7") + "💰 贷款 - " + l.get("bank_name")));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("本金: " + plugin.vaultHook().format(principal), NamedTextColor.GRAY));
            lore.add(Component.text("剩余: " + plugin.vaultHook().format(remaining), NamedTextColor.GRAY));
            lore.add(Component.text("利率: " + String.format("%.2f%%", ((Number) l.get("interest_rate")).doubleValue() * 100), NamedTextColor.GRAY));
            lore.add(Component.text("期限: " + l.get("term_days") + " 天", NamedTextColor.GRAY));
            lore.add(Component.text("状态: " + statusLabel(status), status.equals("ACTIVE") ? NamedTextColor.RED : NamedTextColor.GRAY));
            if (status.equals("ACTIVE")) {
                lore.add(Component.empty());
                lore.add(Component.text("§a§l点击还款", NamedTextColor.YELLOW));
            }
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack emptyHint() {
        String msg = switch (view) {
            case 0 -> "§7暂无银行账户 §7（浏览银行左键存款即自动开户）";
            case 1 -> "§7暂无可浏览的银行";
            case 3 -> "§7暂无贷款 §7（浏览银行申请贷款）";
            case 4 -> "§7你没有经营中的银行 §7（创建 tab 可开行）";
            case 5 -> "§7该银行暂无待审批的贷款申请";
            default -> "§7无数据";
        };
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text(msg)); item.setItemMeta(meta); }
        return item;
    }

    // ---- Write operations via reflection ----

    private void doDeposit(Player player, String bankId, double amount) {
        Object result = plugin.callExtraManager("ks-eco-bank", "bankManager", "deposit",
                new Class<?>[]{String.class, UUID.class, double.class},
                bankId, player.getUniqueId(), amount);
        if (result instanceof Boolean ok && ok) {
            player.sendMessage("§a已存入 " + plugin.vaultHook().format(amount));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
        } else {
            player.sendMessage("§c存款失败，请检查银行是否可用。");
        }
    }

    private void doWithdraw(Player player, String bankId, double amount) {
        Object result = plugin.callExtraManager("ks-eco-bank", "bankManager", "withdraw",
                new Class<?>[]{String.class, UUID.class, double.class},
                bankId, player.getUniqueId(), amount);
        if (result instanceof Boolean ok && ok) {
            player.sendMessage("§a已取出 " + plugin.vaultHook().format(amount));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
        } else {
            player.sendMessage("§c取款失败，余额不足或银行不可用。");
        }
    }

    private void doCreateBank(Player player, String name, double capital) {
        // 5 参重载：真实扣除初始资本 + 资质门槛校验（默认最低 5 万）
        Object result = plugin.callExtraManager("ks-eco-bank", "bankManager", "createBank",
                new Class<?>[]{String.class, String.class, java.util.List.class, double.class, UUID.class},
                name, "COMMERCIAL", List.of(player.getUniqueId()), capital, player.getUniqueId());
        if (result != null) {
            player.sendMessage("§a银行 「" + name + "」 创建成功！已扣除初始资本 " + plugin.vaultHook().format(capital));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        } else {
            player.sendMessage("§c创建银行失败：余额不足、初始资本低于门槛（默认 5 万）或银行模块未加载。");
        }
    }

    private void doRequestLoan(Player player, String bankId, double principal, int days) {
        Object result = plugin.callExtraManager("ks-eco-bank", "bankManager", "requestLoan",
                new Class<?>[]{String.class, UUID.class, String.class, double.class, int.class},
                bankId, player.getUniqueId(), player.getName(), principal, days);
        if (result instanceof String loanId) {
            player.sendMessage("§a贷款申请已提交！金额: " + plugin.vaultHook().format(principal) + " 期限: " + days + "天 申请ID: " + loanId);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        } else {
            player.sendMessage("§c贷款申请失败。");
        }
    }

    private void doRepayLoan(Player player, String loanId, double amount) {
        Object result = plugin.callExtraManager("ks-eco-bank", "bankManager", "repayLoan",
                new Class<?>[]{String.class, UUID.class, double.class},
                loanId, player.getUniqueId(), amount);
        if (result instanceof Boolean ok && ok) {
            player.sendMessage("§a已还款 " + plugin.vaultHook().format(amount));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
        } else {
            player.sendMessage("§c还款失败，请检查余额或贷款状态。");
        }
    }

    /** 写操作前按具体权限复查，管理员和银行所有者自动通过。 */
    private boolean hasBankPermission(Player player, String bankId, String permission) {
        if (isBankOwnerOrAdminGui(player, bankId)) return true;
        var provider = plugin.bankAccessProvider();
        return provider != null && provider.hasPermission(bankId, player.getUniqueId(), permission);
    }

    private void doDecideLoanRequest(Player player, String bankId, String requestId, boolean approve) {
        if (!hasBankPermission(player, bankId, "APPROVE_LOAN")) {
            player.sendMessage("§c你没有该银行的贷款审批权限。");
            return;
        }
        Object result = plugin.callExtraManager("ks-eco-bank", "bankManager",
                approve ? "approveLoanRequest" : "rejectLoanRequest",
                new Class<?>[]{String.class}, requestId);
        if (result instanceof Boolean ok && ok) {
            player.sendMessage(approve ? "§a已批准并放款（已从银行资产扣除本金）。" : "§e已拒绝该贷款申请。");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, approve ? 2.0f : 0.8f);
        } else {
            player.sendMessage("§c操作失败：申请不存在/已处理，或银行资产不足以放款。");
        }
    }

    private void doSetRates(Player player, String bankId, double loanRate, double depositRate) {
        if (!hasBankPermission(player, bankId, "SET_RATES")) {
            player.sendMessage("§c你没有该银行的利率设置权限。");
            return;
        }
        // 央行基准利率 ± 浮动限制 + 全局利率区间校验（与 web 端 /api/bank/rates/set 同一套规则）
        double baseRate = 0.035, adjustLimit = 0.02;
        double rateMin = 0.01, rateMax = 0.20;
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT base_rate FROM ks_bank_cb_rates ORDER BY set_at DESC LIMIT 1")) {
                if (rs.next()) baseRate = rs.getDouble("base_rate");
            }
            try (Statement st2 = conn.createStatement();
                 ResultSet rs2 = st2.executeQuery(
                         "SELECT config_key, config_value FROM ks_bank_cb_config "
                                 + "WHERE config_key IN ('rate_adjust_limit','rate_min','rate_max')")) {
                while (rs2.next()) {
                    switch (rs2.getString("config_key")) {
                        case "rate_adjust_limit" -> adjustLimit = rs2.getDouble("config_value");
                        case "rate_min" -> rateMin = rs2.getDouble("config_value");
                        case "rate_max" -> rateMax = rs2.getDouble("config_value");
                    }
                }
            } catch (Exception ignored) {}

            if (loanRate < baseRate - adjustLimit || loanRate > baseRate + adjustLimit) {
                player.sendMessage(String.format("§c贷款利率超出央行允许范围 [%.1f%%, %.1f%%]",
                        (baseRate - adjustLimit) * 100, (baseRate + adjustLimit) * 100));
                return;
            }
            if (loanRate < rateMin || loanRate > rateMax) {
                player.sendMessage(String.format("§c贷款利率超出央行利率区间 [%.1f%%, %.1f%%]",
                        rateMin * 100, rateMax * 100));
                return;
            }
            if (depositRate < 0 || depositRate > baseRate) {
                player.sendMessage(String.format("§c存款利率应在 0%% 到 %.1f%% 之间", baseRate * 100));
                return;
            }
            if (depositRate > rateMax) {
                player.sendMessage(String.format("§c存款利率超出央行利率上限 %.1f%%", rateMax * 100));
                return;
            }

            conn.setAutoCommit(false);
            try {
                try (var bank = conn.prepareStatement(
                        "UPDATE ks_bank_banks SET loan_rate=?,interest_rate=? WHERE id=?")) {
                    bank.setDouble(1, loanRate);
                    bank.setDouble(2, depositRate);
                    bank.setString(3, bankId);
                    if (bank.executeUpdate() != 1) throw new java.sql.SQLException("bank no longer exists");
                }
                long now = System.currentTimeMillis() / 1000;
                PortableSqlMutation.upsert(conn,
                        "UPDATE ks_bank_rates SET loan_rate=?,deposit_rate=?,updated_at=? WHERE bank_id=?", update -> {
                            update.setDouble(1, loanRate);
                            update.setDouble(2, depositRate);
                            update.setLong(3, now);
                            update.setString(4, bankId);
                        }, "INSERT INTO ks_bank_rates (bank_id,loan_rate,deposit_rate,updated_at) VALUES (?,?,?,?)",
                        insert -> {
                            insert.setString(1, bankId);
                            insert.setDouble(2, loanRate);
                            insert.setDouble(3, depositRate);
                            insert.setLong(4, now);
                        });
                conn.commit();
            } catch (Exception failure) {
                conn.rollback();
                throw failure;
            } finally {
                conn.setAutoCommit(true);
            }
            player.sendMessage(String.format("§a利率已更新：贷款 %.2f%% / 存款 %.2f%%（每结算周期）",
                    loanRate * 100, depositRate * 100));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        } catch (Exception e) {
            player.sendMessage("§c利率设置失败: " + e.getMessage());
        }
    }

    private Player viewer = null;

    /** 王国开发银行（引导贷款）状态按钮 — 数据来自 BankManager.guidanceStatus。 */
    private ItemStack buildGuidanceButton() {
        List<String> lore = new ArrayList<>();
        boolean eligible = false;
        Object status = viewer == null ? null : plugin.callExtraManager("ks-eco-bank", "bankManager", "guidanceStatus",
                new Class<?>[]{UUID.class}, viewer.getUniqueId());
        if (status instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("available"))) {
            lore.add("§7一次性启动贷款: §e" + plugin.vaultHook().format(m.get("starterAmount") instanceof Number n ? n.doubleValue() : 0));
            lore.add("§7固定利率: " + String.format("%.2f%%", (m.get("interestRate") instanceof Number n ? n.doubleValue() : 0) * 100)
                    + " §7/ 期限 " + m.get("termDays") + " 天");
            eligible = Boolean.TRUE.equals(m.get("eligible"));
            lore.add(eligible ? "§a状态: 可领取" : "§c状态: " + m.get("reason"));
            if (eligible) { lore.add(""); lore.add("§a§lShift+左键 领取启动贷款（生成需偿还的贷款）"); }
        } else {
            lore.add("§c引导银行暂不可用");
        }
        return navButton(eligible ? Material.GOLDEN_APPLE : Material.APPLE, "§6🏦 王国开发银行（引导贷款）", lore.toArray(new String[0]));
    }

    private void doClaimGuidanceLoan(Player player) {
        Object result = plugin.callExtraManager("ks-eco-bank", "bankManager", "claimStarterLoan",
                new Class<?>[]{UUID.class}, player.getUniqueId());
        if (result instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("success"))) {
            player.sendMessage("§a启动贷款已到账！这是一笔需要按期偿还的贷款，可在「贷款管理」还款。");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        } else {
            Object reason = result instanceof Map<?, ?> m2 ? m2.get("reason") : null;
            player.sendMessage("§c领取失败" + (reason != null ? ": " + reason : ""));
        }
    }

    // ---- Member management (G2) ----

    /** 是否银行所有者（owner_uuids 含本人）或管理员。 */
    private boolean isBankOwnerOrAdminGui(Player player, String bankId) {
        if (player.hasPermission("kseco.admin")) return true;
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             var query = conn.prepareStatement("SELECT owner_uuids FROM ks_bank_banks WHERE id=?")) {
            query.setString(1, bankId);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() && String.valueOf(rs.getString("owner_uuids"))
                        .contains(player.getUniqueId().toString());
            }
        } catch (Exception e) { return false; }
    }

    /** 成员增删授权：owner/admin 或 access provider 的显式 MANAGE_MEMBERS（与 web canManageBankMembers 同一语义）。 */
    private boolean canManageMembersGui(Player player, String bankId) {
        if (isBankOwnerOrAdminGui(player, bankId)) return true;
        var provider = plugin.bankAccessProvider();
        return provider != null && provider.hasPermission(bankId, player.getUniqueId(), "MANAGE_MEMBERS");
    }

    private void doAddBankMember(Player player, String bankId, String targetName) {
        if (!canManageMembersGui(player, bankId)) {
            player.sendMessage("§c你没有该银行的成员管理权限。"); return;
        }
        UUID targetUuid = null; String resolvedName = targetName;
        for (org.bukkit.OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(targetName)) {
                targetUuid = op.getUniqueId(); resolvedName = op.getName(); break;
            }
        }
        if (targetUuid == null) { player.sendMessage("§c未找到玩家: " + targetName); return; }
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            try (var query = conn.prepareStatement(
                    "SELECT COUNT(*) FROM ks_bank_members WHERE bank_id=? AND player_uuid=?")) {
                query.setString(1, bankId);
                query.setString(2, targetUuid.toString());
                try (ResultSet rs = query.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) { player.sendMessage("§c该玩家已是本行成员。"); return; }
                }
            }
            // 与 web 规则一致：新成员只能以 MEMBER 加入，晋升走岗位/权限管理
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_bank_members (bank_id, player_uuid, player_name, role, joined_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, bankId); ps.setString(2, targetUuid.toString()); ps.setString(3, resolvedName);
                ps.setString(4, "MEMBER"); ps.setLong(5, System.currentTimeMillis() / 1000);
                ps.executeUpdate();
            }
            player.sendMessage("§a已添加成员 " + resolvedName + "（普通成员）。");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        } catch (Exception e) {
            player.sendMessage("§c添加成员失败: " + e.getMessage());
        }
    }

    private void doCycleMemberRole(Player player, String bankId, String memberUuid, String currentRole) {
        // 岗位变更 owner-only（镜像 BankAccessProviderImpl.setMemberRole）
        if (!isBankOwnerOrAdminGui(player, bankId)) {
            player.sendMessage("§c只有银行所有者可以调整成员岗位。"); return;
        }
        int idx = MEMBER_ROLE_CYCLE.indexOf(currentRole == null ? "MEMBER" : currentRole.toUpperCase(Locale.ROOT));
        String next = MEMBER_ROLE_CYCLE.get((idx + 1) % MEMBER_ROLE_CYCLE.size());
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             var ps = conn.prepareStatement("UPDATE ks_bank_members SET role=? WHERE bank_id=? AND player_uuid=?")) {
            ps.setString(1, next); ps.setString(2, bankId); ps.setString(3, memberUuid);
            if (ps.executeUpdate() > 0) {
                player.sendMessage("§a岗位已调整为“" + roleLabel(next) + "”（岗位模板权限自动生效）。");
            } else player.sendMessage("§c目标不是本行成员。");
        } catch (Exception e) {
            player.sendMessage("§c岗位调整失败: " + e.getMessage());
        }
    }

    private void doRemoveBankMember(Player player, String bankId, String memberUuid, String memberName) {
        if (!canManageMembersGui(player, bankId)) {
            player.sendMessage("§c你没有该银行的成员管理权限。"); return;
        }
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            // 禁止移除所有者（与 web 规则一致）
            try (var query = conn.prepareStatement("SELECT owner_uuids FROM ks_bank_banks WHERE id=?")) {
                query.setString(1, bankId);
                try (ResultSet rs = query.executeQuery()) {
                    if (rs.next() && String.valueOf(rs.getString("owner_uuids")).contains(memberUuid)) {
                    player.sendMessage("§c不能移除银行所有者。"); return;
                    }
                }
            }
            try (var ps = conn.prepareStatement("DELETE FROM ks_bank_members WHERE bank_id=? AND player_uuid=?")) {
                ps.setString(1, bankId); ps.setString(2, memberUuid);
                if (ps.executeUpdate() > 0) player.sendMessage("§e已移除成员 " + memberName + "。");
                else player.sendMessage("§c目标不是本行成员。");
            }
            try (var ps2 = conn.prepareStatement("DELETE FROM ks_bank_permissions WHERE bank_id=? AND player_uuid=?")) {
                ps2.setString(1, bankId); ps2.setString(2, memberUuid); ps2.executeUpdate();
            }
        } catch (Exception e) {
            player.sendMessage("§c移除成员失败: " + e.getMessage());
        }
    }

    private void doToggleBankPermission(Player player, String bankId, String memberUuid, String permission, boolean grant) {
        // 授权规则镜像 BankAccessProviderImpl.setIndividualPermission：
        // owner/admin，或（自身有 MANAGE_PERMISSIONS 且自身也持有目标权限）
        boolean owner = isBankOwnerOrAdminGui(player, bankId);
        if (!owner) {
            var provider = plugin.bankAccessProvider();
            boolean ok = provider != null
                    && provider.hasPermission(bankId, player.getUniqueId(), "MANAGE_PERMISSIONS")
                    && provider.hasPermission(bankId, player.getUniqueId(), permission);
            if (!ok) { player.sendMessage("§c你没有授予/撤销该权限的资格。"); return; }
        }
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            // 目标必须是本行成员
            try (var check = conn.prepareStatement("SELECT 1 FROM ks_bank_members WHERE bank_id=? AND player_uuid=?")) {
                check.setString(1, bankId); check.setString(2, memberUuid);
                if (!check.executeQuery().next()) { player.sendMessage("§c目标不是本行成员。"); return; }
            }
            if (grant) {
                long now = System.currentTimeMillis() / 1000;
                PortableSqlMutation.upsert(conn,
                        "UPDATE ks_bank_permissions SET granted_by=?,granted_at=? "
                                + "WHERE bank_id=? AND player_uuid=? AND permission=?", update -> {
                            update.setString(1, player.getUniqueId().toString());
                            update.setLong(2, now);
                            update.setString(3, bankId);
                            update.setString(4, memberUuid);
                            update.setString(5, permission);
                        }, "INSERT INTO ks_bank_permissions "
                                + "(bank_id,player_uuid,permission,granted_by,granted_at) VALUES (?,?,?,?,?)", insert -> {
                            insert.setString(1, bankId);
                            insert.setString(2, memberUuid);
                            insert.setString(3, permission);
                            insert.setString(4, player.getUniqueId().toString());
                            insert.setLong(5, now);
                        });
                player.sendMessage("§a已授予 " + BANK_PERM_CN.getOrDefault(permission, permission) + "。");
            } else {
                try (var ps = conn.prepareStatement("DELETE FROM ks_bank_permissions WHERE bank_id=? AND player_uuid=? AND permission=?")) {
                    ps.setString(1, bankId); ps.setString(2, memberUuid); ps.setString(3, permission);
                    ps.executeUpdate();
                }
                player.sendMessage("§e已撤销 " + BANK_PERM_CN.getOrDefault(permission, permission) + "。");
            }
        } catch (Exception e) {
            player.sendMessage("§c权限操作失败: " + e.getMessage());
        }
    }

    private void doPlaceAuctionBid(Player player, String auctionId, double amount) {
        Object result = plugin.callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "placeAuctionBid",
                new Class<?>[]{String.class, UUID.class, double.class},
                auctionId, player.getUniqueId(), amount);
        if (result instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("success"))) {
            player.sendMessage("§a出价成功: " + plugin.vaultHook().format(amount));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        } else {
            Object err = result instanceof Map<?, ?> m2 ? m2.get("error") : null;
            player.sendMessage("§c出价失败" + (err != null ? ": " + err : "（金额过低或拍卖已结束）"));
        }
    }

    // ---- Helpers ----

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
            if (!(event.getView().getTopInventory().getHolder() instanceof BankGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;

            // View tabs (slots 0-4)
            if (slot >= 0 && slot < TAB_COUNT) {
                if (slot != gui.view) {
                    gui.view = slot; gui.page = 0; gui.selectedBankId = null;
                    gui.loadData(player); gui.build();
                    player.openInventory(gui.getInventory());
                }
                return;
            }

            // Content slots
            if (gui.view == 2 && slot == 13) {
                // Create bank: start chat input
                player.closeInventory();
                pendingBank.put(player.getUniqueId(), new PendingCreateBank(1, null, 0));
                player.sendMessage("§a请在聊天栏输入银行名称，或输入 cancel 取消");
                return;
            }

            if (slot >= 9 && slot < 9 + PAGE_SIZE) {
                int index = gui.page * PAGE_SIZE + (slot - 9);
                if (index < gui.items.size()) {
                    Map<String, Object> item = gui.items.get(index);
                    switch (gui.view) {
                        case 0 -> {
                            String bankId = String.valueOf(item.get("bank_id"));
                            if (event.isRightClick()) {
                                // Withdraw
                                player.closeInventory();
                                pendingBank.put(player.getUniqueId(), new PendingWithdraw(bankId));
                                player.sendMessage("§c请在聊天栏输入取款金额，或输入 cancel 取消");
                            } else {
                                // Deposit
                                player.closeInventory();
                                pendingBank.put(player.getUniqueId(), new PendingDeposit(bankId));
                                player.sendMessage("§a请在聊天栏输入存款金额，或输入 cancel 取消");
                            }
                        }
                        case 1 -> {
                            String bankId = String.valueOf(item.get("id"));
                            if (event.isRightClick()) {
                                // Apply for loan
                                player.closeInventory();
                                pendingBank.put(player.getUniqueId(), new PendingLoanApply(bankId));
                                player.sendMessage("§a请在聊天栏输入 贷款金额 期限天数（如: 5000 30），或 cancel 取消");
                            } else {
                                // Deposit（后端自动开户，新玩家第一笔存款从这里进）
                                player.closeInventory();
                                pendingBank.put(player.getUniqueId(), new PendingDeposit(bankId));
                                player.sendMessage("§a请在聊天栏输入存款金额（首次存款自动开户），或 cancel 取消");
                            }
                        }
                        case 3 -> {
                            String type = String.valueOf(item.getOrDefault("type", "loan"));
                            if (!"request".equals(type)) {
                                String loanId = String.valueOf(item.get("id"));
                                double remaining = ((Number) item.get("remaining")).doubleValue();
                                String status = String.valueOf(item.get("status"));
                                if (("ACTIVE".equals(status) || "OVERDUE".equals(status)) && remaining > 0) {
                                    player.closeInventory();
                                    pendingBank.put(player.getUniqueId(), new PendingLoanRepay(loanId, remaining));
                                    player.sendMessage("§a请在聊天栏输入还款金额（剩余 " + plugin.vaultHook().format(remaining) + "），或 cancel 取消");
                                }
                            }
                        }
                        case 4 -> {
                            String bankId = String.valueOf(item.get("id"));
                            if (event.isShiftClick()) {
                                // 设置利率（chat 输入）
                                player.closeInventory();
                                pendingBank.put(player.getUniqueId(), new PendingRateSet(bankId));
                                player.sendMessage("§a请在聊天栏输入 贷款利率% 存款利率%（如: 5 1 表示贷款5%/存款1%），或 cancel 取消");
                            } else if (event.isRightClick()) {
                                // 进入成员管理
                                gui.view = 6; gui.page = 0; gui.selectedBankId = bankId;
                                gui.loadData(player); gui.build();
                                player.openInventory(gui.getInventory());
                            } else {
                                // 进入贷款审批列表
                                gui.view = 5; gui.page = 0; gui.selectedBankId = bankId;
                                gui.loadData(player); gui.build();
                                player.openInventory(gui.getInventory());
                            }
                        }
                        case 5 -> {
                            String requestId = String.valueOf(item.get("id"));
                            String bankId = String.valueOf(item.get("bank_id"));
                            gui.doDecideLoanRequest(player, bankId, requestId, !event.isRightClick());
                            gui.loadData(player); gui.build();
                            player.openInventory(gui.getInventory());
                        }
                        case 6 -> {
                            if (Boolean.TRUE.equals(item.get("is_owner"))) return;
                            String memberUuid = String.valueOf(item.get("player_uuid"));
                            String memberName = String.valueOf(item.getOrDefault("player_name", memberUuid));
                            String bankId = gui.selectedBankId;
                            if (bankId == null) return;
                            if (event.isShiftClick() && !event.isRightClick()) {
                                gui.view = 7; gui.page = 0;
                                gui.selectedMemberUuid = memberUuid; gui.selectedMemberName = memberName;
                                gui.loadData(player); gui.build();
                                player.openInventory(gui.getInventory());
                            } else if (event.isRightClick()) {
                                gui.doRemoveBankMember(player, bankId, memberUuid, memberName);
                                gui.loadData(player); gui.build();
                                player.openInventory(gui.getInventory());
                            } else {
                                gui.doCycleMemberRole(player, bankId, memberUuid, String.valueOf(item.get("role")));
                                gui.loadData(player); gui.build();
                                player.openInventory(gui.getInventory());
                            }
                        }
                        case 7 -> {
                            if (gui.selectedBankId == null || gui.selectedMemberUuid == null) return;
                            String perm = String.valueOf(item.get("permission"));
                            boolean granted = Boolean.TRUE.equals(item.get("granted"));
                            gui.doToggleBankPermission(player, gui.selectedBankId, gui.selectedMemberUuid, perm, !granted);
                            gui.loadData(player); gui.build();
                            player.openInventory(gui.getInventory());
                        }
                        case 8 -> {
                            String auctionId = String.valueOf(item.get("id"));
                            double current = item.get("current_price") instanceof Number n ? n.doubleValue() : 0;
                            player.closeInventory();
                            pendingBank.put(player.getUniqueId(), new PendingAuctionBid(auctionId, current));
                            player.sendMessage("§a请在聊天栏输入出价金额（当前价 " + plugin.vaultHook().format(current) + "），或 cancel 取消");
                        }
                    }
                }
                return;
            }

            switch (slot) {
                case 45 -> { if (gui.page > 0) { gui.page--; gui.build(); player.openInventory(gui.getInventory()); } }
                case 46 -> {
                    if (gui.view == 0) {
                        player.closeInventory();
                        new TransferGui(plugin).open(player);
                    }
                }
                case 47 -> {
                    if (gui.view == 6 && gui.selectedBankId != null) {
                        // 添加成员（聊天输入玩家名）
                        if (!gui.canManageMembersGui(player, gui.selectedBankId)) {
                            player.sendMessage("§c你没有该银行的成员管理权限。"); return;
                        }
                        player.closeInventory();
                        pendingBank.put(player.getUniqueId(), new PendingAddBankMember(gui.selectedBankId));
                        player.sendMessage("§a请在聊天栏输入要添加的玩家名，或输入 cancel 取消");
                    } else if (gui.view == 0) {
                        // 进入抵押物拍卖
                        gui.view = 8; gui.page = 0;
                        gui.loadData(player); gui.build();
                        player.openInventory(gui.getInventory());
                    }
                }
                case 48 -> {
                    if (gui.view == 0 && event.isShiftClick()) {
                        gui.doClaimGuidanceLoan(player);
                        gui.loadData(player); gui.build();
                        player.openInventory(gui.getInventory());
                    }
                }
                case 49 -> {
                    if (gui.view == 5 || gui.view == 6) {
                        // 返回"我的银行"
                        gui.view = 4; gui.page = 0; gui.selectedBankId = null;
                        gui.selectedMemberUuid = null; gui.selectedMemberName = null;
                        gui.loadData(player); gui.build();
                        player.openInventory(gui.getInventory());
                    } else if (gui.view == 7) {
                        // 返回成员列表
                        gui.view = 6; gui.page = 0;
                        gui.selectedMemberUuid = null; gui.selectedMemberName = null;
                        gui.loadData(player); gui.build();
                        player.openInventory(gui.getInventory());
                    } else if (gui.view == 8) {
                        gui.view = 0; gui.page = 0;
                        gui.loadData(player); gui.build();
                        player.openInventory(gui.getInventory());
                    } else {
                        player.closeInventory(); new EcoGuiMainMenu(plugin).open(player);
                    }
                }
                case 53 -> {
                    if (gui.view != 2 && (gui.page + 1) * PAGE_SIZE < gui.items.size()) {
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

        private static void retry(Player player, Object pending, String message) {
            pendingBank.put(player.getUniqueId(), pending);
            player.sendMessage(message);
        }

        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            pendingBank.remove(event.getPlayer().getUniqueId());
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            Object pending = pendingBank.remove(playerId);
            if (pending == null) return;

            event.setCancelled(true);
            String msg = event.getMessage().trim();
            plugin.scheduler().runPlayer(playerId, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null) return;
                if (msg.equalsIgnoreCase("cancel")) {
                    player.sendMessage("§c已取消。");
                    new BankGui(plugin).open(player);
                    return;
                }

                BankGui gui = new BankGui(plugin);

                switch (pending) {
                    case PendingDeposit pd -> {
                        try {
                            double amount = Double.parseDouble(msg);
                            if (!Double.isFinite(amount) || amount <= 0) {
                                retry(player, pending, "§c金额必须是大于 0 的有效数字，请重新输入或输入 cancel 取消。");
                                return;
                            }
                            gui.doDeposit(player, pd.bankId, amount);
                        } catch (NumberFormatException e) {
                            retry(player, pending, "§c无效金额: " + msg + "，请重新输入或输入 cancel 取消。");
                            return;
                        }
                    }
                    case PendingWithdraw pw -> {
                        try {
                            double amount = Double.parseDouble(msg);
                            if (!Double.isFinite(amount) || amount <= 0) {
                                retry(player, pending, "§c金额必须是大于 0 的有效数字，请重新输入或输入 cancel 取消。");
                                return;
                            }
                            gui.doWithdraw(player, pw.bankId, amount);
                        } catch (NumberFormatException e) {
                            retry(player, pending, "§c无效金额: " + msg + "，请重新输入或输入 cancel 取消。");
                            return;
                        }
                    }
                    case PendingCreateBank cb -> {
                        if (cb.step == 1) {
                            String name = msg;
                            if (name.length() < 2 || name.length() > 32) {
                                player.sendMessage("§c银行名称长度需在2-32字符之间。");
                                pendingBank.put(player.getUniqueId(), new PendingCreateBank(1, null, 0));
                                return;
                            }
                            pendingBank.put(player.getUniqueId(), new PendingCreateBank(2, name, 0));
                            player.sendMessage("§a请输入初始资本金额（从你余额扣除）：");
                            return;
                        } else {
                            try {
                                int capital = Integer.parseInt(msg);
                                if (capital < 100) {
                                    retry(player, pending, "§c初始资本最少100，请重新输入或输入 cancel 取消。");
                                    return;
                                }
                                gui.doCreateBank(player, cb.name, capital);
                            } catch (NumberFormatException e) {
                                player.sendMessage("§c无效金额: " + msg);
                                pendingBank.put(player.getUniqueId(), new PendingCreateBank(2, cb.name, 0));
                                return;
                            }
                        }
                    }
                    case PendingLoanApply la -> {
                        String[] parts = msg.split("\\s+");
                        if (parts.length < 2) {
                            retry(player, pending, "§c格式: 金额 期限（天），如 5000 30；请重新输入或输入 cancel 取消。");
                            return;
                        }
                        try {
                            double principal = Double.parseDouble(parts[0]);
                            int days = Integer.parseInt(parts[1]);
                            if (!Double.isFinite(principal) || principal <= 0 || days <= 0) {
                                retry(player, pending, "§c金额和天数必须大于 0，请重新输入或输入 cancel 取消。");
                                return;
                            }
                            gui.doRequestLoan(player, la.bankId, principal, days);
                        } catch (NumberFormatException e) {
                            retry(player, pending, "§c格式: 金额 期限（天），如 5000 30；请重新输入或输入 cancel 取消。");
                            return;
                        }
                    }
                    case PendingLoanRepay lr -> {
                        try {
                            double amount = Double.parseDouble(msg);
                            if (!Double.isFinite(amount) || amount <= 0) {
                                retry(player, pending, "§c金额必须是大于 0 的有效数字，请重新输入或输入 cancel 取消。");
                                return;
                            }
                            gui.doRepayLoan(player, lr.loanId, Math.min(amount, lr.remaining));
                        } catch (NumberFormatException e) {
                            retry(player, pending, "§c无效金额: " + msg + "，请重新输入或输入 cancel 取消。");
                            return;
                        }
                    }
                    case PendingRateSet rs -> {
                        String[] parts = msg.split("\\s+");
                        if (parts.length < 2) {
                            retry(player, pending, "§c格式: 贷款利率% 存款利率%，如 5 1；请重新输入或输入 cancel 取消。");
                            return;
                        }
                        try {
                            double loanRate = Double.parseDouble(parts[0]) / 100.0;
                            double depositRate = Double.parseDouble(parts[1]) / 100.0;
                            if (!Double.isFinite(loanRate) || !Double.isFinite(depositRate)) {
                                retry(player, pending, "§c利率必须是有效数字，请重新输入或输入 cancel 取消。");
                                return;
                            }
                            gui.doSetRates(player, rs.bankId, loanRate, depositRate);
                        } catch (NumberFormatException e) {
                            retry(player, pending, "§c格式: 贷款利率% 存款利率%，如 5 1；请重新输入或输入 cancel 取消。");
                            return;
                        }
                    }
                    case PendingAddBankMember am -> {
                        gui.doAddBankMember(player, am.bankId, msg);
                        gui.view = 6; gui.selectedBankId = am.bankId;
                        gui.loadData(player); gui.build();
                        player.openInventory(gui.getInventory());
                        return;
                    }
                    case PendingAuctionBid ab -> {
                        try {
                            double amount = Double.parseDouble(msg);
                            if (!Double.isFinite(amount) || amount <= ab.currentPrice) {
                                retry(player, pending, "§c出价必须是高于当前价 " + plugin.vaultHook().format(ab.currentPrice)
                                        + " 的有效数字，请重新输入或输入 cancel 取消。");
                                return;
                            }
                            gui.doPlaceAuctionBid(player, ab.auctionId, amount);
                        } catch (NumberFormatException e) {
                            retry(player, pending, "§c无效金额: " + msg + "，请重新输入或输入 cancel 取消。");
                            return;
                        }
                    }
                    default -> {}
                }
                gui.open(player);
            });
        }
    }
}
