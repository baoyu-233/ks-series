package org.kshwp;

import java.sql.*;
import java.util.*;

/**
 * 地图备注管理器 — 支持点标注、区域标注、分类检索、详情扩展。
 * 数据存储在 SQLite（通过 ks-core 的 DataStore）。
 */
public final class MapAnnotationManager {

    private final KsHWP plugin;

    public MapAnnotationManager(KsHWP plugin) {
        this.plugin = plugin;
        createTable();
    }

    private void createTable() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS kshwp_annotations (
                        id TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        world TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'note',
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        x2 INTEGER,
                        z2 INTEGER,
                        text TEXT NOT NULL,
                        detail TEXT,
                        color TEXT DEFAULT '#ffcc00',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """);
                // Add columns that might be missing in older schemas
                try { stmt.execute("ALTER TABLE kshwp_annotations ADD COLUMN type TEXT NOT NULL DEFAULT 'note'"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE kshwp_annotations ADD COLUMN x2 INTEGER"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE kshwp_annotations ADD COLUMN z2 INTEGER"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE kshwp_annotations ADD COLUMN detail TEXT"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE kshwp_annotations ADD COLUMN color TEXT DEFAULT '#ffcc00'"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE kshwp_annotations ADD COLUMN is_public INTEGER DEFAULT 0"); } catch (SQLException ignored) {}

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_kshwp_anno_player ON kshwp_annotations(player_uuid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_kshwp_anno_world ON kshwp_annotations(world)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_kshwp_anno_type ON kshwp_annotations(type)");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("创建备注表失败: " + e.getMessage());
        }
    }

    /**
     * 添加地图备注（点标注）。
     */
    public Annotation add(UUID playerUuid, String playerName, String world,
                           int x, int y, int z, String text) {
        return addArea(playerUuid, playerName, world, x, y, z, 0, 0, text, "note", null, "#ffcc00");
    }

    /**
     * 添加区域标注。
     */
    public Annotation addArea(UUID playerUuid, String playerName, String world,
                               int x, int y, int z, int x2, int z2,
                               String text, String type, String detail, String color) {
        List<Annotation> existing = getPlayerAnnotations(playerUuid);
        if (existing.size() >= plugin.hwpConfig().getMaxPerPlayer()) return null;

        int maxLen = plugin.hwpConfig().getMaxTextLength();
        if (text != null && text.length() > maxLen) text = text.substring(0, maxLen);

        String id = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        if (type == null || type.isEmpty()) type = "note";
        if (color == null || color.isEmpty()) color = "#ffcc00";

        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO kshwp_annotations (id, player_uuid, player_name, world, type, x, y, z, x2, z2, text, detail, color, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, playerName);
                ps.setString(4, world);
                ps.setString(5, type);
                ps.setInt(6, x); ps.setInt(7, y); ps.setInt(8, z);
                if (x2 != 0) ps.setInt(9, x2); else ps.setNull(9, Types.INTEGER);
                if (z2 != 0) ps.setInt(10, z2); else ps.setNull(10, Types.INTEGER);
                ps.setString(11, text);
                if (detail != null) ps.setString(12, detail); else ps.setNull(12, Types.VARCHAR);
                ps.setString(13, color);
                ps.setLong(14, now);
                ps.setLong(15, now);
                ps.executeUpdate();
            }
            return new Annotation(id, playerUuid, playerName, world, type, x, y, z,
                    x2 != 0 ? x2 : null, z2 != 0 ? z2 : null, text, detail, color, now);
        } catch (SQLException e) {
            plugin.getLogger().warning("添加备注失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取玩家的所有备注。
     */
    public List<Annotation> getPlayerAnnotations(UUID playerUuid) {
        List<Annotation> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM kshwp_annotations WHERE player_uuid=? ORDER BY created_at DESC")) {
                ps.setString(1, playerUuid.toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询备注失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取指定世界的公开备注 + 指定玩家的私有备注。
     * 其他玩家的私有备注不可见（隐私保护）。
     *
     * @param worldName 世界名
     * @param playerUuid 请求玩家 UUID，null 则仅返回公开备注
     */
    public List<Map<String, Object>> getWorldAnnotations(String worldName, UUID playerUuid) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            String sql;
            if (playerUuid != null) {
                sql = "SELECT * FROM kshwp_annotations WHERE world=? AND (is_public=1 OR player_uuid=?) ORDER BY created_at DESC LIMIT 500";
            } else {
                sql = "SELECT * FROM kshwp_annotations WHERE world=? AND is_public=1 ORDER BY created_at DESC LIMIT 500";
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, worldName);
                if (playerUuid != null) ps.setString(2, playerUuid.toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Annotation a = mapRow(rs);
                    boolean isOwner = playerUuid != null && playerUuid.equals(a.playerUuid);
                    result.add(annoToMap(a, isOwner));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询世界备注失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 搜索备注（按文本、类型、世界筛选）。
     */
    public List<Map<String, Object>> search(String query, String world, String type, UUID playerUuid) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            StringBuilder sql = new StringBuilder("SELECT * FROM kshwp_annotations WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (query != null && !query.isEmpty()) {
                sql.append(" AND (text LIKE ? OR detail LIKE ?)");
                String q = "%" + query + "%";
                params.add(q); params.add(q);
            }
            if (world != null && !world.isEmpty()) {
                sql.append(" AND world=?");
                params.add(world);
            }
            if (type != null && !type.isEmpty()) {
                sql.append(" AND type=?");
                params.add(type);
            }
            sql.append(" ORDER BY created_at DESC LIMIT 200");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Annotation a = mapRow(rs);
                    boolean isOwner = playerUuid != null && playerUuid.equals(a.playerUuid);
                    result.add(annoToMap(a, isOwner));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("搜索备注失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 根据 ID 获取单条备注。
     */
    public Annotation getById(String id) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM kshwp_annotations WHERE id=?")) {
                ps.setString(1, id);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询备注失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 删除备注（仅所有者可删除）。
     */
    public boolean delete(String id, UUID requesterUuid) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM kshwp_annotations WHERE id=? AND player_uuid=?")) {
                ps.setString(1, id);
                ps.setString(2, requesterUuid.toString());
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("删除备注失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 添加公开标注（仅限管理员，所有人可见，无需 token）。
     */
    public Annotation addPublicAnnotation(String world, int x, int y, int z,
                                          Integer x2, Integer z2,
                                          String text, String type, String detail, String color) {
        String id = "PUB-" + UUID.randomUUID().toString().substring(0, 6);
        long now = System.currentTimeMillis() / 1000;
        if (type == null || type.isEmpty()) type = "landmark";
        if (color == null || color.isEmpty()) color = "#ff4444";

        int maxLen = plugin.hwpConfig().getMaxTextLength();
        if (text != null && text.length() > maxLen) text = text.substring(0, maxLen);

        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO kshwp_annotations (id, player_uuid, player_name, world, type, x, y, z, x2, z2, text, detail, color, is_public, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, "00000000-0000-0000-0000-000000000000");
                ps.setString(3, "[服务器]");
                ps.setString(4, world);
                ps.setString(5, type);
                ps.setInt(6, x); ps.setInt(7, y); ps.setInt(8, z);
                if (x2 != null) ps.setInt(9, x2); else ps.setNull(9, Types.INTEGER);
                if (z2 != null) ps.setInt(10, z2); else ps.setNull(10, Types.INTEGER);
                ps.setString(11, text);
                if (detail != null) ps.setString(12, detail); else ps.setNull(12, Types.VARCHAR);
                ps.setString(13, color);
                ps.setLong(14, now);
                ps.setLong(15, now);
                ps.executeUpdate();
            }
            return new Annotation(id, null, "[服务器]", world, type, x, y, z,
                    x2, z2, text, detail, color, now);
        } catch (SQLException e) {
            plugin.getLogger().warning("添加公开标注失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取公开标注列表（无需登录，所有人可见）。
     */
    public List<Map<String, Object>> getPublicAnnotations(String world) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM kshwp_annotations WHERE is_public=1 AND world=? ORDER BY created_at DESC LIMIT 500")) {
                ps.setString(1, world);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Annotation a = mapRow(rs);
                    result.add(publicAnnoToMap(a));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询公开标注失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 删除公开标注（仅管理员可调用）。
     */
    public boolean deletePublicAnnotation(String id) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM kshwp_annotations WHERE id=? AND is_public=1")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("删除公开标注失败: " + e.getMessage());
            return false;
        }
    }

    // ---- 内部 ----

    private Map<String, Object> publicAnnoToMap(Annotation a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("playerName", "[服务器]");
        m.put("world", a.world);
        m.put("type", a.type);
        m.put("x", a.x); m.put("y", a.y); m.put("z", a.z);
        if (a.x2 != null) m.put("x2", a.x2);
        if (a.z2 != null) m.put("z2", a.z2);
        m.put("text", a.text);
        m.put("detail", a.detail);
        m.put("color", a.color);
        m.put("createdAt", a.createdAt);
        m.put("mine", false);
        m.put("isPublic", true);
        return m;
    }

    /**
     * 备注总数。
     */
    public int totalAnnotations() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM kshwp_annotations")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) { return 0; }
    }

    // ---- 内部 ----

    private Annotation mapRow(ResultSet rs) throws SQLException {
        int x2 = rs.getInt("x2");
        int z2 = rs.getInt("z2");
        return new Annotation(
                rs.getString("id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
                rs.getString("world"),
                rs.getString("type") != null ? rs.getString("type") : "note",
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.wasNull() || x2 == 0 ? null : x2,
                rs.wasNull() || z2 == 0 ? null : z2,
                rs.getString("text"),
                rs.getString("detail"),
                rs.getString("color") != null ? rs.getString("color") : "#ffcc00",
                rs.getLong("created_at")
        );
    }

    private Map<String, Object> annoToMap(Annotation a, boolean isOwner) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("playerName", a.playerName);
        m.put("world", a.world);
        m.put("type", a.type);
        m.put("x", a.x); m.put("y", a.y); m.put("z", a.z);
        if (a.x2 != null) m.put("x2", a.x2);
        if (a.z2 != null) m.put("z2", a.z2);
        m.put("text", a.text);
        m.put("detail", a.detail);
        m.put("color", a.color);
        m.put("createdAt", a.createdAt);
        m.put("mine", isOwner);
        return m;
    }

    // ---- 数据类 ----

    public record Annotation(
            String id, UUID playerUuid, String playerName,
            String world, String type,
            int x, int y, int z,
            Integer x2, Integer z2,
            String text, String detail, String color,
            long createdAt
    ) {}
}
