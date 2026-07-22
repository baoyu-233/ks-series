package org.kseco.extra.tax;

import org.kseco.KsEco;

import java.sql.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;

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
    private final Object statsLock = new Object();

    public TaxManager(KsEco eco, TaxRateManager taxRateManager) {
        this.eco = eco;
        this.taxRateManager = taxRateManager;
    }

    public void init() {
        try {
            eco.asyncWorkPool().executeDatabase(this::createTable);
        } catch (RejectedExecutionException exception) {
            eco.getLogger().warning("[Tax] Database queue rejected table initialization");
        }
    }

    private void createTable() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            TaxJdbcSupport.ensureTables(conn);
        } catch (SQLException | RuntimeException e) {
            eco.getLogger().warning("[税法] 创建表失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定税种的有效税率。
     * 优先从 TaxRateManager（动态税率），回退到全局税率。
     */
    private double getEffectiveRate(String category) {
        double fallback = TaxValuePolicy.normalizeRate(eco.ecoConfig().getTaxRate(), 0.02d);
        if (taxRateManager != null) {
            return TaxValuePolicy.normalizeRate(taxRateManager.getRate(category), fallback);
        }
        return fallback;
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
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Tax collection must run on the server thread");
        }
        if (payerUuid == null || category == null || category.isBlank()
                || !TaxValuePolicy.isValidPositiveAmount(baseAmount)) {
            eco.getLogger().warning("[Tax] Rejected invalid collection request");
            return 0.0d;
        }

        String normalizedCategory = category.trim().toUpperCase(Locale.ROOT);
        double rate = getEffectiveRate(category);
        double tax = TaxValuePolicy.calculateTax(
                baseAmount, rate, eco.ecoConfig().getMinTax());
        if (tax <= 0.0d) return 0.0d;

        // 从玩家扣税
        var player = org.bukkit.Bukkit.getOfflinePlayer(payerUuid);
        if (!eco.vaultHook().has(player, tax)) {
            // 税额不足 → 记录欠税（信用记录影响）
            eco.getLogger().warning("[税法] 玩家 " + payerName + " 税额不足: " + tax + " (" + category + ")");
            double balance = eco.vaultHook().getBalance(player);
            tax = TaxValuePolicy.isValidPositiveAmount(balance)
                    ? Math.min(tax, balance)
                    : 0.0d;
        }

        if (tax <= 0.0d || !eco.vaultHook().withdraw(player, tax)) {
            eco.getLogger().warning("[Tax] Vault withdrawal failed for " + payerUuid
                    + " amount=" + tax + " category=" + normalizedCategory);
            return 0.0d;
        }

        TaxRecord record = new TaxRecord(
                Long.toString(ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE)),
                payerUuid,
                payerName == null ? "" : payerName,
                normalizedCategory,
                baseAmount,
                rate,
                tax,
                description == null ? "" : description,
                System.currentTimeMillis() / 1000L);
        adjustStats(normalizedCategory, tax);
        if (!queueTaxRecord(record)) {
            if (eco.vaultHook().deposit(player, tax)) {
                adjustStats(normalizedCategory, -tax);
                return 0.0d;
            }
            eco.getLogger().severe("[Tax] Audit queue rejected and refund failed for "
                    + payerUuid + " amount=" + tax);
        }

        return tax;
    }

    private boolean queueTaxRecord(TaxRecord record) {
        try {
            eco.asyncWorkPool().executeDatabase(() -> {
                try {
                    if (insertTaxRecord(record)) return;
                } catch (RuntimeException exception) {
                    eco.getLogger().warning("[Tax] Unexpected tax audit failure " + record.id()
                            + ": " + exception.getMessage());
                }
                try {
                    org.bukkit.Bukkit.getScheduler().runTask(eco, () -> refundFailedAudit(record));
                } catch (RuntimeException exception) {
                    eco.getLogger().severe("[Tax] Failed to schedule refund for unaudited tax "
                            + record.id() + ": " + exception.getMessage());
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            eco.getLogger().warning("[Tax] Database queue rejected tax audit " + record.id());
            return false;
        }
    }

    private boolean insertTaxRecord(TaxRecord record) {
        Exception failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection conn = eco.ksCore().dataStore().getConnection()) {
                if (conn == null) {
                    failure = new IllegalStateException("tax database connection unavailable");
                    continue;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_tax_records (id, payer_uuid, payer_name, category, "
                                + "base_amount, tax_rate, tax_amount, description, collected_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, record.id());
                    ps.setString(2, record.payerUuid().toString());
                    ps.setString(3, record.payerName());
                    ps.setString(4, record.category());
                    ps.setDouble(5, record.baseAmount());
                    ps.setDouble(6, record.rate());
                    ps.setDouble(7, record.tax());
                    ps.setString(8, record.description());
                    ps.setLong(9, record.collectedAt());
                    ps.executeUpdate();
                    return true;
                }
            } catch (SQLException | RuntimeException exception) {
                failure = exception;
                if (taxRecordExists(record.id())) return true;
            }
        }
        eco.getLogger().warning("[Tax] Failed to persist tax audit " + record.id()
                + (failure == null ? "" : ": " + failure.getMessage()));
        return false;
    }

    private boolean taxRecordExists(String id) {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_tax_records WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            return false;
        }
    }

    private void refundFailedAudit(TaxRecord record) {
        var player = org.bukkit.Bukkit.getOfflinePlayer(record.payerUuid());
        if (eco.vaultHook().deposit(player, record.tax())) {
            adjustStats(record.category(), -record.tax());
            eco.getLogger().warning("[Tax] Refunded unaudited tax " + record.id()
                    + " amount=" + record.tax());
        } else {
            eco.getLogger().severe("[Tax] Audit failed and refund failed for " + record.id()
                    + " payer=" + record.payerUuid() + " amount=" + record.tax());
        }
    }

    private void adjustStats(String category, double amount) {
        synchronized (statsLock) {
            totalCollected = Math.max(0.0d, totalCollected + amount);
            taxesByCategory.merge(category, amount, Double::sum);
            if (taxesByCategory.getOrDefault(category, 0.0d) <= 0.0d) {
                taxesByCategory.remove(category);
            }
        }
    }

    /**
     * 征收阶梯式企业税（不同规模企业不同税率）。
     * 优先使用 TaxRateManager 的动态税率，否则使用默认阶梯：
     * - 小型企业（注册资本 < 100,000）：5%
     * - 中型企业（注册资本 100,000 ~ 500,000）：8%
     * - 大型企业（注册资本 > 500,000）：12%
     */
    public double collectEnterpriseTax(String enterpriseId, double income, String description) {
        if (enterpriseId == null || enterpriseId.isBlank()
                || enterpriseId.length() > TaxJdbcSupport.ID_MAX_LENGTH
                || !TaxValuePolicy.isValidPositiveAmount(income)) {
            eco.getLogger().warning("[Tax] Rejected invalid enterprise tax request");
            return 0.0d;
        }
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
            String category;
            if (registeredCapital < 100000) {
                category = "ENTERPRISE_SMALL";
            } else if (registeredCapital < 500000) {
                category = "ENTERPRISE_MEDIUM";
            } else {
                category = "ENTERPRISE_LARGE";
            }

            String ownerUuidText = ownerUuids == null ? "" : ownerUuids.split(",", 2)[0].trim();
            if (ownerUuidText.isEmpty()) return 0.0d;
            UUID ownerUuid;
            try {
                ownerUuid = UUID.fromString(ownerUuidText);
            } catch (IllegalArgumentException exception) {
                eco.getLogger().warning("[Tax] Enterprise has an invalid owner UUID: " + enterpriseId);
                return 0.0d;
            }
            return collect(ownerUuid, "企业", category, income, description);
        } catch (SQLException | RuntimeException e) {
            eco.getLogger().warning("[税法] 企业税失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 获取税收统计。
     */
    public TaxStats getStats() {
        synchronized (statsLock) {
            return new TaxStats(totalCollected,
                    Collections.unmodifiableMap(new LinkedHashMap<>(taxesByCategory)));
        }
    }

    public record TaxStats(double totalCollected, Map<String, Double> byCategory) {}

    private record TaxRecord(String id, UUID payerUuid, String payerName, String category,
                             double baseAmount, double rate, double tax, String description,
                             long collectedAt) {}
}
