package org.itemedit;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * 嵌入式 HTTP 服务器 —— 为网页物品编辑器提供服务。
 * 使用 Java 内置 com.sun.net.httpserver，零额外依赖。
 */
public final class WebServer {

    private final JavaPlugin plugin;
    private final TemplateManager templateManager;
    private final WebApi webApi;
    private HttpServer server;
    private int port;
    private String bindAddress;
    private String publicAddress; // ★ 对外显示的地址（域名/IP），为空时回退到 bindAddress
    private int tokenTimeout;
    private boolean ksCoreMode = false; // true = 由 ks-core 托管，不启动内置服务器

    public WebServer(JavaPlugin plugin) {
        this.plugin = plugin;
        this.templateManager = new TemplateManager(plugin);
        this.webApi = new WebApi(plugin, templateManager, tokenTimeout);
    }

    /**
     * 设置 ks-core 托管模式。
     * true = 不启动内置 HTTP 服务器（路由由 ks-core 管理）。
     */
    public void setKsCoreMode(boolean mode) {
        this.ksCoreMode = mode;
    }

    public boolean isKsCoreMode() {
        return ksCoreMode;
    }

    /**
     * 获取 WebApi 处理器（供 ks-core 路由注册使用）。
     * 仅在 ks-core 模式下有意义。
     */
    public WebApi webApi() {
        return webApi;
    }

    /**
     * 从 config.yml 读取配置并启动服务器。
     * ks-core 模式下跳过服务器启动。
     */
    public void start() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("web-server");
        if (cfg == null) {
            plugin.getLogger().warning("config.yml 缺少 web-server 配置节，使用默认值。");
        }

        port = cfg != null ? cfg.getInt("port", 8123) : 8123;
        bindAddress = cfg != null ? cfg.getString("bind-address", "127.0.0.1") : "127.0.0.1";
        publicAddress = cfg != null ? cfg.getString("public-address", "") : "";
        if (publicAddress == null || publicAddress.isEmpty()) {
            publicAddress = bindAddress;
        }
        tokenTimeout = cfg != null ? cfg.getInt("token-timeout-seconds", 300) : 300;

        // 更新 WebApi 的 tokenTimeout
        webApi.setTokenTimeout(tokenTimeout);

        // ★ ks-core 模式下不启动内置服务器
        if (ksCoreMode) {
            plugin.getLogger().info("Web 编辑器处于 ks-core 托管模式，跳过内置 HTTP 服务器启动。");
            return;
        }

        boolean enabled = cfg != null && cfg.getBoolean("enabled", true);
        if (!enabled) {
            plugin.getLogger().info("Web 编辑器已在配置中禁用。");
            return;
        }

        try {
            InetSocketAddress addr = bindAddress.isEmpty() || bindAddress.equals("0.0.0.0")
                    ? new InetSocketAddress(port)
                    : new InetSocketAddress(bindAddress, port);
            server = HttpServer.create(addr, 0);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Web 服务器启动失败（端口 " + port + " 可能被占用）: " + e.getMessage());
            return;
        }

        // 注册路由
        server.createContext("/", webApi::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        plugin.getLogger().info("Web 编辑器已启动 — 绑定: " +
                (bindAddress.isEmpty() ? "0.0.0.0" : bindAddress) + ":" + port +
                "  对外地址: http://" + publicAddress + ":" + port);
    }

    /**
     * 停止服务器。
     */
    public void stop() {
        if (server != null) {
            server.stop(2);
            server = null;
            plugin.getLogger().info("Web 服务器已停止。");
        }
    }

    public TemplateManager templateManager() {
        return templateManager;
    }

    public int getPort() {
        return port;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    /** 对外显示的地址（用于生成玩家点击的链接）。优先使用 public-address 配置，否则回退到 bind-address。 */
    public String getPublicAddress() {
        return publicAddress != null && !publicAddress.isEmpty() ? publicAddress : bindAddress;
    }

    public int getTokenTimeout() {
        return tokenTimeout;
    }
}
