package org.kshwp;

import org.bukkit.World;

import java.util.*;

/**
 * 将 ksHWP 地图备注/公开标注转换为 Xaero's Minimap 的路径点格式。
 *
 * 有两种真实核实过的格式：
 *
 * 1) 聊天消息格式（推荐，无需碰任何本地文件）：用户实测发现，只要给自己发一条含
 *    "xaero-waypoint:..." 的私信/聊天消息，Xaero 客户端会自动识别并弹出"添加路径点"提示。
 *    真实样例：xaero-waypoint:1:1:-30:95:-82:9:false:0:Internal-overworld
 *    字段：xaero-waypoint:&lt;name&gt;:&lt;initials&gt;:&lt;x&gt;:&lt;y&gt;:&lt;z&gt;:&lt;color 0-15&gt;:&lt;disabled&gt;:&lt;type&gt;:&lt;dimension&gt;
 *    dimension 主世界确认是 "Internal-overworld"；地狱/末地是按命名规律推测的
 *    "Internal-the_nether"/"Internal-the_end"，未经实机验证，需要玩家在对应维度测试确认。
 *
 * 2) 文件格式（备用，需手动放入本地 Xaero 数据目录）：已用用户客户端真实生成的数据核实
 *    （比早期网络资料更权威）：
 *    - mw$default_1.txt 空白模板的注释头给出字段名列表；
 *    - 用户手动在游戏内添加一个路径点后产出的真实行进一步核实了取值类型：
 *      waypoint:1:1:-30:95:-82:9:false:0:gui.xaero_default:false:0:0:false
 *    字段：waypoint:&lt;name&gt;:&lt;initials&gt;:&lt;x&gt;:&lt;y&gt;:&lt;z&gt;:&lt;color 0-15&gt;:&lt;disabled&gt;:&lt;type&gt;:&lt;set&gt;:&lt;rotate_on_tp&gt;:&lt;tp_yaw&gt;:&lt;visibility_type&gt;:&lt;destination&gt;
 *    注意：destination 是布尔值 false（不是整数 0）；真实文件没有 "sets:" 声明行，只有三行 "#" 注释头。
 *    玩家需手动把导出的 waypoint 行追加到自己客户端已有的
 *    xaero/minimap/Multiplayer_&lt;服务器地址&gt;/dim%&lt;id&gt;/mw$default_1.txt 文件末尾
 *    （保留文件原有的 # 注释头，不要整体覆盖）。
 */
public final class XaeroWaypointExporter {

    private XaeroWaypointExporter() {}

    /**
     * 生成可直接发送到聊天框的 xaero-waypoint 消息行列表（每个标注一行）。
     * @param myAnnotations 玩家可见的备注（私有 + 公开，来自 getWorldAnnotations）
     * @param publicAnnotations 服务器公开标注（管理员发布，来自 getPublicAnnotations）
     * @param environment 当前世界的维度类型，用于生成 dimension 字段
     */
    public static List<String> toChatLines(List<Map<String, Object>> myAnnotations,
                                            List<Map<String, Object>> publicAnnotations,
                                            World.Environment environment) {
        List<Map<String, Object>> merged = mergeDedup(myAnnotations, publicAnnotations);
        String dimension = dimensionKey(environment);

        List<String> lines = new ArrayList<>();
        for (var a : merged) {
            lines.add(toChatLine(a, dimension));
        }
        return lines;
    }

    private static String toChatLine(Map<String, Object> a, String dimension) {
        boolean isArea = a.get("x2") != null;
        String rawName = String.valueOf(a.getOrDefault("text", "标注"));
        if (isArea) rawName = rawName + "(区域)";
        String name = sanitize(rawName, 32);
        String initials = name.isEmpty() ? "?" : name.substring(0, Math.min(2, name.length()));
        int x = toInt(a.get("x")), y = toInt(a.get("y")), z = toInt(a.get("z"));
        int colorIdx = XaeroColorUtil.hexToNearestIndex(String.valueOf(a.getOrDefault("color", "#ffcc00")));

        // 字段：xaero-waypoint:name:initials:x:y:z:color:disabled:type:dimension
        return "xaero-waypoint:" + name + ":" + initials + ":" + x + ":" + y + ":" + z + ":" +
                colorIdx + ":false:0:" + dimension;
    }

    /** overworld 已用真实样例核实为 "Internal-overworld"；地狱/末地为推测值，待实机验证。 */
    private static String dimensionKey(World.Environment environment) {
        return switch (environment) {
            case NETHER -> "Internal-the_nether";
            case THE_END -> "Internal-the_end";
            default -> "Internal-overworld";
        };
    }

    /**
     * 生成可直接追加到 mw$default_1.txt 末尾的路径点文本内容。
     * @param myAnnotations 玩家可见的备注（私有 + 公开，来自 getWorldAnnotations）
     * @param publicAnnotations 服务器公开标注（管理员发布，来自 getPublicAnnotations）
     */
    public static String export(List<Map<String, Object>> myAnnotations,
                                 List<Map<String, Object>> publicAnnotations) {
        List<Map<String, Object>> merged = mergeDedup(myAnnotations, publicAnnotations);

        StringBuilder sb = new StringBuilder();
        for (var a : merged) {
            String set = setNameFor(a);
            sb.append(toWaypointLine(a, set)).append("\n");
        }
        return sb.toString();
    }

    private static String setNameFor(Map<String, Object> a) {
        String type = String.valueOf(a.getOrDefault("type", "note"));
        return "ks-" + type;
    }

    private static String toWaypointLine(Map<String, Object> a, String set) {
        boolean isArea = a.get("x2") != null;
        String rawName = String.valueOf(a.getOrDefault("text", "标注"));
        if (isArea) rawName = rawName + "(区域)";
        String name = sanitize(rawName, 32);
        String initials = name.isEmpty() ? "?" : name.substring(0, Math.min(2, name.length()));
        int x = toInt(a.get("x")), y = toInt(a.get("y")), z = toInt(a.get("z"));
        int colorIdx = XaeroColorUtil.hexToNearestIndex(String.valueOf(a.getOrDefault("color", "#ffcc00")));

        // 字段：name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination
        // 真实值已用用户手动在游戏内添加的路径点核实：destination 默认值是布尔 false，不是整数 0
        // （例：waypoint:1:1:-30:95:-82:9:false:0:gui.xaero_default:false:0:0:false）
        return "waypoint:" + name + ":" + initials + ":" + x + ":" + y + ":" + z + ":" +
                colorIdx + ":false:0:" + set + ":false:0:0:false";
    }

    private static String sanitize(String s, int maxLen) {
        if (s == null) return "标注";
        s = s.replace(":", "-").replace("\n", " ").replace("\r", " ").trim();
        if (s.isEmpty()) s = "标注";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<Map<String, Object>> mergeDedup(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (var m : a) byId.put(String.valueOf(m.get("id")), m);
        for (var m : b) byId.put(String.valueOf(m.get("id")), m);
        return new ArrayList<>(byId.values());
    }
}
