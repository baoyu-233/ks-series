package org.kshwp;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.kscore.KsCore;
import org.kscore.KsPluginBridge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * ksHWP — 硬世界地图插件。
 * 提供 Web 端地图渲染，支持维度/世界切换、个人地图备注。
 * 依赖 ks-core 提供 Web 网关和 Token 鉴权。
 */
public final class KsHWP extends JavaPlugin {

    private KsCore ksCore;
    private KsPluginBridge bridge;
    private HwpConfig hwpConfig;
    private MapRenderer mapRenderer;
    private MapAnnotationManager annotationManager;
    private HwpWebHandler webHandler;
    private volatile FederatedMapPublisher federatedMapPublisher;
    private BukkitTask nearbyPreRenderTask;

    /** Hidden 模式玩家集合 — key=player UUID */
    private final Set<UUID> hiddenPlayers = ConcurrentHashMap.newKeySet();

    /** 最近错误收集（环形缓冲区，最多 128 条） */
    private final Deque<Map<String, Object>> recentErrors = new ConcurrentLinkedDeque<>();
    private static final int MAX_ERRORS = 128;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 检查 ks-core 依赖
        Plugin corePlugin = Bukkit.getPluginManager().getPlugin("ks-core");
        if (corePlugin instanceof KsCore core) {
            this.ksCore = core;
            this.bridge = core.bridge();
        } else {
            getLogger().severe("ks-core 未找到！ksHWP 需要 ks-core。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.hwpConfig = new HwpConfig(this);
        this.federatedMapPublisher = new FederatedMapPublisher(this, hwpConfig);
        TileStore tileStore = new TileStore(getDataFolder().toPath());
        this.mapRenderer = new MapRenderer(this, tileStore);
        this.annotationManager = new MapAnnotationManager(this);
        startNearbyPlayerPreRender();

        // 注册事件监听器
        Bukkit.getPluginManager().registerEvents(new KsMapChunkListener(this), this);

        // 注册命令
        if (getCommand("kshwp") != null) getCommand("kshwp").setExecutor(this);
        if (getCommand("map") != null) getCommand("map").setExecutor(this);
        if (getCommand("mapnote") != null) getCommand("mapnote").setExecutor(this);

        // 注册 Web 路由到 ks-core
        this.webHandler = new HwpWebHandler(this);
        if (bridge.isPluginRouteEnabled("kshwp")) {
            String route = bridge.getPluginRoute("kshwp");
            bridge.registerRoute("kshwp", route, webHandler);
        } else {
            getLogger().info("ksHWP 在 ks-core 配置中未启用，路由未注册。");
        }

        getLogger().info("ksHWP 已启用。Web 地图: " +
                (bridge.isPluginRouteEnabled("kshwp")
                        ? "http://" + ksCore.ksConfig().getPublicAddress() + ":" + ksCore.ksConfig().getPort() + bridge.getPluginRoute("kshwp")
                        : "未在 ks-core 中启用"));
        getLogger().info("检测到世界: " + Bukkit.getWorlds().size() + " 个");
    }

    @Override
    public void onDisable() {
        if (nearbyPreRenderTask != null) {
            nearbyPreRenderTask.cancel();
            nearbyPreRenderTask = null;
        }
        if (bridge != null) {
            bridge.unregisterRoute("kshwp");
        }
        if (mapRenderer != null) {
            mapRenderer.shutdown();
        }
        hiddenPlayers.clear();
        getLogger().info("ksHWP 已停用。");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String cmd = command.getName().toLowerCase();

        switch (cmd) {
            case "kshwp":
                return handleAdmin(sender, args);
            case "map":
                return handleMap(sender, args);
            case "mapnote":
                return handleNote(sender, args);
            default:
                return false;
        }
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kshwp.admin")) {
            sender.sendMessage("§c权限不足。");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§6[ksHWP] §e/kshwp reload §7— 重载配置");
            sender.sendMessage("§6[ksHWP] §e/kshwp status §7— 查看状态");
            sender.sendMessage("§6[ksHWP] §e/kshwp forcerender [世界名] §7— 强制渲染已加载区域");
            sender.sendMessage("§6[ksHWP] §e/kshwp forcerender-area <世界名> <x1> <z1> <x2> <z2> §7— 强制渲染指定区域");
            sender.sendMessage("§6[ksHWP] §e/kshwp prerender <世界名> §7— 触发预渲染");
            sender.sendMessage("§6[ksHWP] §e/kshwp cache §7— 查看缓存状态");
            sender.sendMessage("§6[ksHWP] §e/kshwp clearcache [世界名] §7— 清除指定世界缓存（管理员）");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                reloadConfig();
                hwpConfig = new HwpConfig(this);
                federatedMapPublisher = new FederatedMapPublisher(this, hwpConfig);
                restartNearbyPlayerPreRender();
                sender.sendMessage("§a配置已重载。");
                break;
            case "status":
                sender.sendMessage("§6[ksHWP 状态]");
                sender.sendMessage("§7  世界数: §f" + Bukkit.getWorlds().size());
                for (var w : Bukkit.getWorlds()) {
                    sender.sendMessage("§7    - " + w.getName() + " (" + w.getEnvironment().name() + ") 区块: " + w.getLoadedChunks().length);
                }
                sender.sendMessage("§7  备注数: §f" + annotationManager.totalAnnotations());
                sender.sendMessage("§7  隐藏玩家: §f" + hiddenPlayers.size());
                break;
            case "forcerender":
                {
                    String worldName = args.length >= 2 ? args[1] : hwpConfig.getDefaultWorld();
                    sender.sendMessage("§e正在强制渲染世界 " + worldName + " 的已加载区域...");
                    var result = mapRenderer.forceRenderAllLoaded(worldName);
                    if (result.containsKey("error")) {
                        sender.sendMessage("§c" + result.get("error"));
                    } else if (result.containsKey("message")) {
                        sender.sendMessage("§7" + result.get("message"));
                    } else {
                        sender.sendMessage("§a强制渲染完成！图块: " + result.get("rendered") +
                                ", 失败: " + result.get("failed") +
                                ", 区块: " + result.get("loadedChunks") + "/" + result.get("totalChunks") +
                                ", 耗时: " + result.get("elapsedMs") + "ms");
                    }
                }
                break;
            case "forcerender-area":
                if (args.length < 6) {
                    sender.sendMessage("§c用法: /kshwp forcerender-area <世界名> <x1> <z1> <x2> <z2>");
                    return true;
                }
                try {
                    String wName = args[1];
                    int x1 = Integer.parseInt(args[2]);
                    int z1 = Integer.parseInt(args[3]);
                    int x2 = Integer.parseInt(args[4]);
                    int z2 = Integer.parseInt(args[5]);
                    sender.sendMessage("§e正在强制渲染区域 " + wName + " (" + x1 + "," + z1 + ")→(" + x2 + "," + z2 + ")...");
                    var result = mapRenderer.forceRenderArea(wName, x1, z1, x2, z2);
                    sender.sendMessage("§a渲染完成！图块: " + result.get("rendered") +
                            ", 失败: " + result.get("failed") +
                            ", 加载区块: " + result.get("loadedChunks") + "/" + result.get("totalChunks") +
                            ", 耗时: " + result.get("elapsedMs") + "ms");
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c坐标参数必须是整数。");
                }
                break;
            case "prerender":
                {
                    String wName = args.length >= 2 ? args[1] : hwpConfig.getDefaultWorld();
                    var world = Bukkit.getWorld(wName);
                    if (world == null) {
                        sender.sendMessage("§c世界未找到: " + wName);
                        return true;
                    }
                    var spawn = world.getSpawnLocation();
                    int ccx = spawn.getBlockX() >> 4;
                    int ccz = spawn.getBlockZ() >> 4;
                    mapRenderer.preRenderWorld(wName, ccx, ccz);
                    sender.sendMessage("§a预渲染任务已加入队列！世界: " + wName +
                            ", 中心区块: (" + ccx + "," + ccz + ")" +
                            ", 半径: " + hwpConfig.getPreRenderRadius() +
                            ", 缩放级别: " + hwpConfig.getPreRenderZoomLevels());
                }
                break;
            case "cache":
                {
                    var stats = mapRenderer.getCacheStats();
                    sender.sendMessage("§6[缓存状态]");
                    sender.sendMessage("§7  内存图块: §f" + stats.get("memoryTiles"));
                    sender.sendMessage("§7  磁盘图块: §f" + stats.get("diskTiles"));
                    sender.sendMessage("§7  磁盘占用: §f" + (Long.parseLong(String.valueOf(stats.get("diskBytes"))) / 1024) + " KB");
                    sender.sendMessage("§7  预渲染队列: §f" + stats.get("preRenderQueueSize"));
                }
                break;
            case "clearcache":
                {
                    String wName = args.length >= 2 ? args[1] : hwpConfig.getDefaultWorld();
                    var result = mapRenderer.clearAllCache(wName);
                    sender.sendMessage("§a" + result.get("message"));
                }
                break;
            default:
                sender.sendMessage("§c未知参数。使用 /kshwp 查看帮助。");
        }
        return true;
    }

    private boolean handleMap(CommandSender sender, String[] args) {
        // 解析子命令
        if (args.length >= 1 && "hidden".equalsIgnoreCase(args[0])) {
            return handleHidden(sender);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!bridge.isPluginRouteEnabled("kshwp")) {
            player.sendMessage("§c地图功能未启用。");
            return true;
        }
        String link = bridge.createWebLink(player, player.hasPermission("kshwp.admin"),
                bridge.getPluginRoute("kshwp"));
        player.sendMessage("§a地图链接（点击打开）: " + link);
        // 发送可点击的文本组件
        player.sendMessage(net.kyori.adventure.text.Component.text("§e点击查看地图")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link)));
        return true;
    }

    /**
     * /map hidden — 切换隐藏模式。
     */
    private boolean handleHidden(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (!player.hasPermission("kshwp.hidden")) {
            player.sendMessage("§c你没有权限使用隐藏模式。需要 kshwp.hidden 权限。");
            return true;
        }

        UUID uuid = player.getUniqueId();
        if (hiddenPlayers.contains(uuid)) {
            hiddenPlayers.remove(uuid);
            player.sendMessage("§a隐藏模式已§l关闭§a。你的位置将在地图上可见。");
            getLogger().info("玩家 " + player.getName() + " 关闭了隐藏模式");
        } else {
            hiddenPlayers.add(uuid);
            player.sendMessage("§e隐藏模式已§l开启§e。你的位置已从地图上隐藏。");
            getLogger().info("玩家 " + player.getName() + " 开启了隐藏模式");
        }
        return true;
    }

    private boolean handleNote(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c仅玩家可使用此命令。");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§6[地图备注] §e/mapnote add <文本> §7— 在当前位置添加备注");
            player.sendMessage("§6[地图备注] §e/mapnote list §7— 查看我的备注列表");
            player.sendMessage("§6[地图备注] §e/mapnote delete <ID> §7— 删除备注");
            player.sendMessage("§6[地图备注] §e/mapnote xaero §7— 发送 Xaero 路径点到聊天框（点击即可加入小地图）");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "xaero":
                sendXaeroChatWaypoints(player);
                break;
            case "add":
                if (args.length < 2) {
                    player.sendMessage("§c用法: /mapnote add <文本>");
                    return true;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                var annotation = annotationManager.add(
                        player.getUniqueId(), player.getName(),
                        player.getWorld().getName(),
                        player.getLocation().getBlockX(),
                        player.getLocation().getBlockY(),
                        player.getLocation().getBlockZ(),
                        text);
                if (annotation != null) {
                    player.sendMessage("§a备注已添加！ID: " + annotation.id() + " 位置: " +
                            annotation.x() + ", " + annotation.y() + ", " + annotation.z());
                } else {
                    player.sendMessage("§c备注添加失败（可能已达上限）。");
                }
                break;
            case "list":
                var list = annotationManager.getPlayerAnnotations(player.getUniqueId());
                if (list.isEmpty()) {
                    player.sendMessage("§7你还没有地图备注。");
                } else {
                    player.sendMessage("§6你的地图备注 (" + list.size() + " 条):");
                    for (var a : list) {
                        player.sendMessage("§7  [" + a.id() + "] §f" + a.world() +
                                " (" + a.x() + "," + a.y() + "," + a.z() + ") §7— " +
                                (a.text().length() > 30 ? a.text().substring(0, 30) + "..." : a.text()));
                    }
                }
                break;
            case "delete":
                if (args.length < 2) {
                    player.sendMessage("§c用法: /mapnote delete <ID>");
                    return true;
                }
                if (annotationManager.delete(args[1], player.getUniqueId())) {
                    player.sendMessage("§a备注已删除。");
                } else {
                    player.sendMessage("§c备注不存在或不属于你。");
                }
                break;
            default:
                player.sendMessage("§c未知参数。");
        }
        return true;
    }

    /** 把玩家当前世界可见的标注以 xaero-waypoint 聊天格式发送，点击即可加入 Xaero 小地图。 */
    private void sendXaeroChatWaypoints(Player player) {
        String world = player.getWorld().getName();
        var mine = annotationManager.getWorldAnnotations(world, player.getUniqueId());
        var pub = annotationManager.getPublicAnnotations(world);
        var lines = XaeroWaypointExporter.toChatLines(mine, pub, player.getWorld().getEnvironment());

        if (lines.isEmpty()) {
            player.sendMessage("§7该世界暂无可导出的标注。");
            return;
        }

        player.sendMessage("§6[ksHWP] §e以下 " + lines.size() + " 条路径点点击即可加入 Xaero 小地图：");
        for (String line : lines) {
            player.sendMessage("§7" + line);
        }
    }

    // ---- Hidden 模式 ----

    /** 检查玩家是否处于隐藏模式。 */
    public boolean isPlayerHidden(UUID playerUuid) {
        return hiddenPlayers.contains(playerUuid);
    }

    /** 获取隐藏模式玩家数量。 */
    public int getHiddenPlayerCount() {
        return hiddenPlayers.size();
    }

    // ---- 错误收集 ----

    /** 记录一条错误（供 /api/debug 查询）。 */
    public void logError(String source, String message, String detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", System.currentTimeMillis());
        entry.put("source", source);
        entry.put("message", message);
        if (detail != null) entry.put("detail", detail);
        recentErrors.addLast(entry);
        while (recentErrors.size() > MAX_ERRORS) recentErrors.pollFirst();
        getLogger().warning("[" + source + "] " + message + (detail != null ? " — " + detail : ""));
    }

    /** 获取最近错误列表。 */
    public List<Map<String, Object>> getRecentErrors() {
        return new ArrayList<>(recentErrors);
    }

    /** 收集完整的调试信息。 */
    public Map<String, Object> collectDebugInfo() {
        Map<String, Object> debug = new LinkedHashMap<>();

        // 服务器信息
        debug.put("serverVersion", Bukkit.getVersion());
        debug.put("bukkitVersion", Bukkit.getBukkitVersion());
        debug.put("onlineMode", Bukkit.getOnlineMode());

        // 插件状态
        Map<String, Object> pluginInfo = new LinkedHashMap<>();
        pluginInfo.put("version", getDescription().getVersion());
        pluginInfo.put("enabled", isEnabled());
        pluginInfo.put("bridge", bridge != null ? "connected" : "null");
        pluginInfo.put("ksCoreVersion", ksCore != null ? ksCore.getDescription().getVersion() : "null");
        debug.put("plugin", pluginInfo);

        // Web 路由状态
        Map<String, Object> routeInfo = new LinkedHashMap<>();
        if (bridge != null) {
            routeInfo.put("kshwpEnabledInCore", bridge.isPluginRouteEnabled("kshwp"));
            routeInfo.put("route", bridge.isPluginRouteEnabled("kshwp") ? bridge.getPluginRoute("kshwp") : "disabled");
            routeInfo.put("webAddress", ksCore != null && ksCore.ksConfig() != null
                    ? (ksCore.ksConfig().getPublicAddress() + ":" + ksCore.ksConfig().getPort()) : "unknown");
        } else {
            routeInfo.put("error", "ks-core bridge 为 null!");
        }
        debug.put("route", routeInfo);

        // 世界/维度
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (var w : Bukkit.getWorlds()) {
            Map<String, Object> winfo = new LinkedHashMap<>();
            winfo.put("name", w.getName());
            winfo.put("environment", w.getEnvironment().name());
            winfo.put("loadedChunks", w.getLoadedChunks().length);
            winfo.put("players", w.getPlayers().size());
            winfo.put("seed", w.getSeed());
            worlds.add(winfo);
        }
        debug.put("worlds", worlds);
        debug.put("worldCount", worlds.size());

        // 在线玩家
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> pinfo = new LinkedHashMap<>();
            pinfo.put("name", p.getName());
            pinfo.put("uuid", p.getUniqueId().toString());
            pinfo.put("world", p.getWorld().getName());
            pinfo.put("hidden", isPlayerHidden(p.getUniqueId()));
            players.add(pinfo);
        }
        debug.put("players", players);
        debug.put("playerCount", players.size());

        // 渲染状态
        if (mapRenderer != null) {
            debug.put("cache", mapRenderer.getCacheStats());
        }

        // 备注统计
        if (annotationManager != null) {
            debug.put("annotationCount", annotationManager.totalAnnotations());
        }

        // 配置摘要
        Map<String, Object> configSummary = new LinkedHashMap<>();
        configSummary.put("mapEnabled", hwpConfig != null && hwpConfig.isMapEnabled());
        configSummary.put("defaultWorld", hwpConfig != null ? hwpConfig.getDefaultWorld() : "null");
        configSummary.put("preRenderEnabled", hwpConfig != null && hwpConfig.isPreRenderEnabled());
        configSummary.put("showPlayers", hwpConfig != null && hwpConfig.isShowPlayers());
        configSummary.put("layersPlayers", hwpConfig != null && hwpConfig.isLayerPlayersEnabled());
        configSummary.put("layersAnnotations", hwpConfig != null && hwpConfig.isLayerAnnotationsEnabled());
        debug.put("config", configSummary);

        // 隐藏玩家
        debug.put("hiddenPlayers", hiddenPlayers.size());

        // 最近错误
        debug.put("recentErrors", getRecentErrors());
        debug.put("recentErrorCount", recentErrors.size());

        return debug;
    }

    // ---- Getters ----

    public KsCore ksCore() { return ksCore; }
    public KsPluginBridge bridge() { return bridge; }
    public HwpConfig hwpConfig() { return hwpConfig; }
    public MapRenderer mapRenderer() { return mapRenderer; }
    public MapAnnotationManager annotationManager() { return annotationManager; }
    FederatedMapPublisher federatedMapPublisher() { return federatedMapPublisher; }

    private void startNearbyPlayerPreRender() {
        if (!hwpConfig.isPreRenderEnabled()) return;
        nearbyPreRenderTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> mapRenderer.preRenderAroundOnlinePlayers(),
                40L, hwpConfig.getNearbyPlayerIntervalTicks());
    }

    private void restartNearbyPlayerPreRender() {
        if (nearbyPreRenderTask != null) {
            nearbyPreRenderTask.cancel();
            nearbyPreRenderTask = null;
        }
        startNearbyPlayerPreRender();
    }
}
