package org.ksinherit;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * ks-Inherit Web API + Admin SPA (1.21.11 + ks-core).
 * Token auth via ks-core KsAuthManager, Chinese enchantment names, attribute/lore display.
 */
public final class InheritWebHandler implements HttpHandler {

    private final KsInherit plugin;
    private final Gson gson = new Gson();

    InheritWebHandler(KsInherit plugin) {
        this.plugin = plugin;
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

            String subPath = path;
            if (subPath.startsWith("/ks-Inherit")) subPath = subPath.substring(11);
            if (subPath.isEmpty() || subPath.equals("/")) subPath = "/";

            // Page routes — no auth needed (SPA handles its own token via API calls)
            if (subPath.equals("/")) {
                servePlayerPage(exchange);
                return;
            }
            if (subPath.equals("/admin")) {
                serveAdminPage(exchange);
                return;
            }

            // API routes — require admin token
            if (!subPath.startsWith("/api/")) {
                sendJson(exchange, 404, Map.of("error", "unknown route"));
                return;
            }

            // Generate test admin token (localhost only)
            if (subPath.equals("/api/test-token") && "POST".equalsIgnoreCase(method)) {
                handleTestToken(exchange);
                return;
            }

            // Routes that need admin auth
            boolean needsAdmin = subPath.equals("/api/items") || subPath.equals("/api/approve")
                || subPath.equals("/api/reject") || subPath.equals("/api/deliver");

            // Auth check for API
            String token = extractToken(exchange);
            if (token == null || token.isEmpty()) {
                sendJson(exchange, 401, Map.of("error", "missing Authorization header", "hint", "Use /inherit token in-game to get your token"));
                return;
            }
            Object session = validateToken(token);
            if (session == null) {
                sendJson(exchange, 401, Map.of("error", "invalid or expired token", "hint", "Use /inherit token in-game to get a new token"));
                return;
            }
            if (needsAdmin && !isAdminSession(session)) {
                sendJson(exchange, 403, Map.of("error", "admin token required"));
                return;
            }

            // Route dispatch
            if (subPath.equals("/api/items") && "GET".equalsIgnoreCase(method)) {
                handleGetItems(exchange, query);
            } else if (subPath.equals("/api/my-items") && "GET".equalsIgnoreCase(method)) {
                handleMyItems(exchange, session);
            } else if (subPath.equals("/api/approve") && "POST".equalsIgnoreCase(method)) {
                handleApprove(exchange, session);
            } else if (subPath.equals("/api/reject") && "POST".equalsIgnoreCase(method)) {
                handleReject(exchange, session);
            } else if (subPath.equals("/api/deliver") && "POST".equalsIgnoreCase(method)) {
                handleDeliver(exchange);
            } else {
                sendJson(exchange, 404, Map.of("error", "unknown API route"));
            }
        } catch (Exception e) {
            try { sendJson(exchange, 500, Map.of("error", e.getMessage())); } catch (IOException ignored) {}
        }
    }

    // ==================== Auth (reflection via ks-core) ====================

    private String extractToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        // Also check query param
        var params = parseQuery(exchange.getRequestURI().getRawQuery());
        return params.getOrDefault("token", null);
    }

    private Object validateToken(String token) {
        try {
            var core = org.bukkit.Bukkit.getPluginManager().getPlugin("ks-core");
            if (core == null) return null;
            var bridge = core.getClass().getMethod("bridge").invoke(core);
            var m = bridge.getClass().getMethod("validateToken", String.class);
            return m.invoke(bridge, token); // returns KsAuthManager.Session or null
        } catch (Exception e) { return null; }
    }

    private boolean isAdminSession(Object session) {
        try {
            return session.getClass().getField("isAdmin").getBoolean(session);
        } catch (Exception e) { return false; }
    }

    // ==================== API handlers ====================

    private void handleGetItems(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        String playerUuid = params.get("playerUuid");
        String status = params.getOrDefault("status", "");

        List<Map<String, Object>> items;
        if (playerUuid != null && !playerUuid.isEmpty()) {
            items = plugin.getPlayerItems(playerUuid.toString());
        } else {
            items = plugin.getAllItems(status.isEmpty() ? null : status);
        }

        // Enrich each item with decoded NBT summary (display name, lore, enchantments, attributes)
        enrichItems(items);

        sendJson(exchange, 200, Map.of("items", items, "count", items.size()));
    }

    @SuppressWarnings("unchecked")
    private void handleApprove(HttpExchange exchange, Object session) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null) { sendJson(exchange, 400, Map.of("error", "invalid body")); return; }
        int itemId = toInt(req.get("id"), -1);
        if (itemId <= 0) { sendJson(exchange, 400, Map.of("error", "missing id")); return; }
        try {
            java.util.UUID ru = (java.util.UUID) session.getClass().getField("playerUuid").get(session);
            String rn = (String) session.getClass().getField("playerName").get(session);
            boolean ok = plugin.approveItem(itemId, ru.toString(), rn != null ? rn : "admin");
            sendJson(exchange, ok ? 200 : 404,
                ok ? Map.of("message", "Item #" + itemId + " approved") : Map.of("error", "not found"));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleReject(HttpExchange exchange, Object session) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null) { sendJson(exchange, 400, Map.of("error", "invalid body")); return; }
        int itemId = toInt(req.get("id"), -1);
        if (itemId <= 0) { sendJson(exchange, 400, Map.of("error", "missing id")); return; }
        try {
            java.util.UUID ru = (java.util.UUID) session.getClass().getField("playerUuid").get(session);
            String rn = (String) session.getClass().getField("playerName").get(session);
            boolean ok = plugin.rejectItem(itemId, ru.toString(), rn != null ? rn : "admin");
            sendJson(exchange, ok ? 200 : 404,
                ok ? Map.of("message", "Item #" + itemId + " rejected") : Map.of("error", "not found"));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleDeliver(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null) { sendJson(exchange, 400, Map.of("error", "invalid body")); return; }
        int itemId = toInt(req.get("id"), -1);
        if (itemId <= 0) { sendJson(exchange, 400, Map.of("error", "missing id")); return; }
        String result = plugin.deliverItem(itemId);
        if ("OK".equals(result)) {
            sendJson(exchange, 200, Map.of("message", "Item #" + itemId + " delivered"));
        } else {
            sendJson(exchange, 400, Map.of("error", result));
        }
    }

    /** Player's own items — any valid token (non-admin allowed). */
    @SuppressWarnings("unchecked")
    private void handleMyItems(HttpExchange exchange, Object session) throws IOException {
        try {
            java.util.UUID playerUuid = (java.util.UUID) session.getClass().getField("playerUuid").get(session);
            List<Map<String, Object>> items = plugin.getPlayerItems(playerUuid.toString());
            enrichItems(items);
            sendJson(exchange, 200, Map.of("items", items, "count", items.size()));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    /** Extract NBT details (display name, lore, enchantments, attributes) from Base64 item_json. */
    @SuppressWarnings("unchecked")
    private void enrichItems(List<Map<String, Object>> items) {
        for (var item : items) {
            try {
                String b64 = (String) item.get("item_json");
                if (b64 == null || b64.isEmpty()) continue;
                byte[] data = java.util.Base64.getDecoder().decode(b64);
                var stack = org.bukkit.inventory.ItemStack.deserializeBytes(data);
                var meta = stack.getItemMeta();
                if (meta.hasDisplayName()) {
                    item.put("display_name_plain", meta.getDisplayName());
                }
                if (meta.hasLore() && meta.lore() != null) {
                    List<String> loreLines = new ArrayList<>();
                    for (var c : meta.lore()) {
                        loreLines.add(plainText(c));
                    }
                    item.put("lore_lines", loreLines);
                }
                if (meta.hasEnchants()) {
                    Map<String, Object> enchInfo = new LinkedHashMap<>();
                    for (var e : meta.getEnchants().entrySet()) {
                        enchInfo.put(
                            e.getKey().getKey().getKey(),
                            Map.of("level", e.getValue(), "name", trEnchant(e.getKey().getKey().getKey()))
                        );
                    }
                    item.put("enchants_detail", enchInfo);
                }
                if (meta.hasAttributeModifiers()) {
                    List<Map<String, Object>> attrList = new ArrayList<>();
                    for (var attr : meta.getAttributeModifiers().entries()) {
                        Map<String, Object> ai = new LinkedHashMap<>();
                        ai.put("attribute", attr.getKey().getKey().getKey());
                        ai.put("name", trAttribute(attr.getKey().getKey().getKey()));
                        ai.put("amount", attr.getValue().getAmount());
                        ai.put("operation", attr.getValue().getOperation().name());
                        ai.put("slot", attr.getValue().getSlot() != null ? attr.getValue().getSlot().name() : "ANY");
                        attrList.add(ai);
                    }
                    item.put("attributes", attrList);
                }
                item.put("unbreakable", meta.isUnbreakable());
                if (meta.hasCustomModelData()) {
                    item.put("custom_model_data", meta.getCustomModelData());
                }
            } catch (Exception ignored) {}
        }
    }

    // ==================== Enchant / Attribute translation ====================

    private static String trEnchant(String key) {
        return ENCH_CN.getOrDefault(key, key);
    }

    private static String trAttribute(String key) {
        return ATTR_CN.getOrDefault(key, key);
    }

    private static String plainText(net.kyori.adventure.text.Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }

    // ==================== Admin page ====================

    private void serveAdminPage(HttpExchange exchange) throws IOException {
        byte[] bytes = readAdminHtml();
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private byte[] readAdminHtml() {
        // Try plugin data folder first (user-modified), then fallback to resources
        File dataFile = new File(plugin.getDataFolder(), "web/admin.html");
        if (dataFile.exists()) {
            try { return Files.readAllBytes(dataFile.toPath()); } catch (IOException ignored) {}
        }
        // Fallback to jar resource
        try (InputStream is = plugin.getResource("web/admin.html")) {
            if (is != null) return is.readAllBytes();
        } catch (IOException ignored) {}
        // Last resort: embedded minimal page
        return "<html><body><h1>ks-Inherit</h1><p>Admin page not found.</p></body></html>".getBytes(StandardCharsets.UTF_8);
    }

    // ==================== Player page ====================

    private void servePlayerPage(HttpExchange exchange) throws IOException {
        byte[] bytes = readPlayerHtml();
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private byte[] readPlayerHtml() {
        // Try plugin data folder first (user-modified), then fallback to resources
        File dataFile = new File(plugin.getDataFolder(), "web/player.html");
        if (dataFile.exists()) {
            try { return Files.readAllBytes(dataFile.toPath()); } catch (IOException ignored) {}
        }
        // Fallback to jar resource
        try (InputStream is = plugin.getResource("web/player.html")) {
            if (is != null) return is.readAllBytes();
        } catch (IOException ignored) {}
        // Last resort: embedded inline player page
        return PLAYER_HTML.getBytes(StandardCharsets.UTF_8);
    }

    // ==================== Token generation (localhost only) ====================

    private void handleTestToken(HttpExchange exchange) throws IOException {
        if (!testTokensEnabled()) {
            sendJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        // Security: only allow localhost to create test tokens
        var remote = exchange.getRemoteAddress().getAddress();
        if (!remote.isLoopbackAddress()) {
            sendJson(exchange, 403, Map.of("error", "仅限本地访问 (localhost only)"));
            return;
        }
        try {
            var core = org.bukkit.Bukkit.getPluginManager().getPlugin("ks-core");
            if (core == null) {
                sendJson(exchange, 503, Map.of("error", "ks-core not found"));
                return;
            }
            var bridge = core.getClass().getMethod("bridge").invoke(core);
            var session = bridge.getClass().getMethod("createToken",
                    java.util.UUID.class, String.class, boolean.class)
                    .invoke(bridge,
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "TEST_ADMIN", true);
            // Session.token is a public final field, not a getter method
            String token = (String) session.getClass().getField("token").get(session);
            sendJson(exchange, 200, Map.of(
                "token", token,
                "isAdmin", true,
                "playerName", "TEST_ADMIN",
                "hint", "Usage: Authorization: Bearer " + token
            ));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", "Failed to create token: " + e.getMessage()));
        }
    }

    private boolean testTokensEnabled() {
        var core = org.bukkit.Bukkit.getPluginManager().getPlugin("ks-core");
        return core != null && core.getConfig().getBoolean("web-gateway.allow-test-token", false);
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
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
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

    private int toInt(Object value, int def) {
        if (value instanceof Number n) return n.intValue();
        if (value != null) try { return Integer.parseInt(value.toString()); } catch (NumberFormatException ignored) {}
        return def;
    }

    // ==================== Translation maps ====================

    private static final Map<String, String> ENCH_CN = Map.ofEntries(
        Map.entry("sharpness", "锋利"), Map.entry("smite", "亡灵杀手"), Map.entry("bane_of_arthropods", "节肢杀手"),
        Map.entry("knockback", "击退"), Map.entry("fire_aspect", "火焰附加"), Map.entry("looting", "抢夺"),
        Map.entry("sweeping_edge", "横扫之刃"), Map.entry("efficiency", "效率"), Map.entry("silk_touch", "精准采集"),
        Map.entry("fortune", "时运"), Map.entry("power", "力量"), Map.entry("punch", "冲击"),
        Map.entry("flame", "火矢"), Map.entry("infinity", "无限"), Map.entry("protection", "保护"),
        Map.entry("fire_protection", "火焰保护"), Map.entry("feather_falling", "摔落保护"),
        Map.entry("blast_protection", "爆炸保护"), Map.entry("projectile_protection", "弹射物保护"),
        Map.entry("respiration", "水下呼吸"), Map.entry("aqua_affinity", "水下速掘"),
        Map.entry("thorns", "荆棘"), Map.entry("depth_strider", "深海探索者"),
        Map.entry("frost_walker", "冰霜行者"), Map.entry("binding_curse", "绑定诅咒"),
        Map.entry("vanishing_curse", "消失诅咒"), Map.entry("soul_speed", "灵魂疾行"),
        Map.entry("swift_sneak", "潜行加速"), Map.entry("piercing", "穿透"),
        Map.entry("multishot", "多重射击"), Map.entry("quick_charge", "快速装填"),
        Map.entry("loyalty", "忠诚"), Map.entry("channeling", "引雷"), Map.entry("riptide", "激流"),
        Map.entry("impaling", "穿刺"), Map.entry("luck_of_the_sea", "海之眷顾"), Map.entry("lure", "钓饵"),
        Map.entry("mending", "经验修补"), Map.entry("unbreaking", "耐久"),
        Map.entry("density", "密度"), Map.entry("breach", "破甲"), Map.entry("wind_burst", "风爆")
    );

    private static final Map<String, String> ATTR_CN = Map.ofEntries(
        Map.entry("generic.attack_damage", "攻击伤害"),
        Map.entry("generic.attack_speed", "攻击速度"),
        Map.entry("generic.movement_speed", "移动速度"),
        Map.entry("generic.max_health", "最大生命值"),
        Map.entry("generic.armor", "护甲值"),
        Map.entry("generic.armor_toughness", "护甲韧性"),
        Map.entry("generic.knockback_resistance", "击退抗性"),
        Map.entry("generic.luck", "幸运"),
        Map.entry("attack_damage", "攻击伤害"),
        Map.entry("attack_speed", "攻击速度"),
        Map.entry("movement_speed", "移动速度"),
        Map.entry("max_health", "最大生命值"),
        Map.entry("armor", "护甲值"),
        Map.entry("armor_toughness", "护甲韧性"),
        Map.entry("knockback_resistance", "击退抗性"),
        Map.entry("luck", "幸运")
    );

    private static final Map<String, String> SLOT_CN = Map.of(
        "HAND", "主手", "MAINHAND", "主手", "OFF_HAND", "副手", "OFFHAND", "副手",
        "FEET", "靴子", "LEGS", "护腿", "CHEST", "胸甲", "HEAD", "头盔", "BODY", "身体",
        "ANY", "任意"
    );

    // ==================== Inline HTML ====================

    private static final String ADMIN_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>ks-Inherit 物品继承管理</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Segoe UI',system-ui,sans-serif;background:#0d1117;color:#c9d1d9;padding:20px}
h1{color:#58a6ff;font-size:20px;margin-bottom:4px}
.sub{color:#8b949e;font-size:13px;margin-bottom:16px}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px;margin-bottom:16px}
.card h3{color:#58a6ff;margin-bottom:10px;font-size:15px}
.filters{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:12px}
input,select,button{padding:6px 12px;background:#21262d;border:1px solid #30363d;border-radius:6px;color:#c9d1d9;font-size:13px}
button{cursor:pointer;background:#238636;border-color:#2ea043;color:#fff;font-weight:600}
button:hover{background:#2ea043}
button.danger{background:#da3633;border-color:#f85149}
button.danger:hover{background:#f85149}
button.warn{background:#9e6a03;border-color:#bb8009}
button.warn:hover{background:#bb8009}
button.fetch-token{background:#9e6a03;border-color:#bb8009}
button.fetch-token:hover{background:#bb8009}
button.small{padding:2px 8px;font-size:11px}
table{width:100%;border-collapse:collapse;font-size:12px}
th,td{padding:8px 10px;text-align:left;border-bottom:1px solid #21262d;vertical-align:top}
th{background:#0d1117;color:#8b949e;font-weight:600;position:sticky;top:0}
tr:hover{background:#1c2128}
.badge{display:inline-block;padding:2px 8px;border-radius:12px;font-size:11px;font-weight:600}
.badge-pending{background:#9e6a03;color:#fff}
.badge-approved{background:#1f6feb;color:#fff}
.badge-rejected{background:#da3633;color:#fff}
.badge-delivered{background:#238636;color:#fff}
.ench-tag{display:inline-block;background:#1f6feb33;color:#58a6ff;padding:1px 6px;border-radius:4px;margin:2px;font-size:11px}
.attr-row{color:#8b949e;font-size:11px;margin:1px 0}
.attr-val{color:#ff7b72;font-weight:600}
.lore-line{color:#c9d1d9;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:250px}
.toast{position:fixed;top:12px;right:12px;padding:10px 20px;border-radius:6px;font-size:13px;z-index:999;animation:fadeIn .3s}
.toast-ok{background:#238636;color:#fff}
.toast-err{background:#da3633;color:#fff}
@keyframes fadeIn{from{opacity:0;transform:translateY(-10px)}to{opacity:1;transform:translateY(0)}}
.detail-row{cursor:pointer}
.detail-expand{display:none;background:#0d1117;border:1px solid #30363d;border-radius:6px;padding:10px;margin-top:4px}
.token-row{margin-bottom:10px;display:flex;gap:8px;align-items:center}
</style>
</head>
<body>
<h1>📦 ks-Inherit 物品继承管理</h1>
<p class="sub">审阅 → 批准 → 发放到玩家背包 (OpenInv)</p>

<div class="card token-row">
<label style="font-size:12px;color:#8b949e">Admin Token:</label>
<input id="authToken" placeholder="Bearer token or ?token= param" style="width:300px;font-family:monospace;font-size:11px">
<button onclick="setToken()">🔑 设置</button>
<button onclick="fetchTestToken()" class="fetch-token">🎫 获取Token</button>
<span id="authStatus" style="font-size:11px;color:#8b949e;"></span>
</div>

<div class="card">
<div class="filters">
<select id="statusFilter" onchange="loadItems()">
<option value="">全部状态</option><option value="PENDING" selected>待审</option>
<option value="APPROVED">已批准</option><option value="REJECTED">已拒绝</option><option value="DELIVERED">已发放</option>
</select>
<input id="playerUuid" placeholder="玩家UUID (可选)" style="width:260px">
<button onclick="loadItems()">🔍 查询</button>
<button class="danger" onclick="batchReject()" style="margin-left:auto">拒绝所选</button>
<button onclick="batchApprove()">批准所选</button>
<button class="warn" onclick="batchDeliver()">发放所选</button>
</div>
</div>

<div class="card" style="max-height:70vh;overflow-y:auto">
<table><thead><tr>
<th style="width:30px"><input type="checkbox" id="selectAll" onchange="toggleSelectAll()"></th>
<th>ID</th><th>玩家</th><th>物品</th><th>详情</th><th>状态</th><th>操作</th>
</tr></thead><tbody id="itemsBody"></tbody></table>
<div id="stats" style="margin-top:8px;color:#8b949e;font-size:12px"></div>
</div>

<script>
var allItems=[],selected=new Set(),_token='',_tokenFail=false;

// Try to get token from page URL
(function(){
 var m=location.search.match(/[?&]token=([^&]+)/);
 if(m){_token=m[1];document.getElementById('authToken').value=_token;document.getElementById('authStatus').textContent='✅ from URL';}
})();

function setToken(){
 _token=document.getElementById('authToken').value.trim();
 _tokenFail=false;
 document.getElementById('authStatus').textContent=_token?'✅ 已设置':'';
 loadItems();
}

async function fetchTestToken(){
 var r=await fetch('/ks-Inherit/api/test-token',{method:'POST'});
 var d=await r.json();
 if(d.token){
   document.getElementById('authToken').value=d.token;
   setToken();
   toast('✅ Token 已获取（仅本地有效）','ok');
 }else{
   toast('❌ '+d.error,'err');
 }
}

async function api(method,url,body){
 var opts={method:method,headers:{'Content-Type':'application/json'}};
 if(_token)opts.headers['Authorization']='Bearer '+_token;
 if(body)opts.body=JSON.stringify(body);
 var r=await fetch(url,opts);
 if(r.status===401){_tokenFail=true;return{error:'token 无效或过期，请重新获取'};}
 return r.json();
}

async function loadItems(){
 if(_tokenFail){document.getElementById('itemsBody').innerHTML='<tr><td colspan="7" style="color:#f85149;text-align:center">Token 无效！请点击上方 <b>🎫 获取Token</b> 按钮重新获取</td></tr>';return;}
 var s=document.getElementById('statusFilter').value;
 var p=document.getElementById('playerUuid').value.trim();
 var ps=new URLSearchParams();
 if(s)ps.set('status',s);
 if(p)ps.set('playerUuid',p);
 var d=await api('GET','/ks-Inherit/api/items?'+ps.toString());
 if(_tokenFail){loadItems();return;}
 allItems=d.items||[];
 selected.clear();
 render();
 document.getElementById('stats').textContent='共 '+d.count+' 条';
}

function render(){
 var tb=document.getElementById('itemsBody'),h='';
 (allItems||[]).forEach(function(item,idx){
   var id='item_'+item.id;
   var badge='badge-'+(item.status||'PENDING').toLowerCase();

   h+='<tr class="detail-row" onclick="toggleDetail(\\''+id+'\\')">';
   h+='<td><input type="checkbox" '+(selected.has(item.id)?'checked':'')+' onclick="event.stopPropagation();toggleSelect('+item.id+')"></td>';
   h+='<td>'+item.id+'</td>';
   h+='<td><span title="'+he(item.player_uuid||'')+'">'+he(item.player_name||'?')+'</span></td>';
   h+='<td>'+he(item.display_name_plain||item.item_name||item.item_type||'?')+'</td>';
   h+='<td style="font-size:11px">'+summaryHtml(item)+'</td>';
   h+='<td><span class="badge '+badge+'">'+item.status+'</span></td>';
   h+='<td onclick="event.stopPropagation()">'+actionBtns(item)+'</td>';
   h+='</tr>';

   // Expanded detail row
   h+='<tr class="detail-expand" id="'+id+'"><td colspan="7">'+detailHtml(item)+'</td></tr>';
 });
 tb.innerHTML=h||'<tr><td colspan="7" style="color:#666;text-align:center">暂无记录</td></tr>';
}

function summaryHtml(item){
 var p=[];
 // Enchantment tags
 if(item.enchants_detail){
   var en=item.enchants_detail;
   Object.entries(en).slice(0,4).forEach(function(e){p.push('<span class="ench-tag">'+e[1].name+' '+e[1].level+'</span>');});
   if(Object.keys(en).length>4)p.push('<span class="ench-tag">...+'+(Object.keys(en).length-4)+'</span>');
 }
 // Attribute count
 if(item.attributes&&item.attributes.length){
   p.push(' <span style="color:#ff7b72">⚔'+item.attributes.length+'项属性</span>');
 }
 // Lore preview
 if(item.lore_lines&&item.lore_lines.length){
   p.push(' <span style="color:#8b949e">💬'+item.lore_lines.length+'行</span>');
 }
 if(item.unbreakable) p.push(' <span style="color:#d2a8ff">🔒</span>');
 if(item.custom_model_data) p.push(' <span style="color:#ffa657">CMD:'+item.custom_model_data+'</span>');
 return p.join(' ')||'<span style="color:#666">-</span>';
}

function detailHtml(item){
 var h='<div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">';

 // Left: enchantments
 h+='<div><b style="color:#58a6ff">附魔</b>';
 if(item.enchants_detail){
   h+='<div style="margin-top:4px">';
   Object.entries(item.enchants_detail).forEach(function(e){
     h+='<span class="ench-tag">'+e[1].name+' <b>'+e[1].level+'</b></span> ';
   });
   h+='</div>';
 }else{h+='<div style="color:#666">无</div>';}
 h+='</div>';

 // Right: attributes
 h+='<div><b style="color:#ff7b72">主手/装备属性</b>';
 if(item.attributes&&item.attributes.length){
   h+='<div style="margin-top:4px">';
   item.attributes.forEach(function(a){
     var opName=a.operation==='ADD_NUMBER'?'+':(a.operation==='MULTIPLY_SCALAR_1'?'×':'');
     var slot=SLOT_CN[a.slot]||a.slot||'任意';
     h+='<div class="attr-row">'+a.name+' <span class="attr-val">'+opName+String(a.amount).replace(/^-/,'-').replace(/^[^+-]/,'+')+'</span> <span style="color:#666">['+slot+']</span></div>';
   });
   h+='</div>';
 }else{h+='<div style="color:#666">无</div>';}
 h+='</div>';

 // Bottom full width: lore
 h+='<div style="grid-column:1/-1"><b style="color:#c9d1d9">物品 Lore</b>';
 if(item.lore_lines&&item.lore_lines.length){
   h+='<div style="margin-top:4px">';
   item.lore_lines.forEach(function(l){h+='<div class="lore-line">'+he(l)+'</div>';});
   h+='</div>';
 }else{h+='<div style="color:#666">无</div>';}
 h+='</div>';

 // Other info
 h+='<div style="grid-column:1/-1;font-size:11px;color:#666">';
 if(item.unbreakable) h+='🔒 不可破坏 | ';
 if(item.custom_model_data) h+='CustomModelData: '+item.custom_model_data+' | ';
 h+='Item Type: '+item.item_type;
 h+='</div>';

 h+='</div>';
 return h;
}

var SLOT_CN={"HAND":"主手","MAINHAND":"主手","OFF_HAND":"副手","OFFHAND":"副手","FEET":"靴子","LEGS":"护腿","CHEST":"胸甲","HEAD":"头盔","BODY":"身体","ANY":"任意"};

function actionBtns(item){
 var s='';
 if(item.status==='PENDING'){
   s+='<button class="small" onclick="approveOne('+item.id+')">批准</button> ';
   s+='<button class="small danger" onclick="rejectOne('+item.id+')">拒绝</button>';
 }else if(item.status==='APPROVED'){
   s+='<button class="small warn" onclick="deliverOne('+item.id+')">发放</button>';
 }else{s+='<span style="color:#666">-</span>';}
 return s;
}

function toggleDetail(id){
 var el=document.getElementById(id);
 el.style.display=el.style.display==='table-row'?'none':'table-row';
}

function toggleSelect(id){if(selected.has(id))selected.delete(id);else selected.add(id);render();}
function toggleSelectAll(){
 if(document.getElementById('selectAll').checked){allItems.forEach(function(i){selected.add(i.id)});}
 else{selected.clear();}
 render();
}
function he(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
function toast(m,c){var e=document.createElement('div');e.className='toast toast-'+(c||'ok');e.textContent=m;document.body.appendChild(e);setTimeout(function(){e.remove()},3000)}

async function approveOne(id){
 var d=await api('POST','/ks-Inherit/api/approve',{id:id});
 if(d.message){toast(d.message,'ok');loadItems()}else toast(d.error||'err','err')
}
async function rejectOne(id){
 var d=await api('POST','/ks-Inherit/api/reject',{id:id});
 if(d.message){toast(d.message,'ok');loadItems()}else toast(d.error||'err','err')
}
async function deliverOne(id){
 var d=await api('POST','/ks-Inherit/api/deliver',{id:id});
 if(d.message){toast(d.message,'ok');loadItems()}else toast(d.error||'err','err')
}
async function batchApprove(){await batch('PENDING',approveOne)}
async function batchReject(){await batch('',rejectOne)}
async function batchDeliver(){await batch('APPROVED',deliverOne)}
async function batch(status,fn){
 var ids=Array.from(selected).filter(function(id){
   var it=allItems.find(function(i){return i.id===id});
   return it&&(status===''||it.status===status);
 });
 if(!ids.length){toast('请选择物品','err');return}
 for(var i=0;i<ids.length;i++)await fn(ids[i]);
}
loadItems();
</script>
</body>
</html>
""";
    // Inline player page: view own items, no admin actions
    private static final String PLAYER_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>ks-Inherit 物品继承</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Segoe UI',system-ui,sans-serif;background:#0d1117;color:#c9d1d9;padding:20px}
h1{color:#58a6ff;font-size:20px;margin-bottom:4px}
.sub{color:#8b949e;font-size:13px;margin-bottom:16px}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px;margin-bottom:16px}
.badge{display:inline-block;padding:2px 8px;border-radius:12px;font-size:11px;font-weight:600}
.badge-pending{background:#9e6a03;color:#fff}
.badge-approved{background:#1f6feb;color:#fff}
.badge-rejected{background:#da3633;color:#fff}
.badge-delivered{background:#238636;color:#fff}
.ench-tag{display:inline-block;background:#1f6feb33;color:#58a6ff;padding:1px 6px;border-radius:4px;margin:2px;font-size:11px}
.lore-line{color:#c9d1d9;font-size:11px;max-width:250px}
.detail-row{cursor:pointer}
.detail-expand{display:none;background:#0d1117;border:1px solid #30363d;border-radius:6px;padding:10px;margin-top:4px}
table{width:100%;border-collapse:collapse;font-size:12px}
th,td{padding:8px 10px;text-align:left;border-bottom:1px solid #21262d;vertical-align:top}
th{background:#0d1117;color:#8b949e;font-weight:600}
.toast{position:fixed;top:12px;right:12px;padding:10px 20px;border-radius:6px;font-size:13px;z-index:999;animation:fadeIn .3s}
.toast-ok{background:#238636;color:#fff}
.toast-err{background:#da3633;color:#fff}
@keyframes fadeIn{from{opacity:0;transform:translateY(-10px)}to{opacity:1;transform:translateY(0)}}
</style>
</head>
<body>
<h1>📦 ks-Inherit 我的物品</h1>
<p class="sub">查看已提交的跨版本物品继承状态</p>

<div class="card" style="max-height:75vh;overflow-y:auto">
<table><thead><tr>
<th>ID</th><th>物品</th><th>详情</th><th>状态</th><th>提交时间</th>
</tr></thead><tbody id="itemsBody"></tbody></table>
<div id="stats" style="margin-top:8px;color:#8b949e;font-size:12px"></div>
</div>

<script>
var _items=[],_token='';

(function(){
 var m=location.search.match(/[?&]token=([^&]+)/);
 if(m)_token=m[1];
 if(!_token){document.getElementById('itemsBody').innerHTML='<tr><td colspan="5" style="color:#f85149;text-align:center">未提供 Token<br><small>请在游戏中输入 <b>/inherit token</b> 获取访问链接</small></td></tr>';return;}
 loadItems();
})();

async function loadItems(){
 var r=await fetch('/ks-Inherit/api/my-items?token='+encodeURIComponent(_token));
 var d=await r.json();
 if(d.error){document.getElementById('itemsBody').innerHTML='<tr><td colspan="5" style="color:#f85149;text-align:center">'+d.error+'</td></tr>';return;}
 _items=d.items||[];
 render();
 document.getElementById('stats').textContent='共 '+d.count+' 条记录';
}

function render(){
 var tb=document.getElementById('itemsBody'),h='';
 _items.forEach(function(item){
   var id='item_'+item.id;
   var badge='badge-'+(item.status||'PENDING').toLowerCase();
   var statusText={PENDING:'待审',APPROVED:'已批准',REJECTED:'已拒绝',DELIVERED:'已发放'}[item.status]||item.status;
   var time=item.submitted_at?new Date(item.submitted_at*1000).toLocaleString('zh-CN'):'-';
   h+='<tr class="detail-row" onclick="toggleDetail(\\''+id+'\\')">';
   h+='<td>'+item.id+'</td>';
   h+='<td>'+he(item.display_name_plain||item.item_name||item.item_type||'?')+'</td>';
   h+='<td style="font-size:11px">'+summaryHtml(item)+'</td>';
   h+='<td><span class="badge '+badge+'">'+statusText+'</span></td>';
   h+='<td style="font-size:11px;color:#8b949e">'+time+'</td>';
   h+='</tr>';
   h+='<tr class="detail-expand" id="'+id+'"><td colspan="5">'+detailHtml(item)+'</td></tr>';
 });
 tb.innerHTML=h||'<tr><td colspan="5" style="color:#666;text-align:center">暂无提交记录</td></tr>';
}

function summaryHtml(item){
 var p=[];
 if(item.enchants_detail){
   var en=item.enchants_detail;
   Object.entries(en).slice(0,3).forEach(function(e){p.push('<span class="ench-tag">'+e[1].name+' '+e[1].level+'</span>');});
   if(Object.keys(en).length>3)p.push('<span class="ench-tag">...+'+(Object.keys(en).length-3)+'</span>');
 }
 if(item.attributes&&item.attributes.length) p.push(' <span style="color:#ff7b72">⚔'+item.attributes.length+'项属性</span>');
 if(item.unbreakable) p.push(' <span style="color:#d2a8ff">🔒</span>');
 return p.join(' ')||'<span style="color:#666">-</span>';
}

function detailHtml(item){
 var h='<div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">';
 if(item.enchants_detail){
   h+='<div><b style="color:#58a6ff">附魔</b><div style="margin-top:4px">';
   Object.entries(item.enchants_detail).forEach(function(e){h+='<span class="ench-tag">'+e[1].name+' <b>'+e[1].level+'</b></span> ';});
   h+='</div></div>';
 }
 if(item.attributes&&item.attributes.length){
   h+='<div><b style="color:#ff7b72">属性</b><div style="margin-top:4px">';
   item.attributes.forEach(function(a){h+='<div style="font-size:11px;color:#8b949e">'+a.name+': '+a.amount+' ['+a.slot+']</div>';});
   h+='</div></div>';
 }
 if(item.lore_lines&&item.lore_lines.length){
   h+='<div style="grid-column:1/-1"><b style="color:#c9d1d9">Lore</b><div style="margin-top:4px">';
   item.lore_lines.forEach(function(l){h+='<div class="lore-line">'+he(l)+'</div>';});
   h+='</div></div>';
 }
 h+='</div>';
 return h;
}

function toggleDetail(id){
 var el=document.getElementById(id);
 el.style.display=el.style.display==='table-row'?'none':'table-row';
}
function he(s){return String(s||'').replace(/&/g,'&').replace(/</g,'<').replace(/>/g,'>')}
function toast(m,c){var e=document.createElement('div');e.className='toast toast-'+(c||'ok');e.textContent=m;document.body.appendChild(e);setTimeout(function(){e.remove()},3000)}
</script>
</body>
</html>
""";
}
