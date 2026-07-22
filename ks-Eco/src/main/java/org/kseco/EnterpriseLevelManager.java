package org.kseco;

import java.sql.SQLException;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Owns enterprise level persistence and level-based economy modifiers. */
public final class EnterpriseLevelManager {

    private final KsEco plugin;
    private volatile int maxLevel;
    private volatile NavigableMap<Integer, Double> landPerkMultipliers;
    private final Map<String, Integer> levelCache = new ConcurrentHashMap<>();

    public EnterpriseLevelManager(KsEco plugin) {
        this.plugin = plugin;
        reloadConfig();
        ensureSchema();
        refreshLevels();
    }

    public void reload() {
        reloadConfig();
        refreshLevels();
    }

    /** Reloads the shared level cache without blocking Paper's server thread. */
    public void refreshLevelsAsync() {
        plugin.asyncWorkPool().executeDatabase(this::refreshLevels);
    }

    private void reloadConfig() {
        maxLevel = Math.max(1, plugin.getConfig().getInt("enterprise-levels.max-level", 10));
        TreeMap<Integer, Double> configured = new TreeMap<>();
        var section = plugin.getConfig().getConfigurationSection("enterprise-levels.land-perk-multipliers");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int level = Integer.parseInt(key);
                    double multiplier = section.getDouble(key, 1.0);
                    if (level >= 1 && Double.isFinite(multiplier) && multiplier >= 0) {
                        configured.put(level, multiplier);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (configured.isEmpty()) configured.put(1, 1.0);
        landPerkMultipliers = configured;
    }

    private void refreshLevels() {
        Map<String, Integer> loaded = new ConcurrentHashMap<>();
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var rs = conn.createStatement().executeQuery("SELECT id,level FROM ks_ent_enterprises")) {
                while (rs.next()) loaded.put(rs.getString(1), clampLevel(rs.getInt(2)));
            }
            levelCache.clear();
            levelCache.putAll(loaded);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to refresh enterprise level cache", e);
        }
    }

    private void ensureSchema() {
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var statement = conn.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ks_ent_enterprises (id VARCHAR(64) PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL DEFAULT 'PRIVATE', owner_uuids TEXT NOT NULL, registered_capital REAL NOT NULL, current_assets REAL DEFAULT 0.0, employee_count INTEGER DEFAULT 0, region TEXT, status TEXT DEFAULT 'ACTIVE', created_at INTEGER NOT NULL)");
            }
            try (var statement = conn.createStatement()) {
                statement.executeUpdate("ALTER TABLE ks_ent_enterprises ADD COLUMN level INTEGER NOT NULL DEFAULT 1");
            } catch (SQLException ignored) {
                // Existing databases already have the level column.
            }
            try (var statement = conn.createStatement()) {
                statement.executeUpdate("UPDATE ks_ent_enterprises SET level=1 WHERE level IS NULL OR level<1");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to ensure enterprise level schema", e);
        }
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public int clampLevel(int level) {
        return Math.max(1, Math.min(maxLevel, level));
    }

    public int getLevel(String enterpriseId) {
        if (enterpriseId == null || enterpriseId.isBlank()) return 1;
        return levelCache.getOrDefault(enterpriseId, 1);
    }

    public boolean setLevel(String enterpriseId, int level, String actorUuid, String actorName) {
        if (enterpriseId == null || enterpriseId.isBlank() || level < 1 || level > maxLevel) return false;
        int previous = getLevel(enterpriseId);
        try (var conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement("UPDATE ks_ent_enterprises SET level=? WHERE id=?")) {
                ps.setInt(1, level);
                ps.setString(2, enterpriseId);
                if (ps.executeUpdate() != 1) return false;
            }
            levelCache.put(enterpriseId, level);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_audit_log (action,player_uuid,player_name,target_type,target_id,details,created_at) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, "ENTERPRISE_LEVEL_SET");
                ps.setString(2, actorUuid == null ? "SYSTEM" : actorUuid);
                ps.setString(3, actorName == null ? "SYSTEM" : actorName);
                ps.setString(4, "enterprise");
                ps.setString(5, enterpriseId);
                ps.setString(6, "level=" + previous + "->" + level);
                ps.setLong(7, System.currentTimeMillis() / 1000);
                ps.executeUpdate();
            } catch (SQLException auditFailure) {
                plugin.getLogger().warning("Enterprise level changed but audit write failed: " + auditFailure.getMessage());
            }
            plugin.publishCrossServerInvalidation("enterprise-level", enterpriseId);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set enterprise level: " + enterpriseId, e);
            return false;
        }
    }

    public double getLandPerkMultiplier(int level) {
        var entry = landPerkMultipliers.floorEntry(clampLevel(level));
        return entry == null ? 1.0 : entry.getValue();
    }

    public double getLandPerkMultiplier(String enterpriseId) {
        return getLandPerkMultiplier(getLevel(enterpriseId));
    }
}
