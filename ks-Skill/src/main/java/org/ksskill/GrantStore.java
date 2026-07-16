package org.ksskill;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * “指令授予”来源的持久化（独立 SQLite，仿 ks-Sentinel）。
 * 内存镜像 {@code mirror} 供绑定判定热路径无锁读取；grant/revoke 是低频写。
 */
public final class GrantStore {

    private final String dbPath;
    private final Logger log;
    private Connection conn;
    private final Map<UUID, Set<String>> mirror = new ConcurrentHashMap<>();

    public GrantStore(File dataFolder, Logger log) {
        this.dbPath = new File(dataFolder, "skill.db").getAbsolutePath();
        this.log = log;
    }

    public void init() {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS ks_skill_grants (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        skill_id TEXT NOT NULL,
                        granted_by TEXT,
                        created_at INTEGER NOT NULL,
                        UNIQUE(player_uuid, skill_id)
                    )
                """);
            }
            loadMirror();
        } catch (SQLException e) {
            log.severe("技能授予库初始化失败: " + e.getMessage());
        }
    }

    private void loadMirror() {
        mirror.clear();
        if (conn == null) return;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT player_uuid, skill_id FROM ks_skill_grants")) {
            while (rs.next()) {
                try {
                    UUID u = UUID.fromString(rs.getString("player_uuid"));
                    mirror.computeIfAbsent(u, k -> ConcurrentHashMap.newKeySet()).add(rs.getString("skill_id"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            log.warning("加载授予记录失败: " + e.getMessage());
        }
    }

    public boolean has(UUID uuid, String skillId) {
        Set<String> s = mirror.get(uuid);
        return s != null && s.contains(skillId);
    }

    public Set<String> skillsOf(UUID uuid) {
        Set<String> s = mirror.get(uuid);
        return s == null ? Set.of() : new HashSet<>(s);
    }

    public boolean grant(UUID uuid, String skillId, String by) {
        if (conn == null) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO ks_skill_grants (player_uuid, skill_id, granted_by, created_at) VALUES (?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, skillId);
            ps.setString(3, by);
            ps.setLong(4, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
            mirror.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(skillId);
            return true;
        } catch (SQLException e) {
            log.warning("授予失败: " + e.getMessage());
            return false;
        }
    }

    public boolean revoke(UUID uuid, String skillId) {
        if (conn == null) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM ks_skill_grants WHERE player_uuid=? AND skill_id=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, skillId);
            int n = ps.executeUpdate();
            Set<String> s = mirror.get(uuid);
            if (s != null) s.remove(skillId);
            return n > 0;
        } catch (SQLException e) {
            log.warning("撤销失败: " + e.getMessage());
            return false;
        }
    }

    public void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
            conn = null;
        }
    }
}
