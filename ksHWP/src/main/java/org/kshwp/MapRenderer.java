package org.kshwp;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 地图渲染引擎 — 公共社区地图模式。
 *
 * 核心设计:
 * - 持久化磁盘缓存: 渲染过的图块永久保存，切换世界不丢失
 * - 未探索区块: 无缓存 + 无加载区块 → 红色"未探索"图块
 * - 缓存优先: 所有区域默认读磁盘缓存，管理员才能全量重载
 * - 玩家可更新: 已探索区域可由玩家触发重渲染（反应建筑变更）
 */
public final class MapRenderer {

    private final KsHWP plugin;
    private final TileStore tileStore;
    /** 内存图块缓存：world:zoom:x:z → base64 PNG */
    private final Map<String, CachedTile> tileCache = new ConcurrentHashMap<>();
    /** 缓存访问时间追踪，用于 LRU 淘汰 */
    private final Map<String, Long> cacheAccessTime = new ConcurrentHashMap<>();
    /** 正在生成的图块；同一图块并发请求共享一个 Future，避免重复压缩和磁盘写入。 */
    private final Map<String, CompletableFuture<String>> renderInFlight = new ConcurrentHashMap<>();
    /** 异步渲染线程池（避免阻塞 HTTP 线程） */
    private final ExecutorService renderExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ksHWP-Renderer");
        t.setDaemon(true);
        return t;
    });
    /** 预渲染线程池 */
    private final ExecutorService preRenderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ksHWP-PreRenderer");
        t.setDaemon(true);
        return t;
    });
    /** 预渲染队列 */
    private final ConcurrentLinkedQueue<PreRenderTask> preRenderQueue = new ConcurrentLinkedQueue<>();
    /** 预渲染队列大小追踪 */
    private final AtomicInteger preRenderQueueSize = new AtomicInteger(0);
    /** Set of tile keys already queued for pre-render (prevents duplicates) */
    private final Set<String> preRenderQueued = ConcurrentHashMap.newKeySet();

    /** 原版地图颜色缓存：Material → 官方地图色（透明用哨兵值标记） */
    private static final Map<Material, Color> MAP_COLOR_CACHE = new ConcurrentHashMap<>();
    private static final Color TRANSPARENT_SENTINEL = new Color(0, 0, 0, 0);

    /**
     * 获取方块的原版地图颜色（BlockData.getMapColor）。
     * 玻璃/屏障等在原版地图上透明的方块返回 null，调用方应继续向下扫描。
     */
    private static Color blockMapColor(Material mat) {
        Color cached = MAP_COLOR_CACHE.computeIfAbsent(mat, m -> {
            try {
                org.bukkit.Color mc = m.createBlockData().getMapColor();
                if (mc.getRed() == 0 && mc.getGreen() == 0 && mc.getBlue() == 0) {
                    return TRANSPARENT_SENTINEL; // MapColor NONE（透明）
                }
                return new Color(mc.getRed(), mc.getGreen(), mc.getBlue());
            } catch (Exception e) {
                return TRANSPARENT_SENTINEL;
            }
        });
        return cached == TRANSPARENT_SENTINEL ? null : cached;
    }

    public MapRenderer(KsHWP plugin, TileStore tileStore) {
        this.plugin = plugin;
        this.tileStore = tileStore;
        // 启动后台预渲染消费者
        startPreRenderConsumer();
    }

    /**
     * 获取所有可用世界的列表（含维度信息）。
     */
    public List<Map<String, Object>> getWorlds() {
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", w.getName());
            info.put("environment", w.getEnvironment().name());
            info.put("dimension", getDimensionLabel(w.getEnvironment()));
            info.put("loadedChunks", w.getLoadedChunks().length);
            worlds.add(info);
        }
        return worlds;
    }

    /**
     * 渲染指定区域的俯瞰图块。
     * @param worldName 世界名
     * @param cx 区块 X
     * @param cz 区块 Z
     * @param zoom 缩放级别（1=1区块, 2=2×2区块, 以此类推）
     * @return base64 PNG 或 null
     */
    /**
     * 渲染指定图块。
     *
     * 公共社区地图策略:
     * 1. 内存缓存命中 → 直接返回
     * 2. 磁盘缓存命中 → 直接返回（长期有效）
     * 3. zoom=1（单区块）：区块已加载 → 直接扫描渲染；否则 → 未探索占位
     * 4. zoom&gt;=2：递归从 zoom/2 的 4 个子图块合成（子图块各自走本流程，逐级向下最终落到 zoom=1）
     *
     * 只有 zoom=1 会直接扫描世界区块 —— 避免了旧实现里"zoom×zoom 大范围内只要有 1 个区块加载
     * 就整块一次性栅格化"的问题：大范围里其余未加载区块会被填充成默认海洋蓝并永久写入磁盘缓存，
     * 之后即使真实区域被探索过也无法覆盖（表现为"跑过的区块缩放后又变红/变蓝"）。
     * 递归合成天然做到"有真实数据的部分显示真实数据，没有的部分显示未探索"，且不会把"全未探索"
     * 的合成结果写入磁盘（避免过早缓存导致后续探索永远无法更新该图块）。
     *
     * 重要约束：zoom 必须是 2 的幂（1/2/4/8）。resolveTileImage 用 zoom/2 整数除法递归二分，
     * 非 2 的幂（3/5/6/7）会让 subZoom 截断丢精度（比如 zoom=3 时 subZoom=3/2=1，
     * 实际只合成了 2 个区块宽的内容却当成 3 个区块宽塞进同一张图），导致服务端渲染出的地形
     * 范围和客户端 worldToPixel/pixelToWorld 算出的世界坐标对不上——表现为"缩放后玩家位置点
     * 相对地形滑开了"。前端 map.html 的 nextZoom() 只在 1/2/4/8 间跳，不会传非法值；
     * 这里再兜底一次防止 API 直接被传 zoom=3 之类的值。
     */
    private static int snapZoom(int zoom) {
        if (zoom <= 1) return 1;
        if (zoom >= 8) return 8;
        if (zoom >= 4) return 4;
        return 2;
    }

    public String renderTile(String worldName, int cx, int cz, int zoomRaw) {
        int zoom = snapZoom(zoomRaw);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.logError("renderTile", "世界未找到", "worldName=" + worldName + " available=" + Bukkit.getWorlds().stream().map(World::getName).toList());
            return null;
        }

        String cacheKey = worldName + ":" + zoom + ":" + cx + ":" + cz;
        cacheAccessTime.put(cacheKey, System.currentTimeMillis());

        CachedTile cached = tileCache.get(cacheKey);
        if (cached != null) {
            return cached.base64Png;
        }

        String diskTile = tileStore.load(worldName, zoom, cx, cz);
        if (diskTile != null) {
            tileCache.put(cacheKey, new CachedTile(diskTile, System.currentTimeMillis(), true));
            return diskTile;
        }

        TileImageResult result = resolveTileImage(world, worldName, cx, cz, zoom);
        String base64 = encodeImage(result.image());
        if (base64 == null) {
            plugin.logError("renderTile", "图块编码失败", "world=" + worldName + " zoom=" + zoom + " cx=" + cx + " cz=" + cz);
            return null;
        }

        tileCache.put(cacheKey, new CachedTile(base64, System.currentTimeMillis(), result.hasRealData()));
        if (result.hasRealData()) {
            // 只有含真实探索数据的图块才落盘，纯"未探索"合成结果只留内存缓存，
            // 保证之后一旦区域被探索，重新请求能穿透旧的占位结果重新合成。
            tileStore.save(worldName, zoom, cx, cz, base64);
            evictCacheIfNeeded();
        }
        return base64;
    }

    /** 解析出图块的图像内容 + 是否含有真实探索数据。zoom=1 为基础层，zoom&gt;=2 递归合成。 */
    private TileImageResult resolveTileImage(World world, String worldName, int tx, int tz, int zoom) {
        if (zoom == 1) {
            if (world.isChunkLoaded(tx, tz)) {
                int tileSize = 256;
                BufferedImage img = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                renderChunkSection(world, g, tx * 16, tz * 16, 0, 0, 1);
                g.dispose();
                return new TileImageResult(img, true);
            }
            return new TileImageResult(TileStore.getUnexploredPlainImage(), false);
        }

        int subZoom = zoom / 2;
        int baseTx = tx * 2, baseTz = tz * 2;
        int subPixelSize = 128;
        BufferedImage composite = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        boolean anyReal = false;
        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                TileImageResult sub = getOrComposeSubtile(world, worldName, baseTx + dx, baseTz + dz, subZoom);
                g.drawImage(sub.image(), dx * subPixelSize, dz * subPixelSize, subPixelSize, subPixelSize, null);
                if (sub.hasRealData()) anyReal = true;
            }
        }
        g.dispose();
        return new TileImageResult(composite, anyReal);
    }

    /** 子图块解析：优先内存/磁盘缓存命中，未命中则递归 resolveTileImage 并按"是否含真实数据"决定是否落盘。 */
    private TileImageResult getOrComposeSubtile(World world, String worldName, int tx, int tz, int zoom) {
        String key = worldName + ":" + zoom + ":" + tx + ":" + tz;

        CachedTile cached = tileCache.get(key);
        if (cached != null) {
            BufferedImage img = decodeImage(cached.base64Png);
            // 必须带上缓存条目自己的真实数据标记——"未探索"占位图也会进内存缓存，
            // 若一律当真实数据，父级合成结果会被误判 hasRealData 而永久写盘
            if (img != null) return new TileImageResult(img, cached.hasRealData);
        }

        String disk = tileStore.load(worldName, zoom, tx, tz);
        if (disk != null) {
            BufferedImage img = decodeImage(disk);
            if (img != null) {
                tileCache.put(key, new CachedTile(disk, System.currentTimeMillis(), true));
                return new TileImageResult(img, true);
            }
        }

        TileImageResult result = resolveTileImage(world, worldName, tx, tz, zoom);
        String base64 = encodeImage(result.image());
        if (base64 != null) {
            tileCache.put(key, new CachedTile(base64, System.currentTimeMillis(), result.hasRealData()));
            if (result.hasRealData()) tileStore.save(worldName, zoom, tx, tz, base64);
        }
        return result;
    }

    private BufferedImage decodeImage(String base64Png) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Png);
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private String encodeImage(BufferedImage img) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private record TileImageResult(BufferedImage image, boolean hasRealData) {}

    /**
     * 异步渲染图块（返回 CompletableFuture，不阻塞调用线程）。
     */
    public CompletableFuture<String> renderTileAsync(String worldName, int cx, int cz, int zoom) {
        int snappedZoom = snapZoom(zoom);
        String key = worldName + ":" + snappedZoom + ":" + cx + ":" + cz;
        cacheAccessTime.put(key, System.currentTimeMillis());

        CachedTile memory = tileCache.get(key);
        if (memory != null) return CompletableFuture.completedFuture(memory.base64Png);

        return renderInFlight.computeIfAbsent(key, ignored ->
                buildTileFuture(worldName, cx, cz, snappedZoom)
                        .exceptionally(error -> {
                            plugin.logError("renderTileAsync", "Tile render failed",
                                    "world=" + worldName + " zoom=" + snappedZoom
                                            + " cx=" + cx + " cz=" + cz + " err=" + error);
                            return null;
                        })
                        .whenComplete((result, error) -> renderInFlight.remove(key)));
    }

    /**
     * 在工作线程读取/合成/压缩图块。缺少缓存时分批回到主线程抓取 ChunkSnapshot；
     * 任何 ImageIO、图像合成与磁盘 I/O 都不会进入 Paper 主线程。
     *
     * <p>高层图块直接从 zoom=1 磁盘图块或 ChunkSnapshot 合成，避免旧递归路径为一张
     * zoom=8 图块生成、压缩、解码 85 张中间 PNG。</p>
     */
    private CompletableFuture<String> buildTileFuture(String worldName, int cx, int cz, int zoom) {
        String key = worldName + ":" + zoom + ":" + cx + ":" + cz;
        return CompletableFuture.supplyAsync(() -> {
            CachedTile cached = tileCache.get(key);
            if (cached != null) return cached.base64Png;
            String disk = tileStore.load(worldName, zoom, cx, cz);
            if (disk != null) {
                tileCache.put(key, new CachedTile(disk, System.currentTimeMillis(), true));
            }
            return disk;
        }, renderExecutor).thenCompose(cached -> {
            if (cached != null) return CompletableFuture.completedFuture(cached);
            return renderSnapshotGridAsync(worldName, cx, cz, zoom);
        });
    }

    private CompletableFuture<String> renderSnapshotGridAsync(String worldName, int tileX, int tileZ, int zoom) {
        int baseCx = tileX * zoom;
        int baseCz = tileZ * zoom;
        CompletableFuture<String[]> baseTilesFuture = zoom == 1
                ? CompletableFuture.completedFuture(new String[1])
                : CompletableFuture.supplyAsync(
                        () -> loadBaseTileSources(worldName, baseCx, baseCz, zoom), renderExecutor);

        return baseTilesFuture
                .thenCompose(baseTiles -> captureSnapshotGridAsync(
                        worldName, baseCx, baseCz, zoom, baseTiles)
                        .thenApply(snapshots -> new CompositeSources(baseTiles, snapshots)))
                .thenApplyAsync(sources -> {
                    BufferedImage composite = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = composite.createGraphics();
                    graphics.drawImage(TileStore.getUnexploredPlainImage(), 0, 0, 256, 256, null);

                    boolean anyReal = false;
                    int chunkPixels = 256 / zoom;
                    int blockPixels = 16 / zoom;
                    for (int index = 0; index < zoom * zoom; index++) {
                        int dx = index % zoom;
                        int dz = index / zoom;
                        int pixelX = dx * chunkPixels;
                        int pixelZ = dz * chunkPixels;
                        String baseTile = sources.baseTiles()[index];
                        SnapshotTile snapshot = sources.snapshots()[index];
                        if (baseTile != null) {
                            BufferedImage image = decodeImage(baseTile);
                            if (image != null) {
                                graphics.drawImage(image, pixelX, pixelZ, chunkPixels, chunkPixels, null);
                                anyReal = true;
                                continue;
                            }
                        }
                        if (snapshot != null) {
                            renderSnapshotInto(graphics, snapshot, pixelX, pixelZ, blockPixels);
                            anyReal = true;
                        }
                    }
                    graphics.dispose();

                    String base64 = encodeImage(composite);
                    if (base64 == null) return null;
                    String key = worldName + ":" + zoom + ":" + tileX + ":" + tileZ;
                    tileCache.put(key, new CachedTile(base64, System.currentTimeMillis(), anyReal));
                    if (anyReal) {
                        tileStore.save(worldName, zoom, tileX, tileZ, base64);
                        evictCacheIfNeeded();
                    }
                    return base64;
                }, renderExecutor);
    }

    private String[] loadBaseTileSources(String worldName, int baseCx, int baseCz, int zoom) {
        String[] sources = new String[zoom * zoom];
        for (int dz = 0; dz < zoom; dz++) {
            for (int dx = 0; dx < zoom; dx++) {
                int cx = baseCx + dx;
                int cz = baseCz + dz;
                int index = dz * zoom + dx;
                String key = worldName + ":1:" + cx + ":" + cz;
                CachedTile cached = tileCache.get(key);
                String base64 = cached != null && cached.hasRealData
                        ? cached.base64Png
                        : tileStore.load(worldName, 1, cx, cz);
                if (base64 != null) {
                    sources[index] = base64;
                    tileCache.putIfAbsent(key, new CachedTile(base64, System.currentTimeMillis(), true));
                    cacheAccessTime.put(key, System.currentTimeMillis());
                }
            }
        }
        return sources;
    }

    /**
     * 每 tick 最多抓取 8 个基础区块，避免一次 zoom=8 请求把 64 份快照工作挤进同一 tick。
     * 已有 zoom=1 磁盘图块的位置不再访问 Bukkit 世界。
     */
    private CompletableFuture<SnapshotTile[]> captureSnapshotGridAsync(
            String worldName, int baseCx, int baseCz, int zoom, String[] baseTiles) {
        SnapshotTile[] snapshots = new SnapshotTile[zoom * zoom];
        CompletableFuture<SnapshotTile[]> result = new CompletableFuture<>();
        AtomicInteger next = new AtomicInteger();

        class SnapshotCaptureTask implements Runnable {
            @Override
            public void run() {
                try {
                    int captured = 0;
                    while (next.get() < snapshots.length && captured < 8) {
                        int index = next.getAndIncrement();
                        if (baseTiles[index] != null) continue;
                        int dx = index % zoom;
                        int dz = index / zoom;
                        snapshots[index] = captureSnapshotTile(worldName, baseCx + dx, baseCz + dz);
                        captured++;
                    }
                    if (next.get() >= snapshots.length) {
                        result.complete(snapshots);
                    } else {
                        Bukkit.getScheduler().runTask(plugin, this);
                    }
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            }
        }

        try {
            Bukkit.getScheduler().runTask(plugin, new SnapshotCaptureTask());
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    /** Captures Bukkit data on the server thread. The image is built by a worker from these snapshots. */
    private SnapshotTile captureSnapshotTile(String worldName, int cx, int cz) {
        World world = Bukkit.getWorld(worldName);
        if (world == null || !world.isChunkLoaded(cx, cz)) return null;

        ChunkSnapshot chunk = world.getChunkAt(cx, cz).getChunkSnapshot();
        ChunkSnapshot north = world.isChunkLoaded(cx, cz - 1)
                ? world.getChunkAt(cx, cz - 1).getChunkSnapshot()
                : null;
        return new SnapshotTile(chunk, north, world.getMinHeight());
    }

    private TileImageResult renderSnapshotTile(SnapshotTile snapshotTile) {
        ChunkSnapshot chunk = snapshotTile.chunk();
        ChunkSnapshot north = snapshotTile.northChunk();
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        renderSnapshotInto(graphics, snapshotTile, 0, 0, 16);
        graphics.dispose();
        return new TileImageResult(image, true);
    }

    private void renderSnapshotInto(
            Graphics2D graphics, SnapshotTile snapshotTile, int pixelX, int pixelZ, int blockPixels) {
        ChunkSnapshot chunk = snapshotTile.chunk();
        ChunkSnapshot north = snapshotTile.northChunk();
        Color[][] colors = new Color[16][16];
        boolean[][] water = new boolean[16][16];
        int[][] heights = new int[17][16];

        for (int x = 0; x < 16; x++) {
            heights[0][x] = north == null ? Integer.MIN_VALUE
                    : north.getHighestBlockYAt(x, 15);
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int[] info = getSnapshotTopBlockInfo(chunk, snapshotTile.minY(), x, z);
                heights[z + 1][x] = info[0];
                colors[z][x] = new Color(info[1], info[2], info[3]);
                water[z][x] = info[4] == 1;
            }
        }

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                double factor = 1.0;
                if (!water[z][x]) {
                    int northY = heights[z][x];
                    int slope = northY == Integer.MIN_VALUE ? 0 : heights[z + 1][x] - northY;
                    factor = slope > 0 ? 1.17 : (slope < 0 ? 0.82 : 1.0);
                }
                Color base = colors[z][x];
                graphics.setColor(new Color(
                        Math.min(255, (int) (base.getRed() * factor)),
                        Math.min(255, (int) (base.getGreen() * factor)),
                        Math.min(255, (int) (base.getBlue() * factor))));
                graphics.fillRect(
                        pixelX + x * blockPixels,
                        pixelZ + z * blockPixels,
                        blockPixels,
                        blockPixels);
            }
        }
    }

    private int[] getSnapshotTopBlockInfo(ChunkSnapshot chunk, int minY, int x, int z) {
        int y = chunk.getHighestBlockYAt(x, z);
        for (; y >= minY; y--) {
            Material material = chunk.getBlockType(x, y, z);
            if (material.isAir()) continue;
            if (material == Material.WATER) {
                int depth = 1;
                for (int dy = y - 1; dy >= minY && depth < 20; dy--) {
                    if (chunk.getBlockType(x, dy, z) == Material.WATER) depth++;
                    else break;
                }
                Color waterColor = blockMapColor(Material.WATER);
                if (waterColor == null) waterColor = new Color(64, 64, 255);
                double factor = waterFactor(depth, chunk.getX() * 16 + x, chunk.getZ() * 16 + z);
                return new int[]{y, (int) (waterColor.getRed() * factor),
                        (int) (waterColor.getGreen() * factor), (int) (waterColor.getBlue() * factor), 1};
            }
            Color color = blockMapColor(material);
            if (color != null) return new int[]{y, color.getRed(), color.getGreen(), color.getBlue(), 0};
        }
        return new int[]{minY, 64, 64, 255, 1};
    }

    /**
     * 玩家跨区块时的探索刷新：单个异步任务内顺序完成 zoom=1 重渲、zoom=2 重合成、
     * zoom=4/8 失效。顺序执行消除了旧实现"异步渲染 zoom=1/2 与同步失效 zoom=4/8
     * 互相竞态"的问题——失效后的高层图块若在低层渲完前被请求，会用过期子图块
     * 重新合成并再次写盘，表现为缩小视图永远不更新。
     */
    public void refreshChunkAsync(String worldName, int cx, int cz) {
        renderExecutor.submit(() -> {
            try {
                SnapshotTile snapshot = Bukkit.getScheduler().callSyncMethod(
                        plugin, () -> captureSnapshotTile(worldName, cx, cz)).get();
                if (snapshot == null) return;

                // ChunkSnapshot 已脱离 Bukkit live object；图像构建、PNG 压缩和磁盘写入均在 worker。
                TileImageResult r1 = renderSnapshotTile(snapshot);
                String b64 = encodeImage(r1.image());
                if (b64 == null) return;
                String key1 = worldName + ":1:" + cx + ":" + cz;
                tileCache.put(key1, new CachedTile(b64, System.currentTimeMillis(), true));
                tileStore.save(worldName, 1, cx, cz, b64);

                // 低层更新后失效所有父图块；zoom=2 在 worker 链中异步重合成。
                invalidateTile(worldName, 2, cx >> 1, cz >> 1);
                invalidateTile(worldName, 4, cx >> 2, cz >> 2);
                invalidateTile(worldName, 8, cx >> 3, cz >> 3);
                renderTileAsync(worldName, cx >> 1, cz >> 1, 2);
            } catch (Exception e) {
                plugin.logError("refreshChunk", "探索刷新失败", "world=" + worldName + " cx=" + cx + " cz=" + cz + " err=" + e);
            }
        });
    }

    // ==================== 预渲染 ====================

    /**
     * 预渲染 — 生成指定世界当前视角周边的图块。
     * 在玩家切换世界或首次加载时调用。
     */
    public void preRenderWorld(String worldName, int centerCx, int centerCz) {
        if (!plugin.hwpConfig().isPreRenderEnabled()) return;

        int radius = plugin.hwpConfig().getPreRenderRadius();
        List<Integer> zoomLevels = plugin.hwpConfig().getPreRenderZoomLevels();
        int maxQueue = plugin.hwpConfig().getPreRenderMaxQueue();

        for (int zoom : zoomLevels) {
            // 按 zoom 对齐 tile 坐标（floorDiv：截断除法在负坐标会漏掉边缘 tile）
            int startTx = Math.floorDiv(centerCx - radius, zoom);
            int endTx = Math.floorDiv(centerCx + radius, zoom);
            int startTz = Math.floorDiv(centerCz - radius, zoom);
            int endTz = Math.floorDiv(centerCz + radius, zoom);

            for (int tz = startTz; tz <= endTz; tz++) {
                for (int tx = startTx; tx <= endTx; tx++) {
                    String key = worldName + ":" + zoom + ":" + tx + ":" + tz;
                    // 已缓存且在有效期内则跳过
                    int cacheSec = plugin.hwpConfig().getChunkCacheSeconds();
                    CachedTile cached = tileCache.get(key);
                    if (cached != null && cacheSec > 0 &&
                            System.currentTimeMillis() - cached.timestamp < cacheSec * 1000L) {
                        continue;
                    }
                    // 防止重复排队
                    if (preRenderQueued.contains(key)) continue;
                    // 队列已满则跳过
                    if (preRenderQueueSize.get() >= maxQueue) break;

                    preRenderQueued.add(key);
                    preRenderQueueSize.incrementAndGet();
                    preRenderQueue.offer(new PreRenderTask(worldName, tx, tz, zoom));
                }
            }
        }
    }

    /**
     * Queues nearby, already-loaded chunks for every online player. This is called by a
     * synchronous Bukkit task and deliberately never loads or generates chunks for the map.
     */
    public void preRenderAroundOnlinePlayers() {
        if (!plugin.hwpConfig().isPreRenderEnabled()) return;

        int radius = plugin.hwpConfig().getNearbyPlayerChunkRadius();
        int perPlayerBudget = plugin.hwpConfig().getNearbyPlayerMaxEnqueuePerCycle();
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            int centerCx = player.getLocation().getBlockX() >> 4;
            int centerCz = player.getLocation().getBlockZ() >> 4;
            int queued = 0;

            // Fill from the center outward so the player sees the closest terrain first.
            for (int distance = 0; distance <= radius && queued < perPlayerBudget; distance++) {
                for (int dz = -distance; dz <= distance && queued < perPlayerBudget; dz++) {
                    for (int dx = -distance; dx <= distance && queued < perPlayerBudget; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) continue;
                        int cx = centerCx + dx;
                        int cz = centerCz + dz;
                        if (!world.isChunkLoaded(cx, cz)) continue;
                        if (enqueuePreRenderTask(world.getName(), cx, cz, 1)) queued++;
                    }
                }
            }
        }
    }

    private boolean enqueuePreRenderTask(String worldName, int cx, int cz, int zoom) {
        int maxQueue = plugin.hwpConfig().getPreRenderMaxQueue();
        String key = worldName + ":" + zoom + ":" + cx + ":" + cz;
        int cacheSec = plugin.hwpConfig().getChunkCacheSeconds();
        CachedTile cached = tileCache.get(key);
        if (cached != null && cacheSec > 0 &&
                System.currentTimeMillis() - cached.timestamp < cacheSec * 1000L) return false;
        if (preRenderQueueSize.get() >= maxQueue || !preRenderQueued.add(key)) return false;

        preRenderQueueSize.incrementAndGet();
        preRenderQueue.offer(new PreRenderTask(worldName, cx, cz, zoom));
        return true;
    }

    /**
     * 后台预渲染消费者 — 空闲时处理队列中的渲染任务。
     */
    private void startPreRenderConsumer() {
        preRenderExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    PreRenderTask task = preRenderQueue.poll();
                    if (task == null) {
                        Thread.sleep(500); // 队列空，等待
                        continue;
                    }
                    preRenderQueued.remove(task.worldName + ":" + task.zoom + ":" + task.cx + ":" + task.cz);
                    preRenderQueueSize.decrementAndGet();
                    // Queue processing is asynchronous; world access is dispatched safely by renderTileAsync.
                    renderTileAsync(task.worldName, task.cx, task.cz, task.zoom).join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // 单个预渲染失败不影响整体
                }
            }
        });
    }

    /**
     * LRU 缓存淘汰：超过最大条目数时移除最旧条目。
     */
    private void evictCacheIfNeeded() {
        int maxSize = 2048; // 最大缓存图块数
        if (tileCache.size() <= maxSize) return;

        // 按访问时间排序，移出最旧的 25%
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(cacheAccessTime.entrySet());
        sorted.sort(Map.Entry.comparingByValue());
        int toRemove = tileCache.size() - maxSize + maxSize / 4;
        for (int i = 0; i < Math.min(toRemove, sorted.size()); i++) {
            String key = sorted.get(i).getKey();
            tileCache.remove(key);
            cacheAccessTime.remove(key);
        }
    }

    // ==================== 强制渲染区域 ====================

    /**
     * 强制渲染指定矩形区域内的所有已加载区块。
     * 管理员操作，遍历区域内每个已加载区块并重新渲染对应的图块。
     *
     * @param worldName 世界名
     * @param x1 起始 X（方块坐标）
     * @param z1 起始 Z（方块坐标）
     * @param x2 结束 X（方块坐标）
     * @param z2 结束 Z（方块坐标）
     * @return 渲染结果摘要
     */
    /** 前端可选的缩放级别 —— 必须是 2 的幂，见 renderTile 上方 snapZoom 的说明。 */
    private static final int MAX_ZOOM_LEVEL = 8;
    private static final int[] VALID_ZOOM_LEVELS = {1, 2, 4, 8};

    public Map<String, Object> forceRenderArea(String worldName, int x1, int z1, int x2, int z2) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return Map.of("error", "世界未找到");

        // 标准化坐标范围
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        // 区块坐标范围
        int minCX = minX >> 4;
        int maxCX = maxX >> 4;
        int minCZ = minZ >> 4;
        int maxCZ = maxZ >> 4;
        long chunkCount = (long) (maxCX - minCX + 1) * (maxCZ - minCZ + 1);
        if (chunkCount > 1024L) {
            return Map.of("error", "render area exceeds 1024 chunks", "requestedChunks", chunkCount);
        }

        long startMs = System.currentTimeMillis();
        int totalChunks = 0;
        int loadedChunks = 0;
        int renderedTiles = 0;
        int failedTiles = 0;

        // 遍历区域内所有区块，对每个有效缩放级别的 tile 都强制重渲。
        // 之前只刷新 zoom=2，导致缩小（高 zoom 级别）查看时仍命中陈旧的磁盘缓存。
        Set<String> renderedTilesSet = new HashSet<>();
        for (int zoom : VALID_ZOOM_LEVELS) {
            int startTx = Math.floorDiv(minCX, zoom);
            int endTx = Math.floorDiv(maxCX, zoom);
            int startTz = Math.floorDiv(minCZ, zoom);
            int endTz = Math.floorDiv(maxCZ, zoom);

            for (int tz = startTz; tz <= endTz; tz++) {
                for (int tx = startTx; tx <= endTx; tx++) {
                    String tileKey = worldName + ":" + zoom + ":" + tx + ":" + tz;
                    if (renderedTilesSet.contains(tileKey)) continue;
                    renderedTilesSet.add(tileKey);

                    // 检查该 tile 覆盖的区块是否有已加载的
                    boolean hasLoaded = false;
                    for (int dz = 0; dz < zoom; dz++) {
                        for (int dx = 0; dx < zoom; dx++) {
                            int cxx = tx * zoom + dx;
                            int czz = tz * zoom + dz;
                            if (zoom == 1) totalChunks++;
                            if (world.isChunkLoaded(cxx, czz)) {
                                hasLoaded = true;
                                if (zoom == 1) loadedChunks++;
                            }
                        }
                    }

                    if (hasLoaded) {
                        // 强制重新渲染（清除内存+磁盘缓存）
                        tileCache.remove(tileKey);
                        cacheAccessTime.remove(tileKey);
                        tileStore.delete(worldName, zoom, tx, tz);
                        String result = renderTile(worldName, tx, tz, zoom);
                        if (result != null) renderedTiles++;
                        else failedTiles++;
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rendered", renderedTiles);
        summary.put("failed", failedTiles);
        summary.put("totalChunks", totalChunks);
        summary.put("loadedChunks", loadedChunks);
        summary.put("bbox", minX + "," + minZ + " → " + maxX + "," + maxZ);
        summary.put("chunkRange", "(" + minCX + "," + minCZ + ") → (" + maxCX + "," + maxCZ + ")");
        summary.put("zoomLevels", Arrays.toString(VALID_ZOOM_LEVELS));
        summary.put("elapsedMs", elapsed);
        return summary;
    }

    /**
     * 强制渲染指定世界所有已加载区块。
     * 管理员操作，提高地图完整性。
     */
    public Map<String, Object> forceRenderAllLoaded(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return Map.of("error", "世界未找到");

        var loadedChunks = world.getLoadedChunks();
        if (loadedChunks.length == 0) return Map.of("message", "该世界无已加载区块");

        // 计算已加载区块的边界
        int minCX = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE;
        int minCZ = Integer.MAX_VALUE, maxCZ = Integer.MIN_VALUE;
        for (var chunk : loadedChunks) {
            int cx = chunk.getX(), cz = chunk.getZ();
            if (cx < minCX) minCX = cx;
            if (cx > maxCX) maxCX = cx;
            if (cz < minCZ) minCZ = cz;
            if (cz > maxCZ) maxCZ = cz;
        }

        return forceRenderArea(worldName, minCX * 16, minCZ * 16, maxCX * 16 + 15, maxCZ * 16 + 15);
    }

    // ==================== 图层 ====================

    /**
     * 获取所有图层元数据。
     */
    public Map<String, Object> getLayersMeta() {
        HwpConfig cfg = plugin.hwpConfig();
        Map<String, Object> meta = new LinkedHashMap<>();

        // 地形图层
        Map<String, Object> terrain = new LinkedHashMap<>();
        terrain.put("id", "terrain");
        terrain.put("name", "地形");
        terrain.put("enabled", true); // 始终启用
        terrain.put("refreshable", false);
        terrain.put("description", "基础地形图块渲染");
        meta.put("terrain", terrain);

        // 玩家图层
        Map<String, Object> players = new LinkedHashMap<>();
        players.put("id", "players");
        players.put("name", "玩家标记");
        players.put("enabled", cfg.isLayerPlayersEnabled());
        players.put("refreshSeconds", cfg.getLayerPlayersRefreshSeconds());
        players.put("refreshable", true);
        players.put("description", "在线玩家位置标记");
        meta.put("players", players);

        // 备注图层
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("id", "annotations");
        annotations.put("name", "地图备注");
        annotations.put("enabled", cfg.isLayerAnnotationsEnabled());
        annotations.put("refreshable", true);
        annotations.put("description", "玩家/公开地图备注标注");
        meta.put("annotations", annotations);

        return meta;
    }

    /**
     * 获取指定图层的数据（独立于地形图层）。
     */
    public Map<String, Object> getLayerData(String layerId, String worldName) {
        return switch (layerId) {
            case "players" -> Map.of("players", getOnlinePlayers());
            case "layers" -> getLayersMeta();
            default -> Map.of("error", "未知图层: " + layerId);
        };
    }

    // ==================== 玩家查询 ====================

    /**
     * 获取在线玩家位置列表（受 hidden 模式影响）。
     */
    public List<Map<String, Object>> getOnlinePlayers() {
        List<Map<String, Object>> players = new ArrayList<>();
        if (!plugin.hwpConfig().isShowPlayers()) return players;

        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", p.getName());
            info.put("uuid", p.getUniqueId().toString());
            info.put("world", p.getWorld().getName());
            info.put("environment", p.getWorld().getEnvironment().name());
            info.put("dimension", getDimensionLabel(p.getWorld().getEnvironment()));

            // 检查是否隐藏模式
            if (plugin.isPlayerHidden(p.getUniqueId())) {
                info.put("hidden", true);
                info.put("x", 0);
                info.put("y", 0);
                info.put("z", 0);
                info.put("hint", "玩家已隐藏坐标");
            } else {
                info.put("hidden", false);
                info.put("x", p.getLocation().getBlockX());
                info.put("y", p.getLocation().getBlockY());
                info.put("z", p.getLocation().getBlockZ());
            }
            players.add(info);
        }
        return players;
    }

    /**
     * 获取渲染缓存统计。
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("memoryTiles", tileCache.size());
        stats.put("preRenderQueueSize", preRenderQueueSize.get());
        stats.put("preRenderQueued", preRenderQueued.size());
        // 合并磁盘统计
        stats.putAll(tileStore.getStats());
        return stats;
    }

    /** 清除指定世界的全部磁盘 + 内存缓存（管理员操作）。 */
    public Map<String, Object> clearAllCache(String worldName) {
        tileStore.deleteWorld(worldName);
        // 清除内存中该世界的缓存
        String prefix = worldName + ":";
        tileCache.keySet().removeIf(k -> k.startsWith(prefix));
        cacheAccessTime.keySet().removeIf(k -> k.startsWith(prefix));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "已清除世界 " + worldName + " 的全部缓存");
        result.put("stats", getCacheStats());
        return result;
    }

    /** 玩家触发的区域重渲染（不清除磁盘缓存，仅强制重载指定区域）。 */
    public Map<String, Object> playerRefreshArea(String worldName, int x1, int z1, int x2, int z2) {
        // 清除内存缓存强制重渲，但保留磁盘缓存（如果渲染失败可以回退）
        String prefix = worldName + ":";
        tileCache.keySet().removeIf(k -> k.startsWith(prefix));
        // 然后调用 forceRenderArea
        return forceRenderArea(worldName, x1, z1, x2, z2);
    }

    public TileStore tileStore() { return tileStore; }

    /**
     * 使指定图块的内存 + 磁盘缓存失效，但不立即重渲。
     * 下次该图块被请求时会重新递归合成，从而拾取子图块的最新探索数据。
     * 用于玩家跨区块时让覆盖该区块的 zoom=4/8 缩略图跟着更新，
     * 避免只刷新 zoom=1/2 导致"放大能看到新探索区域，缩小却要等管理员强制渲染"。
     */
    public void invalidateTile(String worldName, int zoom, int tx, int tz) {
        String key = worldName + ":" + zoom + ":" + tx + ":" + tz;
        tileCache.remove(key);
        cacheAccessTime.remove(key);
        tileStore.delete(worldName, zoom, tx, tz);
    }

    // ==================== 原有方法 ====================

    /**
     * 关闭渲染线程池。
     */
    public void shutdown() {
        renderExecutor.shutdown();
        preRenderExecutor.shutdown();
    }

    /**
     * 批量渲染周边区块（测试/快速预热用）。
     * @param worldName 世界名
     * @param centerCx 中心区块 X
     * @param centerCz 中心区块 Z
     * @param radius 半径（区块数）
     * @param zoom 缩放级别
     * @return 渲染结果摘要
     */
    public Map<String, Object> batchRender(String worldName, int centerCx, int centerCz, int radius, int zoomRaw) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return Map.of("error", "世界未找到");
        int zoom = snapZoom(zoomRaw);

        int rendered = 0;
        int failed = 0;
        long startMs = System.currentTimeMillis();

        // 按 tile 边界对齐：tile 起始 cx 必须是 zoom 的整数倍（floorDiv 兼容负坐标）
        int startTx = Math.floorDiv(centerCx - radius, zoom);
        int endTx = Math.floorDiv(centerCx + radius, zoom);
        int startTz = Math.floorDiv(centerCz - radius, zoom);
        int endTz = Math.floorDiv(centerCz + radius, zoom);

        int maxChunks = plugin.hwpConfig().getMaxChunksPerRequest();

        for (int tz = startTz; tz <= endTz; tz++) {
            for (int tx = startTx; tx <= endTx; tx++) {
                if (zoom * zoom > maxChunks) {
                    failed++;
                    continue;
                }
                String result = renderTile(worldName, tx, tz, zoom);
                if (result != null) rendered++;
                else failed++;
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rendered", rendered);
        summary.put("failed", failed);
        summary.put("elapsedMs", elapsed);
        summary.put("zoom", zoom);
        summary.put("tileRange", startTx + "-" + endTx + "," + startTz + "-" + endTz);
        return summary;
    }

    private void renderChunkSection(World world, Graphics2D g, int startX, int startZ,
                                     int tileX, int tileZ, int zoom) {
        int tileSize = 256;
        int subTileSize = tileSize / zoom;
        int pixPerBlock = Math.max(1, subTileSize / 16);

        // 第一阶段：采集本段 16×16 列颜色/高度 + 北侧一行邻居高度（消除区块接缝）
        Color[][] colors = new Color[16][16];
        boolean[][] water = new boolean[16][16];
        int[][] heights = new int[17][16]; // 行 0 = 北邻 worldZ-1，行 z+1 = 本段行 z
        for (int x = 0; x < 16; x++) {
            heights[0][x] = topHeight(world, startX + x, startZ - 1);
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int[] info = getTopBlockInfo(world, startX + x, startZ + z);
                heights[z + 1][x] = info[0];
                colors[z][x] = new Color(info[1], info[2], info[3]);
                water[z][x] = info[4] == 1;
            }
        }

        // 第二阶段：原版三档明暗（比北邻高→亮，低→暗）+ 绘制
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                Color base = colors[z][x];
                double factor = 1.0;
                if (!water[z][x]) {
                    int currY = heights[z + 1][x];
                    int northY = heights[z][x];
                    if (northY == Integer.MIN_VALUE) northY = currY; // 北邻未加载 → 无阴影
                    int slope = currY - northY;
                    factor = slope > 0 ? 1.17 : (slope < 0 ? 0.82 : 1.0);
                }

                int r = Math.min(255, (int) (base.getRed() * factor));
                int gv = Math.min(255, (int) (base.getGreen() * factor));
                int b = Math.min(255, (int) (base.getBlue() * factor));

                int px = tileX * subTileSize + x * pixPerBlock;
                int py = tileZ * subTileSize + z * pixPerBlock;
                g.setColor(new Color(r, gv, b));
                g.fillRect(px, py, pixPerBlock, pixPerBlock);
            }
        }
    }

    /** 指定列的地表高度（MOTION_BLOCKING_NO_LEAVES），区块未加载返回 Integer.MIN_VALUE。 */
    private int topHeight(World world, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return Integer.MIN_VALUE;
        try {
            return world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * 获取指定列地表方块的 {y, r, g, b, isWater}。
     * 高度来自区块内建 heightmap（O(1)），颜色用原版地图色；
     * 水体按深度三档亮度 + 棋盘抖动（复刻原版地图水域渐变）。
     */
    private int[] getTopBlockInfo(World world, int x, int z) {
        try {
            int cx = x >> 4, cz = z >> 4;
            if (!world.isChunkLoaded(cx, cz)) return new int[]{64, 64, 64, 255, 1};
            var chunk = world.getChunkAt(cx, cz);
            int lx = x & 15, lz = z & 15;
            int minY = world.getMinHeight();
            int y = world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);

            // 从 heightmap 高度向下找第一个有地图颜色的方块（跳过玻璃等透明方块）
            for (; y >= minY; y--) {
                Material mat = chunk.getBlock(lx, y, lz).getType();
                if (mat.isAir()) continue;

                if (mat == Material.WATER) {
                    int depth = 1;
                    for (int dy = y - 1; dy >= minY && depth < 20; dy--) {
                        if (chunk.getBlock(lx, dy, lz).getType() == Material.WATER) depth++;
                        else break;
                    }
                    double f = waterFactor(depth, x, z);
                    Color wc = blockMapColor(Material.WATER);
                    if (wc == null) wc = new Color(64, 64, 255);
                    return new int[]{y, (int) (wc.getRed() * f), (int) (wc.getGreen() * f), (int) (wc.getBlue() * f), 1};
                }

                Color c = blockMapColor(mat);
                if (c == null) continue; // 原版地图透明方块（玻璃/屏障），继续向下
                return new int[]{y, c.getRed(), c.getGreen(), c.getBlue(), 0};
            }
            return new int[]{minY, 64, 64, 255, 1};
        } catch (Exception e) {
            return new int[]{64, 64, 64, 255, 1};
        }
    }

    /** 水深三档亮度，档位交界深度用棋盘格在相邻两档间交错过渡。 */
    private static double waterFactor(int depth, int x, int z) {
        boolean odd = ((x + z) & 1) == 1;
        if (depth <= 1) return 1.0;
        if (depth == 2) return odd ? 0.86 : 1.0;
        if (depth <= 4) return 0.86;
        if (depth == 5) return odd ? 0.71 : 0.86;
        return 0.71;
    }


    private String getDimensionLabel(World.Environment env) {
        return switch (env) {
            case NORMAL -> "主世界";
            case NETHER -> "地狱";
            case THE_END -> "末地";
            default -> "自定义";
        };
    }

    // ---- 内部类 ----

    private record CachedTile(String base64Png, long timestamp, boolean hasRealData) {}

    private record SnapshotTile(ChunkSnapshot chunk, ChunkSnapshot northChunk, int minY) {}

    private record CompositeSources(String[] baseTiles, SnapshotTile[] snapshots) {}

    private record PreRenderTask(String worldName, int cx, int cz, int zoom) {}
}
