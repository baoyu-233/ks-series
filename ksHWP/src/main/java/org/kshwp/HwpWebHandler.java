package org.kshwp;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.kscore.KsAuthManager;
import org.kscore.KsPluginBridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ksHWP Web 地图处理器 — 通过 ks-core 注册到 /kSHWP 路由。
 */
public final class HwpWebHandler implements HttpHandler {

    private final KsHWP plugin;
    private final Gson gson = new Gson();

    public HwpWebHandler(KsHWP plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String subPath = path;
            if (subPath.startsWith("/kSHWP")) subPath = subPath.substring(6);
            if (subPath.isEmpty() || subPath.equals("/")) subPath = "/";

            if (requiresAdmin(subPath)) {
                KsAuthManager.Session session = authenticatedSession(exchange, query);
                if (session == null || !session.isAdmin) {
                    KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"admin token required\"}");
                    return;
                }
            }

            if (subPath.equals("/")) {
                serveMapPage(exchange);
            } else if (subPath.equals("/api/worlds")) {
                handleWorlds(exchange);
            } else if (subPath.equals("/api/tile")) {
                handleTile(exchange, query);
            } else if (subPath.equals("/api/layers")) {
                handleLayersMeta(exchange);
            } else if (subPath.equals("/api/layers/players")) {
                handleLayerPlayers(exchange);
            } else if (subPath.equals("/api/force-render-area")) {
                handleForceRenderArea(exchange, query);
            } else if (subPath.equals("/api/force-render-all")) {
                handleForceRenderAll(exchange, query);
            } else if (subPath.equals("/api/pre-render")) {
                handlePreRender(exchange, query);
            } else if (subPath.equals("/api/hidden/status")) {
                handleHiddenStatus(exchange, query);
            } else if (subPath.equals("/api/cache-stats")) {
                handleCacheStats(exchange);
            } else if (subPath.equals("/api/player-refresh-area")) {
                handlePlayerRefreshArea(exchange, query);
            } else if (subPath.equals("/api/clear-cache")) {
                handleClearCache(exchange, query);
            } else if (subPath.equals("/api/annotations/search")) {
                handleAnnotationSearch(exchange, query);
            } else if (subPath.equals("/api/annotations")) {
                if ("POST".equalsIgnoreCase(method)) handleAnnotationAdd(exchange);
                else if ("DELETE".equalsIgnoreCase(method)) handleAnnotationDelete(exchange, query);
                else handleAnnotations(exchange, query);
            } else if (subPath.equals("/api/annotations/public")) {
                if ("POST".equalsIgnoreCase(method)) handlePublicAnnotationAdd(exchange);
                else if ("DELETE".equalsIgnoreCase(method)) handlePublicAnnotationDelete(exchange, query);
                else handlePublicAnnotations(exchange, query);
            } else if (subPath.equals("/api/whoami")) {
                handleWhoami(exchange, query);
            } else if (subPath.equals("/api/debug")) {
                handleDebug(exchange, query);
            } else if (subPath.equals("/api/players")) {
                handlePlayers(exchange);
            } else if (subPath.equals("/api/batch-render")) {
                handleBatchRender(exchange, query);
            } else if (subPath.equals("/api/xaero/waypoints")) {
                handleXaeroWaypointsExport(exchange, query);
            } else if (subPath.equals("/api/xaero/send-chat-waypoints")) {
                handleXaeroSendChatWaypoints(exchange, query);
            } else {
                KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"未找到\"}");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("地图 Web API 异常: " + e.getMessage());
            try { KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"服务器内部错误\"}"); }
            catch (IOException ignored) {}
        }
    }

    private boolean requiresAdmin(String subPath) {
        return subPath.equals("/api/force-render-area")
                || subPath.equals("/api/force-render-all")
                || subPath.equals("/api/pre-render")
                || subPath.equals("/api/batch-render");
    }

    private KsAuthManager.Session authenticatedSession(HttpExchange exchange, String query) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String token = null;
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) token = auth.substring(7).trim();
        if (token == null || token.isBlank()) token = KsPluginBridge.parseQuery(query).get("token");
        return token == null || token.isBlank() ? null : plugin.bridge().validateToken(token);
    }

    // ---- 页面 ----

    private void serveMapPage(HttpExchange exchange) throws IOException {
        KsPluginBridge.sendHtml(exchange, 200, loadMapHtml());
    }

    private String loadMapHtml() {
        try (InputStream in = plugin.getClass().getResourceAsStream("/web/map.html")) {
            if (in == null) {
                plugin.logError("loadMapHtml", "jar 内缺少 web/map.html 资源", "打包异常，需要重新构建插件");
                return "<html><body>地图页面资源缺失，请联系管理员重新部署插件。</body></html>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.logError("loadMapHtml", "读取 web/map.html 失败", e.getMessage());
            return "<html><body>地图页面加载失败，请联系管理员。</body></html>";
        }
    }

    // ---- API: 图层 ----

    private void handleLayersMeta(HttpExchange exchange) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("layers", plugin.mapRenderer().getLayersMeta());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleLayerPlayers(HttpExchange exchange) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("players", plugin.mapRenderer().getOnlinePlayers());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    // ---- API: 强制渲染 ----

    private void handleForceRenderArea(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String world = params.getOrDefault("world", plugin.hwpConfig().getDefaultWorld());
        int x1 = Integer.parseInt(params.getOrDefault("x1", "0"));
        int z1 = Integer.parseInt(params.getOrDefault("z1", "0"));
        int x2 = Integer.parseInt(params.getOrDefault("x2", "0"));
        int z2 = Integer.parseInt(params.getOrDefault("z2", "0"));

        if (x1 == 0 && z1 == 0 && x2 == 0 && z2 == 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"请提供区域坐标 x1,z1,x2,z2\"}");
            return;
        }

        Map<String, Object> result = plugin.mapRenderer().forceRenderArea(world, x1, z1, x2, z2);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
    }

    private void handleForceRenderAll(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String world = params.getOrDefault("world", plugin.hwpConfig().getDefaultWorld());

        Map<String, Object> result = plugin.mapRenderer().forceRenderAllLoaded(world);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
    }

    // ---- API: 预渲染 ----

    private void handlePreRender(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String world = params.getOrDefault("world", plugin.hwpConfig().getDefaultWorld());
        int cx = Integer.parseInt(params.getOrDefault("cx", "0"));
        int cz = Integer.parseInt(params.getOrDefault("cz", "0"));

        plugin.mapRenderer().preRenderWorld(world, cx, cz);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("message", "预渲染任务已提交");
        resp.put("world", world);
        resp.put("centerChunk", cx + "," + cz);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    // ---- API: 隐藏模式 ----

    private void handleHiddenStatus(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String token = params.get("token");

        Map<String, Object> resp = new LinkedHashMap<>();
        if (token == null || token.isEmpty()) {
            resp.put("hidden", false);
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
            return;
        }

        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session != null) {
            boolean hidden = plugin.isPlayerHidden(session.playerUuid);
            resp.put("hidden", hidden);
            resp.put("playerName", session.playerName);
            resp.put("hasPermission", true); // token 有效即通过
        } else {
            resp.put("hidden", false);
            resp.put("hasPermission", false);
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    // ---- API: 缓存状态 ----

    private void handleCacheStats(HttpExchange exchange) throws IOException {
        Map<String, Object> resp = plugin.mapRenderer().getCacheStats();
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    // ---- API: 玩家触发的区域刷新 ----

    private void handlePlayerRefreshArea(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String token = params.get("token");
        if (token == null || token.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"需要 token（从游戏内 /map 获取链接）\"}");
            return;
        }
        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"token 无效或已过期，请从游戏内重新 /map\"}");
            return;
        }

        String world = params.getOrDefault("world", plugin.hwpConfig().getDefaultWorld());
        int x1 = Integer.parseInt(params.getOrDefault("x1", "0"));
        int z1 = Integer.parseInt(params.getOrDefault("z1", "0"));
        int x2 = Integer.parseInt(params.getOrDefault("x2", "0"));
        int z2 = Integer.parseInt(params.getOrDefault("z2", "0"));

        if (x1 == 0 && z1 == 0 && x2 == 0 && z2 == 0) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"请提供区域坐标 x1,z1,x2,z2\"}");
            return;
        }

        Map<String, Object> result = plugin.mapRenderer().playerRefreshArea(world, x1, z1, x2, z2);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
    }

    // ---- API: 清除缓存（管理员） ----

    private void handleClearCache(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String token = params.get("token");
        if (token == null || token.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"需要管理员 token\"}");
            return;
        }
        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null || !session.isAdmin) {
            KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"仅管理员可清除缓存\"}");
            return;
        }
        String world = params.getOrDefault("world", plugin.hwpConfig().getDefaultWorld());
        Map<String, Object> result = plugin.mapRenderer().clearAllCache(world);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
    }

    // ---- API: 世界 ----

    private void handleWorlds(HttpExchange exchange) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("worlds", plugin.mapRenderer().getWorlds());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleTile(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String world = params.getOrDefault("world", "world");
        int cx = Integer.parseInt(params.getOrDefault("x", "0"));
        int cz = Integer.parseInt(params.getOrDefault("z", "0"));
        int zoom = Integer.parseInt(params.getOrDefault("zoom", "2"));

        // ✔ 异步渲染，不阻塞 HTTP 线程
        plugin.mapRenderer().renderTileAsync(world, cx, cz, zoom).thenAccept(tile -> {
            try {
                if (tile == null) {
                    KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"渲染失败\"}");
                } else {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("tile", tile); resp.put("world", world);
                    resp.put("x", cx); resp.put("z", cz);
                    KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
                }
            } catch (IOException e) {
                // 客户端已断开，忽略
            }
        }).exceptionally(ex -> {
            plugin.logError("tile-render", "异步渲染异常", ex != null ? ex.getMessage() : "unknown");
            try {
                KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"渲染异常: " + (ex != null ? ex.getMessage() : "unknown") + "\"}");
            } catch (IOException ignored) {}
            return null;
        });
    }

    // ---- API: 备注 ----

    private void handleAnnotations(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String token = params.get("token");
        String world = params.getOrDefault("world", "world");

        UUID playerUuid = null;
        if (token != null && !token.isEmpty()) {
            KsAuthManager.Session session = plugin.bridge().validateToken(token);
            if (session != null) playerUuid = session.playerUuid;
        }

        // 获取当前世界的可见备注（公开 + 自己的私有）
        // getWorldAnnotations 已按 playerUuid 过滤，每个备注有正确的 mine 标记
        List<Map<String, Object>> worldAnnos = plugin.annotationManager().getWorldAnnotations(world, playerUuid);

        // 分离出"我的"和"其他人的"
        List<Map<String, Object>> myAnnos = new ArrayList<>();
        List<Map<String, Object>> otherAnnos = new ArrayList<>();
        for (var a : worldAnnos) {
            if (Boolean.TRUE.equals(a.get("mine"))) {
                myAnnos.add(a);
            } else {
                otherAnnos.add(a);
            }
        }

        List<Map<String, Object>> publicAnnos = plugin.annotationManager().getPublicAnnotations(world);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("annotations", otherAnnos);
        resp.put("myAnnotations", myAnnos);
        resp.put("publicAnnotations", publicAnnos);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleAnnotationSearch(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String q = params.get("q");
        String world = params.get("world");
        String type = params.get("type");
        String token = params.get("token");

        UUID playerUuid = null;
        if (token != null && !token.isEmpty()) {
            KsAuthManager.Session session = plugin.bridge().validateToken(token);
            if (session != null) playerUuid = session.playerUuid;
        }

        List<Map<String, Object>> results = plugin.annotationManager().search(q, world, type, playerUuid);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("results", results);
        resp.put("count", results.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    // ---- 公开标注（管理员操作，所有人可见） ----

    private void handlePublicAnnotations(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String world = params.getOrDefault("world", "world");
        List<Map<String, Object>> list = plugin.annotationManager().getPublicAnnotations(world);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("publicAnnotations", list);
        resp.put("count", list.size());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    @SuppressWarnings("unchecked")
    private void handlePublicAnnotationAdd(HttpExchange exchange) throws IOException {
        String body = KsPluginBridge.readBody(exchange);
        Map<String, Object> req = gson.fromJson(body, Map.class);
        String token = (String) req.get("token");
        if (token == null) { KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"需要 token\"}"); return; }

        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null) { KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"token 无效\"}"); return; }
        if (!session.isAdmin) { KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"仅管理员可添加公开标注\"}"); return; }

        String world = (String) req.getOrDefault("world", "world");
        int x = toInt(req.get("x")); int y = toInt(req.get("y")); int z = toInt(req.get("z"));
        Integer x2 = req.containsKey("x2") ? toInt(req.get("x2")) : null;
        Integer z2 = req.containsKey("z2") ? toInt(req.get("z2")) : null;
        String text = (String) req.get("text");
        String type = (String) req.getOrDefault("type", "landmark");
        String detail = (String) req.get("detail");
        String color = (String) req.getOrDefault("color", "#ff4444");

        if (text == null || text.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"标注文本不能为空\"}"); return;
        }

        var anno = plugin.annotationManager().addPublicAnnotation(world, x, y, z, x2, z2, text, type, detail, color);
        if (anno == null) {
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"添加失败\"}");
        } else {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", anno.id()); resp.put("message", "公开标注已添加");
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
        }
    }

    private void handlePublicAnnotationDelete(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String token = params.get("token"); String id = params.get("id");
        if (token == null || id == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少参数\"}"); return; }

        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null) { KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"token 无效\"}"); return; }
        if (!session.isAdmin) { KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"仅管理员可删除公开标注\"}"); return; }

        if (plugin.annotationManager().deletePublicAnnotation(id)) {
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已删除\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"公开标注不存在\"}");
        }
    }

    // ---- 个人标注 ----

    @SuppressWarnings("unchecked")
    private void handleAnnotationAdd(HttpExchange exchange) throws IOException {
        String body = KsPluginBridge.readBody(exchange);
        Map<String, Object> req = gson.fromJson(body, Map.class);
        String token = (String) req.get("token");
        if (token == null) { KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"需要 token\"}"); return; }

        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null) { KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"token 无效\"}"); return; }

        String world = (String) req.getOrDefault("world", "world");
        int x = toInt(req.get("x")); int y = toInt(req.get("y")); int z = toInt(req.get("z"));
        int x2 = toInt(req.get("x2")); int z2 = toInt(req.get("z2"));
        String text = (String) req.get("text");
        String type = (String) req.getOrDefault("type", "note");
        String detail = (String) req.get("detail");
        String color = (String) req.getOrDefault("color", "#ffcc00");

        if (text == null || text.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"备注文本不能为空\"}"); return;
        }

        MapAnnotationManager.Annotation anno = plugin.annotationManager().addArea(
                session.playerUuid, session.playerName,
                world, x, y, z, x2, z2, text, type, detail, color);

        if (anno == null) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"添加失败（可能已达上限）\"}");
        } else {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", anno.id()); resp.put("message", "备注已添加");
            KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
        }
    }

    private void handleAnnotationDelete(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String token = params.get("token"); String id = params.get("id");
        if (token == null || id == null) { KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少参数\"}"); return; }

        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null) { KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"token 无效\"}"); return; }

        if (plugin.annotationManager().delete(id, session.playerUuid)) {
            KsPluginBridge.sendJson(exchange, 200, "{\"message\":\"已删除\"}");
        } else {
            KsPluginBridge.sendJson(exchange, 404, "{\"error\":\"备注不存在或不属于你\"}");
        }
    }

    private void handleBatchRender(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String world = params.getOrDefault("world", "world");
        int cx = Integer.parseInt(params.getOrDefault("cx", "0"));
        int cz = Integer.parseInt(params.getOrDefault("cz", "0"));
        int radius = Math.max(0, Math.min(16, Integer.parseInt(params.getOrDefault("radius", "5"))));
        int zoom = Integer.parseInt(params.getOrDefault("zoom", "2"));

        Map<String, Object> result = plugin.mapRenderer().batchRender(world, cx, cz, radius, zoom);
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(result));
    }

    // ---- API: Xaero 导出 ----

    private void handleXaeroWaypointsExport(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String token = params.get("token");
        String world = params.getOrDefault("world", plugin.hwpConfig().getDefaultWorld());
        if (token == null || token.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"需要 token\"}");
            return;
        }
        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"token 无效\"}");
            return;
        }

        List<Map<String, Object>> mine = plugin.annotationManager().getWorldAnnotations(world, session.playerUuid);
        List<Map<String, Object>> pub = plugin.annotationManager().getPublicAnnotations(world);

        String content = XaeroWaypointExporter.export(mine, pub);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        sendFileDownload(exchange, bytes, "waypoints-" + sanitizeFilename(world) + ".txt", "text/plain; charset=utf-8");
    }

    /**
     * 推荐方式：把玩家可见的标注以 "xaero-waypoint:..." 格式逐条发到玩家聊天框。
     * 玩家客户端装了 Xaero 会自动识别并弹出"添加路径点"提示，无需碰任何本地文件。
     * 要求玩家当前在线（消息直接发到其游戏内聊天）。
     */
    private void handleXaeroSendChatWaypoints(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String token = params.get("token");
        String world = params.getOrDefault("world", plugin.hwpConfig().getDefaultWorld());
        if (token == null || token.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"需要 token\"}");
            return;
        }
        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null) {
            KsPluginBridge.sendJson(exchange, 401, "{\"error\":\"token 无效\"}");
            return;
        }

        Player player = Bukkit.getPlayer(session.playerUuid);
        if (player == null || !player.isOnline()) {
            KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"需要在线才能收到聊天消息，请先进入游戏\"}");
            return;
        }

        World bukkitWorld = Bukkit.getWorld(world);
        World.Environment env = bukkitWorld != null ? bukkitWorld.getEnvironment() : World.Environment.NORMAL;

        List<Map<String, Object>> mine = plugin.annotationManager().getWorldAnnotations(world, session.playerUuid);
        List<Map<String, Object>> pub = plugin.annotationManager().getPublicAnnotations(world);
        List<String> lines = XaeroWaypointExporter.toChatLines(mine, pub, env);

        if (lines.isEmpty()) {
            KsPluginBridge.sendJson(exchange, 200, "{\"count\":0,\"message\":\"该世界暂无可导出的标注\"}");
            return;
        }

        player.sendMessage("§6[ksHWP] §e以下 " + lines.size() + " 条路径点点击即可加入 Xaero 小地图：");
        for (String line : lines) {
            player.sendMessage("§7" + line);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("count", lines.size());
        resp.put("message", "已发送 " + lines.size() + " 条路径点到你的聊天框");
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    /** 发送二进制附件下载响应（Content-Disposition: attachment）。 */
    private void sendFileDownload(HttpExchange exchange, byte[] data, String filename, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    /** 过滤文件名中 Windows 不允许的字符，防止世界名注入导致下载失败。 */
    private String sanitizeFilename(String name) {
        if (name == null) return "world";
        return name.replaceAll("[:*?\"<>|\\\\/]", "_");
    }

    private void handleWhoami(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String token = params.get("token");
        Map<String, Object> resp = new LinkedHashMap<>();
        if (token == null || token.isEmpty()) {
            resp.put("authenticated", false);
        } else {
            KsAuthManager.Session session = plugin.bridge().validateToken(token);
            if (session != null) {
                resp.put("authenticated", true);
                resp.put("playerName", session.playerName);
                resp.put("playerUuid", session.playerUuid.toString());
                resp.put("isAdmin", session.isAdmin);
                resp.put("hidden", plugin.isPlayerHidden(session.playerUuid));
                resp.put("hasHiddenPermission", true); // 能拿到 token 就有基本权限
            } else {
                resp.put("authenticated", false);
            }
        }
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handlePlayers(HttpExchange exchange) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("players", plugin.mapRenderer().getOnlinePlayers());
        KsPluginBridge.sendJson(exchange, 200, gson.toJson(resp));
    }

    // ---- API: 调试 ----

    private void handleDebug(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = KsPluginBridge.parseQuery(query);
        String token = params.get("token");

        // 调试接口需要管理员 token
        boolean isAdmin = false;
        if (token != null && !token.isEmpty()) {
            KsAuthManager.Session session = plugin.bridge().validateToken(token);
            if (session != null && session.isAdmin) isAdmin = true;
        }

        Map<String, Object> debug = plugin.collectDebugInfo();
        debug.put("debugTokenRequired", true);
        debug.put("debugAuthenticated", isAdmin);

        // 附加: 测试各 API 是否正常
        Map<String, Object> apiChecks = new LinkedHashMap<>();
        try {
            var worlds = plugin.mapRenderer().getWorlds();
            apiChecks.put("worldsApi", Map.of("ok", true, "count", worlds.size(), "names",
                    worlds.stream().map(w -> w.get("name")).toList()));
        } catch (Exception e) {
            apiChecks.put("worldsApi", Map.of("ok", false, "error", e.getMessage()));
        }
        try {
            var players = plugin.mapRenderer().getOnlinePlayers();
            apiChecks.put("playersApi", Map.of("ok", true, "count", players.size()));
        } catch (Exception e) {
            apiChecks.put("playersApi", Map.of("ok", false, "error", e.getMessage()));
        }
        try {
            if (plugin.bridge() != null) {
                apiChecks.put("bridge", Map.of("ok", true, "routeEnabled",
                        plugin.bridge().isPluginRouteEnabled("kshwp")));
            } else {
                apiChecks.put("bridge", Map.of("ok", false, "error", "bridge is null"));
            }
        } catch (Exception e) {
            apiChecks.put("bridge", Map.of("ok", false, "error", e.getMessage()));
        }
        try {
            // 测试渲染一个简单 tile
            var world = Bukkit.getWorlds().get(0);
            if (world != null) {
                long t0 = System.currentTimeMillis();
                String tile = plugin.mapRenderer().renderTileAsync(world.getName(), 0, 0, 1).get();
                long elapsed = System.currentTimeMillis() - t0;
                apiChecks.put("renderTest", Map.of("ok", tile != null, "elapsedMs", elapsed,
                        "tileSize", tile != null ? tile.length() : 0));
            } else {
                apiChecks.put("renderTest", Map.of("ok", false, "error", "no worlds loaded"));
            }
        } catch (Exception e) {
            apiChecks.put("renderTest", Map.of("ok", false, "error", e.getMessage()));
        }

        debug.put("apiChecks", apiChecks);

        // 如果是管理员，显示完整信息；否则去除敏感字段
        if (!isAdmin) {
            debug.remove("recentErrors"); // 仅管理员可查看错误详情
        }

        KsPluginBridge.sendJson(exchange, 200, gson.toJson(debug));
    }

    private int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) { try { return Integer.parseInt(s); } catch (Exception ignored) {} }
        return 0;
    }
}
