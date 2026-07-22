package org.kstitle;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.kstitle.model.TitleAttribute;
import org.kstitle.model.TitleDef;
import org.kstitle.model.TitleFrame;

import java.io.*;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * ks-Title Web API + 管理页。Token 鉴权走 ks-core KsAuthManager（反射调用），全部 /api/* 需 admin token。
 * 仿 ks-Sentinel/SentinelWebHandler.java 结构。
 */
public final class TitleWebHandler implements HttpHandler {

    private static final int MAX_REQUEST_BODY_BYTES = 16 * 1024 * 1024;
    private static final int MAX_IA_IMAGE_DIMENSION = 1024;
    private static final long MAX_IA_IMAGE_PIXELS = 1024L * 1024L;
    private static final int MAX_IA_IMAGES_PER_REQUEST = 32;

    private final KsTitle plugin;
    private final Gson gson = new Gson();

    TitleWebHandler(KsTitle plugin) {
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
            if (subPath.startsWith("/ks-Title")) subPath = subPath.substring(9);
            if (subPath.isEmpty()) subPath = "/";

            if (subPath.equals("/") || subPath.equals("/admin")) {
                servePage(exchange);
                return;
            }

            if (!subPath.startsWith("/api/")) {
                sendJson(exchange, 404, Map.of("error", "unknown route"));
                return;
            }

            if (subPath.equals("/api/test-token") && "POST".equalsIgnoreCase(method)) {
                handleTestToken(exchange);
                return;
            }

            String token = extractToken(exchange);
            if (token == null || token.isEmpty()) {
                sendJson(exchange, 401, Map.of("error", "missing Authorization header", "hint", "Use /title web in-game"));
                return;
            }
            Object session = validateToken(token);
            if (session == null) {
                sendJson(exchange, 401, Map.of("error", "invalid or expired token"));
                return;
            }
            if (!isAdminSession(session)) {
                sendJson(exchange, 403, Map.of("error", "admin token required"));
                return;
            }

            route(exchange, subPath, method, query);
        } catch (IllegalArgumentException e) {
            try { sendJson(exchange, 400, Map.of("error", e.getMessage())); } catch (IOException ignored) {}
        } catch (Exception e) {
            try { sendJson(exchange, 500, Map.of("error", e.getMessage())); } catch (IOException ignored) {}
        }
    }

    private void route(HttpExchange exchange, String subPath, String method, String query) throws IOException {
        if (subPath.equals("/api/status") && "GET".equalsIgnoreCase(method)) {
            handleStatus(exchange);
        } else if (subPath.equals("/api/defs") && "GET".equalsIgnoreCase(method)) {
            handleListDefs(exchange);
        } else if (subPath.equals("/api/defs") && "POST".equalsIgnoreCase(method)) {
            handleCreateDef(exchange);
        } else if (subPath.equals("/api/defs/update") && "POST".equalsIgnoreCase(method)) {
            handleUpdateDef(exchange);
        } else if (subPath.equals("/api/defs") && "DELETE".equalsIgnoreCase(method)) {
            handleDeleteDef(exchange, query);
        } else if (subPath.equals("/api/defs/detail") && "GET".equalsIgnoreCase(method)) {
            handleDefDetail(exchange, query);
        } else if (subPath.equals("/api/attrs") && "POST".equalsIgnoreCase(method)) {
            handleAddAttr(exchange);
        } else if (subPath.equals("/api/attrs") && "DELETE".equalsIgnoreCase(method)) {
            handleDeleteAttr(exchange, query);
        } else if (subPath.equals("/api/frames") && "POST".equalsIgnoreCase(method)) {
            handleAddFrame(exchange);
        } else if (subPath.equals("/api/frames") && "DELETE".equalsIgnoreCase(method)) {
            handleDeleteFrame(exchange, query);
        } else if (subPath.equals("/api/frames/all") && "DELETE".equalsIgnoreCase(method)) {
            handleClearFrames(exchange, query);
        } else if (subPath.equals("/api/ownership") && "GET".equalsIgnoreCase(method)) {
            handleOwnership(exchange, query);
        } else if (subPath.equals("/api/grant") && "POST".equalsIgnoreCase(method)) {
            handleGrant(exchange);
        } else if (subPath.equals("/api/revoke") && "POST".equalsIgnoreCase(method)) {
            handleRevoke(exchange);
        } else if (subPath.equals("/api/ia-binding") && "GET".equalsIgnoreCase(method)) {
            handleGetIaBinding(exchange, query);
        } else if (subPath.equals("/api/ia-binding") && "POST".equalsIgnoreCase(method)) {
            handleSetIaBinding(exchange);
        } else if (subPath.equals("/api/ia-binding") && "DELETE".equalsIgnoreCase(method)) {
            handleDeleteIaBinding(exchange, query);
        } else if (subPath.equals("/api/ia-upload") && "POST".equalsIgnoreCase(method)) {
            handleIaUpload(exchange);
        } else {
            sendJson(exchange, 404, Map.of("error", "unknown API route"));
        }
    }

    // ==================== Auth (反射对接 ks-core) ====================

    private String extractToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        var params = parseQuery(exchange.getRequestURI().getRawQuery());
        return params.getOrDefault("token", null);
    }

    private Object validateToken(String token) {
        try {
            var core = Bukkit.getPluginManager().getPlugin("ks-core");
            if (core == null) return null;
            var bridge = core.getClass().getMethod("bridge").invoke(core);
            var m = bridge.getClass().getMethod("validateToken", String.class);
            return m.invoke(bridge, token);
        } catch (Exception e) { return null; }
    }

    private boolean isAdminSession(Object session) {
        try {
            return session.getClass().getField("isAdmin").getBoolean(session);
        } catch (Exception e) { return false; }
    }

    private void handleTestToken(HttpExchange exchange) throws IOException {
        if (!testTokensEnabled()) {
            sendJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        var remote = exchange.getRemoteAddress().getAddress();
        if (!remote.isLoopbackAddress()) {
            sendJson(exchange, 403, Map.of("error", "仅限本地访问 (localhost only)"));
            return;
        }
        try {
            var core = Bukkit.getPluginManager().getPlugin("ks-core");
            if (core == null) { sendJson(exchange, 503, Map.of("error", "ks-core not found")); return; }
            var bridge = core.getClass().getMethod("bridge").invoke(core);
            var session = bridge.getClass().getMethod("createToken", UUID.class, String.class, boolean.class)
                .invoke(bridge, UUID.fromString("00000000-0000-0000-0000-000000000001"), "TEST_ADMIN", true);
            String token = (String) session.getClass().getField("token").get(session);
            sendJson(exchange, 200, Map.of("token", token, "hint", "Authorization: Bearer " + token));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", "Failed to create token: " + e.getMessage()));
        }
    }

    private boolean testTokensEnabled() {
        var core = Bukkit.getPluginManager().getPlugin("ks-core");
        return core != null && core.getConfig().getBoolean("web-gateway.allow-test-token", false);
    }

    // ==================== API handlers ====================

    private void handleStatus(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, Map.of(
            "migrationDone", "1".equals(plugin.titleManager().getMeta("migration_done")),
            "defsCount", plugin.titleManager().countDefs(),
            "ownershipCount", plugin.titleManager().countOwnership(),
            "equippedCount", plugin.titleManager().countEquipped()
        ));
    }

    private void handleListDefs(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TitleDef d : plugin.titleManager().listDefs()) out.add(defToMap(d));
        sendJson(exchange, 200, Map.of("defs", out));
    }

    private void handleDefDetail(HttpExchange exchange, String query) throws IOException {
        int id = (int) parseLong(parseQuery(query).get("id"), -1);
        TitleDef def = plugin.titleManager().getDef(id);
        if (def == null) { sendJson(exchange, 404, Map.of("error", "未找到")); return; }
        List<Map<String, Object>> attrs = new ArrayList<>();
        for (TitleAttribute a : plugin.titleManager().listAttributes(id)) {
            attrs.add(Map.of("id", a.id(), "buffType", a.buffType(), "buffKey", a.buffKey(), "amount", a.amount(), "extra", a.extra()));
        }
        List<Map<String, Object>> frames = new ArrayList<>();
        for (TitleFrame f : plugin.titleManager().listFrames(id)) {
            frames.add(Map.of("id", f.id(), "frameIndex", f.frameIndex(), "frameText", f.frameText(), "intervalMs", f.intervalMs()));
        }
        Map<String, Object> out = new LinkedHashMap<>(defToMap(def));
        out.put("attributes", attrs);
        out.put("frames", frames);
        sendJson(exchange, 200, out);
    }

    private Map<String, Object> defToMap(TitleDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("displayName", d.displayName());
        m.put("description", d.description());
        m.put("category", d.category());
        m.put("rarity", d.rarity());
        m.put("price", d.price());
        m.put("unlockType", d.unlockType());
        m.put("conditionType", d.conditionType());
        m.put("conditionValue", d.conditionValue());
        m.put("visible", d.visible());
        m.put("enabled", d.enabled());
        return m;
    }

    @SuppressWarnings("unchecked")
    private void handleCreateDef(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null || req.get("displayName") == null) {
            sendJson(exchange, 400, Map.of("error", "missing displayName")); return;
        }
        int id = plugin.titleManager().createTitle(
            str(req, "displayName"), str(req, "description"), str(req, "category"), str(req, "rarity"),
            dbl(req, "price"), strOr(req, "unlockType", "ADMIN_GRANT"), str(req, "conditionType"), str(req, "conditionValue"));
        if (id > 0) sendJson(exchange, 200, Map.of("id", id, "message", "创建成功"));
        else sendJson(exchange, 500, Map.of("error", "创建失败"));
    }

    @SuppressWarnings("unchecked")
    private void handleUpdateDef(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null || req.get("id") == null) { sendJson(exchange, 400, Map.of("error", "missing id")); return; }
        int id = (int) dbl(req, "id");
        boolean ok = plugin.titleManager().updateTitle(id,
            str(req, "displayName"), str(req, "description"), str(req, "category"), str(req, "rarity"),
            dbl(req, "price"), strOr(req, "unlockType", "ADMIN_GRANT"), str(req, "conditionType"), str(req, "conditionValue"),
            bool(req, "visible"), bool(req, "enabled"));
        sendJson(exchange, ok ? 200 : 500, ok ? Map.of("message", "更新成功") : Map.of("error", "更新失败"));
    }

    private void handleDeleteDef(HttpExchange exchange, String query) throws IOException {
        int id = (int) parseLong(parseQuery(query).get("id"), -1);
        if (id <= 0) { sendJson(exchange, 400, Map.of("error", "missing id")); return; }
        boolean ok = plugin.titleManager().deleteTitle(id);
        sendJson(exchange, ok ? 200 : 404, ok ? Map.of("message", "已删除") : Map.of("error", "未找到"));
    }

    @SuppressWarnings("unchecked")
    private void handleAddAttr(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null || req.get("titleId") == null) { sendJson(exchange, 400, Map.of("error", "missing titleId")); return; }
        int attrId = plugin.titleManager().addAttribute((int) dbl(req, "titleId"),
            strOr(req, "buffType", "POTION"), str(req, "buffKey"), dbl(req, "amount"), str(req, "extra"));
        sendJson(exchange, attrId > 0 ? 200 : 500, attrId > 0 ? Map.of("id", attrId, "message", "已添加") : Map.of("error", "添加失败"));
    }

    private void handleDeleteAttr(HttpExchange exchange, String query) throws IOException {
        int id = (int) parseLong(parseQuery(query).get("id"), -1);
        boolean ok = plugin.titleManager().removeAttribute(id);
        sendJson(exchange, ok ? 200 : 404, ok ? Map.of("message", "已删除") : Map.of("error", "未找到"));
    }

    @SuppressWarnings("unchecked")
    private void handleAddFrame(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null || req.get("titleId") == null) { sendJson(exchange, 400, Map.of("error", "missing titleId")); return; }
        int titleId = (int) dbl(req, "titleId");
        int frameIndex = plugin.titleManager().listFrames(titleId).size();
        boolean ok = plugin.titleManager().addFrame(titleId, frameIndex, str(req, "frameText"), (int) dbl(req, "intervalMs"));
        sendJson(exchange, ok ? 200 : 500, ok ? Map.of("message", "已添加第 " + frameIndex + " 帧") : Map.of("error", "添加失败"));
    }

    private void handleDeleteFrame(HttpExchange exchange, String query) throws IOException {
        int id = (int) parseLong(parseQuery(query).get("id"), -1);
        boolean ok = plugin.titleManager().removeFrame(id);
        sendJson(exchange, ok ? 200 : 404, ok ? Map.of("message", "已删除") : Map.of("error", "未找到"));
    }

    private void handleClearFrames(HttpExchange exchange, String query) throws IOException {
        int titleId = (int) parseLong(parseQuery(query).get("id"), -1);
        if (titleId <= 0) { sendJson(exchange, 400, Map.of("error", "missing id")); return; }
        plugin.titleManager().clearFrames(titleId);
        sendJson(exchange, 200, Map.of("message", "已清空全部帧"));
    }

    private void handleOwnership(HttpExchange exchange, String query) throws IOException {
        String name = parseQuery(query).get("player");
        if (name == null || name.isBlank()) { sendJson(exchange, 400, Map.of("error", "missing player")); return; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);
        List<Integer> owned = plugin.titleManager().listOwned(target.getUniqueId());
        Integer equipped = plugin.titleManager().getEquipped(target.getUniqueId());
        sendJson(exchange, 200, Map.of("player", name, "uuid", target.getUniqueId().toString(),
            "owned", owned, "equipped", equipped == null ? -1 : equipped));
    }

    @SuppressWarnings("unchecked")
    private void handleGrant(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null || req.get("player") == null || req.get("titleId") == null) {
            sendJson(exchange, 400, Map.of("error", "missing player/titleId")); return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(str(req, "player"));
        boolean ok = plugin.titleManager().grantOwnership(target.getUniqueId(), (int) dbl(req, "titleId"), "ADMIN");
        sendJson(exchange, ok ? 200 : 500, ok ? Map.of("message", "已发放") : Map.of("error", "发放失败(可能已持有)"));
    }

    @SuppressWarnings("unchecked")
    private void handleRevoke(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null || req.get("player") == null || req.get("titleId") == null) {
            sendJson(exchange, 400, Map.of("error", "missing player/titleId")); return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(str(req, "player"));
        boolean ok = plugin.titleManager().revokeOwnership(target.getUniqueId(), (int) dbl(req, "titleId"));
        sendJson(exchange, ok ? 200 : 500, ok ? Map.of("message", "已收回") : Map.of("error", "收回失败(可能未持有)"));
    }

    // ==================== IA 图片动画绑定 ====================

    private void handleGetIaBinding(HttpExchange exchange, String query) throws IOException {
        int id = (int) parseLong(parseQuery(query).get("id"), -1);
        if (id <= 0) { sendJson(exchange, 400, Map.of("error", "missing id")); return; }
        var b = plugin.titleManager().getIaBinding(id);
        if (b == null) { sendJson(exchange, 200, Map.of("bound", false)); return; }
        sendJson(exchange, 200, Map.of("bound", true, "titleId", b.titleId(), "imagePrefix", b.imagePrefix(),
            "frameCount", b.frameCount(), "intervalMs", b.intervalMs(), "chatStatic", b.chatStatic()));
    }

    @SuppressWarnings("unchecked")
    private void handleSetIaBinding(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null || req.get("titleId") == null || req.get("imagePrefix") == null) {
            sendJson(exchange, 400, Map.of("error", "missing titleId/imagePrefix")); return;
        }
        int titleId = (int) dbl(req, "titleId");
        String prefix = sanitizeIaName(str(req, "imagePrefix"));
        if (prefix.isEmpty()) { sendJson(exchange, 400, Map.of("error", "imagePrefix empty")); return; }
        int frameCount = Math.max(1, (int) dbl(req, "frameCount"));
        int intervalMs = Math.max(50, (int) dbl(req, "intervalMs"));
        boolean chatStatic = bool(req, "chatStatic");

        boolean ok = plugin.titleManager().setIaBinding(titleId, prefix, frameCount, intervalMs, chatStatic);
        if (!ok) { sendJson(exchange, 500, Map.of("error", "写入绑定失败")); return; }

        if (chatStatic) {
            TitleDef def = plugin.titleManager().getDef(titleId);
            if (def != null) {
                String staticDisplay = "&c[:" + prefix + "_static:&c]&r";
                plugin.titleManager().updateTitle(titleId, staticDisplay, def.description(), def.category(), def.rarity(),
                    def.price(), def.unlockType(), def.conditionType(), def.conditionValue(), def.visible(), def.enabled());
            }
        }
        plugin.tabIntegration().syncAndReload();
        sendJson(exchange, 200, Map.of("message", "已绑定，TAB 接线已自动更新" + (plugin.tabIntegration().tabInstalled() ? "" : "（未检测到 TAB，跳过接线）")));
    }

    private void handleDeleteIaBinding(HttpExchange exchange, String query) throws IOException {
        int id = (int) parseLong(parseQuery(query).get("id"), -1);
        if (id <= 0) { sendJson(exchange, 400, Map.of("error", "missing id")); return; }
        var b = plugin.titleManager().getIaBinding(id);
        boolean ok = plugin.titleManager().removeIaBinding(id);
        if (b != null) {
            plugin.tabIntegration().removeBindingLine(id);
            // 注意：不删 animations.yml 里的动画块——多个称号可能共用同一套图片前缀
            // （包括迁移前就手工配置好、完全不在 ks_title_ia_bindings 表里的老称号，例如 71/72/73），
            // 曾经因为这里自动删共享动画块，误删了仍在用的 ks_agent_title 导致该称号 tab 列表崩坏。
            // 留着不用的动画块是无害的，删错了共享的却会直接破坏其他称号显示。
            plugin.tabIntegration().reloadTab();
        }
        sendJson(exchange, ok ? 200 : 404, ok ? Map.of("message", "已解绑") : Map.of("error", "未找到绑定"));
    }

    // ==================== IA 图片自动落盘（iazip 仍需手动运行） ====================

    @SuppressWarnings("unchecked")
    private void handleIaUpload(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null || req.get("images") == null) { sendJson(exchange, 400, Map.of("error", "missing images")); return; }
        String packName = strOr(req, "packName", "ks_title_gen");
        String namespace = strOr(req, "namespace", "kstitle");
        int yPosition = req.get("yPosition") != null ? (int) dbl(req, "yPosition") : 8;
        int scaleRatio = req.get("scaleRatio") != null ? (int) dbl(req, "scaleRatio") : 10;

        List<Map<String, Object>> images = (List<Map<String, Object>>) req.get("images");
        if (images == null || images.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "missing images"));
            return;
        }
        if (images.size() > MAX_IA_IMAGES_PER_REQUEST) {
            sendJson(exchange, 400, Map.of("error",
                    "too many images; max " + MAX_IA_IMAGES_PER_REQUEST + " per request"));
            return;
        }
        List<IaImage> decodedImages = new ArrayList<>();
        for (Map<String, Object> img : images) {
            String name = sanitizeIaName(String.valueOf(img.get("name")));
            String base64 = String.valueOf(img.get("base64"));
            if (name.isEmpty()) continue;
            try {
                byte[] bytes = Base64.getDecoder().decode(base64);
                BufferedImage image = readBoundedImage(bytes, name);
                if (image != null) decodedImages.add(new IaImage(name, image));
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, Map.of("error", e.getMessage()));
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("IA图片解码失败 " + name + ": " + e.getMessage());
            }
        }

        Bounds crop = findUnionBounds(decodedImages, 2);
        List<String> names = new ArrayList<>();
        int count = 0;
        for (IaImage img : decodedImages) {
            try {
                byte[] bytes = encodePng(cropAndScale(img.image(), crop, 32));
                plugin.iaFileManager().writeImage(packName, namespace, img.name(), bytes);
                names.add(img.name());
                count++;
            } catch (Exception e) {
                plugin.getLogger().warning("IA图片写入失败 " + img.name() + ": " + e.getMessage());
            }
        }
        try {
            plugin.iaFileManager().ensureConfigEntries(packName, namespace, names, yPosition, scaleRatio);
        } catch (IOException e) {
            sendJson(exchange, 500, Map.of("error", "图片已写入但配置更新失败: " + e.getMessage()));
            return;
        }
        sendJson(exchange, 200, Map.of("message", "已写入 " + count + " 张图片到 ItemsAdder（contents/" + packName + "），运行 /iazip 后生效", "count", count));
    }

    // ==================== Page ====================

    private void servePage(HttpExchange exchange) throws IOException {
        byte[] bytes = readAdminHtml();
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private byte[] readAdminHtml() {
        File dataFile = new File(plugin.getDataFolder(), "web/admin.html");
        if (dataFile.exists()) {
            try { return Files.readAllBytes(dataFile.toPath()); } catch (IOException ignored) {}
        }
        try (InputStream is = plugin.getResource("web/admin.html")) {
            if (is != null) return is.readAllBytes();
        } catch (IOException ignored) {}
        return "<html><body>admin.html missing</body></html>".getBytes(StandardCharsets.UTF_8);
    }

    // ==================== Helpers ====================

    private void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readNBytes(MAX_REQUEST_BODY_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BODY_BYTES) {
                throw new IllegalArgumentException("request body exceeds " + MAX_REQUEST_BODY_BYTES + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) map.put(pair.substring(0, idx),
                java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    private long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String strOr(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }

    private double dbl(Map<String, Object> m, String key) {
        Object v = m.get(key);
        double value;
        if (v instanceof Number n) value = n.doubleValue();
        else {
            try { value = Double.parseDouble(String.valueOf(v)); }
            catch (Exception e) { throw new IllegalArgumentException(key + " must be numeric"); }
        }
        if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite");
        return value;
    }

    private double boundedDouble(Map<String, Object> m, String key, double min, double max) {
        double value = dbl(m, key);
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return value;
    }

    private int intValue(Map<String, Object> m, String key, int min, int max) {
        double value = dbl(m, key);
        if (value != Math.rint(value) || value < min || value > max) {
            throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max);
        }
        return (int) value;
    }

    private boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private String sanitizeIaName(String raw) {
        if (raw == null) return "";
        String safe = raw.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_-]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^[-_]+|[-_]+$", "");
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }

    private record IaImage(String name, BufferedImage image) {}
    private record Bounds(int x, int y, int width, int height) {}

    private Bounds findUnionBounds(List<IaImage> images, int padding) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        int maxWidth = 1, maxHeight = 1;
        for (IaImage img : images) {
            BufferedImage image = img.image();
            maxWidth = Math.max(maxWidth, image.getWidth());
            maxHeight = Math.max(maxHeight, image.getHeight());
            Bounds b = findContentBounds(image);
            if (b == null) continue;
            minX = Math.min(minX, Math.max(0, b.x() - padding));
            minY = Math.min(minY, Math.max(0, b.y() - padding));
            maxX = Math.max(maxX, Math.min(image.getWidth() - 1, b.x() + b.width() - 1 + padding));
            maxY = Math.max(maxY, Math.min(image.getHeight() - 1, b.y() + b.height() - 1 + padding));
        }
        if (maxX < minX || maxY < minY) return new Bounds(0, 0, maxWidth, maxHeight);
        return new Bounds(minX, minY, Math.max(1, maxX - minX + 1), Math.max(1, maxY - minY + 1));
    }

    private BufferedImage readBoundedImage(byte[] bytes, String name) throws IOException {
        try (javax.imageio.stream.ImageInputStream input =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return null;
            Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * (long) height;
                if (width <= 0 || height <= 0
                        || width > MAX_IA_IMAGE_DIMENSION
                        || height > MAX_IA_IMAGE_DIMENSION
                        || pixels > MAX_IA_IMAGE_PIXELS) {
                    throw new IllegalArgumentException(
                            "image too large: " + name + " (" + width + "x" + height
                                    + "); max " + MAX_IA_IMAGE_DIMENSION + "px per side and "
                                    + MAX_IA_IMAGE_PIXELS + " pixels");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private Bounds findContentBounds(BufferedImage image) {
        int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xff;
                if (alpha > 8) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) return null;
        return new Bounds(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private BufferedImage cropAndScale(BufferedImage source, Bounds crop, int outHeight) {
        int outWidth = Math.max(1, Math.round(crop.width() * (outHeight / (float) Math.max(1, crop.height()))));
        BufferedImage cropped = new BufferedImage(crop.width(), crop.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D cg = cropped.createGraphics();
        cg.drawImage(source, -crop.x(), -crop.y(), null);
        cg.dispose();

        BufferedImage out = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(cropped, 0, 0, outWidth, outHeight, null);
        g.dispose();
        return out;
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
