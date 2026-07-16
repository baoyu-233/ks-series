package org.kshwp;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.Comparator;

/**
 * 持久化图块存储 — 磁盘缓存，不随时间过期。
 *
 * 目录结构: plugins/ksHWP/tiles/<world>/<zoom>/<cx>/<cz>.png
 *
 * 设计理念:
 * - 公共社区地图: 一旦渲染就永久保存
 * - 未探索区域: 无缓存文件 + 无已加载区块 → 红色"未探索"图块
 * - 管理员强制重载: 删除对应 tile 文件 → 下次请求重新渲染
 */
public final class TileStore {

    private final Path baseDir;
    private static final String UNEXPLORED_MARKER = "__UNEXPLORED__";
    private static String unexploredB64 = null;
    private static BufferedImage unexploredPlainImg = null;

    public TileStore(Path dataFolder) {
        this.baseDir = dataFolder.resolve("tiles");
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ignored) {}
    }

    /** 获取 tile 文件路径。 */
    private Path tilePath(String world, int zoom, int cx, int cz) {
        return baseDir.resolve(world).resolve(String.valueOf(zoom))
                .resolve(String.valueOf(cx)).resolve(cz + ".png");
    }

    /** 从磁盘读取缓存的 base64 PNG，若无则返回 null。 */
    public String load(String world, int zoom, int cx, int cz) {
        Path p = tilePath(world, zoom, cx, cz);
        if (!Files.exists(p)) return null;
        try {
            byte[] data = Files.readAllBytes(p);
            return Base64.getEncoder().encodeToString(data);
        } catch (IOException e) {
            return null;
        }
    }

    /** 将 base64 PNG 写入磁盘。 */
    public void save(String world, int zoom, int cx, int cz, String base64Png) {
        Path p = tilePath(world, zoom, cx, cz);
        try {
            Files.createDirectories(p.getParent());
            byte[] data = Base64.getDecoder().decode(base64Png);
            Files.write(p, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {}
    }

    /** 删除指定 tile 的磁盘缓存（强制重载用）。 */
    public void delete(String world, int zoom, int cx, int cz) {
        try {
            Files.deleteIfExists(tilePath(world, zoom, cx, cz));
        } catch (IOException ignored) {}
    }

    /** 删除指定世界所有 tile（管理员全量重载）。 */
    public void deleteWorld(String world) {
        Path worldDir = baseDir.resolve(world);
        if (Files.exists(worldDir)) {
            try (var stream = Files.walk(worldDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
    }

    /** 检查某个区域是否有任何 tile 缓存（用于判断是否被探索过）。 */
    public boolean hasAnyTile(String world) {
        Path worldDir = baseDir.resolve(world);
        if (!Files.exists(worldDir)) return false;
        try (var stream = Files.walk(worldDir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".png"));
        } catch (IOException e) {
            return false;
        }
    }

    /** 获取磁盘缓存统计。 */
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        int total = 0;
        long totalBytes = 0;
        Path worldDir = baseDir;
        if (Files.exists(worldDir)) {
            try (var stream = Files.walk(worldDir)) {
                var it = stream.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (p.toString().endsWith(".png")) {
                        total++;
                        try { totalBytes += Files.size(p); } catch (IOException ignored) {}
                    }
                }
            } catch (IOException ignored) {}
        }
        stats.put("diskTiles", total);
        stats.put("diskBytes", totalBytes);
        stats.put("diskPath", baseDir.toAbsolutePath().toString());
        return stats;
    }

    /** 生成红色"未探索"图块的 base64 PNG。只生成一次，之后复用。 */
    public static String getUnexploredTileB64() {
        if (unexploredB64 != null) return unexploredB64;
        int size = 256;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        // 深红背景
        g.setColor(new Color(80, 10, 10));
        g.fillRect(0, 0, size, size);
        // 网格线
        g.setColor(new Color(120, 20, 20));
        for (int i = 0; i < size; i += 32) {
            g.drawLine(i, 0, i, size);
            g.drawLine(0, i, size, i);
        }
        // 文字
        g.setColor(new Color(255, 200, 200));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 28));
        String text = "未探索";
        var fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        int th = fm.getHeight();
        g.drawString(text, (size - tw) / 2, (size - th) / 2 + fm.getAscent());
        // 副标题
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        String sub = "等待玩家探索...";
        var fm2 = g.getFontMetrics();
        int tw2 = fm2.stringWidth(sub);
        g.drawString(sub, (size - tw2) / 2, (size + th) / 2 + 20);
        g.dispose();

        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            unexploredB64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            unexploredB64 = UNEXPLORED_MARKER;
        }
        return unexploredB64;
    }

    /**
     * 生成不带文字的"未探索"占位图（纯色+网格），用于合成图块时填充缺失的子区域。
     * 带文字的版本缩小到子图块尺寸后文字会糊成乱码，这个版本没有文字，任意缩放都干净。
     */
    public static BufferedImage getUnexploredPlainImage() {
        if (unexploredPlainImg != null) return unexploredPlainImg;
        int size = 256;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(80, 10, 10));
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(120, 20, 20));
        for (int i = 0; i < size; i += 32) {
            g.drawLine(i, 0, i, size);
            g.drawLine(0, i, size, i);
        }
        g.dispose();
        unexploredPlainImg = img;
        return unexploredPlainImg;
    }
}
