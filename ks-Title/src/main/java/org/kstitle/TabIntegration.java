package org.kstitle;

import org.bukkit.Bukkit;
import org.kstitle.model.IaBinding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

/**
 * 自动接管 TAB 的称号显示占位符接线（config.yml / groups.yml / animations.yml）。
 *
 * <p>用逐行文本 patch 而非 Bukkit YamlConfiguration 反序列化再落盘——后者会把整份文件
 * 重新格式化，抹掉 TAB 配置里大量的人工注释。这里只改动我们认识的具体行，其余内容原样保留，
 * 且保留原文件的换行符风格（LF/CRLF）与末尾是否有换行，避免整份文件被判定为"全部改动"。</p>
 *
 * <p>{@link #ensureBaseWiring()} 幂等：可重复调用，只在检测到旧 {@code playerTitle} 占位符
 * 或某个绑定的接线缺失时才改写文件——用于自愈。管理员手滑改回旧配置、或 TAB 升级覆盖了配置后，
 * 下次调用（onEnable + 周期任务）会自动补回。</p>
 */
public final class TabIntegration {

    private final KsTitle plugin;
    private final Path configPath = Path.of("plugins/TAB/config.yml");
    private final Path groupsPath = Path.of("plugins/TAB/groups.yml");
    private final Path animationsPath = Path.of("plugins/TAB/animations.yml");

    public TabIntegration(KsTitle plugin) {
        this.plugin = plugin;
    }

    public boolean tabInstalled() {
        return Bukkit.getPluginManager().getPlugin("TAB") != null;
    }

    /** 幂等接线：改名占位符 + 补齐当前全部绑定的动画块与ID映射。@return 是否有实际改动 */
    public boolean ensureBaseWiring() {
        if (!tabInstalled()) return false;
        boolean changed = false;
        try {
            changed |= patchGroups();
            changed |= patchConfigBase();
            List<IaBinding> bindings = plugin.titleManager().listIaBindings();
            changed |= patchConfigBindings(bindings);
            changed |= patchAnimations(bindings);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "TAB 配置自动接线失败", e);
        }
        return changed;
    }

    /** 绑定/解绑操作后调用：重新走一遍接线 + 触发 tab reload 立即生效。 */
    public void syncAndReload() {
        boolean changed = ensureBaseWiring();
        if (changed) reloadTab();
    }

    public void reloadTab() {
        if (!tabInstalled()) return;
        Bukkit.getScheduler().runTask(plugin, () ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tab reload"));
    }

    // ==================== 换行符保留的读写辅助 ====================

    /** @param lines 内容行（不含行尾符）@param eol 检测到的换行符风格 @param trailingNewline 原文件末尾是否有换行 */
    private record FileLines(List<String> lines, String eol, boolean trailingNewline) {}

    private FileLines readPreservingEol(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String eol = content.contains("\r\n") ? "\r\n" : "\n";
        boolean trailingNewline = content.endsWith("\n");
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\r\n|\n", -1)));
        if (trailingNewline && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return new FileLines(lines, eol, trailingNewline);
    }

    private void writePreservingEol(Path path, FileLines fl) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fl.lines().size(); i++) {
            sb.append(fl.lines().get(i));
            if (i < fl.lines().size() - 1 || fl.trailingNewline()) sb.append(fl.eol());
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ==================== groups.yml：_DEFAULT_ tabprefix/tagprefix ====================

    private boolean patchGroups() throws IOException {
        if (!Files.exists(groupsPath)) return false;
        FileLines fl = readPreservingEol(groupsPath);
        List<String> lines = fl.lines();
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("%playerTitle_use_id%")) {
                lines.set(i, lines.get(i).replace("%playerTitle_use_id%", "%kstitle_use_id%"));
                changed = true;
            }
        }
        if (changed) {
            writePreservingEol(groupsPath, fl);
            plugin.getLogger().info("[TabIntegration] groups.yml 已将 %playerTitle_use_id% 改接为 %kstitle_use_id%");
        }
        return changed;
    }

    // ==================== config.yml：基础占位符改名 + 缺失骨架兜底 ====================

    private boolean patchConfigBase() throws IOException {
        if (!Files.exists(configPath)) return false;
        FileLines fl = readPreservingEol(configPath);
        List<String> lines = fl.lines();
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("'%playerTitle_use_id%':")) {
                lines.set(i, line.replace("'%playerTitle_use_id%':", "'%kstitle_use_id%':"));
                changed = true;
            } else if (line.contains("'else': '%playerTitle_use%'")) {
                lines.set(i, line.replace("'%playerTitle_use%'", "'%kstitle_use%'"));
                changed = true;
            } else if (line.matches(".*'%playerTitle_use%':\\s*\\d+.*")) {
                lines.set(i, line.replace("%playerTitle_use%", "%kstitle_use%"));
                changed = true;
            }
        }
        if (!containsLine(lines, "'%kstitle_use_id%':")) {
            int idx = indexOfLineContaining(lines, "placeholder-output-replacements:");
            if (idx >= 0) {
                lines.add(idx + 1, "  '%kstitle_use_id%':");
                lines.add(idx + 2, "    'else': '%kstitle_use%'");
                changed = true;
                plugin.getLogger().info("[TabIntegration] config.yml 缺少 %kstitle_use_id% 接线，已补齐最小骨架");
            } else {
                plugin.getLogger().warning("[TabIntegration] config.yml 找不到 placeholder-output-replacements: 段，无法自动接线，请手动检查 TAB 配置");
            }
        }
        if (!containsLineMatching(lines, ".*'%kstitle_use%':\\s*\\d+.*")) {
            int idx = indexOfLineContaining(lines, "placeholder-refresh-intervals:");
            if (idx >= 0) {
                lines.add(idx + 1, "  '%kstitle_use%': 500");
                changed = true;
            }
        }
        if (changed) {
            writePreservingEol(configPath, fl);
            plugin.getLogger().info("[TabIntegration] config.yml 基础占位符接线已更新");
        }
        return changed;
    }

    // ==================== config.yml：每个绑定对应的 ID -> 动画占位符映射 ====================

    private boolean patchConfigBindings(List<IaBinding> bindings) throws IOException {
        if (!Files.exists(configPath) || bindings.isEmpty()) return false;
        FileLines fl = readPreservingEol(configPath);
        List<String> lines = fl.lines();
        int keyIdx = indexOfLineContaining(lines, "'%kstitle_use_id%':");
        if (keyIdx < 0) return false; // patchConfigBase 应已保证存在，双重兜底避免越界

        int baseIndent = countLeadingSpaces(lines.get(keyIdx));
        int elseIdx = -1;
        int blockEnd = lines.size();
        for (int i = keyIdx + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) continue;
            if (countLeadingSpaces(raw) <= baseIndent) { blockEnd = i; break; }
            if (raw.trim().startsWith("'else':")) elseIdx = i;
        }

        boolean changed = false;
        for (IaBinding b : bindings) {
            String wantKey = "'" + b.titleId() + "':";
            boolean found = false;
            for (int i = keyIdx + 1; i < blockEnd; i++) {
                if (lines.get(i).trim().startsWith(wantKey)) { found = true; break; }
            }
            if (!found) {
                String newLine = "    '" + b.titleId() + "': '%animation:ks_" + b.imagePrefix() + "_title%'";
                int insertAt = elseIdx >= 0 ? elseIdx : blockEnd;
                lines.add(insertAt, newLine);
                if (elseIdx >= 0) elseIdx++;
                blockEnd++;
                changed = true;
            }
        }
        if (changed) {
            writePreservingEol(configPath, fl);
            plugin.getLogger().info("[TabIntegration] config.yml 已补齐称号动画映射 (" + bindings.size() + " 条绑定)");
        }
        return changed;
    }

    /** 解绑时精确删除该称号在 config.yml 里的一行映射。 */
    public void removeBindingLine(int titleId) {
        try {
            if (!Files.exists(configPath)) return;
            FileLines fl = readPreservingEol(configPath);
            String wantKey = "'" + titleId + "':";
            boolean changed = fl.lines().removeIf(l -> l.trim().startsWith(wantKey) && l.contains("%animation:ks_"));
            if (changed) {
                writePreservingEol(configPath, fl);
                plugin.getLogger().info("[TabIntegration] 已从 config.yml 移除称号 #" + titleId + " 的动画映射");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "移除TAB映射失败", e);
        }
    }

    // ==================== animations.yml：每个绑定对应的动画帧定义 ====================

    private boolean patchAnimations(List<IaBinding> bindings) throws IOException {
        if (!Files.exists(animationsPath) || bindings.isEmpty()) return false;
        FileLines fl = readPreservingEol(animationsPath);
        List<String> lines = fl.lines();
        boolean changed = patchTitleAnimationBrackets(lines);
        for (IaBinding b : bindings) {
            String blockKey = "ks_" + b.imagePrefix() + "_title:";
            if (containsLine(lines, blockKey)) continue;
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.add(blockKey);
            lines.add("  change-interval: " + b.intervalMs());
            lines.add("  texts:");
            for (int f = 1; f <= b.frameCount(); f++) {
                lines.add("    - '&7[%img_" + b.imagePrefix() + "_f" + f + "%&7]'");
            }
            changed = true;
        }
        if (changed) {
            writePreservingEol(animationsPath, fl);
            plugin.getLogger().info("[TabIntegration] animations.yml 已补齐动画定义");
        }
        return changed;
    }

    /** 给 TAB 称号图片动画补上方括号，例如 [%img_title_f1%]。 */
    private boolean patchTitleAnimationBrackets(List<String> lines) {
        boolean changed = false;
        boolean inTitleAnimation = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (countLeadingSpaces(line) == 0) {
                inTitleAnimation = trimmed.matches("ks_[a-zA-Z0-9_\\-]+_title:");
            }
            if (!inTitleAnimation) continue;
            if (trimmed.matches("-\\s+'%img_[^']+%'")) {
                String prefix = line.substring(0, line.indexOf("-"));
                String img = trimmed.substring(trimmed.indexOf("'%") + 1, trimmed.lastIndexOf("'"));
                lines.set(i, prefix + "- '&7[" + img + "&7]'");
                changed = true;
            }
        }
        return changed;
    }

    public void removeAnimationBlock(String imagePrefix) {
        try {
            if (!Files.exists(animationsPath)) return;
            FileLines fl = readPreservingEol(animationsPath);
            List<String> lines = fl.lines();
            String blockKey = "ks_" + imagePrefix + "_title:";
            int idx = indexOfLineContaining(lines, blockKey);
            if (idx < 0) return;
            int end = idx + 1;
            while (end < lines.size() && !lines.get(end).isBlank() && countLeadingSpaces(lines.get(end)) > 0) end++;
            int start = idx;
            if (start > 0 && lines.get(start - 1).isBlank()) start--; // 一并去掉绑定时插入的空行分隔符
            List<String> trimmed = new ArrayList<>(lines.subList(0, start));
            trimmed.addAll(lines.subList(end, lines.size()));
            writePreservingEol(animationsPath, new FileLines(trimmed, fl.eol(), fl.trailingNewline()));
            plugin.getLogger().info("[TabIntegration] 已从 animations.yml 移除 " + blockKey);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "移除TAB动画块失败", e);
        }
    }

    // ==================== helpers ====================

    private boolean containsLine(List<String> lines, String needle) {
        for (String l : lines) if (l.contains(needle)) return true;
        return false;
    }

    private boolean containsLineMatching(List<String> lines, String regex) {
        for (String l : lines) if (l.matches(regex)) return true;
        return false;
    }

    private int indexOfLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(needle)) return i;
        return -1;
    }

    private int countLeadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }
}
