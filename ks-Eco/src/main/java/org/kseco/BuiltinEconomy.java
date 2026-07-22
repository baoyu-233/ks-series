package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.DatabaseDialect;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/**
 * 内置 Vault 经济提供者（动态代理，零编译依赖）。
 * 当服务器没有安装其他经济插件（EssentialsX/CMI 等）时，
 * ks-Eco 自动注册自己为 Vault 经济提供者，使用 ks-core JDBC 存储余额。
 */
public final class BuiltinEconomy {
    private static final double MAX_TRANSACTION_AMOUNT = 1_000_000_000_000d;

    private final JavaPlugin plugin;
    private Object economyProxy;
    private boolean registered;

    record BalanceMutationResult(double balance, boolean success, String error) { }

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
            plugin.getLogger().info("内置经济系统已注册到 Vault（JDBC 存储）");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("内置经济系统注册失败: " + e.getMessage());
            return false;
        }
    }

    /** Enables the JDBC wallet without requiring Vault (Folia ks-only nodes). */
    public boolean setupDirect() {
        if (!plugin.getClass().getName().equals("org.kseco.KsEco")
                || !(plugin instanceof KsEco eco) || !eco.foliaRuntime()) {
            return false;
        }
        if (!ensureTable()) return false;
        this.registered = true;
        plugin.getLogger().info("Folia JDBC 内置经济已启用（无需 Vault）");
        return true;
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
        return addBalance(conn, uuid, name, amount, currentEpochSecond()) == 1;
    }

    // ---- 公共兜底 API（盲盒等无 Vault 时直接走内置 JDBC 余额） ----

    /** 直接扣减内置表余额（不依赖 Vault）。余额不足返回 false。 */
    public boolean withdraw(UUID uuid, String name, double amount) {
        if (uuid == null || !validAmount(amount, true)) return false;
        if (amount == 0.0d) return true;
        return mutateBalance(uuid, name, amount, true).success();
    }

    /** 直接增加内置表余额 */
    public boolean deposit(UUID uuid, String name, double amount) {
        if (uuid == null || !validAmount(amount, false)) return false;
        return mutateBalance(uuid, name, amount, false).success();
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

    static BalanceMutationResult balanceMutationResult(double current, double next, boolean persisted) {
        return new BalanceMutationResult(persisted ? next : current, persisted,
                persisted ? null : "Balance persistence failed");
    }

    static void ensureTable(Connection connection, DatabaseDialect dialect) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(dialect, "dialect");
        String sql = "CREATE TABLE IF NOT EXISTS ks_builtin_economy ("
                + "uuid " + BusinessSchemaDialect.varchar(36) + " PRIMARY KEY, "
                + "balance " + BusinessSchemaDialect.floatingPointType(dialect) + " NOT NULL DEFAULT 0, "
                + "name " + BusinessSchemaDialect.varchar(64) + " NOT NULL DEFAULT '', "
                + "updated_at BIGINT NOT NULL DEFAULT 0)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    static BalanceMutationResult depositBalance(Connection connection, UUID uuid, String name,
                                                  double amount, long updatedAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(uuid, "uuid");
        if (!validAmount(amount, true)) {
            return new BalanceMutationResult(readBalance(connection, uuid), false, "Invalid amount");
        }
        if (amount != 0.0d && addBalance(connection, uuid, name, amount, updatedAt) != 1) {
            return new BalanceMutationResult(readBalance(connection, uuid), false, "Balance persistence failed");
        }
        return new BalanceMutationResult(readBalance(connection, uuid), true, null);
    }

    static BalanceMutationResult withdrawBalance(Connection connection, UUID uuid, String name,
                                                   double amount, long updatedAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(uuid, "uuid");
        if (!validAmount(amount, true)) {
            return new BalanceMutationResult(readBalance(connection, uuid), false, "Invalid amount");
        }
        if (amount == 0.0d) {
            return new BalanceMutationResult(readBalance(connection, uuid), true, null);
        }
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_builtin_economy SET balance=balance-?, "
                        + "name=CASE WHEN ?<>'' THEN ? ELSE name END, updated_at=? "
                        + "WHERE uuid=? AND balance>=?")) {
            String normalizedName = normalizedName(name);
            statement.setDouble(1, amount);
            statement.setString(2, normalizedName);
            statement.setString(3, normalizedName);
            statement.setLong(4, updatedAt);
            statement.setString(5, uuid.toString());
            statement.setDouble(6, amount);
            updated = statement.executeUpdate();
        }
        double balance = readBalance(connection, uuid);
        return updated == 1
                ? new BalanceMutationResult(balance, true, null)
                : new BalanceMutationResult(balance, false, "余额不足");
    }

    static boolean ensureAccount(Connection connection, UUID uuid, String name, long updatedAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(uuid, "uuid");
        return addBalance(connection, uuid, name, 0.0d, updatedAt) == 1;
    }

    static double readBalance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance FROM ks_builtin_economy WHERE uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getDouble(1) : 0.0d;
            }
        }
    }

    private static int addBalance(Connection connection, UUID uuid, String name,
                                  double amount, long updatedAt) throws SQLException {
        String normalizedName = normalizedName(name);
        int updated = updateBalance(connection, uuid, normalizedName, amount, updatedAt);
        if (updated == 1 || accountExists(connection, uuid)) return 1;

        Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ks_builtin_economy (uuid, balance, name, updated_at) VALUES (?,?,?,?)")) {
            statement.setString(1, uuid.toString());
            statement.setDouble(2, amount);
            statement.setString(3, normalizedName);
            statement.setLong(4, updatedAt);
            return statement.executeUpdate();
        } catch (SQLException failure) {
            if (!isConstraintViolation(failure)) throw failure;
            if (savepoint != null) connection.rollback(savepoint);
            updated = updateBalance(connection, uuid, normalizedName, amount, updatedAt);
            if (updated == 1 || accountExists(connection, uuid)) return 1;
            throw failure;
        } finally {
            if (savepoint != null) {
                try {
                    connection.releaseSavepoint(savepoint);
                } catch (SQLException ignored) {
                    // Some drivers release a savepoint automatically after rollback.
                }
            }
        }
    }

    private static int updateBalance(Connection connection, UUID uuid, String name,
                                     double amount, long updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_builtin_economy SET balance=balance+?, "
                        + "name=CASE WHEN ?<>'' THEN ? ELSE name END, updated_at=? WHERE uuid=?")) {
            statement.setDouble(1, amount);
            statement.setString(2, name);
            statement.setString(3, name);
            statement.setLong(4, updatedAt);
            statement.setString(5, uuid.toString());
            return statement.executeUpdate();
        }
    }

    private static boolean accountExists(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM ks_builtin_economy WHERE uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean isConstraintViolation(SQLException failure) {
        if (failure instanceof SQLIntegrityConstraintViolationException) return true;
        String state = failure.getSQLState();
        return state != null && state.startsWith("23");
    }

    private static String normalizedName(String name) {
        return name == null ? "" : name;
    }

    private static long currentEpochSecond() {
        return System.currentTimeMillis() / 1000L;
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

    private boolean ensureTable() {
        try {
            // 通过 ks-core DataStore 获取连接
            var corePlugin = (org.kscore.KsCore) Bukkit.getPluginManager().getPlugin("ks-core");
            if (corePlugin != null) {
                try (var conn = corePlugin.dataStore().getConnection()) {
                    if (conn != null) {
                        ensureTable(conn, BusinessSchemaDialect.detect(conn));
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("内置经济表创建失败: " + e.getMessage());
        }
        return false;
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

    private boolean ensureAccount(UUID uuid, String name) {
        if (uuid == null) return false;
        var corePlugin = (org.kscore.KsCore) Bukkit.getPluginManager().getPlugin("ks-core");
        if (corePlugin == null) return false;
        try (var conn = corePlugin.dataStore().getConnection()) {
            if (conn == null) return false;
            return ensureAccount(conn, uuid, name, currentEpochSecond());
        } catch (SQLException e) {
            plugin.getLogger().warning("BuiltinEconomy.ensureAccount 失败: " + e.getMessage());
            return false;
        }
    }

    private BalanceMutationResult mutateBalance(UUID uuid, String name, double amount, boolean withdrawal) {
        var corePlugin = (org.kscore.KsCore) Bukkit.getPluginManager().getPlugin("ks-core");
        if (corePlugin == null) {
            return new BalanceMutationResult(0.0d, false, "ks-core unavailable");
        }
        try (var conn = corePlugin.dataStore().getConnection()) {
            if (conn == null) return new BalanceMutationResult(0.0d, false, "Database unavailable");
            conn.setAutoCommit(false);
            try {
                BalanceMutationResult result = withdrawal
                        ? withdrawBalance(conn, uuid, name, amount, currentEpochSecond())
                        : depositBalance(conn, uuid, name, amount, currentEpochSecond());
                if (result.success()) conn.commit();
                else conn.rollback();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("BuiltinEconomy." + (withdrawal ? "withdraw" : "deposit")
                    + " 失败: " + e.getMessage());
            return new BalanceMutationResult(0.0d, false, "Balance persistence failed");
        }
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
                        yield ensureAccount(p.getUniqueId(), p.getName() != null ? p.getName() : "?");
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
                        if (!validAmount(amount, true)) yield createResponse(amount, getBalance(p.getUniqueId()), false, "Invalid amount");
                        BalanceMutationResult result = mutateBalance(p.getUniqueId(), p.getName(), amount, true);
                        if (result.success() && plugin instanceof KsEco eco) {
                            eco.publishCrossServerInvalidation("balance", p.getUniqueId().toString());
                        }
                        yield createResponse(amount, result.balance(), result.success(), result.error());
                    }

                    case "depositPlayer" -> {
                        OfflinePlayer p = (OfflinePlayer) args[0];
                        double amount = (double) args[1];
                        if (!validAmount(amount, true)) yield createResponse(amount, getBalance(p.getUniqueId()), false, "Invalid amount");
                        BalanceMutationResult result = mutateBalance(p.getUniqueId(), p.getName(), amount, false);
                        if (result.success() && plugin instanceof KsEco eco) {
                            eco.publishCrossServerInvalidation("balance", p.getUniqueId().toString());
                        }
                        yield createResponse(amount, result.balance(), result.success(), result.error());
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
