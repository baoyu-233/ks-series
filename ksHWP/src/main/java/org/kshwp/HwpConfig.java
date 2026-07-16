package org.kshwp;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * ksHWP 配置管理。
 */
public final class HwpConfig {

    private final JavaPlugin plugin;

    // --- 地图渲染 ---
    private boolean mapEnabled;
    private int maxChunksPerRequest;
    private int renderTimeoutSeconds;
    private int chunkCacheSeconds;
    private String defaultWorld;
    private boolean showPlayers;
    private int playerRefreshSeconds;

    // --- 预渲染 ---
    private boolean preRenderEnabled;
    private int preRenderRadius;
    private List<Integer> preRenderZoomLevels;
    private int preRenderMaxQueue;
    private int nearbyPlayerChunkRadius;
    private int nearbyPlayerIntervalTicks;
    private int nearbyPlayerMaxEnqueuePerCycle;

    // --- 图层 ---
    private boolean layerPlayersEnabled;
    private int layerPlayersRefreshSeconds;
    private boolean layerAnnotationsEnabled;

    // --- 备注 ---
    private boolean annotationsEnabled;
    private int maxPerPlayer;
    private int maxTextLength;

    private String route;

    public HwpConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    @SuppressWarnings("unchecked")
    public void reload() {
        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        // ---- 地图渲染 ----
        var map = cfg.getConfigurationSection("map");
        if (map != null) {
            mapEnabled = map.getBoolean("enabled", true);
            maxChunksPerRequest = map.getInt("max-chunks-per-request", 256);
            renderTimeoutSeconds = map.getInt("render-timeout-seconds", 30);
            chunkCacheSeconds = map.getInt("chunk-cache-seconds", 300);
            defaultWorld = map.getString("default-world", "world");
            showPlayers = map.getBoolean("show-players", true);
            playerRefreshSeconds = map.getInt("player-refresh-seconds", 5);
        } else {
            mapEnabled = true; maxChunksPerRequest = 256;
            renderTimeoutSeconds = 30; chunkCacheSeconds = 300;
            defaultWorld = "world"; showPlayers = true; playerRefreshSeconds = 5;
        }

        // ---- 预渲染 ----
        var pre = cfg.getConfigurationSection("pre-render");
        if (pre != null) {
            preRenderEnabled = pre.getBoolean("enabled", true);
            preRenderRadius = pre.getInt("radius", 5);
            preRenderZoomLevels = (List<Integer>) pre.getList("zoom-levels", List.of(2, 4));
            preRenderMaxQueue = pre.getInt("max-queue", 128);
            nearbyPlayerChunkRadius = Math.max(0, pre.getInt("nearby-player-chunk-radius", 6));
            nearbyPlayerIntervalTicks = Math.max(20, pre.getInt("nearby-player-interval-ticks", 100));
            nearbyPlayerMaxEnqueuePerCycle = Math.max(1, pre.getInt("nearby-player-max-enqueue-per-cycle", 12));
        } else {
            preRenderEnabled = true; preRenderRadius = 5;
            preRenderZoomLevels = List.of(2, 4); preRenderMaxQueue = 128;
            nearbyPlayerChunkRadius = 6; nearbyPlayerIntervalTicks = 100;
            nearbyPlayerMaxEnqueuePerCycle = 12;
        }

        // ---- 图层 ----
        var layer = cfg.getConfigurationSection("layers");
        if (layer != null) {
            layerPlayersEnabled = layer.getBoolean("players.enabled", true);
            layerPlayersRefreshSeconds = layer.getInt("players.refresh-seconds", 5);
            layerAnnotationsEnabled = layer.getBoolean("annotations.enabled", true);
        } else {
            layerPlayersEnabled = true; layerPlayersRefreshSeconds = 5;
            layerAnnotationsEnabled = true;
        }

        // ---- 备注 ----
        var anno = cfg.getConfigurationSection("annotations");
        if (anno != null) {
            annotationsEnabled = anno.getBoolean("enabled", true);
            maxPerPlayer = anno.getInt("max-per-player", 100);
            maxTextLength = anno.getInt("max-text-length", 200);
        } else {
            annotationsEnabled = true; maxPerPlayer = 100; maxTextLength = 200;
        }

        route = cfg.getString("web.route", "/kSHWP");
    }

    // ---- Getters ----

    public boolean isMapEnabled() { return mapEnabled; }
    public int getMaxChunksPerRequest() { return maxChunksPerRequest; }
    public int getRenderTimeoutSeconds() { return renderTimeoutSeconds; }
    public int getChunkCacheSeconds() { return chunkCacheSeconds; }
    public String getDefaultWorld() { return defaultWorld; }
    public boolean isShowPlayers() { return showPlayers; }
    public int getPlayerRefreshSeconds() { return playerRefreshSeconds; }

    public boolean isPreRenderEnabled() { return preRenderEnabled; }
    public int getPreRenderRadius() { return preRenderRadius; }
    public List<Integer> getPreRenderZoomLevels() { return preRenderZoomLevels; }
    public int getPreRenderMaxQueue() { return preRenderMaxQueue; }
    public int getNearbyPlayerChunkRadius() { return nearbyPlayerChunkRadius; }
    public int getNearbyPlayerIntervalTicks() { return nearbyPlayerIntervalTicks; }
    public int getNearbyPlayerMaxEnqueuePerCycle() { return nearbyPlayerMaxEnqueuePerCycle; }

    public boolean isLayerPlayersEnabled() { return layerPlayersEnabled; }
    public int getLayerPlayersRefreshSeconds() { return layerPlayersRefreshSeconds; }
    public boolean isLayerAnnotationsEnabled() { return layerAnnotationsEnabled; }

    public boolean isAnnotationsEnabled() { return annotationsEnabled; }
    public int getMaxPerPlayer() { return maxPerPlayer; }
    public int getMaxTextLength() { return maxTextLength; }
    public String getRoute() { return route; }
}
