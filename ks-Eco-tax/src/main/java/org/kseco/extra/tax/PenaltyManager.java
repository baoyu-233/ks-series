package org.kseco.extra.tax;

import org.kseco.KsEco;

import java.sql.*;
import java.util.*;

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

        createTable();
    }

    private void createTable() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_tax_penalties (
                        id TEXT PRIMARY KEY,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT,
                        penalty_type TEXT NOT NULL,
                        base_amount REAL NOT NULL,
                        penalty_rate REAL NOT NULL,
                        penalty_amount REAL NOT NULL,
                        reason TEXT,
                        paid INTEGER DEFAULT 0,
                        issued_at INTEGER NOT NULL
                    )
                """);
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[惩罚] 创建表失败: " + e.getMessage());
        }
    }

    /**
     * 发出罚单。
     */
    public Penalty issue(UUID targetUuid, String targetName, String penaltyType,
                          double baseAmount, String reason) {
        double rate = penaltyRates.getOrDefault(penaltyType, 0.20);
        double amount = baseAmount * rate;

        String id = UUID.randomUUID().toString().substring(0, 8);
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
                ps.setString(4, penaltyType);
                ps.setDouble(5, baseAmount);
                ps.setDouble(6, rate);
                ps.setDouble(7, amount);
                ps.setString(8, reason);
                ps.setLong(9, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[惩罚] 发出罚单失败: " + e.getMessage());
            return null;
        }

        // 自动扣款
        var player = org.bukkit.Bukkit.getOfflinePlayer(targetUuid);
        if (eco.vaultHook().has(player, amount)) {
            eco.vaultHook().withdraw(player, amount);
            // 标记为已缴
            try (Connection conn = eco.ksCore().dataStore().getConnection()) {
                if (conn != null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE ks_tax_penalties SET paid=1 WHERE id=?")) {
                        ps.setString(1, id);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException ignored) {}
        }

        return new Penalty(id, targetUuid, penaltyType, baseAmount, rate, amount, reason, now);
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
                    result.add(new Penalty(
                            rs.getString("id"), UUID.fromString(rs.getString("target_uuid")),
                            rs.getString("penalty_type"), rs.getDouble("base_amount"),
                            rs.getDouble("penalty_rate"), rs.getDouble("penalty_amount"),
                            rs.getString("reason"), rs.getLong("issued_at")));
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[惩罚] 查询失败: " + e.getMessage());
        }
        return result;
    }

    public record Penalty(String id, UUID targetUuid, String penaltyType,
                          double baseAmount, double rate, double amount,
                          String reason, long issuedAt) {}
}
