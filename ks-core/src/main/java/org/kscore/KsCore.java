package org.kscore;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * ks-core 主插件 — Web 网关中枢。
 * 负责启动嵌入式 HTTP 服务器、管理子插件路由注册、统一 Token 鉴权。
 */
public final class KsCore extends JavaPlugin {

    private KsWebServer webServer;
    private KsRouter router;
    private KsAuthManager authManager;
    private KsDataStore dataStore;
    private KsConfig ksConfig;
    private AnnouncementManager announcementManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 初始化配置
        this.ksConfig = new KsConfig(this);

        // 初始化数据存储
        this.dataStore = new KsDataStore(this, ksConfig);
        dataStore.init();

        // 初始化路由表
        this.router = new KsRouter(this);

        // 初始化认证管理
        this.authManager = new KsAuthManager(ksConfig.getTokenTimeout());

        // 初始化公告栏 + 注册公开路由 /announce（读取无需 token）
        this.announcementManager = new AnnouncementManager(this);
        announcementManager.init();
        router.register("ks-core-announce", "/announce", new AnnouncementHandler(this));

        // 启动 Web 服务器
        this.webServer = new KsWebServer(this, ksConfig, router, authManager);
        webServer.start();

        // 注册管理命令
        if (getCommand("kscore") != null) {
            getCommand("kscore").setExecutor(this);
        }

        // 注册公告栏游戏命令 /announce
        if (getCommand("announce") != null) {
            getCommand("announce").setExecutor(new AnnounceCommand(this, announcementManager, ksConfig));
        }

        getLogger().info("ks-core 已启用。Web 网关: " +
                (webServer.isRunning() ? "http://" + ksConfig.getPublicAddress() + ":" + ksConfig.getPort() : "已禁用"));
        getLogger().info("子插件路由数: " + router.routeCount());
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        if (dataStore != null) {
            dataStore.shutdown();
        }
        getLogger().info("ks-core 已停用。");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6[ks-core] §e/kscore reload §7— 重载配置");
            sender.sendMessage("§6[ks-core] §e/kscore status §7— 查看网关状态");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                reloadPlugin();
                sender.sendMessage("§a[ks-core] 配置已重载。");
                break;
            case "status":
                sender.sendMessage("§6[ks-core 状态]");
                sender.sendMessage("§7  Web 网关: " + (webServer.isRunning()
                        ? "§a运行中 §7http://" + ksConfig.getPublicAddress() + ":" + ksConfig.getPort()
                        : "§c已停止"));
                sender.sendMessage("§7  活跃路由: §f" + router.routeCount());
                sender.sendMessage("§7  活跃会话: §f" + authManager.activeCount());
                sender.sendMessage("§7  数据存储: §f" + ksConfig.getDatabaseType());
                sender.sendMessage("§7  已注册子插件: §f" + String.join(", ", router.registeredPlugins()));
                break;
            default:
                sender.sendMessage("§c未知参数。用法: /kscore [reload|status]");
        }
        return true;
    }

    public void reloadPlugin() {
        reloadConfig();
        this.ksConfig = new KsConfig(this);

        // 重启 Web 服务器
        if (webServer != null) {
            webServer.stop();
        }
        this.authManager = new KsAuthManager(ksConfig.getTokenTimeout());
        this.webServer = new KsWebServer(this, ksConfig, router, authManager);
        webServer.start();

        getLogger().info("ks-core 配置已重载。");
    }

    // ---- 公开 API ----

    public KsRouter router() { return router; }
    public KsAuthManager authManager() { return authManager; }
    public KsDataStore dataStore() { return dataStore; }
    public KsConfig ksConfig() { return ksConfig; }
    public KsWebServer webServer() { return webServer; }
    public AnnouncementManager announcementManager() { return announcementManager; }

    /**
     * 获取 KsPluginBridge — 供其他插件通过 PluginManager 获取。
     * 其他 ks-* 插件在 onEnable 中调用：
     * <pre>
     * KsCore core = (KsCore) Bukkit.getPluginManager().getPlugin("ks-core");
     * KsPluginBridge bridge = core.bridge();
     * bridge.registerRoute(myPlugin, "/my-path", myHandler);
     * </pre>
     */
    public KsPluginBridge bridge() {
        return new KsPluginBridge(this);
    }
}
