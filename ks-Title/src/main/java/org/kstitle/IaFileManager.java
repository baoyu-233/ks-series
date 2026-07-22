package org.kstitle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把网页 Canvas 生成的图片帧自动写入 ItemsAdder 的 contents 目录（实证过真实目录结构，
 * 取自现网 {@code contents/ks_cyber_titles} 包：{@code configs/*.yml} 用
 * {@code info.namespace} + {@code font_images}(每项 path/y_position/scale_ratio，
 * 不是 height/ascent——之前生成的配置片段文本猜错过一次，这里改用实测过的真实字段)，
 * PNG 落在 {@code resourcepack/assets/<namespace>/textures/font/titles/*.png}。
 *
 * <p>只负责把图片和配置"放"进去；resourcepack 打包分发（{@code /iazip}）按用户要求保持手动，
 * 本类不会去触发它。</p>
 */
public final class IaFileManager {

    private static final Pattern EXISTING_KEY = Pattern.compile("^  ([a-zA-Z0-9_\\-]+):\\s*$");

    private final KsTitle plugin;

    public IaFileManager(KsTitle plugin) {
        this.plugin = plugin;
    }

    private Path packRoot(String packName) throws IOException {
        String safePackName = requireSafeSegment(packName, "packName");
        Path contentsRoot = Path.of("plugins/ItemsAdder/contents").toAbsolutePath().normalize();
        Path root = contentsRoot.resolve(safePackName).normalize();
        if (!root.startsWith(contentsRoot)) throw new IOException("pack path escapes ItemsAdder contents");
        return root;
    }

    public void writeImage(String packName, String namespace, String name, byte[] pngBytes) throws IOException {
        String safeNamespace = requireSafeSegment(namespace, "namespace");
        String safeName = requireSafeSegment(name, "image name");
        Path dir = packRoot(packName).resolve("resourcepack/assets")
                .resolve(safeNamespace).resolve("textures/font/titles").normalize();
        Files.createDirectories(dir);
        Files.write(dir.resolve(safeName + ".png"), pngBytes);
    }

    /** 追加缺失的 font_images 条目；本文件完全由本插件管理，可以放心整体读改写。 */
    public void ensureConfigEntries(String packName, String namespace, List<String> imageNames,
                                     int yPosition, int scaleRatio) throws IOException {
        namespace = requireSafeSegment(namespace, "namespace");
        for (String imageName : imageNames) requireSafeSegment(imageName, "image name");
        Path configPath = packRoot(packName).resolve("configs/font_images.yml");
        List<String> lines;
        if (Files.exists(configPath)) {
            lines = new ArrayList<>(Files.readAllLines(configPath, StandardCharsets.UTF_8));
        } else {
            Files.createDirectories(configPath.getParent());
            lines = new ArrayList<>();
            lines.add("info:");
            lines.add("  namespace: " + namespace);
            lines.add("");
            lines.add("font_images:");
        }

        List<String> existing = new ArrayList<>();
        for (String l : lines) {
            var m = EXISTING_KEY.matcher(l);
            if (m.matches()) existing.add(m.group(1));
        }

        boolean changed = false;
        for (String name : imageNames) {
            int entryYPosition = yPositionFor(name, yPosition);
            if (existing.contains(name)) {
                changed |= updateExistingEntry(lines, name, entryYPosition, scaleRatio);
                continue;
            }
            lines.add("  " + name + ":");
            lines.add("    path: \"font/titles/" + name + ".png\"");
            lines.add("    y_position: " + entryYPosition);
            lines.add("    scale_ratio: " + scaleRatio);
            changed = true;
        }
        if (changed) {
            Files.write(configPath, lines, StandardCharsets.UTF_8);
            plugin.getLogger().info("[IaFileManager] 已写入 " + imageNames.size() + " 张图片到 ItemsAdder 包 '" + packName + "'（还需手动运行 /iazip 使其生效）");
        }
    }

    private int yPositionFor(String imageName, int defaultYPosition) {
        return imageName != null && imageName.endsWith("_static") ? 7 : defaultYPosition;
    }

    private boolean updateExistingEntry(List<String> lines, String name, int yPosition, int scaleRatio) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("  " + name + ":")) {
                start = i;
                break;
            }
        }
        if (start < 0) return false;

        boolean changed = false;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.matches("^  [a-zA-Z0-9_\\-]+:\\s*$")) break;
            if (line.trim().startsWith("y_position:")) {
                String next = "    y_position: " + yPosition;
                if (!line.equals(next)) {
                    lines.set(i, next);
                    changed = true;
                }
            } else if (line.trim().startsWith("scale_ratio:")) {
                String next = "    scale_ratio: " + scaleRatio;
                if (!line.equals(next)) {
                    lines.set(i, next);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static String requireSafeSegment(String value, String label) throws IOException {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IOException(label + " must match [a-zA-Z0-9_-]{1,64}");
        }
        return value;
    }
}
