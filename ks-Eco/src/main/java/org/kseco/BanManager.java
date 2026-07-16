package org.kseco;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;

/**
 * 市场禁令管理器。
 *
 * ban_type:
 *   LISTING          — 禁止在市场上架物品
 *   SELL_TO_OFFICIAL — 禁止使用官方兑换规则
 *   ALL_MARKET       — 以上两者皆禁
 */
public final class BanManager {

    public static final String BAN_LISTING = "LISTING";
    public static final String BAN_SELL_TO_OFFICIAL = "SELL_TO_OFFICIAL";
    public static final String BAN_ALL_MARKET = "ALL_MARKET";

    private final KsEco plugin;

    public BanManager(KsEco plugin) {
        this.plugin = plugin;
        createTable();
    }

    private void createTable() {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS ks_eco_bans (" +
                "  id TEXT PRIMARY KEY," +
                "  player_uuid TEXT NOT NULL," +
                "  player_name TEXT NOT NULL," +
                "  ban_type TEXT NOT NULL," +
                "  reason TEXT DEFAULT ''," +
                "  expires_at INTEGER DEFAULT 0," +
                "  created_by TEXT NOT NULL," +
                "  created_at INTEGER NOT NULL" +
                ")"
            );
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "创建禁令表失败", e);
        }
    }

    /**
     * 检查玩家是否有指定类型的有效禁令。
     * ALL_MARKET 类型的禁令会匹配所有 banType 查询。
     */
    public boolean isBanned(UUID playerUuid, String banType) {
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM ks_eco_bans " +
                    "WHERE player_uuid=? AND ban_type IN (?,'" + BAN_ALL_MARKET + "') " +
                    "AND (expires_at=0 OR expires_at>?)")) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, banType);
                ps.setLong(3, now);
                try (var rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("禁令查询失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 获取玩家的禁令详情（用于展示禁令原因）。
     * 返回第一条匹配的有效禁令，没有则返回 null。
     */
    public Map<String, Object> getBanDetail(UUID playerUuid, String banType) {
        long now = System.currentTimeMillis() / 1000;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM ks_eco_bans " +
                    "WHERE player_uuid=? AND ban_type IN (?,'" + BAN_ALL_MARKET + "') " +
                    "AND (expires_at=0 OR expires_at>?) LIMIT 1")) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, banType);
                ps.setLong(3, now);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return rowToMap(rs);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("获取禁令详情失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 添加禁令。
     * @param durationHours 0 = 永久，否则为现实小时数
     * @return 新建的禁令 id，失败返回 null
     */
    public String addBan(UUID playerUuid, String playerName, String banType,
                         String reason, long durationHours, String adminName) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        long expiresAt = durationHours > 0 ? now + durationHours * 3600L : 0L;
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_eco_bans (id,player_uuid,player_name,ban_type,reason,expires_at,created_by,created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, playerName);
                ps.setString(4, banType);
                ps.setString(5, reason != null ? reason : "");
                ps.setLong(6, expiresAt);
                ps.setString(7, adminName);
                ps.setLong(8, now);
                ps.executeUpdate();
                plugin.getLogger().info("[禁令] " + adminName + " 对 " + playerName +
                        " 添加禁令 " + banType + "（" + (durationHours > 0 ? durationHours + "小时" : "永久") + "）: " + reason);
                return id;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("添加禁令失败: " + e.getMessage());
        }
        return null;
    }

    /** 移除禁令 */
    public boolean removeBan(String banId) {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement("DELETE FROM ks_eco_bans WHERE id=?")) {
                ps.setString(1, banId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("移除禁令失败: " + e.getMessage());
        }
        return false;
    }

    /** 列出所有有效禁令（包含已过期的，前端可根据 expires_at 判断状态） */
    public List<Map<String, Object>> listBans() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var rs = conn.createStatement().executeQuery(
                    "SELECT * FROM ks_eco_bans ORDER BY created_at DESC LIMIT 200")) {
                while (rs.next()) out.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("列出禁令失败: " + e.getMessage());
        }
        return out;
    }

    /** 列出某玩家的有效禁令 */
    public List<Map<String, Object>> listPlayerBans(UUID playerUuid) {
        long now = System.currentTimeMillis() / 1000;
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM ks_eco_bans WHERE player_uuid=? AND (expires_at=0 OR expires_at>?) ORDER BY created_at DESC")) {
                ps.setString(1, playerUuid.toString());
                ps.setLong(2, now);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rowToMap(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("列出玩家禁令失败: " + e.getMessage());
        }
        return out;
    }

    private Map<String, Object> rowToMap(java.sql.ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("playerUuid", rs.getString("player_uuid"));
        m.put("playerName", rs.getString("player_name"));
        m.put("banType", rs.getString("ban_type"));
        m.put("reason", rs.getString("reason"));
        m.put("expiresAt", rs.getLong("expires_at"));
        m.put("createdBy", rs.getString("created_by"));
        m.put("createdAt", rs.getLong("created_at"));
        long now = System.currentTimeMillis() / 1000;
        long exp = rs.getLong("expires_at");
        m.put("active", exp == 0 || exp > now);
        return m;
    }
}
