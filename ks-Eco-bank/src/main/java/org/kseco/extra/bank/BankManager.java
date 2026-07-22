package org.kseco.extra.bank;

import org.kseco.KsEco;
import org.kseco.extra.BankAccessProvider;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

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
    static final String APPROVAL_REQUEST_SELECT =
            "SELECT bank_id,borrower_uuid,principal,term_days,quoted_rate,product_type,repayment_type,purpose "
                    + "FROM ks_bank_loan_requests WHERE id=? AND status='PENDING'";

    private final KsEco eco;

    public BankManager(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        createTables();
        recoverInterruptedLoanDisbursements();
        recoverLoanRepaymentSettlements();
    }

    private void createTables() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            BankSchema.initialize(conn);
            ensureLoanPolicyDefaults(conn);
            ensureGuidanceBank(conn);
        } catch (SQLException e) {
            eco.getLogger().severe("[银行] 核心表初始化失败，银行模块拒绝启动: " + e.getMessage());
            throw new IllegalStateException("Bank schema initialization failed", e);
        }
    }

    private void ensureLoanPolicyDefaults(Connection conn) throws SQLException {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("loan_min_principal", "100");
        defaults.put("loan_max_principal", "50000");
        defaults.put("loan_min_term_days", "1");
        defaults.put("loan_max_term_days", "90");
        defaults.put("loan_max_active_per_borrower", "3");
        defaults.put("loan_max_pending_per_bank", "1");
        defaults.put("loan_block_overdue", "1");
        defaults.put("loan_credit_base_score", "650");
        defaults.put("loan_credit_min_score", "520");
        defaults.put("loan_credit_recent_window_days", "30");
        defaults.put("loan_tier_a_min", "760");
        defaults.put("loan_tier_b_min", "700");
        defaults.put("loan_tier_c_min", "640");
        defaults.put("loan_tier_d_min", "580");
        defaults.put("loan_limit_factor_a", "1.0");
        defaults.put("loan_limit_factor_b", "0.85");
        defaults.put("loan_limit_factor_c", "0.65");
        defaults.put("loan_limit_factor_d", "0.40");
        defaults.put("loan_limit_factor_e", "0.20");
        defaults.put("loan_max_term_a_days", "90");
        defaults.put("loan_max_term_b_days", "90");
        defaults.put("loan_max_term_c_days", "60");
        defaults.put("loan_max_term_d_days", "30");
        defaults.put("loan_max_term_e_days", "14");
        defaults.put("loan_risk_spread_a", "-0.005");
        defaults.put("loan_risk_spread_b", "0");
        defaults.put("loan_risk_spread_c", "0.01");
        defaults.put("loan_risk_spread_d", "0.025");
        defaults.put("loan_risk_spread_e", "0.05");
        defaults.put("loan_term_spread_per_30_days", "0.005");
        defaults.put("loan_term_spread_cap", "0.02");
        defaults.put("loan_effective_rate_min", "0");
        defaults.put("loan_effective_rate_max", "0.25");
        defaults.put("loan_quote_valid_seconds", "120");
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            BankSqlMutation.insertIfAbsent(conn,
                    "SELECT 1 FROM ks_bank_cb_config WHERE config_key=?", ps -> ps.setString(1, entry.getKey()),
                    "INSERT INTO ks_bank_cb_config (config_key,config_value) VALUES (?,?)", ps -> {
                        ps.setString(1, entry.getKey());
                        ps.setString(2, entry.getValue());
                    });
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
        defaults.put("starter_loan_block_overdue", "1");
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            BankSqlMutation.insertIfAbsent(conn,
                    "SELECT 1 FROM ks_bank_guidance_config WHERE config_key=?",
                    ps -> ps.setString(1, entry.getKey()),
                    "INSERT INTO ks_bank_guidance_config (config_key,config_value) VALUES (?,?)", ps -> {
                        ps.setString(1, entry.getKey());
                        ps.setString(2, entry.getValue());
                    });
        }

        double seedCapital = guidanceConfig(conn).getOrDefault("seed_capital", 500000.0);
        long now = System.currentTimeMillis() / 1000;
        BankSqlMutation.insertIfAbsent(conn,
                "SELECT 1 FROM ks_bank_banks WHERE id=?", ps -> ps.setString(1, GUIDANCE_BANK_ID),
                "INSERT INTO ks_bank_banks (id,name,type,owner_uuids,total_assets,status,created_at) "
                        + "VALUES (?,?,?,?,?,'ACTIVE',?)", bank -> {
                    bank.setString(1, GUIDANCE_BANK_ID);
                    bank.setString(2, GUIDANCE_BANK_NAME);
                    bank.setString(3, "GUIDANCE");
                    bank.setString(4, GUIDANCE_OWNER);
                    bank.setDouble(5, seedCapital);
                    bank.setLong(6, now);
                });
        // Existing GUIDE-BANK installations keep their balance sheet; only the public name changes.
        try (PreparedStatement rename = conn.prepareStatement(
                "UPDATE ks_bank_banks SET name=?, type='GUIDANCE' WHERE id=?")) {
            rename.setString(1, GUIDANCE_BANK_NAME);
            rename.setString(2, GUIDANCE_BANK_ID);
            rename.executeUpdate();
        }
        BankSqlMutation.insertIfAbsent(conn,
                "SELECT 1 FROM ks_bank_members WHERE bank_id=? AND player_uuid=?", owner -> {
                    owner.setString(1, GUIDANCE_BANK_ID);
                    owner.setString(2, GUIDANCE_OWNER);
                }, "INSERT INTO ks_bank_members (bank_id,player_uuid,player_name,role,joined_at) VALUES (?,?,?,?,?)",
                owner -> {
                    owner.setString(1, GUIDANCE_BANK_ID);
                    owner.setString(2, GUIDANCE_OWNER);
                    owner.setString(3, "SYSTEM");
                    owner.setString(4, "OWNER");
                    owner.setLong(5, now);
                });
    }

    private Map<String, Double> guidanceConfig(Connection conn) throws SQLException {
        Map<String, Double> result = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT config_key,config_value FROM ks_bank_guidance_config");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    result.put(rs.getString("config_key"), Double.parseDouble(rs.getString("config_value")));
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
            boolean blockOverdue = config.getOrDefault("starter_loan_block_overdue", 1.0) >= 0.5;
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
            boolean overdue = blockOverdue && hasOverdueLoan(conn, playerUuid);
            double reserve = Math.max(0, seed * Math.max(0, Math.min(1, reserveRatio)));
            String reason = null;
            if (config.getOrDefault("enabled", 1.0) < 0.5) reason = "引导贷款目前未开放";
            else if (!"ACTIVE".equalsIgnoreCase(bankStatus)) reason = "引导银行当前不可用";
            else if (claimed) reason = "每位玩家只能领取一次引导贷款";
            else if (overdue) reason = "请先还清逾期贷款后再申请引导贷款";
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
            result.put("blockedByOverdue", overdue);
            result.put("eligible", reason == null);
            result.put("reason", reason == null ? "可领取" : reason);
            return Map.copyOf(result);
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 读取引导贷款状态失败: " + e.getMessage());
            return Map.of("available", false, "reason", "读取引导贷款状态失败");
        }
    }

    public Map<String, Object> claimStarterLoan(UUID playerUuid) {
        if (playerUuid == null) return Map.of("success", false, "reason", "玩家身份无效");
        GuidanceReservation reservation;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return Map.of("success", false, "reason", "数据库不可用");
            conn.setAutoCommit(false);
            try {
                Map<String, Double> config = guidanceConfig(conn);
                double amount = config.getOrDefault("starter_loan_amount", 2500.0);
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
                if (config.getOrDefault("starter_loan_block_overdue", 1.0) >= 0.5
                        && hasOverdueLoan(conn, playerUuid)) {
                    conn.rollback();
                    return Map.of("success", false, "reason", "请先还清逾期贷款后再申请引导贷款");
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
                long dueAt = now + termDays * 86400L;
                try (PreparedStatement loan = conn.prepareStatement(
                        "INSERT INTO ks_bank_loans (id,bank_id,borrower_uuid,principal,remaining,interest_rate,term_days,issued_at,due_at,status) VALUES (?,?,?,?,?,?,?,?,?, '" + LoanPayoutState.PENDING + "')")) {
                    loan.setString(1, loanId);
                    loan.setString(2, GUIDANCE_BANK_ID);
                    loan.setString(3, playerUuid.toString());
                    loan.setDouble(4, amount);
                    loan.setDouble(5, amount * (1 + rate));
                    loan.setDouble(6, rate);
                    loan.setInt(7, termDays);
                    loan.setLong(8, now);
                    loan.setLong(9, dueAt);
                    loan.executeUpdate();
                }
                try (PreparedStatement claim = conn.prepareStatement(
                        "INSERT INTO ks_bank_guidance_claims (player_uuid,loan_id,claimed_at) VALUES (?,?,?)")) {
                    claim.setString(1, playerUuid.toString());
                    claim.setString(2, loanId);
                    claim.setLong(3, now);
                    claim.executeUpdate();
                }
                conn.commit();
                reservation = new GuidanceReservation(loanId, amount, rate, termDays, dueAt);
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                eco.getLogger().warning("[银行] 发放引导贷款失败: " + e.getMessage());
                return Map.of("success", false, "reason", "引导贷款发放失败");
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 发放引导贷款失败: " + e.getMessage());
            return Map.of("success", false, "reason", "引导贷款发放失败");
        }

        // External economy settlement happens only after the SQLite transaction is durable.
        if (!beginLoanPayout(reservation.loanId())) {
            cancelGuidanceReservation(playerUuid, reservation);
            return Map.of("success", false, "reason", "贷款放款状态初始化失败");
        }
        if (!creditWallet(playerUuid, reservation.amount())) {
            if (!cancelGuidanceReservation(playerUuid, reservation)) {
                eco.getLogger().severe("[Bank] Guidance payout failed and ledger compensation failed: "
                        + reservation.loanId());
            }
            return Map.of("success", false, "reason", "经济服务入账失败，贷款记录已撤销");
        }
        if (!activateLoan(reservation.loanId())) {
            eco.getLogger().severe("[Bank] Guidance wallet credited but loan activation failed: "
                    + reservation.loanId());
            return Map.of("success", false, "reason", "贷款激活失败，请联系管理员核对");
        }
        notifyPlayer(playerUuid, String.format(Locale.ROOT,
                "§a引导贷款已到账：%.2f，固定利率 %.2f%%，%d 天内应还 %.2f。",
                reservation.amount(), reservation.rate() * 100, reservation.termDays(),
                reservation.amount() * (1 + reservation.rate())));
        return Map.of("success", true, "loanId", reservation.loanId(), "amount", reservation.amount(),
                "interestRate", reservation.rate(), "termDays", reservation.termDays(),
                "dueAt", reservation.dueAt(), "message", "引导贷款已到账，请按期偿还");
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
                "starter_loan_total_cap", "starter_loan_daily_cap", "starter_loan_min_reserve_ratio",
                "starter_loan_block_overdue");
        Map<String, Double> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            if (!allowed.contains(entry.getKey())) continue;
            double value;
            try { value = entry.getValue() instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(entry.getValue())); }
            catch (NumberFormatException e) { return false; }
            if (!Double.isFinite(value) || value < 0 || ("starter_loan_min_reserve_ratio".equals(entry.getKey()) && value > 1)) return false;
            if (("enabled".equals(entry.getKey()) || "starter_loan_block_overdue".equals(entry.getKey()))
                    && value != 0 && value != 1) return false;
            if ("starter_loan_term_days".equals(entry.getKey()) && (value < 1 || value > 3650 || value != Math.rint(value))) return false;
            accepted.put(entry.getKey(), value);
        }
        if (accepted.isEmpty()) return false;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            for (Map.Entry<String, Double> entry : accepted.entrySet()) {
                String value = String.valueOf(entry.getValue());
                BankSqlMutation.upsert(conn,
                        "UPDATE ks_bank_guidance_config SET config_value=? WHERE config_key=?", update -> {
                            update.setString(1, value);
                            update.setString(2, entry.getKey());
                        }, "INSERT INTO ks_bank_guidance_config (config_key,config_value) VALUES (?,?)", insert -> {
                            insert.setString(1, entry.getKey());
                            insert.setString(2, value);
                        });
            }
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
        if (name == null || type == null || ownerUuids == null || !Double.isFinite(initialCapital)
                || initialCapital < 0) return null;
        name = name.trim();
        type = type.trim().toUpperCase(Locale.ROOT);
        List<UUID> owners = ownerUuids.stream().filter(Objects::nonNull).distinct().toList();
        if (name.length() < 2 || name.length() > 32 || owners.isEmpty() || owners.size() > 8
                || !Set.of("COMMERCIAL", "CENTRAL", "GUIDANCE").contains(type)) return null;

        String id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;

        if (payerUuid != null) {
            double minCapital = getConfigDouble("bank_min_capital", 50000);
            if (initialCapital < minCapital) {
                eco.getLogger().info("[银行] 创建被拒：初始资本 " + initialCapital + " 低于门槛 " + minCapital);
                return null;
            }
            if (!debitWallet(payerUuid, initialCapital)) return null;
        }
        Map<UUID, String> ownerNames = snapshotPlayerNames(owners);

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                if (payerUuid != null) refundWallet(payerUuid, initialCapital, "bank creation database unavailable");
                return null;
            }
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_bank_banks (id, name, type, owner_uuids, total_assets, created_at) " +
                        "VALUES (?,?,?,?,?,?)")) {
                    ps.setString(1, id);
                    ps.setString(2, name);
                    ps.setString(3, type);
                    ps.setString(4, String.join(",", owners.stream().map(UUID::toString).toList()));
                    ps.setDouble(5, initialCapital);
                    ps.setLong(6, now);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_bank_members (bank_id, player_uuid, player_name, role, joined_at) VALUES (?,?,?,?,?)")) {
                    for (UUID owner : owners) {
                        ps.setString(1, id);
                        ps.setString(2, owner.toString());
                        ps.setString(3, ownerNames.getOrDefault(owner, owner.toString()));
                        ps.setString(4, "OWNER");
                        ps.setLong(5, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
            return new Bank(id, name, type, List.copyOf(owners), initialCapital, now);
        } catch (SQLException e) {
            if (payerUuid != null) refundWallet(payerUuid, initialCapital, "bank creation rollback");
            eco.getLogger().warning("[银行] 创建银行失败: " + e.getMessage());
            return null;
        }
    }

    /** 读央行配置键值（ks_bank_cb_config），无值或解析失败返回默认值。 */
    private double getConfigDouble(String key, double def) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return def;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT config_value FROM ks_bank_cb_config WHERE config_key=?")) {
                ps.setString(1, key);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return Double.parseDouble(rs.getString("config_value"));
            }
        } catch (Exception ignored) {}
        return def;
    }

    /**
     * 存款。
     */
    public boolean deposit(String bankId, UUID playerUuid, double amount) {
        if (bankId == null || bankId.isBlank() || playerUuid == null || !Double.isFinite(amount) || amount <= 0) return false;
        long amountMinor;
        try {
            amountMinor = BankInterestSettlementStore.toMinor(amount);
        } catch (IllegalArgumentException | ArithmeticException error) {
            return false;
        }
        if (amountMinor <= 0) return false;
        double settledAmount = BankInterestSettlementStore.fromMinor(amountMinor);
        // 目标银行必须存在且在营——否则玩家的钱会打进"幽灵银行"凭空消失
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_bank_banks WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, bankId);
                if (!ps.executeQuery().next()) return false;
            }
            if (!bankAcceptsNewBusiness(conn, bankId)) return false;
        } catch (SQLException e) {
            return false;
        }

        if (!debitWallet(playerUuid, settledAmount)) return false;

        // 存入银行账户
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                refundWallet(playerUuid, settledAmount, "deposit database unavailable");
                return false;
            }
            conn.setAutoCommit(false);
            try {
                if (!bankAcceptsNewBusiness(conn, bankId)) {
                    conn.rollback();
                    refundWallet(playerUuid, settledAmount, "deposit bank entered resolution");
                    return false;
                }
                long now = System.currentTimeMillis() / 1000;
                String accId = bankId + ":" + playerUuid;
                BankInterestSettlementStore.SettlementResult settlement = mutateAccountBalance(
                        conn, bankId, accId, playerUuid, now, amountMinor, true);
                if (!settlement.applied()) {
                    conn.rollback();
                    refundWallet(playerUuid, settledAmount, "deposit account mutation rejected");
                    return false;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'")) {
                    ps.setDouble(1, settledAmount);
                    ps.setString(2, bankId);
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        refundWallet(playerUuid, settledAmount, "deposit target became unavailable");
                        return false;
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            refundWallet(playerUuid, settledAmount, "deposit rollback");
            eco.getLogger().warning("[银行] 存款失败: " + e.getMessage());
            return false;
        }
    }

    private boolean bankAcceptsNewBusiness(Connection connection, String bankId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT operating_status FROM ks_bank_risk_state WHERE bank_id=?")) {
            statement.setString(1, bankId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return true;
                return !Set.of("RESTRICTED", "RESOLUTION", "RESOLVED").contains(row.getString(1));
            }
        }
    }

    /**
     * 取款。
     */
    public boolean withdraw(String bankId, UUID playerUuid, double amount) {
        if (bankId == null || bankId.isBlank() || playerUuid == null || !Double.isFinite(amount) || amount <= 0) return false;
        long amountMinor;
        try {
            amountMinor = BankInterestSettlementStore.toMinor(amount);
        } catch (IllegalArgumentException | ArithmeticException error) {
            return false;
        }
        if (amountMinor <= 0) return false;
        double settledAmount = BankInterestSettlementStore.fromMinor(amountMinor);
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                String accId = bankId + ":" + playerUuid;
                BankInterestSettlementStore.SettlementResult settlement = mutateAccountBalance(
                        conn, bankId, accId, playerUuid, System.currentTimeMillis() / 1000,
                        -amountMinor, false);
                if (!settlement.applied()) {
                    conn.rollback();
                    return false;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND status='ACTIVE' AND total_assets>=?")) {
                    ps.setDouble(1, settledAmount);
                    ps.setString(2, bankId);
                    ps.setDouble(3, settledAmount);
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 取款失败: " + e.getMessage());
            return false;
        }
        // Never call Vault while the database transaction is still open. If Vault fails
        // after a successful commit, restore the exact ledger mutation in a new transaction.
        if (!creditWallet(playerUuid, settledAmount)) {
            if (!restoreWithdrawal(bankId, playerUuid, settledAmount)) {
                eco.getLogger().severe("[Bank] Withdrawal payout failed and ledger compensation failed: " + bankId + "/" + playerUuid);
            }
            return false;
        }
        return true;
    }

    BankInterestSettlementStore.SettlementResult mutateAccountBalance(
            Connection conn, String bankId, String accountId, UUID playerUuid, long now,
            long mutationMinor, boolean allowCreate) throws SQLException {
        double rate;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT interest_rate FROM ks_bank_banks WHERE id=? AND status='ACTIVE'")) {
            ps.setString(1, bankId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return BankInterestSettlementStore.SettlementResult.notApplied();
                rate = rs.getDouble(1);
            }
        }
        double periodDays = configDouble(conn, "interest_period_days", 7);
        long periodSeconds = Math.max(60L,
                (long) (Math.max(0.001, periodDays) * 86_400L));
        return BankInterestSettlementStore.apply(conn, bankId, accountId,
                playerUuid.toString(), rate, periodSeconds, now, mutationMinor, allowCreate);
    }

    private double configDouble(Connection conn, String key, double fallback) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT config_value FROM ks_bank_cb_config WHERE config_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        double value = Double.parseDouble(rs.getString(1));
                        return Double.isFinite(value) ? value : fallback;
                    } catch (NumberFormatException ignored) {
                        return fallback;
                    }
                }
            }
        }
        return fallback;
    }

    // ---- 贷款 ----

    public Map<String, Object> creditProfile(UUID borrowerUuid) {
        if (borrowerUuid == null) return Map.of("available", false, "reason", "玩家身份无效");
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return Map.of("available", false, "reason", "数据库不可用");
            LoanPolicy policy = loadLoanPolicy(conn);
            LoanPricingPolicy.Config config = loadPricingConfig(conn, policy);
            LoanPricingPolicy.Profile profile = LoanPricingPolicy.buildProfile(
                    loadCreditStats(conn, borrowerUuid, config), config,
                    policy.maxPrincipal(), policy.minTermDays(), policy.maxTermDays());
            return profileMap(profile, config, policy);
        } catch (SQLException e) {
            eco.getLogger().warning("[Bank] Credit profile query failed: " + e.getMessage());
            return Map.of("available", false, "reason", "信用资料读取失败");
        }
    }

    public Map<String, Object> loanQuote(String bankId, UUID borrowerUuid, double principal, int termDays) {
        if (bankId == null || bankId.isBlank() || borrowerUuid == null) {
            return Map.of("available", false, "allowed", false, "reason", "贷款参数无效");
        }
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return Map.of("available", false, "allowed", false, "reason", "数据库不可用");
            LoanAssessment assessment = assessLoan(conn, bankId, borrowerUuid, principal, termDays, true);
            return quoteMap(assessment, principal, termDays);
        } catch (SQLException e) {
            eco.getLogger().warning("[Bank] Loan quote failed: " + e.getMessage());
            return Map.of("available", false, "allowed", false, "reason", "贷款报价失败");
        }
    }

    public boolean issueLoan(String bankId, UUID borrowerUuid, double principal, int termDays) {
        LoanIssue issue = reserveLoan(bankId, borrowerUuid, principal, termDays, null);
        if (issue == null) return false;
        // Commit the durable loan first, then perform the external wallet payout.
        // A failed payout is compensated by deleting the newly issued loan and restoring assets.
        if (!beginLoanPayout(issue.loanId())) {
            cancelIssuedLoan(issue.loanId(), bankId, principal);
            return false;
        }
        if (!creditWallet(borrowerUuid, principal)) {
            if (!cancelIssuedLoan(issue.loanId(), bankId, principal)) {
                eco.getLogger().severe("[Bank] Loan payout failed and ledger compensation failed: " + issue.loanId());
            }
            return false;
        }
        if (!activateLoan(issue.loanId())) {
            eco.getLogger().severe("[Bank] Wallet credited but loan activation failed: " + issue.loanId());
            return false;
        }
        notifyLoanIssued(borrowerUuid, issue);
        return true;
    }

    private LoanIssue reserveLoan(String bankId, UUID borrowerUuid, double principal, int termDays,
                                  Double quotedRate) {
        if (bankId == null || bankId.isBlank() || borrowerUuid == null) return null;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            conn.setAutoCommit(false);
            try {
                LoanAssessment assessment = assessLoan(conn, bankId, borrowerUuid, principal, termDays, false);
                if (!assessment.allowed()) {
                    conn.rollback();
                    notifyLoanRejected(borrowerUuid, assessment.reason());
                    return null;
                }
                double rate = quotedRate != null && Double.isFinite(quotedRate) && quotedRate >= 0 && quotedRate <= 1
                        ? quotedRate : assessment.rate();
                LoanIssue issue = insertLoan(conn, bankId, borrowerUuid, principal, termDays, rate);
                if (issue == null) {
                    conn.rollback();
                    return null;
                }
                conn.commit();
                return issue;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 放贷失败: " + e.getMessage());
            return null;
        }
    }

    private LoanIssue insertLoan(Connection conn, String bankId, UUID borrowerUuid, double principal,
                                 int termDays, double rate) throws SQLException {
        return insertLoan(conn, bankId, borrowerUuid, principal, termDays, rate,
                "STANDARD", "BULLET", "");
    }

    private LoanIssue insertLoan(Connection conn, String bankId, UUID borrowerUuid, double principal,
                                 int termDays, double rate, String productType,
                                 String repaymentType, String purpose) throws SQLException {
        double reserve = requiredReserve(conn, bankId);
        String id = "LN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis() / 1000;
        long due = now + termDays * 86400L;
        double totalDue = principal * (1 + rate);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ks_bank_loans (id, bank_id, borrower_uuid, principal, remaining, " +
                "interest_rate, term_days, issued_at, due_at, product_type,repayment_type,purpose,status) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?, '" + LoanPayoutState.PENDING + "')")) {
            ps.setString(1, id);
            ps.setString(2, bankId);
            ps.setString(3, borrowerUuid.toString());
            ps.setDouble(4, principal);
            ps.setDouble(5, totalDue);
            ps.setDouble(6, rate);
            ps.setInt(7, termDays);
            ps.setLong(8, now);
            ps.setLong(9, due);
            ps.setString(10, productType == null ? "STANDARD" : productType);
            ps.setString(11, repaymentType == null ? "BULLET" : repaymentType);
            ps.setString(12, purpose == null ? "" : purpose);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ks_bank_banks SET total_assets=total_assets-? WHERE id=? AND status='ACTIVE' AND total_assets>=?")) {
            ps.setDouble(1, principal);
            ps.setString(2, bankId);
            ps.setDouble(3, principal + reserve);
            if (ps.executeUpdate() != 1) return null;
        }
        return new LoanIssue(id, bankId, principal, totalDue, rate, termDays, due);
    }

    private String normalizeLoanProduct(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Set.of("STANDARD", "CONSUMER", "HOME", "BUSINESS", "PROJECT").contains(normalized) ? normalized : null;
    }

    private String normalizeRepaymentType(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Set.of("BULLET", "EQUAL_PAYMENT", "EQUAL_PRINCIPAL").contains(normalized) ? normalized : null;
    }

    private boolean productTermsAllowed(String product, double principal, int termDays) {
        if (!Double.isFinite(principal) || principal <= 0 || termDays <= 0) return false;
        return switch (product) {
            case "CONSUMER" -> principal >= 100 && principal <= 20_000 && termDays <= 30;
            case "HOME" -> principal >= 5_000 && principal <= 50_000 && termDays >= 30 && termDays <= 90;
            case "BUSINESS" -> principal >= 1_000 && principal <= 50_000 && termDays >= 7 && termDays <= 90;
            case "PROJECT" -> principal >= 1_000 && principal <= 50_000 && termDays >= 7 && termDays <= 90;
            default -> false;
        };
    }

    private boolean productRequiresCollateral(String product) {
        return "HOME".equals(product) || "BUSINESS".equals(product) || "PROJECT".equals(product);
    }

    private double productEffectiveRate(double baseRate, String product) {
        double factor = switch (product == null ? "STANDARD" : product) {
            case "CONSUMER" -> 1.35;
            case "HOME" -> 0.72;
            case "BUSINESS" -> 0.95;
            case "PROJECT" -> 0.82;
            default -> 1.0;
        };
        return Math.max(0, Math.min(1, baseRate * factor));
    }

    private double activePolicyRateModifier(Connection conn) {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT COALESCE(SUM(rate_modifier),0) FROM ks_bank_policy_events "
                        + "WHERE status='ACTIVE' AND starts_at<=? AND ends_at>?")) {
            long current = System.currentTimeMillis() / 1000;
            statement.setLong(1, current);
            statement.setLong(2, current);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Math.max(-0.25, Math.min(0.25, row.getDouble(1))) : 0;
            }
        } catch (SQLException missingGameplaySchema) {
            return 0;
        }
    }

    private LoanAssessment assessLoan(Connection conn, String bankId, UUID borrowerUuid, double principal,
                                      int termDays, boolean checkPending) throws SQLException {
        LoanPolicy policy = loadLoanPolicy(conn);
        LoanPricingPolicy.Config pricingConfig = loadPricingConfig(conn, policy);
        LoanPricingPolicy.Profile profile = LoanPricingPolicy.buildProfile(
                loadCreditStats(conn, borrowerUuid, pricingConfig), pricingConfig,
                policy.maxPrincipal(), policy.minTermDays(), policy.maxTermDays());
        double baseRate;
        double liquidity;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT loan_rate,total_assets FROM ks_bank_banks WHERE id=? AND status='ACTIVE'")) {
            ps.setString(1, bankId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    LoanPricingPolicy.Quote quote = LoanPricingPolicy.quote(profile, pricingConfig, 0,
                            principal, termDays, System.currentTimeMillis() / 1000);
                    return new LoanAssessment(false, "目标银行不存在或未营业", policy, pricingConfig, quote, 0);
                }
                baseRate = rs.getDouble("loan_rate");
                liquidity = rs.getDouble("total_assets");
            }
        }
        LoanPricingPolicy.Quote quote = LoanPricingPolicy.quote(profile, pricingConfig, baseRate,
                principal, termDays, System.currentTimeMillis() / 1000);
        try (PreparedStatement risk = conn.prepareStatement(
                "SELECT operating_status FROM ks_bank_risk_state WHERE bank_id=?")) {
            risk.setString(1, bankId);
            try (ResultSet state = risk.executeQuery()) {
                if (state.next() && Set.of("RESTRICTED", "RESOLUTION").contains(state.getString(1))) {
                    return new LoanAssessment(false, "银行处于风险处置状态，暂停新增贷款",
                            policy, pricingConfig, quote, liquidity);
                }
            }
        } catch (SQLException missingGameplaySchema) {
            // Compatibility with the short initialization window before BankGameplayManager creates its tables.
        }
        if (!Double.isFinite(principal) || principal < policy.minPrincipal() || principal > policy.maxPrincipal()) {
            return new LoanAssessment(false, String.format(Locale.ROOT, "贷款金额须在 %.2f 至 %.2f 之间",
                    policy.minPrincipal(), policy.maxPrincipal()), policy, pricingConfig, quote, liquidity);
        }
        if (termDays < policy.minTermDays() || termDays > policy.maxTermDays()) {
            return new LoanAssessment(false, "贷款期限须在 " + policy.minTermDays() + " 至 "
                    + policy.maxTermDays() + " 天之间", policy, pricingConfig, quote, liquidity);
        }
        if (!Double.isFinite(baseRate) || baseRate < 0 || baseRate > 1) {
            return new LoanAssessment(false, "银行贷款基础利率配置无效", policy, pricingConfig, quote, liquidity);
        }
        if (policy.blockOverdue() && hasOverdueLoan(conn, borrowerUuid)) {
            return new LoanAssessment(false, "存在逾期贷款，请先完成还款", policy, pricingConfig, quote, liquidity);
        }
        if (!profile.eligible(pricingConfig)) {
            return new LoanAssessment(false, "信用评分不足，当前评分 " + profile.score()
                    + "，最低要求 " + pricingConfig.minEligibleScore(), policy, pricingConfig, quote, liquidity);
        }
        if (principal > profile.availableCredit() + 0.000_001) {
            return new LoanAssessment(false, String.format(Locale.ROOT,
                    "超过当前可用信用额度 %.2f（总额度 %.2f）",
                    profile.availableCredit(), profile.maxPrincipal()), policy, pricingConfig, quote, liquidity);
        }
        if (termDays > profile.maxTermDays()) {
            return new LoanAssessment(false, "当前信用等级最高可选 " + profile.maxTermDays() + " 天",
                    policy, pricingConfig, quote, liquidity);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM ks_bank_loans WHERE borrower_uuid=? " +
                "AND status IN ('ACTIVE','OVERDUE','PENDING_PAYOUT','PAYOUT_SETTLING','RECONCILE_REQUIRED')")) {
            ps.setString(1, borrowerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) >= policy.maxActiveLoans()) {
                    return new LoanAssessment(false, "在贷笔数已达到上限 " + policy.maxActiveLoans(),
                            policy, pricingConfig, quote, liquidity);
                }
            }
        }
        if (checkPending) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM ks_bank_loan_requests WHERE bank_id=? AND borrower_uuid=? " +
                    "AND status IN ('PENDING','PROCESSING')")) {
                ps.setString(1, bankId);
                ps.setString(2, borrowerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= policy.maxPendingPerBank()) {
                        return new LoanAssessment(false, "你在该银行已有待审批申请，请等待处理后再申请",
                                policy, pricingConfig, quote, liquidity);
                    }
                }
            }
        }
        double lendableLiquidity = Math.max(0, liquidity - requiredReserve(conn, bankId));
        if (principal > lendableLiquidity + 0.000_001) {
            return new LoanAssessment(false, String.format(Locale.ROOT,
                    "银行可放贷流动性不足，当前最多可放 %.2f", lendableLiquidity),
                    policy, pricingConfig, quote, lendableLiquidity);
        }
        return new LoanAssessment(true, "", policy, pricingConfig, quote, lendableLiquidity);
    }

    private LoanPricingPolicy.Config loadPricingConfig(Connection conn, LoanPolicy policy) throws SQLException {
        Map<String, Double> values = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT config_key,config_value FROM ks_bank_cb_config WHERE config_key LIKE 'loan_%'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try { values.put(rs.getString(1), Double.parseDouble(rs.getString(2))); }
                catch (NumberFormatException ignored) {}
            }
        }
        int aMin = boundedInt(values, "loan_tier_a_min", 760, 301, 850);
        int bMin = Math.min(aMin, boundedInt(values, "loan_tier_b_min", 700, 300, 849));
        int cMin = Math.min(bMin, boundedInt(values, "loan_tier_c_min", 640, 300, 848));
        int dMin = Math.min(cMin, boundedInt(values, "loan_tier_d_min", 580, 300, 847));
        return new LoanPricingPolicy.Config(
                boundedInt(values, "loan_credit_base_score", 650, 300, 850),
                boundedInt(values, "loan_credit_min_score", 520, 300, 850),
                boundedInt(values, "loan_credit_recent_window_days", 30, 1, 365),
                aMin, bMin, cMin, dMin,
                boundedDouble(values, "loan_limit_factor_a", 1.0, 0, 1),
                boundedDouble(values, "loan_limit_factor_b", 0.85, 0, 1),
                boundedDouble(values, "loan_limit_factor_c", 0.65, 0, 1),
                boundedDouble(values, "loan_limit_factor_d", 0.40, 0, 1),
                boundedDouble(values, "loan_limit_factor_e", 0.20, 0, 1),
                boundedInt(values, "loan_max_term_a_days", policy.maxTermDays(), 1, 3650),
                boundedInt(values, "loan_max_term_b_days", policy.maxTermDays(), 1, 3650),
                boundedInt(values, "loan_max_term_c_days", Math.min(60, policy.maxTermDays()), 1, 3650),
                boundedInt(values, "loan_max_term_d_days", Math.min(30, policy.maxTermDays()), 1, 3650),
                boundedInt(values, "loan_max_term_e_days", Math.min(14, policy.maxTermDays()), 1, 3650),
                boundedDouble(values, "loan_risk_spread_a", -0.005, -0.10, 0.50),
                boundedDouble(values, "loan_risk_spread_b", 0, -0.10, 0.50),
                boundedDouble(values, "loan_risk_spread_c", 0.01, -0.10, 0.50),
                boundedDouble(values, "loan_risk_spread_d", 0.025, -0.10, 0.50),
                boundedDouble(values, "loan_risk_spread_e", 0.05, -0.10, 0.50),
                boundedDouble(values, "loan_term_spread_per_30_days", 0.005, 0, 0.50),
                boundedDouble(values, "loan_term_spread_cap", 0.02, 0, 0.50),
                boundedDouble(values, "loan_effective_rate_min", 0, 0, 1),
                boundedDouble(values, "loan_effective_rate_max", 0.25, 0, 1),
                boundedInt(values, "loan_quote_valid_seconds", 120, 30, 3600));
    }

    private LoanPricingPolicy.Stats loadCreditStats(Connection conn, UUID borrowerUuid,
                                                     LoanPricingPolicy.Config config) throws SQLException {
        int paid = 0;
        int active = 0;
        int overdue = 0;
        int everOverdue = 0;
        double outstanding = 0;
        long oldest = 0;
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT
                  COALESCE(SUM(CASE WHEN status='PAID' THEN 1 ELSE 0 END),0) AS paid_count,
                  COALESCE(SUM(CASE WHEN status IN ('ACTIVE','OVERDUE','PENDING_PAYOUT','PAYOUT_SETTLING','RECONCILE_REQUIRED') THEN 1 ELSE 0 END),0) AS active_count,
                  COALESCE(SUM(CASE WHEN status='OVERDUE' THEN 1 ELSE 0 END),0) AS overdue_count,
                  COALESCE(SUM(CASE WHEN ever_overdue=1 OR status='OVERDUE' THEN 1 ELSE 0 END),0) AS ever_overdue_count,
                  COALESCE(SUM(CASE WHEN status IN ('ACTIVE','OVERDUE','PENDING_PAYOUT','PAYOUT_SETTLING','RECONCILE_REQUIRED')
                    THEN CASE WHEN remaining < principal THEN remaining ELSE principal END ELSE 0 END),0) AS outstanding,
                  COALESCE(MIN(issued_at),0) AS oldest_at
                FROM ks_bank_loans WHERE borrower_uuid=?
                """)) {
            ps.setString(1, borrowerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    paid = rs.getInt("paid_count");
                    active = rs.getInt("active_count");
                    overdue = rs.getInt("overdue_count");
                    everOverdue = rs.getInt("ever_overdue_count");
                    outstanding = rs.getDouble("outstanding");
                    oldest = rs.getLong("oldest_at");
                }
            }
        }
        long now = System.currentTimeMillis() / 1000;
        int recentRequests = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM ks_bank_loan_requests WHERE borrower_uuid=? AND requested_at>=?")) {
            ps.setString(1, borrowerUuid.toString());
            ps.setLong(2, now - config.recentWindowDays() * 86_400L);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) recentRequests = rs.getInt(1); }
        }
        return new LoanPricingPolicy.Stats(paid, active, overdue, everOverdue,
                recentRequests, outstanding, oldest, now);
    }

    private Map<String, Object> profileMap(LoanPricingPolicy.Profile profile,
                                           LoanPricingPolicy.Config config, LoanPolicy policy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("eligible", profile.eligible(config));
        result.put("score", profile.score());
        result.put("tier", profile.tier().name());
        result.put("tierLabel", profile.tier().label());
        result.put("maxPrincipal", profile.maxPrincipal());
        result.put("availableCredit", profile.availableCredit());
        result.put("maxTermDays", profile.maxTermDays());
        result.put("minPrincipal", policy.minPrincipal());
        result.put("minTermDays", policy.minTermDays());
        result.put("paidLoans", profile.paidLoans());
        result.put("activeLoans", profile.activeLoans());
        result.put("overdueLoans", profile.overdueLoans());
        result.put("recentRequests", profile.recentRequests());
        result.put("outstandingPrincipal", profile.outstandingPrincipal());
        result.put("factors", profile.factors().stream().map(factor -> Map.of(
                "code", factor.code(), "label", factorLabel(factor.code()), "points", factor.points())).toList());
        result.put("nextSteps", profile.nextSteps().stream().map(this::nextStepLabel).toList());
        return Map.copyOf(result);
    }

    private Map<String, Object> quoteMap(LoanAssessment assessment, double principal, int termDays) {
        LoanPricingPolicy.Quote quote = assessment.quote();
        Map<String, Object> result = new LinkedHashMap<>(profileMap(
                quote.profile(), assessment.pricingConfig(), assessment.policy()));
        result.put("allowed", assessment.allowed());
        result.put("reason", assessment.allowed() ? "报价可用" : assessment.reason());
        result.put("principal", principal);
        result.put("termDays", termDays);
        result.put("baseRate", quote.baseRate());
        result.put("riskSpread", quote.riskSpread());
        result.put("termSpread", quote.termSpread());
        result.put("effectiveRate", quote.effectiveRate());
        result.put("totalDue", quote.totalDue());
        result.put("dueAt", quote.dueAt());
        result.put("liquidityAvailable", assessment.liquidityAvailable());
        result.put("quoteVersion", 1);
        result.put("quoteLocked", false);
        result.put("validUntil", System.currentTimeMillis() / 1000 + assessment.pricingConfig().quoteValidSeconds());
        return Map.copyOf(result);
    }

    private String factorLabel(String code) {
        return switch (code) {
            case "PAID_HISTORY" -> "按期结清记录";
            case "HISTORY_LENGTH" -> "信用历史时长";
            case "ACTIVE_LOANS" -> "当前在贷笔数";
            case "CURRENT_OVERDUE" -> "当前逾期";
            case "PAST_OVERDUE" -> "历史逾期";
            case "RECENT_APPLICATIONS" -> "近期申请较多";
            case "CREDIT_UTILIZATION" -> "额度使用率";
            default -> code;
        };
    }

    private String nextStepLabel(String code) {
        return switch (code) {
            case "CLEAR_OVERDUE" -> "先还清逾期贷款";
            case "REDUCE_BALANCE" -> "降低当前未还本金";
            case "PAUSE_APPLICATIONS" -> "减少短期重复申请";
            case "BUILD_REPAYMENT_HISTORY" -> "通过按期结清积累记录";
            default -> "保持按期还款";
        };
    }

    private static int boundedInt(Map<String, Double> values, String key, int fallback, int min, int max) {
        double raw = values.getOrDefault(key, (double) fallback);
        if (!Double.isFinite(raw)) return fallback;
        return Math.max(min, Math.min(max, (int) Math.round(raw)));
    }

    private static double boundedDouble(Map<String, Double> values, String key, double fallback,
                                        double min, double max) {
        double raw = values.getOrDefault(key, fallback);
        if (!Double.isFinite(raw)) return fallback;
        return Math.max(min, Math.min(max, raw));
    }

    private LoanPolicy loadLoanPolicy(Connection conn) throws SQLException {
        Map<String, Double> values = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT config_key,config_value FROM ks_bank_cb_config WHERE config_key IN (" +
                "'loan_min_principal','loan_max_principal','loan_min_term_days','loan_max_term_days'," +
                "'loan_max_active_per_borrower','loan_max_pending_per_bank','loan_block_overdue')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try { values.put(rs.getString(1), Double.parseDouble(rs.getString(2))); }
                catch (NumberFormatException ignored) {}
            }
        }
        double minPrincipal = Math.max(0.01, values.getOrDefault("loan_min_principal", 100.0));
        double maxPrincipal = Math.max(minPrincipal, values.getOrDefault("loan_max_principal", 50000.0));
        int minTerm = Math.max(1, (int) Math.round(values.getOrDefault("loan_min_term_days", 1.0)));
        int maxTerm = Math.max(minTerm, Math.min(3650,
                (int) Math.round(values.getOrDefault("loan_max_term_days", 90.0))));
        int maxActive = Math.max(1, Math.min(100,
                (int) Math.round(values.getOrDefault("loan_max_active_per_borrower", 3.0))));
        int maxPending = Math.max(1, Math.min(20,
                (int) Math.round(values.getOrDefault("loan_max_pending_per_bank", 1.0))));
        boolean blockOverdue = values.getOrDefault("loan_block_overdue", 1.0) >= 0.5;
        return new LoanPolicy(minPrincipal, maxPrincipal, minTerm, maxTerm, maxActive, maxPending, blockOverdue);
    }

    private boolean hasOverdueLoan(Connection conn, UUID borrowerUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM ks_bank_loans WHERE borrower_uuid=? AND status='OVERDUE' LIMIT 1")) {
            ps.setString(1, borrowerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private boolean restoreWithdrawal(String bankId, UUID playerUuid, double amount) {
        long amountMinor;
        try {
            amountMinor = BankInterestSettlementStore.toMinor(amount);
        } catch (IllegalArgumentException | ArithmeticException error) {
            return false;
        }
        if (amountMinor <= 0) return false;
        double settledAmount = BankInterestSettlementStore.fromMinor(amountMinor);
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                BankInterestSettlementStore.SettlementResult settlement = mutateAccountBalance(
                        conn, bankId, bankId + ":" + playerUuid, playerUuid,
                        System.currentTimeMillis() / 1000, amountMinor, false);
                if (!settlement.applied()) {
                    conn.rollback();
                    return false;
                }
                try (PreparedStatement bank = conn.prepareStatement(
                        "UPDATE ks_bank_banks SET total_assets=total_assets+? "
                                + "WHERE id=? AND status='ACTIVE'")) {
                    bank.setDouble(1, settledAmount);
                    bank.setString(2, bankId);
                    if (bank.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
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
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM ks_bank_loans WHERE id=? AND bank_id=? AND status IN ('ACTIVE','PENDING_PAYOUT','PAYOUT_SETTLING')");
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

    private boolean cancelGuidanceReservation(UUID playerUuid, GuidanceReservation reservation) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try (PreparedStatement claim = conn.prepareStatement(
                    "DELETE FROM ks_bank_guidance_claims WHERE player_uuid=? AND loan_id=?");
                 PreparedStatement loan = conn.prepareStatement(
                    "DELETE FROM ks_bank_loans WHERE id=? AND bank_id=? AND borrower_uuid=? AND status IN ('ACTIVE','PENDING_PAYOUT','PAYOUT_SETTLING')");
                 PreparedStatement bank = conn.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'")) {
                claim.setString(1, playerUuid.toString());
                claim.setString(2, reservation.loanId());
                loan.setString(1, reservation.loanId());
                loan.setString(2, GUIDANCE_BANK_ID);
                loan.setString(3, playerUuid.toString());
                bank.setDouble(1, reservation.amount());
                bank.setString(2, GUIDANCE_BANK_ID);
                if (claim.executeUpdate() != 1 || loan.executeUpdate() != 1 || bank.executeUpdate() != 1) {
                    conn.rollback();
                    return false;
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                return false;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean cancelApprovedLoan(String requestId, UUID borrowerUuid, LoanIssue issue) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try (PreparedStatement loan = conn.prepareStatement(
                    "DELETE FROM ks_bank_loans WHERE id=? AND bank_id=? AND borrower_uuid=? AND status IN ('ACTIVE','PENDING_PAYOUT','PAYOUT_SETTLING')");
                 PreparedStatement bank = conn.prepareStatement(
                    "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'");
                 PreparedStatement request = conn.prepareStatement(
                    "UPDATE ks_bank_loan_requests SET status='PENDING',decided_at=NULL,loan_id=NULL " +
                    "WHERE id=? AND status='APPROVED' AND loan_id=?")) {
                loan.setString(1, issue.loanId());
                loan.setString(2, issue.bankId());
                loan.setString(3, borrowerUuid.toString());
                bank.setDouble(1, issue.principal());
                bank.setString(2, issue.bankId());
                request.setString(1, requestId);
                request.setString(2, issue.loanId());
                if (loan.executeUpdate() != 1 || bank.executeUpdate() != 1 || request.executeUpdate() != 1) {
                    conn.rollback();
                    return false;
                }
                if (!PlayerLoanCollateralStore.returnToRequest(conn, requestId, issue.loanId())) {
                    // Unsecured products have no collateral row; secured products must never lose their lock.
                    try (PreparedStatement product = conn.prepareStatement(
                            "SELECT product_type FROM ks_bank_loan_requests WHERE id=?")) {
                        product.setString(1, requestId);
                        try (ResultSet row = product.executeQuery()) {
                            if (row.next() && productRequiresCollateral(row.getString(1))) {
                                conn.rollback();
                                return false;
                            }
                        }
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                return false;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean beginLoanPayout(String loanId) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_loans SET status=? WHERE id=? AND status=?")) {
                ps.setString(1, LoanPayoutState.SETTLING);
                ps.setString(2, loanId);
                ps.setString(3, LoanPayoutState.PENDING);
                return ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[Bank] Unable to begin loan payout: " + e.getMessage());
            return false;
        }
    }

    private boolean activateLoan(String loanId) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_loans SET status=? WHERE id=? AND status=?")) {
                ps.setString(1, LoanPayoutState.ACTIVE);
                ps.setString(2, loanId);
                ps.setString(3, LoanPayoutState.SETTLING);
                return ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 激活贷款失败: " + e.getMessage());
            return false;
        }
    }

    private void recoverInterruptedLoanDisbursements() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_loans SET status=? WHERE status IN (?,?)")) {
                ps.setString(1, LoanPayoutState.RECONCILE_REQUIRED);
                ps.setString(2, LoanPayoutState.PENDING);
                ps.setString(3, LoanPayoutState.SETTLING);
                int changed = ps.executeUpdate();
                if (changed > 0) {
                    eco.getLogger().severe("[Bank] " + changed + " interrupted loan payout(s) require reconciliation; " +
                            "loan rows and bank liquidity were preserved to avoid an unsafe automatic refund.");
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 恢复未完成放款失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listLoanPayoutReviews() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return out;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT l.*,r.id AS request_id,r.borrower_name FROM ks_bank_loans l
                    LEFT JOIN ks_bank_loan_requests r ON r.loan_id=l.id
                    WHERE l.status='RECONCILE_REQUIRED' ORDER BY l.issued_at
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    ResultSetMetaData meta = rows.getMetaData();
                    while (rows.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i), rows.getObject(i));
                        out.add(row);
                    }
                }
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[Bank] Loan payout review query failed: " + failure.getMessage());
        }
        return List.copyOf(out);
    }

    public Map<String, Object> resolveLoanPayoutReview(String loanId, String requestedAction) {
        String action = requestedAction == null ? "" : requestedAction.trim().toUpperCase(Locale.ROOT);
        if (loanId == null || loanId.isBlank()
                || !("CONFIRM_PAYOUT_SUCCEEDED".equals(action) || "CONFIRM_PAYOUT_FAILED".equals(action))) {
            return Map.of("success", false, "error", "放款复核参数无效");
        }
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) return Map.of("success", false, "error", "数据库不可用");
            connection.setAutoCommit(false);
            try {
                String bankId;
                String borrower;
                double principal;
                String requestId = null;
                String productType = "STANDARD";
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT l.bank_id,l.borrower_uuid,l.principal,l.product_type,r.id AS request_id
                        FROM ks_bank_loans l LEFT JOIN ks_bank_loan_requests r ON r.loan_id=l.id
                        WHERE l.id=? AND l.status='RECONCILE_REQUIRED'
                        """)) {
                    statement.setString(1, loanId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) { connection.rollback(); return Map.of("success", false, "error", "放款记录不在待复核状态"); }
                        bankId = row.getString(1); borrower = row.getString(2); principal = row.getDouble(3);
                        productType = row.getString(4); requestId = row.getString(5);
                    }
                }
                if ("CONFIRM_PAYOUT_SUCCEEDED".equals(action)) {
                    try (PreparedStatement activate = connection.prepareStatement(
                            "UPDATE ks_bank_loans SET status='ACTIVE' WHERE id=? AND status='RECONCILE_REQUIRED'")) {
                        activate.setString(1, loanId);
                        if (activate.executeUpdate() != 1) throw new SQLException("loan review changed");
                    }
                } else {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM ks_bank_loans WHERE id=? AND status='RECONCILE_REQUIRED'")) {
                        delete.setString(1, loanId);
                        if (delete.executeUpdate() != 1) throw new SQLException("loan review changed");
                    }
                    try (PreparedStatement restore = connection.prepareStatement(
                            "UPDATE ks_bank_banks SET total_assets=total_assets+? WHERE id=? AND status='ACTIVE'")) {
                        restore.setDouble(1, principal); restore.setString(2, bankId);
                        if (restore.executeUpdate() != 1) throw new SQLException("bank liquidity restore failed");
                    }
                    if (requestId != null) {
                        try (PreparedStatement request = connection.prepareStatement("""
                                UPDATE ks_bank_loan_requests SET status='PENDING',decided_at=NULL,loan_id=NULL
                                WHERE id=? AND loan_id=? AND status='APPROVED'
                                """)) {
                            request.setString(1, requestId); request.setString(2, loanId);
                            if (request.executeUpdate() != 1) throw new SQLException("loan request review changed");
                        }
                        boolean returned = PlayerLoanCollateralStore.returnToRequest(connection, requestId, loanId);
                        if (productRequiresCollateral(productType) && !returned) {
                            throw new SQLException("secured loan collateral review changed");
                        }
                    } else {
                        PlayerLoanCollateralStore.releaseLoan(connection, loanId, System.currentTimeMillis() / 1000);
                    }
                }
                connection.commit();
                return Map.of("success", true, "message", "放款复核已完成", "loanId", loanId,
                        "status", "CONFIRM_PAYOUT_SUCCEEDED".equals(action) ? "ACTIVE" : "COMPENSATED",
                        "borrowerUuid", borrower);
            } catch (SQLException failure) {
                connection.rollback();
                return Map.of("success", false, "error", "放款复核失败，账务已回滚");
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) {
            return Map.of("success", false, "error", "放款复核失败");
        }
    }
    private boolean debitWallet(UUID playerUuid, double amount) {
        return onServerThread(() -> {
            var player = org.bukkit.Bukkit.getOfflinePlayer(playerUuid);
            return eco.vaultHook().has(player, amount) && eco.vaultHook().withdraw(player, amount);
        }, false);
    }

    private boolean creditWallet(UUID playerUuid, double amount) {
        return onServerThread(() -> eco.vaultHook().deposit(org.bukkit.Bukkit.getOfflinePlayer(playerUuid), amount), false);
    }

    private void refundWallet(UUID playerUuid, double amount, String context) {
        if (!creditWallet(playerUuid, amount)) {
            eco.getLogger().severe("[Bank] Wallet refund failed for " + playerUuid + " amount=" + amount
                    + " context=" + context);
        }
    }

    private Map<UUID, String> snapshotPlayerNames(List<UUID> playerUuids) {
        return onServerThread(() -> {
            Map<UUID, String> names = new LinkedHashMap<>();
            for (UUID uuid : playerUuids) {
                String name = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
                names.put(uuid, name == null ? uuid.toString() : name);
            }
            return Map.copyOf(names);
        }, Map.of());
    }

    private void notifyLoanRejected(UUID playerUuid, String reason) {
        notifyPlayer(playerUuid, "§c贷款申请未通过：" + reason);
    }

    private void notifyLoanIssued(UUID playerUuid, LoanIssue issue) {
        notifyPlayer(playerUuid, String.format(Locale.ROOT,
                "§a贷款已到账：%.2f，固定利率 %.2f%%，应还 %.2f，期限 %d 天。",
                issue.principal(), issue.rate() * 100, issue.totalDue(), issue.termDays()));
    }

    private void notifyPlayer(UUID playerUuid, String message) {
        Runnable task = () -> {
            var player = org.bukkit.Bukkit.getPlayer(playerUuid);
            if (player != null) player.sendMessage(message);
        };
        if (org.bukkit.Bukkit.isPrimaryThread()) task.run();
        else {
            try { org.bukkit.Bukkit.getScheduler().runTask(eco, task); }
            catch (RuntimeException ignored) {}
        }
    }

    private <T> T onServerThread(Supplier<T> operation, T fallback) {
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            try { return operation.get(); }
            catch (RuntimeException e) { return fallback; }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            org.bukkit.Bukkit.getScheduler().runTask(eco, () -> {
                try { future.complete(operation.get()); }
                catch (Throwable error) { future.completeExceptionally(error); }
            });
        } catch (RuntimeException e) {
            eco.getLogger().warning("[Bank] Server-thread economy call failed: " + e.getMessage());
            return fallback;
        }
        boolean interrupted = false;
        try {
            while (true) {
                try { return future.get(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } catch (ExecutionException e) {
            eco.getLogger().warning("[Bank] Server-thread economy call failed: " + e.getCause());
            return fallback;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * 还款。
     */
    public boolean repayLoan(String loanId, UUID borrowerUuid, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) return false;
        var player = org.bukkit.Bukkit.getOfflinePlayer(borrowerUuid);
        LoanRepaymentSettlementStore.Settlement settlement;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            settlement = LoanRepaymentSettlementStore.prepare(
                    conn, loanId, borrowerUuid, amount, System.currentTimeMillis() / 1000);
            if (settlement == null) return false;
            if (!onServerThread(() -> eco.vaultHook().has(player, settlement.amount()), false)) {
                LoanRepaymentSettlementStore.cancelReady(conn, settlement,
                        "wallet balance check rejected repayment", System.currentTimeMillis() / 1000);
                return false;
            }
            if (!LoanRepaymentSettlementStore.claimCharge(
                    conn, settlement.id(), System.currentTimeMillis() / 1000)) return false;
        } catch (SQLException e) {
            eco.getLogger().warning("[Bank] Loan repayment preparation failed: " + e.getMessage());
            return false;
        }

        boolean charged = onServerThread(() -> eco.vaultHook().withdraw(player, settlement.amount()), false);
        if (!charged) {
            markLoanRepaymentReview(settlement.id(), LoanRepaymentSettlementStore.CHARGE_CLAIMED,
                    "Vault withdrawal was rejected or its outcome could not be confirmed");
            return false;
        }
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            if (!LoanRepaymentSettlementStore.markCharged(
                    conn, settlement.id(), System.currentTimeMillis() / 1000)) return false;
        } catch (SQLException e) {
            eco.getLogger().severe("[Bank] Charged loan repayment could not be persisted: "
                    + settlement.id() + " " + e.getMessage());
            return false;
        }

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null && LoanRepaymentSettlementStore.finalizeCharged(
                    conn, settlement, System.currentTimeMillis() / 1000)) return true;
            if (conn != null) LoanRepaymentSettlementStore.queueRefund(conn, settlement.id(),
                    "loan snapshot changed before repayment finalization", System.currentTimeMillis() / 1000);
        } catch (SQLException e) {
            eco.getLogger().warning("[Bank] Charged loan repayment awaits database retry: "
                    + settlement.id() + " " + e.getMessage());
            return false;
        }
        refundLoanRepayment(settlement.id());
        return false;
    }

    private void recoverLoanRepaymentSettlements() {
        try {
            eco.asyncWorkPool().executeDatabase(() -> {
                List<LoanRepaymentSettlementStore.Settlement> ready;
                List<LoanRepaymentSettlementStore.Settlement> charged;
                List<LoanRepaymentSettlementStore.Settlement> refunds;
                int unknown;
                try (Connection conn = eco.ksCore().dataStore().getConnection()) {
                    if (conn == null) return;
                    unknown = LoanRepaymentSettlementStore.recoverUnknownExternalCalls(
                            conn, System.currentTimeMillis() / 1000);
                    ready = LoanRepaymentSettlementStore.list(conn, LoanRepaymentSettlementStore.CHARGE_READY);
                    charged = LoanRepaymentSettlementStore.list(conn, LoanRepaymentSettlementStore.CHARGED);
                    refunds = LoanRepaymentSettlementStore.list(conn, LoanRepaymentSettlementStore.REFUND_READY);
                } catch (SQLException e) {
                    eco.getLogger().warning("[Bank] Loan repayment recovery scan failed: " + e.getMessage());
                    return;
                }
                if (unknown > 0) {
                    eco.getLogger().severe("[Bank] " + unknown
                            + " loan repayment wallet outcome(s) require manual reconciliation");
                }
                for (LoanRepaymentSettlementStore.Settlement settlement : ready) {
                    try (Connection conn = eco.ksCore().dataStore().getConnection()) {
                        if (conn != null) LoanRepaymentSettlementStore.cancelReady(conn, settlement,
                                "cancelled before external wallet call during startup recovery",
                                System.currentTimeMillis() / 1000);
                    } catch (SQLException e) {
                        eco.getLogger().warning("[Bank] Ready repayment recovery failed: " + settlement.id());
                    }
                }
                for (LoanRepaymentSettlementStore.Settlement settlement : charged) {
                    try (Connection conn = eco.ksCore().dataStore().getConnection()) {
                        if (conn == null) continue;
                        if (!LoanRepaymentSettlementStore.finalizeCharged(
                                conn, settlement, System.currentTimeMillis() / 1000)) {
                            LoanRepaymentSettlementStore.queueRefund(conn, settlement.id(),
                                    "loan snapshot changed during startup recovery",
                                    System.currentTimeMillis() / 1000);
                            refunds = new ArrayList<>(refunds);
                            refunds.add(settlement);
                        }
                    } catch (SQLException e) {
                        eco.getLogger().warning("[Bank] Charged repayment recovery failed: " + settlement.id());
                    }
                }
                for (LoanRepaymentSettlementStore.Settlement settlement : refunds) {
                    refundLoanRepayment(settlement.id());
                }
            });
        } catch (RuntimeException rejected) {
            eco.getLogger().warning("[Bank] Loan repayment recovery queue rejected: " + rejected.getMessage());
        }
    }

    private void refundLoanRepayment(String settlementId) {
        LoanRepaymentSettlementStore.Settlement settlement;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null || !LoanRepaymentSettlementStore.claimRefund(
                    conn, settlementId, System.currentTimeMillis() / 1000)) return;
            settlement = LoanRepaymentSettlementStore.find(conn, settlementId);
        } catch (SQLException e) {
            eco.getLogger().warning("[Bank] Loan repayment refund claim failed: " + settlementId);
            return;
        }
        boolean refunded = creditWallet(settlement.borrowerUuid(), settlement.amount());
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) LoanRepaymentSettlementStore.finishRefund(conn, settlement.id(), refunded,
                    refunded ? "" : "Vault refund was rejected or its outcome could not be confirmed",
                    System.currentTimeMillis() / 1000);
        } catch (SQLException e) {
            eco.getLogger().severe("[Bank] Loan repayment refund outcome could not be persisted: "
                    + settlement.id());
        }
    }

    private void markLoanRepaymentReview(String settlementId, String expectedStatus, String error) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) LoanRepaymentSettlementStore.markReview(conn, settlementId, expectedStatus,
                    expectedStatus, error, System.currentTimeMillis() / 1000);
        } catch (SQLException e) {
            eco.getLogger().severe("[Bank] Loan repayment review state could not be persisted: " + settlementId);
        }
    }

    public List<BankAccessProvider.SettlementReview> listLoanRepaymentReviews() throws SQLException {
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) throw new SQLException("database unavailable");
            LoanRepaymentSettlementStore.initialize(connection);
            List<BankAccessProvider.SettlementReview> reviews = new ArrayList<>();
            for (LoanRepaymentSettlementStore.Settlement settlement
                    : LoanRepaymentSettlementStore.list(connection,
                    LoanRepaymentSettlementStore.REVIEW_REQUIRED)) {
                reviews.add(new BankAccessProvider.SettlementReview(
                        settlement.id(), settlement.loanId(), settlement.borrowerUuid(),
                        settlement.bankId(), settlement.amount(), settlement.expectedRemaining(),
                        settlement.reviewStage(), settlement.lastError()));
            }
            return List.copyOf(reviews);
        }
    }

    public BankAccessProvider.SettlementResolution resolveLoanRepaymentReview(String settlementId,
                                                                               String requestedAction)
            throws SQLException {
        if (settlementId == null || settlementId.isBlank()) throw new SQLException("settlement id is required");
        String action = requestedAction == null ? "" : requestedAction.trim().toUpperCase(Locale.ROOT);
        LoanRepaymentSettlementStore.Settlement settlement;
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) throw new SQLException("database unavailable");
            LoanRepaymentSettlementStore.initialize(connection);
            settlement = LoanRepaymentSettlementStore.find(connection, settlementId);
            if (settlement == null || !LoanRepaymentSettlementStore.REVIEW_REQUIRED.equals(settlement.status())) {
                throw new SQLException("settlement is not awaiting review");
            }
            long now = System.currentTimeMillis() / 1000;
            switch (action) {
                case "CONFIRM_CHARGE_SUCCEEDED" -> {
                    requireLoanReviewStage(settlement, LoanRepaymentSettlementStore.CHARGE_CLAIMED);
                    requireLoanReviewResolution(LoanRepaymentSettlementStore.resolveReview(connection,
                            settlement.id(), settlement.reviewStage(), LoanRepaymentSettlementStore.CHARGED,
                            "administrator confirmed charge", now));
                }
                case "CONFIRM_CHARGE_FAILED" -> {
                    requireLoanReviewStage(settlement, LoanRepaymentSettlementStore.CHARGE_CLAIMED);
                    requireLoanReviewResolution(LoanRepaymentSettlementStore.confirmCompensatedReview(connection,
                            settlement, settlement.reviewStage(), "administrator confirmed charge failed", now));
                    return loanReviewResolution(connection, settlement.id(), "charge rejected and marker released");
                }
                case "CONFIRM_REFUND_SUCCEEDED" -> {
                    requireLoanReviewStage(settlement, LoanRepaymentSettlementStore.REFUND_CLAIMED);
                    requireLoanReviewResolution(LoanRepaymentSettlementStore.confirmCompensatedReview(connection,
                            settlement, settlement.reviewStage(), "administrator confirmed refund", now));
                    return loanReviewResolution(connection, settlement.id(), "refund confirmed and marker released");
                }
                case "CONFIRM_REFUND_FAILED" -> {
                    requireLoanReviewStage(settlement, LoanRepaymentSettlementStore.REFUND_CLAIMED);
                    requireLoanReviewResolution(LoanRepaymentSettlementStore.resolveReview(connection,
                            settlement.id(), settlement.reviewStage(), LoanRepaymentSettlementStore.REFUND_READY,
                            "administrator confirmed refund failed; queued for retry", now));
                }
                default -> throw new SQLException("unsupported bank repayment review action");
            }
        }

        if ("CONFIRM_CHARGE_SUCCEEDED".equals(action)) {
            boolean finalized;
            try (Connection connection = eco.ksCore().dataStore().getConnection()) {
                if (connection == null) throw new SQLException("database unavailable");
                finalized = LoanRepaymentSettlementStore.finalizeCharged(
                        connection, settlement, System.currentTimeMillis() / 1000);
                if (!finalized) {
                    requireLoanReviewResolution(LoanRepaymentSettlementStore.queueRefund(connection,
                            settlement.id(), "loan snapshot changed after administrator confirmed charge",
                            System.currentTimeMillis() / 1000));
                }
            }
            if (!finalized) refundLoanRepayment(settlement.id());
        } else {
            refundLoanRepayment(settlement.id());
        }
        try (Connection connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) throw new SQLException("database unavailable");
            return loanReviewResolution(connection, settlement.id(),
                    "administrator resolution applied");
        }
    }

    private static void requireLoanReviewStage(LoanRepaymentSettlementStore.Settlement settlement,
                                               String expected) throws SQLException {
        if (!expected.equals(settlement.reviewStage())) {
            throw new SQLException("review stage does not allow this action");
        }
    }

    private static void requireLoanReviewResolution(boolean resolved) throws SQLException {
        if (!resolved) throw new SQLException("settlement review changed concurrently");
    }

    private static BankAccessProvider.SettlementResolution loanReviewResolution(
            Connection connection, String id, String message) throws SQLException {
        LoanRepaymentSettlementStore.Settlement current = LoanRepaymentSettlementStore.find(connection, id);
        if (current == null) throw new SQLException("settlement disappeared");
        return new BankAccessProvider.SettlementResolution(
                current.status(), current.reviewStage(), message);
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
                    "UPDATE ks_bank_loans SET remaining=?, status=?, " +
                    "paid_at=CASE WHEN ?='PAID' THEN ? ELSE paid_at END " +
                    "WHERE id=? AND borrower_uuid=? AND remaining>=? AND status IN ('ACTIVE','OVERDUE')")) {
                ps.setDouble(1, newRemaining);
                ps.setString(2, status);
                ps.setString(3, status);
                ps.setLong(4, System.currentTimeMillis() / 1000);
                ps.setString(5, loanId);
                ps.setString(6, borrowerUuid.toString());
                ps.setDouble(7, pay);
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
        return requestLoanInternal(bankId, borrowerUuid, borrowerName, principal, termDays, null, 0,
                "STANDARD", "BULLET", "", "", "");
    }

    public String requestLoanWithQuote(String bankId, UUID borrowerUuid, String borrowerName,
                                       double principal, int termDays, double acceptedRate, long validUntil) {
        if (!Double.isFinite(acceptedRate) || acceptedRate < 0 || acceptedRate > 1) return null;
        return requestLoanInternal(bankId, borrowerUuid, borrowerName, principal, termDays,
                acceptedRate, validUntil, "STANDARD", "BULLET", "", "", "");
    }

    public String requestProductLoan(String bankId, UUID borrowerUuid, String borrowerName,
                                     double principal, int termDays, double acceptedRate, long validUntil,
                                     String productType, String repaymentType, String purpose) {
        return requestProductLoan(bankId, borrowerUuid, borrowerName, principal, termDays, acceptedRate,
                validUntil, productType, repaymentType, purpose, "", "");
    }

    public String requestProductLoan(String bankId, UUID borrowerUuid, String borrowerName,
                                     double principal, int termDays, double acceptedRate, long validUntil,
                                     String productType, String repaymentType, String purpose,
                                     String collateralType, String collateralRef) {
        if (!Double.isFinite(acceptedRate) || acceptedRate < 0 || acceptedRate > 1) return null;
        String product = normalizeLoanProduct(productType);
        String repayment = normalizeRepaymentType(repaymentType);
        if (product == null || repayment == null || !productTermsAllowed(product, principal, termDays)) return null;
        String safePurpose = purpose == null ? "" : purpose.trim();
        if (safePurpose.length() > 256) safePurpose = safePurpose.substring(0, 256);
        return requestLoanInternal(bankId, borrowerUuid, borrowerName, principal, termDays,
                acceptedRate, validUntil, product, repayment, safePurpose, collateralType, collateralRef);
    }

    private String requestLoanInternal(String bankId, UUID borrowerUuid, String borrowerName,
                                       double principal, int termDays, Double acceptedRate, long validUntil,
                                       String productType, String repaymentType, String purpose,
                                       String collateralType, String collateralRef) {
        if (bankId == null || bankId.isBlank() || borrowerUuid == null) return null;
        String safeName = borrowerName == null ? borrowerUuid.toString() : borrowerName.trim();
        if (safeName.isEmpty()) safeName = borrowerUuid.toString();
        if (safeName.length() > 64) safeName = safeName.substring(0, 64);
        String id = "RQ-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
        double quotedRate;
        String quotedTier;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            conn.setAutoCommit(false);
            try {
                LoanAssessment assessment = assessLoan(conn, bankId, borrowerUuid, principal, termDays, true);
                if (!assessment.allowed()) {
                    conn.rollback();
                    notifyLoanRejected(borrowerUuid, assessment.reason());
                    return null;
                }
                long now = System.currentTimeMillis() / 1000;
                double productRate = Math.max(0, Math.min(1,
                        productEffectiveRate(assessment.rate(), productType) + activePolicyRateModifier(conn)));
                if (acceptedRate != null && (now > validUntil || productRate > acceptedRate + 0.000_000_1)) {
                    conn.rollback();
                    notifyLoanRejected(borrowerUuid, now > validUntil
                            ? "贷款报价已过期，请重新获取报价"
                            : "银行利率或信用状态已变化，请确认新报价");
                    return null;
                }
                quotedRate = productRate;
                LoanPricingPolicy.Quote quote = assessment.quote();
                quotedTier = quote.profile().tier().label();
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_bank_loan_requests (id, bank_id, borrower_uuid, borrower_name, " +
                        "principal, term_days, quoted_rate, quoted_total_due, quote_base_rate, quote_risk_spread, " +
                        "quote_term_spread, quote_version, credit_score, credit_tier, product_type, repayment_type, " +
                        "purpose, quote_expires_at, requested_at) " +
                        "SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM ks_bank_loan_requests " +
                        "WHERE bank_id=? AND borrower_uuid=? AND status IN ('PENDING','PROCESSING')) < ?")) {
                    ps.setString(1, id);
                    ps.setString(2, bankId);
                    ps.setString(3, borrowerUuid.toString());
                    ps.setString(4, safeName);
                    ps.setDouble(5, principal);
                    ps.setInt(6, termDays);
                    ps.setDouble(7, quotedRate);
                    ps.setDouble(8, principal * (1 + quotedRate));
                    ps.setDouble(9, quote.baseRate());
                    ps.setDouble(10, quote.riskSpread());
                    ps.setDouble(11, quote.termSpread());
                    ps.setInt(12, 1);
                    ps.setInt(13, quote.profile().score());
                    ps.setString(14, quote.profile().tier().name());
                    ps.setString(15, productType);
                    ps.setString(16, repaymentType);
                    ps.setString(17, purpose);
                    if (validUntil > 0) ps.setLong(18, validUntil); else ps.setNull(18, Types.BIGINT);
                    ps.setLong(19, now);
                    ps.setString(20, bankId);
                    ps.setString(21, borrowerUuid.toString());
                    ps.setInt(22, assessment.policy().maxPendingPerBank());
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        notifyLoanRejected(borrowerUuid, "你在该银行已有待审批申请，请等待处理后再申请");
                        return null;
                    }
                }
                if (productRequiresCollateral(productType)) {
                    PlayerLoanCollateralStore.Appraisal collateral = PlayerLoanCollateralStore.reserve(
                            conn, id, bankId, borrowerUuid, productType, collateralType, collateralRef,
                            principal, now);
                    if (collateral == null) {
                        conn.rollback();
                        notifyLoanRejected(borrowerUuid, "抵押物无效、已被占用，或贷款金额超过可贷额度");
                        return null;
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 提交贷款申请失败: " + e.getMessage());
            return null;
        }
        double totalDue = principal * (1 + quotedRate);
        notifyPlayer(borrowerUuid, String.format(Locale.ROOT,
                "§a贷款申请已提交。信用 %s，整期固定费率 %.2f%%，到账 %.2f，应还 %.2f，期限 %d 天。",
                quotedTier, quotedRate * 100, principal, totalDue, termDays));
        return id;
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
        if (requestId == null || requestId.isBlank()) return false;
        UUID borrowerUuid;
        LoanIssue issue;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            String bankId;
            String borrowerUuidText;
            double principal;
            Double quotedRate;
            int termDays;
            String productType;
            String repaymentType;
            String purpose;
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        APPROVAL_REQUEST_SELECT)) {
                    ps.setString(1, requestId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return false;
                        }
                        bankId = rs.getString("bank_id");
                        borrowerUuidText = rs.getString("borrower_uuid");
                        principal = rs.getDouble("principal");
                        termDays = rs.getInt("term_days");
                        double storedRate = rs.getDouble("quoted_rate");
                        quotedRate = rs.wasNull() ? null : storedRate;
                        productType = normalizeLoanProduct(rs.getString("product_type"));
                        if (productType == null) productType = "STANDARD";
                        repaymentType = normalizeRepaymentType(rs.getString("repayment_type"));
                        if (repaymentType == null) repaymentType = "BULLET";
                        purpose = rs.getString("purpose");
                    }
                }
                try {
                    borrowerUuid = UUID.fromString(borrowerUuidText);
                } catch (IllegalArgumentException e) {
                    conn.rollback();
                    return false;
                }
                LoanAssessment assessment = assessLoan(conn, bankId, borrowerUuid, principal, termDays, false);
                if (!assessment.allowed()) {
                    conn.rollback();
                    notifyLoanRejected(borrowerUuid, assessment.reason());
                    return false;
                }
                if (productRequiresCollateral(productType)
                        && !PlayerLoanCollateralStore.requestHasReservedCollateral(conn, requestId)) {
                    conn.rollback();
                    notifyLoanRejected(borrowerUuid, "贷款抵押物已失效，请重新申请");
                    return false;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_bank_loan_requests SET status='PROCESSING' WHERE id=? AND status='PENDING'")) {
                    ps.setString(1, requestId);
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                double rate = quotedRate != null && Double.isFinite(quotedRate) && quotedRate >= 0 && quotedRate <= 1
                        ? quotedRate : assessment.rate();
                issue = insertLoan(conn, bankId, borrowerUuid, principal, termDays, rate,
                        productType, repaymentType, purpose);
                if (issue == null) {
                    conn.rollback();
                    return false;
                }
                if (productRequiresCollateral(productType)
                        && !PlayerLoanCollateralStore.attachToLoan(conn, requestId, issue.loanId(),
                        System.currentTimeMillis() / 1000)) {
                    conn.rollback();
                    return false;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_bank_loan_requests SET status='APPROVED', decided_at=?, loan_id=? " +
                        "WHERE id=? AND status='PROCESSING'")) {
                    ps.setLong(1, System.currentTimeMillis() / 1000);
                    ps.setString(2, issue.loanId());
                    ps.setString(3, requestId);
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 批准贷款申请失败: " + e.getMessage());
            return false;
        }

        if (!beginLoanPayout(issue.loanId())) {
            cancelApprovedLoan(requestId, borrowerUuid, issue);
            return false;
        }
        if (!creditWallet(borrowerUuid, issue.principal())) {
            if (!cancelApprovedLoan(requestId, borrowerUuid, issue)) {
                eco.getLogger().severe("[Bank] Approved-loan payout failed and compensation failed: "
                        + requestId + "/" + issue.loanId());
            }
            return false;
        }
        if (!activateLoan(issue.loanId())) {
            eco.getLogger().severe("[Bank] Approved loan wallet credited but activation failed: "
                    + requestId + "/" + issue.loanId());
            return false;
        }
        notifyLoanIssued(borrowerUuid, issue);
        return true;
    }

    public boolean rejectLoanRequest(String requestId) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                long now = System.currentTimeMillis() / 1000;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_bank_loan_requests SET status='REJECTED', decided_at=? WHERE id=? AND status='PENDING'")) {
                    ps.setLong(1, now);
                    ps.setString(2, requestId);
                    if (ps.executeUpdate() != 1) { conn.rollback(); return false; }
                }
                PlayerLoanCollateralStore.releaseRequest(conn, requestId, now);
                conn.commit();
                return true;
            } catch (SQLException failure) {
                conn.rollback();
                throw failure;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 拒绝贷款申请失败: " + e.getMessage());
            return false;
        }
    }

    public boolean cancelLoanRequest(String requestId, UUID borrowerUuid) {
        if (requestId == null || requestId.isBlank() || borrowerUuid == null) return false;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                long timestamp = System.currentTimeMillis() / 1000;
                try (PreparedStatement request = conn.prepareStatement("""
                        UPDATE ks_bank_loan_requests SET status='CANCELLED',decided_at=?
                        WHERE id=? AND borrower_uuid=? AND status='PENDING'
                        """)) {
                    request.setLong(1, timestamp); request.setString(2, requestId);
                    request.setString(3, borrowerUuid.toString());
                    if (request.executeUpdate() != 1) { conn.rollback(); return false; }
                }
                PlayerLoanCollateralStore.releaseRequest(conn, requestId, timestamp);
                conn.commit();
                return true;
            } catch (SQLException failure) {
                conn.rollback(); throw failure;
            } finally { conn.setAutoCommit(true); }
        } catch (SQLException failure) {
            eco.getLogger().warning("[银行] 撤销贷款申请失败: " + failure.getMessage());
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

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;

            // 1) 逾期标记
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_bank_loans SET status='OVERDUE',ever_overdue=1 " +
                    "WHERE status='ACTIVE' AND COALESCE(grace_until,due_at) < ?")) {
                ps.setLong(1, now);
                int n = ps.executeUpdate();
                if (n > 0) eco.getLogger().info("[银行] " + n + " 笔贷款已标记为逾期 (OVERDUE)");
            }
            expireStaleLoanRequests(conn, now);
            conn.setAutoCommit(false);
            try {
                PlayerLoanCollateralStore.maintainDefaults(conn, now);
                conn.commit();
            } catch (SQLException failure) {
                conn.rollback();
                throw failure;
            } finally {
                conn.setAutoCommit(true);
            }

            record AccountRef(String accountId, String bankId, UUID playerUuid) {}
            List<AccountRef> accounts = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT a.id,a.bank_id,a.player_uuid FROM ks_bank_accounts a "
                            + "JOIN ks_bank_banks b ON b.id=a.bank_id WHERE b.status='ACTIVE'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            accounts.add(new AccountRef(rs.getString(1), rs.getString(2),
                                    UUID.fromString(rs.getString(3))));
                        } catch (IllegalArgumentException invalidUuid) {
                            eco.getLogger().warning("[Bank] Skipping account with invalid player UUID: "
                                    + rs.getString(1));
                        }
                    }
                }
            }

            int postings = 0;
            long totalInterestMinor = 0;
            for (AccountRef account : accounts) {
                conn.setAutoCommit(false);
                try {
                    BankInterestSettlementStore.SettlementResult settlement = mutateAccountBalance(
                            conn, account.bankId(), account.accountId(), account.playerUuid(), now, 0, false);
                    if (!settlement.applied()) {
                        conn.rollback();
                        continue;
                    }
                    conn.commit();
                    postings += settlement.postingCount();
                    totalInterestMinor = Math.addExact(totalInterestMinor, settlement.interestMinor());
                } catch (SQLException | RuntimeException error) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    eco.getLogger().warning("[Bank] Interest settlement failed for "
                            + account.accountId() + ": " + error.getMessage());
                } finally {
                    try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                }
            }
            if (postings > 0) {
                eco.getLogger().info(String.format(Locale.ROOT,
                        "[Bank] Interest settlement completed: %d postings, %.2f credited",
                        postings, BankInterestSettlementStore.fromMinor(totalInterestMinor)));
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[银行] 利息结算失败: " + e.getMessage());
        }
    }

    private void expireStaleLoanRequests(Connection connection, long current) throws SQLException {
        List<String> stale = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM ks_bank_loan_requests WHERE status='PENDING' AND requested_at<?")) {
            statement.setLong(1, current - 7L * 86400L);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) stale.add(rows.getString(1)); }
        }
        if (stale.isEmpty()) return;
        connection.setAutoCommit(false);
        try {
            for (String requestId : stale) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE ks_bank_loan_requests SET status='EXPIRED',decided_at=? WHERE id=? AND status='PENDING'")) {
                    statement.setLong(1, current); statement.setString(2, requestId);
                    if (statement.executeUpdate() == 1) PlayerLoanCollateralStore.releaseRequest(connection, requestId, current);
                }
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback(); throw failure;
        } finally { connection.setAutoCommit(true); }
    }

    // ---- 数据类 ----

    public record Bank(String id, String name, String type, List<UUID> ownerUuids,
                       double totalAssets, long createdAt) {}

    private record GuidanceReservation(String loanId, double amount, double rate, int termDays, long dueAt) {}

    private record LoanIssue(String loanId, String bankId, double principal, double totalDue,
                             double rate, int termDays, long dueAt) {}

    private record LoanPolicy(double minPrincipal, double maxPrincipal, int minTermDays, int maxTermDays,
                              int maxActiveLoans, int maxPendingPerBank, boolean blockOverdue) {}

    private record LoanAssessment(boolean allowed, String reason, LoanPolicy policy,
                                  LoanPricingPolicy.Config pricingConfig,
                                  LoanPricingPolicy.Quote quote, double liquidityAvailable) {
        double rate() { return quote.effectiveRate(); }
    }
}
