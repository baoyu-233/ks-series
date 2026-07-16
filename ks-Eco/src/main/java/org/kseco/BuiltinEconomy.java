package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/**
 * 内置 Vault 经济提供者（动态代理，零编译依赖）。
 * 当服务器没有安装其他经济插件（EssentialsX/CMI 等）时，
 * ks-Eco 自动注册自己为 Vault 经济提供者，使用 SQLite 存储余额。
 */
public final class BuiltinEconomy {
    private static final double MAX_TRANSACTION_AMOUNT = 1_000_000_000_000d;

    private final JavaPlugin plugin;
    private Object economyProxy;
    private boolean registered;

    public BuiltinEconomy(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 初始化并注册到 Vault。
     * @return true 如果注册成功
     */
    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false; // Vault 未安装，无需注册
        }

        try {
            // 确保余额表存在
            ensureTable();

            // 查找 Economy 接口
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");

            // 创建动态代理
            this.economyProxy = Proxy.newProxyInstance(
                    economyClass.getClassLoader(),
                    new Class[]{economyClass},
                    new EconomyHandler()
            );

            // 注册到 Bukkit ServicesManager（raw cast 处理泛型擦除）
            @SuppressWarnings("unchecked")
            Class<Object> rawClass = (Class<Object>) economyClass;
            Bukkit.getServicesManager().register(
                    rawClass, this.economyProxy, plugin, ServicePriority.Normal);

            this.registered = true;
            plugin.getLogger().info("内置经济系统已注册到 Vault（SQLite 存储）");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("内置经济系统注册失败: " + e.getMessage());
            return false;
        }
    }

    public boolean isRegistered() { return registered; }

    /**
     * 在调用方现有数据库事务中给内置经济账户入账。
     * 仅供同时修改业务表和钱包余额的原子资金流使用；本方法不会提交或回滚连接。
     */
    public boolean depositInTransaction(Connection conn, OfflinePlayer player, double amount) throws SQLException {
        if (player == null) return false;
        return depositInTransaction(conn, player.getUniqueId(), player.getName(), amount);
    }

    public boolean depositInTransaction(Connection conn, UUID uuid, String name, double amount) throws SQLException {
        if (!registered || conn == null || uuid == null || !validAmount(amount, true)) return false;
        if (amount == 0.0d) return true;
        try (var ps = conn.prepareStatement(
                "INSERT INTO ks_builtin_economy (uuid, balance, name, updated_at) VALUES (?,?,?,strftime('%s','now')) " +
                        "ON CONFLICT(uuid) DO UPDATE SET balance=balance+excluded.balance, " +
                        "name=CASE WHEN excluded.name<>'' THEN excluded.name ELSE ks_builtin_economy.name END, " +
                        "updated_at=strftime('%s','now')")) {
            ps.setString(1, uuid.toString());
            ps.setDouble(2, amount);
            ps.setString(3, name != null ? name : "");
            return ps.executeUpdate() == 1;
        }
    }

    // ---- 公共兜底 API（盲盒等无 Vault 时直接走 SQLite） ----

    /** 直接扣减内置表余额（不依赖 Vault）。余额不足返回 false。 */
    public boolean withdraw(UUID uuid, String name, double amount) {
        if (!validAmount(amount, true)) return false;
        if (amount == 0.0d) return true;
        var corePlugin = (org.kscore.KsCore) Bukkit.getPluginManager().getPlugin("ks-core");
        if (corePlugin == null) return false;
        try (var conn = corePlugin.dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement("SELECT balance FROM ks_builtin_economy WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        try (var ins = conn.prepareStatement(
                                "INSERT INTO ks_builtin_economy (uuid, balance, name) VALUES (?, 0, ?)")) {
                            ins.setString(1, uuid.toString());
                            ins.setString(2, name != null ? name : "");
                            ins.executeUpdate();
                        }
                    }
                }
            }
            double cur = 0;
            try (var ps = conn.prepareStatement("SELECT balance FROM ks_builtin_economy WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) cur = rs.getDouble(1);
                }
            }
            if (cur < amount) { conn.rollback(); return false; }
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_builtin_economy SET balance=balance-?, name=?, updated_at=strftime('%s','now') WHERE uuid=?")) {
                ps.setDouble(1, amount);
                ps.setString(2, name != null ? name : "");
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }
            conn.commit();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("BuiltinEconomy.withdraw 失败: " + e.getMessage());
            return false;
        }
    }

    /** 直接增加内置表余额 */
    public void deposit(UUID uuid, String name, double amount) {
        if (!validAmount(amount, false)) return;
        var corePlugin = (org.kscore.KsCore) Bukkit.getPluginManager().getPlugin("ks-core");
        if (corePlugin == null) return;
        try (var conn = corePlugin.dataStore().getConnection()) {
            if (conn == null) return;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_builtin_economy (uuid, balance, name, updated_at) VALUES (?,?,?,strftime('%s','now')) " +
                    "ON CONFLICT(uuid) DO UPDATE SET balance=balance+excluded.balance, name=excluded.name, updated_at=strftime('%s','now')")) {
                ps.setString(1, uuid.toString());
                ps.setDouble(2, amount);
                ps.setString(3, name != null ? name : "");
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("BuiltinEconomy.deposit 失败: " + e.getMessage());
        }
    }

    /** 查询内置表余额 */
    public double getBalance(UUID uuid) {
        return getBalanceInternal(uuid);
    }
    private static boolean validAmount(double amount, boolean allowZero) {
        return Double.isFinite(amount)
                && (allowZero ? amount >= 0.0d : amount > 0.0d)
                && amount <= MAX_TRANSACTION_AMOUNT;
    }


    public void shutdown() {
        if (economyProxy != null) {
            try {
                Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
                Bukkit.getServicesManager().unregister(economyClass, economyProxy);
            } catch (Exception ignored) {}
        }
    }

    // ---- 数据库 ----

    private void ensureTable() {
        try {
            // 通过 ks-core DataStore 获取连接
            var corePlugin = (org.kscore.KsCore) Bukkit.getPluginManager().getPlugin("ks-core");
            if (corePlugin != null) {
                try (var conn = corePlugin.dataStore().getConnection()) {
                    if (conn != null) {
                        conn.createStatement().executeUpdate(
                            "CREATE TABLE IF NOT EXISTS ks_builtin_economy (" +
                            "uuid TEXT PRIMARY KEY, " +
                            "balance REAL DEFAULT 0, " +
                            "name TEXT DEFAULT '', " +
                            "updated_at INTEGER DEFAULT (strftime('%s','now')))");
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("内置经济表创建失败: " + e.getMessage());
        }
    }

    private double getBalanceInternal(UUID uuid) {
        try {
            var corePlugin = (org.kscore.KsCore) Bukkit.getPluginManager().getPlugin("ks-core");
            if (corePlugin != null) {
                try (var conn = corePlugin.dataStore().getConnection()) {
                    if (conn != null) {
                        try (var ps = conn.prepareStatement(
                                "SELECT balance FROM ks_builtin_economy WHERE uuid=?")) {
                            ps.setString(1, uuid.toString());
                            try (var rs = ps.executeQuery()) {
                                if (rs.next()) return rs.getDouble("balance");
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    private boolean setBalance(UUID uuid, String name, double balance) {
        try {
            var corePlugin = (org.kscore.KsCore) Bukkit.getPluginManager().getPlugin("ks-core");
            if (corePlugin != null) {
                try (var conn = corePlugin.dataStore().getConnection()) {
                    if (conn != null) {
                        try (var ps = conn.prepareStatement(
                                "INSERT OR REPLACE INTO ks_builtin_economy (uuid, balance, name) VALUES (?, ?, ?)")) {
                            ps.setString(1, uuid.toString());
                            ps.setDouble(2, balance);
                            ps.setString(3, name != null ? name : "");
                            ps.executeUpdate();
                        }
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ---- 动态代理处理器 ----

    private class EconomyHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            int paramCount = method.getParameterCount();

            try {
                return switch (name) {
                    // ---- 基本信息 ----
                    case "getName" -> "ks-Eco内置经济";
                    case "isEnabled" -> true;
                    case "hasBankSupport" -> false;
                    case "fractionalDigits" -> -1;
                    case "currencyNamePlural" -> "金币";
                    case "currencyNameSingular" -> "金币";
                    case "format" -> {
                        double amount = (double) args[0];
                        yield String.format("%.2f 金币", amount);
                    }

                    // ---- 玩家账户 ----
                    case "hasAccount" -> true; // 自动创建
                    case "createPlayerAccount" -> {
                        OfflinePlayer p = (OfflinePlayer) args[0];
                        setBalance(p.getUniqueId(), p.getName() != null ? p.getName() : "?", 0);
                        yield true;
                    }
                    case "getBalance" -> getBalance(((OfflinePlayer) args[0]).getUniqueId());

                    case "has" -> {
                        OfflinePlayer p = (OfflinePlayer) args[0];
                        double amount = (double) args[1];
                        yield validAmount(amount, true) && getBalance(p.getUniqueId()) >= amount;
                    }

                    case "withdrawPlayer" -> {
                        OfflinePlayer p = (OfflinePlayer) args[0];
                        double amount = (double) args[1];
                        double current = getBalance(p.getUniqueId());
                        if (!validAmount(amount, true)) yield createResponse(amount, getBalance(p.getUniqueId()), false, "Invalid amount");
                        if (current < amount) yield createResponse(amount, 0, false, "余额不足");
                        setBalance(p.getUniqueId(), p.getName(), current - amount);
                        yield createResponse(amount, current - amount, true, null);
                    }

                    case "depositPlayer" -> {
                        OfflinePlayer p = (OfflinePlayer) args[0];
                        double amount = (double) args[1];
                        double current = getBalance(p.getUniqueId());
                        if (!validAmount(amount, true)) yield createResponse(amount, getBalance(p.getUniqueId()), false, "Invalid amount");
                        setBalance(p.getUniqueId(), p.getName(), current + amount);
                        yield createResponse(amount, current + amount, true, null);
                    }

                    // ---- 银行（不支持） ----
                    case "createBank" -> createResponse(0, 0, false, "内置经济不支持银行");
                    case "deleteBank" -> createResponse(0, 0, false, "内置经济不支持银行");
                    case "bankBalance", "bankHas", "bankWithdraw", "bankDeposit" -> createResponse(0, 0, false, "内置经济不支持银行");
                    case "isBankOwner", "isBankMember" -> false;
                    case "getBanks" -> List.of();

                    // ---- 默认 ----
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "BuiltinEconomy(ks-Eco)";
                    case "equals" -> proxy == args[0];
                    default -> {
                        plugin.getLogger().warning("未处理的 Economy 方法: " + name + "(" + paramCount + ")");
                        yield null;
                    }
                };
            } catch (Exception e) {
                plugin.getLogger().warning("Economy." + name + " 异常: " + e.getMessage());
                if (name.startsWith("withdraw") || name.startsWith("deposit") || name.startsWith("bank") || name.equals("createBank") || name.equals("deleteBank")) {
                    return createResponse(0, 0, false, e.getMessage());
                }
                return null;
            }
        }

        /** 创建 EconomyResponse（完全反射，Vault 不在编译 classpath） */
        private Object createResponse(double amount, double balance, boolean success, String error) throws Exception {
            Class<?> responseClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            Class<?> responseTypeClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse$ResponseType");
            // ResponseType 是 enum，获取 SUCCESS/FAILURE 常量
            Object responseType = success
                    ? responseTypeClass.getField("SUCCESS").get(null)
                    : responseTypeClass.getField("FAILURE").get(null);
            return responseClass
                    .getConstructor(double.class, double.class, responseTypeClass, String.class)
                    .newInstance(amount, balance, responseType, error != null ? error : "");
        }
    }
}
