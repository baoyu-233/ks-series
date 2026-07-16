package org.kscore;

import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Level;

/**
 * 数据存储抽象层。
 * 当前实现：SQLite（面板服友好，零配置）。
 * 预留：MySQL 接口（跨服架构时切换）。
 *
 * 使用方式：
 * <pre>
 *   KsDataStore ds = plugin.dataStore();
 *   try (Connection conn = ds.getConnection()) { ... }
 * </pre>
 */
public final class KsDataStore {

    private final JavaPlugin plugin;
    private final KsConfig config;
    private String dbUrl;
    private Properties connProps; // 连接属性（含 busy_timeout/WAL/foreign_keys）

    public KsDataStore(JavaPlugin plugin, KsConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * 初始化数据库连接。
     */
    public void init() {
        String dbType = config.getDatabaseType();
        if ("mysql".equalsIgnoreCase(dbType)) {
            initMysql();
        } else {
            initSqlite();
        }
        createTables();
    }

    private void initSqlite() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) dataFolder.mkdirs();
            File dbFile = new File(dataFolder, config.getSqliteFile());
            dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            // 把所有 PRAGMA 嵌入 Properties，避免每次 getConnection 额外执行一次 Statement
            SQLiteConfig cfg = new SQLiteConfig();
            cfg.enforceForeignKeys(true);
            cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
            cfg.setBusyTimeout(5000);
            connProps = cfg.toProperties();
            // 验证连接可用
            try (var conn = DriverManager.getConnection(dbUrl, connProps)) {
                plugin.getLogger().info("SQLite 数据库已连接: " + dbFile.getName());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "SQLite 数据库初始化失败", e);
        }
    }

    private void initMysql() {
        // 预留 — 跨服架构时实现
        plugin.getLogger().warning("MySQL 模式为预留功能，当前使用 SQLite 回退。");
        initSqlite();
    }

    /**
     * 获取数据库连接。
     * 每次调用返回新的 Connection（适用于 try-with-resources 模式，SQLite WAL 支持并发）。
     * PRAGMA 已嵌入 connProps，不再执行额外 Statement。
     */
    public Connection getConnection() {
        try {
            if (dbUrl != null) {
                return DriverManager.getConnection(dbUrl, connProps);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "获取数据库连接失败", e);
        }
        return null;
    }

    /**
     * 创建核心表结构。
     * 子插件可通过此方法扩展自己的表。
     */
    private void createTables() {
        try (var conn = getConnection();
             Statement stmt = conn.createStatement()) {
            // 会话持久化表（预留 — 跨服共享 session）
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ks_sessions (
                    token TEXT PRIMARY KEY,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    is_admin INTEGER DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    server_id TEXT DEFAULT 'main'
                )
            """);

            // 子插件注册表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ks_registry (
                    plugin_id TEXT PRIMARY KEY,
                    plugin_name TEXT NOT NULL,
                    route_path TEXT NOT NULL,
                    enabled INTEGER DEFAULT 1,
                    registered_at INTEGER NOT NULL
                )
            """);

            // 通用键值存储（供子插件使用）
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ks_kv_store (
                    namespace TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (namespace, key)
                )
            """);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "数据库表创建失败", e);
        }
    }

    /**
     * 关闭数据库连接。
     * 每连接由 try-with-resources 各自关闭，此处无需额外操作。
     */
    public void shutdown() {
        // SQLite 连接由各调用方通过 try-with-resources 管理
        plugin.getLogger().info("数据库存储已关闭。");
    }
}
