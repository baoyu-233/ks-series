package org.kseco.extra.bank;

import org.kseco.KsEco;
import org.kseco.EconomyStatsFilter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.DoubleAdder;

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
    private static final Duration BALANCE_TIMEOUT = Duration.ofSeconds(10);

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
        if (!eco.scheduler().isGlobalThread() || eco.vaultHook().directBuiltinActive()) {
            throw new IllegalStateException("Synchronous money-supply capture requires the global thread and external Vault");
        }
        double m0 = estimateM0();
        double m1Deposits = getTotalDemandDeposits();
        double m2Deposits = getTotalTimeDeposits();

        return persistSnapshot(m0, m1Deposits, m2Deposits);
    }

    /**
     * Capture the player directory globally, read each external Vault balance on
     * its entity owner, then persist the pure database portion on ks-Eco's pool.
     */
    public void snapshotAsync() {
        if (!eco.scheduler().isGlobalThread()) {
            eco.scheduler().runGlobal(this::snapshotAsync);
            return;
        }
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        if (eco.vaultHook().directBuiltinActive()) {
            if (players.isEmpty()) {
                eco.asyncWorkPool().executeDatabase(() -> persistAsyncSnapshot(0.0d));
                return;
            }
            ConcurrentLinkedQueue<PlayerSnapshot> snapshots = new ConcurrentLinkedQueue<>();
            AtomicInteger remaining = new AtomicInteger(players.size());
            for (Player player : players) {
                AtomicBoolean playerCompleted = new AtomicBoolean();
                Runnable complete = () -> {
                    if (!playerCompleted.compareAndSet(false, true)) return;
                    if (remaining.decrementAndGet() == 0) {
                        List<PlayerSnapshot> immutable = List.copyOf(snapshots);
                        eco.asyncWorkPool().executeDatabase(() -> persistAsyncSnapshot(
                                immutable.stream().filter(snapshot -> !snapshot.excluded())
                                        .mapToDouble(snapshot -> eco.vaultHook().getBalanceDirect(snapshot.uuid())).sum()));
                    }
                };
                boolean scheduled = eco.scheduler().runEntity(player, () -> {
                    try {
                        snapshots.add(new PlayerSnapshot(player.getUniqueId(), player.getName(),
                                EconomyStatsFilter.shouldExcludePlayer(eco, player)));
                    } finally {
                        complete.run();
                    }
                }, complete);
                if (!scheduled) complete.run();
            }
            return;
        }
        if (players.isEmpty()) {
            eco.asyncWorkPool().executeDatabase(() -> persistAsyncSnapshot(0.0d));
            return;
        }
        DoubleAdder total = new DoubleAdder();
        AtomicInteger remaining = new AtomicInteger(players.size());
        for (Player player : players) {
            AtomicBoolean playerCompleted = new AtomicBoolean();
            Runnable complete = () -> {
                if (!playerCompleted.compareAndSet(false, true)) return;
                if (remaining.decrementAndGet() == 0) {
                    eco.asyncWorkPool().executeDatabase(() -> persistAsyncSnapshot(total.sum()));
                }
            };
            boolean scheduled = eco.scheduler().runEntity(player, () -> {
                try {
                    if (!EconomyStatsFilter.shouldExcludePlayer(eco, player)) {
                        total.add(eco.vaultHook().getBalance(player));
                    }
                } finally {
                    complete.run();
                }
            }, complete);
            if (!scheduled) complete.run();
        }
    }

    private void persistAsyncSnapshot(double m0) {
        double m1Deposits = getTotalDemandDeposits();
        double m2Deposits = getTotalTimeDeposits();
        persistSnapshot(m0, m1Deposits, m2Deposits);
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
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                total += eco.scheduler().callEntity(player, () ->
                        EconomyStatsFilter.shouldExcludePlayer(eco, player)
                                ? 0.0d : eco.vaultHook().getBalance(player), BALANCE_TIMEOUT);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException failure) {
                eco.getLogger().warning("[货币供应] 玩家余额快照失败: " + player.getUniqueId());
            }
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
    private record PlayerSnapshot(UUID uuid, String name, boolean excluded) {}
}
