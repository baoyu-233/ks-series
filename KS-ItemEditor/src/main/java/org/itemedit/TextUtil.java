package org.itemedit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class TextUtil {

    private TextUtil() {}

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /**
     * 解析玩家输入：
     * - 含有 < 和 > 视为 MiniMessage（例如 &lt;red&gt;、&lt;gradient:..&gt;）
     * - 否则按 & 颜色码解析（例如 &amp;c&amp;l）
     * 解析失败自动回退，并默认关闭斜体，让名称/Lore 显示干净。
     */
    public static Component parse(String input) {
        String s = input == null ? "" : input;
        Component result;
        boolean looksMini = s.indexOf('<') >= 0 && s.indexOf('>') >= 0;
        try {
            result = looksMini ? MM.deserialize(s) : LEGACY.deserialize(s);
        } catch (Exception ex) {
            result = LEGACY.deserialize(s);
        }
        return result.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** 关闭默认斜体（用于 GUI 按钮文字）。 */
    public static Component clean(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** 取纯文本（用于把聊天输入还原成字符串）。 */
    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /**
     * 将 Component 序列化为 & 格式码字符串，保留颜色/粗体/斜体/乱码等所有格式。
     * 用于网页编辑器回读物品数据，避免 {@link #plain(Component)} 丢弃格式信息。
     */
    public static String legacy(Component c) {
        return LegacyComponentSerializer.legacyAmpersand().serialize(c);
    }
}
