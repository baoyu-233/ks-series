package org.kseco.extra.realestatedungeon;

import org.kseco.KsEco;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 虚空世界网格分配器。
 *
 * 逻辑：
 * - 网格存 ks_dungeon_grids 表 (world, grid_x, grid_z) UNIQUE
 * - allocate() 优先复用 FREE 网格（last_used_at ASC），无则按 (x=spacing*n, z=spacing*m) 取下一个
 * - maxGrids 上限控制总占用数
 */
public final class DungeonGridAllocator {

    private final KsEco eco;
    private final DungeonConfigManager configManager;
    private final String gridId;  // 分配器所属副本 ID（用于创建时占位）

    public DungeonGridAllocator(KsEco eco, DungeonConfigManager configManager) {
        this.eco = eco;
        this.configManager = configManager;
        this.gridId = null;
    }

    /** 分配一个新网格。返回 Map{id, world, centerX, centerZ} 或 null（已满）。 */
    public synchronized Map<String, Object> allocate() {
        int max = configManager.snapshot().maxGrids;
        String world = configManager.snapshot().gridWorldName;

        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;

            // 1) 占用数检查
            int active;
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM ks_dungeon_grids WHERE status='OCCUPIED'");
                 var rs = ps.executeQuery()) {
                rs.next();
                active = rs.getInt(1);
            }
            if (active >= max) return null;

            // 2) 优先复用 FREE
            try (var ps = conn.prepareStatement(
                    "SELECT id, grid_x, grid_z, world FROM ks_dungeon_grids " +
                    "WHERE status='FREE' ORDER BY last_used_at ASC, grid_x ASC, grid_z ASC LIMIT 1");
                 var rs = ps.executeQuery()) {
                if (rs.next()) {
                    String id = rs.getString(1);
                    int gx = rs.getInt(2);
                    int gz = rs.getInt(3);
                    String w = rs.getString(4);
                    try (var up = conn.prepareStatement(
                            "UPDATE ks_dungeon_grids SET status='OCCUPIED', occupied_since=?, last_used_at=? WHERE id=?")) {
                        long now = System.currentTimeMillis() / 1000;
                        up.setLong(1, now);
                        up.setLong(2, now);
                        up.setString(3, id);
                        up.executeUpdate();
                    }
                    return toMap(id, w, gx, gz);
                }
            }

            // 3) 没有可复用 → 计算下一个新坐标
            int count;
            try (var ps = conn.createStatement();
                 var rs = ps.executeQuery("SELECT COUNT(*) FROM ks_dungeon_grids")) {
                rs.next();
                count = rs.getInt(1);
            }
            int spacing = configManager.snapshot().gridSpacing;
            int side = (int) Math.ceil(Math.sqrt(max));
            int gx = (count % side) * spacing;
            int gz = (count / side) * spacing;
            String id = "G" + UUID.randomUUID().toString().substring(0, 8);
            long now = System.currentTimeMillis() / 1000;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_dungeon_grids (id, world, grid_x, grid_z, status, occupied_since, last_used_at) " +
                    "VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, world);
                ps.setInt(3, gx);
                ps.setInt(4, gz);
                ps.setString(5, "OCCUPIED");
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            return toMap(id, world, gx, gz);
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 网格分配失败: " + e.getMessage());
            return null;
        }
    }

    /** 标记网格为 CLEANING（副本已结束，待清理）。 */
    public boolean markCleaning(String gridId) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_dungeon_grids SET status='CLEANING' WHERE id=?")) {
                ps.setString(1, gridId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 标记 CLEANING 失败: " + e.getMessage());
            return false;
        }
    }

    /** 释放网格回 FREE（清理完成后）。 */
    public boolean release(String gridId) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_dungeon_grids SET status='FREE', last_used_at=? WHERE id=?")) {
                ps.setLong(1, System.currentTimeMillis() / 1000);
                ps.setString(2, gridId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 释放网格失败: " + e.getMessage());
            return false;
        }
    }

    public int getFreeCount() {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (var ps = conn.createStatement();
                 var rs = ps.executeQuery("SELECT COUNT(*) FROM ks_dungeon_grids WHERE status='FREE'")) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var ps = conn.createStatement();
                 var rs = ps.executeQuery(
                         "SELECT * FROM ks_dungeon_grids ORDER BY grid_x, grid_z")) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("world", rs.getString("world"));
                    m.put("gridX", rs.getInt("grid_x"));
                    m.put("gridZ", rs.getInt("grid_z"));
                    m.put("status", rs.getString("status"));
                    m.put("occupiedSince", rs.getLong("occupied_since"));
                    m.put("lastUsedAt", rs.getLong("last_used_at"));
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 列表网格失败: " + e.getMessage());
        }
        return out;
    }

    private Map<String, Object> toMap(String id, String world, int gx, int gz) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("world", world);
        m.put("gridX", gx);
        m.put("gridZ", gz);
        m.put("centerX", gx);
        m.put("centerZ", gz);
        return m;
    }
}
