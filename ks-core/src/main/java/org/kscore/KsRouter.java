package org.kscore;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * URL 路由分发器。
 * 按最长路径前缀匹配，将请求分发到注册的子插件处理器。
 *
 * 路由示例：
 *   /IE/*     → ItemEditor handler
 *   /kSHWP/*  → ksHWP handler
 *   /ks-Eco/* → ks-Eco handler
 *   /          → 网关状态页（默认）
 */
public final class KsRouter implements HttpHandler {

    private final JavaPlugin plugin;
    /** 已注册路由：路径前缀 → 路由条目 */
    private final Map<String, RouteEntry> routes = new LinkedHashMap<>();

    public KsRouter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 注册一个子插件路由。
     *
     * @param pluginId   子插件标识（如 "ie", "kshwp", "ks-eco"）
     * @param pathPrefix 路径前缀（如 "/IE", "/kSHWP"）
     * @param handler    HTTP 请求处理器
     */
    public void register(String pluginId, String pathPrefix, HttpHandler handler) {
        // 规范化路径前缀
        String normalized = normalizePrefix(pathPrefix);
        RouteEntry entry = new RouteEntry(pluginId, normalized, handler);
        routes.put(normalized, entry);
        plugin.getLogger().info("路由已注册: " + normalized + " → " + pluginId);
    }

    /**
     * 取消注册。
     */
    public void unregister(String pluginId) {
        routes.entrySet().removeIf(e -> e.getValue().pluginId.equals(pluginId));
    }

    /**
     * 已注册的子插件列表。
     */
    public Set<String> registeredPlugins() {
        Set<String> ids = new LinkedHashSet<>();
        for (RouteEntry e : routes.values()) ids.add(e.pluginId);
        return ids;
    }

    /**
     * 路由数量。
     */
    public int routeCount() {
        return routes.size();
    }

    /**
     * 检查是否有子插件注册了指定前缀。
     */
    public boolean hasRoute(String pathPrefix) {
        return routes.containsKey(normalizePrefix(pathPrefix));
    }

    // ---- HttpHandler 实现 ----

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        // CORS 头
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // 按最长前缀匹配路由
        RouteEntry matched = findMatch(path);
        if (matched != null) {
            try {
                matched.handler.handle(exchange);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "子插件 [" + matched.pluginId + "] 处理请求异常: " + e.getMessage(), e);
                sendText(exchange, 500, "{\"error\":\"子插件处理异常\"}");
            }
        } else {
            // 无匹配 → 返回网关状态页
            serveGatewayPage(exchange);
        }
    }

    // ---- 内部 ----

    private RouteEntry findMatch(String path) {
        // 从最长路径开始匹配（优先匹配 /ks-Eco/admin 在 /ks-Eco 之前）
        RouteEntry best = null;
        int bestLen = 0;
        for (Map.Entry<String, RouteEntry> e : routes.entrySet()) {
            String prefix = e.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + "?")) {
                if (prefix.length() > bestLen) {
                    best = e.getValue();
                    bestLen = prefix.length();
                }
            }
        }
        // 如果无精确匹配，检查是否有 "/" 根路由
        if (best == null && routes.containsKey("/") && path.equals("/")) {
            best = routes.get("/");
        }
        return best;
    }

    private String normalizePrefix(String prefix) {
        String p = prefix.trim();
        if (!p.startsWith("/")) p = "/" + p;
        if (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p;
    }

    private void serveGatewayPage(HttpExchange exchange) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        html.append("<title>ks-core 网关</title>");
        html.append("<style>");
        html.append("body{font-family:'Segoe UI',sans-serif;max-width:800px;margin:40px auto;padding:0 20px;background:#1a1a2e;color:#e0e0e0;}");
        html.append("h1{color:#00d4ff;border-bottom:2px solid #00d4ff33;padding-bottom:10px;}");
        html.append(".card{background:#16213e;border-radius:8px;padding:16px;margin:12px 0;border-left:4px solid #00d4ff;}");
        html.append(".card h3{margin:0 0 8px;color:#00d4ff;}");
        html.append(".card p{margin:4px 0;color:#a0a0b0;}");
        html.append(".badge{display:inline-block;padding:2px 8px;border-radius:4px;font-size:12px;font-weight:bold;}");
        html.append(".badge-ok{background:#00d4ff33;color:#00d4ff;}");
        html.append("</style></head><body>");
        html.append("<h1>ks-core Web 网关</h1>");
        html.append("<p>ks-Series 经济生态系统中枢 — 运行中</p>");

        if (routes.isEmpty()) {
            html.append("<p style=\"color:#ff6b6b;\">暂无已注册的子插件路由。</p>");
        } else {
            html.append("<h2>已注册路由</h2>");
            for (RouteEntry e : routes.values()) {
                html.append("<div class=\"card\">");
                html.append("<h3>").append(e.pathPrefix).append(" <span class=\"badge badge-ok\">ACTIVE</span></h3>");
                html.append("<p>插件: ").append(e.pluginId).append("</p>");
                html.append("</div>");
            }
        }

        html.append("<p style=\"margin-top:30px;color:#666;\">ks-core v").append(plugin.getDescription().getVersion()).append("</p>");
        html.append("</body></html>");

        byte[] bytes = html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---- 内部类 ----

    public static class RouteEntry {
        public final String pluginId;
        public final String pathPrefix;
        public final HttpHandler handler;

        RouteEntry(String pluginId, String pathPrefix, HttpHandler handler) {
            this.pluginId = pluginId;
            this.pathPrefix = pathPrefix;
            this.handler = handler;
        }
    }
}
