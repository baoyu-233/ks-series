package org.kseco;

import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * 物品暂存箱管理器。
 * 玩家购买/出售/退单的物品暂存在此，可随时提取。
 * 超过最大天数的物品自动清理。
 */
public final class StorageManager {

    private final KsEco plugin;
    private final Queue<PendingStore> pendingStores = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private static final int WRITE_BATCH_SIZE = 64;

    public StorageManager(KsEco plugin) {
        this.plugin = plugin;
        createTable();
    }

    private boolean enqueueStore(String id, UUID ownerUuid, byte[] data, String material,
                                 int quantity, String source, long storedAt) {
        pendingStores.add(new PendingStore(id, ownerUuid.toString(), data, material, quantity, source, storedAt));
        scheduleFlush();
        return true;
    }

    private void scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return;
        plugin.asyncWorkPool().execute(() -> {
            boolean drained = false;
            try {
                drained = flushQueuedStores();
            } finally {
                flushScheduled.set(false);
                if (drained && !pendingStores.isEmpty()) scheduleFlush();
            }
        });
    }

    private boolean flushQueuedStores() {
        while (true) {
            List<PendingStore> batch = takeBatch();
            if (batch.isEmpty()) return true;
            if (!writeBatch(batch)) {
                pendingStores.addAll(batch);
                return false;
            }
        }
    }

    private List<PendingStore> takeBatch() {
        List<PendingStore> batch = new ArrayList<>(WRITE_BATCH_SIZE);
        for (int i = 0; i < WRITE_BATCH_SIZE; i++) {
            PendingStore row = pendingStores.poll();
            if (row == null) break;
            batch.add(row);
        }
        return batch;
    }

    private boolean writeBatch(List<PendingStore> batch) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_eco_storage (id, owner_uuid, item_data, item_material, quantity, source, stored_at) VALUES (?,?,?,?,?,?,?)")) {
                for (PendingStore row : batch) {
                    ps.setString(1, row.id());
                    ps.setString(2, row.ownerUuid());
                    ps.setBytes(3, row.data());
                    ps.setString(4, row.material());
                    ps.setInt(5, row.quantity());
                    ps.setString(6, row.source());
                    ps.setLong(7, row.storedAt());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                plugin.getLogger().warning("Storage batch write failed: " + e.getMessage());
                return false;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Storage batch write failed: " + e.getMessage());
            return false;
        }
    }

    public void flushPending() {
        flushQueuedStores();
    }

    private void createTable() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_storage (
                        id TEXT PRIMARY KEY,
                        owner_uuid TEXT NOT NULL,
                        item_data BLOB NOT NULL,
                        item_material TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        stored_at INTEGER NOT NULL
                    )
                """);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "创建暂存箱表失败", e);
        }
    }

    /**
     * 存储物品到暂存箱。
     */
    public void storeItem(UUID ownerUuid, ItemStack item, String source) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            plugin.getLogger().warning("storeItem: 拒绝存储空/无效物品 (owner=" + ownerUuid + ")");
            return;
        }
        String id = UUID.randomUUID().toString();
        byte[] data = item.serializeAsBytes();
        long now = System.currentTimeMillis() / 1000;
        if (enqueueStore(id, ownerUuid, data, item.getType().name(), item.getAmount(), source, now)) return;

        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_eco_storage (id, owner_uuid, item_data, item_material, quantity, source, stored_at) " +
                    "VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, ownerUuid.toString());
                ps.setBytes(3, data);
                ps.setString(4, item.getType().name());
                ps.setInt(5, item.getAmount());
                ps.setString(6, source);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("存储物品失败: " + e.getMessage());
        }
    }

    /** 同步持久化关键结算物品；返回存储 ID，失败返回 null。 */
    public String storeItemNow(UUID ownerUuid, ItemStack item, String source) {
        if (ownerUuid == null || item == null || item.getType().isAir() || item.getAmount() <= 0) return null;
        String id = UUID.randomUUID().toString();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_eco_storage (id, owner_uuid, item_data, item_material, quantity, source, stored_at) " +
                            "VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, ownerUuid.toString());
                ps.setBytes(3, item.serializeAsBytes());
                ps.setString(4, item.getType().name());
                ps.setInt(5, item.getAmount());
                ps.setString(6, source == null ? "UNKNOWN" : source);
                ps.setLong(7, System.currentTimeMillis() / 1000);
                ps.executeUpdate();
                return id;
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().warning("同步存储物品失败: " + e.getMessage());
            return null;
        }
    }

    public boolean deleteStoredItem(UUID ownerUuid, String storageId) {
        if (ownerUuid == null || storageId == null || storageId.isBlank()) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM ks_eco_storage WHERE id=? AND owner_uuid=?")) {
            ps.setString(1, storageId);
            ps.setString(2, ownerUuid.toString());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("删除暂存物品失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取玩家暂存箱所有物品。
     */
    public List<StoredItem> getPlayerItems(UUID ownerUuid) {
        List<StoredItem> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, item_data, item_material, quantity, source, stored_at " +
                    "FROM ks_eco_storage WHERE owner_uuid=? ORDER BY stored_at ASC")) {
                ps.setString(1, ownerUuid.toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    byte[] data = rs.getBytes("item_data");
                    ItemStack item = ItemStack.deserializeBytes(data);
                    result.add(new StoredItem(
                            rs.getString("id"),
                            item,
                            rs.getString("item_material"),
                            rs.getInt("quantity"),
                            rs.getString("source"),
                            rs.getLong("stored_at")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询暂存箱失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 从暂存箱提取物品到玩家背包。
     */
    public boolean withdrawItem(UUID playerUuid, String storageId, org.bukkit.entity.Player player) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;

            // 查询并删除
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT item_data, quantity FROM ks_eco_storage WHERE id=? AND owner_uuid=?")) {
                ps.setString(1, storageId);
                ps.setString(2, playerUuid.toString());
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) return false;

                byte[] data = rs.getBytes("item_data");
                ItemStack item = ItemStack.deserializeBytes(data);
                item.setAmount(rs.getInt("quantity"));

                // 检查背包空间
                if (player.getInventory().firstEmpty() == -1) {
                    player.sendMessage("§c背包已满，请清理后再提取。");
                    return false;
                }

                // 删除记录
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM ks_eco_storage WHERE id=?")) {
                    del.setString(1, storageId);
                    del.executeUpdate();
                }

                // 给予物品
                player.getInventory().addItem(item);
            }
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("提取物品失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 清理过期物品。
     */
    public void cleanExpired() {
        int maxDays = plugin.ecoConfig().getMaxStorageDays();
        if (maxDays <= 0) return;

        long cutoff = System.currentTimeMillis() / 1000 - maxDays * 86400L;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM ks_eco_storage WHERE stored_at < ?")) {
                ps.setLong(1, cutoff);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    plugin.getLogger().info("清理了 " + deleted + " 件过期暂存物品。");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("清理过期暂存物品失败: " + e.getMessage());
        }
    }

    /**
     * 暂存物品总数。
     */
    public int totalStoredItems() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ks_eco_storage")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    // ---- 数据类 ----

    public record StoredItem(String id, ItemStack item, String material, int quantity, String source, long storedAt) {}

    private record PendingStore(String id, String ownerUuid, byte[] data, String material,
                                int quantity, String source, long storedAt) {}
}
