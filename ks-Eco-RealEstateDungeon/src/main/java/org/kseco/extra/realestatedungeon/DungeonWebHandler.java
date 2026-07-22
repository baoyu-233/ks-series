package org.kseco.extra.realestatedungeon;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.kscore.KsAuthManager;
import org.kscore.KsPluginBridge;
import org.kseco.KsEco;
import org.kseries.instanceworld.api.InstanceWorldApi;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 副本系统 Web API 处理器。
 *
 * 路由前缀：/ks-Eco/api/realestate-dungeon
 *
 * 端点：
 *   GET    /templates
 *   GET    /instances?status=ACTIVE
 *   GET    /instances/{id}
 *   POST   /instances                  {templateId}
 *   POST   /instances/{id}/leave
 *   GET    /my-properties?instanceId=...
 *   POST   /properties                 {instanceId, x1, z1, x2, z2, propertyFunction, price}
 *   POST   /properties/{id}/develop
 *   POST   /revive                     {instanceId}
 *   GET    /my-status
 *   GET    /config
 *   POST   /config                     {key, value}
 *   GET    /grids
 *   POST   /templates                  (admin)
 *   POST   /templates/{id}/delete      (admin)
 *   POST   /admin/instance/{id}/force-end  (admin)
 */
public final class DungeonWebHandler implements HttpHandler {

    private static final String ROUTE_PREFIX = "/ks-Eco/api/realestate-dungeon";
    private final KsEco eco;
    private final DungeonConfigManager configManager;
    private final DungeonInstanceManager instanceManager;
    private final InstanceWorldApi instanceWorld;
    private final DungeonDeathHandler deathHandler;
    private final Gson gson = new Gson();

    public DungeonWebHandler(KsEco eco, DungeonConfigManager configManager,
                             DungeonInstanceManager instanceManager,
                             InstanceWorldApi instanceWorld,
                             DungeonDeathHandler deathHandler) {
        this.eco = eco;
        this.configManager = configManager;
        this.instanceManager = instanceManager;
        this.instanceWorld = instanceWorld;
        this.deathHandler = deathHandler;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // 提取子路径
            String subPath = path;
            if (subPath.startsWith(ROUTE_PREFIX)) subPath = subPath.substring(ROUTE_PREFIX.length());
            if (subPath.isEmpty()) subPath = "/";
            Map<String, String> qp = KsPluginBridge.parseQuery(query);

            // 路由分发（按优先级：先 /templates POST，再 /templates GET）
            if (subPath.equals("/templates")) {
                if ("POST".equalsIgnoreCase(method)) {
                    KsAuthManager.Session s = requireAdmin(exchange);
                    if (s == null) return;
                    handleTemplateUpsert(exchange);
                } else {
                    handleTemplateList(exchange);
                }
            } else if (subPath.startsWith("/templates/") && subPath.endsWith("/delete") && "POST".equalsIgnoreCase(method)) {
                KsAuthManager.Session s = requireAdmin(exchange);
                if (s == null) return;
                String templateId = subPath.substring("/templates/".length(), subPath.length() - "/delete".length());
                handleTemplateDelete(exchange, templateId);
            } else if (subPath.equals("/instances")) {
                if ("POST".equalsIgnoreCase(method)) {
                    KsAuthManager.Session s = requireOnlinePlayer(exchange);
                    if (s == null) return;
                    handleInstanceCreate(exchange, s);
                } else {
                    handleInstanceList(exchange, qp);
                }
            } else if (subPath.startsWith("/instances/") && subPath.endsWith("/leave") && "POST".equalsIgnoreCase(method)) {
                KsAuthManager.Session s = requireOnlinePlayer(exchange);
                if (s == null) return;
                String instanceId = subPath.substring("/instances/".length(), subPath.length() - "/leave".length());
                handleInstanceLeave(exchange, instanceId, s);
            } else if (subPath.startsWith("/instances/") && subPath.endsWith("/force-end") && "POST".equalsIgnoreCase(method)) {
                KsAuthManager.Session s = requireAdmin(exchange);
                if (s == null) return;
                String instanceId = subPath.substring("/instances/".length(), subPath.length() - "/force-end".length());
                handleForceEnd(exchange, instanceId);
            } else if (subPath.startsWith("/instances/")) {
                String instanceId = subPath.substring("/instances/".length());
                handleInstanceDetail(exchange, instanceId, qp);
            } else if (subPath.equals("/revive") && "POST".equalsIgnoreCase(method)) {
                KsAuthManager.Session s = requireOnlinePlayer(exchange);
                if (s == null) return;
                handleRevive(exchange, s);
            } else if (subPath.equals("/my-status")) {
                KsAuthManager.Session s = requireAuth(exchange);
                if (s == null) return;
                handleMyStatus(exchange, s);
            } else if (subPath.equals("/config")) {
                if ("POST".equalsIgnoreCase(method)) {
                    KsAuthManager.Session s = requireAdmin(exchange);
                    if (s == null) return;
                    handleConfigSet(exchange);
                } else {
                    handleConfigGet(exchange);
                }
            } else if (subPath.equals("/grids")) {
                KsAuthManager.Session s = requireAdmin(exchange);
                if (s == null) return;
                handleGridsList(exchange);
            } else {
                sendJson(exchange, 404, "{\"error\":\"未知路由: " + subPath + "\"}");
            }
        } catch (Exception e) {
            eco.getLogger().warning("[副本系统] Web 请求异常: " + e.getMessage());
            e.printStackTrace();
            sendJson(exchange, 500, "{\"error\":\"服务器内部错误: " + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    // ================================================================
    // Handler 方法
    // ================================================================

    private void handleTemplateList(HttpExchange ex) throws IOException {
        List<Map<String, Object>> templates = instanceManager.listTemplates();
        sendJson(ex, 200, "{\"templates\":" + gson.toJson(templates) + "}");
    }

    private void handleTemplateDelete(HttpExchange ex, String templateId) throws IOException {
        String error = instanceManager.deleteTemplate(templateId);
        if (error == null) {
            sendJson(ex, 200, "{\"message\":\"模板已删除\"}");
        } else {
            sendJson(ex, 400, "{\"error\":\"" + error.replace("\"", "'") + "\"}");
        }
    }

    private void handleTemplateUpsert(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonBody(ex);
        String id = (String) body.get("id");
        String name = (String) body.get("name");
        String difficulty = (String) body.getOrDefault("difficulty", "NORMAL");
        double price = toDouble(body.get("ticketPrice"), configManager.snapshot().ticketDefaultPrice);
        int minP = toInt(body.get("minPlayers"), 1);
        int maxP = toInt(body.get("maxPlayers"), 4);
        int timeMin = toInt(body.get("timeLimitMinutes"), configManager.snapshot().instanceTimeoutMinutes);
        int monsterLevel = toInt(body.get("monsterLevel"), 10);
        String desc = (String) body.getOrDefault("description", "");
        String schematic = (String) body.getOrDefault("schematic", "");
        boolean requirePropertyKey = Boolean.TRUE.equals(body.get("requirePropertyKey"));
        String rewardConfig = (String) body.getOrDefault("rewardConfig", "");
        String newId = instanceManager.upsertTemplate(id, name, difficulty, price, minP, maxP, timeMin,
                monsterLevel, desc, schematic, requirePropertyKey, rewardConfig);
        if (newId == null) {
            sendJson(ex, 500, "{\"error\":\"创建/更新模板失败\"}");
        } else {
            sendJson(ex, 200, "{\"id\":\"" + newId + "\"}");
        }
    }

    private void handleInstanceList(HttpExchange ex, Map<String, String> qp) throws IOException {
        String status = qp.get("status");
        int limit = parseIntOr(qp.get("limit"), 50);
        List<Map<String, Object>> list = instanceManager.listInstances(status, limit);
        sendJson(ex, 200, "{\"instances\":" + gson.toJson(list) + "}");
    }

    private void handleInstanceCreate(HttpExchange ex, KsAuthManager.Session s) throws IOException {
        Map<String, Object> body = readJsonBody(ex);
        String templateId = (String) body.get("templateId");
        if (templateId == null) {
            sendJson(ex, 400, "{\"error\":\"缺少 templateId\"}");
            return;
        }
        if (s.playerUuid == null) {
            sendJson(ex, 400, "{\"error\":\"Session 缺少 playerUuid\"}");
            return;
        }
        String denyMsg = instanceManager.checkPropertyKey(s.playerUuid, templateId);
        if (denyMsg != null) {
            sendJson(ex, 400, "{\"error\":\"" + denyMsg + "\"}");
            return;
        }
        CompletableFuture<String> creation = callOnServerThread(() ->
                instanceManager.createInstanceAsync(templateId, s.playerUuid, s.playerName));
        String instanceId;
        try {
            instanceId = creation.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IOException("等待副本购票结算失败", failure);
        }
        if (instanceId == null) {
            sendJson(ex, 400, "{\"error\":\"购票失败（余额不足/模板不存在/网格已满）\"}");
            return;
        }
        sendJson(ex, 200, "{\"id\":\"" + instanceId + "\"}");
    }

    private void handleInstanceDetail(HttpExchange ex, String instanceId, Map<String, String> qp) throws IOException {
        Map<String, Object> inst = instanceManager.getInstance(instanceId);
        if (inst == null) {
            sendJson(ex, 404, "{\"error\":\"实例不存在\"}");
            return;
        }
        if ("true".equalsIgnoreCase(qp.get("withLogs"))) {
            int logLimit = parseIntOr(qp.get("logLimit"), 50);
            inst.put("logs", instanceManager.listLogs(instanceId, logLimit));
        }
        sendJson(ex, 200, gson.toJson(inst));
    }

    private void handleInstanceLeave(HttpExchange ex, String instanceId, KsAuthManager.Session s) throws IOException {
        if (s.playerUuid == null) {
            sendJson(ex, 400, "{\"error\":\"Session 缺少 playerUuid\"}");
            return;
        }
        boolean ok = callOnServerThread(() -> instanceManager.leaveInstance(instanceId, s.playerUuid));
        if (!ok) {
            sendJson(ex, 400, "{\"error\":\"不在该副本中或已离开\"}");
            return;
        }
        sendJson(ex, 200, "{\"ok\":true}");
    }

    private void handleForceEnd(HttpExchange ex, String instanceId) throws IOException {
        boolean ok = callOnServerThread(() -> instanceManager.forceEnd(instanceId));
        if (!ok) {
            sendJson(ex, 400, "{\"error\":\"实例不存在或已结束\"}");
            return;
        }
        sendJson(ex, 200, "{\"ok\":true}");
    }

    // 注：副本内房产相关端点（/my-properties、/properties、/properties/{id}/develop）已迁出本插件，
    //     现由 ks-Eco-RealEstate 经主 EcoWebHandler 的 /api/realestate/instance-* 提供。

    private void handleRevive(HttpExchange ex, KsAuthManager.Session s) throws IOException {
        Map<String, Object> body = readJsonBody(ex);
        String instanceId = (String) body.get("instanceId");
        if (instanceId == null) {
            sendJson(ex, 400, "{\"error\":\"缺少 instanceId\"}");
            return;
        }
        if (s.playerUuid == null) {
            sendJson(ex, 400, "{\"error\":\"Session 缺少 playerUuid\"}");
            return;
        }
        CompletableFuture<Double> revival = callOnServerThread(() ->
                deathHandler.reviveAsync(instanceId, s.playerUuid));
        double result;
        try {
            result = revival.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IOException("等待付费复活结算失败；请求可安全重试", failure);
        }
        if (result < 0) {
            String msg = switch ((int) result) {
                case (int) DungeonDeathHandler.ERR_LIMIT -> "达到复活上限";
                case (int) DungeonDeathHandler.ERR_VAULT -> "Vault 不可用";
                case (int) DungeonDeathHandler.ERR_BALANCE -> "余额不足";
                case (int) DungeonDeathHandler.ERR_WITHDRAW -> "扣款失败";
                case (int) DungeonDeathHandler.ERR_INSTANCE -> "副本不存在、已结束或不属于你";
                case (int) DungeonDeathHandler.ERR_NOT_DEAD -> "当前状态不可复活";
                case (int) DungeonDeathHandler.ERR_PERSIST -> "复活记录失败，已尝试退款";
                case (int) DungeonDeathHandler.ERR_OFFLINE -> "玩家必须在线才能实际回场";
                default -> "复活失败";
            };
            sendJson(ex, 400, "{\"error\":\"" + msg + "\"}");
            return;
        }
        sendJson(ex, 200, "{\"ok\":true,\"cost\":" + result + "}");
    }

    private void handleMyStatus(HttpExchange ex, KsAuthManager.Session s) throws IOException {
        if (s.playerUuid == null) {
            sendJson(ex, 400, "{\"error\":\"Session 缺少 playerUuid\"}");
            return;
        }
        UUID uuid = s.playerUuid;
        String active = instanceManager.getPlayerActiveInstance(uuid);
        Map<String, Object> resp = new LinkedHashMap<>();
        // 即使没活跃实例也输出字段（让前端明确知道状态）
        resp.put("hasActive", active != null);
        if (active != null) {
            resp.put("activeInstance", active);
            resp.put("participant", instanceManager.getParticipantStatus(active, uuid));
        }
        sendJson(ex, 200, gson.toJson(resp));
    }

    private void handleConfigGet(HttpExchange ex) throws IOException {
        sendJson(ex, 200, gson.toJson(configManager.dumpAll()));
    }

    private void handleConfigSet(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonBody(ex);
        String key = (String) body.get("key");
        Object valObj = body.get("value");
        if (key == null || valObj == null) {
            sendJson(ex, 400, "{\"error\":\"缺少 key 或 value\"}");
            return;
        }
        String err = configManager.updateKey(key, String.valueOf(valObj));
        if (err != null) {
            sendJson(ex, 400, "{\"error\":\"" + err + "\"}");
            return;
        }
        DungeonConfigManager.ReloadMode mode = configManager.modeOf(key);
        sendJson(ex, 200, "{\"ok\":true,\"mode\":\"" + (mode == DungeonConfigManager.ReloadMode.IMMEDIATE ? "IMMEDIATE" : "ON_NEXT_INSTANCE") + "\"}");
    }

    private void handleGridsList(HttpExchange ex) throws IOException {
        List<Map<String, Object>> grids = new ArrayList<>();
        instanceWorld.grids().forEach(grid -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", grid.gridId());
            row.put("world", grid.worldName());
            row.put("gridX", grid.centerX());
            row.put("gridZ", grid.centerZ());
            row.put("status", grid.status());
            row.put("occupiedSince", grid.occupiedSince());
            row.put("lastUsedAt", grid.lastUsedAt());
            grids.add(row);
        });
        sendJson(ex, 200, "{\"grids\":" + gson.toJson(grids) +
                ",\"freeCount\":" + instanceWorld.freeGridCount() +
                ",\"maxGrids\":" + instanceWorld.maxGridCount(configManager.snapshot().gridWorldName) + "}");
    }

    // ================================================================
    // 鉴权工具
    // ================================================================

    private KsAuthManager.Session requireAuth(HttpExchange ex) throws IOException {
        String token = getToken(ex);
        if (token == null) {
            sendJson(ex, 401, "{\"error\":\"缺少认证 token\"}");
            return null;
        }
        KsAuthManager.Session s = eco.bridge().validateToken(token);
        if (s == null) {
            sendJson(ex, 401, "{\"error\":\"Token 无效或已过期\"}");
            return null;
        }
        eco.bridge().touchToken(token);
        return s;
    }

    private <T> T callOnServerThread(Callable<T> action) throws IOException {
        if (Bukkit.isPrimaryThread()) {
            try {
                return action.call();
            } catch (Exception failure) {
                throw new IOException("副本操作失败", failure);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(eco, () -> {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IOException("等待服务器线程处理副本操作失败", failure);
        }
    }

    private KsAuthManager.Session requireAdmin(HttpExchange ex) throws IOException {
        KsAuthManager.Session s = requireAuth(ex);
        if (s == null) return null;
        if (!s.isAdmin) {
            sendJson(ex, 403, "{\"error\":\"需要管理员权限\"}");
            return null;
        }
        return s;
    }

    private KsAuthManager.Session requireOnlinePlayer(HttpExchange ex) throws IOException {
        KsAuthManager.Session s = requireAuth(ex);
        if (s == null) return null;
        if (s.playerUuid == null) {
            sendJson(ex, 400, "{\"error\":\"Session 缺少 playerUuid\"}");
            return null;
        }
        return s;
    }

    private String getToken(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) {
            Map<String, String> p = KsPluginBridge.parseQuery(q);
            String t = p.get("token");
            if (t != null && !t.isEmpty()) return t;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        return null;
    }

    // ================================================================
    // 通用工具
    // ================================================================

    private void sendJson(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        String body = KsPluginBridge.readBody(ex);
        if (body == null || body.isEmpty()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = gson.fromJson(body, Map.class);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static double toDouble(Object o, double dflt) {
        if (o == null) return dflt;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return dflt; }
    }

    private static int toInt(Object o, int dflt) {
        if (o == null) return dflt;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return dflt; }
    }

    private static int parseIntOr(String s, int dflt) {
        if (s == null) return dflt;
        try { return Integer.parseInt(s); } catch (Exception e) { return dflt; }
    }
}
