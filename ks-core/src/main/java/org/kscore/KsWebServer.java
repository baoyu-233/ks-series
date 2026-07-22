package org.kscore;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;
import org.kscore.scheduler.KsScheduler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * ks-core 嵌入式 HTTP 服务器。
 * 基于 Java 内置 com.sun.net.httpserver（零额外依赖），
 * 作为所有 ks-Series 插件的统一 Web 入口。
 */
public final class KsWebServer {

    private final JavaPlugin plugin;
    private final KsConfig config;
    private final KsRouter router;
    private final KsAuthManager authManager;
    private HttpServer server;
    private ExecutorService executor;
    private boolean running;

    public KsWebServer(JavaPlugin plugin, KsConfig config, KsRouter router, KsAuthManager authManager) {
        this.plugin = plugin;
        this.config = config;
        this.router = router;
        this.authManager = authManager;
    }

    /**
     * 启动 HTTP 服务器。
     */
    public void start() {
        if (!config.isWebEnabled()) {
            plugin.getLogger().info("Web 网关已在配置中禁用。");
            return;
        }

        try {
            String bind = config.getBindAddress();
            int port = config.getPort();

            InetSocketAddress addr = (bind.isEmpty() || "0.0.0.0".equals(bind))
                    ? new InetSocketAddress(port)
                    : new InetSocketAddress(bind, port);

            server = HttpServer.create(addr, 0);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Web 网关启动失败（端口 " + config.getPort() + " 可能被占用）: " + e.getMessage());
            return;
        }

        // 注册路由分发器为根处理器
        server.createContext("/", router);

        // 线程池
        int threads = config.getMaxThreads();
        executor = Executors.newFixedThreadPool(threads);
        server.setExecutor(executor);
        server.start();
        running = true;

        // 定时清理过期会话（每 5 分钟）
        KsScheduler.runAsyncAtFixedRate(plugin, authManager::cleanup,
                Duration.ofMinutes(5), Duration.ofMinutes(5));

        plugin.getLogger().info("Web 网关已启动 — 绑定: " +
                (config.getBindAddress().isEmpty() ? "0.0.0.0" : config.getBindAddress()) +
                ":" + config.getPort() +
                "  对外地址: http://" + config.getPublicAddress() + ":" + config.getPort());
    }

    /**
     * 停止 HTTP 服务器。
     */
    public void stop() {
        if (server != null) {
            server.stop(2);
            server = null;
            running = false;
            plugin.getLogger().info("Web 网关已停止。");
        }
        shutdownExecutor();
        KsScheduler.cancelAsyncTasks(plugin);
    }

    private void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isRunning() {
        return running && server != null;
    }
}
