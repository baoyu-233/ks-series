package org.kseco.extra.realestatedungeon;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.kseco.KsEco;
import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.PortableSqlMutation;
import org.kseries.instanceworld.api.InstanceGridSpec;
import org.kseries.instanceworld.api.InstanceMarker;
import org.kseries.instanceworld.api.InstancePreparation;
import org.kseries.instanceworld.api.InstancePrepareRequest;
import org.kseries.instanceworld.api.InstanceWorldApi;
import org.kseries.instanceworld.api.PreparedInstance;
import org.kseries.instanceworld.api.ReleaseCause;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * 副本实例生命周期管理。
 *
 * 状态机：
 *   WAITING (购票后立即) → ACTIVE (玩家进入并 spawn) → COMPLETED / ABANDONED (结束)
 *
 * 实例世界、网格、schematic 与清理由 ks-InstanceWorld 管理；本类只编排经济和战斗流程。
 */
public final class DungeonInstanceManager {

    private static final int MAX_AUTO_REWARD_ATTEMPTS = 3;

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    private final KsEco eco;
    private final DungeonConfigManager configManager;
    private final InstanceWorldApi instanceWorld;
    private final DungeonRpgBridge rpgBridge;
    private final ConcurrentHashMap<String, String> playerToInstance = new ConcurrentHashMap<>();
    private final Set<String> pendingPlayers = ConcurrentHashMap.newKeySet();
    private final String localSettlementInstanceId = UUID.randomUUID().toString().replace("-", "");
    private final ConcurrentHashMap<String, InstancePreparation> pendingPreparations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PreparedInstance> pendingAdmissions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PreparedInstance> preparedWorlds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> worldInstanceIds = new ConcurrentHashMap<>();
    /** 实例 → 全部通关目标 UUID；只有集合中所有目标均死亡才允许完成。 */
    private final ConcurrentHashMap<String, Set<UUID>> instanceBosses = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask monitorTask;

    private record TemplateWrite(String id, String name, String difficulty, double ticketPrice,
                                 int minPlayers, int maxPlayers, int timeLimitMinutes, int monsterLevel,
                                 String description, String schematic, int requirePropertyKey,
                                 String rewardConfig, long createdAt) { }

    public DungeonInstanceManager(KsEco eco, DungeonConfigManager configManager,
                                  InstanceWorldApi instanceWorld,
                                  DungeonRpgBridge rpgBridge) {
        this.eco = eco;
        this.configManager = configManager;
        this.instanceWorld = instanceWorld;
        this.rpgBridge = rpgBridge;
    }

    public void init() {
        ensureTables();
        recoverTicketSettlements();
        recoverActiveInstances();
        recoverPendingRevives();
        recoverRewardGrants();
    }

    private void ensureTables() {
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            DungeonSchema.initializeBase(conn);
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
                BusinessSchemaDialect.createIndexIfMissing(
                        conn, "idx_di_status", "ks_dungeon_instances", "status");
                BusinessSchemaDialect.createIndexIfMissing(
                        conn, "idx_di_owner", "ks_dungeon_instances", "owner_uuid");

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
                        revived_at INTEGER NOT NULL,
                        return_status TEXT NOT NULL DEFAULT 'RETURNED',
                        last_error TEXT NOT NULL DEFAULT ''
                    )
                """);
                BusinessSchemaDialect.createIndexIfMissing(
                        conn, "idx_dr_instance", "ks_dungeon_revivals", "instance_id");

                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_dungeon_log (
                        id INTEGER PRIMARY KEY,
                        instance_id TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        player_uuid TEXT DEFAULT '',
                        detail TEXT DEFAULT '',
                        created_at INTEGER NOT NULL
                    )
                """);
                BusinessSchemaDialect.createIndexIfMissing(
                        conn, "idx_dl_instance", "ks_dungeon_log", "instance_id");

                s.execute("""
                    CREATE TABLE IF NOT EXISTS ks_dungeon_reward_roster (
                        instance_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        PRIMARY KEY(instance_id, player_uuid)
                    )
                """);

                // 地图：模板关联的 schematic 图名（FAWE 贴图）。老库兼容用 ALTER。
                try { s.execute("ALTER TABLE ks_dungeon_templates ADD COLUMN schematic TEXT DEFAULT ''"); }
                catch (SQLException ignore) { /* 列已存在 */ }
                // 资产联动：开本前是否要求发起人持有绑定本模板的住宅地块（ks_re_plots.dungeon_template_id）
                try { s.execute("ALTER TABLE ks_dungeon_templates ADD COLUMN require_property_key INTEGER NOT NULL DEFAULT 0"); }
                catch (SQLException ignore) { /* 列已存在 */ }
                try { s.execute("ALTER TABLE ks_dungeon_templates ADD COLUMN reward_config TEXT DEFAULT ''"); }
                catch (SQLException ignore) { /* column already exists */ }
                try { s.execute("ALTER TABLE ks_dungeon_instances ADD COLUMN instance_world_id TEXT DEFAULT ''"); }
                catch (SQLException ignore) { /* column already exists */ }
                try { s.execute("ALTER TABLE ks_dungeon_instances ADD COLUMN reward_status TEXT DEFAULT 'NONE'"); }
                catch (SQLException ignore) { /* column already exists */ }
                try { s.execute("ALTER TABLE ks_dungeon_revivals ADD COLUMN return_status TEXT NOT NULL DEFAULT 'RETURNED'"); }
                catch (SQLException ignore) { /* column already exists */ }
                try { s.execute("ALTER TABLE ks_dungeon_revivals ADD COLUMN last_error TEXT NOT NULL DEFAULT ''"); }
                catch (SQLException ignore) { /* column already exists */ }
                try {
                    s.execute("UPDATE ks_dungeon_instances SET reward_status='NONE' " +
                            "WHERE reward_status IS NULL OR reward_status=''");
                } catch (SQLException ignore) { /* best effort backfill */ }
            }
            DungeonTicketSettlementStore.createSchema(conn);
            DungeonRewardGrantStore.initialize(conn);
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
        TemplateWrite row = new TemplateWrite(id, name, difficulty, Math.max(0, ticketPrice),
                Math.max(1, minPlayers), Math.max(minPlayers, maxPlayers), Math.max(5, timeLimitMinutes),
                Math.max(1, monsterLevel), description == null ? "" : description,
                schematic == null ? "" : schematic.trim(), requirePropertyKey ? 1 : 0,
                rewardConfig == null ? "" : rewardConfig.trim(), now);
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            PortableSqlMutation.upsert(conn,
                    "UPDATE ks_dungeon_templates SET name=?,difficulty=?,ticket_price=?,min_players=?,"
                            + "max_players=?,time_limit_minutes=?,monster_level=?,description=?,schematic=?,"
                            + "require_property_key=?,reward_config=? WHERE id=?",
                    statement -> bindTemplateUpdate(statement, row),
                    "INSERT INTO ks_dungeon_templates (id,name,difficulty,ticket_price,min_players,max_players,"
                            + "time_limit_minutes,monster_level,description,schematic,require_property_key,"
                            + "reward_config,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    statement -> bindTemplateInsert(statement, row));
            return row.id();
        } catch (SQLException e) {
            eco.getLogger().warning("[副本系统] upsert 模板失败: " + e.getMessage());
        }
        return null;
    }

    private static void bindTemplateUpdate(PreparedStatement statement, TemplateWrite row) throws SQLException {
        statement.setString(1, row.name());
        statement.setString(2, row.difficulty());
        statement.setDouble(3, row.ticketPrice());
        statement.setInt(4, row.minPlayers());
        statement.setInt(5, row.maxPlayers());
        statement.setInt(6, row.timeLimitMinutes());
        statement.setInt(7, row.monsterLevel());
        statement.setString(8, row.description());
        statement.setString(9, row.schematic());
        statement.setInt(10, row.requirePropertyKey());
        statement.setString(11, row.rewardConfig());
        statement.setString(12, row.id());
    }

    private static void bindTemplateInsert(PreparedStatement statement, TemplateWrite row) throws SQLException {
        statement.setString(1, row.id());
        statement.setString(2, row.name());
        statement.setString(3, row.difficulty());
        statement.setDouble(4, row.ticketPrice());
        statement.setInt(5, row.minPlayers());
        statement.setInt(6, row.maxPlayers());
        statement.setInt(7, row.timeLimitMinutes());
        statement.setInt(8, row.monsterLevel());
        statement.setString(9, row.description());
        statement.setString(10, row.schematic());
        statement.setInt(11, row.requirePropertyKey());
        statement.setString(12, row.rewardConfig());
        statement.setLong(13, row.createdAt());
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

    public CompletableFuture<String> createInstanceAsync(String templateId, UUID ownerUuid, String ownerName) {
        return beginCreate(templateId, ownerUuid,
                List.of(new DungeonTicketSettlementStore.Participant(ownerUuid, ownerName)),
                List.of(ownerUuid));
    }

    /** The leader pays once; all members are frozen before any Vault withdrawal starts. */
    public CompletableFuture<String> createInstanceForPartyAsync(String templateId, UUID leaderUuid,
                                                                  String leaderName, List<UUID> members) {
        LinkedHashSet<UUID> uniqueMembers = new LinkedHashSet<>(members == null ? List.of() : members);
        uniqueMembers.add(leaderUuid);
        List<DungeonTicketSettlementStore.Participant> participants = new ArrayList<>();
        for (UUID member : uniqueMembers) {
            var offline = Bukkit.getOfflinePlayer(member);
            String name = member.equals(leaderUuid) && leaderName != null
                    ? leaderName : Objects.toString(offline.getName(), "");
            participants.add(new DungeonTicketSettlementStore.Participant(member, name));
        }
        return beginCreate(templateId, leaderUuid, participants, List.copyOf(uniqueMembers));
    }

    private CompletableFuture<String> beginCreate(String templateId, UUID payerUuid,
                                                   List<DungeonTicketSettlementStore.Participant> participants,
                                                   List<UUID> members) {
        CompletableFuture<String> result = new CompletableFuture<>();
        if (!Bukkit.isPrimaryThread()) {
            result.completeExceptionally(new IllegalStateException("Dungeon admission must start on the server thread"));
            return result;
        }
        if (templateId == null || templateId.isBlank() || members.isEmpty() || !reservePlayers(members)) {
            result.complete(null);
            return result;
        }
        String instanceId = "DI" + UUID.randomUUID().toString().substring(0, 8);
        executeTicketSql(() -> {
                Map<String, Object> template = getTemplate(templateId);
                if (template == null) {
                    finishCreateFailure(members, result, null);
                    return;
                }
                double price = ((Number) template.get("ticketPrice")).doubleValue();
                if (!Double.isFinite(price) || price < 0) {
                    finishCreateFailure(members, result,
                            new IllegalArgumentException("Invalid dungeon ticket price"));
                    return;
                }
                long now = System.currentTimeMillis() / 1000;
                int timeLimit = ((Number) template.get("timeLimitMinutes")).intValue();
                var settlement = new DungeonTicketSettlementStore.Settlement(
                        instanceId, templateId, payerUuid, price, settlementServerId(),
                        settlementInstanceId(), now);
                try (var connection = eco.ksCore().dataStore().getConnection()) {
                    if (connection == null || hasOpenParticipant(connection, members)) {
                        finishCreateFailure(members, result, null);
                        return;
                    }
                    DungeonTicketSettlementStore.insertChargeReady(connection, settlement);
                    if (!DungeonTicketSettlementStore.claimCharge(connection, instanceId, now)) {
                        finishCreateFailure(members, result, null);
                        return;
                    }
                } catch (SQLException failure) {
                    eco.getLogger().warning("[副本系统] 门票预留失败: " + failure.getMessage());
                    finishCreateFailure(members, result, failure);
                    return;
                }
                runOnServerThread(() -> chargeTicket(settlement, template, timeLimit, participants, members, result));
            }, () -> finishCreateFailure(members, result,
                    new RejectedExecutionException("Database queue rejected ticket reservation")));
        return result;
    }

    private void chargeTicket(DungeonTicketSettlementStore.Settlement settlement,
                              Map<String, Object> template, int timeLimit,
                              List<DungeonTicketSettlementStore.Participant> participants,
                              List<UUID> members, CompletableFuture<String> result) {
        boolean charged;
        try {
            if (settlement.amount() <= 0) {
                charged = true;
            } else {
                var payer = Bukkit.getOfflinePlayer(settlement.payerUuid());
                charged = eco.vaultHook().isAvailable() && eco.vaultHook().has(payer, settlement.amount())
                        && eco.vaultHook().withdraw(payer, settlement.amount());
            }
        } catch (Throwable uncertain) {
            markTicketReview(settlement.instanceId(), "Vault withdrawal outcome unknown: " + uncertain.getMessage());
            releasePlayers(members);
            result.completeExceptionally(uncertain);
            return;
        }
        if (!charged) {
            executeTicketSql(() -> {
                try (var connection = eco.ksCore().dataStore().getConnection()) {
                    if (connection != null) DungeonTicketSettlementStore.markChargeRejected(connection,
                            settlement.instanceId(), "Vault rejected ticket withdrawal", nowSeconds());
                }
                finishCreateFailure(members, result, null);
            }, () -> finishCreateFailure(members, result, null));
            return;
        }

        executeTicketSql(() -> {
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection == null) throw new SQLException("数据库连接不可用");
                DungeonTicketSettlementStore.commitCharge(connection, settlement,
                        settlement.createdAt() + Math.max(5, timeLimit) * 60L, participants);
            } catch (SQLException failure) {
                eco.getLogger().severe("[副本系统] 扣款后无法提交入场记录 " + settlement.instanceId()
                        + ": " + failure.getMessage());
                prepareRefundAfterCommitFailure(settlement, members, result, failure);
                return;
            }
            runOnServerThread(() -> {
                releasePlayers(members);
                for (UUID member : members) playerToInstance.put(member.toString(), settlement.instanceId());
                logEvent(settlement.instanceId(), "START", settlement.payerUuid().toString(),
                        "template=" + settlement.templateId() + " participants=" + participants.size());
                requestInstanceWorld(settlement.instanceId(), settlement.templateId(), template,
                        settlement.payerUuid(), settlement.amount(), members);
                result.complete(settlement.instanceId());
            });
        }, () -> prepareRefundAfterCommitFailure(settlement, members, result,
                new RejectedExecutionException("Database queue rejected charged ticket commit")));
    }

    private void prepareRefundAfterCommitFailure(DungeonTicketSettlementStore.Settlement settlement,
                                                 List<UUID> members, CompletableFuture<String> result,
                                                 Throwable failure) {
        executeTicketSql(() -> {
            boolean ready = false;
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection != null) ready = DungeonTicketSettlementStore.markChargeForRefund(connection,
                        settlement.instanceId(), messageOf(failure), nowSeconds());
            } catch (SQLException databaseFailure) {
                eco.getLogger().severe("[副本系统] 无法持久化门票退款状态 " + settlement.instanceId()
                        + ": " + databaseFailure.getMessage());
            }
            releasePlayers(members);
            if (ready) {
                refundTicket(settlement.instanceId(), settlement.payerUuid(), settlement.amount(), ignored ->
                        result.complete(null));
            } else {
                markTicketReview(settlement.instanceId(), "Charged ticket commit failed before refund could be claimed");
                runOnServerThread(() -> result.completeExceptionally(failure));
            }
        }, () -> {
            releasePlayers(members);
            markTicketReview(settlement.instanceId(), "Database queue rejected refund preparation");
            result.completeExceptionally(failure);
        });
    }

    private void activateAndTeleportAll(String instanceId, List<UUID> members,
                                        Map<String, Object> template, PreparedInstance prepared) {
        String preparedWorldId = prepared.instanceId();
        pendingAdmissions.put(instanceId, prepared);
        executeTicketSql(() -> {
            boolean activated = false;
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection != null) activated = DungeonTicketSettlementStore.activateAdmission(
                        connection, instanceId, nowSeconds());
            } catch (SQLException failure) {
                eco.getLogger().warning("[副本系统] 入场状态提交失败: " + failure.getMessage());
            }
            boolean admitted = activated;
            runOnServerThread(() -> {
                PreparedInstance ready = pendingAdmissions.remove(instanceId);
                if (!admitted || ready == null) {
                    instanceWorld.release(ready == null ? preparedWorldId : ready.instanceId(), ReleaseCause.ABANDONED);
                    handlePreparationFailure(instanceId, members, new SQLException("入场状态提交失败"));
                    return;
                }
                preparedWorlds.put(instanceId, ready);
                int defaultLevel = template.get("monsterLevel") == null ? 10
                        : ((Number) template.get("monsterLevel")).intValue();
                int mobs = spawnDungeonMarkers(instanceId, ready, defaultLevel,
                        configManager.snapshot().mapSpawnMobs);
                logEvent(instanceId, "MAP_PASTED", "", "schem=" + template.get("schematic") + " mobs=" + mobs);
                Location loc = ready.spawn().toLocation(ready.world());
                for (UUID member : members) {
                    Player player = Bukkit.getPlayer(member);
                    if (player != null && player.isOnline()) {
                        player.teleport(loc);
                        player.sendMessage("§6[副本] §a你已进入副本 " + instanceId);
                    }
                }
            });
        }, () -> {
            PreparedInstance ready = pendingAdmissions.remove(instanceId);
            instanceWorld.release(ready == null ? preparedWorldId : ready.instanceId(), ReleaseCause.ABANDONED);
            handlePreparationFailure(instanceId, members,
                    new RejectedExecutionException("数据库队列繁忙"));
        });
    }

    private void requestInstanceWorld(String instanceId, String templateId, Map<String, Object> template,
                                      UUID payerUuid, double ticketPrice, List<UUID> members) {
        String schematic = Objects.toString(template.get("schematic"), "").trim();
        if (schematic.isBlank()) {
            handlePreparationFailure(instanceId, payerUuid, ticketPrice, members,
                    new IllegalArgumentException("副本模板未配置 schematic"));
            return;
        }
        DungeonConfigManager.ConfigSnapshot cfg = configManager.snapshot();
        InstancePrepareRequest request = new InstancePrepareRequest(
                "ks-eco-dungeon",
                templateId,
                schematic,
                new InstanceGridSpec(cfg.gridWorldName, cfg.gridSpacing, cfg.maxGrids),
                cfg.mapBaseY,
                cfg.mapArenaRadius,
                Duration.ofSeconds(Math.max(30, cfg.mapPrepareTimeoutSeconds))
        );
        final InstancePreparation preparation;
        try {
            preparation = instanceWorld.prepare(request);
        } catch (Throwable failure) {
            handlePreparationFailure(instanceId, payerUuid, ticketPrice, members, failure);
            return;
        }
        String worldInstanceId = preparation.instanceId();
        pendingPreparations.put(instanceId, preparation);
        worldInstanceIds.put(instanceId, worldInstanceId);
        executeTicketSql(() -> {
            Throwable updateFailure = null;
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection == null) throw new SQLException("数据库连接不可用");
                try (var statement = connection.prepareStatement(
                        "UPDATE ks_dungeon_instances SET instance_world_id=? WHERE id=? AND status='WAITING'")) {
                    statement.setString(1, worldInstanceId);
                    statement.setString(2, instanceId);
                    if (statement.executeUpdate() != 1) throw new SQLException("副本已不再等待准备");
                }
            } catch (SQLException failure) {
                updateFailure = failure;
            }
            Throwable finalFailure = updateFailure;
            runOnServerThread(() -> {
                if (finalFailure != null) {
                    pendingPreparations.remove(instanceId);
                    instanceWorld.release(worldInstanceId, ReleaseCause.ABANDONED);
                    handlePreparationFailure(instanceId, members, finalFailure);
                    return;
                }
                InstancePreparation pending = pendingPreparations.remove(instanceId);
                if (pending == null) {
                    instanceWorld.release(worldInstanceId, ReleaseCause.ABANDONED);
                    handlePreparationFailure(instanceId, members,
                            new IllegalStateException("副本准备句柄已丢失"));
                    return;
                }
                pending.ready().whenComplete((prepared, failure) -> runOnServerThread(() -> {
                    if (failure != null) {
                        handlePreparationFailure(instanceId, members, unwrap(failure));
                    } else {
                        activateAndTeleportAll(instanceId, members, template, prepared);
                    }
                }));
            });
        }, () -> {
            pendingPreparations.remove(instanceId);
            instanceWorld.release(worldInstanceId, ReleaseCause.ABANDONED);
            handlePreparationFailure(instanceId, members,
                    new RejectedExecutionException("数据库队列繁忙"));
        });
    }

    private void handlePreparationFailure(String instanceId, UUID payerUuid, double ticketPrice,
                                          List<UUID> members, Throwable failure) {
        handlePreparationFailure(instanceId, members, failure);
    }

    private void handlePreparationFailure(String instanceId, List<UUID> members, Throwable failure) {
        playerToInstance.values().removeIf(instanceId::equals);
        pendingPreparations.remove(instanceId);
        pendingAdmissions.remove(instanceId);
        preparedWorlds.remove(instanceId);
        worldInstanceIds.remove(instanceId);
        String message = messageOf(failure);
        logEvent(instanceId, "PREPARE_FAILED", "", message);
        executeTicketSql(() -> {
            DungeonTicketSettlementStore.Settlement settlement = null;
            boolean refundReady = false;
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection != null) {
                    settlement = DungeonTicketSettlementStore.load(connection, instanceId);
                    refundReady = settlement != null && DungeonTicketSettlementStore.prepareRefund(
                            connection, instanceId, message, nowSeconds());
                }
            } catch (SQLException databaseFailure) {
                eco.getLogger().warning("[副本系统] 记录地图准备失败时数据库异常: "
                        + databaseFailure.getMessage());
            }
            DungeonTicketSettlementStore.Settlement refund = settlement;
            if (refundReady && refund != null) {
                refundTicket(instanceId, refund.payerUuid(), refund.amount(), refunded ->
                        notifyPreparationFailure(members, message, refunded));
            } else {
                runOnServerThread(() -> notifyPreparationFailure(members, message, false));
            }
        }, () -> notifyPreparationFailure(members, message, false));
    }

    private synchronized boolean reservePlayers(List<UUID> members) {
        for (UUID member : members) {
            String key = member.toString();
            if (playerToInstance.containsKey(key) || pendingPlayers.contains(key)) return false;
        }
        for (UUID member : members) pendingPlayers.add(member.toString());
        return true;
    }

    private void releasePlayers(List<UUID> members) {
        for (UUID member : members) pendingPlayers.remove(member.toString());
    }

    private static boolean hasOpenParticipant(Connection connection, List<UUID> members) throws SQLException {
        if (members.isEmpty()) return false;
        String placeholders = String.join(",", Collections.nCopies(members.size(), "?"));
        String sql = "SELECT 1 FROM ks_dungeon_participants p JOIN ks_dungeon_instances i "
                + "ON i.id=p.instance_id WHERE p.player_uuid IN (" + placeholders + ") "
                + "AND p.status<>'LEFT' AND i.status IN ('WAITING','ACTIVE') LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < members.size(); i++) statement.setString(i + 1, members.get(i).toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void refundTicket(String instanceId, UUID payerUuid, double amount,
                              java.util.function.Consumer<Boolean> completion) {
        executeTicketSql(() -> {
            boolean claimed;
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                claimed = connection != null && DungeonTicketSettlementStore.claimRefund(
                        connection, instanceId, nowSeconds());
            }
            if (!claimed) {
                runOnServerThread(() -> completion.accept(false));
                return;
            }
            runOnServerThread(() -> {
                boolean refunded;
                try {
                    refunded = amount <= 0 || (eco.vaultHook().isAvailable()
                            && eco.vaultHook().deposit(Bukkit.getOfflinePlayer(payerUuid), amount));
                } catch (Throwable uncertain) {
                    markTicketReview(instanceId, "Vault refund outcome unknown: " + uncertain.getMessage());
                    completion.accept(false);
                    return;
                }
                executeTicketSql(() -> {
                    boolean persisted;
                    try (var connection = eco.ksCore().dataStore().getConnection()) {
                        if (connection == null) {
                            persisted = false;
                        } else if (refunded) {
                            persisted = DungeonTicketSettlementStore.markRefunded(
                                    connection, instanceId, nowSeconds());
                        } else {
                            persisted = DungeonTicketSettlementStore.returnRefundReady(connection, instanceId,
                                    "Vault rejected ticket refund", nowSeconds());
                        }
                    }
                    boolean completed = refunded && persisted;
                    runOnServerThread(() -> completion.accept(completed));
                }, () -> {
                    markTicketReview(instanceId, "Database queue rejected refund finalization");
                    completion.accept(false);
                });
            });
        }, () -> completion.accept(false));
    }

    private void notifyPreparationFailure(List<UUID> members, String message, boolean refunded) {
        String settlement = refunded ? "门票已退还" : "门票退款待核对";
        for (UUID member : members) {
            Player player = Bukkit.getPlayer(member);
            if (player != null && player.isOnline()) {
                player.sendMessage("§6[副本] §c地图准备失败，" + settlement + ": " + message);
            }
        }
    }

    private void recoverTicketSettlements() {
        executeTicketSql(() -> {
            DungeonTicketSettlementStore.Recovery recovery;
            List<DungeonTicketSettlementStore.Settlement> refunds = new ArrayList<>();
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection == null) return;
                recovery = DungeonTicketSettlementStore.recoverInterrupted(
                        connection, settlementServerId(), nowSeconds());
                for (String instanceId : recovery.refundReady()) {
                    DungeonTicketSettlementStore.Settlement settlement =
                            DungeonTicketSettlementStore.load(connection, instanceId);
                    if (settlement != null) refunds.add(settlement);
                }
            }
            if (!recovery.reviewRequired().isEmpty()) {
                eco.getLogger().severe("[副本系统] 门票结算需人工核对: "
                        + String.join(",", recovery.reviewRequired()));
            }
            for (DungeonTicketSettlementStore.Settlement refund : refunds) {
                refundTicket(refund.instanceId(), refund.payerUuid(), refund.amount(), success -> {
                    if (!success) eco.getLogger().warning("[副本系统] 重启恢复退款尚未完成: " + refund.instanceId());
                });
            }
        }, () -> eco.getLogger().severe("[副本系统] 数据库队列拒绝门票结算恢复"));
    }

    private void recoverActiveInstances() {
        executeTicketSql(() -> {
            Map<String, MutableActiveRecovery> rows = new LinkedHashMap<>();
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection == null) return;
                try (var statement = connection.prepareStatement("""
                        SELECT i.id, COALESCE(NULLIF(i.instance_world_id,''), i.grid_id), i.template_id,
                               p.player_uuid, p.status
                        FROM ks_dungeon_instances i
                        JOIN ks_dungeon_participants p ON p.instance_id=i.id
                        WHERE i.status='ACTIVE' AND p.status<>'LEFT'
                        ORDER BY i.created_at,p.joined_at
                        """)) {
                    try (var result = statement.executeQuery()) {
                        while (result.next()) {
                            String instanceId = result.getString(1);
                            MutableActiveRecovery recovery = rows.get(instanceId);
                            if (recovery == null) {
                                recovery = new MutableActiveRecovery(instanceId,
                                        resultString(result, 2), resultString(result, 3));
                                rows.put(instanceId, recovery);
                            }
                            try {
                                recovery.participants.put(UUID.fromString(result.getString(4)), result.getString(5));
                            } catch (IllegalArgumentException ignored) {
                                eco.getLogger().warning("[副本系统] 忽略无效恢复玩家 UUID: " + result.getString(4));
                            }
                        }
                    }
                }
            }
            List<ActiveRecovery> recoveries = rows.values().stream().map(MutableActiveRecovery::freeze).toList();
            runOnServerThread(() -> recoveries.forEach(this::resumeActiveInstance));
        }, () -> eco.getLogger().severe("[副本系统] 数据库队列拒绝 ACTIVE 实例恢复"));
    }

    private void resumeActiveInstance(ActiveRecovery recovery) {
        if (recovery.worldInstanceId().isBlank() || "PENDING".equals(recovery.worldInstanceId())) {
            abandonUnrecoverable(recovery.instanceId(), "缺少持久化 InstanceWorld ID");
            return;
        }
        recovery.participants().keySet().forEach(uuid ->
                playerToInstance.put(uuid.toString(), recovery.instanceId()));
        worldInstanceIds.put(recovery.instanceId(), recovery.worldInstanceId());
        instanceWorld.resume(recovery.worldInstanceId()).whenComplete((prepared, failure) -> runOnServerThread(() -> {
            if (failure != null || prepared == null) {
                abandonUnrecoverable(recovery.instanceId(), messageOf(unwrap(failure)));
                return;
            }
            preparedWorlds.put(recovery.instanceId(), prepared);
            resetRecoveredEncounter(recovery.instanceId(), prepared);
            logEvent(recovery.instanceId(), "RECOVERED", "",
                    "world=" + recovery.worldInstanceId() + " participants=" + recovery.participants().size());
            for (var participant : recovery.participants().entrySet()) {
                Player online = Bukkit.getPlayer(participant.getKey());
                if (online == null || !online.isOnline()) continue;
                if ("ALIVE".equals(participant.getValue())) {
                    returnPlayerToInstance(recovery.instanceId(), online);
                } else if ("DEAD".equals(participant.getValue())) {
                    online.sendMessage("§6[副本] §c副本已恢复，你仍处于阵亡状态；使用 /dungeon revive 回场。");
                }
            }
            resumePendingRevives(recovery.instanceId());
        }));
    }

    private void resetRecoveredEncounter(String instanceId, PreparedInstance prepared) {
        Location center = new Location(prepared.world(),
                (prepared.bounds().minX() + prepared.bounds().maxX()) / 2.0 + 0.5,
                (prepared.bounds().minY() + prepared.bounds().maxY()) / 2.0 + 0.5,
                (prepared.bounds().minZ() + prepared.bounds().maxZ()) / 2.0 + 0.5);
        double halfX = (prepared.bounds().maxX() - prepared.bounds().minX()) / 2.0 + 2;
        double halfY = (prepared.bounds().maxY() - prepared.bounds().minY()) / 2.0 + 2;
        double halfZ = (prepared.bounds().maxZ() - prepared.bounds().minZ()) / 2.0 + 2;
        for (var entity : prepared.world().getNearbyEntities(center, halfX, halfY, halfZ)) {
            if (entity instanceof LivingEntity && !(entity instanceof Player)) entity.remove();
        }
        instanceBosses.remove(instanceId);
        Map<String, Object> template = getTemplate(prepared.templateKey());
        int level = template == null || template.get("monsterLevel") == null
                ? 10 : ((Number) template.get("monsterLevel")).intValue();
        int mobs = spawnDungeonMarkers(instanceId, prepared, level, configManager.snapshot().mapSpawnMobs);
        eco.getLogger().info("[副本系统] 已恢复副本 " + instanceId + " 并重建遭遇，刷怪 " + mobs + " 个。");
    }

    private void abandonUnrecoverable(String instanceId, String reason) {
        eco.getLogger().severe("[副本系统] ACTIVE 副本无法恢复，执行失败关闭: " + instanceId + " reason=" + reason);
        endInstance(instanceId, STATUS_ABANDONED, "RECOVERY_FAILED",
                "§6[副本] §c服务器恢复后无法重建本副本，实例已安全关闭。请联系管理员处理门票补偿。");
    }

    private static String resultString(ResultSet result, int column) throws SQLException {
        String value = result.getString(column);
        return value == null ? "" : value;
    }

    private void recoverPendingRevives() {
        executeTicketSql(() -> {
            List<DungeonReviveStore.Revival> activePending;
            List<DungeonReviveStore.Revival> refunds;
            List<DungeonReviveStore.Revival> inactive = new ArrayList<>();
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection == null) return;
                DungeonReviveStore.recoverUncertainClaims(connection, nowSeconds());
                DungeonReviveStore.cancelUnclaimedCharges(connection, nowSeconds());
                activePending = DungeonReviveStore.pending(connection).stream().filter(revival -> {
                    try {
                        DungeonReviveStore.Candidate candidate = DungeonReviveStore.candidate(
                                connection, revival.instanceId(), revival.playerUuid());
                        if (candidate != null && STATUS_ACTIVE.equals(candidate.instanceStatus())) return true;
                        inactive.add(revival);
                    } catch (SQLException failure) {
                        eco.getLogger().warning("[副本系统] 读取待恢复复活实例状态失败: " + failure.getMessage());
                    }
                    return false;
                }).toList();
                refunds = DungeonReviveStore.pendingRefunds(connection);
            }
            runOnServerThread(() -> {
                activePending.forEach(this::tryCompletePendingRevive);
                inactive.forEach(revival -> requestReviveRefund(
                        revival, DungeonReviveStore.PAID_PENDING, "instance no longer active"));
                refunds.forEach(revival -> continueReviveRefund(revival, "startup refund recovery"));
            });
        }, () -> eco.getLogger().severe("[副本系统] 数据库队列拒绝复活回场恢复"));
    }

    void resumePendingRevives(String instanceId) {
        executeTicketSql(() -> {
            List<DungeonReviveStore.Revival> pending;
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection == null) return;
                pending = DungeonReviveStore.pending(connection).stream()
                        .filter(revival -> instanceId.equals(revival.instanceId())).toList();
            }
            runOnServerThread(() -> pending.forEach(this::tryCompletePendingRevive));
        }, () -> eco.getLogger().severe("[副本系统] 数据库队列拒绝实例复活恢复: " + instanceId));
    }

    private void tryCompletePendingRevive(DungeonReviveStore.Revival revival) {
        Player player = Bukkit.getPlayer(revival.playerUuid());
        if (!revival.instanceId().equals(getPlayerActiveInstance(revival.playerUuid()))) return;
        if (player == null || !player.isOnline() || !preparedWorlds.containsKey(revival.instanceId())) return;
        if (returnPlayerToInstance(revival.instanceId(), player)) {
            submitReviveSql(connection -> DungeonReviveStore.completeReturn(connection, revival, nowSeconds()))
                    .whenComplete((completed, failure) -> runOnServerThread(() -> {
                        if (failure == null && Boolean.TRUE.equals(completed)) {
                            player.sendMessage("§a[副本] §f已恢复上次未完成的付费复活回场。");
                        } else {
                            eco.getLogger().severe("[副本系统] 已回场但复活完成状态未落库，保留 PAID_PENDING 供恢复: "
                                    + revival.id());
                        }
                    }));
        }
    }

    void requestReviveRefund(DungeonReviveStore.Revival revival, String expectedStatus, String reason) {
        submitReviveSql(connection -> {
            if (!DungeonReviveStore.prepareRefund(connection, revival, expectedStatus, reason, nowSeconds())) {
                return false;
            }
            return DungeonReviveStore.claimRefund(connection, revival, nowSeconds());
        }).whenComplete((claimed, failure) -> runOnServerThread(() -> {
            if (failure != null || !Boolean.TRUE.equals(claimed)) {
                eco.getLogger().severe("[副本系统] 无法持久化复活退款 claim，未执行 Vault 退款: "
                        + revival.id() + " reason=" + messageOf(failure));
                return;
            }
            finishClaimedReviveRefund(revival, reason);
        }));
    }

    private void continueReviveRefund(DungeonReviveStore.Revival revival, String reason) {
        submitReviveSql(connection -> DungeonReviveStore.claimRefund(connection, revival, nowSeconds()))
                .whenComplete((claimed, failure) -> runOnServerThread(() -> {
                    if (failure == null && Boolean.TRUE.equals(claimed)) {
                        finishClaimedReviveRefund(revival, reason);
                    }
                }));
    }

    private void finishClaimedReviveRefund(DungeonReviveStore.Revival revival, String reason) {
        boolean refunded;
        String detail = reason;
        try {
            refunded = revival.costPaid() <= 0 || (eco.vaultHook().isAvailable()
                    && eco.vaultHook().deposit(Bukkit.getOfflinePlayer(revival.playerUuid()), revival.costPaid()));
            if (!refunded) detail += "; Vault rejected refund";
        } catch (Throwable uncertain) {
            refunded = false;
            detail += "; refund outcome unknown: " + messageOf(uncertain);
        }
        boolean refundResult = refunded;
        String refundDetail = detail;
        submitReviveSql(connection -> DungeonReviveStore.finishRefund(
                connection, revival, refundResult, refundDetail, nowSeconds()))
                .whenComplete((finished, failure) -> runOnServerThread(() -> {
                    if (failure != null || !Boolean.TRUE.equals(finished)) {
                        eco.getLogger().severe("[副本系统] 复活退款结果未能落库，保留 REFUND_CLAIMED 人工复核: "
                                + revival.id());
                    }
                }));
    }

    private static final class MutableActiveRecovery {
        private final String instanceId;
        private final String worldInstanceId;
        private final String templateId;
        private final Map<UUID, String> participants = new LinkedHashMap<>();

        private MutableActiveRecovery(String instanceId, String worldInstanceId, String templateId) {
            this.instanceId = instanceId;
            this.worldInstanceId = worldInstanceId;
            this.templateId = templateId;
        }

        private ActiveRecovery freeze() {
            return new ActiveRecovery(instanceId, worldInstanceId, templateId, Map.copyOf(participants));
        }
    }

    private record ActiveRecovery(String instanceId, String worldInstanceId, String templateId,
                                  Map<UUID, String> participants) { }

    private void markTicketReview(String instanceId, String error) {
        executeTicketSql(() -> {
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection != null) DungeonTicketSettlementStore.markReviewRequired(
                        connection, instanceId, error, nowSeconds());
            }
        }, () -> eco.getLogger().severe("[副本系统] 无法写入门票人工核对状态: " + instanceId));
    }

    private void finishCreateFailure(List<UUID> members, CompletableFuture<String> result, Throwable failure) {
        runOnServerThread(() -> {
            releasePlayers(members);
            if (failure == null) result.complete(null);
            else result.completeExceptionally(failure);
        });
    }

    private void executeTicketSql(SqlTask task, Runnable rejectedCallback) {
        try {
            eco.asyncWorkPool().executeDatabase(() -> {
                try {
                    task.run();
                } catch (Throwable failure) {
                    eco.getLogger().warning("[副本系统] 门票数据库任务失败: " + messageOf(failure));
                    runOnServerThread(rejectedCallback);
                }
            });
        } catch (RejectedExecutionException rejected) {
            runOnServerThread(rejectedCallback);
        }
    }

    void runOnServerThread(Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(eco, action);
    }

    <T> CompletableFuture<T> submitReviveSql(ReviveSqlTask<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            eco.asyncWorkPool().executeDatabase(() -> {
                try (var connection = eco.ksCore().dataStore().getConnection()) {
                    if (connection == null) throw new SQLException("数据库连接不可用");
                    result.complete(task.run(connection));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    CompletableFuture<Integer> recordDeathAsync(String instanceId, UUID playerUuid) {
        return submitReviveSql(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE ks_dungeon_participants SET status='DEAD', died_at=? " +
                            "WHERE instance_id=? AND player_uuid=? AND status='ALIVE' " +
                            "AND EXISTS (SELECT 1 FROM ks_dungeon_instances i WHERE i.id=? AND i.status='ACTIVE')")) {
                statement.setLong(1, nowSeconds());
                statement.setString(2, instanceId);
                statement.setString(3, playerUuid.toString());
                statement.setString(4, instanceId);
                if (statement.executeUpdate() != 1) return -1;
            }
            DungeonReviveStore.Candidate candidate = DungeonReviveStore.candidate(connection, instanceId, playerUuid);
            return candidate == null ? -1 : candidate.reviveCount();
        });
    }

    @FunctionalInterface
    interface ReviveSqlTask<T> {
        T run(Connection connection) throws Exception;
    }

    private String settlementServerId() {
        return eco.ecoDatabase() == null ? "local" : eco.ecoDatabase().serverId();
    }

    private String settlementInstanceId() {
        return eco.ecoDatabase() == null ? localSettlementInstanceId : eco.ecoDatabase().instanceId();
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    private static String messageOf(Throwable failure) {
        return failure == null || failure.getMessage() == null ? "未知错误" : failure.getMessage();
    }

    @FunctionalInterface
    private interface SqlTask {
        void run() throws Exception;
    }

    private int spawnDungeonMarkers(String instanceId, PreparedInstance prepared,
                                    int defaultLevel, boolean spawnMobs) {
        int spawned = 0;
        for (InstanceMarker marker : prepared.markers()) {
            String mobSpec;
            boolean boss;
            if ("[mm]".equalsIgnoreCase(marker.tag())) {
                mobSpec = marker.argument(1);
                boss = "boss".equalsIgnoreCase(marker.argument(2));
            } else if ("[marker]".equalsIgnoreCase(marker.tag())
                    && "mm".equalsIgnoreCase(marker.argument(1))) {
                mobSpec = marker.argument(2);
                boss = "boss".equalsIgnoreCase(marker.argument(3));
            } else {
                continue;
            }
            if (!spawnMobs || mobSpec.isBlank()) continue;
            String mobName = mobSpec;
            int level = defaultLevel;
            int colon = mobSpec.lastIndexOf(':');
            if (colon > 0) {
                mobName = mobSpec.substring(0, colon).trim();
                try {
                    level = Integer.parseInt(mobSpec.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) { }
            }
            boolean completionTarget = boss || MythicSpawner.isCompletionController(mobName);
            UUID spawnedUuid = MythicSpawner.spawn(eco, mobName,
                    marker.point().toLocation(prepared.world()), level);
            if (spawnedUuid != null) {
                spawned++;
                if (completionTarget) {
                    instanceBosses.computeIfAbsent(instanceId, ignored -> ConcurrentHashMap.newKeySet())
                            .add(spawnedUuid);
                }
            } else if (completionTarget) {
                eco.getLogger().warning("[副本系统] 通关检测目标刷怪失败: " + mobName + " instance=" + instanceId);
            }
        }
        return spawned;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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
                    int remaining = 0;
                    try (var count = conn.prepareStatement(
                            "SELECT COUNT(*) FROM ks_dungeon_participants WHERE instance_id=? AND status<>'LEFT'")) {
                        count.setString(1, instanceId);
                        try (var rs = count.executeQuery()) {
                            if (rs.next()) remaining = rs.getInt(1);
                        }
                    }
                    if (remaining == 0) {
                        endInstance(instanceId, STATUS_ABANDONED, "PARTY_LEFT", null);
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
     * 清空副本内房产 → 请求 ks-InstanceWorld 幂等清场并释放。
     * @param finalStatus COMPLETED 或 ABANDONED
     */
    private boolean endInstance(String instanceId, String finalStatus, String eventType, String kickMessage) {
        final DungeonLifecycleStore.EndPlan plan;
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            Optional<DungeonLifecycleStore.EndPlan> result =
                    DungeonLifecycleStore.endInstance(conn, instanceId, finalStatus);
            if (result.isEmpty()) return false;
            plan = result.get();
        } catch (SQLException | RuntimeException e) {
            eco.getLogger().warning("[副本系统] 结束副本失败: " + e.getMessage());
            return false;
        }

        if (plan.transitioned()) {
            logEvent(instanceId, eventType, "", "");
        }
        if (STATUS_COMPLETED.equals(finalStatus) && plan.rewardsPending()) {
            if (rpgBridge == null) {
                eco.getLogger().severe("[副本系统] RPG 奖励桥不可用，保持 PENDING: " + instanceId);
            } else if (plan.rewardParticipants().isEmpty()) {
                eco.getLogger().severe("[副本系统] 通关奖励 roster 缺失，保持 PENDING 人工核对: " + instanceId);
            } else {
                scheduleCompletionRewards(instanceId, plan);
            }
        }

        playerToInstance.values().removeIf(instanceId::equals);
        instanceBosses.remove(instanceId);
        preparedWorlds.remove(instanceId);
        String mappedWorldInstanceId = worldInstanceIds.remove(instanceId);
        String releaseId = mappedWorldInstanceId == null || mappedWorldInstanceId.isBlank()
                ? plan.worldInstanceId() : mappedWorldInstanceId;
        ReleaseCause releaseCause = switch (eventType) {
            case "BOSS_KILLED" -> ReleaseCause.COMPLETED;
            case "TIMEOUT" -> ReleaseCause.TIMEOUT;
            case "FORCE_END" -> ReleaseCause.ABANDONED;
            case "PLUGIN_DISABLE" -> ReleaseCause.PLUGIN_DISABLE;
            default -> ReleaseCause.EXTERNAL;
        };
        Runnable evacuateAndRelease = () -> {
            World mainWorld = Bukkit.getWorld("world");
            for (String uuidStr : plan.remainingParticipants()) {
                try {
                    Player player = Bukkit.getPlayer(UUID.fromString(uuidStr));
                    if (player != null && player.isOnline()) {
                        if (mainWorld != null) player.teleport(mainWorld.getSpawnLocation());
                        if (kickMessage != null) player.sendMessage(kickMessage);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed legacy participant rows.
                }
            }
            if (releaseId == null || releaseId.isBlank() || "PENDING".equals(releaseId)) return;
            try {
                instanceWorld.release(releaseId, releaseCause).whenComplete((result, failure) -> {
                    if (failure != null) {
                        eco.getLogger().warning("[副本系统] 副本世界释放失败 " + instanceId + ": " + failure.getMessage());
                    } else {
                        eco.getLogger().info("[副本系统] 副本 " + instanceId
                                + " 已清理（房产 " + plan.plotsDeleted() + " 个）");
                    }
                });
            } catch (Throwable failure) {
                eco.getLogger().warning("[副本系统] 无法请求释放副本世界 " + instanceId + ": " + failure.getMessage());
            }
        };
        if (Bukkit.isPrimaryThread()) evacuateAndRelease.run();
        else Bukkit.getScheduler().runTask(eco, evacuateAndRelease);
        return true;
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
        if (instanceBosses.isEmpty()) return;
        for (var entry : new ArrayList<>(instanceBosses.entrySet())) {
            String instanceId = entry.getKey();
            Set<UUID> bosses = entry.getValue();
            if (allRegisteredBossesDead(bosses, MythicSpawner::isAlive)
                    && instanceBosses.remove(instanceId, bosses)) {
                eco.getLogger().info("[副本系统] 副本 " + instanceId + " 的全部 boss 已死亡，自动判定通关。");
                if (!completeInstance(instanceId)) instanceBosses.putIfAbsent(instanceId, bosses);
            }
        }
    }

    static boolean allRegisteredBossesDead(Set<UUID> bosses,
                                           java.util.function.Predicate<UUID> aliveCheck) {
        return bosses != null && !bosses.isEmpty() && bosses.stream().noneMatch(aliveCheck);
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
                        m.put("gridId", readInstanceWorldId(rs));
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
                        m.put("gridId", readInstanceWorldId(rs));
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

    private static String readInstanceWorldId(ResultSet rs) throws SQLException {
        String value = rs.getString("instance_world_id");
        return value == null || value.isBlank() ? rs.getString("grid_id") : value;
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

    private void scheduleCompletionRewards(String instanceId, DungeonLifecycleStore.EndPlan plan) {
        executeRewardSql(() -> prepareCompletionRewards(instanceId, plan),
                () -> eco.getLogger().severe("[副本系统] 奖励数据库队列拒绝任务，需人工核对: " + instanceId));
    }

    private void prepareCompletionRewards(String instanceId, DungeonLifecycleStore.EndPlan plan) throws Exception {
        List<DungeonRpgBridge.RewardGrant> definitions = rpgBridge.parseRewardGrants(plan.rewardConfig());
        if (definitions.isEmpty()) {
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection != null) DungeonLifecycleStore.markRewardsGranted(connection, instanceId);
            }
            return;
        }
        Map<String, DungeonRpgBridge.RewardGrant> definitionByKey = new LinkedHashMap<>();
        for (DungeonRpgBridge.RewardGrant definition : definitions) {
            if (definitionByKey.putIfAbsent(definition.rewardKey(), definition) != null) {
                throw new IllegalArgumentException("Duplicate reward key: " + definition.rewardKey());
            }
        }

        List<DungeonRewardGrantStore.Grant> grants;
        List<ClaimedReward> claimed = new ArrayList<>();
        try (var connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) throw new SQLException("数据库连接不可用");
            grants = DungeonRewardGrantStore.listForInstance(connection, instanceId);
            if (grants.isEmpty()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    long now = nowSeconds();
                    for (UUID playerUuid : plan.rewardParticipants()) {
                        for (DungeonRpgBridge.RewardGrant definition : definitions) {
                            DungeonRewardGrantStore.ensure(connection, instanceId, playerUuid,
                                    definition.rewardKey(), now);
                        }
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(previousAutoCommit);
                }
                grants = DungeonRewardGrantStore.listForInstance(connection, instanceId);
            }

            if (!rewardManifestMatches(plan.rewardParticipants(), definitionByKey.keySet(), grants)) {
                eco.getLogger().severe("[副本系统] 奖励清单与已持久化账目不一致，保持 PENDING 人工核对: "
                        + instanceId);
                return;
            }

            long now = nowSeconds();
            for (DungeonRewardGrantStore.Grant grant : grants) {
                if (!DungeonRewardGrantStore.STATUS_NONE.equals(grant.status())
                        && !DungeonRewardGrantStore.STATUS_RETRY_REQUIRED.equals(grant.status())) continue;
                if (grant.attemptCount() >= MAX_AUTO_REWARD_ATTEMPTS) continue;
                DungeonRpgBridge.RewardGrant definition = definitionByKey.get(grant.rewardKey());
                if (definition == null) continue;
                if (DungeonRewardGrantStore.claim(connection, instanceId, grant.playerUuid(),
                        grant.rewardKey(), now) == DungeonRewardGrantStore.ClaimResult.CLAIMED) {
                    claimed.add(new ClaimedReward(grant.playerUuid(), definition));
                }
            }

            if (DungeonRewardGrantStore.allGranted(connection, instanceId)) {
                if (DungeonLifecycleStore.markRewardsGranted(connection, instanceId)) {
                    notifyRewardCompletion(plan.rewardParticipants());
                }
                return;
            }
        }

        if (claimed.isEmpty()) {
            long pending = grants.stream().filter(grant ->
                    DungeonRewardGrantStore.STATUS_PENDING.equals(grant.status())).count();
            long exhausted = grants.stream().filter(grant ->
                    DungeonRewardGrantStore.STATUS_RETRY_REQUIRED.equals(grant.status())
                            && grant.attemptCount() >= MAX_AUTO_REWARD_ATTEMPTS).count();
            if (pending > 0 || exhausted > 0) {
                eco.getLogger().severe("[副本系统] 奖励保持待核对状态 instance=" + instanceId
                        + " pending=" + pending + " retryExhausted=" + exhausted);
            }
            return;
        }

        List<ClaimedReward> immutableClaims = List.copyOf(claimed);
        runOnServerThread(() -> deliverClaimedRewards(instanceId, plan, immutableClaims));
    }

    private void deliverClaimedRewards(String instanceId, DungeonLifecycleStore.EndPlan plan,
                                       List<ClaimedReward> claimed) {
        List<RewardDeliveryResult> results = new ArrayList<>(claimed.size());
        for (ClaimedReward reward : claimed) {
            DungeonRpgBridge.Delivery delivery;
            try {
                delivery = rpgBridge.deliverReward(instanceId, plan.templateId(),
                        reward.playerUuid(), reward.definition());
            } catch (Throwable uncertain) {
                delivery = new DungeonRpgBridge.Delivery(DungeonRpgBridge.DeliveryOutcome.REVIEW_REQUIRED,
                        reward.definition().rewardKey() + " outcome_unknown=" + messageOf(uncertain));
            }
            results.add(new RewardDeliveryResult(reward.playerUuid(), reward.definition(), delivery));
        }
        List<RewardDeliveryResult> immutableResults = List.copyOf(results);
        executeRewardSql(() -> persistRewardDeliveries(instanceId, plan, immutableResults),
                () -> eco.getLogger().severe("[副本系统] 奖励结果无法落库，PENDING 需人工核对: " + instanceId));
    }

    private void persistRewardDeliveries(String instanceId, DungeonLifecycleStore.EndPlan plan,
                                         List<RewardDeliveryResult> results) throws Exception {
        boolean retry = false;
        try (var connection = eco.ksCore().dataStore().getConnection()) {
            if (connection == null) throw new SQLException("数据库连接不可用");
            long now = nowSeconds();
            for (RewardDeliveryResult result : results) {
                switch (result.delivery().outcome()) {
                    case DELIVERED, SKIPPED -> {
                        if (DungeonRewardGrantStore.complete(connection, instanceId, result.playerUuid(),
                                result.definition().rewardKey(), now)) {
                            insertRewardLog(connection, instanceId, result.playerUuid(), result.delivery().detail(), now);
                        }
                    }
                    case RETRY_REQUIRED -> {
                        if (DungeonRewardGrantStore.fail(connection, instanceId, result.playerUuid(),
                                result.definition().rewardKey(), result.delivery().detail(), now)) retry = true;
                    }
                    case REVIEW_REQUIRED -> {
                        DungeonRewardGrantStore.markReviewRequired(connection, instanceId, result.playerUuid(),
                                result.definition().rewardKey(), result.delivery().detail(), now);
                        eco.getLogger().severe(
                                "[副本系统] 奖励外部结果未知，保持 PENDING 人工核对 instance=" + instanceId
                                        + " player=" + result.playerUuid() + " detail=" + result.delivery().detail());
                    }
                }
            }

            if (DungeonRewardGrantStore.allGranted(connection, instanceId)) {
                if (DungeonLifecycleStore.markRewardsGranted(connection, instanceId)) {
                    notifyRewardCompletion(plan.rewardParticipants());
                }
                return;
            }

            retry = retry && DungeonRewardGrantStore.listClaimable(connection, instanceId).stream()
                    .anyMatch(grant -> grant.attemptCount() < MAX_AUTO_REWARD_ATTEMPTS);
        }

        if (retry) {
            runOnServerThread(() -> Bukkit.getScheduler().runTaskLater(eco,
                    () -> scheduleCompletionRewards(instanceId, plan), 20L));
        }
    }

    private static boolean rewardManifestMatches(List<UUID> players, Set<String> rewardKeys,
                                                 List<DungeonRewardGrantStore.Grant> grants) {
        Set<String> expected = new HashSet<>();
        for (UUID player : players) {
            for (String rewardKey : rewardKeys) expected.add(player + "|" + rewardKey);
        }
        Set<String> actual = new HashSet<>();
        for (DungeonRewardGrantStore.Grant grant : grants) {
            actual.add(grant.playerUuid() + "|" + grant.rewardKey());
        }
        return expected.equals(actual);
    }

    private void notifyRewardCompletion(List<UUID> participants) {
        runOnServerThread(() -> {
            for (UUID participant : participants) {
                Player player = Bukkit.getPlayer(participant);
                if (player != null && player.isOnline()) player.sendMessage("§6[副本] §a通关奖励已发放。");
            }
        });
    }

    private static void insertRewardLog(Connection connection, String instanceId, UUID playerUuid,
                                        String detail, long now) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO ks_dungeon_log (instance_id, event_type, player_uuid, detail, created_at) " +
                        "VALUES (?,?,?,?,?)")) {
            statement.setString(1, instanceId);
            statement.setString(2, "REWARD");
            statement.setString(3, playerUuid.toString());
            statement.setString(4, detail == null ? "" : detail);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private void recoverRewardGrants() {
        executeRewardSql(() -> {
            List<String> instances = new ArrayList<>();
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection == null) return;
                try (var statement = connection.prepareStatement(
                        "SELECT id FROM ks_dungeon_instances WHERE status=? AND reward_status=?")) {
                    statement.setString(1, STATUS_COMPLETED);
                    statement.setString(2, DungeonLifecycleStore.REWARD_PENDING);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) instances.add(rows.getString(1));
                    }
                }
            }
            for (String instanceId : instances) {
                try (var connection = eco.ksCore().dataStore().getConnection()) {
                    if (connection == null) continue;
                    DungeonLifecycleStore.endInstance(connection, instanceId, STATUS_COMPLETED)
                            .filter(DungeonLifecycleStore.EndPlan::rewardsPending)
                            .ifPresent(plan -> scheduleCompletionRewards(instanceId, plan));
                }
            }
        }, () -> eco.getLogger().severe("[副本系统] 奖励恢复任务被数据库队列拒绝"));
    }

    private void executeRewardSql(SqlTask task, Runnable rejectedCallback) {
        try {
            eco.asyncWorkPool().executeDatabase(() -> {
                try {
                    task.run();
                } catch (Throwable failure) {
                    eco.getLogger().warning("[副本系统] 奖励数据库任务失败: " + messageOf(failure));
                    runOnServerThread(rejectedCallback);
                }
            });
        } catch (RejectedExecutionException rejected) {
            runOnServerThread(rejectedCallback);
        }
    }

    private record ClaimedReward(UUID playerUuid, DungeonRpgBridge.RewardGrant definition) { }

    private record RewardDeliveryResult(UUID playerUuid, DungeonRpgBridge.RewardGrant definition,
                                        DungeonRpgBridge.Delivery delivery) { }
    public boolean returnPlayerToInstance(String instanceId, Player player) {
        if (!Bukkit.isPrimaryThread() || player == null || !player.isOnline()) return false;
        PreparedInstance prepared = preparedWorlds.get(instanceId);
        if (prepared == null || prepared.world() == null || prepared.spawn() == null) return false;
        Location destination = prepared.spawn().toLocation(prepared.world());
        if (!player.teleport(destination)) return false;
        player.setGameMode(GameMode.SURVIVAL);
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) player.setHealth(Math.max(1.0, maxHealth.getValue()));
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setNoDamageTicks(40);
        return true;
    }

    public boolean isInstanceReady(String instanceId) {
        PreparedInstance prepared = preparedWorlds.get(instanceId);
        return prepared != null && prepared.world() != null && prepared.spawn() != null;
    }

    public void recoverPlayerOnJoin(Player player) {
        if (player == null) return;
        retryDeferredRewards(player.getUniqueId());
        String instanceId = getPlayerActiveInstance(player.getUniqueId());
        if (instanceId == null) return;
        submitReviveSql(connection -> DungeonReviveStore.candidate(
                connection, instanceId, player.getUniqueId())).whenComplete((candidate, failure) ->
                runOnServerThread(() -> {
                    if (failure != null || candidate == null
                            || !STATUS_ACTIVE.equals(candidate.instanceStatus()) || !player.isOnline()) return;
                    if ("ALIVE".equals(candidate.participantStatus())) {
                        returnPlayerToInstance(instanceId, player);
                    } else if ("DEAD".equals(candidate.participantStatus())) {
                        player.sendMessage("§6[副本] §c你仍处于阵亡状态；使用 /dungeon revive 付费回场。");
                    } else if ("REVIVE_PENDING".equals(candidate.participantStatus())) {
                        resumePendingRevives(instanceId);
                    }
                }));
    }

    private void retryDeferredRewards(UUID playerUuid) {
        executeRewardSql(() -> {
            List<String> instances;
            try (var connection = eco.ksCore().dataStore().getConnection()) {
                if (connection == null) return;
                instances = DungeonRewardGrantStore.rearmOfflineDeliveries(connection, playerUuid, nowSeconds());
            }
            for (String instanceId : instances) {
                try (var connection = eco.ksCore().dataStore().getConnection()) {
                    if (connection == null) continue;
                    DungeonLifecycleStore.endInstance(connection, instanceId, STATUS_COMPLETED)
                            .filter(DungeonLifecycleStore.EndPlan::rewardsPending)
                            .ifPresent(plan -> scheduleCompletionRewards(instanceId, plan));
                }
            }
        }, () -> eco.getLogger().warning("[副本系统] 玩家登录奖励补发任务被拒绝: " + playerUuid));
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

    public void shutdownInstances() {
        Set<String> activeInstances = new LinkedHashSet<>(preparedWorlds.keySet());
        try (var conn = eco.ksCore().dataStore().getConnection()) {
            if (conn != null) {
                try (var ps = conn.prepareStatement(
                        "SELECT id FROM ks_dungeon_instances WHERE status='ACTIVE'")) {
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) activeInstances.add(rs.getString(1));
                    }
                }
            }
        } catch (SQLException failure) {
            eco.getLogger().warning("[副本系统] 停用时读取活动副本失败: " + failure.getMessage());
        }
        for (String instanceId : activeInstances) {
            if (!endInstance(instanceId, STATUS_ABANDONED, "PLUGIN_DISABLE",
                    "§6[副本] §c副本模块已停用，你已返回主世界。")) {
                eco.getLogger().warning("[副本系统] 停用时未能终止活动副本 " + instanceId);
            }
        }
        releaseWorldInstances();
    }

    private void releaseWorldInstances() {
        for (String worldInstanceId : new HashSet<>(worldInstanceIds.values())) {
            try {
                instanceWorld.release(worldInstanceId, ReleaseCause.PLUGIN_DISABLE);
            } catch (Throwable failure) {
                eco.getLogger().warning("[副本系统] 停用时无法请求释放世界 "
                        + worldInstanceId + ": " + failure.getMessage());
            }
        }
        playerToInstance.clear();
        pendingPlayers.clear();
        pendingPreparations.clear();
        pendingAdmissions.clear();
        instanceBosses.clear();
        preparedWorlds.clear();
        worldInstanceIds.clear();
    }
}
