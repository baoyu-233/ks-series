package org.kseco.extra.politic;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.kscore.KsAuthManager;
import org.kscore.KsPluginBridge;
import org.kseco.KsEco;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 政治系统 Web API 处理器。
 *
 * 通过 ks-core 注册到 /ks-Eco/politic 路由。
 * 所有 /ks-Eco/politic/api/* 请求由此类处理。
 */
public final class PoliticWebHandler implements HttpHandler {

    private final KsEco eco;
    private final PoliticManager politicManager;
    private final ProposalManager proposalManager;
    private final VoteManager voteManager;
    private final ElectionEngine electionEngine;
    private final Gson gson = new Gson();

    public PoliticWebHandler(KsEco eco, PoliticManager politicManager, ProposalManager proposalManager,
                              VoteManager voteManager, ElectionEngine electionEngine) {
        this.eco = eco;
        this.politicManager = politicManager;
        this.proposalManager = proposalManager;
        this.voteManager = voteManager;
        this.electionEngine = electionEngine;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Cache-Control", "no-store, private");

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // 提取子路径
            String subPath = path;
            if (subPath.startsWith("/ks-Eco/politic")) subPath = subPath.substring("/ks-Eco/politic".length());
            if (subPath.isEmpty() || subPath.equals("/")) subPath = "/";

            // 玩家功能门控：politic 功能未开放时，非管理员一律拒绝（含页面本身）
            if (!eco.featureGate().isOpen("politic")) {
                String gateToken = getToken(exchange);
                KsAuthManager.Session gateSession = gateToken != null ? eco.bridge().validateToken(gateToken) : null;
                if (gateSession == null || !gateSession.isAdmin) {
                    sendJson(exchange, 403, "{\"error\":\"该功能暂未开放\"}");
                    return;
                }
            }

            // 路由分发
            if (subPath.equals("/") && "GET".equalsIgnoreCase(method)) {
                sendHtml(exchange, 200, buildPoliticPage());
            } else if (subPath.equals("/api/offices")) {
                handleOfficesList(exchange, query);
            } else if (subPath.equals("/api/admin/senator/add") && "POST".equalsIgnoreCase(method)) {
                handleSenatorAdd(exchange);
            } else if (subPath.equals("/api/admin/senator/remove") && "POST".equalsIgnoreCase(method)) {
                handleSenatorRemove(exchange);
            } else if (subPath.equals("/api/admin/election/trigger") && "POST".equalsIgnoreCase(method)) {
                handleElectionTrigger(exchange);
            } else if (subPath.equals("/api/admin/consul/assign") && "POST".equalsIgnoreCase(method)) {
                handleConsulAssign(exchange);
            } else if (subPath.equals("/api/tribune-election") && "GET".equalsIgnoreCase(method)) {
                handleTribuneElectionStatus(exchange);
            } else if (subPath.equals("/api/tribune-election/vote") && "POST".equalsIgnoreCase(method)) {
                handleTribuneElectionVote(exchange);
            } else if (subPath.equals("/api/proposals")) {
                handleProposalsList(exchange, query);
            } else if (subPath.equals("/api/proposal/create") && "POST".equalsIgnoreCase(method)) {
                handleProposalCreate(exchange); // must be BEFORE /api/proposal/ wildcard
            } else if (subPath.startsWith("/api/proposal/") && subPath.endsWith("/vote") && "POST".equalsIgnoreCase(method)) {
                handleCastVote(exchange);
            } else if (subPath.startsWith("/api/proposal/") && subPath.endsWith("/start-vote") && "POST".equalsIgnoreCase(method)) {
                handleStartVote(exchange);
            } else if (subPath.startsWith("/api/proposal/") && subPath.endsWith("/tribune-review") && "POST".equalsIgnoreCase(method)) {
                handleTribuneReview(exchange);
            } else if (subPath.startsWith("/api/proposal/") && subPath.endsWith("/override") && "POST".equalsIgnoreCase(method)) {
                handleOverride(exchange);
            } else if (subPath.startsWith("/api/proposal/")) {
                handleProposalDetail(exchange, subPath);
            } else if (subPath.equals("/api/elections/status")) {
                handleElectionStatus(exchange);
            } else if (subPath.equals("/api/my-office")) {
                handleMyOffice(exchange);
            } else if (subPath.equals("/api/my-votes")) {
                handleMyVotes(exchange);
            } else if (subPath.equals("/api/config")) {
                if ("GET".equalsIgnoreCase(method)) handleConfigGet(exchange);
                else handleConfigSet(exchange);
            } else {
                sendJson(exchange, 404, "{\"error\":\"未知路由: " + subPath + "\"}");
            }
        } catch (Exception e) {
            eco.getLogger().warning("[政治系统] Web 请求异常: " + e.getMessage());
            sendJson(exchange, 500, "{\"error\":\"服务器内部错误\"}");
        }
    }

    // ================================================================
    // 鉴权工具
    // ================================================================

    private KsAuthManager.Session requireAuth(HttpExchange exchange) throws IOException {
        String token = getToken(exchange);
        if (token == null) {
            sendJson(exchange, 401, "{\"error\":\"缺少认证 token\"}");
            return null;
        }
        KsAuthManager.Session session = eco.bridge().validateToken(token);
        if (session == null) {
            sendJson(exchange, 401, "{\"error\":\"Token 无效或已过期\"}");
            return null;
        }
        eco.bridge().touchToken(token);
        return session;
    }

    private KsAuthManager.Session requireAdmin(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return null;
        if (!s.isAdmin) {
            sendJson(exchange, 403, "{\"error\":\"需要管理员权限\"}");
            return null;
        }
        return s;
    }

    private String getToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        if (exchange.getRequestURI().getPath().startsWith("/ks-Eco/politic/api/")) return null;
        String q = exchange.getRequestURI().getRawQuery();
        if (q != null) {
            String token = parseQuery(q).get("token");
            if (token != null && !token.isEmpty()) return token;
        }
        return null;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return KsPluginBridge.readBody(exchange);
    }

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendHtml(HttpExchange exchange, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 公开政治仪表盘 HTML（无需 token）。 */
    private String buildPoliticPage() {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>🏛 元老院与共和政治</title><style>"
            + "*{box-sizing:border-box}body{font-family:'Segoe UI',sans-serif;max-width:960px;margin:0 auto;padding:20px;background:#0f1020;color:#e0e0e0}"
            + "h1{color:#ffd700;border-bottom:2px solid #ffd70033;padding-bottom:10px;font-size:22px}"
            + "h2{color:#9fb;font-size:16px;margin:22px 0 8px}"
            + ".card{background:#16213e;border-radius:8px;padding:12px 14px;margin:8px 0}"
            + ".card h3{margin:0 0 6px;font-size:14px;color:#fff}"
            + ".prop{background:#16213e;border-radius:8px;padding:10px 14px;margin:6px 0;border-left:4px solid #666}"
            + ".prop.PROPOSED{border-left-color:#888}.prop.SENATE_VOTING,.prop.SENATE_OVERRIDE{border-left-color:#ff9800}"
            + ".prop.APPROVED{border-left-color:#2196f3}.prop.ENACTED{border-left-color:#4caf50}"
            + ".prop.VETOED,.prop.REJECTED,.prop.ABANDONED{border-left-color:#f44336}"
            + ".prop h4{margin:0 0 3px;font-size:13px;color:#fff}.prop .meta{font-size:10px;color:#7a8}"
            + ".prop .desc{font-size:12px;color:#bcd;margin-top:3px;white-space:pre-wrap}"
            + ".badge{display:inline-block;padding:1px 7px;border-radius:4px;font-size:10px;font-weight:700;margin-right:5px}"
            + ".b-CONSUL{background:#ffd70033;color:#ffd700}.b-SENATOR{background:#ffd70022;color:#ffcc80}"
            + ".b-TRIBUNE{background:#f4433622;color:#f48b8b}.b-EQUESTRIAN{background:#00d4ff22;color:#7fd6ff}"
            + ".b-PROPOSED{background:#8883;color:#aaa}.b-SENATE_VOTING,.b-SENATE_OVERRIDE{background:#ff980033;color:#ffd08a}"
            + ".b-APPROVED{background:#2196f333;color:#90caf9}.b-ENACTED{background:#4caf5033;color:#7fe08a}"
            + ".b-VETOED,.b-REJECTED,.b-ABANDONED{background:#f4433633;color:#f4a0a0}"
            + ".empty{color:#667;font-size:13px;padding:6px}"
            + ".links{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}"
            + ".links a{display:inline-block;background:#16213e;color:#00d4ff;text-decoration:none;padding:8px 14px;border-radius:6px;font-size:13px;border:1px solid #2a3a5a}"
            + ".links a:hover{background:#1d2f5a}"
            + ".row{display:flex;gap:8px;flex-wrap:wrap}.col{flex:1;min-width:280px}"
            + ".stat{font-size:22px;font-weight:700;color:#ffd700}.stat-label{font-size:11px;color:#889}"
            + ".status-bar{display:flex;gap:16px;margin:10px 0;flex-wrap:wrap}"
            + ".status-item{background:#16213e;border-radius:6px;padding:10px 16px;text-align:center;min-width:80px}"
            + "</style></head><body>"
            + "<h1>🏛 元老院与共和政治系统</h1>"
            + "<div class=\"links\">"
            + "<a href=\"/announce\">📢 城邦公告栏（立法动态+官方公告）</a>"
            + "<a href=\"/ks-Eco/player\">🎮 玩家中心（需登录投票/提案）</a>"
            + "<a href=\"/ks-Eco/admin\">⚙ 管理后台</a>"
            + "</div>"
            + "<div class=\"status-bar\" id=\"statusBar\">"
            + "<div class=\"status-item\"><div class=\"stat\" id=\"cntVoting\">-</div><div class=\"stat-label\">表决中</div></div>"
            + "<div class=\"status-item\"><div class=\"stat\" id=\"cntLaws\">-</div><div class=\"stat-label\">已颁布法律</div></div>"
            + "<div class=\"status-item\"><div class=\"stat\" id=\"cntSenators\">-</div><div class=\"stat-label\">元老院席位</div></div>"
            + "</div>"
            + "<div class=\"row\">"
            + "<div class=\"col\"><h2>👑 现任职务</h2><div class=\"card\" id=\"cardOffices\"><div class=\"empty\">加载中...</div></div></div>"
            + "<div class=\"col\"><h2>⚙ 议会配置</h2><div class=\"card\" id=\"cardConfig\"><div class=\"empty\">加载中...</div></div></div>"
            + "</div>"
            + "<h2>📜 表决中的提案</h2><div id=\"secVoting\"><div class=\"empty\">加载中...</div></div>"
            + "<h2>📋 最新法案</h2><div id=\"secEnacted\"><div class=\"empty\">加载中...</div></div>"
            + "<h2>📝 最近提案</h2><div id=\"secRecent\"><div class=\"empty\">加载中...</div></div>"
            + "<p style=\"margin-top:30px;color:#556;font-size:11px;text-align:center\">ks-Politic 元老院系统 · 自动刷新 30s</p>"
            + "<script>"
            + "function esc(s){return(s==null?'':String(s)).replace(/[&<>]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}"
            + "function dt(t){return t?new Date(t*1000).toLocaleString('zh-CN'):'';}"
            + "var STATUS_CN={PROPOSED:'草拟',SENATE_VOTING:'元老院表决中',SENATE_OVERRIDE:'覆议中',TRIBUNE_REVIEW:'保民官审查',APPROVED:'已批准',ENACTED:'已颁布',REJECTED:'元老院否决',VETOED:'保民官否决',OVERRIDDEN:'覆议推翻',ABANDONED:'覆议失败'};"
            + "var TYPE_CN={SET_TAX_RATE:'税率',SET_TAX_BRACKET:'阶梯税率',SET_CB_RATES:'央行利率',CB_INJECT:'央行注资',SET_OFFICIAL_PRICE:'官方定价',RE_ZONE_ADMIN:'房地产',GENERAL:'决议'};"
            + "function badge(cat,text){return '<span class=\"badge b-'+cat+'\">'+text+'</span>';}"
            + "function propCard(p){var s=p.status||'PROPOSED';return '<div class=\"prop '+s+'\"><h4>'+badge(s,STATUS_CN[s]||s)+esc(p.title)+'</h4>'"
            + "+'<div class=\"meta\">'+esc(p.proposerName||'?')+' · '+(TYPE_CN[p.proposalType]||p.proposalType||'?')+(p.enactedAt?' · 颁布于 '+dt(p.enactedAt):'')+'</div>'"
            + "+(p.description?'<div class=\"desc\">'+esc(p.description)+'</div>':'')+'</div>';}"
            + "function render(id,arr){var el=document.getElementById(id);el.innerHTML=arr.length?arr.map(propCard).join(''):'<div class=\"empty\">暂无</div>';}"
            + "function renderOffices(offices){var h='';var groups={CONSUL:[],SENATOR:[],TRIBUNE:[],EQUESTRIAN:[]};"
            + "offices.forEach(function(o){var g=groups[o.officeType];if(g)g.push(o);});"
            + "var labels={CONSUL:'👑 执政官',SENATOR:'📜 元老院议员',TRIBUNE:'🛡 平民保民官',EQUESTRIAN:'🐴 骑士阶级'};"
            + "for(var t in groups){var arr=groups[t];"
            + "h+='<p style=\"margin:4px 0;font-size:12px\"><b>'+labels[t]+'</b> ('+arr.length+'): ';"
            + "if(!arr.length)h+='<span style=\"color:#667\">空缺</span>';"
            + "else h+=arr.map(function(o){return badge(t,esc(o.playerName));}).join(' ');"
            + "h+='</p>';}"
            + "document.getElementById('cardOffices').innerHTML=h;"
            + "var senators=groups.SENATOR.length+(groups.CONSUL.length>0?0:0);"
            + "document.getElementById('cntSenators').textContent=senators;}"
            + "async function load(){"
            + "try{var o=await fetch('/ks-Eco/politic/api/offices?type=all');var od=await o.json();renderOffices(od.offices||[]);}catch(e){}"
            + "try{var r=await fetch('/ks-Eco/politic/api/config');var cd=await r.json();var cfg=cd.config||{};"
            + "var chtml='<p style=\"font-size:12px;margin:2px 0\">元老院席位: <b>'+cfg.senate_seats+'</b></p>';"
            + "chtml+='<p style=\"font-size:12px;margin:2px 0\">立法模式: <b>'+(cfg.legislative_mode==='true'?'🔒 元老院立法（禁止 admin 直改）':'🔓 自由模式')+'</b></p>';"
            + "document.getElementById('cardConfig').innerHTML=chtml;}catch(e){}"
            + "try{var p=await fetch('/ks-Eco/politic/api/proposals?limit=50');var pd=await p.json();var all=pd.proposals||[];"
            + "var voting=all.filter(function(x){return x.status==='SENATE_VOTING'||x.status==='SENATE_OVERRIDE'||x.status==='TRIBUNE_REVIEW';});"
            + "var enacted=all.filter(function(x){return x.status==='ENACTED';}).slice(0,5);"
            + "var recent=all.filter(function(x){return x.status!=='ENACTED'&&x.status!=='SENATE_VOTING'&&x.status!=='SENATE_OVERRIDE'&&x.status!=='TRIBUNE_REVIEW';}).slice(0,10);"
            + "render('secVoting',voting);render('secEnacted',enacted);render('secRecent',recent);"
            + "document.getElementById('cntVoting').textContent=voting.length;"
            + "document.getElementById('cntLaws').textContent=enacted.length;}catch(e){}"
            + "}"
            + "load();setInterval(load,30000);"
            + "</script></body></html>";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body == null || body.isEmpty()) return Map.of();
        try { return (Map<String, Object>) gson.fromJson(body, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    // ================================================================
    // 职务管理
    // ================================================================

    private void handleOfficesList(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        String type = params.getOrDefault("type", "all");
        List<PoliticManager.Office> offices;
        if ("all".equals(type)) {
            offices = new ArrayList<>();
            offices.addAll(politicManager.getSenators());
            PoliticManager.Office c = politicManager.getConsul();
            if (c != null) offices.add(c);
            offices.addAll(politicManager.getTribunes());
            offices.addAll(politicManager.getEquestrians());
        } else {
            offices = politicManager.getActiveOffices(type.toUpperCase());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (PoliticManager.Office o : offices) list.add(o.toMap());
        sendJson(exchange, 200, gson.toJson(Map.of("offices", list, "count", list.size())));
    }

    private void handleSenatorAdd(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdmin(exchange);
        if (s == null) return;
        Map<String, Object> body = parseBody(exchange);
        String uuid = (String) body.get("playerUuid");
        String name = (String) body.getOrDefault("playerName", uuid != null ? uuid : "?");
        if (uuid == null || uuid.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"缺少 playerUuid\"}");
            return;
        }
        var result = politicManager.assignSenator(java.util.UUID.fromString(uuid), name);
        sendJson(exchange, result.success() ? 200 : 400,
            gson.toJson(Map.of("success", result.success(), "message", result.message())));
    }

    private void handleSenatorRemove(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdmin(exchange);
        if (s == null) return;
        Map<String, Object> body = parseBody(exchange);
        String uuid = (String) body.get("playerUuid");
        if (uuid == null || uuid.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"缺少 playerUuid\"}");
            return;
        }
        boolean ok = politicManager.removeSenator(java.util.UUID.fromString(uuid));
        sendJson(exchange, ok ? 200 : 400,
            gson.toJson(Map.of("success", ok, "message", ok ? "已移除" : "不是元老成员")));
    }

    private void handleElectionTrigger(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdmin(exchange);
        if (s == null) return;
        Map<String, Object> body = parseBody(exchange);
        String type = (String) body.getOrDefault("type", "ALL");
        Map<String, Object> result = electionEngine.triggerElection(type);
        sendJson(exchange, 200, gson.toJson(result));
    }

    /** admin 直接指定某个元老担任执政官（取代旧的自动/轮换选举）。 */
    private void handleConsulAssign(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdmin(exchange);
        if (s == null) return;
        Map<String, Object> body = parseBody(exchange);
        String uuid = (String) body.get("playerUuid");
        if (uuid == null || uuid.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"缺少 playerUuid\"}");
            return;
        }
        java.util.UUID puuid = java.util.UUID.fromString(uuid);
        String name = (String) body.getOrDefault("playerName", "");
        if (name == null || name.isEmpty()) {
            var op = org.bukkit.Bukkit.getOfflinePlayer(puuid);
            name = op.getName() != null ? op.getName() : uuid;
        }
        var result = politicManager.electConsul(puuid, name);
        sendJson(exchange, result.success() ? 200 : 400,
            gson.toJson(Map.of("success", result.success(), "message", result.message())));
    }

    /** 保民官选举状态：周期信息 + 实时计票 + 我的投票。 */
    private void handleTribuneElectionStatus(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        String electionId = politicManager.getTribuneElectionId();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("electionId", electionId);
        resp.put("startedAt", politicManager.getTribuneElectionStartedAt());
        resp.put("endsAt", politicManager.getTribuneElectionEndsAt());
        resp.put("intervalHours", politicManager.getConfigInt("tribune_election_interval_hours", 24));
        resp.put("seats", politicManager.getConfigInt("tribune_seats", 2));
        resp.put("tally", politicManager.getTribuneElectionTally(electionId));
        resp.put("myVote", politicManager.getMyTribuneVote(electionId, s.playerUuid));
        sendJson(exchange, 200, gson.toJson(resp));
    }

    /** 玩家为保民官候选人投票（一人一票，可改票，无身份限制）。 */
    private void handleTribuneElectionVote(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        Map<String, Object> body = parseBody(exchange);
        String candUuid = (String) body.get("candidateUuid");
        if (candUuid == null || candUuid.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"缺少 candidateUuid\"}");
            return;
        }
        java.util.UUID candidate = java.util.UUID.fromString(candUuid);
        String candName = (String) body.getOrDefault("candidateName", "");
        if (candName == null || candName.isEmpty()) {
            var op = org.bukkit.Bukkit.getOfflinePlayer(candidate);
            candName = op.getName() != null ? op.getName() : candUuid;
        }
        String electionId = politicManager.getTribuneElectionId();
        var result = politicManager.castTribuneElectionVote(electionId, s.playerUuid, s.playerName, candidate, candName);
        sendJson(exchange, result.success() ? 200 : 400,
            gson.toJson(Map.of("success", result.success(), "message", result.message())));
    }

    // ================================================================
    // 提案
    // ================================================================

    private void handleProposalsList(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        String status = params.get("status");
        String proposer = params.get("proposer");
        int limit = parseInt(params.get("limit"), 50);
        List<ProposalManager.Proposal> proposals = proposalManager.listProposals(status, proposer, limit);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ProposalManager.Proposal p : proposals) list.add(p.toMap());
        sendJson(exchange, 200, gson.toJson(Map.of("proposals", list, "count", list.size())));
    }

    private void handleProposalDetail(HttpExchange exchange, String subPath) throws IOException {
        // /api/proposal/{id}
        String id = subPath.substring(14); // strip "/api/proposal/"
        ProposalManager.Proposal p = proposalManager.getProposal(id);
        if (p == null) {
            sendJson(exchange, 404, "{\"error\":\"提案不存在\"}");
            return;
        }
        Map<String, Object> resp = p.toMap();
        // 附带各阶段投票明细
        resp.put("senateVotes", voteManager.getVoteDetails(id, "SENATE_VOTING"));
        resp.put("senateOverrideVotes", voteManager.getVoteDetails(id, "SENATE_OVERRIDE"));
        resp.put("nonVoters", voteManager.getNonVoters(id, p.status));
        resp.put("eligibleVoters", voteManager.getEligibleVoters(p.status));
        resp.put("tally", voteManager.countVotes(id, p.status).toMap());
        sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleProposalCreate(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;

        // 检查是否有提案权
        if (!politicManager.canPropose(s.playerUuid)) {
            sendJson(exchange, 403, "{\"error\":\"只有执政官或骑士可以提交提案\"}");
            return;
        }

        Map<String, Object> body = parseBody(exchange);
        String title = (String) body.get("title");
        if (title == null || title.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"缺少提案标题\"}");
            return;
        }

        String proposalType = (String) body.getOrDefault("proposalType", "GENERAL");
        String description = (String) body.getOrDefault("description", "");
        String targetEndpoint = (String) body.getOrDefault("targetEndpoint", "");
        String office = politicManager.getPlayerOffice(s.playerUuid);
        if (office == null) office = "SENATOR";

        if (!(body.getOrDefault("payload", Map.of()) instanceof Map<?, ?> rawPayload)) {
            sendJson(exchange, 400, "{\"error\":\"提案参数必须是对象\"}");
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        rawPayload.forEach((key, value) -> payload.put(String.valueOf(key), value));
        String validationError = ProposalManager.validateProposalPayload(proposalType, payload);
        if (validationError != null) {
            sendJson(exchange, 400, gson.toJson(Map.of("error", validationError)));
            return;
        }
        String payloadJson = gson.toJson(payload);

        var result = proposalManager.createProposal(title, description, proposalType,
            targetEndpoint, payloadJson, s.playerUuid, s.playerName, office);

        if (result.success()) {
            sendJson(exchange, 200, gson.toJson(Map.of("success", true, "id", result.id(), "title", result.title())));
        } else {
            sendJson(exchange, 400, gson.toJson(Map.of("success", false, "error", result.error())));
        }
    }

    private void handleStartVote(HttpExchange exchange) throws IOException {
        // POST /api/proposal/{id}/start-vote
        String subPath = extractSubPath(exchange);
        String id = subPath.replace("start-vote", "").replaceAll("/$", "");
        if (id.startsWith("/api/proposal/")) id = id.substring(14);
        if (id.endsWith("/")) id = id.substring(0, id.length() - 1);

        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;

        var result = proposalManager.transitionProposal(id, "SENATE_VOTING", s.playerUuid, s.playerName);
        sendJson(exchange, result.success() ? 200 : 400,
            gson.toJson(Map.of("success", result.success(),
                "fromState", result.fromState() != null ? result.fromState() : "",
                "toState", result.toState() != null ? result.toState() : "",
                "error", result.error() != null ? result.error() : "")));
    }

    private void handleCastVote(HttpExchange exchange) throws IOException {
        // POST /api/proposal/{id}/vote
        String path = exchange.getRequestURI().getPath();
        String id = extractProposalId(path, "vote");

        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;

        Map<String, Object> body = parseBody(exchange);
        String vote = (String) body.get("vote");
        if (vote == null) vote = "YES";

        ProposalManager.Proposal p = proposalManager.getProposal(id);
        if (p == null) {
            sendJson(exchange, 404, "{\"error\":\"提案不存在\"}");
            return;
        }

        // 检查投票权限
        String stage = p.status;
        String office;
        if ("SENATE_VOTING".equals(stage) || "SENATE_OVERRIDE".equals(stage)) {
            if (!politicManager.canVoteInSenate(s.playerUuid)) {
                sendJson(exchange, 403, "{\"error\":\"只有元老院议员可以投票\"}");
                return;
            }
            office = politicManager.getPlayerOffice(s.playerUuid);
        } else if ("TRIBUNE_REVIEW".equals(stage)) {
            if (!politicManager.canVeto(s.playerUuid)) {
                sendJson(exchange, 403, "{\"error\":\"只有保民官可以审查\"}");
                return;
            }
            office = "TRIBUNE";
        } else {
            sendJson(exchange, 400, "{\"error\":\"当前状态 " + stage + " 不允许投票\"}");
            return;
        }

        var result = voteManager.castVote(id, s.playerUuid, s.playerName, office, vote, stage);
        if (!result.success()) {
            sendJson(exchange, 400, gson.toJson(Map.of("success", false, "error", result.error())));
            return;
        }

        // 计票结果是否触发自动流转
        VoteManager.Tally tally = result.tally();
        if (tally.quorumMet()) {
            proposalManager.autoAdvanceAfterVote(id, tally);
            p = proposalManager.getProposal(id); // refresh
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("voteId", result.voteId());
        resp.put("tally", tally.toMap());
        resp.put("newStatus", p != null ? p.status : stage);
        sendJson(exchange, 200, gson.toJson(resp));
    }

    private void handleTribuneReview(HttpExchange exchange) throws IOException {
        // POST /api/proposal/{id}/tribune-review
        String path = exchange.getRequestURI().getPath();
        String id = extractProposalId(path, "tribune-review");

        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;

        if (!politicManager.canVeto(s.playerUuid)) {
            sendJson(exchange, 403, "{\"error\":\"只有保民官可以审查法案\"}");
            return;
        }

        Map<String, Object> body = parseBody(exchange);
        String action = (String) body.getOrDefault("action", "VETO");
        String newState = "APPROVE".equalsIgnoreCase(action) ? "APPROVED" : "VETOED";

        // 先投出保民官的票
        voteManager.castVote(id, s.playerUuid, s.playerName, "TRIBUNE",
            "APPROVED".equals(newState) ? "YES" : "NO", "TRIBUNE_REVIEW");

        var result = proposalManager.transitionProposal(id, newState, s.playerUuid, s.playerName);
        if (result.success() && "APPROVED".equals(newState)) {
            // 自动颁布
            proposalManager.transitionProposal(id, "ENACTED", s.playerUuid, "SYSTEM");
        }

        ProposalManager.Proposal p = proposalManager.getProposal(id);
        sendJson(exchange, result.success() ? 200 : 400,
            gson.toJson(Map.of("success", result.success(), "newStatus", p != null ? p.status : "",
                "error", result.error() != null ? result.error() : "")));
    }

    private void handleOverride(HttpExchange exchange) throws IOException {
        // POST /api/proposal/{id}/override
        String path = exchange.getRequestURI().getPath();
        String id = extractProposalId(path, "override");

        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;

        if (!politicManager.canVoteInSenate(s.playerUuid)) {
            sendJson(exchange, 403, "{\"error\":\"只有元老院议员可以发起覆议\"}");
            return;
        }

        ProposalManager.Proposal p = proposalManager.getProposal(id);
        if (p == null) {
            sendJson(exchange, 404, "{\"error\":\"提案不存在\"}");
            return;
        }
        if (!"VETOED".equals(p.status)) {
            sendJson(exchange, 400, "{\"error\":\"当前状态不是 VETOED，无法发起覆议\"}");
            return;
        }

        var result = proposalManager.transitionProposal(id, "SENATE_OVERRIDE", s.playerUuid, s.playerName);
        // 自动为发起人投 YES
        if (result.success()) {
            voteManager.castVote(id, s.playerUuid, s.playerName,
                politicManager.getPlayerOffice(s.playerUuid), "YES", "SENATE_OVERRIDE");
        }

        p = proposalManager.getProposal(id);
        sendJson(exchange, result.success() ? 200 : 400,
            gson.toJson(Map.of("success", result.success(),
                "newStatus", p != null ? p.status : "",
                "eligibleVoters", voteManager.getEligibleVoterCount("SENATE_OVERRIDE"),
                "message", result.success() ? "覆议已启动，需要全体元老一致同意" : result.error())));
    }

    // ================================================================
    // 选举状态
    // ================================================================

    private void handleElectionStatus(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, gson.toJson(electionEngine.getElectionStatus()));
    }

    // ================================================================
    // 个人信息
    // ================================================================

    private void handleMyOffice(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;

        String office = politicManager.getPlayerOffice(s.playerUuid);
        boolean isSenator = politicManager.isSenator(s.playerUuid);
        boolean isConsul = politicManager.isConsul(s.playerUuid);
        boolean isTribune = politicManager.isTribune(s.playerUuid);
        boolean isEquestrian = politicManager.isEquestrian(s.playerUuid);

        sendJson(exchange, 200, gson.toJson(Map.of(
            "playerUuid", s.playerUuid.toString(),
            "playerName", s.playerName,
            "office", office != null ? office : "NONE",
            "isSenator", isSenator,
            "isConsul", isConsul,
            "isTribune", isTribune,
            "isEquestrian", isEquestrian,
            "canPropose", politicManager.canPropose(s.playerUuid),
            "canVeto", politicManager.canVeto(s.playerUuid),
            "canVoteInSenate", politicManager.canVoteInSenate(s.playerUuid)
        )));
    }

    private void handleMyVotes(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAuth(exchange);
        if (s == null) return;
        List<Map<String, Object>> votes = voteManager.getPlayerVotes(s.playerUuid);
        sendJson(exchange, 200, gson.toJson(Map.of("votes", votes, "count", votes.size())));
    }

    // ================================================================
    // 配置
    // ================================================================

    private void handleConfigGet(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, gson.toJson(Map.of("config", politicManager.getAllConfig())));
    }

    private void handleConfigSet(HttpExchange exchange) throws IOException {
        KsAuthManager.Session s = requireAdmin(exchange);
        if (s == null) return;
        Map<String, Object> body = parseBody(exchange);
        String key = (String) body.get("key");
        String value = (String) body.get("value");
        if (key == null || value == null) {
            sendJson(exchange, 400, "{\"error\":\"需要 key 和 value\"}");
            return;
        }
        politicManager.setConfig(key, value);
        sendJson(exchange, 200, "{\"success\":true}");
    }

    // ================================================================
    // 路径解析工具
    // ================================================================

    private static final String ROUTE_PREFIX = "/ks-Eco/politic";

    private String extractSubPath(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith(ROUTE_PREFIX)) path = path.substring(ROUTE_PREFIX.length());
        return path;
    }

    private String extractProposalId(String path, String suffix) {
        // /ks-Eco/politic/api/proposal/{id}/suffix → {id}
        if (path.startsWith(ROUTE_PREFIX)) path = path.substring(ROUTE_PREFIX.length());
        if (path.startsWith("/api/proposal/")) path = path.substring("/api/proposal/".length());
        if (path.endsWith("/" + suffix)) path = path.substring(0, path.length() - suffix.length() - 1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
