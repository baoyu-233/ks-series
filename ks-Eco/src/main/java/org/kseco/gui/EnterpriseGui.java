package org.kseco.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业 GUI — 我的企业、浏览所有、创建企业、企业管理。
 * SQL 读 + 反射写（enterpriseManager）。
 */
public final class EnterpriseGui implements InventoryHolder {

    private final KsEco plugin;
    private Inventory inventory;
    private int view = 0; // 0=my, 1=browseAll, 2=create, 3=manage, 4=member-detail, 5=financing, 6=dividend-history
    private int page = 0;
    private String selectedEntId = null;
    private final List<Map<String, Object>> items = new ArrayList<>();
    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 36;
    private static final int MANAGE_MEMBER_PAGE_SIZE = 32;

    // Chat input
    record PendingCreateEnt(String name, double capital, int step) {} // step 1=name, 2=capital, 3=region
    record PendingEntInvite(String entId) {}
    record PendingEntInjection(String entId) {}
    record PendingDividendRate(String entId) {}
    record PendingDividendShares(String entId) {}
    record PendingSetSalary(String entId, String memberUuid, String memberName) {}
    record PendingEfRepay(String loanId, String entId, double outstanding) {}

    private String selectedMemberUuid = null;
    private String selectedMemberName = null;
    private final org.kseco.EnterprisePermissionService permService = new org.kseco.EnterprisePermissionService();
    private static final Map<String, String> ENT_PERM_CN = Map.of(
            "MANAGE_MEMBERS", "管理成员", "MANAGE_PERMISSIONS", "管理权限", "MANAGE_BIDDING", "招投标",
            "DECLARE_DIVIDEND", "分红宣派", "VIEW_FINANCE", "查看财务", "MANAGE_FUNDS", "资金管理",
            "BLINDBOX_DRAW", "企业盲盒抽取");
    static final Map<UUID, Object> pendingEnt = new ConcurrentHashMap<>();

    public EnterpriseGui(KsEco plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        this.page = 0;
        this.view = 0;
        this.selectedEntId = null;
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
                            "SELECT e.*, COALESCE(ca.balance,0) AS corporate_balance FROM ks_ent_enterprises e LEFT JOIN ks_ent_corporate_accounts ca ON e.id=ca.enterprise_id WHERE e.owner_uuids LIKE '%"
                                    + uuid + "%' OR e.id IN (SELECT enterprise_id FROM ks_ent_members WHERE player_uuid='"
                                    + uuid + "') ORDER BY e.created_at DESC");
                    while (rs.next()) {
                        items.add(rowFromRs(rs));
                    }
                }
                case 1 -> {
                    ResultSet rs = st.executeQuery(
                            "SELECT e.*, COALESCE(ca.balance,0) AS corporate_balance FROM ks_ent_enterprises e LEFT JOIN ks_ent_corporate_accounts ca ON e.id=ca.enterprise_id WHERE e.status='ACTIVE' ORDER BY e.created_at DESC LIMIT 200");
                    while (rs.next()) {
                        items.add(rowFromRs(rs));
                    }
                }
                case 3 -> {
                    if (selectedEntId == null) break;
                    // Members 和 enterprise detail 用独立 Statement，防止 SQLITE_BUSY_SNAPSHOT
                    try (Statement st2 = conn.createStatement()) {
                        ResultSet rs = st.executeQuery(
                                "SELECT * FROM ks_ent_members WHERE enterprise_id='" + selectedEntId.replace("'", "''") + "' ORDER BY role, player_name");
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("type", "member");
                            row.put("player_uuid", rs.getString("player_uuid"));
                            row.put("player_name", rs.getString("player_name"));
                            row.put("role", rs.getString("role"));
                            row.put("salary", rs.getDouble("salary"));
                            items.add(row);
                        }
                        // Enterprise detail as first item — independent Statement
                        ResultSet rs2 = st2.executeQuery(
                                "SELECT e.*, COALESCE(ca.balance,0) AS corporate_balance FROM ks_ent_enterprises e LEFT JOIN ks_ent_corporate_accounts ca ON e.id=ca.enterprise_id WHERE e.id='"
                                        + selectedEntId.replace("'", "''") + "'");
                        if (rs2.next()) {
                            Map<String, Object> info = rowFromRs(rs2);
                            info.put("type", "info");
                            items.add(0, info);
                        }
                    }
                }
                case 4 -> {
                    // 成员详情：7 项企业权限开关（个人授权状态）
                    if (selectedEntId != null && selectedMemberUuid != null) {
                        Set<String> granted = new HashSet<>();
                        try (ResultSet rs = st.executeQuery("SELECT permission FROM ks_ent_permissions WHERE enterprise_id='"
                                + selectedEntId.replace("'", "''") + "' AND player_uuid='" + selectedMemberUuid.replace("'", "''") + "'")) {
                            while (rs.next()) granted.add(rs.getString(1));
                        }
                        for (String perm : permService.allowedPermissions()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("permission", perm);
                            row.put("granted", granted.contains(perm));
                            items.add(row);
                        }
                    }
                }
                case 5 -> {
                    // 企业融资贷款（EnterpriseFinanceManager）
                    if (selectedEntId != null) {
                        Object raw = plugin.callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "listEnterpriseLoans",
                                new Class<?>[]{String.class}, selectedEntId);
                        if (raw instanceof List<?> list) {
                            for (Object o : list) {
                                if (o instanceof Map<?, ?> m) {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    m.forEach((k, v) -> row.put(String.valueOf(k), v));
                                    items.add(row);
                                }
                            }
                        }
                    }
                }
                case 6 -> {
                    // 分红记录（只读）
                    if (selectedEntId != null) {
                        try (ResultSet rs = st.executeQuery("SELECT * FROM ks_ent_dividends WHERE enterprise_id='"
                                + selectedEntId.replace("'", "''") + "' ORDER BY declared_at DESC LIMIT 45")) {
                            while (rs.next()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("id", rs.getString("id"));
                                row.put("amount", rs.getDouble("amount"));
                                try { row.put("tax", rs.getDouble("tax")); } catch (Exception ignored) {}
                                row.put("declared_at", rs.getLong("declared_at"));
                                try { row.put("status", rs.getString("status")); } catch (Exception ignored) {}
                                items.add(row);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("EnterpriseGui 加载失败: " + e.getMessage());
        }
    }

    private Map<String, Object> rowFromRs(ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("name", rs.getString("name"));
        row.put("type", rs.getString("type"));
        row.put("registered_capital", rs.getDouble("registered_capital"));
        row.put("current_assets", rs.getDouble("current_assets"));
        row.put("employee_count", rs.getInt("employee_count"));
        try { row.put("level", rs.getInt("level")); } catch (java.sql.SQLException ignored) { row.put("level", 1); }
        row.put("region", rs.getString("region"));
        row.put("status", rs.getString("status"));
        row.put("corporate_balance", rs.getDouble("corporate_balance"));
        row.put("owner_uuids", rs.getString("owner_uuids"));
        try { row.put("dividend_rate", rs.getDouble("dividend_rate")); } catch (Exception ignored) { row.put("dividend_rate", 0.0); }
        try { row.put("industry", rs.getString("industry")); } catch (Exception ignored) {}
        return row;
    }

    private void build() {
        String[] viewNames = {"我的企业", "浏览企业", "创建企业", "企业管理", "成员权限", "企业融资", "分红记录"};
        inventory = Bukkit.createInventory(this, ROWS * 9,
                Component.text("§8企业 — " + viewNames[view] + " 第" + (page + 1) + "页"));

        // View tabs
        Material[] tabIcons = {Material.IRON_BLOCK, Material.BOOKSHELF, Material.OAK_SIGN, Material.ANVIL};
        String[] tabLabels = {"§7我的", "§e浏览", "§a创建", "§c管理"};
        for (int v = 0; v < 4; v++) {
            String label = tabLabels[v] + (v == view ? " §l◀" : "");
            inventory.setItem(v, navButton(tabIcons[v], label, v == view ? "§7（当前）" : "§7点击切换"));
        }

        if (view == 2) {
            buildCreateView();
        } else if (view == 3 && selectedEntId != null) {
            buildManageView();
        } else {
            int start = page * PAGE_SIZE;
            for (int i = 0; i < PAGE_SIZE && (start + i) < items.size(); i++) {
                inventory.setItem(9 + i, buildItem(items.get(start + i)));
            }
            if (items.isEmpty()) inventory.setItem(22, emptyHint());
        }

        if (page > 0 && view != 3)
            inventory.setItem(45, navButton(Material.ARROW, "§a◀ 上一页"));
        if (view >= 4)
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回企业管理", "§7回到管理面板"));
        else
            inventory.setItem(49, navButton(Material.OAK_DOOR, "§c✕ 返回主菜单", "§7回到经济面板"));
        if (view == 4)
            inventory.setItem(47, navButton(Material.GOLD_NUGGET, "§6💵 设置该成员工资",
                    "§7点击后在聊天栏输入每期工资金额", "§7成员: §f" + (selectedMemberName != null ? selectedMemberName : "")));
        if (view != 2 && view != 3 && (page + 1) * PAGE_SIZE < items.size())
            inventory.setItem(53, navButton(Material.ARROW, "§a▶ 下一页"));
        // Back to list for manage view
        if (view == 3 && selectedEntId != null)
            inventory.setItem(53, navButton(Material.ARROW, "§a◀ 返回企业列表", "§7回到我的企业"));

        fillEmpty();
    }

    private void buildCreateView() {
        inventory.setItem(13, navButton(Material.OAK_SIGN, "§a➕ 创建企业",
                "§7点击后在聊天栏输入企业名称",
                "§7然后输入注册资本和地区"));
        inventory.setItem(22, navButton(Material.KNOWLEDGE_BOOK, "§e创建说明",
                "§71. 点击上方按钮",
                "§72. 输入企业名称",
                "§73. 输入注册资本（从余额扣除）",
                "§74. 输入所在地区（可选，enter跳过）",
                "§7类型默认为 PRIVATE（私有企业）"));
        inventory.setItem(31, navButton(Material.GOLD_INGOT, "§6当前余额",
                "§7你的余额将决定注册资本上限"));
    }

    private void buildManageView() {
        if (!items.isEmpty() && "info".equals(items.get(0).get("type"))) {
            inventory.setItem(9, buildEntInfoItem(items.get(0)));
        }
        List<Map<String, Object>> members = items.stream()
                .filter(item -> "member".equals(item.get("type"))).toList();
        int start = page * MANAGE_MEMBER_PAGE_SIZE;
        for (int i = 0; i < MANAGE_MEMBER_PAGE_SIZE && start + i < members.size(); i++) {
            inventory.setItem(10 + i, buildMemberItem(members.get(start + i)));
        }
        if (members.isEmpty()) {
            // 没有成员记录时给出提示（所有者可能只在 owner_uuids 里而不在 ks_ent_members 表）
            ItemStack hint = new ItemStack(Material.PAPER);
            ItemMeta hm = hint.getItemMeta();
            if (hm != null) {
                hm.displayName(Component.text("§7暂无成员记录"));
                hm.lore(List.of(
                    Component.text("§7成员通过邀请或申请加入后显示", NamedTextColor.DARK_GRAY),
                    Component.text("§7所有者记录不在成员表中，因此不会显示在此处", NamedTextColor.DARK_GRAY)
                ));
                hint.setItemMeta(hm);
            }
            inventory.setItem(20, hint);
        }
        inventory.setItem(45, navButton(Material.GOLD_INGOT, "§6追加注资",
                "§7从你的余额追加注册资本", "§7资金将进入企业公户"));
        inventory.setItem(44, navButton(Material.PAPER, "§b所有者分红占比",
                "§7设置每位所有者获得税后分红的比例", "§7所有比例合计必须为 100%"));
        inventory.setItem(46, navButton(Material.EMERALD, "§a分红设置与发放",
                "§7左键：设置本次分红比例", "§7右键：按当前比例发放一次分红"));
        // Invite button
        inventory.setItem(47, navButton(Material.WRITABLE_BOOK, "§d🤝 邀请成员",
                "§7点击后在聊天栏输入玩家名"));
        inventory.setItem(48, navButton(Material.LAVA_BUCKET, "§c💀 解散企业",
                "§7点击解散此企业",
                "§c§l此操作不可撤销！"));
        inventory.setItem(42, navButton(Material.BOOK, "§b📜 分红记录",
                "§7查看该企业历次分红宣派记录"));
        inventory.setItem(43, navButton(Material.GOLDEN_HORSE_ARMOR, "§6🏦 企业融资",
                "§7查看融资贷款 / 点击 ACTIVE 贷款还款",
                "§7新贷款申请（含抵押物选择）请在网页端提交"));
        if (page > 0) inventory.setItem(50, navButton(Material.ARROW, "§a◀ 上一页成员"));
        if ((page + 1) * MANAGE_MEMBER_PAGE_SIZE < members.size())
            inventory.setItem(52, navButton(Material.ARROW, "§a▶ 下一页成员"));
    }

    private ItemStack buildItem(Map<String, Object> item) {
        return switch (view) {
            case 0, 1 -> buildEntListItem(item);
            case 4 -> buildEntPermToggleItem(item);
            case 5 -> buildEfLoanItem(item);
            case 6 -> buildDividendItem(item);
            default -> new ItemStack(Material.BARRIER);
        };
    }

    private static String enterpriseTypeLabel(Object raw) {
        String type = String.valueOf(raw);
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "PRIVATE" -> "私营企业";
            case "STATE", "STATE_OWNED" -> "国有企业";
            default -> type;
        };
    }

    private static String statusLabel(Object raw) {
        String status = String.valueOf(raw);
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "正常";
            case "PENDING" -> "待处理";
            case "APPROVED" -> "已批准";
            case "REJECTED" -> "已拒绝";
            case "OVERDUE" -> "已逾期";
            case "REPAID" -> "已结清";
            case "DISSOLVED", "CLOSED" -> "已停止运营";
            case "COMPLETED" -> "已完成";
            default -> status;
        };
    }

    private static String roleLabel(String role) {
        return switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "OWNER" -> "所有者";
            case "CO_OWNER" -> "共同所有者";
            case "CEO" -> "首席执行官";
            case "MANAGER" -> "经理";
            case "EMPLOYEE", "MEMBER" -> "普通员工";
            default -> role == null ? "未知" : role;
        };
    }

    private static String collateralTypeLabel(Object raw) {
        String type = String.valueOf(raw);
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "PROPERTY", "HOUSE" -> "房产";
            case "LAND", "PLOT" -> "地块";
            case "ITEM" -> "物品";
            case "NONE", "NULL", "—" -> "无";
            default -> type;
        };
    }

    private ItemStack buildEntPermToggleItem(Map<String, Object> p) {
        boolean granted = Boolean.TRUE.equals(p.get("granted"));
        String perm = String.valueOf(p.get("permission"));
        ItemStack stack = new ItemStack(granted ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(Component.text((granted ? "§a✔ " : "§7✘ ") + ENT_PERM_CN.getOrDefault(perm, perm)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("成员: " + (selectedMemberName != null ? selectedMemberName : selectedMemberUuid), NamedTextColor.GRAY));
        lore.add(Component.text("权限键: " + perm, NamedTextColor.DARK_GRAY));
        lore.add(Component.text("状态: " + (granted ? "已单独授予" : "未单独授予（可能仍由角色模板赋予）"), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text(granted ? "§c§l点击撤销该授权" : "§a§l点击授予该权限", NamedTextColor.YELLOW));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildEfLoanItem(Map<String, Object> l) {
        String status = String.valueOf(l.getOrDefault("status", ""));
        boolean active = "ACTIVE".equalsIgnoreCase(status);
        ItemStack stack = new ItemStack(active ? Material.GOLD_INGOT : Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(Component.text((active ? "§6" : "§7") + "🏦 融资贷款 " + String.valueOf(l.getOrDefault("id", "")).substring(0, Math.min(8, String.valueOf(l.getOrDefault("id", "")).length()))));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("用途: " + l.getOrDefault("purpose", "—"), NamedTextColor.GRAY));
        Object principal = l.get("principal"); Object outstanding = l.getOrDefault("outstanding", l.get("remaining"));
        lore.add(Component.text("本金: " + plugin.vaultHook().format(principal instanceof Number n ? n.doubleValue() : 0), NamedTextColor.GRAY));
        lore.add(Component.text("未偿: §e" + plugin.vaultHook().format(outstanding instanceof Number n ? n.doubleValue() : 0), NamedTextColor.GRAY));
        Object rate = l.get("interest_rate");
        if (rate instanceof Number n) lore.add(Component.text("利率: " + String.format("%.2f%%", n.doubleValue() * 100), NamedTextColor.GRAY));
        lore.add(Component.text("状态: " + statusLabel(status), active ? NamedTextColor.RED : NamedTextColor.GRAY));
        lore.add(Component.text("抵押: " + collateralTypeLabel(l.getOrDefault("collateral_type", "—"))
                + " " + l.getOrDefault("collateral_ref", ""), NamedTextColor.DARK_GRAY));
        if (active) {
            lore.add(Component.empty());
            lore.add(Component.text("§a§l点击还款（从企业公户扣款）", NamedTextColor.YELLOW));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildDividendItem(Map<String, Object> d) {
        ItemStack stack = new ItemStack(Material.EMERALD);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        double amount = d.get("amount") instanceof Number n ? n.doubleValue() : 0;
        meta.displayName(Component.text("§a💎 分红 " + plugin.vaultHook().format(amount)));
        List<Component> lore = new ArrayList<>();
        Object tax = d.get("tax");
        if (tax instanceof Number n) lore.add(Component.text("税额: " + plugin.vaultHook().format(n.doubleValue()), NamedTextColor.GRAY));
        Object at = d.get("declared_at");
        if (at instanceof Number n && n.longValue() > 0)
            lore.add(Component.text("时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(n.longValue() * 1000)), NamedTextColor.GRAY));
        Object status = d.get("status");
        if (status != null) lore.add(Component.text("状态: " + statusLabel(status), NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildEntListItem(Map<String, Object> e) {
        ItemStack stack = new ItemStack(Material.IRON_BLOCK);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String name = String.valueOf(e.get("name"));
        String type = String.valueOf(e.get("type"));
        double capital = ((Number) e.get("registered_capital")).doubleValue();
        double balance = ((Number) e.get("corporate_balance")).doubleValue();
        int employees = ((Number) e.get("employee_count")).intValue();
        String entId = String.valueOf(e.get("id"));

        meta.displayName(Component.text("§7🏢 " + name));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("类型: " + enterpriseTypeLabel(type), NamedTextColor.GRAY));
        lore.add(Component.text("注册资本: " + plugin.vaultHook().format(capital), NamedTextColor.GRAY));
        lore.add(Component.text("公户余额: " + plugin.vaultHook().format(balance), NamedTextColor.GRAY));
        lore.add(Component.text("企业等级: Lv." + e.getOrDefault("level", 1), NamedTextColor.GREEN));
        lore.add(Component.text("员工: " + employees, NamedTextColor.GRAY));
        Object region = e.get("region");
        if (region != null && !String.valueOf(region).isEmpty())
            lore.add(Component.text("地区: " + region, NamedTextColor.GRAY));
        Object industry = e.get("industry");
        if (industry != null && !String.valueOf(industry).isEmpty())
            lore.add(Component.text("行业: " + industry, NamedTextColor.GRAY));
        lore.add(Component.text("ID: " + entId, NamedTextColor.DARK_GRAY));
        if (view == 0) {
            lore.add(Component.empty());
            lore.add(Component.text("§a§l点击管理", NamedTextColor.YELLOW));
        } else {
            lore.add(Component.empty());
            lore.add(Component.text("§a§l左键申请加入", NamedTextColor.YELLOW));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildEntInfoItem(Map<String, Object> e) {
        ItemStack stack = new ItemStack(Material.IRON_BLOCK);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        meta.displayName(Component.text("§7§l🏢 " + e.get("name")));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("类型: " + enterpriseTypeLabel(e.get("type"))
                + " | 状态: " + statusLabel(e.get("status")), NamedTextColor.GRAY));
        lore.add(Component.text("注册资本: " + plugin.vaultHook().format(((Number) e.get("registered_capital")).doubleValue()), NamedTextColor.GRAY));
        lore.add(Component.text("公户余额: " + plugin.vaultHook().format(((Number) e.get("corporate_balance")).doubleValue()), NamedTextColor.GRAY));
        lore.add(Component.text("企业等级: Lv." + e.getOrDefault("level", 1), NamedTextColor.GREEN));
        double dividendRate = ((Number) e.getOrDefault("dividend_rate", 0.0)).doubleValue();
        lore.add(Component.text("分红比例: " + String.format(Locale.ROOT, "%.2f", dividendRate) + "%", NamedTextColor.GRAY));
        lore.add(Component.text("员工: " + e.get("employee_count"), NamedTextColor.GRAY));
        lore.add(Component.text("——— 成员列表 ———", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildMemberItem(Map<String, Object> m) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String role = String.valueOf(m.getOrDefault("role", "EMPLOYEE"));
        String playerName = String.valueOf(m.getOrDefault("player_name", m.get("player_uuid")));

        String roleDisplay = switch (role) {
            case "OWNER", "CEO" -> "§6" + roleLabel(role);
            case "CO_OWNER" -> "§e" + roleLabel(role);
            case "MANAGER" -> "§b" + roleLabel(role);
            default -> "§7" + roleLabel(role);
        };
        meta.displayName(Component.text(roleDisplay + " " + playerName));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("角色: " + roleLabel(role), NamedTextColor.GRAY));
        Object salary = m.get("salary");
        lore.add(Component.text("工资/期: " + plugin.vaultHook().format(salary instanceof Number n ? n.doubleValue() : 0), NamedTextColor.GRAY));
        lore.add(Component.text("UUID: " + m.get("player_uuid"), NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("§a§l左键在普通员工与经理间切换", NamedTextColor.YELLOW));
        lore.add(Component.text("§e§lShift+左键 权限/工资设置 §7| §b§l右键 发放本期工资", NamedTextColor.YELLOW));
        lore.add(Component.text("§c§lShift+右键 移除成员", NamedTextColor.RED));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack emptyHint() {
        String msg = switch (view) {
            case 0 -> "§7你还没有企业 §7（创建或加入一个）";
            case 1 -> "§7暂无企业";
            default -> "§7无数据";
        };
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(Component.text(msg)); item.setItemMeta(meta); }
        return item;
    }

    // ---- Write operations ----

    private void doCreateEnterprise(Player player, String name, double capital, String region) {
        Object result = plugin.callExtraManager("ks-eco-enterprise", "enterpriseManager", "register",
                new Class<?>[]{String.class, String.class, java.util.List.class, double.class, String.class},
                name, "PRIVATE", List.of(player.getUniqueId()), capital, region);
        if (result != null) {
            player.sendMessage("§a企业 「" + name + "」 创建成功！");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        } else {
            player.sendMessage("§c创建企业失败。请检查余额是否足够或企业模块是否加载。");
        }
    }

    private void doJoinEnterprise(Player player, String entId) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            try (var check = conn.prepareStatement("SELECT status FROM ks_ent_enterprises WHERE id=?")) {
                check.setString(1, entId);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next() || !"ACTIVE".equalsIgnoreCase(rs.getString("status"))) {
                        player.sendMessage("§c该企业不存在或已停止运营。");
                        return;
                    }
                }
            }
            try (var ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO ks_ent_members (enterprise_id, player_uuid, player_name, role) VALUES (?,?,?,'EMPLOYEE')")) {
                ps.setString(1, entId);
                ps.setString(2, player.getUniqueId().toString());
                ps.setString(3, player.getName());
                if (ps.executeUpdate() > 0) player.sendMessage("§a已加入企业！");
                else player.sendMessage("§e你已经是该企业成员。");
            }
        } catch (Exception e) {
            player.sendMessage("§c加入失败: " + e.getMessage());
        }
    }

    /** MANAGE_MEMBERS / MANAGE_PERMISSIONS 等企业权限校验（admin 豁免），写操作前必查。 */
    private boolean hasEntPermission(Player player, String entId, String permission) {
        if (player.hasPermission("kseco.admin")) return true;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            return conn != null && permService.hasPermission(conn, entId, player.getUniqueId(), permission);
        } catch (Exception e) { return false; }
    }

    private void doRemoveMember(Player player, String entId, String targetUuid) {
        if (!hasEntPermission(player, entId, org.kseco.EnterprisePermissionService.MANAGE_MEMBERS)) {
            player.sendMessage("§c你没有该企业的成员管理权限。"); return;
        }
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             Statement st = conn.createStatement()) {
            // 禁止移除所有者
            try (Statement st0 = conn.createStatement();
                 ResultSet rs = st0.executeQuery("SELECT owner_uuids FROM ks_ent_enterprises WHERE id='" + entId.replace("'", "''") + "'")) {
                if (rs.next() && String.valueOf(rs.getString("owner_uuids")).contains(targetUuid)) {
                    player.sendMessage("§c不能移除企业所有者。"); return;
                }
            }
            st.executeUpdate("DELETE FROM ks_ent_members WHERE enterprise_id='"
                    + entId.replace("'", "''") + "' AND player_uuid='" + targetUuid.replace("'", "''") + "'");
            player.sendMessage("§a成员已移除。");
            loadData(player);
            build();
            player.openInventory(getInventory());
        } catch (Exception e) {
            player.sendMessage("§c移除失败: " + e.getMessage());
        }
    }

    private void doCycleMemberRole(Player player, String entId, String memberUuid, String currentRole) {
        String next = "MANAGER".equalsIgnoreCase(currentRole) ? "EMPLOYEE" : "MANAGER";
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            // canAssignRole：owner 全可；CEO 可派非 CEO；MANAGER 只能派 EMPLOYEE（镜像 web 规则）
            if (!player.hasPermission("kseco.admin")
                    && !permService.canAssignRole(conn, entId, player.getUniqueId(), next)) {
                player.sendMessage("§c你没有将该成员调整为“" + roleLabel(next) + "”的权限。"); return;
            }
            // 禁止修改所有者
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT owner_uuids FROM ks_ent_enterprises WHERE id='" + entId.replace("'", "''") + "'")) {
                if (rs.next() && String.valueOf(rs.getString("owner_uuids")).contains(memberUuid)) {
                    player.sendMessage("§c不能修改企业所有者的角色。"); return;
                }
            }
            try (var ps = conn.prepareStatement("UPDATE ks_ent_members SET role=? WHERE enterprise_id=? AND player_uuid=?")) {
                ps.setString(1, next); ps.setString(2, entId); ps.setString(3, memberUuid);
            if (ps.executeUpdate() > 0) player.sendMessage("§a角色已调整为“" + roleLabel(next) + "”。");
                else player.sendMessage("§c目标不是该企业成员。");
            }
        } catch (Exception e) {
            player.sendMessage("§c角色调整失败: " + e.getMessage());
        }
    }

    private void doSetSalary(Player player, String entId, String memberUuid, double salary) {
        if (!hasEntPermission(player, entId, org.kseco.EnterprisePermissionService.MANAGE_MEMBERS)) {
            player.sendMessage("§c你没有该企业的成员管理权限。"); return;
        }
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             var ps = conn.prepareStatement("UPDATE ks_ent_members SET salary=? WHERE enterprise_id=? AND player_uuid=?")) {
            ps.setDouble(1, salary); ps.setString(2, entId); ps.setString(3, memberUuid);
            if (ps.executeUpdate() > 0) player.sendMessage("§a工资已设置为 " + plugin.vaultHook().format(salary) + " / 期。");
            else player.sendMessage("§c目标不是该企业成员。");
        } catch (Exception e) {
            player.sendMessage("§c工资设置失败: " + e.getMessage());
        }
    }

    private void doPaySalary(Player player, String entId, String memberUuid) {
        UUID employeeUuid;
        try {
            employeeUuid = UUID.fromString(memberUuid);
        } catch (IllegalArgumentException e) {
            player.sendMessage("§c该成员的 UUID 数据无效，无法发放工资。");
            return;
        }
        Object result = plugin.callExtraManager("ks-eco-enterprise", "enterpriseManager", "paySalary",
                new Class<?>[]{String.class, UUID.class, UUID.class},
                entId, player.getUniqueId(), employeeUuid);
        if (result instanceof Map<?, ?> m) {
            boolean ok = Boolean.TRUE.equals(m.get("success"));
            Object message = m.get("message");
            player.sendMessage((ok ? "§a" : "§c") + (message != null ? message : (ok ? "工资已发放。" : "工资发放失败。")));
        } else if (result instanceof Boolean ok && ok) {
            player.sendMessage("§a工资已发放。");
        } else {
            player.sendMessage("§c工资发放失败（权限不足、公户余额不足或未设置工资）。");
        }
    }

    private void doToggleEntPermission(Player player, String entId, String memberUuid, String permission, boolean grant) {
        // 镜像 web 规则：owner/admin，或（自身有 MANAGE_PERMISSIONS 且自身也持有目标权限）
        boolean allowed = player.hasPermission("kseco.admin");
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (!allowed) {
                allowed = permService.isOwner(conn, entId, player.getUniqueId())
                        || (permService.hasPermission(conn, entId, player.getUniqueId(), org.kseco.EnterprisePermissionService.MANAGE_PERMISSIONS)
                        && permService.hasPermission(conn, entId, player.getUniqueId(), permission));
            }
            if (!allowed) { player.sendMessage("§c你没有授予/撤销该权限的资格。"); return; }
            try (var check = conn.prepareStatement("SELECT 1 FROM ks_ent_members WHERE enterprise_id=? AND player_uuid=?")) {
                check.setString(1, entId); check.setString(2, memberUuid);
                if (!check.executeQuery().next()) { player.sendMessage("§c目标不是该企业成员。"); return; }
            }
            if (grant) {
                try (var ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO ks_ent_permissions (enterprise_id,player_uuid,permission,granted_by,granted_at) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, entId); ps.setString(2, memberUuid); ps.setString(3, permission);
                    ps.setString(4, player.getUniqueId().toString()); ps.setLong(5, System.currentTimeMillis() / 1000);
                    ps.executeUpdate();
                }
                player.sendMessage("§a已授予 " + ENT_PERM_CN.getOrDefault(permission, permission) + "。");
            } else {
                try (var ps = conn.prepareStatement("DELETE FROM ks_ent_permissions WHERE enterprise_id=? AND player_uuid=? AND permission=?")) {
                    ps.setString(1, entId); ps.setString(2, memberUuid); ps.setString(3, permission);
                    ps.executeUpdate();
                }
                player.sendMessage("§e已撤销 " + ENT_PERM_CN.getOrDefault(permission, permission) + "。");
            }
        } catch (Exception e) {
            player.sendMessage("§c权限操作失败: " + e.getMessage());
        }
    }

    private void doEfRepay(Player player, String loanId, String entId, double amount) {
        Object result = plugin.callExtraManager("ks-eco-bank", "enterpriseFinanceManager", "repay",
                new Class<?>[]{String.class, String.class, UUID.class, double.class},
                loanId, entId, player.getUniqueId(), amount);
        if (result instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("success"))) {
            player.sendMessage("§a已还款 " + plugin.vaultHook().format(amount) + "（从企业公户扣除）。");
        } else {
            Object err = result instanceof Map<?, ?> m2 ? (m2.get("error") != null ? m2.get("error") : m2.get("message")) : null;
            player.sendMessage("§c还款失败" + (err != null ? ": " + err : "（权限不足或公户余额不足）"));
        }
    }

    private void doDissolveEnterprise(Player player, String entId) {
        Object result = plugin.callExtraManager("ks-eco-enterprise", "enterpriseManager", "dissolve",
                new Class<?>[]{String.class, UUID.class}, entId, player.getUniqueId());
        if (result instanceof Boolean ok && ok) {
            player.sendMessage("§a企业已解散。");
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 0.5f, 1.0f);
            selectedEntId = null;
            view = 0;
            loadData(player);
            build();
        } else {
            player.sendMessage("§c解散失败，你可能不是企业所有者。");
        }
    }

    @SuppressWarnings("unchecked")
    private void doInjectCapital(Player player, String entId, double amount) {
        Object raw = plugin.callExtraManager("ks-eco-enterprise", "enterpriseManager", "injectCapital",
                new Class<?>[]{String.class, UUID.class, double.class}, entId, player.getUniqueId(), amount);
        Map<String, Object> result = raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        if (Boolean.TRUE.equals(result.get("success"))) {
            player.sendMessage("§a" + result.getOrDefault("message", "企业注资成功。"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
        } else {
            player.sendMessage("§c" + result.getOrDefault("message", "企业注资失败。"));
        }
    }

    @SuppressWarnings("unchecked")
    private void doSetDividendRate(Player player, String entId, double rate) {
        Object raw = plugin.callExtraManager("ks-eco-enterprise", "enterpriseManager", "setDividendRate",
                new Class<?>[]{String.class, UUID.class, double.class}, entId, player.getUniqueId(), rate);
        Map<String, Object> result = raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        player.sendMessage((Boolean.TRUE.equals(result.get("success")) ? "§a" : "§c")
                + result.getOrDefault("message", "设置分红比例失败。"));
    }

    @SuppressWarnings("unchecked")
    private boolean doSetDividendShares(Player player, String entId, String input) {
        Map<String, Object> shares = new LinkedHashMap<>();
        try {
            for (String part : input.split(",")) {
                String[] pair = part.trim().split(":", 2);
                if (pair.length != 2) throw new IllegalArgumentException();
                shares.put(UUID.fromString(pair[0].trim()).toString(), Double.parseDouble(pair[1].trim()));
            }
        } catch (Exception e) {
            player.sendMessage("§c格式无效。示例：UUID:60,UUID:40");
            return false;
        }
        Object raw = plugin.callExtraManager("ks-eco-enterprise", "enterpriseManager", "setDividendShares",
                new Class<?>[]{String.class, UUID.class, Map.class}, entId, player.getUniqueId(), shares);
        Map<String, Object> result = raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        player.sendMessage((Boolean.TRUE.equals(result.get("success")) ? "§a" : "§c")
                + result.getOrDefault("message", "设置所有者分红占比失败。"));
        return true;
    }

    @SuppressWarnings("unchecked")
    private void doDistributeDividend(Player player, String entId) {
        Object raw = plugin.callExtraManager("ks-eco-enterprise", "enterpriseManager", "distributeDividend",
                new Class<?>[]{String.class, UUID.class}, entId, player.getUniqueId());
        Map<String, Object> result = raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        if (Boolean.TRUE.equals(result.get("success"))) {
            player.sendMessage("§a" + result.getOrDefault("message", "分红已发放。"));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
        } else {
            player.sendMessage("§c" + result.getOrDefault("message", "分红发放失败。"));
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
            if (!(event.getView().getTopInventory().getHolder() instanceof EnterpriseGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= gui.getInventory().getSize()) return;

            // View tabs (0-3)
            if (slot >= 0 && slot <= 3) {
                if (slot != gui.view) {
                    gui.view = slot; gui.page = 0; gui.selectedEntId = null; gui.loadData(player); gui.build();
                    player.openInventory(gui.getInventory());
                }
                return;
            }

            // Create view
            if (gui.view == 2 && slot == 13) {
                player.closeInventory();
                pendingEnt.put(player.getUniqueId(), new PendingCreateEnt(null, 0, 1));
                player.sendMessage("§a请输入企业名称，或输入 cancel 取消：");
                return;
            }

            // Manage view special buttons
            if (gui.view == 3) {
                if (slot == 42) {
                    gui.view = 6; gui.page = 0;
                    gui.loadData(player); gui.build();
                    player.openInventory(gui.getInventory());
                    return;
                }
                if (slot == 43) {
                    gui.view = 5; gui.page = 0;
                    gui.loadData(player); gui.build();
                    player.openInventory(gui.getInventory());
                    return;
                }
                if (slot == 44) {
                    player.closeInventory();
                    player.sendMessage("§a请输入所有者分红占比，格式：UUID:60,UUID:40；合计必须为100。输入 cancel 取消：");
                    pendingEnt.put(player.getUniqueId(), new PendingDividendShares(gui.selectedEntId));
                    return;
                }
                if (slot == 45) {
                    player.closeInventory();
                    player.sendMessage("§a请输入注资金额，或输入 cancel 取消：");
                    pendingEnt.put(player.getUniqueId(), new PendingEntInjection(gui.selectedEntId));
                    return;
                }
                if (slot == 46) {
                    if (event.isRightClick()) {
                        if (gui.selectedEntId != null) gui.doDistributeDividend(player, gui.selectedEntId);
                    } else {
                        player.closeInventory();
                        player.sendMessage("§a请输入分红比例（0-100），或输入 cancel 取消：");
                        pendingEnt.put(player.getUniqueId(), new PendingDividendRate(gui.selectedEntId));
                    }
                    return;
                }
                if (slot == 47) {
                    // Invite member
                    if (gui.selectedEntId == null || !gui.hasEntPermission(player, gui.selectedEntId,
                            org.kseco.EnterprisePermissionService.MANAGE_MEMBERS)) {
                        player.sendMessage("§c你没有该企业的成员管理权限。");
                        return;
                    }
                    player.closeInventory();
                    player.sendMessage("§a请输入要邀请的玩家名称，或输入 cancel 取消：");
                    pendingEnt.put(player.getUniqueId(), new PendingEntInvite(gui.selectedEntId));
                    return;
                }
                if (slot == 48) {
                    // Dissolve
                    if (gui.selectedEntId != null) gui.doDissolveEnterprise(player, gui.selectedEntId);
                    return;
                }
                if (slot == 49) {
                    player.closeInventory();
                    new EcoGuiMainMenu(plugin).open(player);
                    return;
                }
                if (slot == 50 && gui.page > 0) {
                    gui.page--;
                    gui.build();
                    player.openInventory(gui.getInventory());
                    return;
                }
                long memberCount = gui.items.stream().filter(item -> "member".equals(item.get("type"))).count();
                if (slot == 52 && (gui.page + 1L) * MANAGE_MEMBER_PAGE_SIZE < memberCount) {
                    gui.page++;
                    gui.build();
                    player.openInventory(gui.getInventory());
                    return;
                }
                if (slot == 53) {
                    // Back to list
                    gui.view = 0; gui.selectedEntId = null; gui.loadData(player); gui.build();
                    player.openInventory(gui.getInventory());
                    return;
                }
                // Member actions: info 固定在 slot 9，成员按独立页渲染于 slot 10-41。
                if (slot >= 10 && slot < 10 + MANAGE_MEMBER_PAGE_SIZE) {
                    int index = gui.page * MANAGE_MEMBER_PAGE_SIZE + (slot - 10);
                    List<Map<String, Object>> members = gui.items.stream()
                            .filter(item -> "member".equals(item.get("type"))).toList();
                    if (index >= 0 && index < members.size()) {
                        Map<String, Object> item = members.get(index);
                        if ("member".equals(item.get("type"))) {
                            String memberUuid = String.valueOf(item.get("player_uuid"));
                            String memberName = String.valueOf(item.getOrDefault("player_name", memberUuid));
                            if (event.isShiftClick() && event.isRightClick()) {
                                gui.doRemoveMember(player, gui.selectedEntId, memberUuid);
                            } else if (event.isShiftClick()) {
                                gui.view = 4; gui.page = 0;
                                gui.selectedMemberUuid = memberUuid; gui.selectedMemberName = memberName;
                                gui.loadData(player); gui.build();
                                player.openInventory(gui.getInventory());
                            } else if (event.isRightClick()) {
                                gui.doPaySalary(player, gui.selectedEntId, memberUuid);
                            } else {
                                gui.doCycleMemberRole(player, gui.selectedEntId, memberUuid, String.valueOf(item.get("role")));
                                gui.loadData(player); gui.build();
                                player.openInventory(gui.getInventory());
                            }
                        }
                    }
                }
                return;
            }

            // Member-detail / financing / dividend views
            if (gui.view >= 4 && gui.view <= 6) {
                if (slot == 47 && gui.view == 4 && gui.selectedEntId != null && gui.selectedMemberUuid != null) {
                    player.closeInventory();
                    pendingEnt.put(player.getUniqueId(), new PendingSetSalary(gui.selectedEntId, gui.selectedMemberUuid, gui.selectedMemberName));
                    player.sendMessage("§a请输入 " + gui.selectedMemberName + " 的每期工资金额，或输入 cancel 取消：");
                    return;
                }
                if (slot == 49) {
                    gui.view = 3; gui.page = 0;
                    gui.selectedMemberUuid = null; gui.selectedMemberName = null;
                    gui.loadData(player); gui.build();
                    player.openInventory(gui.getInventory());
                    return;
                }
                if (slot == 45 && gui.page > 0) {
                    gui.page--;
                    gui.build();
                    player.openInventory(gui.getInventory());
                    return;
                }
                if (slot == 53 && (gui.page + 1) * PAGE_SIZE < gui.items.size()) {
                    gui.page++;
                    gui.build();
                    player.openInventory(gui.getInventory());
                    return;
                }
                if (slot >= 9 && slot < 9 + PAGE_SIZE) {
                    int index = gui.page * PAGE_SIZE + (slot - 9);
                    if (index < gui.items.size()) {
                        Map<String, Object> item = gui.items.get(index);
                        switch (gui.view) {
                            case 4 -> {
                                String perm = String.valueOf(item.get("permission"));
                                boolean granted = Boolean.TRUE.equals(item.get("granted"));
                                gui.doToggleEntPermission(player, gui.selectedEntId, gui.selectedMemberUuid, perm, !granted);
                                gui.loadData(player); gui.build();
                                player.openInventory(gui.getInventory());
                            }
                            case 5 -> {
                                if ("ACTIVE".equalsIgnoreCase(String.valueOf(item.get("status")))) {
                                    Object out = item.getOrDefault("outstanding", item.get("remaining"));
                                    double outstanding = out instanceof Number n ? n.doubleValue() : 0;
                                    player.closeInventory();
                                    pendingEnt.put(player.getUniqueId(),
                                            new PendingEfRepay(String.valueOf(item.get("id")), gui.selectedEntId, outstanding));
                                    player.sendMessage("§a请输入还款金额（未偿 " + plugin.vaultHook().format(outstanding) + "），或 cancel 取消");
                                }
                            }
                            case 6 -> { /* 分红记录只读 */ }
                        }
                    }
                }
                return;
            }

            // Content slots for list views
            if (slot >= 9 && slot < 9 + PAGE_SIZE) {
                int index = gui.page * PAGE_SIZE + (slot - 9);
                if (index < gui.items.size()) {
                    Map<String, Object> item = gui.items.get(index);
                    if (gui.view == 0) {
                        // Open manage
                        gui.selectedEntId = String.valueOf(item.get("id"));
                        gui.view = 3;
                        gui.loadData(player);
                        gui.build();
                        player.openInventory(gui.getInventory());
                    } else if (gui.view == 1) {
                        gui.doJoinEnterprise(player, String.valueOf(item.get("id")));
                    }
                }
                return;
            }

            switch (slot) {
                case 45 -> { if (gui.page > 0) { gui.page--; gui.build(); player.openInventory(gui.getInventory()); } }
                case 49 -> { player.closeInventory(); new EcoGuiMainMenu(plugin).open(player); }
                case 53 -> {
                    if ((gui.view == 0 || gui.view == 1)
                            && (gui.page + 1) * PAGE_SIZE < gui.items.size()) {
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
            pendingEnt.put(player.getUniqueId(), pending);
            player.sendMessage(message);
        }

        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            pendingEnt.remove(event.getPlayer().getUniqueId());
        }

        private void reopen(Player player, String entId, int view, String memberUuid, String memberName) {
            EnterpriseGui gui = new EnterpriseGui(plugin);
            gui.view = view;
            gui.selectedEntId = entId;
            gui.selectedMemberUuid = memberUuid;
            gui.selectedMemberName = memberName;
            gui.loadData(player);
            gui.build();
            player.openInventory(gui.getInventory());
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();
            Object pendingObj = pendingEnt.remove(player.getUniqueId());
            if (pendingObj == null) return;

            event.setCancelled(true);
            String msg = event.getMessage().trim();
            if (msg.equalsIgnoreCase("cancel")) {
                player.sendMessage("§c已取消。");
                Bukkit.getScheduler().runTask(plugin, () -> new EnterpriseGui(plugin).open(player));
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                EnterpriseGui gui = new EnterpriseGui(plugin);

                if (pendingObj instanceof PendingEntInvite inv) {
                    if (!gui.hasEntPermission(player, inv.entId,
                            org.kseco.EnterprisePermissionService.MANAGE_MEMBERS)) {
                        player.sendMessage("§c你没有该企业的成员管理权限。");
                        return;
                    }
                    OfflinePlayer target = Bukkit.getOfflinePlayer(msg);
                    if (!target.hasPlayedBefore() && !target.isOnline()) {
                        retry(player, pendingObj, "§c未找到玩家: " + msg + "，请重新输入或输入 cancel 取消。");
                        return;
                    }
                    try (var conn = plugin.ksCore().dataStore().getConnection();
                         var st = conn.createStatement()) {
                        String inviteId = UUID.randomUUID().toString().substring(0, 8);
                        st.executeUpdate("INSERT INTO ks_ent_invites (id, enterprise_id, inviter_uuid, invitee_uuid, created_at) VALUES ('"
                                + inviteId + "','" + inv.entId.replace("'", "''") + "','"
                                + player.getUniqueId().toString().replace("'", "''") + "','"
                                + target.getUniqueId().toString().replace("'", "''") + "',"
                                + (System.currentTimeMillis() / 1000) + ")");
                        player.sendMessage("§a已向 " + target.getName() + " 发送合资邀请！");
                    } catch (Exception e) {
                        player.sendMessage("§c邀请失败: " + e.getMessage());
                    }
                    reopen(player, inv.entId, 3, null, null);
                    return;
                }

                if (pendingObj instanceof PendingEntInjection injection) {
                    try {
                        double amount = Double.parseDouble(msg);
                        if (!Double.isFinite(amount) || amount <= 0) throw new NumberFormatException();
                        gui.doInjectCapital(player, injection.entId, amount);
                    } catch (NumberFormatException e) {
                        retry(player, pendingObj, "§c请输入有效的正数金额，或输入 cancel 取消。");
                        return;
                    }
                    reopen(player, injection.entId, 3, null, null);
                    return;
                }

                if (pendingObj instanceof PendingDividendRate dividendRate) {
                    try {
                        double rate = Double.parseDouble(msg);
                        if (!Double.isFinite(rate) || rate < 0 || rate > 100) throw new NumberFormatException();
                        gui.doSetDividendRate(player, dividendRate.entId, rate);
                    } catch (NumberFormatException e) {
                        retry(player, pendingObj, "§c请输入 0 到 100 之间的数字，或输入 cancel 取消。");
                        return;
                    }
                    reopen(player, dividendRate.entId, 3, null, null);
                    return;
                }

                if (pendingObj instanceof PendingDividendShares dividendShares) {
                    if (!gui.doSetDividendShares(player, dividendShares.entId, msg)) {
                        pendingEnt.put(player.getUniqueId(), pendingObj);
                        player.sendMessage("§e请重新输入，或输入 cancel 取消。");
                        return;
                    }
                    reopen(player, dividendShares.entId, 3, null, null);
                    return;
                }

                if (pendingObj instanceof PendingSetSalary ss) {
                    try {
                        double salary = Double.parseDouble(msg);
                        if (!Double.isFinite(salary) || salary < 0) throw new NumberFormatException();
                        gui.doSetSalary(player, ss.entId, ss.memberUuid, salary);
                    } catch (NumberFormatException e) {
                        retry(player, pendingObj, "§c请输入有效的非负金额，或输入 cancel 取消。");
                        return;
                    }
                    reopen(player, ss.entId, 4, ss.memberUuid, ss.memberName);
                    return;
                }

                if (pendingObj instanceof PendingEfRepay er) {
                    try {
                        double amount = Double.parseDouble(msg);
                        if (!Double.isFinite(amount) || amount <= 0) throw new NumberFormatException();
                        gui.doEfRepay(player, er.loanId, er.entId, Math.min(amount, er.outstanding > 0 ? er.outstanding : amount));
                    } catch (NumberFormatException e) {
                        retry(player, pendingObj, "§c无效金额: " + msg + "，请重新输入或输入 cancel 取消。");
                        return;
                    }
                    reopen(player, er.entId, 5, null, null);
                    return;
                }

                if (!(pendingObj instanceof PendingCreateEnt pending)) return;

                switch (pending.step) {
                    case 1 -> {
                        if (msg.length() < 2 || msg.length() > 32) {
                            player.sendMessage("§c企业名称长度需在2-32字符之间。");
                            pendingEnt.put(player.getUniqueId(), new PendingCreateEnt(null, 0, 1));
                            return;
                        }
                        pendingEnt.put(player.getUniqueId(), new PendingCreateEnt(msg, 0, 2));
                        player.sendMessage("§a请输入注册资本（从你余额扣除）：");
                    }
                    case 2 -> {
                        try {
                            double capital = Double.parseDouble(msg);
                            if (!Double.isFinite(capital) || capital > 1_000_000_000_000d) throw new NumberFormatException();
                            if (capital < 100) {
                                retry(player, pendingObj, "§c注册资本最少100，请重新输入或输入 cancel 取消。");
                                return;
                            }
                            pendingEnt.put(player.getUniqueId(), new PendingCreateEnt(pending.name, capital, 3));
                            player.sendMessage("§a请输入所在地区（可选，输入 - 跳过）：");
                        } catch (NumberFormatException e) {
                            player.sendMessage("§c无效金额: " + msg);
                            pendingEnt.put(player.getUniqueId(), new PendingCreateEnt(pending.name, 0, 2));
                        }
                    }
                    case 3 -> {
                        String region = msg.isEmpty() || msg.equals("-") ? "" : msg;
                        gui.doCreateEnterprise(player, pending.name, pending.capital, region);
                        gui.open(player);
                    }
                }
            });
        }
    }
}
