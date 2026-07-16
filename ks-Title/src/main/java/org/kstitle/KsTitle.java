package org.kstitle;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/**
 * ks-Title — 自研称号插件，取代 PlayerTitle（无数量上限 / 商店GUI / 属性加成 / 原生动画称号 / 条件解锁）。
 */
public final class KsTitle extends JavaPlugin {

    private static final String DB_PATH = "plugins/ks-Title/title.db";

    private final Gson gson = new Gson();
    private Connection conn;

    private TitleManager titleManager;
    private VaultHook vaultHook;
    private AttributeApplier attributeApplier;
    private ConditionEngine conditionEngine;
    private MigrationTool migrationTool;
    private TabIntegration tabIntegration;
    private IaFileManager iaFileManager;
    private TitleCardFactory titleCardFactory;

    @Override
    public void onEnable() {
        initDatabase();

        titleManager = new TitleManager(this);
        vaultHook = new VaultHook(this);
        attributeApplier = new AttributeApplier(this);
        conditionEngine = new ConditionEngine(this);
        migrationTool = new MigrationTool(this);
        tabIntegration = new TabIntegration(this);
        iaFileManager = new IaFileManager(this);
        titleCardFactory = new TitleCardFactory(this);

        migrationTool.runIfNeeded();
        int normalizedTitles = titleManager.normalizeLegacyCommandTitleWrappers();
        if (normalizedTitles > 0) {
            getLogger().info("Normalized " + normalizedTitles + " legacy command title wrapper(s).");
        }

        var cmd = getCommand("title");
        if (cmd != null) {
            TitleCommand executor = new TitleCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getServer().getPluginManager().registerEvents(new TitleListener(this), this);
        getServer().getPluginManager().registerEvents(new TitleMenuListener(this), this);

        getServer().getScheduler().runTaskTimer(this, conditionEngine::tick, 200L, 1200L);
        getServer().getScheduler().runTaskTimer(this, () -> titleManager.expireOwnerships(), 200L, 1200L);
        // TAB 接线自愈：每 20 分钟校验一次，管理员手滑改回旧占位符或TAB更新覆盖配置后自动补回并触发 tab reload
        getServer().getScheduler().runTaskTimerAsynchronously(this, tabIntegration::syncAndReload, 100L, 24000L);

        tryRegisterPapi();
        tryRegisterWebHandler();

        getLogger().info("ks-Title v" + getDescription().getVersion() + " 已启用 (" + titleManager.countDefs() + " 个称号定义)");
    }

    @Override
    public void onDisable() {
        closeConnection();
        getLogger().info("ks-Title 已禁用");
    }

    private void closeConnection() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
            conn = null;
        }
    }

    // ==================== 数据库 ====================

    private void initDatabase() {
        try {
            File dbFile = new File(DB_PATH);
            dbFile.getParentFile().mkdirs();
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_title_defs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        display_name TEXT NOT NULL,
                        description TEXT DEFAULT '',
                        category TEXT DEFAULT 'general',
                        rarity TEXT DEFAULT 'COMMON',
                        price REAL DEFAULT 0,
                        unlock_type TEXT DEFAULT 'ADMIN_GRANT',
                        condition_type TEXT DEFAULT '',
                        condition_value TEXT DEFAULT '',
                        visible INTEGER DEFAULT 1,
                        enabled INTEGER DEFAULT 1,
                        created_at INTEGER NOT NULL
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_title_attributes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id INTEGER NOT NULL,
                        buff_type TEXT NOT NULL,
                        buff_key TEXT NOT NULL,
                        amount REAL DEFAULT 0,
                        extra TEXT DEFAULT ''
                    )
                """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_title_attr_title ON ks_title_attributes(title_id)");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_title_frames (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title_id INTEGER NOT NULL,
                        frame_index INTEGER DEFAULT 0,
                        frame_text TEXT NOT NULL,
                        interval_ms INTEGER DEFAULT 800
                    )
                """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_title_frames_title ON ks_title_frames(title_id)");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_title_ownership (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        title_id INTEGER NOT NULL,
                        acquired_at INTEGER NOT NULL,
                        source TEXT DEFAULT 'ADMIN',
                        expires_at INTEGER DEFAULT 0,
                        UNIQUE(player_uuid, title_id)
                    )
                """);
                ensureColumn(stmt, "ks_title_ownership", "expires_at", "INTEGER DEFAULT 0");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_title_own_player ON ks_title_ownership(player_uuid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_title_own_expires ON ks_title_ownership(expires_at)");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_title_equipped (
                        player_uuid TEXT PRIMARY KEY,
                        title_id INTEGER NOT NULL,
                        equipped_at INTEGER NOT NULL
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_title_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_title_ia_bindings (
                        title_id INTEGER PRIMARY KEY,
                        image_prefix TEXT NOT NULL,
                        frame_count INTEGER NOT NULL,
                        interval_ms INTEGER DEFAULT 150,
                        chat_static INTEGER DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )
                """);
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "数据库初始化失败", e);
        }
    }

    public Connection getConnection() { return conn; }

    private void ensureColumn(Statement stmt, String table, String column, String ddl) throws SQLException {
        try (var rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return;
            }
        }
        stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
    }

    // ==================== 组件访问 ====================

    public TitleManager titleManager() { return titleManager; }
    public VaultHook vaultHook() { return vaultHook; }
    public AttributeApplier attributeApplier() { return attributeApplier; }
    public ConditionEngine conditionEngine() { return conditionEngine; }
    public TabIntegration tabIntegration() { return tabIntegration; }
    public IaFileManager iaFileManager() { return iaFileManager; }
    public TitleCardFactory titleCardFactory() { return titleCardFactory; }
    public Gson gson() { return gson; }

    // ==================== PlaceholderAPI（可选） ====================

    private void tryRegisterPapi() {
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                getLogger().info("未检测到 PlaceholderAPI，跳过 %kstitle_*% 占位符注册");
                return;
            }
            new KsTitlePapiExpansion(this).register();
            getLogger().info("已注册 PlaceholderAPI 扩展 (%kstitle_use%, %kstitle_use_id%)");
        } catch (Throwable t) {
            getLogger().warning("PlaceholderAPI 扩展注册失败: " + t.getMessage());
        }
    }

    // ==================== Web 路由（仅 ks-core 存在时） ====================

    private void tryRegisterWebHandler() {
        try {
            var corePlugin = Bukkit.getPluginManager().getPlugin("ks-core");
            if (corePlugin == null) {
                getLogger().info("未检测到 ks-core，跳过 Web 路由注册（仅游戏内 /title 可用）");
                return;
            }
            var bridgeMethod = corePlugin.getClass().getMethod("bridge");
            Object bridge = bridgeMethod.invoke(corePlugin);
            var regMethod = bridge.getClass().getMethod("registerRoute",
                String.class, String.class, Class.forName("com.sun.net.httpserver.HttpHandler"));
            regMethod.invoke(bridge, "ks-Title", "/ks-Title", new TitleWebHandler(this));
            getLogger().info("已注册 Web 路由 /ks-Title");
        } catch (Exception e) {
            getLogger().warning("Web 路由注册失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
