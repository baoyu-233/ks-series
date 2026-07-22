package org.kscore;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.kscore.scheduler.KsScheduler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 公告栏 Web 处理器（ks-core 本体，路由 /announce）。
 *
 *   GET  /announce            公开公告页（无需 token）
 *   GET  /announce/api/list   公告 JSON（无需 token）
 *   POST /announce/api/post   发布公告（需 admin token）
 *   POST /announce/api/delete 删除公告（需 admin token）
 */
public final class AnnouncementHandler implements HttpHandler {

    private final KsCore core;
    private final Gson gson = new Gson();

    public AnnouncementHandler(KsCore core) {
        this.core = core;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String sub = path.startsWith("/announce") ? path.substring("/announce".length()) : path;
        if (sub.isEmpty()) sub = "/";

        try {
            if (sub.equals("/api/list")) {
                Map<String, String> q = KsPluginBridge.parseQuery(exchange.getRequestURI().getRawQuery());
                List<Map<String, Object>> list = core.announcementManager().list(q.get("category"));
                KsPluginBridge.sendJson(exchange, 200, gson.toJson(Map.of("announcements", list, "count", list.size())));
            } else if (sub.equals("/api/post") && "POST".equalsIgnoreCase(method)) {
                if (requireAdmin(exchange) == null) return;
                @SuppressWarnings("unchecked")
                Map<String, Object> body = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
                if (body == null || body.get("title") == null) {
                    KsPluginBridge.sendJson(exchange, 400, "{\"error\":\"缺少标题\"}"); return;
                }
                String category = str(body.get("category"), "GENERAL");
                String title = str(body.get("title"), "");
                String content = str(body.get("body"), "");
                int priority = (int) num(body.get("priority"), 0);
                long expiresAt = (long) num(body.get("expiresAt"), 0);
                String author = str(body.get("author"), "管理员");
                long id = core.announcementManager().post(category, null, title, content, author, priority, expiresAt);
                if (id > 0) {
                    // 游戏内广播
                    broadcastAnnouncement(category, title, author);
                }
                KsPluginBridge.sendJson(exchange, id > 0 ? 200 : 500,
                        id > 0 ? "{\"message\":\"已发布\",\"id\":" + id + "}" : "{\"error\":\"发布失败\"}");
            } else if (sub.equals("/api/delete") && "POST".equalsIgnoreCase(method)) {
                if (requireAdmin(exchange) == null) return;
                @SuppressWarnings("unchecked")
                Map<String, Object> body = gson.fromJson(KsPluginBridge.readBody(exchange), Map.class);
                long id = (long) num(body != null ? body.get("id") : null, -1);
                boolean ok = core.announcementManager().removeById(id);
                KsPluginBridge.sendJson(exchange, ok ? 200 : 400,
                        ok ? "{\"message\":\"已删除\"}" : "{\"error\":\"删除失败\"}");
            } else {
                KsPluginBridge.sendHtml(exchange, 200, buildPage());
            }
        } catch (Exception e) {
            core.getLogger().warning("[公告] 请求异常: " + e.getMessage());
            KsPluginBridge.sendJson(exchange, 500, "{\"error\":\"服务器内部错误\"}");
        }
    }

    private KsAuthManager.Session requireAdmin(HttpExchange exchange) throws IOException {
        String token = null;
        Map<String, String> q = KsPluginBridge.parseQuery(exchange.getRequestURI().getRawQuery());
        if (q.get("token") != null) token = q.get("token");
        if (token == null) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) token = auth.substring(7);
        }
        KsAuthManager.Session s = token != null ? core.authManager().validate(token) : null;
        if (s == null || !s.isAdmin) {
            KsPluginBridge.sendJson(exchange, 403, "{\"error\":\"需要管理员 token（游戏内 /kseco-admin web 获取）\"}");
            return null;
        }
        return s;
    }

    private static String str(Object o, String def) { return o != null ? o.toString() : def; }
    private static double num(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return def; }
    }

    private String buildPage() {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>城邦公告栏</title><style>"
            + "*{box-sizing:border-box}body{font-family:'Segoe UI',sans-serif;max-width:920px;margin:0 auto;padding:20px;background:#0f1020;color:#e0e0e0}"
            + "h1{color:#00d4ff;border-bottom:2px solid #00d4ff33;padding-bottom:10px}"
            + "h2{color:#9fb;font-size:16px;margin:22px 0 8px}"
            + ".ann{background:#16213e;border-radius:8px;padding:12px 14px;margin:8px 0;border-left:4px solid #00d4ff}"
            + ".ann.LAW{border-left-color:#4caf50}.ann.VOTING{border-left-color:#ff9800}.ann.SYSTEM{border-left-color:#e91e63}"
            + ".ann h3{margin:0 0 4px;font-size:15px;color:#fff}.ann .meta{font-size:11px;color:#7a8}.ann .body{font-size:13px;color:#bcd;margin-top:4px;white-space:pre-wrap}"
            + ".empty{color:#667;font-size:13px;padding:6px}"
            + ".badge{display:inline-block;padding:1px 7px;border-radius:4px;font-size:10px;font-weight:700;margin-right:6px}"
            + ".b-LAW{background:#4caf5033;color:#7fe08a}.b-VOTING{background:#ff980033;color:#ffd08a}.b-GENERAL{background:#00d4ff22;color:#7fd6ff}.b-SYSTEM{background:#e91e6333;color:#ff8ab0}"
            + "input,textarea,select{background:#0d1b2a;border:1px solid #2a3a5a;color:#e0e0e0;border-radius:5px;padding:6px;width:100%;margin-top:3px}"
            + "button{background:#00d4ff;border:0;color:#012;font-weight:700;border-radius:5px;padding:7px 14px;cursor:pointer}"
            + ".admin{background:#16213e;border-radius:8px;padding:14px;margin:18px 0;border:1px dashed #2a3a5a}"
            + "label{display:block;font-size:12px;margin-bottom:8px}.row{display:flex;gap:8px}"
            + "</style></head><body>"
            + "<h1>📢 城邦公告栏</h1>"
            + "<p style=\"color:#889;font-size:12px\">实时展示官方公告与元老院立法动态。任何人可查看，无需登录。</p>"
            + "<div id=\"adminBox\"></div>"
            + "<h2>🏛 元老院 · 表决中</h2><div id=\"secVOTING\"></div>"
            + "<h2>📜 元老院 · 已颁布法案</h2><div id=\"secLAW\"></div>"
            + "<h2>📢 官方公告</h2><div id=\"secGEN\"></div>"
            + "<p style=\"margin-top:30px;color:#556;font-size:11px\">ks-core 公告栏 · 自动刷新</p>"
            + "<script>"
            + "var TOKEN=(new URL(location)).searchParams.get('token')||'';"
            + "function esc(s){return (s==null?'':String(s)).replace(/[&<>]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}"
            + "function dt(t){return t?new Date(t*1000).toLocaleString('zh-CN'):'';}"
            + "function card(a){var c=a.category||'GENERAL';var del=TOKEN?(' <a href=\"#\" onclick=\"delAnn('+a.id+');return false\" style=\"color:#f77;font-size:11px\">删除</a>'):'';"
            + "return '<div class=\"ann '+c+'\"><h3><span class=\"badge b-'+c+'\">'+c+'</span>'+esc(a.title)+del+'</h3>'"
            + "+'<div class=\"meta\">'+esc(a.author||'')+' · '+dt(a.createdAt)+'</div>'+(a.body?'<div class=\"body\">'+esc(a.body)+'</div>':'')+'</div>';}"
            + "function render(id,arr){var el=document.getElementById(id);el.innerHTML=arr.length?arr.map(card).join(''):'<div class=\"empty\">暂无</div>';}"
            + "async function load(){var r=await fetch('/announce/api/list');var d=await r.json();var a=d.announcements||[];"
            + "render('secVOTING',a.filter(function(x){return x.category==='VOTING';}));"
            + "render('secLAW',a.filter(function(x){return x.category==='LAW';}));"
            + "render('secGEN',a.filter(function(x){return x.category!=='VOTING'&&x.category!=='LAW';}));}"
            + "function adminUI(){"
            + "if(!TOKEN){"
            + "document.getElementById('adminBox').innerHTML='<div class=\"admin\" style=\"border-color:#ffd70055\">'"
            + "+'<b>🔑 管理员登录</b><p style=\"font-size:11px;color:#889;margin:4px 0\">在游戏中执行 <code style=\"background:#0d1b2a;padding:1px 5px;border-radius:3px\">/kseco-admin web</code> 获取管理链接，复制 token 参数粘贴到下方。</p>'"
            + "+'<div class=\"row\"><input id=\"loginToken\" placeholder=\"粘贴 admin token...\" style=\"flex:1\"/>'"
            + "+'<button onclick=\"doLogin()\" style=\"white-space:nowrap\">登录</button></div>'"
            + "+'<p id=\"loginMsg\" style=\"font-size:11px;margin-top:6px\"></p></div>';"
            + "return;}"
            + "document.getElementById('adminBox').innerHTML='<div class=\"admin\"><b>✍ 发布公告（管理员）</b>'"
            + "+' <a href=\"#\" onclick=\"doLogout();return false\" style=\"color:#f77;font-size:11px;float:right\">退出登录</a>'"
            + "+'<label>标题<input id=\"aTitle\"/></label>'"
            + "+'<label>类型<select id=\"aCat\"><option value=\"GENERAL\">普通公告</option><option value=\"SYSTEM\">系统通知</option></select></label>'"
            + "+'<label>内容<textarea id=\"aBody\" rows=\"3\"></textarea></label>'"
            + "+'<div class=\"row\"><label style=\"flex:1\">优先级(越大越靠前)<input id=\"aPrio\" type=\"number\" value=\"0\"/></label>'"
            + "+'<label style=\"flex:1\">有效期(小时,0=永久)<input id=\"aTtl\" type=\"number\" value=\"0\"/></label></div>'"
            + "+'<button onclick=\"postAnn()\">发布</button> <span id=\"aMsg\" style=\"font-size:12px;color:#9fb\"></span></div>';}"
            + "async function postAnn(){var ttl=parseInt(document.getElementById('aTtl').value)||0;"
            + "var exp=ttl>0?Math.floor(Date.now()/1000)+ttl*3600:0;"
            + "var r=await fetch('/announce/api/post?token='+encodeURIComponent(TOKEN),{method:'POST',headers:{'Content-Type':'application/json'},"
            + "body:JSON.stringify({title:document.getElementById('aTitle').value,category:document.getElementById('aCat').value,"
            + "body:document.getElementById('aBody').value,priority:parseInt(document.getElementById('aPrio').value)||0,expiresAt:exp})});"
            + "var d=await r.json();document.getElementById('aMsg').textContent=d.message||d.error;if(d.message){document.getElementById('aTitle').value='';document.getElementById('aBody').value='';load();}}"
            + "function doLogin(){var t=document.getElementById('loginToken').value.trim();if(!t){document.getElementById('loginMsg').textContent='请输入 token';return;}"
            + "var url=new URL(location);url.searchParams.set('token',t);location.href=url.toString();}"
            + "function doLogout(){var url=new URL(location);url.searchParams.delete('token');location.href=url.toString();}"
            + "async function delAnn(id){if(!confirm('删除该公告?'))return;var r=await fetch('/announce/api/delete?token='+encodeURIComponent(TOKEN),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:id})});var d=await r.json();if(d.message)load();else alert(d.error);}"
            + "adminUI();load();setInterval(load,15000);"
            + "</script></body></html>";
    }

    /** 向全服在线玩家广播新公告 */
    private void broadcastAnnouncement(String category, String title, String author) {
        try {
            String prefix = switch (category != null ? category : "") {
                case "VOTING" -> "🏛 元老院表决";
                case "LAW" -> "📜 新法案";
                case "SYSTEM" -> "⚙ 系统通知";
                default -> "📢 公告";
            };
            Component msg = Component.text()
                .append(Component.text("[" + prefix + "] ", NamedTextColor.GOLD))
                .append(Component.text(title, NamedTextColor.WHITE))
                .append(Component.text("  — " + (author != null ? author : "管理员"), NamedTextColor.GRAY))
                .build();
            KsScheduler.runGlobal(core, () -> core.getServer().broadcast(msg));
        } catch (Throwable ignored) { }
    }
}
