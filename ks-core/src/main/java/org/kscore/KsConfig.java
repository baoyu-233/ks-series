package org.kscore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ks-core 配置管理 —— 从 config.yml 读取所有配置项。
 */
public final class KsConfig {

    private final JavaPlugin plugin;

    private boolean webEnabled;
    private int port;
    private String bindAddress;
    private String publicAddress;
    private int tokenTimeout;
    private int maxThreads;
    private boolean allowTestToken;

    private String dbType;
    private String sqliteFile;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private boolean mysqlUseSsl;
    private int mysqlPoolSize;

    public KsConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        // Web 网关
        var web = cfg.getConfigurationSection("web-gateway");
        if (web != null) {
            webEnabled = web.getBoolean("enabled", true);
            port = web.getInt("port", 8123);
            bindAddress = web.getString("bind-address", "0.0.0.0");
            publicAddress = web.getString("public-address", "");
            tokenTimeout = web.getInt("token-timeout-seconds", 600);
            maxThreads = web.getInt("max-threads", 8);
            allowTestToken = web.getBoolean("allow-test-token", false);
        } else {
            webEnabled = true;
            port = 8123;
            bindAddress = "0.0.0.0";
            publicAddress = "";
            tokenTimeout = 600;
            maxThreads = 8;
            allowTestToken = false;
        }

        if (publicAddress == null || publicAddress.isEmpty()) {
            // bind-address 为 0.0.0.0 时对外地址应回退到 127.0.0.1（浏览器无法访问 0.0.0.0）
            publicAddress = bindAddress.equals("0.0.0.0") ? "127.0.0.1" : bindAddress;
        }

        // 数据库
        var db = cfg.getConfigurationSection("database");
        if (db != null) {
            dbType = db.getString("type", "sqlite");
            var sqlite = db.getConfigurationSection("sqlite");
            sqliteFile = sqlite != null ? sqlite.getString("file", "data.db") : "data.db";
            var mysql = db.getConfigurationSection("mysql");
            if (mysql != null) {
                mysqlHost = mysql.getString("host", "localhost");
                mysqlPort = mysql.getInt("port", 3306);
                mysqlDatabase = mysql.getString("database", "ks_core");
                mysqlUsername = mysql.getString("username", "");
                mysqlPassword = mysql.getString("password", "");
                mysqlUseSsl = mysql.getBoolean("use-ssl", false);
                mysqlPoolSize = mysql.getInt("pool-size", 5);
            }
        } else {
            dbType = "sqlite";
            sqliteFile = "data.db";
        }
    }

    // ---- Web 网关 getters ----

    public boolean isWebEnabled() { return webEnabled; }
    public int getPort() { return port; }
    public String getBindAddress() { return bindAddress; }
    public String getPublicAddress() { return publicAddress; }
    public int getTokenTimeout() { return tokenTimeout; }
    public int getMaxThreads() { return maxThreads; }
    public boolean isTestTokenAllowed() { return allowTestToken; }

    // ---- 数据库 getters ----

    public String getDatabaseType() { return dbType; }
    public String getSqliteFile() { return sqliteFile; }
    public String getMysqlHost() { return mysqlHost; }
    public int getMysqlPort() { return mysqlPort; }
    public String getMysqlDatabase() { return mysqlDatabase; }
    public String getMysqlUsername() { return mysqlUsername; }
    public String getMysqlPassword() { return mysqlPassword; }
    public boolean isMysqlUseSsl() { return mysqlUseSsl; }
    public int getMysqlPoolSize() { return mysqlPoolSize; }

    // ---- 子插件路由配置 ----

    public boolean isSubPluginEnabled(String pluginId) {
        var sp = plugin.getConfig().getConfigurationSection("sub-plugins." + pluginId);
        return sp == null || sp.getBoolean("enabled", true);
    }

    public String getSubPluginRoute(String pluginId) {
        var sp = plugin.getConfig().getConfigurationSection("sub-plugins." + pluginId);
        if (sp == null) return "/" + pluginId.toUpperCase();
        return sp.getString("route", "/" + pluginId.toUpperCase());
    }
}
