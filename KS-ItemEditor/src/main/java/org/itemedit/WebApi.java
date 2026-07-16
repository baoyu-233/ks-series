package org.itemedit;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Registry;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Web API 端点处理器 —— 路由分发 + 各端点业务逻辑。
 * 实现 HttpHandler 以便注册到 ks-core 的 KsRouter。
 */
public final class WebApi implements HttpHandler {
    private static final int MAX_REQUEST_BODY_BYTES = 1_048_576;

    private final JavaPlugin plugin;
    private final TemplateManager templateManager;
    private int tokenTimeout;
    private final Gson gson = new Gson();

    // ★ 原版附魔中文名映射
    private static final Map<String, String> ENCH_CN = new LinkedHashMap<>();
    static {
        // 武器
        ENCH_CN.put("minecraft:sharpness", "锋利");
        ENCH_CN.put("minecraft:smite", "亡灵杀手");
        ENCH_CN.put("minecraft:bane_of_arthropods", "节肢杀手");
        ENCH_CN.put("minecraft:fire_aspect", "火焰附加");
        ENCH_CN.put("minecraft:looting", "抢夺");
        ENCH_CN.put("minecraft:sweeping_edge", "横扫之刃");
        ENCH_CN.put("minecraft:knockback", "击退");
        // 工具
        ENCH_CN.put("minecraft:efficiency", "效率");
        ENCH_CN.put("minecraft:fortune", "时运");
        ENCH_CN.put("minecraft:silk_touch", "精准采集");
        ENCH_CN.put("minecraft:unbreaking", "耐久");
        ENCH_CN.put("minecraft:mending", "经验修补");
        // 盔甲
        ENCH_CN.put("minecraft:protection", "保护");
        ENCH_CN.put("minecraft:fire_protection", "火焰保护");
        ENCH_CN.put("minecraft:blast_protection", "爆炸保护");
        ENCH_CN.put("minecraft:projectile_protection", "弹射物保护");
        ENCH_CN.put("minecraft:feather_falling", "摔落缓冲");
        ENCH_CN.put("minecraft:respiration", "水下呼吸");
        ENCH_CN.put("minecraft:aqua_affinity", "水下速掘");
        ENCH_CN.put("minecraft:thorns", "荆棘");
        ENCH_CN.put("minecraft:depth_strider", "深海探索者");
        ENCH_CN.put("minecraft:frost_walker", "冰霜行者");
        ENCH_CN.put("minecraft:soul_speed", "灵魂疾行");
        ENCH_CN.put("minecraft:swift_sneak", "迅捷潜行");
        // 弓/弩
        ENCH_CN.put("minecraft:power", "力量");
        ENCH_CN.put("minecraft:punch", "冲击");
        ENCH_CN.put("minecraft:flame", "火矢");
        ENCH_CN.put("minecraft:infinity", "无限");
        ENCH_CN.put("minecraft:multishot", "多重射击");
        ENCH_CN.put("minecraft:piercing", "穿透");
        ENCH_CN.put("minecraft:quick_charge", "快速装填");
        // 钓鱼
        ENCH_CN.put("minecraft:luck_of_the_sea", "海之眷顾");
        ENCH_CN.put("minecraft:lure", "饵钓");
        // 三叉戟
        ENCH_CN.put("minecraft:loyalty", "忠诚");
        ENCH_CN.put("minecraft:impaling", "穿刺");
        ENCH_CN.put("minecraft:riptide", "激流");
        ENCH_CN.put("minecraft:channeling", "引雷");
        // 弩（额外）
        ENCH_CN.put("minecraft:wind_burst", "风爆");
        ENCH_CN.put("minecraft:density", "致密");
        ENCH_CN.put("minecraft:breach", "破甲");
        // 锤
        ENCH_CN.put("minecraft:vanishing_curse", "消失诅咒");
        ENCH_CN.put("minecraft:binding_curse", "绑定诅咒");
    }

    // ★ FE 分类中文映射
    private static final Map<String, String> FE_CATEGORY_CN = new LinkedHashMap<>();
    static {
        FE_CATEGORY_CN.put("melee", "近战");
        FE_CATEGORY_CN.put("armor", "护甲");
        FE_CATEGORY_CN.put("ranged", "远程");
        FE_CATEGORY_CN.put("tools", "工具");
        FE_CATEGORY_CN.put("universal", "通用");
        FE_CATEGORY_CN.put("unknown", "未知");
    }

    private Object ksBridge; // ks-core KsPluginBridge 实例（反射调用）

    public WebApi(JavaPlugin plugin, TemplateManager templateManager, int tokenTimeout) {
        this.plugin = plugin;
        this.templateManager = templateManager;
        this.tokenTimeout = tokenTimeout;
    }

    /** 设置 ks-core 桥接（ks-core 模式下使用，用于统一鉴权）。
     *  参数为 Object 类型以支持 KS-ItemEditor 不编译 ks-core 的反射场景。 */
    public void setKsBridge(Object bridge) {
        this.ksBridge = bridge;
    }

    // ---- ks-core 反射调用（避免编译依赖） ----

    private Object validateKsToken(String token) {
        try {
            Method m = ksBridge.getClass().getMethod("validateToken", String.class);
            return m.invoke(ksBridge, token);
        } catch (Exception e) { return null; }
    }

    private boolean touchKsToken(String token) {
        try {
            Method m = ksBridge.getClass().getMethod("touchToken", String.class);
            return (boolean) m.invoke(ksBridge, token);
        } catch (Exception e) { return false; }
    }

    private int getKsTokenTimeout() {
        try {
            Method m = ksBridge.getClass().getMethod("getTokenTimeout");
            return (int) m.invoke(ksBridge);
        } catch (Exception e) { return 600; }
    }

    private void removeKsToken(String token) {
        try {
            Method m = ksBridge.getClass().getMethod("removeToken", String.class);
            m.invoke(ksBridge, token);
        } catch (Exception ignored) {}
    }

    private String refreshKsToken(String oldToken) {
        try {
            // KsPluginBridge.refreshToken(String) 直接返回新 token 字符串（不是 Session 对象）
            Method m = ksBridge.getClass().getMethod("refreshToken", String.class);
            return (String) m.invoke(ksBridge, oldToken);
        } catch (Exception e) { return null; }
    }

    /** 从 ks-core Session 对象反射提取字段 */
    private Map<String, Object> extractKsSession(Object session) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (session == null) return map;
        try {
            map.put("playerUuid", session.getClass().getField("playerUuid").get(session));
            map.put("playerName", session.getClass().getField("playerName").get(session));
            map.put("isAdmin", session.getClass().getField("isAdmin").get(session));
        } catch (Exception ignored) {}
        return map;
    }

    /** 更新 token 超时时间（供 reload 时使用）。 */
    public void setTokenTimeout(int seconds) {
        this.tokenTimeout = seconds;
    }

    /**
     * 主路由入口。
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            // 去除 ks-core 路由前缀 "/IE"
            String subPath = path;
            if (subPath.startsWith("/IE")) {
                subPath = subPath.substring(3); // 去掉 "/IE"
            }
            if (subPath.isEmpty() || subPath.equals("/")) subPath = "/";

            // 添加 CORS 头
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // 路由分发
            if (subPath.equals("/") || subPath.equals("/editor")) {
                serveEditor(exchange);
            } else if (subPath.equals("/api/session")) {
                handleSession(exchange, query);
            } else if (subPath.equals("/api/keep-alive")) {
                handleKeepAlive(exchange, query);
            } else if (subPath.equals("/api/materials")) {
                handleMaterials(exchange);
            } else if (subPath.equals("/api/enchantments")) {
                handleEnchantments(exchange);
            } else if (subPath.equals("/api/fe-enchantments")) {
                handleFeEnchantments(exchange);
            } else if (subPath.equals("/api/ia-items")) {
                handleIaItems(exchange);
            } else if (subPath.equals("/api/template/save") && "POST".equalsIgnoreCase(method)) {
                handleTemplateSave(exchange);
            } else if (subPath.equals("/api/my-templates")) {
                handleMyTemplates(exchange, query);
            } else if (subPath.startsWith("/api/template/") && subPath.length() > 14) {
                String code = subPath.substring(14);
                if ("DELETE".equalsIgnoreCase(method)) {
                    handleTemplateDelete(exchange, code, query);
                } else {
                    handleTemplateLoad(exchange, code, query);
                }
            } else {
                sendJson(exchange, 404, "{\"error\":\"未找到\"}");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Web API 异常: " + e.getMessage());
            try {
                sendJson(exchange, 500, "{\"error\":\"服务器内部错误\"}");
            } catch (IOException ignored) {}
        }
    }

    // ==================== 页面服务 ====================

    private void serveEditor(HttpExchange exchange) throws IOException {
        // ★ 每次请求都重新加载，确保热部署后拿到最新 HTML（内存占用可忽略）
        // ★ 注入路由前缀，使 API 调用指向正确的 /IE/ 路径
        String html = loadEditorHtml();

        // 注入 <base> 标签到 <head> 后，确保相对路径正确
        html = html.replace("<head>", "<head>\n<base href=\"/IE/\">");
        // 注入 JS 全局配置 BASE_PATH
        html = html.replace("<head>", "<head>\n<script>window.BASE='/IE';window.BASE_API='/IE';</script>");
        // 修复绝对路径 API 调用：/api/ → /IE/api/
        html = html.replace("fetch('/api/", "fetch('/IE/api/");
        html = html.replace("\"/api/", "\"/IE/api/");
        html = html.replace("'/api/", "'/IE/api/");

        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String loadEditorHtml() {
        // 从插件资源加载
        try (InputStream in = plugin.getClass().getResourceAsStream("/web/editor.html")) {
            if (in == null) {
                plugin.getLogger().warning("找不到 /web/editor.html 资源文件！");
                return "<html><body><h1>错误：找不到编辑器页面</h1></body></html>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("读取 editor.html 失败: " + e.getMessage());
            return "<html><body><h1>错误：无法加载编辑器页面</h1></body></html>";
        }
    }

    // ==================== 会话 API ====================

    private void handleSession(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        String token = params.get("token");
        if (token == null || token.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"缺少 token 参数\"}");
            return;
        }

        // ks-core 模式：使用 ks-core 统一鉴权
        String playerName;
        boolean isAdmin;
        UUID playerUuid;
        int expiresIn;

        if (ksBridge != null) {
            Object ksSession = validateKsToken(token);
            if (ksSession == null) {
                sendJson(exchange, 401, "{\"error\":\"token 无效或已过期，请在游戏内重新输入 /ie web 或 /design\"}");
                return;
            }
            touchKsToken(token);
            var s = extractKsSession(ksSession);
            playerName = (String) s.get("playerName");
            isAdmin = (boolean) s.get("isAdmin");
            playerUuid = (UUID) s.get("playerUuid");
            expiresIn = getKsTokenTimeout();
        } else {
            // 独立模式：使用内置 WebSessionManager
            WebSessionManager.Session session = WebSessionManager.validate(token, tokenTimeout);
            if (session == null) {
                sendJson(exchange, 401, "{\"error\":\"token 无效或已过期，请在游戏内重新输入 /design\"}");
                return;
            }
            playerName = session.playerName;
            isAdmin = session.isAdmin;
            playerUuid = session.playerUuid;
            expiresIn = tokenTimeout;
        }

        Player player = Bukkit.getPlayer(playerUuid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("playerName", playerName);
        result.put("isAdmin", isAdmin);
        result.put("tokenExpiresIn", expiresIn);

        // ★ FE 附魔兼容性设置（无论管理员还是玩家都需要知道）
        result.put("feSettings", getFeSettings());

        // 获取手持物品数据（转为纯 Map，避免 Gson 2.8 序列化静态内部类污染）
        // ★ 非管理员不泄露 IA 模型和 FE 附魔数据
        if (player != null && player.isOnline()) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (!hand.getType().isAir()) {
                ItemData itemData = ItemSerializer.toItemData(hand, player);
                Map<String, Object> heldMap = itemData.toMap();
                if (!isAdmin) {
                    heldMap.remove("iaModel");
                    heldMap.remove("feEnchantments");
                    heldMap.remove("attributeModifiers");
                }
                result.put("heldItem", heldMap);
            }
        }

        sendJson(exchange, 200, gson.toJson(result));
    }

    // ==================== 保活 API ====================

    private void handleKeepAlive(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        String token = params.get("token");
        if (token == null || token.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"缺少 token\"}");
            return;
        }
        boolean ok;
        int expires;
        if (ksBridge != null) {
            ok = touchKsToken(token);
            expires = getKsTokenTimeout();
        } else {
            ok = WebSessionManager.touch(token, tokenTimeout);
            expires = tokenTimeout;
        }
        if (!ok) {
            sendJson(exchange, 401, "{\"error\":\"token 已过期\"}");
            return;
        }
        sendJson(exchange, 200, "{\"alive\":true,\"expiresIn\":" + expires + "}");
    }

    // ==================== 材料列表 API ====================

    private void handleMaterials(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> materials = new ArrayList<>();
        for (Material mat : Material.values()) {
            if (!mat.isItem()) continue;
            // 过滤掉一些不适合的
            String name = mat.name();
            if (name.startsWith("LEGACY_")) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", name);
            entry.put("category", getCategory(mat));
            materials.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("materials", materials);
        sendJson(exchange, 200, gson.toJson(result));
    }

    private String getCategory(Material mat) {
        String n = mat.name().toLowerCase();
        if (n.contains("sword")) return "武器";
        if (n.contains("axe")) return "工具";
        if (n.contains("pickaxe")) return "工具";
        if (n.contains("shovel") || n.contains("spade")) return "工具";
        if (n.contains("hoe")) return "工具";
        if (n.contains("helmet") || n.contains("chestplate") || n.contains("leggings") || n.contains("boots")) return "盔甲";
        if (n.contains("bow") || n.contains("crossbow") || n.contains("arrow")) return "武器";
        if (n.contains("trident")) return "武器";
        if (n.contains("shield")) return "武器";
        if (n.contains("block") || n.contains("ore") || n.contains("log") || n.contains("plank")) return "方块";
        if (n.contains("ingot") || n.contains("diamond") || n.contains("emerald") || n.contains("netherite")) return "材料";
        if (n.contains("food") || n.contains("apple") || n.contains("meat") || n.contains("fish") || n.contains("bread")) return "食物";
        if (n.startsWith("potion") || n.contains("potion")) return "药水";
        if (n.contains("egg") || n.contains("spawn")) return "生物蛋";
        return "其他";
    }

    // ==================== 附魔列表 API ====================

    private void handleEnchantments(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> enchants = new ArrayList<>();
        for (Enchantment ench : Registry.ENCHANTMENT) {
            String key = ench.getKey().asString();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", key);
            entry.put("name", ENCH_CN.getOrDefault(key, formatEnchId(key)));
            entry.put("maxLevel", ench.getMaxLevel());
            entry.put("isCursed", ench.isCursed());
            enchants.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enchantments", enchants);
        sendJson(exchange, 200, gson.toJson(result));
    }

    private static String formatEnchId(String key) {
        String name = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        name = name.replace('_', ' ');
        if (!name.isEmpty()) name = name.substring(0, 1).toUpperCase() + name.substring(1);
        return name;
    }

    // ==================== FE 附魔列表 API ====================

    private void handleFeEnchantments(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> feEnchants = new ArrayList<>();
        if (FotiaEnchantmentHook.isAvailable()) {
            for (FeEnchantData data : FotiaEnchantmentHook.getEnabledEnchantments()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                String id = data.getId();
                entry.put("id", id);
                // ★ 使用 FE LanguageManager 获取中文名
                String cnName = FotiaEnchantmentHook.getDefaultEnchantName(id);
                if (cnName == null || cnName.isEmpty() || cnName.equals(id)) {
                    cnName = id.replace('_', ' ');
                    if (!cnName.isEmpty()) cnName = cnName.substring(0, 1).toUpperCase() + cnName.substring(1);
                }
                entry.put("name", cnName);
                entry.put("maxLevel", data.getMaxLevel());
                // 分类（近战/护甲/远程/工具/通用）
                String cat = FotiaEnchantmentHook.getEnchantmentCategory(data);
                entry.put("category", cat);
                entry.put("categoryName", FE_CATEGORY_CN.getOrDefault(cat, cat));
                // ★ 附魔冲突列表
                List<String> conflicts = FotiaEnchantmentHook.getEnchantmentConflicts(data);
                entry.put("conflicts", conflicts != null ? conflicts : Collections.emptyList());
                feEnchants.add(entry);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", FotiaEnchantmentHook.isAvailable());
        result.put("categories", new ArrayList<>(FE_CATEGORY_CN.values()));
        result.put("enchantments", feEnchants);
        sendJson(exchange, 200, gson.toJson(result));
    }

    // ==================== IA 物品列表 API ====================

    private void handleIaItems(HttpExchange exchange) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean iaAvailable = Bukkit.getPluginManager().getPlugin("ItemsAdder") != null;
        result.put("available", iaAvailable);
        if (iaAvailable) {
            result.put("items", ItemsAdderHook.allIds());
        } else {
            result.put("items", Collections.emptyList());
        }
        sendJson(exchange, 200, gson.toJson(result));
    }

    // ==================== 模板保存 API ====================

    private void handleTemplateSave(HttpExchange exchange) throws IOException {
        // 读取 body
        String body = readBody(exchange);

        @SuppressWarnings("unchecked")
        Map<String, Object> req = gson.fromJson(body, Map.class);
        if (req == null) {
            sendJson(exchange, 400, "{\"error\":\"请求体格式错误\"}");
            return;
        }

        String token = (String) req.get("token");
        if (token == null || token.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"缺少 token\"}");
            return;
        }

        // 验证 token
        String playerName, playerUuid;
        boolean isAdmin;
        if (ksBridge != null) {
            Object ksSession = validateKsToken(token);
            if (ksSession == null) { sendJson(exchange, 401, "{\"error\":\"token 无效或已过期\"}"); return; }
            var s = extractKsSession(ksSession);
            playerName = (String) s.get("playerName");
            playerUuid = ((UUID) s.get("playerUuid")).toString();
            isAdmin = (boolean) s.get("isAdmin");
            // 刷新 token（refreshKsToken 内部已做 remove+create，不要提前 remove）
            String newToken = refreshKsToken(token);
            token = newToken != null ? newToken : token;
        } else {
            WebSessionManager.Session session = WebSessionManager.validate(token, tokenTimeout);
            if (session == null) { sendJson(exchange, 401, "{\"error\":\"token 无效或已过期\"}"); return; }
            playerName = session.playerName;
            playerUuid = session.playerUuid.toString();
            isAdmin = session.isAdmin;
            // 刷新 token
            WebSessionManager.remove(token);
            WebSessionManager.Session newSession = WebSessionManager.create(session.playerUuid, session.playerName, session.isAdmin);
            token = newSession.token;
        }

        // 解析物品数据
        @SuppressWarnings("unchecked")
        Map<String, Object> itemMap = (Map<String, Object>) req.get("item");
        if (itemMap == null) {
            sendJson(exchange, 400, "{\"error\":\"缺少物品数据\"}");
            return;
        }

        // ★ 管理员可选择导出为管理员模板或普通模板；玩家只能导出普通模板
        boolean adminTemplate = isAdmin;
        if (req.containsKey("adminTemplate") && isAdmin) {
            adminTemplate = Boolean.TRUE.equals(req.get("adminTemplate"));
        }

        // ★ 玩家模板强制剥离 IA 模型、FE 附魔、属性修饰符（管理员专属字段）
        if (!adminTemplate) {
            itemMap.remove("iaModel");
            itemMap.remove("feEnchantments");
            itemMap.remove("attributeModifiers");
        }

        // ★ 自定义名称
        String customName = null;
        Object nameObj = req.get("templateName");
        if (nameObj instanceof String s && !s.isBlank()) {
            customName = s.trim();
            if (customName.length() > 64) customName = customName.substring(0, 64);
        }

        // ★ 直接从 Map 构建 ItemData，避免 Gson 2.8 双转换污染静态内部类字段
        ItemData itemData = ItemSerializer.fromMap(itemMap);

        // ★ 玩家模板：截断附魔等级到自然上限（管理员可能在网页编辑器突破上限后导出为玩家模板）
        if (!adminTemplate) {
            itemData.capEnchantmentLevels();
        }

        // 保存模板
        TemplateManager.Template template = templateManager.save(
                itemData, UUID.fromString(playerUuid), playerName, adminTemplate, customName);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", template.code);
        resp.put("name", template.name != null ? template.name : "");
        resp.put("displayTitle", template.displayTitle());
        resp.put("itemMaterial", template.itemMaterial());
        resp.put("newToken", token);
        resp.put("adminTemplate", template.adminTemplate);
        resp.put("message", "模板已保存（" + (template.adminTemplate ? "管理员专属" : "玩家模板")
                + "）。在游戏内输入: /design load " + template.code);
        sendJson(exchange, 200, gson.toJson(resp));
    }

    // ==================== 模板加载 API ====================

    private void handleTemplateLoad(HttpExchange exchange, String code, String query) throws IOException {
        TemplateManager.Template template = templateManager.load(code);
        if (template == null) {
            sendJson(exchange, 404, "{\"error\":\"模板不存在: " + code + "\"}");
            return;
        }
        if (template.adminTemplate && !isAdminRequest(exchange, query)) {
            sendJson(exchange, 403, "{\"error\":\"管理员模板需要管理员 token\"}");
            return;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", template.code);
        resp.put("name", template.name != null ? template.name : "");
        resp.put("displayTitle", template.displayTitle());
        resp.put("itemMaterial", template.itemMaterial());
        resp.put("createdAt", template.createdAt);
        resp.put("createdBy", template.createdBy != null ? template.createdBy.name : "未知");
        resp.put("adminTemplate", template.adminTemplate);
        resp.put("item", template.item != null ? template.item.toMap() : null);
        sendJson(exchange, 200, gson.toJson(resp));
    }

    // ==================== 我的模板列表 API ====================

    private void handleMyTemplates(HttpExchange exchange, String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        String token = params.get("token");
        if (token == null || token.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"缺少 token\"}");
            return;
        }

        UUID playerUuid;
        if (ksBridge != null) {
            Object ksSession = validateKsToken(token);
            if (ksSession == null) { sendJson(exchange, 401, "{\"error\":\"token 无效或已过期\"}"); return; }
            playerUuid = (UUID) extractKsSession(ksSession).get("playerUuid");
            touchKsToken(token); // 续期
        } else {
            WebSessionManager.Session session = WebSessionManager.validate(token, tokenTimeout);
            if (session == null) { sendJson(exchange, 401, "{\"error\":\"token 无效或已过期\"}"); return; }
            playerUuid = session.playerUuid;
            WebSessionManager.touch(token, tokenTimeout); // 续期
        }

        List<TemplateManager.Template> templates = templateManager.listByPlayer(playerUuid);
        List<Map<String, Object>> list = new ArrayList<>();
        for (TemplateManager.Template t : templates) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", t.code);
            entry.put("name", t.name != null ? t.name : "");
            entry.put("title", t.displayTitle());
            entry.put("material", t.itemMaterial());
            entry.put("createdAt", t.createdAt);
            entry.put("adminTemplate", t.adminTemplate);
            list.add(entry);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("templates", list);
        sendJson(exchange, 200, gson.toJson(resp));
    }

    // ==================== 模板删除 API ====================

    private void handleTemplateDelete(HttpExchange exchange, String code, String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        String token = params.get("token");
        if (token == null || token.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"缺少 token\"}");
            return;
        }

        boolean isAdmin = false;
        if (ksBridge != null) {
            Object ksSession = validateKsToken(token);
            if (ksSession == null) { sendJson(exchange, 401, "{\"error\":\"token 无效或已过期\"}"); return; }
            isAdmin = (boolean) extractKsSession(ksSession).get("isAdmin");
            touchKsToken(token); // 续期
        } else {
            WebSessionManager.Session session = WebSessionManager.validate(token, tokenTimeout);
            if (session == null || !session.isAdmin) {
                sendJson(exchange, 403, "{\"error\":\"仅管理员可删除模板\"}");
                return;
            }
            isAdmin = session.isAdmin;
        }
        if (!isAdmin) {
            sendJson(exchange, 403, "{\"error\":\"仅管理员可删除模板\"}");
            return;
        }

        boolean ok = templateManager.delete(code);
        if (ok) {
            sendJson(exchange, 200, "{\"message\":\"模板已删除\"}");
        } else {
            sendJson(exchange, 404, "{\"error\":\"模板不存在\"}");
        }
    }

    // ==================== 工具方法 ====================

    private String readBody(HttpExchange exchange) throws IOException {
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

    private boolean isAdminRequest(HttpExchange exchange, String query) {
        String token = null;
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) token = auth.substring(7).trim();
        if ((token == null || token.isBlank()) && query != null) token = parseQuery(query).get("token");
        if (token == null || token.isBlank()) return false;
        if (ksBridge != null) {
            Object session = validateKsToken(token);
            return session != null && Boolean.TRUE.equals(extractKsSession(session).get("isAdmin"));
        }
        WebSessionManager.Session session = WebSessionManager.validate(token, tokenTimeout);
        return session != null && session.isAdmin;
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ==================== FE 兼容性配置 ====================

    /** 从 config.yml 读取 FE 兼容性设置，通过 session API 暴露给前端。 */
    private Map<String, Object> getFeSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        try {
            var cfg = plugin.getConfig().getConfigurationSection("fe-enchantments");
            if (cfg != null) {
                settings.put("adminMaxEnchantLevel", cfg.getInt("admin-max-enchant-level", 32767));
                settings.put("ignoreVanillaConflicts", cfg.getBoolean("ignore-vanilla-conflicts", false));
                settings.put("ignoreFeConflicts", cfg.getBoolean("ignore-fe-conflicts", false));
                settings.put("showCompatibilityWarnings", cfg.getBoolean("show-compatibility-warnings", true));
            } else {
                settings.put("adminMaxEnchantLevel", 32767);
                settings.put("ignoreVanillaConflicts", false);
                settings.put("ignoreFeConflicts", false);
                settings.put("showCompatibilityWarnings", true);
            }
        } catch (Exception e) {
            settings.put("adminMaxEnchantLevel", 32767);
            settings.put("ignoreVanillaConflicts", false);
            settings.put("ignoreFeConflicts", false);
            settings.put("showCompatibilityWarnings", true);
        }
        return settings;
    }

    private Map<String, String> parseQuery(String query) {
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
}
