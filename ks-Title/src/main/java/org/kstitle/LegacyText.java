package org.kstitle;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 称号文本颜色码渲染工具。除了经典 {@code &0-9a-fk-or} 单字符码，
 * 迁移自 PlayerTitle 的多条称号（如 id 29/31/55/59）还用了 {@code &#RRGGBB} 十六进制渐变码——
 * {@link ChatColor#translateAlternateColorCodes} 不认这种写法，会原样显示成一串 &# 文本。
 * 这里先把 {@code &#RRGGBB} 展开成 Bungee 的逐位 {@code §x§R§R§G§G§B§B} 格式（游戏内物品显示名原生支持），
 * 再走一遍经典码转换。
 */
public final class LegacyText {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");

    private LegacyText() {}

    public static String colorize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        Matcher m = HEX_PATTERN.matcher(raw);
        StringBuilder expanded = new StringBuilder();
        while (m.find()) {
            StringBuilder bungeeHex = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) bungeeHex.append('§').append(c);
            m.appendReplacement(expanded, Matcher.quoteReplacement(bungeeHex.toString()));
        }
        m.appendTail(expanded);
        return ChatColor.translateAlternateColorCodes('&', expanded.toString());
    }
}
