package org.kseco.extra.tax;

import org.kseco.KsEco;

import java.sql.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;

/**
 * 惩罚机制管理器。
 *
 * 惩罚类型：
 * - TAX_EVASION: 逃税漏税（罚金 = 应纳税额 × penaltyRate）
 * - CONTRACT_BREACH: 违约（罚金 = 项目合同金额 × penaltyRate）
 * - FRAUD: 交易欺诈
 *
 * 罚金直接进入系统国库。
 */
public final class PenaltyManager {

    private final KsEco eco;
    private final Map<String, Double> penaltyRates = new LinkedHashMap<>();

    public PenaltyManager(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        penaltyRates.put("TAX_EVASION", 0.30);    // 逃税罚金 30%
        penaltyRates.put("CONTRACT_BREACH", 0.20); // 违约罚金 20%
        penaltyRates.put("FRAUD", 0.50);           // 欺诈罚金 50%

        try {
            eco.asyncWorkPool().executeDatabase(this::createTable);
        } catch (RejectedExecutionException exception) {
            eco.getLogger().warning("[Penalty] Database queue rejected table initialization");
        }
    }

    private void createTable() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            TaxJdbcSupport.ensureTables(conn);
        } catch (SQLException | RuntimeException e) {
            eco.getLogger().warning("[惩罚] 创建表失败: " + e.getMessage());
        }
    }

    /**
     * 发出罚单。
     */
    public Penalty issue(UUID targetUuid, String targetName, String penaltyType,
                          double baseAmount, String reason) {
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Penalty collection must run on the server thread");
        }
        String normalizedType = penaltyType == null
                ? ""
                : penaltyType.trim().toUpperCase(Locale.ROOT);
        if (targetUuid == null || normalizedType.isEmpty()
                || normalizedType.length() > TaxJdbcSupport.KEY_MAX_LENGTH
                || !TaxValuePolicy.isValidPositiveAmount(baseAmount)) {
            eco.getLogger().warning("[Penalty] Rejected invalid penalty request");
            return null;
        }
        double rate = TaxValuePolicy.normalizeRate(
                penaltyRates.getOrDefault(normalizedType, 0.20d), 0.20d);
        double amount = TaxValuePolicy.calculateTax(baseAmount, rate, 0.0d);
        if (!TaxValuePolicy.isValidPositiveAmount(amount)) return null;

        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_tax_penalties (id, target_uuid, target_name, penalty_type, " +
                    "base_amount, penalty_rate, penalty_amount, reason, issued_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, targetUuid.toString());
                ps.setString(3, targetName);
                ps.setString(4, normalizedType);
                ps.setDouble(5, baseAmount);
                ps.setDouble(6, rate);
                ps.setDouble(7, amount);
                ps.setString(8, reason);
                ps.setLong(9, now);
                ps.executeUpdate();
            }
        } catch (SQLException | RuntimeException e) {
            eco.getLogger().warning("[惩罚] 发出罚单失败: " + e.getMessage());
            return null;
        }

        // 自动扣款
        var player = org.bukkit.Bukkit.getOfflinePlayer(targetUuid);
        if (eco.vaultHook().has(player, amount) && eco.vaultHook().withdraw(player, amount)) {
            // 标记为已缴
            try (Connection conn = eco.ksCore().dataStore().getConnection()) {
                if (conn != null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE ks_tax_penalties SET paid=1 WHERE id=?")) {
                        ps.setString(1, id);
                        if (ps.executeUpdate() != 1) {
                            throw new SQLException("penalty row disappeared before payment update");
                        }
                    }
                }
            } catch (SQLException | RuntimeException paymentFailure) {
                if (eco.vaultHook().deposit(player, amount)) {
                    eco.getLogger().warning("[Penalty] Refunded payment after status update failed: " + id);
                } else {
                    eco.getLogger().severe("[Penalty] Payment status update and refund both failed: " + id);
                }
            }
        }

        return new Penalty(id, targetUuid, normalizedType, baseAmount, rate, amount, reason, now);
    }

    /**
     * 获取玩家的未缴罚单。
     */
    public List<Penalty> getUnpaidPenalties(UUID playerUuid) {
        List<Penalty> result = new ArrayList<>();
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM ks_tax_penalties WHERE target_uuid=? AND paid=0")) {
                ps.setString(1, playerUuid.toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    try {
                        result.add(new Penalty(
                                rs.getString("id"), UUID.fromString(rs.getString("target_uuid")),
                                rs.getString("penalty_type"), rs.getDouble("base_amount"),
                                rs.getDouble("penalty_rate"), rs.getDouble("penalty_amount"),
                                rs.getString("reason"), rs.getLong("issued_at")));
                    } catch (IllegalArgumentException invalidUuid) {
                        eco.getLogger().warning("[Penalty] Ignored row with invalid target UUID: "
                                + rs.getString("id"));
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            eco.getLogger().warning("[惩罚] 查询失败: " + e.getMessage());
        }
        return result;
    }

    public record Penalty(String id, UUID targetUuid, String penaltyType,
                          double baseAmount, double rate, double amount,
                          String reason, long issuedAt) {}
}
