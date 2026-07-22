package org.kseco.extra.bank;

import org.kseco.KsEco;
import org.kseco.EconomyStatsFilter;
import org.bukkit.Bukkit;

import java.sql.*;
import java.util.*;

/**
 * 货币供应量追踪器。
 *
 * M0（基础货币）= 流通中的现金（玩家持有的货币）
 * M1（狭义货币）= M0 + 银行活期存款
 * M2（广义货币）= M1 + 银行定期存款 + 其他准货币
 *
 * 定时快照并存储，用于央行决策参考。
 */
public final class MoneySupplyTracker {

    private final KsEco eco;

    public MoneySupplyTracker(KsEco eco) {
        this.eco = eco;
    }

    public void init() {
        createTable();
    }

    private void createTable() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            BankSchema.ensureMoneySupplyTable(conn);
        } catch (SQLException e) {
            eco.getLogger().warning("[货币供应] 创建表失败: " + e.getMessage());
        }
    }

    /**
     * 计算当前货币供应量并存储快照。
     */
    public MoneySupply snapshot() {
        double m0 = estimateM0();
        double m1Deposits = getTotalDemandDeposits();
        double m2Deposits = getTotalTimeDeposits();

        return persistSnapshot(m0, m1Deposits, m2Deposits);
    }

    /**
     * Capture Bukkit/Vault state on the server thread, then persist the pure
     * database portion on ks-Eco's worker pool.
     */
    public void snapshotAsync() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Money supply capture must run on the server thread");
        }
        double m0 = estimateM0();
        eco.asyncWorkPool().executeDatabase(() -> {
            double m1Deposits = getTotalDemandDeposits();
            double m2Deposits = getTotalTimeDeposits();
            persistSnapshot(m0, m1Deposits, m2Deposits);
        });
    }

    private MoneySupply persistSnapshot(double m0, double m1Deposits, double m2Deposits) {
        double m1 = m0 + m1Deposits;
        double m2 = m1 + m2Deposits;

        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_bank_money_supply (m0, m1, m2, snapshot_at) VALUES (?,?,?,?)")) {
                    ps.setDouble(1, m0);
                    ps.setDouble(2, m1);
                    ps.setDouble(3, m2);
                    ps.setLong(4, System.currentTimeMillis() / 1000);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[货币供应] 存储快照失败: " + e.getMessage());
        }

        return new MoneySupply(m0, m1, m2);
    }

    /**
     * 获取最近的货币供应快照。
     */
    public MoneySupply getLatest() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return new MoneySupply(0, 0, 0);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT m0, m1, m2 FROM ks_bank_money_supply ORDER BY snapshot_at DESC LIMIT 1")) {
                if (rs.next()) {
                    return new MoneySupply(rs.getDouble("m0"), rs.getDouble("m1"), rs.getDouble("m2"));
                }
            }
        } catch (SQLException ignored) {}
        return new MoneySupply(0, 0, 0);
    }

    // M0: 简化估算 — 从 Vault 经济系统获取总余额（实际上这是近似值）
    private double estimateM0() {
        // 简化：遍历在线玩家的余额总和 + 离线玩家估算
        double total = 0;
        for (var player : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (EconomyStatsFilter.shouldExcludePlayer(eco, player)) continue;
            total += eco.vaultHook().getBalance(player);
        }
        return total;
    }

    private double getTotalDemandDeposits() {
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            return EconomyStatsFilter.sumPlayerBalances(eco, conn,
                    "ks_bank_accounts", "player_uuid", null, "balance").included();
        } catch (SQLException e) { return 0; }
    }

    private double getTotalTimeDeposits() {
        double total = 0;
        try (Connection conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (PreparedStatement statement = conn.prepareStatement(
                    "SELECT player_uuid,principal FROM ks_bank_term_deposits WHERE status='ACTIVE'");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String playerUuid = rows.getString("player_uuid");
                    if (!EconomyStatsFilter.shouldExcludePlayer(eco, playerUuid, null)) {
                        total += rows.getDouble("principal");
                    }
                }
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[货币供应] 读取定期存款失败: " + failure.getMessage());
        }
        return total;
    }

    public record MoneySupply(double m0, double m1, double m2) {}
}
