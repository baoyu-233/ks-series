package org.kshwp;

/** hex 颜色 ↔ Xaero/Minecraft 聊天颜色 16 色调色板 转换工具。 */
public final class XaeroColorUtil {

    // 索引 0-15 对应 Minecraft 聊天颜色代码 §0-§f 的标准 RGB
    private static final int[] PALETTE_RGB = {
        0x000000, // 0 黑
        0x0000AA, // 1 深蓝
        0x00AA00, // 2 深绿
        0x00AAAA, // 3 深青
        0xAA0000, // 4 深红
        0xAA00AA, // 5 深紫
        0xFFAA00, // 6 金
        0xAAAAAA, // 7 灰
        0x555555, // 8 深灰
        0x5555FF, // 9 蓝
        0x55FF55, // 10 绿
        0x55FFFF, // 11 青
        0xFF5555, // 12 红
        0xFF55FF, // 13 粉紫
        0xFFFF55, // 14 黄
        0xFFFFFF, // 15 白
    };

    private XaeroColorUtil() {}

    /** hex（如 "#ffcc00" 或 "ffcc00"）→ 最接近的 0-15 调色板索引（欧氏距离）。 */
    public static int hexToNearestIndex(String hex) {
        if (hex == null || hex.isEmpty()) return 15;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) return 15;
        int rgb;
        try {
            rgb = Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return 15;
        }
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;

        int bestIdx = 15;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < PALETTE_RGB.length; i++) {
            int pr = (PALETTE_RGB[i] >> 16) & 0xFF, pg = (PALETTE_RGB[i] >> 8) & 0xFF, pb = PALETTE_RGB[i] & 0xFF;
            long dr = r - pr, dg = g - pg, db = b - pb;
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
