package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Durable, once-per-player compensation plans backed by the shared SQLite store. */
public final class CompensationManager {
    private final KsEco plugin;

    public record Plan(String id, String name, ItemStack item, int amount, boolean enabled,
                       long startsAt, long endsAt, long createdAt, int claimCount, boolean claimed) {
        public boolean active(long now) {
            return enabled && (startsAt <= 0 || now >= startsAt) && (endsAt <= 0 || now <= endsAt);
        }
        public int expiryDays() {
            if (endsAt <= startsAt) return 0;
            return Math.max(1, (int) Math.ceil((endsAt - startsAt) / 86400.0));
        }
    }

    public record OperationResult(boolean success, String message) {}

    private record RawPlan(String id, String name, String material, byte[] itemData, int amount, int maxStack,
                           boolean enabled, long startsAt, long endsAt, long createdAt,
                           int claimCount, boolean claimed) {}

    public CompensationManager(KsEco plugin) {
        this.plugin = plugin;
        createTables();
    }

    private void createTables() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_compensation_plans (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        item_material TEXT NOT NULL,
                        item_data BLOB NOT NULL,
                        item_amount INTEGER NOT NULL DEFAULT 1,
                        item_max_stack INTEGER NOT NULL DEFAULT 64,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        starts_at INTEGER NOT NULL DEFAULT 0,
                        ends_at INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_compensation_claims (
                        plan_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        claimed_at INTEGER NOT NULL,
                        storage_id TEXT NOT NULL,
                        PRIMARY KEY (plan_id, player_uuid)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ks_comp_claim_player ON ks_compensation_claims(player_uuid)");
        } catch (SQLException e) {
            plugin.getLogger().warning("创建补偿系统数据表失败: " + e.getMessage());
        }
    }

    public void listForPlayer(UUID playerUuid, Consumer<List<Plan>> callback) {
        runQuery(playerUuid, false, callback);
    }

    public void listAll(Consumer<List<Plan>> callback) {
        runQuery(null, true, callback);
    }

    private void runQuery(UUID playerUuid, boolean includeDisabled, Consumer<List<Plan>> callback) {
        plugin.asyncWorkPool().execute(() -> {
            List<RawPlan> rows = new ArrayList<>();
            String sql = """
                    SELECT p.*,
                           (SELECT COUNT(*) FROM ks_compensation_claims c WHERE c.plan_id=p.id) AS claim_count,
                           CASE WHEN ? IS NOT NULL AND EXISTS(
                               SELECT 1 FROM ks_compensation_claims mine WHERE mine.plan_id=p.id AND mine.player_uuid=?
                           ) THEN 1 ELSE 0 END AS claimed
                    FROM ks_compensation_plans p
                    """ + (includeDisabled ? "" : " WHERE p.enabled=1 AND (p.starts_at<=? OR p.starts_at=0) AND (p.ends_at>=? OR p.ends_at=0)")
                    + " ORDER BY p.enabled DESC, p.created_at DESC";
            try (Connection conn = plugin.ksCore().dataStore().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                String uuid = playerUuid == null ? null : playerUuid.toString();
                ps.setString(1, uuid);
                ps.setString(2, uuid);
                if (!includeDisabled) {
                    long now = now();
                    ps.setLong(3, now);
                    ps.setLong(4, now);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(readRaw(rs));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("查询补偿计划失败: " + e.getMessage());
            }
            sync(() -> callback.accept(decode(rows)));
        });
    }

    public void create(ItemStack source, int expiryDays, Consumer<OperationResult> callback) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Compensation item snapshot must run on the server thread");
        if (source == null || source.getType().isAir()) {
            callback.accept(new OperationResult(false, "请先手持要发放的特殊物品"));
            return;
        }
        ItemStack snapshot = source.clone();
        int amount = Math.max(1, Math.min(64, snapshot.getAmount()));
        int maxStack = Math.max(1, snapshot.getMaxStackSize());
        snapshot.setAmount(1);
        byte[] data = snapshot.serializeAsBytes();
        String material = snapshot.getType().name();
        String name = plugin.limitedSaleManager().itemName(snapshot) + " 补偿";
        String id = "comp_" + UUID.randomUUID().toString().substring(0, 8);
        long now = now();
        long endsAt = now + Math.max(1, expiryDays) * 86400L;
        execute("创建补偿计划", callback, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO ks_compensation_plans
                    (id,name,item_material,item_data,item_amount,item_max_stack,enabled,starts_at,ends_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,1,?,?,?,?)
                    """)) {
                ps.setString(1, id); ps.setString(2, name); ps.setString(3, material); ps.setBytes(4, data);
                ps.setInt(5, amount); ps.setInt(6, maxStack); ps.setLong(7, now); ps.setLong(8, endsAt); ps.setLong(9, now); ps.setLong(10, now);
                ps.executeUpdate();
            }
        });
    }

    public void updateName(String id, String name, Consumer<OperationResult> callback) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 80) { callback.accept(new OperationResult(false, "名称长度必须为 1-80")); return; }
        update("name", id, value, callback);
    }

    public void updateAmount(String id, int amount, Consumer<OperationResult> callback) {
        update("item_amount", id, Math.max(1, Math.min(64, amount)), callback);
    }

    public void updateExpiryDays(String id, int days, Consumer<OperationResult> callback) {
        long now = now();
        long endsAt = now + Math.max(1, Math.min(3650, days)) * 86400L;
        execute("更新补偿有效期", callback, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_compensation_plans SET starts_at=?, ends_at=?, updated_at=? WHERE id=?")) {
                ps.setLong(1, now); ps.setLong(2, endsAt); ps.setLong(3, now); ps.setString(4, id);
                if (ps.executeUpdate() != 1) throw new SQLException("补偿计划不存在");
            }
        });
    }

    public void updateEnabled(String id, boolean enabled, Consumer<OperationResult> callback) {
        update("enabled", id, enabled ? 1 : 0, callback);
    }

    public void replaceItem(String id, ItemStack source, Consumer<OperationResult> callback) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Compensation item snapshot must run on the server thread");
        if (source == null || source.getType().isAir()) { callback.accept(new OperationResult(false, "请先手持替换物品")); return; }
        ItemStack snapshot = source.clone();
        int amount = Math.max(1, Math.min(64, snapshot.getAmount()));
        int maxStack = Math.max(1, snapshot.getMaxStackSize());
        snapshot.setAmount(1);
        byte[] data = snapshot.serializeAsBytes();
        execute("替换补偿物品", callback, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_compensation_plans SET item_material=?,item_data=?,item_amount=?,item_max_stack=?,updated_at=? WHERE id=?")) {
                ps.setString(1, snapshot.getType().name()); ps.setBytes(2, data); ps.setInt(3, amount); ps.setInt(4, maxStack);
                ps.setLong(5, now()); ps.setString(6, id);
                if (ps.executeUpdate() != 1) throw new SQLException("补偿计划不存在");
            }
        });
    }

    public void delete(String id, Consumer<OperationResult> callback) {
        execute("删除补偿计划", callback, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ks_compensation_plans WHERE id=?")) {
                ps.setString(1, id);
                if (ps.executeUpdate() != 1) throw new SQLException("补偿计划不存在");
            }
        });
    }

    public void claim(UUID playerUuid, String playerName, String planId, Consumer<OperationResult> callback) {
        plugin.asyncWorkPool().execute(() -> {
            OperationResult result;
            try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
                conn.setAutoCommit(false);
                try {
                    RawPlan plan = findRaw(conn, planId, playerUuid);
                    long now = now();
                    if (plan == null) throw new ClaimRejected("补偿计划不存在");
                    if (!plan.enabled || (plan.startsAt > 0 && now < plan.startsAt) || (plan.endsAt > 0 && now > plan.endsAt)) {
                        throw new ClaimRejected("该补偿已关闭或过期");
                    }
                    String storageId = "comp-" + planId + "-" + playerUuid + "-0";
                    try (PreparedStatement claim = conn.prepareStatement(
                            "INSERT INTO ks_compensation_claims(plan_id,player_uuid,player_name,claimed_at,storage_id) VALUES(?,?,?,?,?)")) {
                        claim.setString(1, planId); claim.setString(2, playerUuid.toString());
                        claim.setString(3, playerName == null ? "" : playerName); claim.setLong(4, now); claim.setString(5, storageId);
                        claim.executeUpdate();
                    }
                    try (PreparedStatement storage = conn.prepareStatement("""
                            INSERT INTO ks_eco_storage(id,owner_uuid,item_data,item_material,quantity,source,stored_at)
                            VALUES(?,?,?,?,?,?,?)
                            """)) {
                        int remaining = plan.amount;
                        int index = 0;
                        while (remaining > 0) {
                            int stackAmount = Math.min(Math.max(1, plan.maxStack), remaining);
                            storage.setString(1, "comp-" + planId + "-" + playerUuid + "-" + index++);
                            storage.setString(2, playerUuid.toString()); storage.setBytes(3, plan.itemData);
                            storage.setString(4, plan.material); storage.setInt(5, stackAmount);
                            storage.setString(6, "COMPENSATION:" + planId); storage.setLong(7, now); storage.addBatch();
                            remaining -= stackAmount;
                        }
                        storage.executeBatch();
                    }
                    conn.commit();
                    result = new OperationResult(true, "补偿已发放至个人暂存箱");
                } catch (ClaimRejected e) {
                    conn.rollback(); result = new OperationResult(false, e.getMessage());
                } catch (SQLException e) {
                    conn.rollback();
                    result = new OperationResult(false, isUniqueViolation(e) ? "你已经领取过这份补偿" : "领取失败，请稍后重试");
                    if (!isUniqueViolation(e)) plugin.getLogger().warning("领取补偿失败: " + e.getMessage());
                } finally {
                    try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                }
            } catch (SQLException e) {
                result = new OperationResult(false, "数据库暂不可用");
            }
            OperationResult finalResult = result;
            sync(() -> callback.accept(finalResult));
        });
    }

    private void update(String column, String id, Object value, Consumer<OperationResult> callback) {
        if (!List.of("name", "item_amount", "enabled").contains(column)) throw new IllegalArgumentException("Unsupported column");
        execute("更新补偿计划", callback, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_compensation_plans SET " + column + "=?, updated_at=? WHERE id=?")) {
                ps.setObject(1, value); ps.setLong(2, now()); ps.setString(3, id);
                if (ps.executeUpdate() != 1) throw new SQLException("补偿计划不存在");
            }
        });
    }

    private void execute(String action, Consumer<OperationResult> callback, SqlAction sql) {
        plugin.asyncWorkPool().execute(() -> {
            OperationResult result;
            try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
                sql.run(conn);
                result = new OperationResult(true, action + "成功");
            } catch (SQLException e) {
                plugin.getLogger().warning(action + "失败: " + e.getMessage());
                result = new OperationResult(false, action + "失败");
            }
            OperationResult finalResult = result;
            sync(() -> callback.accept(finalResult));
        });
    }

    private RawPlan findRaw(Connection conn, String id, UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.*,
                       (SELECT COUNT(*) FROM ks_compensation_claims c WHERE c.plan_id=p.id) claim_count,
                       CASE WHEN EXISTS(SELECT 1 FROM ks_compensation_claims c WHERE c.plan_id=p.id AND c.player_uuid=?) THEN 1 ELSE 0 END claimed
                FROM ks_compensation_plans p WHERE p.id=?
                """)) {
            ps.setString(1, playerUuid.toString()); ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? readRaw(rs) : null; }
        }
    }

    private RawPlan readRaw(ResultSet rs) throws SQLException {
        return new RawPlan(rs.getString("id"), rs.getString("name"), rs.getString("item_material"),
                rs.getBytes("item_data"), rs.getInt("item_amount"), rs.getInt("item_max_stack"), rs.getInt("enabled") != 0,
                rs.getLong("starts_at"), rs.getLong("ends_at"), rs.getLong("created_at"),
                rs.getInt("claim_count"), rs.getInt("claimed") != 0);
    }

    private List<Plan> decode(List<RawPlan> rows) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Compensation items must decode on the server thread");
        List<Plan> result = new ArrayList<>(rows.size());
        for (RawPlan row : rows) {
            ItemStack item;
            try { item = ItemStack.deserializeBytes(row.itemData); }
            catch (Exception e) {
                Material material = Material.matchMaterial(row.material);
                item = new ItemStack(material == null ? Material.BARRIER : material);
            }
            item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), row.amount)));
            result.add(new Plan(row.id, row.name, item, row.amount, row.enabled, row.startsAt, row.endsAt,
                    row.createdAt, row.claimCount, row.claimed));
        }
        return result;
    }

    private boolean isUniqueViolation(SQLException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("unique constraint");
    }

    public long now() { return System.currentTimeMillis() / 1000L; }

    private void sync(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    @FunctionalInterface private interface SqlAction { void run(Connection conn) throws SQLException; }
    private static final class ClaimRejected extends Exception { private ClaimRejected(String message) { super(message); } }
}
