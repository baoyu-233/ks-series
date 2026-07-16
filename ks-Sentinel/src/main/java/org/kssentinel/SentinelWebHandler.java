package org.kssentinel;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * ks-Sentinel Web API + 管理页（1.21.11 + ks-core）。
 * Token 鉴权走 ks-core KsAuthManager（反射调用），全部 API 需 admin token。
 */
public final class SentinelWebHandler implements HttpHandler {

    private final KsSentinel plugin;
    private final Gson gson = new Gson();

    SentinelWebHandler(KsSentinel plugin) {
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
            if (subPath.startsWith("/ks-Sentinel")) subPath = subPath.substring(12);
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

            // 全部 /api/* 需要 admin token
            String token = extractToken(exchange);
            if (token == null || token.isEmpty()) {
                sendJson(exchange, 401, Map.of("error", "missing Authorization header", "hint", "Use /sentinel token in-game"));
                return;
            }
            Object session = validateToken(token);
            if (session == null) {
                sendJson(exchange, 401, Map.of("error", "invalid or expired token", "hint", "Use /sentinel token in-game to get a new token"));
                return;
            }
            if (!isAdminSession(session)) {
                sendJson(exchange, 403, Map.of("error", "admin token required"));
                return;
            }

            if (subPath.equals("/api/logs") && "GET".equalsIgnoreCase(method)) {
                handleGetLogs(exchange, query);
            } else if (subPath.equals("/api/rules") && "GET".equalsIgnoreCase(method)) {
                handleGetRules(exchange);
            } else if (subPath.equals("/api/rules") && "POST".equalsIgnoreCase(method)) {
                handleAddRule(exchange);
            } else if (subPath.equals("/api/rules") && "DELETE".equalsIgnoreCase(method)) {
                handleDeleteRule(exchange, query);
            } else if (subPath.equals("/api/exclusions") && "GET".equalsIgnoreCase(method)) {
                handleGetExclusions(exchange);
            } else if (subPath.equals("/api/exclusions") && "POST".equalsIgnoreCase(method)) {
                handleAddExclusion(exchange);
            } else if (subPath.equals("/api/exclusions") && "DELETE".equalsIgnoreCase(method)) {
                handleDeleteExclusion(exchange, query);
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
        var params = parseQuery(exchange.getRequestURI().getRawQuery());
        return params.getOrDefault("token", null);
    }

    private Object validateToken(String token) {
        try {
            var core = org.bukkit.Bukkit.getPluginManager().getPlugin("ks-core");
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
            var core = org.bukkit.Bukkit.getPluginManager().getPlugin("ks-core");
            if (core == null) { sendJson(exchange, 503, Map.of("error", "ks-core not found")); return; }
            var bridge = core.getClass().getMethod("bridge").invoke(core);
            var session = bridge.getClass().getMethod("createToken",
                    java.util.UUID.class, String.class, boolean.class)
                    .invoke(bridge,
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "TEST_ADMIN", true);
            String token = (String) session.getClass().getField("token").get(session);
            sendJson(exchange, 200, Map.of("token", token, "hint", "Authorization: Bearer " + token));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", "Failed to create token: " + e.getMessage()));
        }
    }

    private boolean testTokensEnabled() {
        var core = org.bukkit.Bukkit.getPluginManager().getPlugin("ks-core");
        return core != null && core.getConfig().getBoolean("web-gateway.allow-test-token", false);
    }

    // ==================== API handlers ====================

    private void handleGetLogs(HttpExchange exchange, String query) throws IOException {
        Map<String, String> p = parseQuery(query);
        String riskLevel = p.getOrDefault("riskLevel", "");
        String executor = p.getOrDefault("executor", "");
        String command = p.getOrDefault("command", "");
        long from = parseLong(p.get("from"), 0);
        long to = parseLong(p.get("to"), 0);
        int page = (int) parseLong(p.get("page"), 0);
        int pageSize = Math.max(1, Math.min(200, (int) parseLong(p.get("pageSize"), 50)));

        var rows = plugin.queryLogs(riskLevel.isEmpty() ? null : riskLevel,
            executor.isEmpty() ? null : executor, command.isEmpty() ? null : command,
            from, to, pageSize, page * pageSize);
        sendJson(exchange, 200, Map.of("logs", rows, "count", rows.size(), "page", page));
    }

    private void handleGetRules(HttpExchange exchange) throws IOException {
        var rules = plugin.rulesSnapshot();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rules) {
            out.add(Map.of("id", r.id(), "commandPrefix", r.commandPrefix(),
                "checkTargetArg", r.checkTargetArg(), "riskLevel", r.riskLevel(), "enabled", r.enabled()));
        }
        sendJson(exchange, 200, Map.of("rules", out));
    }

    @SuppressWarnings("unchecked")
    private void handleAddRule(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null || req.get("commandPrefix") == null) {
            sendJson(exchange, 400, Map.of("error", "missing commandPrefix")); return;
        }
        String prefix = String.valueOf(req.get("commandPrefix")).trim();
        boolean checkTarget = Boolean.parseBoolean(String.valueOf(req.getOrDefault("checkTargetArg", false)));
        String riskLevel = String.valueOf(req.getOrDefault("riskLevel", "HIGH"));
        if (prefix.isEmpty()) { sendJson(exchange, 400, Map.of("error", "commandPrefix empty")); return; }
        boolean ok = plugin.addRule(prefix, checkTarget, riskLevel);
        sendJson(exchange, ok ? 200 : 500, ok ? Map.of("message", "规则已添加: " + prefix) : Map.of("error", "添加失败"));
    }

    private void handleDeleteRule(HttpExchange exchange, String query) throws IOException {
        int id = (int) parseLong(parseQuery(query).get("id"), -1);
        if (id <= 0) { sendJson(exchange, 400, Map.of("error", "missing id")); return; }
        boolean ok = plugin.removeRule(id);
        sendJson(exchange, ok ? 200 : 404, ok ? Map.of("message", "已删除") : Map.of("error", "未找到"));
    }

    private void handleGetExclusions(HttpExchange exchange) throws IOException {
        var list = plugin.exclusionsSnapshot();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : list) out.add(Map.of("id", e.id(), "commandPrefix", e.commandPrefix()));
        sendJson(exchange, 200, Map.of("exclusions", out));
    }

    @SuppressWarnings("unchecked")
    private void handleAddExclusion(HttpExchange exchange) throws IOException {
        Map<String, Object> req = gson.fromJson(readBody(exchange), Map.class);
        if (req == null || req.get("commandPrefix") == null) {
            sendJson(exchange, 400, Map.of("error", "missing commandPrefix")); return;
        }
        String prefix = String.valueOf(req.get("commandPrefix")).trim();
        String note = String.valueOf(req.getOrDefault("note", ""));
        if (prefix.isEmpty()) { sendJson(exchange, 400, Map.of("error", "commandPrefix empty")); return; }
        boolean ok = plugin.addExclusion(prefix, note);
        sendJson(exchange, ok ? 200 : 500, ok ? Map.of("message", "已排除: " + prefix) : Map.of("error", "添加失败"));
    }

    private void handleDeleteExclusion(HttpExchange exchange, String query) throws IOException {
        int id = (int) parseLong(parseQuery(query).get("id"), -1);
        if (id <= 0) { sendJson(exchange, 400, Map.of("error", "missing id")); return; }
        boolean ok = plugin.removeExclusion(id);
        sendJson(exchange, ok ? 200 : 404, ok ? Map.of("message", "已删除") : Map.of("error", "未找到"));
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
        return ADMIN_HTML.getBytes(StandardCharsets.UTF_8);
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

    private long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }

    // ==================== Inline HTML ====================

    private static final String ADMIN_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>ks-Sentinel 管理员行为审计</title>
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
button.fetch-token{background:#9e6a03;border-color:#bb8009}
button.fetch-token:hover{background:#bb8009}
button.small{padding:2px 8px;font-size:11px}
table{width:100%;border-collapse:collapse;font-size:12px}
th,td{padding:8px 10px;text-align:left;border-bottom:1px solid #21262d;vertical-align:top}
th{background:#0d1117;color:#8b949e;font-weight:600;position:sticky;top:0}
tr:hover{background:#1c2128}
.badge{display:inline-block;padding:2px 8px;border-radius:12px;font-size:11px;font-weight:600}
.badge-high{background:#da3633;color:#fff}
.badge-medium{background:#9e6a03;color:#fff}
.badge-info{background:#30363d;color:#c9d1d9}
.toast{position:fixed;top:12px;right:12px;padding:10px 20px;border-radius:6px;font-size:13px;z-index:999;animation:fadeIn .3s}
.toast-ok{background:#238636;color:#fff}
.toast-err{background:#da3633;color:#fff}
@keyframes fadeIn{from{opacity:0;transform:translateY(-10px)}to{opacity:1;transform:translateY(0)}}
.token-row{margin-bottom:10px;display:flex;gap:8px;align-items:center}
.rules-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.rule-form{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px}
.rule-form input,.rule-form select{flex:1;min-width:80px}
.mono{font-family:monospace}
</style>
</head>
<body>
<h1>🛡 ks-Sentinel 管理员行为审计</h1>
<p class="sub">全体玩家指令审计 — 高危操作预警 / 排除规则管理</p>

<div class="card token-row">
<label style="font-size:12px;color:#8b949e">Admin Token:</label>
<input id="authToken" placeholder="Bearer token or ?token= param" style="width:300px;font-family:monospace;font-size:11px">
<button onclick="setToken()">🔑 设置</button>
<button onclick="fetchTestToken()" class="fetch-token">🎫 获取Token(仅本地)</button>
<span id="authStatus" style="font-size:11px;color:#8b949e;"></span>
</div>

<div class="card">
<h3>📋 指令日志</h3>
<div class="filters">
<select id="riskFilter" onchange="loadLogs()">
<option value="">全部等级</option><option value="HIGH" selected>HIGH</option>
<option value="MEDIUM">MEDIUM</option><option value="INFO">INFO</option>
</select>
<input id="executorFilter" placeholder="执行者 (可选)" style="width:140px">
<input id="commandFilter" placeholder="指令关键字 (可选)" style="width:160px">
<button onclick="loadLogs()">🔍 查询</button>
<button onclick="prevPage()" style="margin-left:auto">⬅ 上一页</button>
<button onclick="nextPage()">下一页 ➡</button>
</div>
<div style="max-height:55vh;overflow-y:auto">
<table><thead><tr>
<th>时间</th><th>执行者</th><th>指令</th><th>目标</th><th>等级</th><th>位置</th>
</tr></thead><tbody id="logsBody"></tbody></table>
</div>
<div id="logsStats" style="margin-top:8px;color:#8b949e;font-size:12px"></div>
</div>

<div class="card">
<h3>⚙ 规则管理</h3>
<div class="rules-grid">
<div>
<b style="color:#da3633">高危规则</b>
<div class="rule-form" style="margin-top:8px">
<input id="ruleCmd" placeholder="指令前缀 如 give">
<select id="ruleCheckTarget"><option value="true">检查目标参数</option><option value="false">不检查(命中即高危)</option></select>
<select id="ruleLevel"><option value="HIGH">HIGH</option><option value="MEDIUM">MEDIUM</option></select>
<button onclick="addRule()">添加</button>
</div>
<table><thead><tr><th>指令</th><th>检查目标</th><th>等级</th><th></th></tr></thead><tbody id="rulesBody"></tbody></table>
</div>
<div>
<b style="color:#8b949e">排除规则（完全不记录）</b>
<div class="rule-form" style="margin-top:8px">
<input id="exclCmd" placeholder="指令前缀 如 tpa">
<input id="exclNote" placeholder="备注(可选)">
<button onclick="addExclusion()">添加</button>
</div>
<table><thead><tr><th>指令</th><th>备注</th><th></th></tr></thead><tbody id="exclBody"></tbody></table>
</div>
</div>
</div>

<script>
var _token='',_tokenFail=false,_page=0,_pageSize=50,_lastCount=0;

(function(){
 var m=location.search.match(/[?&]token=([^&]+)/);
 if(m){_token=m[1];document.getElementById('authToken').value=_token;document.getElementById('authStatus').textContent='✅ from URL';}
})();

function setToken(){
 _token=document.getElementById('authToken').value.trim();
 _tokenFail=false;
 document.getElementById('authStatus').textContent=_token?'✅ 已设置':'';
 loadAll();
}

async function fetchTestToken(){
 var r=await fetch('/ks-Sentinel/api/test-token',{method:'POST'});
 var d=await r.json();
 if(d.token){document.getElementById('authToken').value=d.token;setToken();toast('✅ Token 已获取（仅本地有效）','ok');}
 else toast('❌ '+d.error,'err');
}

async function api(method,url,body){
 var opts={method:method,headers:{'Content-Type':'application/json'}};
 if(_token)opts.headers['Authorization']='Bearer '+_token;
 if(body)opts.body=JSON.stringify(body);
 var r=await fetch(url,opts);
 if(r.status===401){_tokenFail=true;return{error:'token 无效或过期，请重新获取'};}
 return r.json();
}

function loadAll(){loadLogs();loadRules();loadExclusions();}

async function loadLogs(){
 if(_tokenFail){document.getElementById('logsBody').innerHTML='<tr><td colspan="6" style="color:#f85149;text-align:center">Token 无效，请重新获取</td></tr>';return;}
 var ps=new URLSearchParams();
 var risk=document.getElementById('riskFilter').value; if(risk)ps.set('riskLevel',risk);
 var ex=document.getElementById('executorFilter').value.trim(); if(ex)ps.set('executor',ex);
 var cmd=document.getElementById('commandFilter').value.trim(); if(cmd)ps.set('command',cmd);
 ps.set('page',_page); ps.set('pageSize',_pageSize);
 var d=await api('GET','/ks-Sentinel/api/logs?'+ps.toString());
 if(_tokenFail){loadLogs();return;}
 var rows=d.logs||[]; _lastCount=rows.length;
 var tb=document.getElementById('logsBody'),h='';
 rows.forEach(function(r){
   var badge='badge-'+(r.risk_level||'INFO').toLowerCase();
   var time=r.created_at?new Date(r.created_at*1000).toLocaleString('zh-CN'):'-';
   var target=r.target_name?he(r.target_name):'-';
   var loc=r.world?(he(r.world)+' '+Math.round(r.x)+','+Math.round(r.y)+','+Math.round(r.z)):(r.is_console?'控制台':'-');
   h+='<tr><td style="font-size:11px;color:#8b949e">'+time+'</td><td>'+he(r.executor_name)+'</td>'
     +'<td class="mono">/'+he(r.command)+'</td><td>'+target+'</td>'
     +'<td><span class="badge '+badge+'">'+r.risk_level+'</span></td>'
     +'<td style="font-size:11px;color:#8b949e">'+loc+'</td></tr>';
 });
 tb.innerHTML=h||'<tr><td colspan="6" style="color:#666;text-align:center">暂无记录</td></tr>';
 document.getElementById('logsStats').textContent='第 '+(_page+1)+' 页，本页 '+rows.length+' 条';
}

function prevPage(){if(_page>0){_page--;loadLogs();}}
function nextPage(){if(_lastCount>=_pageSize){_page++;loadLogs();}}

async function loadRules(){
 var d=await api('GET','/ks-Sentinel/api/rules');
 if(_tokenFail)return;
 var tb=document.getElementById('rulesBody'),h='';
 (d.rules||[]).forEach(function(r){
   h+='<tr><td class="mono">'+he(r.commandPrefix)+'</td><td>'+(r.checkTargetArg?'是':'否')+'</td>'
     +'<td><span class="badge badge-'+r.riskLevel.toLowerCase()+'">'+r.riskLevel+'</span></td>'
     +'<td><button class="small danger" onclick="delRule('+r.id+')">删除</button></td></tr>';
 });
 tb.innerHTML=h||'<tr><td colspan="4" style="color:#666;text-align:center">暂无</td></tr>';
}

async function loadExclusions(){
 var d=await api('GET','/ks-Sentinel/api/exclusions');
 if(_tokenFail)return;
 var tb=document.getElementById('exclBody'),h='';
 (d.exclusions||[]).forEach(function(e){
   h+='<tr><td class="mono">'+he(e.commandPrefix)+'</td><td style="color:#8b949e">-</td>'
     +'<td><button class="small danger" onclick="delExclusion('+e.id+')">删除</button></td></tr>';
 });
 tb.innerHTML=h||'<tr><td colspan="3" style="color:#666;text-align:center">暂无</td></tr>';
}

async function addRule(){
 var cmd=document.getElementById('ruleCmd').value.trim();
 if(!cmd){toast('请输入指令前缀','err');return;}
 var checkTarget=document.getElementById('ruleCheckTarget').value;
 var level=document.getElementById('ruleLevel').value;
 var d=await api('POST','/ks-Sentinel/api/rules',{commandPrefix:cmd,checkTargetArg:checkTarget,riskLevel:level});
 if(d.message){toast(d.message,'ok');document.getElementById('ruleCmd').value='';loadRules();}else toast(d.error||'失败','err');
}
async function delRule(id){
 var d=await api('DELETE','/ks-Sentinel/api/rules?id='+id);
 if(d.message){toast(d.message,'ok');loadRules();}else toast(d.error||'失败','err');
}
async function addExclusion(){
 var cmd=document.getElementById('exclCmd').value.trim();
 if(!cmd){toast('请输入指令前缀','err');return;}
 var note=document.getElementById('exclNote').value.trim();
 var d=await api('POST','/ks-Sentinel/api/exclusions',{commandPrefix:cmd,note:note});
 if(d.message){toast(d.message,'ok');document.getElementById('exclCmd').value='';document.getElementById('exclNote').value='';loadExclusions();}else toast(d.error||'失败','err');
}
async function delExclusion(id){
 var d=await api('DELETE','/ks-Sentinel/api/exclusions?id='+id);
 if(d.message){toast(d.message,'ok');loadExclusions();}else toast(d.error||'失败','err');
}

function he(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
function toast(m,c){var e=document.createElement('div');e.className='toast toast-'+(c||'ok');e.textContent=m;document.body.appendChild(e);setTimeout(function(){e.remove()},3000)}

loadAll();
</script>
</body>
</html>
""";
}
