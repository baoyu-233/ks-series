package org.kshwp;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ksHWP -> ks-Eco 的可选窄桥。
 *
 * <p>桥只接收图块渲染完成后已经冻结的 PNG 字节；反射用于避免 ksHWP 对 ks-Eco
 * 产生强编译依赖。所有压缩、分片和 SQL 均由 ks-Eco 的异步发布合同负责。</p>
 */
final class FederatedMapPublisher {
    private static final Gson GSON = new Gson();
    private static final String MEDIA_TYPE = "application/vnd.kseries.hwp-tile+json;version=1";

    private final KsHWP owner;
    private final boolean enabled;
    private final long minIntervalMillis;
    private final int maxPayloadBytes;
    private final Plugin eco;
    private final String nodeId;
    private final Constructor<?> sourceConstructor;
    private final Constructor<?> preparedConstructor;
    private final Object mapKind;
    private final Method publishMethod;
    private final ConcurrentHashMap<String, Long> lastPublished = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TileBundle> tileBundles = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();

    FederatedMapPublisher(KsHWP owner, HwpConfig config) {
        this.owner = owner;
        this.enabled = config.isFederatedMapPublishEnabled();
        this.minIntervalMillis = config.getFederatedMapPublishMinIntervalSeconds() * 1_000L;
        this.maxPayloadBytes = config.getFederatedMapPublishMaxPayloadBytes();

        Plugin foundEco = null;
        String foundNode = null;
        Constructor<?> foundSource = null;
        Constructor<?> foundPrepared = null;
        Object foundMapKind = null;
        Method foundPublish = null;
        if (enabled) {
            try {
                foundEco = Bukkit.getPluginManager().getPlugin("ks-Eco");
                if (foundEco == null || !foundEco.isEnabled()) throw new IllegalStateException("ks-Eco 未启用");
                ClassLoader loader = foundEco.getClass().getClassLoader();
                Class<?> sourceType = Class.forName("org.kseco.crossserver.assets.AssetSource", true, loader);
                Class<?> kindType = Class.forName("org.kseco.crossserver.assets.FederatedSnapshot$Kind", true, loader);
                Class<?> preparedType = Class.forName(
                        "org.kseco.crossserver.assets.FederatedSnapshotPublisher$PreparedSnapshot", true, loader);
                foundSource = sourceType.getConstructor(String.class, String.class, String.class);
                foundMapKind = Enum.valueOf(kindType.asSubclass(Enum.class), "MAP");
                foundPrepared = preparedType.getConstructor(String.class, kindType, sourceType, long.class,
                        String.class, byte[].class, List.class, long.class);
                foundPublish = foundEco.getClass().getMethod("publishFederatedSnapshot", preparedType);
                Method status = foundEco.getClass().getMethod("federatedAssetStatus");
                Object statusValue = status.invoke(foundEco);
                if (!(statusValue instanceof Map<?, ?> statusMap)) throw new IllegalStateException("状态合同不兼容");
                foundNode = String.valueOf(statusMap.get("nodeId"));
                if (!Boolean.TRUE.equals(statusMap.get("enabled"))) {
                    throw new IllegalStateException("ks-Eco cross-server.federated-assets 未启用");
                }
                owner.getLogger().info("跨服 MAP 发布桥已就绪，节点: " + foundNode);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                owner.getLogger().warning("跨服 MAP 发布保持关闭: " + failure.getMessage());
                foundEco = null;
            }
        }
        this.eco = foundEco;
        this.nodeId = foundNode;
        this.sourceConstructor = foundSource;
        this.preparedConstructor = foundPrepared;
        this.mapKind = foundMapKind;
        this.publishMethod = foundPublish;
    }

    void publishTile(String world, int tileX, int tileZ, int zoom, String base64Png) {
        if (!enabled || eco == null || base64Png == null || base64Png.isBlank()) return;
        String canonicalWorld = canonicalId(world);
        String dimension = dimensionId(world);
        String sourceKey = canonicalWorld + "/" + dimension;
        String tileKey = sourceKey + '/' + zoom + '/' + tileX + '/' + tileZ;
        long now = System.currentTimeMillis();
        Long previous = lastPublished.putIfAbsent(tileKey, now);
        if (previous != null) {
            if (now - previous < minIntervalMillis || !lastPublished.replace(tileKey, previous, now)) return;
        }
        try {
            byte[] png = Base64.getDecoder().decode(base64Png);
            byte[] payload = tileBundles.computeIfAbsent(sourceKey, ignored -> new TileBundle())
                    .upsert(canonicalWorld, dimension, tileX, tileZ, zoom, png, now, maxPayloadBytes);
            if (payload == null) {
                owner.logError("federatedMap", "跨服地图图块超过 ksHWP 发布上限",
                        "tile=" + zoom + '/' + tileX + '/' + tileZ + " limit=" + maxPayloadBytes);
                return;
            }
            long nextRevision = revision.updateAndGet(old -> Math.max(old + 1, now));
            Object source = sourceConstructor.newInstance(nodeId, canonicalWorld, dimension);
            Object prepared = preparedConstructor.newInstance(UUID.randomUUID().toString(), mapKind, source,
                    nextRevision, MEDIA_TYPE, payload, List.of(), now);
            Object result = publishMethod.invoke(eco, prepared);
            if (result instanceof CompletionStage<?> stage) {
                stage.whenComplete((ignored, failure) -> {
                    if (failure != null) owner.logError("federatedMap", "跨服地图图块发布失败", rootMessage(failure));
                });
            }
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            owner.logError("federatedMap", "跨服地图发布合同调用失败", rootMessage(failure));
        }
    }

    static byte[] encodeTilePayload(String world, String dimension, int tileX, int tileZ, int zoom,
                                    byte[] png, long producedAt) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "ks-hwp-map-tile/v1");
        envelope.put("world", world);
        envelope.put("dimension", dimension);
        envelope.put("tileX", tileX);
        envelope.put("tileZ", tileZ);
        envelope.put("zoom", zoom);
        envelope.put("producedAt", producedAt);
        envelope.put("pngBase64", Base64.getEncoder().encodeToString(png.clone()));
        return GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] encodeTileBundlePayload(String world, String dimension, List<TileData> tiles, long producedAt) {
        List<Map<String, Object>> encodedTiles = new ArrayList<>(tiles.size());
        for (TileData tile : tiles) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("tileX", tile.tileX());
            encoded.put("tileZ", tile.tileZ());
            encoded.put("zoom", tile.zoom());
            encoded.put("producedAt", tile.producedAt());
            encoded.put("pngBase64", Base64.getEncoder().encodeToString(tile.png()));
            encodedTiles.add(encoded);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "ks-hwp-map-tile-bundle/v1");
        envelope.put("world", world);
        envelope.put("dimension", dimension);
        envelope.put("producedAt", producedAt);
        envelope.put("tiles", encodedTiles);
        return GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    }

    record TileData(int tileX, int tileZ, int zoom, byte[] png, long producedAt) {
        TileData {
            png = png.clone();
        }

        @Override public byte[] png() { return png.clone(); }
    }

    private static final class TileBundle {
        private final LinkedHashMap<String, TileData> tiles = new LinkedHashMap<>(16, 0.75f, true);

        synchronized byte[] upsert(String world, String dimension, int tileX, int tileZ, int zoom,
                                   byte[] png, long producedAt, int maxPayloadBytes) {
            String key = zoom + "/" + tileX + "/" + tileZ;
            tiles.put(key, new TileData(tileX, tileZ, zoom, png, producedAt));
            byte[] payload = encodeTileBundlePayload(world, dimension, List.copyOf(tiles.values()), producedAt);
            while (payload.length > maxPayloadBytes && tiles.size() > 1) {
                String eldest = tiles.keySet().iterator().next();
                tiles.remove(eldest);
                payload = encodeTileBundlePayload(world, dimension, List.copyOf(tiles.values()), producedAt);
            }
            if (payload.length <= maxPayloadBytes) return payload;
            tiles.remove(key);
            return null;
        }
    }

    static String canonicalId(String value) {
        String normalized = value == null ? "world" : value.strip().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9_.:/-]", "_");
        return normalized.isBlank() ? "world" : normalized.substring(0, Math.min(128, normalized.length()));
    }

    static String dimensionId(String world) {
        String normalized = canonicalId(world);
        if (normalized.endsWith("_nether") || normalized.contains("nether")) return "minecraft:the_nether";
        if (normalized.endsWith("_the_end") || normalized.endsWith("_end") || normalized.contains("the_end")) {
            return "minecraft:the_end";
        }
        return "minecraft:overworld";
    }

    private static String rootMessage(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
