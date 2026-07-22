package org.kssentinel;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * ks-Sentinel — 管理员行为审计插件。
 *
 * <p>记录全体玩家（含控制台）执行的指令，按规则判定风险等级，
 * 独立 SQLite 存储；1.21.11 + ks-core 时额外提供 Web 查看/规则管理界面。
 */
public final class KsSentinel extends JavaPlugin {

    private static final String DB_PATH = "plugins/ks-Sentinel/sentinel.db";

    private final Gson gson = new Gson();
    private Connection conn;
    private BukkitTask flushTask;
    private final ConcurrentLinkedQueue<LogEntry> pendingLogs = new ConcurrentLinkedQueue<>();

    private final List<RiskEvaluator.Rule> rulesCache = Collections.synchronizedList(new ArrayList<>());
    private final List<RiskEvaluator.Exclusion> exclusionsCache = Collections.synchronizedList(new ArrayList<>());

    public record LogEntry(String executorUuid, String executorName, boolean isConsole,
                            String command, String baseCommand,
                            String targetUuid, String targetName, String riskLevel,
                            String world, double x, double y, double z, long createdAt) {}

    @Override
    public void onEnable() {
        initDatabase();
        reloadRulesCache();
        reloadExclusionsCache();

        var cmd = Objects.requireNonNull(getCommand("sentinel"));
        cmd.setExecutor(new SentinelCommand(this));

        getServer().getPluginManager().registerEvents(new CommandAuditListener(this), this);

        flushTask = getServer().getScheduler().runTaskTimerAsynchronously(
            this, this::flushPendingLogs, 100L, 100L);

        tryRegisterWebHandler();

        getLogger().info("ks-Sentinel v" + getDescription().getVersion() + " 已启用（追踪全体玩家指令）");
    }

    @Override
    public void onDisable() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushPendingLogs();
        closeConnection();
        getLogger().info("ks-Sentinel 已禁用");
    }

    private synchronized void closeConnection() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
            conn = null;
        }
    }

    // ==================== 数据库 ====================

    private synchronized void initDatabase() {
        try {
            File dbFile = new File(DB_PATH);
            dbFile.getParentFile().mkdirs();
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_sentinel_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        executor_uuid TEXT,
                        executor_name TEXT NOT NULL,
                        is_console INTEGER DEFAULT 0,
                        command TEXT NOT NULL,
                        base_command TEXT NOT NULL,
                        target_uuid TEXT,
                        target_name TEXT,
                        risk_level TEXT DEFAULT 'INFO',
                        world TEXT, x REAL, y REAL, z REAL,
                        created_at INTEGER NOT NULL
                    )
                """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sentinel_logs_created ON ks_sentinel_logs(created_at)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sentinel_logs_risk ON ks_sentinel_logs(risk_level)");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_sentinel_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        command_prefix TEXT NOT NULL UNIQUE,
                        check_target_arg INTEGER DEFAULT 0,
                        risk_level TEXT DEFAULT 'HIGH',
                        enabled INTEGER DEFAULT 1
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_sentinel_exclusions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        command_prefix TEXT NOT NULL UNIQUE,
                        note TEXT DEFAULT ''
                    )
                """);
            }
            seedDefaultRules();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "数据库初始化失败", e);
        }
    }

    private synchronized void seedDefaultRules() {
        // command_prefix, checkTargetArg, riskLevel
        Object[][] seeds = {
            {"give", true, "HIGH"}, {"gamemode", true, "HIGH"}, {"tp", true, "HIGH"},
            {"teleport", true, "HIGH"}, {"effect", true, "HIGH"}, {"enchant", true, "HIGH"},
            {"clear", true, "HIGH"}, {"kill", true, "HIGH"}, {"item", true, "HIGH"},
            {"op", false, "HIGH"}, {"deop", false, "HIGH"}, {"ban", false, "HIGH"},
            {"ban-ip", false, "HIGH"}, {"pardon", false, "MEDIUM"}, {"pardon-ip", false, "MEDIUM"},
            {"whitelist", false, "MEDIUM"}, {"stop", false, "HIGH"}, {"kick", false, "MEDIUM"},
            {"xp", true, "MEDIUM"}, {"experience", true, "MEDIUM"}, {"summon", false, "MEDIUM"},
            {"setblock", false, "MEDIUM"}, {"fill", false, "MEDIUM"}, {"worldborder", false, "MEDIUM"},
            {"difficulty", false, "MEDIUM"}, {"reload", false, "MEDIUM"},
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO ks_sentinel_rules (command_prefix, check_target_arg, risk_level) VALUES (?, ?, ?)")) {
            for (Object[] s : seeds) {
                ps.setString(1, (String) s[0]);
                ps.setInt(2, ((boolean) s[1]) ? 1 : 0);
                ps.setString(3, (String) s[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            getLogger().warning("种子规则写入失败: " + e.getMessage());
        }
    }

    public Connection getConnection() { return conn; }

    // ==================== 规则缓存（供 RiskEvaluator 高频读取） ====================

    public synchronized void reloadRulesCache() {
        List<RiskEvaluator.Rule> fresh = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, command_prefix, check_target_arg, risk_level, enabled FROM ks_sentinel_rules")) {
            while (rs.next()) {
                fresh.add(new RiskEvaluator.Rule(rs.getInt("id"), rs.getString("command_prefix"),
                    rs.getInt("check_target_arg") != 0, rs.getString("risk_level"), rs.getInt("enabled") != 0));
            }
        } catch (SQLException e) {
            getLogger().warning("加载规则失败: " + e.getMessage());
        }
        synchronized (rulesCache) { rulesCache.clear(); rulesCache.addAll(fresh); }
    }

    public synchronized void reloadExclusionsCache() {
        List<RiskEvaluator.Exclusion> fresh = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, command_prefix FROM ks_sentinel_exclusions")) {
            while (rs.next()) {
                fresh.add(new RiskEvaluator.Exclusion(rs.getInt("id"), rs.getString("command_prefix")));
            }
        } catch (SQLException e) {
            getLogger().warning("加载排除规则失败: " + e.getMessage());
        }
        synchronized (exclusionsCache) { exclusionsCache.clear(); exclusionsCache.addAll(fresh); }
    }

    public List<RiskEvaluator.Rule> rulesSnapshot() {
        synchronized (rulesCache) { return new ArrayList<>(rulesCache); }
    }

    public List<RiskEvaluator.Exclusion> exclusionsSnapshot() {
        synchronized (exclusionsCache) { return new ArrayList<>(exclusionsCache); }
    }

    public synchronized boolean addRule(String commandPrefix, boolean checkTargetArg, String riskLevel) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO ks_sentinel_rules (command_prefix, check_target_arg, risk_level, enabled) VALUES (?, ?, ?, 1)")) {
            ps.setString(1, commandPrefix.toLowerCase(Locale.ROOT));
            ps.setInt(2, checkTargetArg ? 1 : 0);
            ps.setString(3, riskLevel);
            ps.executeUpdate();
            reloadRulesCache();
            return true;
        } catch (SQLException e) {
            getLogger().warning("新增规则失败: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean removeRule(int id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ks_sentinel_rules WHERE id=?")) {
            ps.setInt(1, id);
            int n = ps.executeUpdate();
            reloadRulesCache();
            return n > 0;
        } catch (SQLException e) { return false; }
    }

    public synchronized boolean addExclusion(String commandPrefix, String note) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO ks_sentinel_exclusions (command_prefix, note) VALUES (?, ?)")) {
            ps.setString(1, commandPrefix.toLowerCase(Locale.ROOT));
            ps.setString(2, note == null ? "" : note);
            ps.executeUpdate();
            reloadExclusionsCache();
            return true;
        } catch (SQLException e) {
            getLogger().warning("新增排除规则失败: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean removeExclusion(int id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ks_sentinel_exclusions WHERE id=?")) {
            ps.setInt(1, id);
            int n = ps.executeUpdate();
            reloadExclusionsCache();
            return n > 0;
        } catch (SQLException e) { return false; }
    }

    // ==================== 日志写入（异步批量 flush） ====================

    public void enqueueLog(LogEntry entry) {
        pendingLogs.add(entry);
    }

    private synchronized void flushPendingLogs() {
        if (pendingLogs.isEmpty() || conn == null) return;
        List<LogEntry> batch = new ArrayList<>();
        LogEntry e;
        while ((e = pendingLogs.poll()) != null) batch.add(e);
        if (batch.isEmpty()) return;
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_sentinel_logs (executor_uuid, executor_name, is_console, command, base_command, " +
                    "target_uuid, target_name, risk_level, world, x, y, z, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (LogEntry log : batch) {
                    ps.setString(1, log.executorUuid());
                    ps.setString(2, log.executorName());
                    ps.setInt(3, log.isConsole() ? 1 : 0);
                    ps.setString(4, log.command());
                    ps.setString(5, log.baseCommand());
                    ps.setString(6, log.targetUuid());
                    ps.setString(7, log.targetName());
                    ps.setString(8, log.riskLevel());
                    ps.setString(9, log.world());
                    ps.setDouble(10, log.x());
                    ps.setDouble(11, log.y());
                    ps.setDouble(12, log.z());
                    ps.setLong(13, log.createdAt());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException ex) {
            getLogger().warning("日志批量写入失败: " + ex.getMessage());
            try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) {}
            // Put the batch back so a transient DB failure does not permanently drop audits.
            for (int i = batch.size() - 1; i >= 0; i--) {
                pendingLogs.offer(batch.get(i));
            }
        }
    }

    // ==================== 查询（Web/指令共用） ====================

    public synchronized List<Map<String, Object>> queryLogs(String riskLevel, String executor, String commandLike,
                                                long fromTs, long toTs, int limit, int offset) {
        List<Map<String, Object>> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM ks_sentinel_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (riskLevel != null && !riskLevel.isEmpty()) {
            sql.append(" AND risk_level=?"); params.add(riskLevel);
        }
        if (executor != null && !executor.isEmpty()) {
            sql.append(" AND executor_name LIKE ?"); params.add("%" + executor + "%");
        }
        if (commandLike != null && !commandLike.isEmpty()) {
            sql.append(" AND command LIKE ?"); params.add("%" + commandLike + "%");
        }
        if (fromTs > 0) { sql.append(" AND created_at>=?"); params.add(fromTs); }
        if (toTs > 0) { sql.append(" AND created_at<=?"); params.add(toTs); }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit); params.add(offset);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                var meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) row.put(meta.getColumnName(i), rs.getObject(i));
                    list.add(row);
                }
            }
        } catch (SQLException e) {
            getLogger().warning("查询日志失败: " + e.getMessage());
        }
        return list;
    }

    // ==================== Web 路由（仅 1.21.11 + ks-core） ====================

    private void tryRegisterWebHandler() {
        try {
            var corePlugin = Bukkit.getPluginManager().getPlugin("ks-core");
            if (corePlugin == null) {
                getLogger().info("未检测到 ks-core，跳过 Web 路由注册（仅游戏内 /sentinel log 可用）");
                return;
            }
            var bridgeMethod = corePlugin.getClass().getMethod("bridge");
            Object bridge = bridgeMethod.invoke(corePlugin);
            var regMethod = bridge.getClass().getMethod("registerRoute",
                String.class, String.class, Class.forName("com.sun.net.httpserver.HttpHandler"));
            regMethod.invoke(bridge, "ks-Sentinel", "/ks-Sentinel", new SentinelWebHandler(this));
            getLogger().info("已注册 Web 路由 /ks-Sentinel");
        } catch (Exception e) {
            getLogger().warning("Web 路由注册失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public Gson gson() { return gson; }
}
