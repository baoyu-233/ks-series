package org.kstitle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * 一次性从 PlayerTitle.db 只读导入到 ks-Title 自己的表。幂等：{@code ks_title_meta.migration_done=1}
 * 后再次调用直接跳过。映射规则见项目计划文档（已用真实数据核对）：
 * <ul>
 *   <li>title_list.id 原样保留为 ks_title_defs.id（保证 ks-Skill 现有称号ID绑定不失效）</li>
 *   <li>buy_type=permission → CONDITION/PERMISSION（条件取自 title_require.buy_data）</li>
 *   <li>buy_type in (activity,not) → ADMIN_GRANT（现网数据里 amount/day 均未真正用作定价/限时）</li>
 *   <li>title_buff(potion_effect) → POTION 属性；title_buff(MythicLib) → MYTHICLIB_STAT 属性</li>
 *   <li>title_player 全量 → ownership(source=MIGRATED)；is_use=1 → equipped</li>
 * </ul>
 */
public final class MigrationTool {

    private static final String SOURCE_DB = "plugins/PlayerTitle/PlayerTitle.db";

    private final KsTitle plugin;

    public MigrationTool(KsTitle plugin) {
        this.plugin = plugin;
    }

    public void runIfNeeded() {
        if (isMigrationDone()) {
            plugin.getLogger().info("PlayerTitle 数据迁移已完成过，跳过");
            return;
        }
        File sourceFile = new File(SOURCE_DB);
        if (!sourceFile.exists()) {
            plugin.getLogger().info("未找到 PlayerTitle.db，跳过迁移（全新安装）");
            markDone();
            return;
        }
        // 注意：不加 ?mode=ro —— sqlite-jdbc 在 Windows 绝对路径(含盘符反斜杠)后拼接 query string 会解析失败
        // ("文件名、目录名或卷标语法不正确")。本类只执行 SELECT，正常读写模式打开即可，不会改动源文件。
        try (Connection src = DriverManager.getConnection("jdbc:sqlite:" + sourceFile.getAbsolutePath())) {
            int defs = migrateDefs(src);
            int attrs = migrateBuffs(src);
            int[] ownEquip = migratePlayers(src);
            markDone();
            plugin.getLogger().info(String.format(
                "PlayerTitle 数据迁移完成: %d 个称号定义, %d 条属性加成, %d 条持有记录, %d 条佩戴记录",
                defs, attrs, ownEquip[0], ownEquip[1]));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "PlayerTitle 数据迁移失败，本次不标记完成，下次启动会重试", e);
        }
    }

    private boolean isMigrationDone() {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
                "SELECT value FROM ks_title_meta WHERE key='migration_done'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && "1".equals(rs.getString(1));
        } catch (SQLException e) {
            return false;
        }
    }

    private void markDone() {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
                "INSERT OR REPLACE INTO ks_title_meta (key, value) VALUES ('migration_done', '1')")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "写入迁移完成标记失败", e);
        }
    }

    private int migrateDefs(Connection src) throws SQLException {
        // 先把 permission 类型的条件值取出来（title_require.buy_type='permission'）
        java.util.Map<Integer, String> permissionByTitle = new java.util.HashMap<>();
        try (Statement s = src.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT title_id, buy_data FROM title_require WHERE buy_type='permission' AND buy_data IS NOT NULL")) {
            while (rs.next()) permissionByTitle.put(rs.getInt("title_id"), rs.getString("buy_data"));
        }

        int count = 0;
        try (Statement s = src.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT id, title_name, buy_type, is_hide, description FROM title_list ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("title_name");
                String buyType = rs.getString("buy_type");
                boolean visible = rs.getInt("is_hide") == 0;
                String description = rs.getString("description");

                String unlockType;
                String conditionType = "";
                String conditionValue = "";
                if ("permission".equals(buyType)) {
                    unlockType = "CONDITION";
                    conditionType = "PERMISSION";
                    conditionValue = permissionByTitle.getOrDefault(id, "");
                } else {
                    unlockType = "ADMIN_GRANT";
                }

                boolean ok = plugin.titleManager().insertDefWithId(
                    id, name, description, "general", visible, unlockType, conditionType, conditionValue,
                    System.currentTimeMillis());
                if (ok) count++;
            }
        }
        return count;
    }

    private int migrateBuffs(Connection src) throws SQLException {
        int count = 0;
        Set<String> seen = new HashSet<>(); // 去重：源库里存在重复行(如 title 71 的 MythicLib LIFESTEAL:4 出现两次)
        try (Statement s = src.createStatement();
             ResultSet rs = s.executeQuery("SELECT title_id, buff_type, buff_content FROM title_buff")) {
            while (rs.next()) {
                int titleId = rs.getInt("title_id");
                String buffType = rs.getString("buff_type");
                String content = rs.getString("buff_content");
                if (content == null) continue;

                if ("potion_effect".equals(buffType)) {
                    try {
                        JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                        String potionName = json.has("potionName") ? json.get("potionName").getAsString() : null;
                        if (potionName == null) continue;
                        double level = json.has("potionLevel") ? json.get("potionLevel").getAsDouble() : 1;
                        boolean hide = json.has("potionHide") && json.get("potionHide").getAsBoolean();
                        String dedupKey = titleId + "|POTION|" + potionName;
                        if (!seen.add(dedupKey)) continue;
                        String extra = "{\"hide\":" + hide + "}";
                        if (plugin.titleManager().addAttribute(titleId, "POTION", potionName, level, extra) > 0) count++;
                    } catch (Exception e) {
                        plugin.getLogger().warning("跳过无法解析的 potion_effect buff titleId=" + titleId + ": " + e.getMessage());
                    }
                } else if ("MythicLib".equals(buffType)) {
                    // 格式如 "LIFESTEAL:4"
                    String[] parts = content.split(":", 2);
                    String statKey = parts[0].trim();
                    double amount = parts.length > 1 ? parseDoubleSafe(parts[1].trim()) : 0;
                    String dedupKey = titleId + "|MYTHICLIB_STAT|" + statKey;
                    if (!seen.add(dedupKey)) continue;
                    if (plugin.titleManager().addAttribute(titleId, "MYTHICLIB_STAT", statKey, amount, "") > 0) count++;
                }
            }
        }
        return count;
    }

    private double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    /** @return [持有记录数, 佩戴记录数] */
    private int[] migratePlayers(Connection src) throws SQLException {
        int ownCount = 0, equipCount = 0;
        try (Statement s = src.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT player_uuid, title_id, is_use FROM title_player WHERE player_uuid IS NOT NULL AND title_id IS NOT NULL")) {
            while (rs.next()) {
                String uuidStr = rs.getString("player_uuid");
                int titleId = rs.getInt("title_id");
                if (uuidStr == null || uuidStr.isBlank()) continue;
                java.util.UUID uuid;
                try {
                    uuid = java.util.UUID.fromString(uuidStr);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (plugin.titleManager().grantOwnership(uuid, titleId, "MIGRATED")) ownCount++;
                if (rs.getInt("is_use") == 1) {
                    plugin.titleManager().setEquippedRaw(uuid, titleId, System.currentTimeMillis());
                    equipCount++;
                }
            }
        }
        return new int[]{ownCount, equipCount};
    }
}
