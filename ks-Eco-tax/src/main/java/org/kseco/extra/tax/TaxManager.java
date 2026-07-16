package org.kseco.extra.tax;

import org.kseco.KsEco;

import java.sql.*;
import java.util.*;

/**
 * 税收征收管理器。
 * 拦截所有经济交易，按税率征缴税款。
 * 税款进入"国库"账户（系统管理的特殊账户）。
 *
 * 税率来源：优先使用 TaxRateManager 的动态税率（按税种），
 * 如果 TaxRateManager 未初始化则回退到 ecoConfig 全局税率。
 */
public final class TaxManager {

    private final KsEco eco;
    private final TaxRateManager taxRateManager;

    // 税收统计
    private double totalCollected = 0.0;
    private final Map<String, Double> taxesByCategory = new LinkedHashMap<>();

    public TaxManager(KsEco eco, TaxRateManager taxRateManager) {
        this.eco = eco;
        this.taxRateManager = taxRateManager;
    }

    public void init() {
        createTable();
    }

    private void createTable() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_tax_records (
                        id TEXT PRIMARY KEY,
                        payer_uuid TEXT NOT NULL,
                        payer_name TEXT,
                        category TEXT NOT NULL,
                        base_amount REAL NOT NULL,
                        tax_rate REAL NOT NULL,
                        tax_amount REAL NOT NULL,
                        description TEXT,
                        collected_at INTEGER NOT NULL
                    )
                """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tax_payer ON ks_tax_records(payer_uuid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tax_category ON ks_tax_records(category)");
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[税法] 创建表失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定税种的有效税率。
     * 优先从 TaxRateManager（动态税率），回退到全局税率。
     */
    private double getEffectiveRate(String category) {
        if (taxRateManager != null) {
            return taxRateManager.getRate(category);
        }
        return eco.ecoConfig().getTaxRate();
    }

    /**
     * 征收交易税。
     *
     * @param payerUuid 纳税人 UUID
     * @param payerName 纳税人名称
     * @param category 税种（MARKET_TRADE, OFFICIAL_TRADE, ENTERPRISE_TAX, BANK_INTEREST 等）
     * @param baseAmount 税基（交易金额/收入金额）
     * @return 实际征收的税额
     */
    public double collect(UUID payerUuid, String payerName, String category, double baseAmount, String description) {
        double rate = getEffectiveRate(category);
        double tax = Math.max(baseAmount * rate, eco.ecoConfig().getMinTax());

        // 从玩家扣税
        var player = org.bukkit.Bukkit.getOfflinePlayer(payerUuid);
        if (!eco.vaultHook().has(player, tax)) {
            // 税额不足 → 记录欠税（信用记录影响）
            eco.getLogger().warning("[税法] 玩家 " + payerName + " 税额不足: " + tax + " (" + category + ")");
            tax = eco.vaultHook().getBalance(player); // 有多少扣多少
        }

        if (tax > 0) {
            eco.vaultHook().withdraw(player, tax);
        }

        // 记录
        String id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_tax_records (id, payer_uuid, payer_name, category, " +
                        "base_amount, tax_rate, tax_amount, description, collected_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, id);
                    ps.setString(2, payerUuid.toString());
                    ps.setString(3, payerName);
                    ps.setString(4, category);
                    ps.setDouble(5, baseAmount);
                    ps.setDouble(6, rate);
                    ps.setDouble(7, tax);
                    ps.setString(8, description);
                    ps.setLong(9, now);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[税法] 记录失败: " + e.getMessage());
        }

        totalCollected += tax;
        taxesByCategory.merge(category, tax, Double::sum);

        return tax;
    }

    /**
     * 征收阶梯式企业税（不同规模企业不同税率）。
     * 优先使用 TaxRateManager 的动态税率，否则使用默认阶梯：
     * - 小型企业（注册资本 < 100,000）：5%
     * - 中型企业（注册资本 100,000 ~ 500,000）：8%
     * - 大型企业（注册资本 > 500,000）：12%
     */
    public double collectEnterpriseTax(String enterpriseId, double income, String description) {
        // 获取企业信息
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            double registeredCapital = 0;
            String ownerUuids = "";
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT registered_capital, owner_uuids FROM ks_ent_enterprises WHERE id=?")) {
                ps.setString(1, enterpriseId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    registeredCapital = rs.getDouble("registered_capital");
                    ownerUuids = rs.getString("owner_uuids");
                } else return 0;
            }

            // 阶梯税率 — 优先从 TaxRateManager 获取，回退到默认值
            double rate;
            String category;
            if (registeredCapital < 100000) {
                rate = taxRateManager != null ? taxRateManager.getRate("ENTERPRISE_SMALL") : 0.05;
                category = "ENTERPRISE_SMALL";
            } else if (registeredCapital < 500000) {
                rate = taxRateManager != null ? taxRateManager.getRate("ENTERPRISE_MEDIUM") : 0.08;
                category = "ENTERPRISE_MEDIUM";
            } else {
                rate = taxRateManager != null ? taxRateManager.getRate("ENTERPRISE_LARGE") : 0.12;
                category = "ENTERPRISE_LARGE";
            }

            double tax = income * rate;
            UUID ownerUuid = UUID.fromString(ownerUuids.split(",")[0]);
            return collect(ownerUuid, "企业", category, income, description);
        } catch (SQLException e) {
            eco.getLogger().warning("[税法] 企业税失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 获取税收统计。
     */
    public TaxStats getStats() {
        return new TaxStats(totalCollected, new LinkedHashMap<>(taxesByCategory));
    }

    public record TaxStats(double totalCollected, Map<String, Double> byCategory) {}
}
