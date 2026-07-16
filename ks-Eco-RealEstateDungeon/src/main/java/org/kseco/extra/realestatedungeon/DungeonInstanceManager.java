package org.kseco.extra.realestatedungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.kseco.KsEco;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 副本实例生命周期管理。
 *
 * 状态机：
 *   WAITING (购票后立即) → ACTIVE (玩家进入并 spawn) → COMPLETED / ABANDONED (结束)
 *
 * 异步副本生成/销毁：使用 BukkitScheduler 切到异步任务。
 */
public final class DungeonInstanceManager {

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    private final KsEco eco;
    private final DungeonConfigManager configManager;
    private final DungeonGridAllocator gridAllocator;
    private final DungeonRpgBridge rpgBridge;
    private final ConcurrentHashMap<String, String> playerToInstance = new ConcurrentHashMap<>();
    /** 实例 → [spawn] 告示牌标记的出生点覆盖（贴图时若发现则用它替代网格中心传送）。 */
    private final ConcurrentHashMap<String, Location> instanceSpawnOverride = new ConcurrentHashMap<>();
    /** 实例 → 贴图包围盒几何中心（无 [spawn] 标记时的兜底出生点，远比网格角落可靠）。 */
    private final ConcurrentHashMap<String, Location> instancePasteCenter = new ConcurrentHashMap<>();
    /** 实例 → 标记为 boss 的怪物实体 UUID（[mm] 告示牌第3行写 boss）。监控任务靠这个判断是否通关。 */
    private final ConcurrentHashMap<String, UUID> instanceBoss = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask monitorTask;

    public DungeonInstanceManager(KsEco eco, DungeonConfigManager configManager,
                                  DungeonGridAllocator gridAllocator) {
        this(eco, configManager, gridAllocator, null);
    }

    public DungeonInstanceManager(KsEco eco, DungeonConfigManager configManager,
                                  DungeonGridAllocator gridAllocator,
                                  DungeonRpgBridge rpgBridge) {
        this.eco = eco;
        this.configManager = configManager;
        this.gridAllocator = gridAllocator;
        this.rpgBridge = rpgBridge;
    }

    public void init() {
        ensureTables();
    }

    private void ensureTables() {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var s = conn.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_dungeon_templates (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        difficulty TEXT NOT NULL DEFAULT 'NORMAL',
                        ticket_price REAL NOT NULL DEFAULT 500,
                        min_players INTEGER NOT NULL DEFAULT 1,
                        max_players INTEGER NOT NULL DEFAULT 4,
                        time_limit_minutes INTEGER NOT NULL DEFAULT 60,
                        monster_level INTEGER NOT NULL DEFAULT 10,
                        description TEXT DEFAULT '',
                        created_at INTEGER NOT NULL
                    )
                """);
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_dungeon_instances (
                        id TEXT PRIMARY KEY,
                        template_id TEXT NOT NULL,
                        grid_id TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'WAITING',
                        started_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        owner_uuid TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """);
                s.execute("CREATE INDEX IF NOT EXISTS idx_di_status ON ks_dungeon_instances(status)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_di_owner ON ks_dungeon_instances(owner_uuid)");

                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_dungeon_grids (
                        id TEXT PRIMARY KEY,
                        world TEXT NOT NULL,
                        grid_x INTEGER NOT NULL,
                        grid_z INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'FREE',
                        occupied_since INTEGER DEFAULT 0,
                        last_used_at INTEGER DEFAULT 0,
                        UNIQUE(world, grid_x, grid_z)
                    )
                """);
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_dungeon_participants (
                        instance_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL DEFAULT '',
                        joined_at INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'ALIVE',
                        died_at INTEGER DEFAULT 0,
                        revive_count INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(instance_id, player_uuid)
                    )
                """);
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_dungeon_revivals (
                        id TEXT PRIMARY KEY,
                        instance_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        revive_count INTEGER NOT NULL,
                        cost_paid REAL NOT NULL,
                        formula_cost REAL NOT NULL,
                        revived_at INTEGER NOT NULL
                    )
                """);
                s.execute("CREATE INDEX IF NOT EXISTS idx_dr_instance ON ks_dungeon_revivals(instance_id)");

                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_dungeon_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        instance_id TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        player_uuid TEXT DEFAULT '',
                        detail TEXT DEFAULT '',
                        created_at INTEGER NOT NULL
                    )
                """);
                s.execute("CREATE INDEX IF NOT EXISTS idx_dl_instance ON ks_dungeon_log(instance_id)");

                // 地图：模板关联的 schematic 图名（FAWE 贴图）。老库兼容用 ALTER。
                try { s.execute("ALTER TABLE ks_dungeon_templates ADD COLUMN schematic TEXT DEFAULT ''"); }
                catch (SQLException ignore) { /* 列已存在 */ }
                // 资产联动：开本前是否要求发起人持有绑定本模板的住宅地块（ks_re_plots.dungeon_template_id）
                try { s.execute("ALTER TABLE ks_dungeon_templates ADD COLUMN require_property_key INTEGER NOT NULL DEFAULT 0"); }
                catch (SQLException ignore) { /* 列已存在 */ }
                try { s.execute("ALTER TABLE ks_dungeon_templates ADD COLUMN reward_config TEXT DEFAULT ''"); }
                catch (SQLException ignore) { /* column already exists */ }
            }
            // 注：副本房产现由 ks-Eco-RealEstate 维护（含 ks_re_plots 的 instance_id/property_function 两列），本插件不再扩列。
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 建表失败: " + e.getMessage());
        }
    }

    // ================================================================
    // 模板管理
    // ================================================================

    public String upsertTemplate(String id, String name, String difficulty, double ticketPrice,
                                  int minPlayers, int maxPlayers, int timeLimitMinutes,
                                  int monsterLevel, String description, String schematic,
                                  boolean requirePropertyKey) {
        return upsertTemplate(id, name, difficulty, ticketPrice, minPlayers, maxPlayers,
                timeLimitMinutes, monsterLevel, description, schematic, requirePropertyKey, "");
    }

    public String upsertTemplate(String id, String name, String difficulty, double ticketPrice,
                                  int minPlayers, int maxPlayers, int timeLimitMinutes,
                                  int monsterLevel, String description, String schematic,
                                  boolean requirePropertyKey, String rewardConfig) {
        if (id == null || id.isEmpty()) id = "T" + UUID.randomUUID().toString().substring(0, 8);
        if (name == null || name.isEmpty()) name = "未命名副本";
        if (difficulty == null) difficulty = "NORMAL";
        long now = System.currentTimeMillis() / 1000;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            // UPSERT
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_dungeon_templates (id, name, difficulty, ticket_price, " +
                    "min_players, max_players, time_limit_minutes, monster_level, description, schematic, " +
                    "require_property_key, reward_config, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET name=excluded.name, difficulty=excluded.difficulty, " +
                    "ticket_price=excluded.ticket_price, min_players=excluded.min_players, " +
                    "max_players=excluded.max_players, time_limit_minutes=excluded.time_limit_minutes, " +
                    "monster_level=excluded.monster_level, description=excluded.description, schematic=excluded.schematic, " +
                    "require_property_key=excluded.require_property_key, reward_config=excluded.reward_config")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, difficulty);
                ps.setDouble(4, Math.max(0, ticketPrice));
                ps.setInt(5, Math.max(1, minPlayers));
                ps.setInt(6, Math.max(minPlayers, maxPlayers));
                ps.setInt(7, Math.max(5, timeLimitMinutes));
                ps.setInt(8, Math.max(1, monsterLevel));
                ps.setString(9, description == null ? "" : description);
                ps.setString(10, schematic == null ? "" : schematic.trim());
                ps.setInt(11, requirePropertyKey ? 1 : 0);
                ps.setString(12, rewardConfig == null ? "" : rewardConfig.trim());
                ps.setLong(13, now);
                ps.executeUpdate();
                return id;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] upsert 模板失败: " + e.getMessage());
        }
        return null;
    }

    public List<Map<String, Object>> listTemplates() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (var ps = conn.createStatement();
                 var rs = ps.executeQuery("SELECT * FROM ks_dungeon_templates ORDER BY created_at DESC")) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("name", rs.getString("name"));
                    m.put("difficulty", rs.getString("difficulty"));
                    m.put("ticketPrice", rs.getDouble("ticket_price"));
                    m.put("minPlayers", rs.getInt("min_players"));
                    m.put("maxPlayers", rs.getInt("max_players"));
                    m.put("timeLimitMinutes", rs.getInt("time_limit_minutes"));
                    m.put("monsterLevel", rs.getInt("monster_level"));
                    m.put("description", rs.getString("description"));
                    m.put("schematic", readSchematic(rs));
                    m.put("requirePropertyKey", readRequirePropertyKey(rs));
                    m.put("rewardConfig", readRewardConfig(rs));
                    m.put("createdAt", rs.getLong("created_at"));
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 列模板失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 删除副本模板。若该模板下还有未结束的实例（WAITING/ACTIVE）则拒绝删除，避免破坏正在进行的副本。
     * @return null=成功，否则中文错误信息
     */
    public String deleteTemplate(String id) {
        if (id == null || id.isEmpty()) return "缺少模板ID";
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "数据库不可用";
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM ks_dungeon_instances WHERE template_id=? AND status IN ('WAITING','ACTIVE')")) {
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        return "该模板下还有 " + rs.getInt(1) + " 个进行中的副本实例，请先结束后再删除";
                    }
                }
            }
            try (var ps = conn.prepareStatement("DELETE FROM ks_dungeon_templates WHERE id=?")) {
                ps.setString(1, id);
                int n = ps.executeUpdate();
                if (n == 0) return "模板不存在";
            }
            return null;
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 删除模板失败: " + e.getMessage());
            return "删除失败: " + e.getMessage();
        }
    }

    public Map<String, Object> getTemplate(String id) {
        if (id == null) return null;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement("SELECT * FROM ks_dungeon_templates WHERE id=?")) {
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("name", rs.getString("name"));
                        m.put("difficulty", rs.getString("difficulty"));
                        m.put("ticketPrice", rs.getDouble("ticket_price"));
                        m.put("minPlayers", rs.getInt("min_players"));
                        m.put("maxPlayers", rs.getInt("max_players"));
                        m.put("timeLimitMinutes", rs.getInt("time_limit_minutes"));
                        m.put("monsterLevel", rs.getInt("monster_level"));
                        m.put("description", rs.getString("description"));
                        m.put("schematic", readSchematic(rs));
                        m.put("requirePropertyKey", readRequirePropertyKey(rs));
                        m.put("rewardConfig", readRewardConfig(rs));
                        return m;
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 查模板失败: " + e.getMessage());
        }
        return null;
    }

    /** 读 schematic 列；老库无该列时返回空串（双保险，ensureTables 已 ALTER）。 */
    private static String readSchematic(ResultSet rs) {
        try {
            String v = rs.getString("schematic");
            return v == null ? "" : v;
        } catch (SQLException e) {
            return "";
        }
    }

    /** 读 require_property_key 列；老库无该列时返回 false（双保险，ensureTables 已 ALTER）。 */
    private static boolean readRequirePropertyKey(ResultSet rs) {
        try {
            return rs.getInt("require_property_key") != 0;
        } catch (SQLException e) {
            return false;
        }
    }

    private static String readRewardConfig(ResultSet rs) {
        try {
            String v = rs.getString("reward_config");
            return v == null ? "" : v;
        } catch (SQLException e) {
            return "";
        }
    }

    /**
     * 开本前的资产钥匙校验。null=可以开本；非null=拒绝理由（中文，可直接展示给玩家）。
     * 检查发起人（单人开本者 / 队长）是否持有一块绑定了本模板的住宅地产——
     * 既可以是自己买的地块（ks_re_plots.dungeon_template_id），也可以是买的商品房
     * （ks_re_houses.dungeon_template_id，登记时从所在地块继承）。
     * 与房地产模块解耦：不依赖其 Java 类，直接查共享表，房地产模块未安装/列不存在时按"无钥匙"拒绝。
     */
    public String checkPropertyKey(UUID uuid, String templateId) {
        Map<String, Object> tpl = getTemplate(templateId);
        if (tpl == null) return "模板不存在";
        if (!Boolean.TRUE.equals(tpl.get("requirePropertyKey"))) return null;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return "无法校验资产钥匙（数据库不可用）";
            try (var ps = conn.prepareStatement(
                    "SELECT id FROM ks_re_plots WHERE owner_type='PLAYER' AND owner_id=? " +
                    "AND dungeon_template_id=? AND instance_id IS NULL " +
                    "UNION " +
                    "SELECT h.id FROM ks_re_houses h JOIN ks_re_plots p ON p.id = h.plot_id " +
                    "WHERE h.owner_type='PLAYER' AND h.owner_id=? AND h.dungeon_template_id=? AND p.instance_id IS NULL " +
                    "LIMIT 1")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, templateId);
                ps.setString(3, uuid.toString());
                ps.setString(4, templateId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return null;
                }
            }
        } catch (SQLException e) {
            return "无法校验资产钥匙（地产模块未安装或数据缺失）";
        }
        return "你没有持有解锁本副本的住宅地产，请先购买地块（或商品房）并确认其绑定了该副本";
    }

    // ================================================================
    // 实例生命周期
    // ================================================================

    /**
     * 购票进入副本。
     * @return instanceId 或 null（失败）
     */
    public String createInstance(String templateId, UUID ownerUuid, String ownerName) {
        Map<String, Object> template = getTemplate(templateId);
        if (template == null) return null;
        double price = ((Number) template.get("ticketPrice")).doubleValue();

        // 1) 扣门票
        if (!eco.vaultHook().isAvailable()) return null;
        var p = Bukkit.getOfflinePlayer(ownerUuid);
        if (!eco.vaultHook().has(p, price)) return null;
        if (!eco.vaultHook().withdraw(p, price)) return null;

        // 2) 分配网格
        Map<String, Object> grid = gridAllocator.allocate();
        if (grid == null) {
            eco.vaultHook().deposit(p, price);
            return null;
        }
        String gridId = (String) grid.get("id");

        // 3) 写实例
        String instanceId = "DI" + UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        int timeLimitMin = ((Number) template.get("timeLimitMinutes")).intValue();
        long expiresAt = now + timeLimitMin * 60L;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) {
                refundAndRelease(ownerUuid, price, gridId);
                return null;
            }
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_dungeon_instances (id, template_id, grid_id, status, " +
                    "started_at, expires_at, owner_uuid, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, instanceId);
                ps.setString(2, templateId);
                ps.setString(3, gridId);
                ps.setString(4, STATUS_WAITING);
                ps.setLong(5, now);
                ps.setLong(6, expiresAt);
                ps.setString(7, ownerUuid.toString());
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_dungeon_participants (instance_id, player_uuid, player_name, joined_at, status) " +
                    "VALUES (?,?,?,?, 'ALIVE')")) {
                ps.setString(1, instanceId);
                ps.setString(2, ownerUuid.toString());
                ps.setString(3, ownerName == null ? "" : ownerName);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            logEvent(instanceId, "START", ownerUuid.toString(), "template=" + templateId + " grid=" + gridId);
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 创建实例失败: " + e.getMessage());
            refundAndRelease(ownerUuid, price, gridId);
            return null;
        }

        // 4) 异步加载虚空世界（如果还没加载）→ 主线程贴图/刷怪 → 传送
        asyncLoadDungeonWorld((String) grid.get("world"), () ->
                Bukkit.getScheduler().runTask(eco, () ->
                        prepareMapAndActivate(instanceId, templateId,
                                () -> activateAndTeleport(instanceId, ownerUuid))));
        playerToInstance.put(ownerUuid.toString(), instanceId);
        return instanceId;
    }

    private void activateAndTeleport(String instanceId, UUID ownerUuid) {
        // 1) 更新状态为 ACTIVE
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_dungeon_instances SET status='ACTIVE' WHERE id=? AND status='WAITING'")) {
                ps.setString(1, instanceId);
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {}

        // 2) 传送玩家到网格中心
        Map<String, Object> grid = getGridByInstance(instanceId);
        if (grid == null) return;
        Player player = Bukkit.getPlayer(ownerUuid);
        if (player == null || !player.isOnline()) return;
        World world = Bukkit.getWorld((String) grid.get("world"));
        if (world == null) return;
        int cx = ((Number) grid.get("centerX")).intValue();
        int cz = ((Number) grid.get("centerZ")).intValue();
        Location loc = resolveSpawnLocation(instanceId, world, cx, cz);
        player.teleport(loc);
        player.sendMessage("§6[副本] §a你已进入副本 " + instanceId);
    }

    /**
     * 组队开本：队长付一张门票，全队一次性进入。members 必须含队长，人数应已由调用方校验在 [min,max]。
     * @return instanceId 或 null（失败）
     */
    public String createInstanceForParty(String templateId, UUID leaderUuid, String leaderName, List<UUID> members) {
        Map<String, Object> template = getTemplate(templateId);
        if (template == null) return null;
        double price = ((Number) template.get("ticketPrice")).doubleValue();
        if (!eco.vaultHook().isAvailable()) return null;
        var lp = Bukkit.getOfflinePlayer(leaderUuid);
        if (!eco.vaultHook().has(lp, price)) return null;
        if (!eco.vaultHook().withdraw(lp, price)) return null;

        Map<String, Object> grid = gridAllocator.allocate();
        if (grid == null) { eco.vaultHook().deposit(lp, price); return null; }
        String gridId = (String) grid.get("id");

        String instanceId = "DI" + UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;
        int timeLimitMin = ((Number) template.get("timeLimitMinutes")).intValue();
        long expiresAt = now + timeLimitMin * 60L;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) { refundAndRelease(leaderUuid, price, gridId); return null; }
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_dungeon_instances (id, template_id, grid_id, status, " +
                    "started_at, expires_at, owner_uuid, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, instanceId);
                ps.setString(2, templateId);
                ps.setString(3, gridId);
                ps.setString(4, STATUS_WAITING);
                ps.setLong(5, now);
                ps.setLong(6, expiresAt);
                ps.setString(7, leaderUuid.toString());
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_dungeon_participants (instance_id, player_uuid, player_name, joined_at, status) " +
                    "VALUES (?,?,?,?, 'ALIVE')")) {
                for (UUID m : members) {
                    var op = Bukkit.getOfflinePlayer(m);
                    ps.setString(1, instanceId);
                    ps.setString(2, m.toString());
                    ps.setString(3, op.getName() == null ? "" : op.getName());
                    ps.setLong(4, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            logEvent(instanceId, "START", leaderUuid.toString(),
                    "template=" + templateId + " grid=" + gridId + " party=" + members.size());
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 组队创建实例失败: " + e.getMessage());
            refundAndRelease(leaderUuid, price, gridId);
            return null;
        }
        for (UUID m : members) playerToInstance.put(m.toString(), instanceId);
        final List<UUID> snapshot = new ArrayList<>(members);
        asyncLoadDungeonWorld((String) grid.get("world"), () ->
                Bukkit.getScheduler().runTask(eco, () ->
                        prepareMapAndActivate(instanceId, templateId,
                                () -> activateAndTeleportAll(instanceId, snapshot))));
        return instanceId;
    }

    private void activateAndTeleportAll(String instanceId, List<UUID> members) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) try (var ps = conn.prepareStatement(
                    "UPDATE ks_dungeon_instances SET status='ACTIVE' WHERE id=? AND status='WAITING'")) {
                ps.setString(1, instanceId);
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {}
        Map<String, Object> grid = getGridByInstance(instanceId);
        if (grid == null) return;
        World world = Bukkit.getWorld((String) grid.get("world"));
        if (world == null) return;
        int cx = ((Number) grid.get("centerX")).intValue();
        int cz = ((Number) grid.get("centerZ")).intValue();
        Location loc = resolveSpawnLocation(instanceId, world, cx, cz);
        for (UUID m : members) {
            Player pl = Bukkit.getPlayer(m);
            if (pl != null && pl.isOnline()) {
                pl.teleport(loc);
                pl.sendMessage("§6[副本] §a你已进入副本 " + instanceId);
            }
        }
    }

    private Map<String, Object> getGridByInstance(String instanceId) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "SELECT g.* FROM ks_dungeon_grids g " +
                    "JOIN ks_dungeon_instances i ON i.grid_id=g.id WHERE i.id=?")) {
                ps.setString(1, instanceId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("world", rs.getString("world"));
                        m.put("centerX", rs.getInt("grid_x"));
                        m.put("centerZ", rs.getInt("grid_z"));
                        return m;
                    }
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public boolean leaveInstance(String instanceId, UUID playerUuid) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_dungeon_participants SET status='LEFT' WHERE instance_id=? AND player_uuid=?")) {
                ps.setString(1, instanceId);
                ps.setString(2, playerUuid.toString());
                boolean ok = ps.executeUpdate() > 0;
                if (ok) {
                    playerToInstance.remove(playerUuid.toString());
                    logEvent(instanceId, "LEAVE", playerUuid.toString(), "");
                    // 传送回主世界（默认 overworld）
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player != null && player.isOnline()) {
                        World mainWorld = Bukkit.getWorld("world");
                        if (mainWorld != null) {
                            player.teleport(mainWorld.getSpawnLocation());
                            player.sendMessage("§6[副本] §a你已离开副本 " + instanceId);
                        }
                    }
                }
                return ok;
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 离开副本失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 强制结束副本（admin）。
     */
    public boolean forceEnd(String instanceId) {
        return endInstance(instanceId, STATUS_ABANDONED, "FORCE_END", "§6[副本] §c本副本已被管理员强制结束。");
    }

    /** Boss 被击杀后自动判定通关（由监控任务调用）。 */
    public boolean completeInstance(String instanceId) {
        return endInstance(instanceId, STATUS_COMPLETED, "BOSS_KILLED", "§6[副本] §a恭喜，Boss 已被击败，副本通关！");
    }

    /** 超过模板配置的时限后自动结束（由监控任务调用）。 */
    public boolean timeoutInstance(String instanceId) {
        return endInstance(instanceId, STATUS_ABANDONED, "TIMEOUT", "§6[副本] §c本副本已超时，自动结束。");
    }

    /**
     * 通用结束流程（强制结束/通关/超时三处共用）：标记终态 → 踢出并传送在线参与者回主世界 →
     * 清空副本内房产 → 清场（杀实体+清地形）→ 标记网格 CLEANING → 调度释放。
     * @param finalStatus COMPLETED 或 ABANDONED
     */
    private boolean endInstance(String instanceId, String finalStatus, String eventType, String kickMessage) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            String gridId;
            String templateId;
            String rewardConfig = "";
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_dungeon_instances SET status=? WHERE id=? AND status IN ('WAITING','ACTIVE')")) {
                ps.setString(1, finalStatus);
                ps.setString(2, instanceId);
                if (ps.executeUpdate() == 0) return false;
            }
            try (var ps = conn.prepareStatement("SELECT grid_id, template_id FROM ks_dungeon_instances WHERE id=?")) {
                ps.setString(1, instanceId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    gridId = rs.getString(1);
                    templateId = rs.getString(2);
                }
            }
            if (STATUS_COMPLETED.equals(finalStatus)) {
                try (var ps = conn.prepareStatement("SELECT reward_config FROM ks_dungeon_templates WHERE id=?")) {
                    ps.setString(1, templateId);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) rewardConfig = readRewardConfig(rs);
                    }
                }
            }
            // 结束前先拿到仍在副本里的参与者名单（用于传送+提示），再统一标 LEFT
            List<String> remaining = new ArrayList<>();
            try (var ps = conn.prepareStatement(
                    "SELECT player_uuid FROM ks_dungeon_participants WHERE instance_id=? AND status<>'LEFT'")) {
                ps.setString(1, instanceId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) remaining.add(rs.getString(1));
                }
            }
            List<UUID> rewardParticipants = new ArrayList<>();
            if (STATUS_COMPLETED.equals(finalStatus)) {
                try (var ps = conn.prepareStatement(
                        "SELECT player_uuid FROM ks_dungeon_participants WHERE instance_id=?")) {
                    ps.setString(1, instanceId);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                rewardParticipants.add(UUID.fromString(rs.getString(1)));
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }
                }
            }
            if (STATUS_COMPLETED.equals(finalStatus) && rpgBridge != null && !rewardParticipants.isEmpty()) {
                rpgBridge.grantCompletionRewards(instanceId, templateId, rewardConfig, rewardParticipants,
                        (uuid, detail) -> logEvent(instanceId, "REWARD", uuid.toString(), detail));
            }
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_dungeon_participants SET status='LEFT' WHERE instance_id=? AND status<>'LEFT'")) {
                ps.setString(1, instanceId);
                ps.executeUpdate();
            }
            logEvent(instanceId, eventType, "", "");
            // 清理副本内房产（实例生命周期清理；ks_re_plots 由 ks-Eco-RealEstate 拥有，此处仅删本实例 instance_id 行）
            int deleted = 0;
            try (var ps = conn.prepareStatement("DELETE FROM ks_re_plots WHERE instance_id=?")) {
                ps.setString(1, instanceId);
                deleted = ps.executeUpdate();
            } catch (SQLException ignore) { /* 列不存在/房产插件未装：忽略 */ }
            final int plotsDeleted = deleted;
            // 清出内存映射
            playerToInstance.values().removeIf(v -> v.equals(instanceId));
            instanceSpawnOverride.remove(instanceId);
            instancePasteCenter.remove(instanceId);
            instanceBoss.remove(instanceId);
            // 把还在副本里的在线玩家传送回主世界（此前漏了这一步，结束时玩家会被晾在即将清空的场地里）
            World mainWorld = Bukkit.getWorld("world");
            Bukkit.getScheduler().runTask(eco, () -> {
                for (String uuidStr : remaining) {
                    try {
                        Player pl = Bukkit.getPlayer(UUID.fromString(uuidStr));
                        if (pl != null && pl.isOnline()) {
                            if (mainWorld != null) pl.teleport(mainWorld.getSpawnLocation());
                            if (kickMessage != null) pl.sendMessage(kickMessage);
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
            });
            // 清场：杀实体 + 清地形（异步），回收内存
            cleanupArena(gridId);
            // 标记网格 CLEANING
            gridAllocator.markCleaning(gridId);
            // 调度释放网格
            long delayTicks = configManager.snapshot().cleanTimeoutSeconds * 20L;
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                gridAllocator.release(gridId);
                eco.getLogger().info("[副本系统] 副本 " + instanceId + " 已清理（房产 " + plotsDeleted + " 个）");
            }, delayTicks);
            return true;
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 结束副本失败: " + e.getMessage());
        }
        return false;
    }

    // ================================================================
    // 监控任务：boss 存活检测 + 超时检测
    // ================================================================

    /** 启动周期监控任务（每 100 ticks/约 5 秒一次）。重复调用安全（已在跑则忽略）。 */
    public void startMonitor() {
        if (monitorTask != null) return;
        monitorTask = Bukkit.getScheduler().runTaskTimer(eco, () -> {
            checkBossDeaths();
            checkExpiredInstances();
        }, 100L, 100L);
    }

    public void stopMonitor() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
    }

    /** 逐个检查已记录 boss 的副本，boss 死亡（MM 已不再追踪该实体）则自动通关。 */
    private void checkBossDeaths() {
        if (instanceBoss.isEmpty()) return;
        for (var entry : new ArrayList<>(instanceBoss.entrySet())) {
            String instanceId = entry.getKey();
            UUID bossUuid = entry.getValue();
            if (!MythicSpawner.isAlive(bossUuid)) {
                eco.getLogger().info("[副本系统] 副本 " + instanceId + " 的 boss 已死亡，自动判定通关。");
                completeInstance(instanceId);
            }
        }
    }

    /** 查所有未结束但已超过 expires_at 的实例，逐个自动结束（标 ABANDONED）。 */
    private void checkExpiredInstances() {
        long now = System.currentTimeMillis() / 1000;
        List<String> expired = new ArrayList<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var ps = conn.prepareStatement(
                    "SELECT id FROM ks_dungeon_instances WHERE status IN ('WAITING','ACTIVE') AND expires_at < ?")) {
                ps.setLong(1, now);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) expired.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 检查超时副本失败: " + e.getMessage());
            return;
        }
        for (String id : expired) {
            eco.getLogger().info("[副本系统] 副本 " + id + " 已超时，自动结束。");
            timeoutInstance(id);
        }
    }

    public List<Map<String, Object>> listInstances(String statusFilter, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            String sql = "SELECT * FROM ks_dungeon_instances" +
                    (statusFilter != null ? " WHERE status=?" : "") +
                    " ORDER BY created_at DESC LIMIT ?";
            try (var ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (statusFilter != null) ps.setString(idx++, statusFilter);
                ps.setInt(idx, limit > 0 ? limit : 50);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("templateId", rs.getString("template_id"));
                        m.put("gridId", rs.getString("grid_id"));
                        m.put("status", rs.getString("status"));
                        m.put("startedAt", rs.getLong("started_at"));
                        m.put("expiresAt", rs.getLong("expires_at"));
                        m.put("ownerUuid", rs.getString("owner_uuid"));
                        m.put("createdAt", rs.getLong("created_at"));
                        out.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 列实例失败: " + e.getMessage());
        }
        return out;
    }

    public Map<String, Object> getInstance(String id) {
        if (id == null) return null;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement("SELECT * FROM ks_dungeon_instances WHERE id=?")) {
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("templateId", rs.getString("template_id"));
                        m.put("gridId", rs.getString("grid_id"));
                        m.put("status", rs.getString("status"));
                        m.put("startedAt", rs.getLong("started_at"));
                        m.put("expiresAt", rs.getLong("expires_at"));
                        m.put("ownerUuid", rs.getString("owner_uuid"));
                        m.put("createdAt", rs.getLong("created_at"));
                        return m;
                    }
                }
            }
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] 查实例失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 查玩家在某个副本内的状态。
     */
    public Map<String, Object> getParticipantStatus(String instanceId, UUID playerUuid) {
        if (instanceId == null || playerUuid == null) return null;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM ks_dungeon_participants WHERE instance_id=? AND player_uuid=?")) {
                ps.setString(1, instanceId);
                ps.setString(2, playerUuid.toString());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("instanceId", rs.getString("instance_id"));
                        m.put("playerUuid", rs.getString("player_uuid"));
                        m.put("status", rs.getString("status"));
                        m.put("joinedAt", rs.getLong("joined_at"));
                        m.put("diedAt", rs.getLong("died_at"));
                        m.put("reviveCount", rs.getInt("revive_count"));
                        return m;
                    }
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public String getPlayerActiveInstance(UUID playerUuid) {
        return playerToInstance.get(playerUuid.toString());
    }

    public void recordDeath(String instanceId, UUID playerUuid) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_dungeon_participants SET status='DEAD', died_at=? " +
                    "WHERE instance_id=? AND player_uuid=?")) {
                ps.setLong(1, System.currentTimeMillis() / 1000);
                ps.setString(2, instanceId);
                ps.setString(3, playerUuid.toString());
                ps.executeUpdate();
            }
            logEvent(instanceId, "DEATH", playerUuid.toString(), "");
        } catch (SQLException ignored) {}
    }

    public void recordRevive(String instanceId, UUID playerUuid, int reviveCount,
                              double costPaid, double formulaCost) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            String revId = "DR" + UUID.randomUUID().toString().substring(0, 8);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_dungeon_revivals (id, instance_id, player_uuid, revive_count, " +
                    "cost_paid, formula_cost, revived_at) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, revId);
                ps.setString(2, instanceId);
                ps.setString(3, playerUuid.toString());
                ps.setInt(4, reviveCount);
                ps.setDouble(5, costPaid);
                ps.setDouble(6, formulaCost);
                ps.setLong(7, System.currentTimeMillis() / 1000);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(
                    "UPDATE ks_dungeon_participants SET status='ALIVE', revive_count=?, died_at=0 " +
                    "WHERE instance_id=? AND player_uuid=?")) {
                ps.setInt(1, reviveCount);
                ps.setString(2, instanceId);
                ps.setString(3, playerUuid.toString());
                ps.executeUpdate();
            }
            logEvent(instanceId, "REVIVE", playerUuid.toString(),
                    "count=" + reviveCount + " cost=" + costPaid);
        } catch (SQLException ignored) {}
    }

    public List<Map<String, Object>> listLogs(String instanceId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            String sql = "SELECT * FROM ks_dungeon_log" +
                    (instanceId != null ? " WHERE instance_id=?" : "") +
                    " ORDER BY id DESC LIMIT ?";
            try (var ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (instanceId != null) ps.setString(idx++, instanceId);
                ps.setInt(idx, limit > 0 ? limit : 100);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("instanceId", rs.getString("instance_id"));
                        m.put("eventType", rs.getString("event_type"));
                        m.put("playerUuid", rs.getString("player_uuid"));
                        m.put("detail", rs.getString("detail"));
                        m.put("createdAt", rs.getLong("created_at"));
                        out.add(m);
                    }
                }
            }
        } catch (SQLException ignored) {}
        return out;
    }

    public void logEvent(String instanceId, String eventType, String playerUuid, String detail) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (var ps = conn.prepareStatement(
                    "INSERT INTO ks_dungeon_log (instance_id, event_type, player_uuid, detail, created_at) " +
                    "VALUES (?,?,?,?,?)")) {
                ps.setString(1, instanceId);
                ps.setString(2, eventType);
                ps.setString(3, playerUuid == null ? "" : playerUuid);
                ps.setString(4, detail == null ? "" : detail);
                ps.setLong(5, System.currentTimeMillis() / 1000);
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {}
    }

    public int getCurrentReviveCount(String instanceId, UUID playerUuid) {
        Map<String, Object> p = getParticipantStatus(instanceId, playerUuid);
        if (p == null) return 0;
        Object v = p.get("reviveCount");
        return v == null ? 0 : ((Number) v).intValue();
    }

    // ================================================================
    // 地图：FAWE 贴 schematic + 告示牌标记刷怪 + 清场
    // ================================================================

    /**
     * 在传送前准备地图（主线程入口）。模板配了 schematic 且装了 FAWE 时：
     * 异步先清画布→贴图，回主线程扫描 [mm]/[spawn] 告示牌标记（刷怪+定出生点），再执行 activate。
     * 无图 / 无 FAWE / 找不到文件 → 直接 activate（保持纯虚空行为）。
     */
    private void prepareMapAndActivate(String instanceId, String templateId, Runnable activate) {
        Map<String, Object> tpl = getTemplate(templateId);
        String schem = tpl == null ? null : (String) tpl.get("schematic");
        Map<String, Object> grid = getGridByInstance(instanceId);
        DungeonConfigManager.ConfigSnapshot cfg = configManager.snapshot();

        if (grid == null || schem == null || schem.isBlank()) { activate.run(); return; }
        if (!SchematicService.isAvailable()) {
            eco.getLogger().warning("[副本系统] 模板配置了地图但服务器未装 FastAsyncWorldEdit，跳过贴图: " + schem);
            activate.run();
            return;
        }
        final World world = Bukkit.getWorld((String) grid.get("world"));
        if (world == null) { activate.run(); return; }
        final File file = SchematicService.resolveFile(eco.getDataFolder(), schem);
        if (file == null) {
            eco.getLogger().warning("[副本系统] 找不到 schematic 文件: " + schem
                    + "（放到 plugins/ks-Eco/dungeon_schematics/ 或 FAWE schematics 目录），跳过贴图");
            activate.run();
            return;
        }

        final int cx = ((Number) grid.get("centerX")).intValue();
        final int cz = ((Number) grid.get("centerZ")).intValue();
        final int baseY = cfg.mapBaseY;
        final int radius = cfg.mapArenaRadius;
        final boolean spawnMobs = cfg.mapSpawnMobs;
        final int level = tpl.get("monsterLevel") == null ? 10 : ((Number) tpl.get("monsterLevel")).intValue();

        Bukkit.getScheduler().runTaskAsynchronously(eco, () -> {
            int[] yRange = arenaYRange(world, baseY);
            // 先清画布：防止复用网格时上一副本地形残留
            SchematicService.clearRegion(eco, world, cx - radius, yRange[0], cz - radius,
                    cx + radius, yRange[1], cz + radius);
            int[] box = SchematicService.paste(eco, world, file, cx, baseY, cz);
            Bukkit.getScheduler().runTask(eco, () -> {
                if (box != null) {
                    // 包围盒几何中心做兜底出生点（远比网格角落可靠：角落大概率落在结构外的空气里）
                    int midX = (box[0] + box[3]) / 2;
                    int midZ = (box[2] + box[5]) / 2;
                    int groundY = world.getHighestBlockYAt(midX, midZ);
                    if (groundY <= world.getMinHeight()) groundY = box[1]; // 中心列仍无地面则退回贴图最低层
                    instancePasteCenter.put(instanceId, new Location(world, midX + 0.5, groundY + 1.0, midZ + 0.5));
                    int mobs = scanMarkers(instanceId, world, box, level, spawnMobs);
                    logEvent(instanceId, "MAP_PASTED", "", "schem=" + schem + " mobs=" + mobs);
                    eco.getLogger().info("[副本系统] 副本 " + instanceId + " 贴图 " + schem
                            + " 完成（包围盒 " + box[0] + "," + box[1] + "," + box[2] + " ~ "
                            + box[3] + "," + box[4] + "," + box[5] + "），刷怪 " + mobs + " 只");
                } else {
                    eco.getLogger().warning("[副本系统] 副本 " + instanceId + " 贴图失败: " + schem);
                }
                activate.run();
            });
        });
    }

    /**
     * 扫描贴图区域内的告示牌标记（主线程）。
     *   第1行 [mm]   第2行 怪物名:等级  → spawnMythicMob，然后清除该牌
     *   第1行 [spawn]                    → 记为玩家出生点覆盖，然后清除该牌
     * @return 成功刷出的怪物数
     */
    private int scanMarkers(String instanceId, World world, int[] box, int defaultLevel, boolean spawnMobs) {
        int minX = box[0], minY = box[1], minZ = box[2], maxX = box[3], maxY = box[4], maxZ = box[5];
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > 12_000_000L) {
            eco.getLogger().warning("[副本系统] schematic 体积较大(" + volume + " 方块)，扫描刷怪点可能有短暂卡顿: " + instanceId);
        }
        int mobs = 0;
        int signsSeen = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    // 廉价过滤：仅对告示牌方块取 BlockState
                    if (!b.getType().name().endsWith("_SIGN")) continue;
                    if (!(b.getState() instanceof Sign sign)) continue;
                    signsSeen++;
                    String[] lines = sign.getLines();
                    String tag = stripJsonQuotes(lines.length > 0 ? lines[0] : "").toLowerCase(Locale.ROOT);
                    String arg = stripJsonQuotes(lines.length > 1 ? lines[1] : "");
                    boolean isBoss = lines.length > 2 && "boss".equalsIgnoreCase(stripJsonQuotes(lines[2]).trim());
                    eco.getLogger().info("[副本系统] 扫描到告示牌 @" + x + "," + y + "," + z
                            + " tag=\"" + tag + "\" arg=\"" + arg + "\"" + (isBoss ? " [BOSS]" : ""));
                    Location at = new Location(world, x + 0.5, y, z + 0.5);
                    if (tag.equals("[mm]")) {
                        if (spawnMobs && !arg.isEmpty()) {
                            String mobName = arg;
                            int level = defaultLevel;
                            int colon = arg.lastIndexOf(':');
                            if (colon > 0) {
                                mobName = arg.substring(0, colon).trim();
                                try { level = Integer.parseInt(arg.substring(colon + 1).trim()); }
                                catch (NumberFormatException ignored) {}
                            }
                            boolean completionTarget = isBoss || MythicSpawner.isCompletionController(mobName);
                            UUID spawnedUuid = MythicSpawner.spawn(eco, mobName, at, level);
                            eco.getLogger().info("[副本系统] 刷怪 " + mobName + "@" + at + " -> " + (spawnedUuid != null));
                            if (spawnedUuid != null) {
                                mobs++;
                                if (completionTarget) {
                                    instanceBoss.put(instanceId, spawnedUuid);
                                    eco.getLogger().info("[副本系统] 副本 " + instanceId + " 已记录通关检测目标: " + mobName + " (" + spawnedUuid + ")");
                                }
                            } else if (completionTarget) {
                                eco.getLogger().warning("[副本系统] 副本 " + instanceId + " 的通关检测目标刷怪未拿到实体引用，无法自动判定通关: " + mobName);
                            }
                        }
                        b.setType(Material.AIR, false);  // 清除标记牌
                    } else if (tag.equals("[spawn]")) {
                        instanceSpawnOverride.put(instanceId, new Location(world, x + 0.5, y, z + 0.5));
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
        eco.getLogger().info("[副本系统] scanMarkers 完成: 共扫到 " + signsSeen + " 块告示牌, box=["
                + minX + "," + minY + "," + minZ + " ~ " + maxX + "," + maxY + "," + maxZ + "]");
        return mobs;
    }

    /** 出生点：优先 [spawn] 标记覆盖，否则网格中心的最高方块上方。 */
    private Location resolveSpawnLocation(String instanceId, World world, int cx, int cz) {
        Location ov = instanceSpawnOverride.get(instanceId);
        if (ov != null && ov.getWorld() != null) return ov.clone();
        Location center = instancePasteCenter.get(instanceId);
        if (center != null && center.getWorld() != null) return center.clone();
        return new Location(world, cx + 0.5, world.getHighestBlockYAt(cx, cz) + 1.0, cz + 0.5);
    }

    /** 部分 schematic 来源（如 mcschematic 等第三方工具生成）写入的 front_text 行带有原始 JSON 引号，
     *  Sign#getLines() 在本服务器版本上不会再做一次 JSON 解码，需要手动剥掉首尾引号再比较标记。 */
    private static String stripJsonQuotes(String s) {
        s = s == null ? "" : s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.trim();
    }

    private int[] arenaYRange(World world, int baseY) {
        int yMin = Math.max(world.getMinHeight(), baseY - 16);
        int yMax = Math.min(world.getMaxHeight() - 1, baseY + 255);
        return new int[]{yMin, yMax};
    }

    /**
     * 清场（副本结束时）：杀网格区域内所有非玩家实体 + FAWE 把地形清回空气，回收内存。
     */
    private void cleanupArena(String gridId) {
        Map<String, Object> grid = getGridById(gridId);
        if (grid == null) return;
        final World world = Bukkit.getWorld((String) grid.get("world"));
        if (world == null) return;
        final int cx = ((Number) grid.get("centerX")).intValue();
        final int cz = ((Number) grid.get("centerZ")).intValue();
        DungeonConfigManager.ConfigSnapshot cfg = configManager.snapshot();
        final int baseY = cfg.mapBaseY;
        final int radius = cfg.mapArenaRadius;
        final Location center = new Location(world, cx + 0.5, baseY, cz + 0.5);
        Bukkit.getScheduler().runTask(eco, () -> {
            try {
                for (Entity e : world.getNearbyEntities(center, radius, 256, radius)) {
                    if (!(e instanceof Player)) e.remove();
                }
            } catch (Throwable ignored) {}
            if (SchematicService.isAvailable()) {
                Bukkit.getScheduler().runTaskAsynchronously(eco, () -> {
                    int[] yRange = arenaYRange(world, baseY);
                    SchematicService.clearRegion(eco, world, cx - radius, yRange[0], cz - radius,
                            cx + radius, yRange[1], cz + radius);
                });
            }
        });
    }

    private Map<String, Object> getGridById(String gridId) {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (var ps = conn.prepareStatement("SELECT * FROM ks_dungeon_grids WHERE id=?")) {
                ps.setString(1, gridId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("world", rs.getString("world"));
                        m.put("centerX", rs.getInt("grid_x"));
                        m.put("centerZ", rs.getInt("grid_z"));
                        return m;
                    }
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    // ================================================================
    // 异步世界管理
    // ================================================================

    private final Set<String> loadingWorlds = ConcurrentHashMap.newKeySet();

    private void asyncLoadDungeonWorld(String worldName, Runnable onReady) {
        if (Bukkit.getWorld(worldName) != null) {
            onReady.run();
            return;
        }
        if (!loadingWorlds.add(worldName)) return;  // 已经在加载
        Bukkit.getScheduler().runTaskAsynchronously(eco, () -> {
            try {
                // 同步代码创建世界（需要主线程）—— 切回主线程
                Bukkit.getScheduler().runTask(eco, () -> {
                    try {
                        var creator = new org.bukkit.WorldCreator(worldName)
                                .type(org.bukkit.WorldType.FLAT)
                                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:the_void\"}")
                                .generateStructures(false);
                        var w = Bukkit.createWorld(creator);
                        if (w != null) {
                            eco.getLogger().info("[副本系统] 虚空世界 " + worldName + " 已创建");
                        }
                        onReady.run();
                    } catch (Exception e) {
                        eco.getLogger().warning("[副本系统] 加载虚空世界失败: " + e.getMessage());
                    } finally {
                        loadingWorlds.remove(worldName);
                    }
                });
            } catch (Exception e) {
                loadingWorlds.remove(worldName);
                eco.getLogger().warning("[副本系统] 加载虚空世界失败: " + e.getMessage());
            }
        });
    }

    private void refundAndRelease(UUID owner, double price, String gridId) {
        var p = Bukkit.getOfflinePlayer(owner);
        if (eco.vaultHook().isAvailable()) eco.vaultHook().deposit(p, price);
        gridAllocator.release(gridId);
    }
}
