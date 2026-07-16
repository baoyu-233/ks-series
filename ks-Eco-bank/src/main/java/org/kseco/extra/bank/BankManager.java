package org.kseco.extra.bank;

import org.kseco.KsEco;

import java.sql.*;
import java.util.*;

/**
 * 银行管理器。
 * 玩家银行的创建、管理、存款/取款/贷款核心逻辑。
 *
 * 资质要求（可在配置中调整）：
 * - 独资银行：玩家资产 ≥ 50,000
 * - 合资银行：发起人 ≥ 2 人，总资产 ≥ 100,000
 */
public final class BankManager {

    public static final String GUIDANCE_BANK_ID = "GUIDE-BANK";
    private static final String GUIDANCE_OWNER = "SYSTEM";
    private static final String GUIDANCE_BANK_NAME = "\u5F00\u53D1\u94F6\u884C";

    private final KsEco eco;

    public BankManager(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        createTables();
    }

    private void createTables() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                // 银行表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_banks (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'COMMERCIAL',
                        owner_uuids TEXT NOT NULL,
                        total_assets REAL DEFAULT 0.0,
                        reserve_ratio REAL DEFAULT 0.1,
                        interest_rate REAL DEFAULT 0.03,
                        loan_rate REAL DEFAULT 0.08,
                        status TEXT DEFAULT 'ACTIVE',
                        created_at INTEGER NOT NULL
                    )
                """);
                // 账户表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_accounts (
                        id TEXT PRIMARY KEY,
                        bank_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        balance REAL DEFAULT 0.0,
                        interest_earned REAL DEFAULT 0.0,
                        opened_at INTEGER NOT NULL,
                        FOREIGN KEY (bank_id) REFERENCES ks_bank_banks(id)
                    )
                """);
                // 贷款表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_loans (
                        id TEXT PRIMARY KEY,
                        bank_id TEXT NOT NULL,
                        borrower_uuid TEXT NOT NULL,
                        principal REAL NOT NULL,
                        remaining REAL NOT NULL,
                        interest_rate REAL NOT NULL,
                        term_days INTEGER NOT NULL,
                        issued_at INTEGER NOT NULL,
                        due_at INTEGER NOT NULL,
                        status TEXT DEFAULT 'ACTIVE',
                        FOREIGN KEY (bank_id) REFERENCES ks_bank_banks(id)
                    )
                """);
                // 央行利率历史
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_cb_rates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        base_rate REAL NOT NULL,
                        reserve_requirement REAL NOT NULL,
                        set_at INTEGER NOT NULL
                    )
                """);
                // 央行配置键值表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_cb_config (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                """);
                // 银行自定义利率表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_rates (
                        bank_id TEXT PRIMARY KEY,
                        loan_rate REAL DEFAULT 0.05,
                        deposit_rate REAL DEFAULT 0.01,
                        updated_at INTEGER DEFAULT (strftime('%s','now'))
                    )
                """);
                // 银行成员表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_members (
                        bank_id TEXT,
                        player_uuid TEXT,
                        player_name TEXT,
                        role TEXT DEFAULT 'MEMBER',
                        joined_at INTEGER DEFAULT (strftime('%s','now')),
                        PRIMARY KEY(bank_id, player_uuid)
                    )
                """);
                // 银行权限表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_permissions (
                        bank_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        permission TEXT NOT NULL,
                        granted_by TEXT,
                        granted_at INTEGER NOT NULL,
                        PRIMARY KEY(bank_id, player_uuid, permission)
                    )
                """);
                // 货币供应量表
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_money_supply (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        m0 REAL NOT NULL,
                        m1 REAL NOT NULL,
                        m2 REAL NOT NULL,
                        snapshot_at INTEGER NOT NULL
                    )
                """);
                // 贷款申请表（玩家自助申请，银行所有者/有权限成员审批）
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_loan_requests (
                        id TEXT PRIMARY KEY,
                        bank_id TEXT NOT NULL,
                        borrower_uuid TEXT NOT NULL,
                        borrower_name TEXT,
                        principal REAL NOT NULL,
                        term_days INTEGER NOT NULL,
                        status TEXT DEFAULT 'PENDING',
                        requested_at INTEGER NOT NULL,
                        decided_at INTEGER,
                        loan_id TEXT
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_guidance_config (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_bank_guidance_claims (
                        player_uuid TEXT PRIMARY KEY,
                        loan_id TEXT NOT NULL,
                        claimed_at INTEGER NOT NULL
                    )
                """);
                // 利息结算锚点列（旧库兼容模式补列）
                try { stmt.execute("ALTER TABLE ks_bank_accounts ADD COLUMN last_interest_at INTEGER"); } catch (SQLException ignored) {}
                // 账户 ID 迁移：旧格式 bankId:UUID前8位 有碰撞风险（两个玩家前8位相同会共用账户，
                // 后来者能取走先来者的钱）。统一迁移为 bankId:完整UUID，幂等，冲突行跳过。
                try { stmt.execute("UPDATE ks_bank_accounts SET id = bank_id || ':' || player_uuid WHERE id != bank_id || ':' || player_uuid"); } catch (SQLException ignored) {}
            }
            ensureGuidanceBank(conn);
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 创建表失败: " + e.getMessage());
        }
    }

    // ---- 银行 CRUD ----

    /** 管理员/系统路径：不扣款、不做资质检查。 */
    private void ensureGuidanceBank(Connection conn) throws SQLException {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("enabled", "1");
        defaults.put("seed_capital", "500000");
        defaults.put("starter_loan_amount", "2500");
        defaults.put("starter_loan_rate", "0.02");
        defaults.put("starter_loan_term_days", "7");
        defaults.put("starter_loan_total_cap", "100000");
        defaults.put("starter_loan_daily_cap", "20000");
        defaults.put("starter_loan_min_reserve_ratio", "0.20");
        try (PreparedStatement config = conn.prepareStatement(
                "INSERT OR IGNORE INTO ks_bank_guidance_config (key, value) VALUES (?,?)")) {
            for (Map.Entry<String, String> entry : defaults.entrySet()) {
                config.setString(1, entry.getKey());
                config.setString(2, entry.getValue());
                config.addBatch();
            }
            config.executeBatch();
        }

        double seedCapital = guidanceConfig(conn).getOrDefault("seed_capital", 500000.0);
        long now = System.currentTimeMillis() / 1000;
        try (PreparedStatement bank = conn.prepareStatement(
                "INSERT OR IGNORE INTO ks_bank_banks (id,name,type,owner_uuids,total_assets,status,created_at) VALUES (?,?,?,?,?,'ACTIVE',?)")) {
            bank.setString(1, GUIDANCE_BANK_ID);
            bank.setString(2, GUIDANCE_BANK_NAME);
            bank.setString(3, "GUIDANCE");
            bank.setString(4, GUIDANCE_OWNER);
            bank.setDouble(5, seedCapital);
            bank.setLong(6, now);
            bank.executeUpdate();
        }
        // Existing GUIDE-BANK installations keep their balance sheet; only the public name changes.
        try (PreparedStatement rename = conn.prepareStatement(
                "UPDATE ks_bank_banks SET name=?, type='GUIDANCE' WHERE id=?")) {
            rename.setString(1, GUIDANCE_BANK_NAME);
            rename.setString(2, GUIDANCE_BANK_ID);
            rename.executeUpdate();
        }
        try (PreparedStatement owner = conn.prepareStatement(
                "INSERT OR IGNORE INTO ks_bank_members (bank_id,player_uuid,player_name,role,joined_at) VALUES (?,?,?,?,?)")) {
            owner.setString(1, GUIDANCE_BANK_ID);
            owner.setString(2, GUIDANCE_OWNER);
            owner.setString(3, "SYSTEM");
            owner.setString(4, "OWNER");
            owner.setLong(5, now);
            owner.executeUpdate();
        }
    }

    private Map<String, Double> guidanceConfig(Connection conn) throws SQLException {
        Map<String, Double> result = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT key,value FROM ks_bank_guidance_config");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    result.put(rs.getString("key"), Double.parseDouble(rs.getString("value")));
                } catch (NumberFormatException ignored) {
                    // Invalid administrator data is ignored and handled by the caller's defaults.
                }
            }
        }
        return result;
    }

    public Map<String, Object> guidanceStatus(UUID playerUuid) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bankId", GUIDANCE_BANK_ID);
        result.put("bankName", GUIDANCE_BANK_NAME);
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return Map.of("available", false, "reason", "数据库不可用");
            Map<String, Double> config = guidanceConfig(conn);
            double seed = config.getOrDefault("seed_capital", 500000.0);
            double amount = config.getOrDefault("starter_loan_amount", 2500.0);
            double rate = config.getOrDefault("starter_loan_rate", 0.02);
            int termDays = (int) Math.round(config.getOrDefault("starter_loan_term_days", 7.0));
            double totalCap = config.getOrDefault("starter_loan_total_cap", 100000.0);
            double dailyCap = config.getOrDefault("starter_loan_daily_cap", 20000.0);
            double reserveRatio = config.getOrDefault("starter_loan_min_reserve_ratio", 0.20);
            long today = (System.currentTimeMillis() / 1000 / 86400) * 86400;
            double claimedTotal = sumGuidanceClaims(conn, 0);
            double claimedToday = sumGuidanceClaims(conn, today);
            double assets = 0;
            String bankStatus = "MISSING";
            try (PreparedStatement bank = conn.prepareStatement("SELECT total_assets,status FROM ks_bank_banks WHERE id=?")) {
                bank.setString(1, GUIDANCE_BANK_ID);
                try (ResultSet rs = bank.executeQuery()) {
                    if (rs.next()) {
                        assets = rs.getDouble("total_assets");
                        bankStatus = rs.getString("status");
                    }
                }
            }
            boolean claimed = false;
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ks_bank_guidance_claims WHERE player_uuid=?")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) { claimed = rs.next(); }
            }
            double reserve = Math.max(0, seed * Math.max(0, Math.min(1, reserveRatio)));
            String reason = null;
            if (config.getOrDefault("enabled", 1.0) < 0.5) reason = "引导贷款目前未开放";
            else if (!"ACTIVE".equalsIgnoreCase(bankStatus)) reason = "引导银行当前不可用";
            else if (claimed) reason = "每位玩家只能领取一次引导贷款";
            else if (!Double.isFinite(amount) || amount <= 0 || termDays <= 0 || rate < 0) reason = "引导贷款配置无效";
            else if (claimedTotal + amount > totalCap) reason = "本期引导贷款总额度已用尽";
            else if (claimedToday + amount > dailyCap) reason = "今日引导贷款额度已用尽";
            else if (assets < amount + reserve) reason = "引导银行需保留准备金，暂无法放款";

            result.put("available", true);
            result.put("enabled", config.getOrDefault("enabled", 1.0) >= 0.5);
            result.put("assets", assets);
            result.put("starterAmount", amount);
            result.put("interestRate", rate);
            result.put("termDays", termDays);
            result.put("totalCap", totalCap);
            result.put("dailyCap", dailyCap);
            result.put("claimedTotal", claimedTotal);
            result.put("claimedToday", claimedToday);
            result.put("reserve", reserve);
            result.put("alreadyClaimed", claimed);
            result.put("eligible", reason == null);
            result.put("reason", reason == null ? "可领取" : reason);
            return result;
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 读取引导贷款状态失败: " + e.getMessage());
            return Map.of("available", false, "reason", "读取引导贷款状态失败");
        }
    }

    public Map<String, Object> claimStarterLoan(UUID playerUuid) {
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            return Map.of("success", false, "reason", "领取操作必须在服务器主线程执行");
        }
        org.bukkit.OfflinePlayer player = org.bukkit.Bukkit.getOfflinePlayer(playerUuid);
        boolean credited = false;
        double amount = 0;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return Map.of("success", false, "reason", "数据库不可用");
            conn.setAutoCommit(false);
            try {
                Map<String, Double> config = guidanceConfig(conn);
                amount = config.getOrDefault("starter_loan_amount", 2500.0);
                double rate = config.getOrDefault("starter_loan_rate", 0.02);
                int termDays = (int) Math.round(config.getOrDefault("starter_loan_term_days", 7.0));
                double totalCap = config.getOrDefault("starter_loan_total_cap", 100000.0);
                double dailyCap = config.getOrDefault("starter_loan_daily_cap", 20000.0);
                double reserve = Math.max(0, config.getOrDefault("seed_capital", 500000.0)
                        * Math.max(0, Math.min(1, config.getOrDefault("starter_loan_min_reserve_ratio", 0.20))));
                if (config.getOrDefault("enabled", 1.0) < 0.5 || !Double.isFinite(amount) || amount <= 0
                        || !Double.isFinite(rate) || rate < 0 || termDays <= 0) {
                    conn.rollback();
                    return Map.of("success", false, "reason", "引导贷款当前不可用");
                }
                try (PreparedStatement claimed = conn.prepareStatement("SELECT 1 FROM ks_bank_guidance_claims WHERE player_uuid=?")) {
                    claimed.setString(1, playerUuid.toString());
                    try (ResultSet rs = claimed.executeQuery()) {
                        if (rs.next()) { conn.rollback(); return Map.of("success", false, "reason", "每位玩家只能领取一次引导贷款"); }
                    }
                }
                long now = System.currentTimeMillis() / 1000;
                long today = (now / 86400) * 86400;
                if (sumGuidanceClaims(conn, 0) + amount > totalCap) {
                    conn.rollback(); return Map.of("success", false, "reason", "本期引导贷款总额度已用尽");
                }
                if (sumGuidanceClaims(conn, today) + amount > dailyCap) {
                    conn.rollback(); return Map.of("success", false, "reason", "今日引导贷款额度已用尽");
                }
                try (PreparedStatement debit = conn.prepareStatement(
                        "UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND status='ACTIVE' AND total_assets>=?")) {
                    debit.setDouble(1, amount);
                    debit.setString(2, GUIDANCE_BANK_ID);
                    debit.setDouble(3, amount + reserve);
                    if (debit.executeUpdate() != 1) {
                        conn.rollback(); return Map.of("success", false, "reason", "引导银行准备金不足或未启用");
                    }
                }
                String loanId = "GL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
                try (PreparedStatement loan = conn.prepareStatement(
                        "INSERT INTO ks_bank_loans (id,bank_id,borrower_uuid,principal,remaining,interest_rate,term_days,issued_at,due_at,status) VALUES (?,?,?,?,?,?,?,?,?, 'ACTIVE')")) {
                    loan.setString(1, loanId);
                    loan.setString(2, GUIDANCE_BANK_ID);
                    loan.setString(3, playerUuid.toString());
                    loan.setDouble(4, amount);
                    loan.setDouble(5, amount * (1 + rate));
                    loan.setDouble(6, rate);
                    loan.setInt(7, termDays);
                    loan.setLong(8, now);
                    loan.setLong(9, now + termDays * 86400L);
                    loan.executeUpdate();
                }
                try (PreparedStatement claim = conn.prepareStatement(
                        "INSERT INTO ks_bank_guidance_claims (player_uuid,loan_id,claimed_at) VALUES (?,?,?)")) {
                    claim.setString(1, playerUuid.toString());
                    claim.setString(2, loanId);
                    claim.setLong(3, now);
                    claim.executeUpdate();
                }
                if (!eco.vaultHook().deposit(player, amount)) {
                    conn.rollback();
                    return Map.of("success", false, "reason", "经济服务入账失败");
                }
                credited = true;
                conn.commit();
                return Map.of("success", true, "loanId", loanId, "amount", amount, "interestRate", rate,
                        "termDays", termDays, "message", "引导贷款已到账，请按期偿还");
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                if (credited) eco.vaultHook().withdraw(player, amount);
                eco.getLogger().warning("[银行] 发放引导贷款失败: " + e.getMessage());
                return Map.of("success", false, "reason", "引导贷款发放失败");
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            if (credited) eco.vaultHook().withdraw(player, amount);
            eco.getLogger().warning("[银行] 发放引导贷款失败: " + e.getMessage());
            return Map.of("success", false, "reason", "引导贷款发放失败");
        }
    }

    public Map<String, Object> guidanceConfig() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            result.putAll(guidanceConfig(conn));
            return result;
        } catch (SQLException e) {
            return Map.of();
        }
    }

    public boolean updateGuidanceConfig(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) return false;
        Set<String> allowed = Set.of("enabled", "starter_loan_amount", "starter_loan_rate", "starter_loan_term_days",
                "starter_loan_total_cap", "starter_loan_daily_cap", "starter_loan_min_reserve_ratio");
        Map<String, Double> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            if (!allowed.contains(entry.getKey())) continue;
            double value;
            try { value = entry.getValue() instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(entry.getValue())); }
            catch (NumberFormatException e) { return false; }
            if (!Double.isFinite(value) || value < 0 || ("starter_loan_min_reserve_ratio".equals(entry.getKey()) && value > 1)) return false;
            if ("starter_loan_term_days".equals(entry.getKey()) && (value < 1 || value > 3650 || value != Math.rint(value))) return false;
            accepted.put(entry.getKey(), value);
        }
        if (accepted.isEmpty()) return false;
        try (Connection conn = eco.ksCore().dataStore().getConnection();
             PreparedStatement ps = conn == null ? null : conn.prepareStatement(
                     "INSERT INTO ks_bank_guidance_config (key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            if (ps == null) return false;
            for (Map.Entry<String, Double> entry : accepted.entrySet()) {
                ps.setString(1, entry.getKey());
                ps.setString(2, String.valueOf(entry.getValue()));
                ps.addBatch();
            }
            ps.executeBatch();
            return true;
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 更新引导贷款配置失败: " + e.getMessage());
            return false;
        }
    }

    private double sumGuidanceClaims(Connection conn, long issuedAfter) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(l.principal),0) FROM ks_bank_guidance_claims c JOIN ks_bank_loans l ON l.id=c.loan_id WHERE l.bank_id=? AND l.issued_at>=?")) {
            ps.setString(1, GUIDANCE_BANK_ID);
            ps.setLong(2, issuedAfter);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0; }
        }
    }

    public Bank createBank(String name, String type, List<UUID> ownerUuids, double initialCapital) {
        return createBank(name, type, ownerUuids, initialCapital, null);
    }

    /**
     * 创建银行。payerUuid 非 null 时（玩家自助创建）：
     * - 初始资本不得低于 ks_bank_cb_config 的 bank_min_capital（默认 50000）
     * - 初始资本从付款人 Vault 余额真实扣除，扣不起则拒绝；入库失败自动退回
     */
    public Bank createBank(String name, String type, List<UUID> ownerUuids, double initialCapital, UUID payerUuid) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;

        org.bukkit.OfflinePlayer payer = null;
        if (payerUuid != null) {
            double minCapital = getConfigDouble("bank_min_capital", 50000);
            if (initialCapital < minCapital) {
                eco.getLogger().info("[银行] 创建被拒：初始资本 " + initialCapital + " 低于门槛 " + minCapital);
                return null;
            }
            payer = org.bukkit.Bukkit.getOfflinePlayer(payerUuid);
            if (!eco.vaultHook().has(payer, initialCapital)) return null;
            eco.vaultHook().withdraw(payer, initialCapital);
        }

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                if (payer != null) eco.vaultHook().deposit(payer, initialCapital);
                return null;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_bank_banks (id, name, type, owner_uuids, total_assets, created_at) " +
                    "VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, type);
                ps.setString(4, String.join(",", ownerUuids.stream().map(UUID::toString).toList()));
                ps.setDouble(5, initialCapital);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO ks_bank_members (bank_id, player_uuid, player_name, role, joined_at) VALUES (?,?,?,?,?)")) {
                for (UUID owner : ownerUuids) {
                    var player = org.bukkit.Bukkit.getOfflinePlayer(owner);
                    ps.setString(1, id);
                    ps.setString(2, owner.toString());
                    ps.setString(3, player.getName() == null ? owner.toString() : player.getName());
                    ps.setString(4, "OWNER");
                    ps.setLong(5, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return new Bank(id, name, type, ownerUuids, initialCapital, now);
        } catch (SQLException e) {
            if (payer != null) eco.vaultHook().deposit(payer, initialCapital);
            eco.getLogger().warning("[银行] 创建银行失败: " + e.getMessage());
            return null;
        }
    }

    /** 读央行配置键值（ks_bank_cb_config），无值或解析失败返回默认值。 */
    private double getConfigDouble(String key, double def) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return def;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM ks_bank_cb_config WHERE key=?")) {
                ps.setString(1, key);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return Double.parseDouble(rs.getString("value"));
            }
        } catch (Exception ignored) {}
        return def;
    }

    /**
     * 存款。
     */
    public boolean deposit(String bankId, UUID playerUuid, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) return false;
        // 目标银行必须存在且在营——否则玩家的钱会打进"幽灵银行"凭空消失
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_bank_banks WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, bankId);
                if (!ps.executeQuery().next()) return false;
            }
        } catch (SQLException e) {
            return false;
        }

        var player = org.bukkit.Bukkit.getOfflinePlayer(playerUuid);
        if (!eco.vaultHook().has(player, amount)) return false;
        if (!eco.vaultHook().withdraw(player, amount)) return false;

        // 存入银行账户
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                eco.vaultHook().deposit(player, amount);
                return false;
            }
            conn.setAutoCommit(false);
            // 更新账户余额
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_bank_accounts (id, bank_id, player_uuid, balance, opened_at) " +
                    "VALUES (?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET balance=balance+?")) {
                String accId = bankId + ":" + playerUuid;
                ps.setString(1, accId);
                ps.setString(2, bankId);
                ps.setString(3, playerUuid.toString());
                ps.setDouble(4, amount);
                ps.setLong(5, System.currentTimeMillis() / 1000);
                ps.setDouble(6, amount);
                ps.executeUpdate();
            }
            // 更新银行总资产
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=?")) {
                ps.setDouble(1, amount);
                ps.setString(2, bankId);
                ps.executeUpdate();
            }
            conn.commit();
            return true;
        } catch (SQLException e) {
            // 回滚
            eco.vaultHook().deposit(player, amount);
            eco.getLogger().warning("[银行] 存款失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 取款。
     */
    public boolean withdraw(String bankId, UUID playerUuid, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) return false;
        var player = org.bukkit.Bukkit.getOfflinePlayer(playerUuid);
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            String accId = bankId + ":" + playerUuid;
            // 检查余额
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_accounts SET balance=balance-? WHERE id=? AND bank_id=? AND balance>=?")) {
                ps.setDouble(1, amount);
                ps.setString(2, accId);
                ps.setString(3, bankId);
                ps.setDouble(4, amount);
                if (ps.executeUpdate() != 1) {
                    conn.rollback();
                    return false;
                }
            }
            // 扣款
            // 更新银行总资产
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND status='ACTIVE' AND total_assets>=?")) {
                ps.setDouble(1, amount);
                ps.setString(2, bankId);
                ps.setDouble(3, amount);
                if (ps.executeUpdate() != 1) {
                    conn.rollback();
                    return false;
                }
            }
            conn.commit();
            // Never call Vault while the database transaction is still open. If Vault fails
            // after a successful commit, restore the exact ledger mutation in a new transaction.
            if (!eco.vaultHook().deposit(player, amount)) {
                if (!restoreWithdrawal(bankId, playerUuid, amount)) {
                    eco.getLogger().severe("[Bank] Withdrawal payout failed and ledger compensation failed: " + bankId + "/" + playerUuid);
                }
                return false;
            }
            return true;
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 取款失败: " + e.getMessage());
            return false;
        }
    }

    // ---- 贷款 ----

    public boolean issueLoan(String bankId, UUID borrowerUuid, double principal, int termDays) {
        if (!Double.isFinite(principal) || principal <= 0 || termDays <= 0) return false;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            // 获取银行贷款利率与法定/自定准备金。普通贷款和企业融资使用同一流动性约束。
            double loanRate = 0.08;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT loan_rate FROM ks_bank_banks WHERE id=?")) {
                ps.setString(1, bankId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) loanRate = rs.getDouble("loan_rate");
                else return false;
            }
            double reserve = requiredReserve(conn, bankId);

            String id = UUID.randomUUID().toString().substring(0, 8);
            long now = System.currentTimeMillis() / 1000;
            long due = now + termDays * 86400L;

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_bank_loans (id, bank_id, borrower_uuid, principal, remaining, " +
                    "interest_rate, term_days, issued_at, due_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, bankId);
                ps.setString(3, borrowerUuid.toString());
                ps.setDouble(4, principal);
                ps.setDouble(5, principal * (1 + loanRate)); // 本息合计
                ps.setDouble(6, loanRate);
                ps.setInt(7, termDays);
                ps.setLong(8, now);
                ps.setLong(9, due);
                ps.executeUpdate();
            }

            // 放款真实扣减银行资产（条件更新原子完成"检查+扣减"，资产不足则 0 行更新）。
            // 放在 INSERT 之后：扣减失败只需删掉刚插入的贷款行，不会出现"资产扣了贷款没建"。
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND status='ACTIVE' AND total_assets>=?")) {
                ps.setDouble(1, principal);
                ps.setString(2, bankId);
                ps.setDouble(3, principal + reserve);
                if (ps.executeUpdate() == 0) {
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM ks_bank_loans WHERE id=?")) {
                        del.setString(1, id);
                        del.executeUpdate();
                    }
                    return false; // 银行资产不足
                }
            }

            conn.commit();
            // Commit the durable loan first, then perform the external wallet payout.
            // A failed payout is compensated by deleting the newly issued loan and restoring assets.
            var player = org.bukkit.Bukkit.getOfflinePlayer(borrowerUuid);
            if (!eco.vaultHook().deposit(player, principal)) {
                if (!cancelIssuedLoan(id, bankId, principal)) {
                    eco.getLogger().severe("[Bank] Loan payout failed and ledger compensation failed: " + id);
                }
                return false;
            }
            return true;
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 放贷失败: " + e.getMessage());
            return false;
        }
    }

    private boolean restoreWithdrawal(String bankId, UUID playerUuid, double amount) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try (PreparedStatement account = conn.prepareStatement(
                    "UPDATE ks_bank_accounts SET balance=balance+? WHERE id=? AND bank_id=?");
                 PreparedStatement bank = conn.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'")) {
                account.setDouble(1, amount); account.setString(2, bankId + ":" + playerUuid); account.setString(3, bankId);
                bank.setDouble(1, amount); bank.setString(2, bankId);
                if (account.executeUpdate() != 1 || bank.executeUpdate() != 1) {
                    conn.rollback();
                    return false;
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                return false;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean cancelIssuedLoan(String loanId, String bankId, double principal) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM ks_bank_loans WHERE id=? AND bank_id=? AND status='ACTIVE'");
                 PreparedStatement bank = conn.prepareStatement("UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'")) {
                delete.setString(1, loanId); delete.setString(2, bankId);
                bank.setDouble(1, principal); bank.setString(2, bankId);
                if (delete.executeUpdate() != 1 || bank.executeUpdate() != 1) {
                    conn.rollback();
                    return false;
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                return false;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private double requiredReserve(Connection conn, String bankId) throws SQLException {
        double ratio = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT reserve_ratio FROM ks_bank_banks WHERE id=?")) {
            ps.setString(1, bankId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) ratio = rs.getDouble(1); }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT reserve_requirement FROM ks_bank_cb_rates ORDER BY set_at DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) ratio = Math.max(ratio, rs.getDouble(1));
        }
        ratio = Math.max(0, Math.min(1, ratio));
        double deposits = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(balance),0) FROM ks_bank_accounts WHERE bank_id=?")) {
            ps.setString(1, bankId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) deposits += rs.getDouble(1); }
        }
        // The bank extra can start before the enterprise schema on a fresh install.
        // Player deposits must still be protected rather than rejecting every ordinary loan.
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(balance),0) FROM ks_ent_corporate_accounts WHERE bank_id=?")) {
            ps.setString(1, bankId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) deposits += rs.getDouble(1); }
        } catch (SQLException ignored) {}
        return deposits * ratio;
    }

    /**
     * 还款。
     */
    public boolean repayLoan(String loanId, UUID borrowerUuid, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) return false;
        var player = org.bukkit.Bukkit.getOfflinePlayer(borrowerUuid);
        LoanSnapshot loan = findRepayableLoan(loanId, borrowerUuid);
        if (loan == null) return false;

        double pay = Math.min(amount, loan.remaining());
        if (!eco.vaultHook().has(player, pay) || !eco.vaultHook().withdraw(player, pay)) return false;

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                refundRepayment(player, pay);
                return false;
            }
            conn.setAutoCommit(false);
            try {
                double newRemaining = loan.remaining() - pay;
                String newStatus = newRemaining <= 0.01 ? "PAID" : loan.status();
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_bank_loans SET remaining=?, status=? WHERE id=? AND borrower_uuid=? " +
                        "AND remaining=? AND status IN ('ACTIVE','OVERDUE')")) {
                    ps.setDouble(1, newRemaining);
                    ps.setString(2, newStatus);
                    ps.setString(3, loanId);
                    ps.setString(4, borrowerUuid.toString());
                    ps.setDouble(5, loan.remaining());
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        refundRepayment(player, pay);
                        return false;
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'")) {
                    ps.setDouble(1, pay);
                    ps.setString(2, loan.bankId());
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        refundRepayment(player, pay);
                        return false;
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                refundRepayment(player, pay);
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[Bank] Loan repayment database update failed: " + e.getMessage());
            return false;
        }
    }

    private LoanSnapshot findRepayableLoan(String loanId, UUID borrowerUuid) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT bank_id, remaining, status FROM ks_bank_loans WHERE id=? AND borrower_uuid=? " +
                    "AND status IN ('ACTIVE','OVERDUE')")) {
                ps.setString(1, loanId);
                ps.setString(2, borrowerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new LoanSnapshot(rs.getString("bank_id"), rs.getDouble("remaining"),
                            rs.getString("status")) : null;
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[Bank] Loan repayment lookup failed: " + e.getMessage());
            return null;
        }
    }

    private void refundRepayment(org.bukkit.OfflinePlayer player, double amount) {
        if (!eco.vaultHook().deposit(player, amount)) {
            eco.getLogger().severe("[Bank] Loan repayment rollback could not refund " + amount
                    + " to " + player.getUniqueId());
        }
    }

    private record LoanSnapshot(String bankId, double remaining, String status) {
    }

    @Deprecated
    private boolean repayLoanLegacy(String loanId, UUID borrowerUuid, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) return false;
        var player = org.bukkit.Bukkit.getOfflinePlayer(borrowerUuid);
        double chargedAmount = 0.0;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            // 获取贷款信息
            double remaining;
            String bankId;
            String currStatus;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT bank_id, remaining, status FROM ks_bank_loans WHERE id=? AND borrower_uuid=? AND status IN ('ACTIVE','OVERDUE')")) {
                ps.setString(1, loanId);
                ps.setString(2, borrowerUuid.toString());
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) return false;
                bankId = rs.getString("bank_id");
                remaining = rs.getDouble("remaining");
                currStatus = rs.getString("status");
            }

            double pay = Math.min(amount, remaining);
            if (!eco.vaultHook().has(player, pay)) return false;
            if (!eco.vaultHook().withdraw(player, pay)) return false;
            chargedAmount = pay;

            // 更新贷款（部分还款保留原状态：逾期贷款还清前仍是 OVERDUE）
            double newRemaining = remaining - pay;
            String status = newRemaining <= 0.01 ? "PAID" : currStatus;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_loans SET remaining=?, status=? WHERE id=? AND borrower_uuid=? AND remaining>=? AND status IN ('ACTIVE','OVERDUE')")) {
                ps.setDouble(1, newRemaining);
                ps.setString(2, status);
                ps.setString(3, loanId);
                ps.setString(4, borrowerUuid.toString());
                ps.setDouble(5, pay);
                if (ps.executeUpdate() != 1) {
                    conn.rollback();
                    eco.vaultHook().deposit(player, pay);
                    return false;
                }
            }

            // 还款金额回到银行
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'")) {
                ps.setDouble(1, pay);
                ps.setString(2, bankId);
                if (ps.executeUpdate() != 1) {
                    conn.rollback();
                    eco.vaultHook().deposit(player, pay);
                    return false;
                }
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 还款失败: " + e.getMessage());
            return false;
        }
    }

    // ---- 贷款申请（玩家自助申请 + 银行审批） ----

    /**
     * 玩家向指定银行提交贷款申请，等待银行所有者/有 APPROVE_LOAN 权限的成员审批。
     */
    public String requestLoan(String bankId, UUID borrowerUuid, String borrowerName, double principal, int termDays) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_bank_banks WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, bankId);
                if (!ps.executeQuery().next()) return null;
            }
            String id = UUID.randomUUID().toString().substring(0, 8);
            long now = System.currentTimeMillis() / 1000;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_bank_loan_requests (id, bank_id, borrower_uuid, borrower_name, " +
                    "principal, term_days, requested_at) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, bankId);
                ps.setString(3, borrowerUuid.toString());
                ps.setString(4, borrowerName);
                ps.setDouble(5, principal);
                ps.setInt(6, termDays);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            return id;
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 提交贷款申请失败: " + e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> listLoanRequests(String bankId, String status) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            String sql = "SELECT * FROM ks_bank_loan_requests WHERE bank_id=?" +
                    (status != null ? " AND status=?" : "") + " ORDER BY requested_at DESC LIMIT 100";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, bankId);
                if (status != null) ps.setString(2, status);
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 查询贷款申请失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 批准贷款申请：实际放款（复用 issueLoan）并将申请标记为 APPROVED。
     */
    public boolean approveLoanRequest(String requestId) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            String bankId, borrowerUuid;
            double principal;
            int termDays;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT bank_id, borrower_uuid, principal, term_days FROM ks_bank_loan_requests " +
                    "WHERE id=? AND status='PENDING'")) {
                ps.setString(1, requestId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) return false;
                bankId = rs.getString("bank_id");
                borrowerUuid = rs.getString("borrower_uuid");
                principal = rs.getDouble("principal");
                termDays = rs.getInt("term_days");
            }
            // Claim the request before releasing this connection. A second approver
            // sees PROCESSING and cannot create a duplicate loan.
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_loan_requests SET status='PROCESSING' WHERE id=? AND status='PENDING'")) {
                ps.setString(1, requestId);
                if (ps.executeUpdate() != 1) return false;
            }
            if (!issueLoan(bankId, UUID.fromString(borrowerUuid), principal, termDays)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_bank_loan_requests SET status='PENDING' WHERE id=? AND status='PROCESSING'")) {
                    ps.setString(1, requestId);
                    ps.executeUpdate();
                }
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_loan_requests SET status='APPROVED', decided_at=? WHERE id=? AND status='PROCESSING'")) {
                ps.setLong(1, System.currentTimeMillis() / 1000);
                ps.setString(2, requestId);
                if (ps.executeUpdate() != 1) return false;
            }
            return true;
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 批准贷款申请失败: " + e.getMessage());
            return false;
        }
    }

    public boolean rejectLoanRequest(String requestId) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_loan_requests SET status='REJECTED', decided_at=? WHERE id=? AND status='PENDING'")) {
                ps.setLong(1, System.currentTimeMillis() / 1000);
                ps.setString(2, requestId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 拒绝贷款申请失败: " + e.getMessage());
            return false;
        }
    }

    public List<Map<String, Object>> myLoanRequests(UUID borrowerUuid) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM ks_bank_loan_requests WHERE borrower_uuid=? ORDER BY requested_at DESC LIMIT 50")) {
                ps.setString(1, borrowerUuid.toString());
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 查询我的贷款申请失败: " + e.getMessage());
        }
        return out;
    }

    // ---- 利息结算 + 逾期标记 ----

    /**
     * 周期性利息结算 + 逾期贷款标记。由 BankExtra 的定时任务在异步线程调用。
     *
     * 周期天数读 ks_bank_cb_config 键 interest_period_days（默认 7 = 周结，可设 30 月结 / 1 日结）。
     * 利率语义：ks_bank_banks.interest_rate 是「每周期利率」（非年化）。
     * 利息加进账户余额但不动银行 total_assets（total_assets = 自有资金 + 存款，
     * 存款负债增加即自有资金减少）；银行自有净头寸不足支付时本轮跳过该账户。
     * 结算按时间戳锚点推进，停服期间的周期在下次运行时一次性补齐。
     */
    public void settleInterestAndOverdue() {
        long now = System.currentTimeMillis() / 1000;
        double periodDays = getConfigDouble("interest_period_days", 7);
        if (periodDays <= 0) periodDays = 7;
        long periodSec = Math.max(60L, (long) (periodDays * 86400));

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;

            // 1) 逾期标记
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_loans SET status='OVERDUE' WHERE status='ACTIVE' AND due_at < ?")) {
                ps.setLong(1, now);
                int n = ps.executeUpdate();
                if (n > 0) eco.getLogger().info("[银行] " + n + " 笔贷款已标记为逾期 (OVERDUE)");
            }

            // 2) 利息锚点初始化：NULL 视为"从现在起算"，不补发历史利息
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_accounts SET last_interest_at=? WHERE last_interest_at IS NULL")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }

            // 3) 各银行净头寸与利率快照
            Map<String, Double> equity = new HashMap<>();
            Map<String, Double> rate = new HashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                    "SELECT b.id, b.interest_rate, b.total_assets, " +
                    "COALESCE((SELECT SUM(balance) FROM ks_bank_accounts WHERE bank_id=b.id),0) AS deposits " +
                    "FROM ks_bank_banks b WHERE b.status='ACTIVE'")) {
                while (rs.next()) {
                    equity.put(rs.getString("id"), rs.getDouble("total_assets") - rs.getDouble("deposits"));
                    rate.put(rs.getString("id"), rs.getDouble("interest_rate"));
                }
            }

            // 4) 找到期账户并计息（先收集后更新，避免同连接游标堆叠）
            record Payout(String accId, double interest, long newAnchor) {}
            List<Payout> payouts = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, bank_id, balance, last_interest_at FROM ks_bank_accounts " +
                    "WHERE balance > 0 AND last_interest_at <= ?")) {
                ps.setLong(1, now - periodSec);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String bankId = rs.getString("bank_id");
                    Double r = rate.get(bankId);
                    if (r == null || r <= 0) continue;
                    long anchor = rs.getLong("last_interest_at");
                    long periods = (now - anchor) / periodSec;
                    if (periods <= 0) continue;
                    double interest = rs.getDouble("balance") * r * periods;
                    if (interest <= 0) continue;
                    double avail = equity.getOrDefault(bankId, 0.0);
                    if (avail < interest) continue; // 银行自有资金付不起，本轮跳过
                    equity.put(bankId, avail - interest);
                    payouts.add(new Payout(rs.getString("id"), interest, anchor + periods * periodSec));
                }
            }

            int paid = 0;
            double totalPaid = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_accounts SET balance=balance+?, " +
                    "interest_earned=COALESCE(interest_earned,0)+?, last_interest_at=? WHERE id=?")) {
                for (Payout p : payouts) {
                    ps.setDouble(1, p.interest());
                    ps.setDouble(2, p.interest());
                    ps.setLong(3, p.newAnchor());
                    ps.setString(4, p.accId());
                    totalPaid += p.interest();
                    ps.addBatch();
                }
                for (int changed : ps.executeBatch()) if (changed > 0 || changed == Statement.SUCCESS_NO_INFO) paid++;
            }
            if (paid > 0) eco.getLogger().info(String.format("[银行] 利息结算完成: %d 个账户共发放 %.2f", paid, totalPaid));
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 利息结算失败: " + e.getMessage());
        }
    }

    // ---- 数据类 ----

    public record Bank(String id, String name, String type, List<UUID> ownerUuids,
                       double totalAssets, long createdAt) {}
}
