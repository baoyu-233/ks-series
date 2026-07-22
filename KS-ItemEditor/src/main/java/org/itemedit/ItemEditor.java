package org.itemedit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ItemEditor extends JavaPlugin {

    private final Map<UUID, EditSession> sessions = new HashMap<>();
    private final Map<UUID, RefineSession> refineSessions = new HashMap<>();
    private boolean itemsAdder;
    private WebServer webServer;

    private boolean ksCoreMode = false; // true = 由 ks-core 托管 Web，false = 独立模式
    private Object ksBridge;            // ks-core KsPluginBridge 实例（反射调用）

    @Override
    public void onEnable() {
        // 加载 / 创建默认配置
        saveDefaultConfig();

        this.itemsAdder = getServer().getPluginManager().getPlugin("ItemsAdder") != null;

        // 管理员命令
        EditCommand adminCmd = new EditCommand(this);
        if (getCommand("itemedit") != null) {
            getCommand("itemedit").setExecutor(adminCmd);
        }

        // 玩家精炼命令
        RefineCommand refineCmd = new RefineCommand(this);
        if (getCommand("refine") != null) {
            getCommand("refine").setExecutor(refineCmd);
        }

        // 网页设计器命令
        DesignCommand designCmd = new DesignCommand(this);
        if (getCommand("design") != null) {
            getCommand("design").setExecutor(designCmd);
        }

        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new RefineListener(this), this);

        // ★ 注册临时聊天输入监听（替代已弃用的铁砧输入）
        ChatInput.init();

        // ★ 检测 ks-core，决定 Web 模式
        var ksCorePlugin = getServer().getPluginManager().getPlugin("ks-core");
        if (ksCorePlugin != null && ksCorePlugin.isEnabled()) {
            // ks-core 模式：由 ks-core 托管 Web 路由
            initWithKsCore(ksCorePlugin, designCmd);
        } else {
            // 独立模式：使用内置 WebServer
            initWithStandalone(designCmd);
        }

        getLogger().info("ItemEditor 已启用。ItemsAdder: " + (itemsAdder ? "开启" : "未检测到")
                + " | Web: " + (ksCoreMode ? "ks-core 托管" : "独立模式"));
    }

    /**
     * ks-core 托管模式 — 将 Web 路由注册到 ks-core 的网关。
     */
    private void initWithKsCore(org.bukkit.plugin.Plugin ksCorePlugin, DesignCommand designCmd) {
        try {
            // 通过反射调用 ks-core API（避免硬编译依赖）
            Object core = ksCorePlugin;
            var bridgeMethod = core.getClass().getMethod("bridge");
            Object bridge = bridgeMethod.invoke(core);
            this.ksBridge = bridge; // ★ 保存引用，供 createWebUrl() 使用

            // 创建 Web 服务器（仅用于 TemplateManager）
            webServer = new WebServer(this);
            webServer.setKsCoreMode(true);

            // 通过 bridge 注册路由
            var registerMethod = bridge.getClass().getMethod("registerRoute",
                    String.class, String.class, com.sun.net.httpserver.HttpHandler.class);
            registerMethod.invoke(bridge, "KS-ItemEditor", "/IE", webServer.webApi());
            // ★ 设置 ks-core 桥接，使 WebApi 使用 ks-core 统一鉴权（而非独立 WebSessionManager）
            webServer.webApi().setKsBridge(bridge);

            // 检查 ks-core 配置中是否启用了 ie 路由
            var isEnabledMethod = bridge.getClass().getMethod("isPluginRouteEnabled", String.class);
            boolean enabled = (boolean) isEnabledMethod.invoke(bridge, "KS-ItemEditor");

            if (enabled) {
                ksCoreMode = true;
                webServer.start(); // ★ 初始化配置（端口/地址/token超时），start() 在 ksCoreMode 下只读配置不启动 HTTP
                getLogger().info("Web 编辑器已注册到 ks-core 网关（路由: /IE）。");
            } else {
                // 配置中禁用了 IE 路由，回退到独立模式
                webServer = new WebServer(this);
                webServer.setKsCoreMode(false);
                webServer.start();
                getLogger().info("ks-core 配置中 IE 路由已禁用，回退到独立 Web 模式。");
            }

            if (webServer.templateManager() != null) {
                designCmd.setTemplateManager(webServer.templateManager());
            }
        } catch (Exception e) {
            getLogger().warning("ks-core 对接失败，回退到独立 Web 模式: " + e.getMessage());
            initWithStandalone(designCmd);
        }
    }

    /**
     * 独立模式 — 使用内置 HTTP 服务器（兼容旧配置）。
     */
    private void initWithStandalone(DesignCommand designCmd) {
        ksCoreMode = false;
        webServer = new WebServer(this);
        webServer.setKsCoreMode(false);
        webServer.start();

        if (webServer.templateManager() != null) {
            designCmd.setTemplateManager(webServer.templateManager());
        }
    }

    @Override
    public void onDisable() {
        // 仅在独立模式下停止 Web 服务器（ks-core 模式下由 ks-core 管理）
        if (webServer != null && !ksCoreMode) {
            webServer.stop();
        }

        // 关闭所有管理编辑界面
        for (UUID id : new HashMap<>(sessions).keySet()) {
            Player p = getServer().getPlayer(id);
            if (p != null) p.closeInventory();
        }
        sessions.clear();

        // 归还所有精炼会话中的物品
        for (RefineSession rs : new HashMap<>(refineSessions).values()) {
            if (!rs.isHandled()) {
                rs.cancel();
            }
        }
        refineSessions.clear();
    }

    // ---- Web 服务器 ----

    public WebServer webServer() {
        return webServer;
    }

    // ---- 管理编辑会话 ----

    public Map<UUID, EditSession> sessions() {
        return sessions;
    }

    public EditSession session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    // ---- 玩家精炼会话 ----

    public Map<UUID, RefineSession> refineSessions() {
        return refineSessions;
    }

    public RefineSession refineSession(Player player) {
        return refineSessions.get(player.getUniqueId());
    }

    public boolean itemsAdderEnabled() {
        return itemsAdder;
    }

    /**
     * ★ /itemedit reload — 重载配置文件，重启 Web 服务器。
     * 无需重启整个服务器即可应用 config.yml 修改。
     */
    public void reloadPlugin() {
        // 1. 重新读取 config.yml
        reloadConfig();

        // 2. 刷新 ItemsAdder 检测
        this.itemsAdder = getServer().getPluginManager().getPlugin("ItemsAdder") != null;

        // 3. 重启 Web 服务器（根据 ks-core 模式决定）
        if (!ksCoreMode) {
            // 独立模式：重启内置 WebServer
            if (webServer != null) {
                webServer.stop();
            }
            webServer = new WebServer(this);
            webServer.setKsCoreMode(false);
            webServer.start();

            // 注入 TemplateManager
            if (webServer.templateManager() != null) {
                var designCmd = (DesignCommand) getCommand("design").getExecutor();
                if (designCmd != null) {
                    designCmd.setTemplateManager(webServer.templateManager());
                }
            }
        } else {
            // ks-core 模式：只需重建 TemplateManager（Web 路由由 ks-core 管理）
            webServer = new WebServer(this);
            webServer.setKsCoreMode(true);
            webServer.start(); // ★ 初始化配置值（不启动 HTTP）
            // 注意：ks-core 模式下的路由已在 onEnable 中注册，无需重新注册
        }

        getLogger().info("ItemEditor 配置已重载。Web: " +
                (ksCoreMode ? "ks-core 托管" :
                 (webServer != null && webServer.getPort() > 0
                     ? "http://" + webServer.getPublicAddress() + ":" + webServer.getPort()
                     : "已禁用")) +
                "  ItemsAdder: " + (itemsAdder ? "开启" : "未检测到"));
    }

    /** 是否处于 ks-core 托管模式。 */
    public boolean isKsCoreMode() { return ksCoreMode; }

    /** ks-core 桥接实例（反射调用，ks-core 模式下方非 null）。 */
    public Object bridge() { return ksBridge; }

    /**
     * 生成 Web 编辑器链接。
     * ks-core 模式下使用 bridge.createWebLink()（含正确路由 /IE），
     * 独立模式下使用 WebServer 地址。
     */
    public String createWebUrl(org.bukkit.entity.Player player, boolean isAdmin) {
        if (ksCoreMode && ksBridge != null) {
            try {
                var method = ksBridge.getClass().getMethod("createWebLink",
                        org.bukkit.entity.Player.class, boolean.class, String.class);
                return (String) method.invoke(ksBridge, player, isAdmin, "/IE");
            } catch (Exception e) {
                getLogger().warning("通过 bridge 生成链接失败，回退到独立模式 URL: " + e.getMessage());
            }
        }
        // 独立模式或回退
        WebServer ws = webServer();
        if (ws == null) return null;
        String host = ws.getPublicAddress();
        if (host == null || host.equals("0.0.0.0") || host.isEmpty()) host = "127.0.0.1";
        boolean admin = isAdmin && player.hasPermission("itemedit.admin");
        WebSessionManager.Session session = WebSessionManager.create(
                player.getUniqueId(), player.getName(), admin);
        return "http://" + host + ":" + ws.getPort() + "/?token=" + session.token;
    }
}
