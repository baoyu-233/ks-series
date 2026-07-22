package org.kseco.extra.bank;

import org.kseco.KsEco;

import java.sql.*;

/**
 * 央行宏观调控管理器。
 *
 * 功能：
 * - 基准利率 + 利率上下限（rateMin/rateMax，控制商业银行利率波动区间）
 * - 准备金率
 * - 注入流动性：仅允许 LOAN（有息借贷，自动到期回收）
 */
public final class CentralBankManager {

    private final KsEco eco;
    private double baseRate = 0.035;        // 基准利率 3.5%
    private double reserveRequirement = 0.10; // 准备金率 10%
    private double rateMin = 0.01;          // 商业银行存款利率下限 1%
    private double rateMax = 0.20;          // 商业银行贷款利率上限 20%

    public CentralBankManager(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT base_rate, reserve_requirement FROM ks_bank_cb_rates ORDER BY set_at DESC LIMIT 1")) {
                if (rs.next()) {
                    double storedBaseRate = rs.getDouble("base_rate");
                    double storedReserveRequirement = rs.getDouble("reserve_requirement");
                    if (Double.isFinite(storedBaseRate)) {
                        baseRate = CentralBankPolicy.clamp(storedBaseRate, -1, 1, "baseRate");
                    }
                    if (Double.isFinite(storedReserveRequirement)) {
                        reserveRequirement = CentralBankPolicy.clamp(
                                storedReserveRequirement, 0, 1, "reserveRequirement");
                    }
                }
            }
            // 加载利率上下限（存于 ks_bank_cb_config）
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT config_key, config_value FROM ks_bank_cb_config "
                            + "WHERE config_key IN ('rate_min','rate_max')")) {
                Double storedRateMin = null;
                Double storedRateMax = null;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            double v = Double.parseDouble(rs.getString("config_value"));
                            if (!Double.isFinite(v)) continue;
                            if ("rate_min".equals(rs.getString("config_key"))) storedRateMin = v;
                            else if ("rate_max".equals(rs.getString("config_key"))) storedRateMax = v;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                if (storedRateMin != null) {
                    rateMin = CentralBankPolicy.clamp(storedRateMin, 0, 1, "rateMin");
                }
                if (storedRateMax != null) {
                    rateMax = CentralBankPolicy.clamp(storedRateMax, rateMin, 1, "rateMax");
                } else if (rateMax < rateMin) {
                    rateMax = rateMin;
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[央行] 加载利率失败: " + e.getMessage());
        }
    }

    public void setBaseRate(double rate) {
        this.baseRate = CentralBankPolicy.clamp(rate, -1, 1, "baseRate");
        saveRate();
    }

    public void setReserveRequirement(double ratio) {
        this.reserveRequirement = CentralBankPolicy.clamp(ratio, 0, 1, "reserveRequirement");
        saveRate();
    }

    /** 央行设置商业银行利率上下限（全局） */
    public void setRateRange(double min, double max) {
        this.rateMin = CentralBankPolicy.clamp(min, 0, 1, "rateMin");
        this.rateMax = CentralBankPolicy.clamp(max, rateMin, 1, "rateMax");
        saveConfig("rate_min", String.valueOf(rateMin));
        saveConfig("rate_max", String.valueOf(rateMax));
    }

    public double getBaseRate() { return baseRate; }
    public double getReserveRequirement() { return reserveRequirement; }
    public double getRateMin() { return rateMin; }
    public double getRateMax() { return rateMax; }

    /** 校验银行利率是否在央行区间内 */
    public boolean isLoanRateValid(double rate) { return Double.isFinite(rate) && rate >= rateMin && rate <= rateMax; }
    public boolean isDepositRateValid(double rate) { return Double.isFinite(rate) && rate >= 0 && rate <= rateMax; }

    /**
     * 央行向商业银行注入流动性。
     * @param mode LOAN=有息借贷（写入 ks_bank_cb_loans，到期由 CbLoanManager 回收）
     */
    public String injectLiquidity(String bankId, double amount, String mode) {
        if (!CentralBankPolicy.validPositiveAmount(amount)) return "金额必须 > 0";
        mode = mode == null ? "LOAN" : mode.toUpperCase();
        if (!"LOAN".equals(mode)) {
            return "央行流动性支持仅允许 LOAN 有息拆借，不提供无偿注资";
        }
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "数据库未就绪";
            // 验证目标
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, type FROM ks_bank_banks WHERE id=? AND type!='CENTRAL' AND status='ACTIVE'")) {
                ps.setString(1, bankId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return "目标银行不存在或是央行自身";
                }
            }
            conn.setAutoCommit(false);
            try {
                // 增加银行总资产
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_bank_banks SET total_assets=total_assets+? "
                                + "WHERE id=? AND type!='CENTRAL' AND status='ACTIVE'")) {
                    ps.setDouble(1, amount);
                    ps.setString(2, bankId);
                    if (ps.executeUpdate() != 1) throw new SQLException("Target bank is no longer active");
                }
                String loanId = null;
                if ("LOAN".equals(mode)) {
                    loanId = java.util.UUID.randomUUID().toString();
                    long now = System.currentTimeMillis() / 1000;
                    long due = now + 30L * 86400L; // 默认 30 天
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO ks_bank_cb_loans (id, bank_id, principal, interest_rate, term_days, issued_at, due_at, repaid) " +
                            "VALUES (?,?,?,?,?,?,?,0)")) {
                        ps.setString(1, loanId);
                        ps.setString(2, bankId);
                        ps.setDouble(3, amount);
                        ps.setDouble(4, baseRate); // 利率 = 央行基准利率
                        ps.setInt(5, 30);
                        ps.setLong(6, now);
                        ps.setLong(7, due);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
                eco.getLogger().info("[央行] 向银行 " + bankId + " 注入流动性 " + amount + " 模式=" + mode
                        + (loanId != null ? " 贷款ID=" + loanId : ""));
                return null;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[央行] 注资失败: " + e.getMessage());
            return "注资失败: " + e.getMessage();
        }
    }

    /** 兼容旧调用（默认使用唯一允许的 LOAN 模式） */
    public boolean injectLiquidity(String bankId, double amount) {
        return injectLiquidity(bankId, amount, "LOAN") == null;
    }

    private void saveRate() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_bank_cb_rates (base_rate, reserve_requirement, set_at) VALUES (?,?,?)")) {
                ps.setDouble(1, baseRate);
                ps.setDouble(2, reserveRequirement);
                ps.setLong(3, System.currentTimeMillis() / 1000);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[央行] 保存利率失败: " + e.getMessage());
        }
    }

    private void saveConfig(String key, String value) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            BankSqlMutation.upsert(conn,
                    "UPDATE ks_bank_cb_config SET config_value=? WHERE config_key=?", update -> {
                        update.setString(1, value);
                        update.setString(2, key);
                    }, "INSERT INTO ks_bank_cb_config (config_key,config_value) VALUES (?,?)", insert -> {
                        insert.setString(1, key);
                        insert.setString(2, value);
                    });
        } catch (SQLException e) {
            eco.getLogger().warning("[央行] 保存配置失败: " + e.getMessage());
        }
    }
}
