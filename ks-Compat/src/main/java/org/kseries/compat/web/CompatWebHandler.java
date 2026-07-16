package org.kseries.compat.web;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.kscore.KsAuthManager;
import org.kscore.KsPluginBridge;
import org.kseries.compat.KsCompat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CompatWebHandler implements HttpHandler {

    private final KsCompat plugin;
    private final Gson gson = new Gson();

    public CompatWebHandler(KsCompat plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/admin") || path.endsWith("/admin/")) {
            if (requireAdmin(exchange) == null) return;
            serveAdmin(exchange);
            return;
        }
        if (path.contains("/api/status")) {
            if (requireAdmin(exchange) == null) return;
            sendJson(exchange, 200, snapshot());
            return;
        }
        if (path.contains("/api/fotia/save")) {
            if (requireAdmin(exchange) == null) return;
            plugin.fotiaMoneyDurabilityModule().updateSettingsFromWeb(readJson(exchange));
            sendJson(exchange, 200, Map.of("ok", true));
            return;
        }
        if (path.contains("/api/bots/settings/save")) {
            if (requireAdmin(exchange) == null) return;
            plugin.botManagerModule().updateSettingsFromWeb(readJson(exchange));
            sendJson(exchange, 200, Map.of("ok", true));
            return;
        }
        sendJson(exchange, 404, Map.of("error", "not_found"));
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", plugin.statusSnapshot());
        out.put("fotia", plugin.fotiaMoneyDurabilityModule().settingsView());
        out.put("bots", plugin.botManagerModule().settingsView());
        return out;
    }

    private KsAuthManager.Session requireAdmin(HttpExchange exchange) throws IOException {
        String token = token(exchange);
        KsAuthManager.Session session = plugin.bridge().validateToken(token);
        if (session == null || !session.isAdmin) {
            sendJson(exchange, 401, Map.of("error", "unauthorized"));
            return null;
        }
        plugin.bridge().touchToken(token);
        return session;
    }

    private String token(HttpExchange exchange) {
        Map<String, String> query = KsPluginBridge.parseQuery(exchange.getRequestURI().getRawQuery());
        String token = query.get("token");
        if (token != null && !token.isBlank()) return token;
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring("Bearer ".length()).trim();
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        String body = KsPluginBridge.readBody(exchange);
        if (body == null || body.isBlank()) return new LinkedHashMap<>();
        Object parsed = gson.fromJson(body, Map.class);
        return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private void serveAdmin(HttpExchange exchange) throws IOException {
        try (InputStream in = plugin.getResource("web/admin.html")) {
            if (in == null) {
                sendJson(exchange, 404, Map.of("error", "admin_page_missing"));
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        }
    }

    private void sendJson(HttpExchange exchange, int code, Object data) throws IOException {
        byte[] bytes = gson.toJson(data).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
