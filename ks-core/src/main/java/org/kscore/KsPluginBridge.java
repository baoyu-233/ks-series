package org.kscore;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * KsPluginBridge — 供其他 ks-* 插件使用的 API 桥接。
 *
 * 子插件通过此桥接完成：
 * 1. 注册 Web 路由（向 ks-core 的 Router）
 * 2. Token 鉴权（调用 ks-core 的 AuthManager）
 * 3. 数据存储（获取 ks-core 的 DataStore 连接）
 * 4. 生成 Web 链接（含 Token）
 *
 * 使用方式：
 * <pre>
 *   KsCore core = (KsCore) Bukkit.getPluginManager().getPlugin("ks-core");
 *   KsPluginBridge bridge = core.bridge();
 *   bridge.registerRoute(myPlugin, "/my-path", myHandler);
 *   KsAuthManager.Session session = bridge.validate(token);
 * </pre>
 */
public final class KsPluginBridge {
    private static final int MAX_REQUEST_BODY_BYTES = 1_048_576;

    private final KsCore core;
    private final Gson gson = new Gson();

    public KsPluginBridge(KsCore core) {
        this.core = core;
    }

    // ---- 路由注册 ----

    /**
     * 注册子插件的 Web 路由处理器。
     * @param pluginId  子插件标识（如 "kshwp"、"ks-eco"）
     * @param pathPrefix URL 路径前缀（如 "/kSHWP"、"/ks-Eco"）
     * @param handler   HTTP 请求处理器
     */
    public void registerRoute(String pluginId, String pathPrefix, HttpHandler handler) {
        core.router().register(pluginId, pathPrefix, handler);
    }

    /**
     * 取消注册子插件的路由。
     */
    public void unregisterRoute(String pluginId) {
        core.router().unregister(pluginId);
    }

    // ---- Token 鉴权 ----

    /**
     * 为玩家创建一个 Web 会话 Token。
     * @param player  玩家
     * @param isAdmin 是否为管理员会话
     * @return 生成的 Token 字符串
     */
    public String createToken(Player player, boolean isAdmin) {
        var session = core.authManager().create(
                player.getUniqueId(), player.getName(), isAdmin);
        return session.token;
    }

    /**
     * 通过 UUID + 名称创建会话 Token（无需 Player 对象）。
     * 供子插件在 Web 层（如 test-token）或离线场景使用。
     * @param playerUuid 玩家 UUID
     * @param playerName 玩家名称
     * @param isAdmin    是否为管理员会话
     * @return Session 对象（含 token 字段）
     */
    public KsAuthManager.Session createToken(UUID playerUuid, String playerName, boolean isAdmin) {
        return core.authManager().create(playerUuid, playerName, isAdmin);
    }

    /**
     * 验证 Token 是否有效。
     * @param token Token 字符串
     * @return 会话对象，无效返回 null
     */
    public KsAuthManager.Session validateToken(String token) {
        return core.authManager().validate(token);
    }

    /**
     * 续期 Token。
     */
    public boolean touchToken(String token) {
        return core.authManager().touch(token);
    }

    /**
     * 刷新 Token（旧 Token 失效，返回新 Token）。
     */
    public String refreshToken(String oldToken) {
        var session = core.authManager().refresh(oldToken);
        return session != null ? session.token : null;
    }

    /**
     * 使 Token 失效。
     */
    public void removeToken(String token) {
        core.authManager().remove(token);
    }

    // ---- 便捷方法 ----

    /**
     * 生成子插件的完整 Web 链接（含 Token）。
     * @param player   玩家
     * @param isAdmin  是否管理员
     * @param subPath  子路径（如 "/kSHWP"、"/ks-Eco/admin"）
     * @return 完整 URL
     */
    public String createWebLink(Player player, boolean isAdmin, String subPath) {
        String token = createToken(player, isAdmin);
        String addr = core.ksConfig().getPublicAddress();
        int port = core.ksConfig().getPort();
        String path = subPath.startsWith("/") ? subPath : "/" + subPath;
        return "http://" + addr + ":" + port + path + "?token=" + token;
    }

    // ---- 数据存储 ----

    /**
     * 获取 ks-core 的数据库连接（供子插件存储数据）。
     */
    public java.sql.Connection getDatabaseConnection() {
        return core.dataStore().getConnection();
    }

    // ---- 公告栏 ----

    /**
     * 发布一条公告到 ks-core 公告栏（/announce 公开展示）。
     * @param category 分组类别（GENERAL/SYSTEM/VOTING/LAW/自定义）
     * @param refKey   去重键，非空时按其 upsert（同 key 覆盖）；用于动态可更新公告，传 null 为普通公告
     * @param title    标题
     * @param body     正文
     * @param author   署名
     * @param priority 优先级（越大越靠前）
     * @param expiresAt 过期时间戳（秒），0=永久
     * @return 公告 id，失败 -1
     */
    public long postAnnouncement(String category, String refKey, String title, String body,
                                 String author, int priority, long expiresAt) {
        return core.announcementManager().post(category, refKey, title, body, author, priority, expiresAt);
    }

    /** 按 refKey 撤下公告（用于动态公告，如提案表决结束后撤下）。 */
    public boolean removeAnnouncement(String refKey) {
        return core.announcementManager().removeByRef(refKey);
    }

    /** 读取公告列表（category 为空=全部）。 */
    public java.util.List<java.util.Map<String, Object>> listAnnouncements(String category) {
        return core.announcementManager().list(category);
    }

    // ---- 配置查询 ----

    /**
     * 检查子插件路由是否在 ks-core 配置中启用。
     */
    public boolean isPluginRouteEnabled(String pluginId) {
        return core.ksConfig().isSubPluginEnabled(pluginId);
    }

    /**
     * 获取 Token 超时时间（秒），供子插件参考。
     */
    public int getTokenTimeout() {
        return core.authManager().getTimeoutSeconds();
    }

    /**
     * 获取子插件的路由路径。
     */
    public String getPluginRoute(String pluginId) {
        return core.ksConfig().getSubPluginRoute(pluginId);
    }

    // ---- 工具方法 ----

    /**
     * 解析 HTTP 查询参数。
     */
    public static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return result;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                try {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                    result.put(key, val);
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    /**
     * 读取 HTTP 请求 body。
     */
    public static String readBody(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > MAX_REQUEST_BODY_BYTES) throw new IOException("request body too large");
            } catch (NumberFormatException e) {
                throw new IOException("invalid Content-Length", e);
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_REQUEST_BODY_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BODY_BYTES) throw new IOException("request body too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 发送 JSON 响应。
     */
    public static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 发送 HTML 响应。
     */
    public static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 从 Token 获取在线玩家（已验证）。只能在全局 tick 线程调用；Web/异步
     * 处理器应读取 {@link KsAuthManager.Session#playerUuid}，再通过实体调度器操作玩家。
     */
    @Deprecated(forRemoval = false)
    public Player getPlayerFromToken(String token) {
        var session = validateToken(token);
        if (session == null) return null;
        if (!Bukkit.isGlobalTickThread()) {
            throw new IllegalStateException("getPlayerFromToken must run on the global tick thread");
        }
        return Bukkit.getPlayer(session.playerUuid);
    }

    /**
     * Gson 实例（供子插件使用）。
     */
    public Gson gson() {
        return gson;
    }
}
