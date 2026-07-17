package org.kseco;

import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class LimitedSaleManager {

    public static final String SALE_TYPE_ITEM = "ITEM";
    public static final String SALE_TYPE_BLINDBOX = "BLINDBOX";
    public static final int BOX_UNITS = 27;

    private final KsEco plugin;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private final Set<String> activePurchases = ConcurrentHashMap.newKeySet();

    public LimitedSaleManager(KsEco plugin) {
        this.plugin = plugin;
        createTables();
    }

    public record SaleItem(
            String id,
            String name,
            String itemMaterial,
            String displayName,
            ItemStack item,
            double price,
            int totalStock,
            int sold,
            int perPlayerLimit,
            boolean enabled,
            long startsAt,
            long endsAt,
            long createdAt,
            long updatedAt,
            String saleType,
            String blindBoxPoolId,
            boolean boxEnabled,
            double boxPrice
    ) {
        public boolean blindBoxSale() {
            return SALE_TYPE_BLINDBOX.equalsIgnoreCase(saleType);
        }

        public boolean unlimitedStock() {
            return totalStock < 0;
        }

        public boolean unlimitedPerPlayer() {
            return perPlayerLimit < 0;
        }

        public int remainingStock() {
            if (unlimitedStock()) return Integer.MAX_VALUE;
            return Math.max(0, totalStock - sold);
        }

        public boolean soldOut() {
            return !unlimitedStock() && sold >= totalStock;
        }

        public boolean timeActive(long now) {
            return (startsAt <= 0 || now >= startsAt) && (endsAt <= 0 || now <= endsAt);
        }

        public boolean active(long now) {
            return enabled && timeActive(now) && !soldOut();
        }

        public boolean boxPurchaseAvailable() {
            return !blindBoxSale() && boxEnabled && boxPrice > 0 && Double.isFinite(boxPrice)
                    && item != null && !item.getType().isAir() && !ShulkerBoxParser.isShulkerBox(item)
                    && item.getAmount() > 0 && item.getAmount() <= item.getMaxStackSize();
        }
    }

    public record PurchaseResult(boolean success, String message, SaleItem sale, int quantity, boolean storedOverflow) {}

    /** 数据库线程使用的纯快照；物品字节只在服务器线程 materialize。 */
    private record SaleSnapshot(
            String id, String name, String itemMaterial, String displayName, byte[] itemData,
            double price, int totalStock, int sold, int perPlayerLimit, boolean enabled,
            long startsAt, long endsAt, long createdAt, long updatedAt,
            String saleType, String blindBoxPoolId, boolean boxEnabled, double boxPrice
    ) {
        private SaleSnapshot {
            itemData = itemData == null ? null : itemData.clone();
        }

        boolean blindBoxSale() { return SALE_TYPE_BLINDBOX.equalsIgnoreCase(saleType); }
        boolean unlimitedStock() { return totalStock < 0; }
        boolean unlimitedPerPlayer() { return perPlayerLimit < 0; }
        int remainingStock() { return unlimitedStock() ? Integer.MAX_VALUE : Math.max(0, totalStock - sold); }
    }

    private record PurchasePreparation(SaleSnapshot sale, int purchased, String error) {}
    private record PurchaseCommit(SaleSnapshot sale, double cost, String error) {}

    private void createTables() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement s = conn.createStatement()) {
                s.execute("""
                        CREATE TABLE IF NOT EXISTS ks_limited_sale_items (
                            id TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            item_material TEXT NOT NULL,
                            item_data BLOB,
                            display_name TEXT DEFAULT '',
                            price REAL NOT NULL DEFAULT 100,
                            total_stock INTEGER DEFAULT -1,
                            sold INTEGER DEFAULT 0,
                            per_player_limit INTEGER DEFAULT -1,
                            enabled INTEGER DEFAULT 1,
                            starts_at INTEGER DEFAULT 0,
                            ends_at INTEGER DEFAULT 0,
                            sale_type TEXT NOT NULL DEFAULT 'ITEM',
                            blindbox_pool_id TEXT DEFAULT '',
                            box_enabled INTEGER NOT NULL DEFAULT 0,
                            box_price REAL NOT NULL DEFAULT 0,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                s.execute("""
                        CREATE TABLE IF NOT EXISTS ks_limited_sale_players (
                            sale_id TEXT NOT NULL,
                            player_uuid TEXT NOT NULL,
                            purchased INTEGER NOT NULL DEFAULT 0,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY (sale_id, player_uuid)
                        )
                        """);
                s.execute("""
                        CREATE TABLE IF NOT EXISTS ks_limited_sale_log (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            sale_id TEXT NOT NULL,
                            player_uuid TEXT NOT NULL,
                            player_name TEXT DEFAULT '',
                            quantity INTEGER NOT NULL,
                            price_each REAL NOT NULL,
                            total_price REAL NOT NULL,
                            bought_at INTEGER NOT NULL
                        )
                        """);
                s.execute("CREATE INDEX IF NOT EXISTS idx_ks_limited_sale_log_sale ON ks_limited_sale_log(sale_id)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_ks_limited_sale_log_player ON ks_limited_sale_log(player_uuid)");
            }
            addColumnIfMissing(conn, "ALTER TABLE ks_limited_sale_items ADD COLUMN total_stock INTEGER DEFAULT -1");
            addColumnIfMissing(conn, "ALTER TABLE ks_limited_sale_items ADD COLUMN sold INTEGER DEFAULT 0");
            addColumnIfMissing(conn, "ALTER TABLE ks_limited_sale_items ADD COLUMN per_player_limit INTEGER DEFAULT -1");
            addColumnIfMissing(conn, "ALTER TABLE ks_limited_sale_items ADD COLUMN starts_at INTEGER DEFAULT 0");
            addColumnIfMissing(conn, "ALTER TABLE ks_limited_sale_items ADD COLUMN ends_at INTEGER DEFAULT 0");
            addColumnIfMissing(conn, "ALTER TABLE ks_limited_sale_items ADD COLUMN sale_type TEXT NOT NULL DEFAULT 'ITEM'");
            addColumnIfMissing(conn, "ALTER TABLE ks_limited_sale_items ADD COLUMN blindbox_pool_id TEXT DEFAULT ''");
            addColumnIfMissing(conn, "ALTER TABLE ks_limited_sale_items ADD COLUMN box_enabled INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "ALTER TABLE ks_limited_sale_items ADD COLUMN box_price REAL NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "ALTER TABLE ks_limited_sale_items ADD COLUMN updated_at INTEGER DEFAULT 0");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "限时直售表创建失败", e);
        }
    }

    private void addColumnIfMissing(Connection conn, String sql) {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        } catch (SQLException ignored) {
        }
    }

    public List<SaleItem> listSales(boolean includeUnavailable) {
        List<SaleItem> out = new ArrayList<>();
        long now = now();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return out;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM ks_limited_sale_items ORDER BY enabled DESC, created_at DESC")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SaleItem item = rowToSale(rs);
                        if (item == null) continue;
                        if (!includeUnavailable && !item.active(now)) continue;
                        out.add(item);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询限时直售失败: " + e.getMessage());
        }
        return out;
    }

    public SaleItem getSale(String id) {
        if (id == null || id.isBlank()) return null;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            return getSale(conn, id);
        } catch (SQLException e) {
            plugin.getLogger().warning("查询限时直售商品失败: " + e.getMessage());
            return null;
        }
    }

    private SaleItem getSale(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ks_limited_sale_items WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToSale(rs) : null;
            }
        }
    }

    private SaleSnapshot getSaleSnapshot(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ks_limited_sale_items WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToSnapshot(rs) : null;
            }
        }
    }

    private SaleItem rowToSale(ResultSet rs) throws SQLException {
        return materialize(rowToSnapshot(rs));
    }

    private SaleSnapshot rowToSnapshot(ResultSet rs) throws SQLException {
        String saleType = rs.getString("sale_type");
        if (saleType == null || saleType.isBlank()) saleType = SALE_TYPE_ITEM;
        String blindBoxPoolId = rs.getString("blindbox_pool_id");
        if (blindBoxPoolId == null) blindBoxPoolId = "";
        return new SaleSnapshot(
                rs.getString("id"), rs.getString("name"), rs.getString("item_material"),
                rs.getString("display_name"), rs.getBytes("item_data"), rs.getDouble("price"),
                rs.getInt("total_stock"), rs.getInt("sold"), rs.getInt("per_player_limit"),
                rs.getInt("enabled") != 0, rs.getLong("starts_at"), rs.getLong("ends_at"),
                rs.getLong("created_at"), rs.getLong("updated_at"), saleType, blindBoxPoolId,
                rs.getInt("box_enabled") != 0, rs.getDouble("box_price"));
    }

    private SaleItem materialize(SaleSnapshot snapshot) {
        if (snapshot == null) return null;
        ItemStack item = null;
        byte[] blob = snapshot.itemData();
        if (blob != null && blob.length > 0) {
            try {
                item = ItemStack.deserializeBytes(blob);
            } catch (Exception e) {
                plugin.getLogger().warning("限时直售物品反序列化失败: " + snapshot.id() + " / " + e.getMessage());
            }
        }
        if (item == null) {
            try {
                item = new ItemStack(Material.valueOf(snapshot.itemMaterial()), 1);
            } catch (Exception ignored) {
                item = new ItemStack(Material.BARRIER, 1);
            }
        }
        return new SaleItem(
                snapshot.id(), snapshot.name(), snapshot.itemMaterial(), snapshot.displayName(), item,
                snapshot.price(), snapshot.totalStock(), snapshot.sold(), snapshot.perPlayerLimit(),
                snapshot.enabled(), snapshot.startsAt(), snapshot.endsAt(), snapshot.createdAt(),
                snapshot.updatedAt(), snapshot.saleType(), snapshot.blindBoxPoolId(), snapshot.boxEnabled(),
                snapshot.boxPrice()
        );
    }

    public String addSale(ItemStack sourceItem, String name, double price, int totalStock, int perPlayerLimit,
                          long startsAt, long endsAt) {
        if (sourceItem == null || sourceItem.getType().isAir()) return null;
        ItemStack item = sourceItem.clone();
        if (item.getAmount() <= 0) item.setAmount(1);
        String id = "sale_" + UUID.randomUUID().toString().substring(0, 8);
        long now = now();
        String finalName = (name == null || name.isBlank()) ? itemName(item) : name.trim();
        byte[] blob = serialize(item);
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO ks_limited_sale_items
                    (id, name, item_material, item_data, display_name, price, total_stock, sold,
                     per_player_limit, enabled, starts_at, ends_at, sale_type, blindbox_pool_id, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                ps.setString(1, id);
                ps.setString(2, finalName);
                ps.setString(3, item.getType().name());
                ps.setBytes(4, blob);
                ps.setString(5, legacyDisplayName(item));
                ps.setDouble(6, Math.max(0.01, price));
                ps.setInt(7, totalStock < 0 ? -1 : Math.max(0, totalStock));
                ps.setInt(8, 0);
                ps.setInt(9, perPlayerLimit < 0 ? -1 : Math.max(0, perPlayerLimit));
                ps.setInt(10, 1);
                ps.setLong(11, Math.max(0, startsAt));
                ps.setLong(12, Math.max(0, endsAt));
                ps.setString(13, SALE_TYPE_ITEM);
                ps.setString(14, "");
                ps.setLong(15, now);
                ps.setLong(16, now);
                ps.executeUpdate();
                return id;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("新增限时直售失败: " + e.getMessage());
            return null;
        }
    }

    public String addBlindBoxSale(String poolId, String name, double price, int totalStock, int perPlayerLimit,
                                  long startsAt, long endsAt) {
        if (poolId == null || poolId.isBlank()) return null;
        Map<String, Object> pool = plugin.blindBoxManager().getPool(poolId.trim());
        if (pool == null) return null;
        String id = "bb_sale_" + UUID.randomUUID().toString().substring(0, 8);
        long now = now();
        String poolName = String.valueOf(pool.getOrDefault("name", poolId));
        String finalName = (name == null || name.isBlank()) ? poolName + " 限时盲盒" : name.trim();
        ItemStack icon = new ItemStack(Material.ENDER_CHEST, 1);
        double finalPrice = price > 0 ? price : (pool.get("price") instanceof Number n ? n.doubleValue() : 100.0);
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO ks_limited_sale_items
                    (id, name, item_material, item_data, display_name, price, total_stock, sold,
                     per_player_limit, enabled, starts_at, ends_at, sale_type, blindbox_pool_id, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                ps.setString(1, id);
                ps.setString(2, finalName);
                ps.setString(3, icon.getType().name());
                ps.setBytes(4, serialize(icon));
                ps.setString(5, finalName);
                ps.setDouble(6, Math.max(0.01, finalPrice));
                ps.setInt(7, totalStock < 0 ? -1 : Math.max(0, totalStock));
                ps.setInt(8, 0);
                ps.setInt(9, perPlayerLimit < 0 ? -1 : Math.max(0, perPlayerLimit));
                ps.setInt(10, 1);
                ps.setLong(11, Math.max(0, startsAt));
                ps.setLong(12, Math.max(0, endsAt));
                ps.setString(13, SALE_TYPE_BLINDBOX);
                ps.setString(14, poolId.trim());
                ps.setLong(15, now);
                ps.setLong(16, now);
                ps.executeUpdate();
                return id;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("新增限时盲盒失败: " + e.getMessage());
            return null;
        }
    }

    public boolean deleteSale(String id) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ks_limited_sale_players WHERE sale_id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            boolean deleted;
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ks_limited_sale_items WHERE id=?")) {
                ps.setString(1, id);
                deleted = ps.executeUpdate() > 0;
            }
            conn.commit();
            return deleted;
        } catch (SQLException e) {
            plugin.getLogger().warning("删除限时直售失败: " + e.getMessage());
            return false;
        }
    }

    public boolean setEnabled(String id, boolean enabled) {
        return updateInt(id, "enabled", enabled ? 1 : 0);
    }

    public boolean setPrice(String id, double price) {
        if (!Double.isFinite(price) || price <= 0 || price > 1_000_000_000_000d) return false;
        return updateDouble(id, "price", price);
    }

    public boolean setBoxEnabled(String id, boolean enabled) {
        SaleItem sale = getSale(id);
        if (sale == null || sale.blindBoxSale()) return false;
        ItemStack item = sale.item();
        if (enabled && (item == null || item.getType().isAir() || ShulkerBoxParser.isShulkerBox(item)
                || item.getAmount() <= 0 || item.getAmount() > item.getMaxStackSize())) return false;
        double generatedPrice = sale.boxPrice() > 0 && Double.isFinite(sale.boxPrice())
                ? sale.boxPrice() : sale.price() * 27.0;
        if (!Double.isFinite(generatedPrice) || generatedPrice <= 0 || generatedPrice > 1_000_000_000_000d) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_limited_sale_items SET box_enabled=?, box_price=?, updated_at=? WHERE id=? AND sale_type='ITEM'")) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setDouble(2, generatedPrice);
                ps.setLong(3, now());
                ps.setString(4, id);
                return ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("更新限时直售整盒开关失败: " + e.getMessage());
            return false;
        }
    }

    public boolean setBoxPrice(String id, double price) {
        if (!Double.isFinite(price) || price <= 0 || price > 1_000_000_000_000d) return false;
        SaleItem sale = getSale(id);
        return sale != null && !sale.blindBoxSale() && updateDouble(id, "box_price", price);
    }

    public boolean regenerateBoxPrice(String id) {
        SaleItem sale = getSale(id);
        return sale != null && !sale.blindBoxSale() && setBoxPrice(id, sale.price() * 27.0);
    }

    public boolean setTotalStock(String id, int stock) {
        return updateInt(id, "total_stock", stock < 0 ? -1 : Math.max(0, stock));
    }

    public boolean setPerPlayerLimit(String id, int limit) {
        return updateInt(id, "per_player_limit", limit < 0 ? -1 : Math.max(0, limit));
    }

    public boolean setStartsAt(String id, long startsAt) {
        return updateLong(id, "starts_at", Math.max(0, startsAt));
    }

    public boolean setEndsAt(String id, long endsAt) {
        return updateLong(id, "ends_at", Math.max(0, endsAt));
    }

    public boolean shiftEndsAt(String id, long seconds) {
        SaleItem sale = getSale(id);
        if (sale == null) return false;
        long base = sale.endsAt() > 0 ? sale.endsAt() : now();
        return setEndsAt(id, Math.max(0, base + seconds));
    }

    public boolean setName(String id, String name) {
        if (name == null || name.isBlank()) return false;
        String trimmed = name.trim();
        if (trimmed.length() > 48) trimmed = trimmed.substring(0, 48);
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_limited_sale_items SET name=?, updated_at=? WHERE id=?")) {
                ps.setString(1, trimmed);
                ps.setLong(2, now());
                ps.setString(3, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("修改限时直售名称失败: " + e.getMessage());
            return false;
        }
    }

    public boolean setBlindBoxPool(String id, String poolId) {
        if (poolId == null || poolId.isBlank()) return false;
        if (plugin.blindBoxManager().getPool(poolId.trim()) == null) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_limited_sale_items SET blindbox_pool_id=?, sale_type=?, updated_at=? WHERE id=?")) {
                ps.setString(1, poolId.trim());
                ps.setString(2, SALE_TYPE_BLINDBOX);
                ps.setLong(3, now());
                ps.setString(4, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("修改限时盲盒池失败: " + e.getMessage());
            return false;
        }
    }

    public boolean replaceItem(String id, ItemStack sourceItem) {
        if (sourceItem == null || sourceItem.getType().isAir()) return false;
        ItemStack item = sourceItem.clone();
        if (item.getAmount() <= 0) item.setAmount(1);
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE ks_limited_sale_items
                    SET item_material=?, item_data=?, display_name=?, updated_at=?
                    WHERE id=?
                    """)) {
                ps.setString(1, item.getType().name());
                ps.setBytes(2, serialize(item));
                ps.setString(3, legacyDisplayName(item));
                ps.setLong(4, now());
                ps.setString(5, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("替换限时直售物品失败: " + e.getMessage());
            return false;
        }
    }

    private boolean updateInt(String id, String column, int value) {
        return updateLong(id, column, value);
    }

    private boolean updateLong(String id, String column, long value) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_limited_sale_items SET " + column + "=?, updated_at=? WHERE id=?")) {
                ps.setLong(1, value);
                ps.setLong(2, now());
                ps.setString(3, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("更新限时直售字段失败: " + e.getMessage());
            return false;
        }
    }

    private boolean updateDouble(String id, String column, double value) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_limited_sale_items SET " + column + "=?, updated_at=? WHERE id=?")) {
                ps.setDouble(1, value);
                ps.setLong(2, now());
                ps.setString(3, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("更新限时直售字段失败: " + e.getMessage());
            return false;
        }
    }

    public int getPurchased(UUID uuid, String saleId) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            return getPurchased(conn, uuid, saleId);
        } catch (SQLException e) {
            return 0;
        }
    }

    private int getPurchased(Connection conn, UUID uuid, String saleId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT purchased FROM ks_limited_sale_players WHERE sale_id=? AND player_uuid=?")) {
            ps.setString(1, saleId);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void purchaseAsync(Player player, String saleId, int quantity, Consumer<PurchaseResult> callback) {
        purchaseInternalAsync(player, saleId, Math.max(1, Math.min(64, quantity)), false, callback);
    }

    public void purchaseBoxAsync(Player player, String saleId, Consumer<PurchaseResult> callback) {
        purchaseInternalAsync(player, saleId, BOX_UNITS, true, callback);
    }

    private void purchaseInternalAsync(Player player, String saleId, int qty, boolean boxed,
                                       Consumer<PurchaseResult> callback) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> purchaseInternalAsync(player, saleId, qty, boxed, callback));
            return;
        }
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        String operationKey = uuid + ":" + saleId;
        if (!activePurchases.add(operationKey)) {
            callback.accept(new PurchaseResult(false, "该商品正在结算，请稍候", null, boxed ? 1 : qty, false));
            return;
        }
        Consumer<PurchaseResult> completion = result -> {
            activePurchases.remove(operationKey);
            callback.accept(result);
        };
        plugin.asyncWorkPool().executeDatabase(() -> {
            PurchasePreparation preparation = preparePurchase(uuid, saleId, qty, boxed);
            Bukkit.getScheduler().runTask(plugin, () -> {
                SaleItem sale = materialize(preparation.sale());
                if (preparation.error() != null) {
                    completion.accept(new PurchaseResult(false, preparation.error(), sale, boxed ? 1 : qty, false));
                    return;
                }
                if (!player.isOnline()) {
                    completion.accept(new PurchaseResult(false, "玩家已离线，购买已取消", sale, boxed ? 1 : qty, false));
                    return;
                }
                if (boxed && (sale == null || !sale.boxPurchaseAvailable())) {
                    completion.accept(new PurchaseResult(false, "该商品未开放整盒购买", sale, 1, false));
                    return;
                }
                if (!sale.blindBoxSale() && (sale.item() == null || sale.item().getType().isAir())) {
                    completion.accept(new PurchaseResult(false, "商品物品无效", sale, boxed ? 1 : qty, false));
                    return;
                }
                double cost = boxed ? sale.boxPrice() : sale.price() * qty;
                if (!Double.isFinite(cost) || cost <= 0 || cost > 1_000_000_000_000d) {
                    completion.accept(new PurchaseResult(false, "商品价格无效", sale, boxed ? 1 : qty, false));
                    return;
                }
                if (!chargePlayer(player, cost)) {
                    completion.accept(new PurchaseResult(false, "余额不足，需要 " + formatMoney(cost), sale,
                            boxed ? 1 : qty, false));
                    return;
                }
                plugin.asyncWorkPool().executeDatabase(() -> {
                    PurchaseCommit commit = commitPurchase(uuid, playerName, saleId, qty, boxed, cost);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        SaleItem committedSale = materialize(commit.sale());
                        if (commit.error() != null) {
                            refundPlayer(player, cost);
                            completion.accept(new PurchaseResult(false, commit.error() + "，已退款", committedSale,
                                    boxed ? 1 : qty, false));
                            return;
                        }
                        settleCommittedPurchase(player, committedSale, qty, boxed, cost, completion);
                    });
                });
            });
        });
    }

    private PurchasePreparation preparePurchase(UUID uuid, String saleId, int qty, boolean boxed) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return new PurchasePreparation(null, 0, "数据库不可用");
            SaleSnapshot sale = getSaleSnapshot(conn, saleId);
            int purchased = sale == null ? 0 : getPurchased(conn, uuid, saleId);
            String error = validatePurchaseSnapshot(conn, sale, qty, purchased, boxed);
            return new PurchasePreparation(sale, purchased, error);
        } catch (SQLException e) {
            plugin.getLogger().warning("准备限时直售购买失败: " + e.getMessage());
            return new PurchasePreparation(null, 0, "读取商品失败");
        }
    }

    private PurchaseCommit commitPurchase(UUID uuid, String playerName, String saleId, int qty,
                                          boolean boxed, double expectedCost) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return new PurchaseCommit(null, expectedCost, "数据库不可用");
            conn.setAutoCommit(false);
            try {
                SaleSnapshot fresh = getSaleSnapshot(conn, saleId);
                int purchased = fresh == null ? 0 : getPurchased(conn, uuid, saleId);
                String error = validatePurchaseSnapshot(conn, fresh, qty, purchased, boxed);
                if (error != null) {
                    conn.rollback();
                    return new PurchaseCommit(fresh, expectedCost, error);
                }
                double freshCost = boxed ? fresh.boxPrice() : fresh.price() * qty;
                if (Double.compare(freshCost, expectedCost) != 0) {
                    conn.rollback();
                    return new PurchaseCommit(fresh, expectedCost, "商品价格已变化，请重新确认");
                }
                long timestamp = now();
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ks_limited_sale_items SET sold=sold+?, updated_at=? WHERE id=?")) {
                    ps.setInt(1, qty);
                    ps.setLong(2, timestamp);
                    ps.setString(3, saleId);
                    if (ps.executeUpdate() != 1) throw new SQLException("商品库存更新失败");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_limited_sale_players (sale_id, player_uuid, purchased, updated_at) VALUES (?,?,?,?) " +
                                "ON CONFLICT(sale_id, player_uuid) DO UPDATE SET purchased=purchased+excluded.purchased, updated_at=excluded.updated_at")) {
                    ps.setString(1, saleId);
                    ps.setString(2, uuid.toString());
                    ps.setInt(3, qty);
                    ps.setLong(4, timestamp);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO ks_limited_sale_log
                        (sale_id, player_uuid, player_name, quantity, price_each, total_price, bought_at)
                        VALUES (?,?,?,?,?,?,?)
                        """)) {
                    ps.setString(1, saleId);
                    ps.setString(2, uuid.toString());
                    ps.setString(3, playerName);
                    ps.setInt(4, qty);
                    ps.setDouble(5, expectedCost / qty);
                    ps.setDouble(6, expectedCost);
                    ps.setLong(7, timestamp);
                    ps.executeUpdate();
                }
                conn.commit();
                return new PurchaseCommit(fresh, expectedCost, null);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("限时直售购买事务失败: " + e.getMessage());
            return new PurchaseCommit(null, expectedCost, "购买失败");
        }
    }

    private String validatePurchaseSnapshot(Connection conn, SaleSnapshot sale, int quantity,
                                            int purchased, boolean boxed) throws SQLException {
        if (sale == null) return "商品不存在";
        long timestamp = now();
        if (!sale.enabled()) return "商品未启用";
        if (sale.startsAt() > 0 && timestamp < sale.startsAt()) return "商品还没开售";
        if (sale.endsAt() > 0 && timestamp > sale.endsAt()) return "商品已经结束";
        if (!sale.unlimitedStock() && sale.remainingStock() < quantity) return "库存不足，剩余 " + sale.remainingStock();
        if (!sale.unlimitedPerPlayer() && purchased + quantity > sale.perPlayerLimit()) {
            return "超过个人限购，已买 " + purchased + "/" + sale.perPlayerLimit();
        }
        if (boxed && (sale.blindBoxSale() || !sale.boxEnabled() || !Double.isFinite(sale.boxPrice())
                || sale.boxPrice() <= 0)) return "该商品未开放整盒购买";
        if (sale.blindBoxSale()) return blindBoxPoolError(conn, sale.blindBoxPoolId());
        if ((sale.itemData() == null || sale.itemData().length == 0)
                && (sale.itemMaterial() == null || sale.itemMaterial().isBlank())) return "商品物品无效";
        return null;
    }

    private String blindBoxPoolError(Connection conn, String poolId) throws SQLException {
        if (poolId == null || poolId.isBlank()) return "限时盲盒未绑定盲盒池";
        try (PreparedStatement ps = conn.prepareStatement("SELECT enabled FROM ks_bb_pools WHERE id=?")) {
            ps.setString(1, poolId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "绑定的盲盒池不存在";
                return rs.getInt(1) == 0 ? "绑定的盲盒池未启用" : null;
            }
        }
    }

    private void settleCommittedPurchase(Player player, SaleItem sale, int qty, boolean boxed, double cost,
                                         Consumer<PurchaseResult> callback) {
        if (sale == null) {
            rollbackAndRefund(player, null, qty, cost, "商品数据异常", callback);
            return;
        }
        if (sale.blindBoxSale()) {
            plugin.blindBoxManager().pullNoChargeBatchAsync(player.getUniqueId(), sale.blindBoxPoolId(), qty,
                    "LIMITED_BLINDBOX:" + sale.id(), results -> fulfillBlindBoxPurchaseAsync(
                            player, sale, qty, cost, results, callback));
            return;
        }
        if (boxed) {
            ItemStack box = createFilledBox(sale);
            if (box == null) {
                rollbackAndRefund(player, sale, qty, cost, "整盒生成失败", callback);
                return;
            }
            boolean stored = giveSingleItem(player, box, "LIMITED_SALE_BOX:" + sale.id());
            callback.accept(new PurchaseResult(true,
                    "整盒购买成功: " + sale.name() + " x" + BOX_UNITS + " 份，花费 " + formatMoney(cost)
                            + (stored ? "（背包已满，整盒已进暂存箱）" : ""), sale, 1, stored));
            return;
        }
        boolean stored = giveItems(player, sale.item(), qty, "LIMITED_SALE:" + sale.id());
        callback.accept(new PurchaseResult(true,
                "购买成功: " + sale.name() + " x" + qty + "，花费 " + formatMoney(cost)
                        + (stored ? "（背包满的部分已进暂存箱）" : ""), sale, qty, stored));
    }

    private void fulfillBlindBoxPurchaseAsync(Player player, SaleItem sale, int quantity, double paidCost,
                                              List<BlindBoxManager.PullResult> results,
                                              Consumer<PurchaseResult> callback) {
        if (results.size() != quantity || results.stream().anyMatch(result -> !result.success)) {
            String error = results.stream().filter(result -> !result.success).map(result -> result.error)
                    .filter(message -> message != null && !message.isBlank()).findFirst().orElse("未知错误");
            rollbackAndRefund(player, sale, quantity, paidCost, "限时盲盒抽取失败: " + error, callback);
            return;
        }
        boolean stored = results.stream().anyMatch(result -> result.storedToBox);
        List<String> summary = new ArrayList<>(results.size());
        for (BlindBoxManager.PullResult result : results) {
            String itemName = result.itemDisplayName != null && !result.itemDisplayName.isBlank()
                    ? result.itemDisplayName : result.itemMaterial;
            summary.add(result.rarity + ":" + itemName);
        }
        callback.accept(new PurchaseResult(true,
                "限时盲盒抽取成功: " + sale.name() + " x" + quantity + "，花费 " + formatMoney(paidCost)
                        + "。结果: " + String.join(", ", summary)
                        + (stored ? "（背包满的部分已进暂存箱）" : ""), sale, quantity, stored));
    }

    private void rollbackAndRefund(Player player, SaleItem sale, int amount, double cost, String error,
                                   Consumer<PurchaseResult> callback) {
        String saleId = sale == null ? null : sale.id();
        UUID uuid = player.getUniqueId();
        plugin.asyncWorkPool().executeDatabase(() -> {
            if (saleId != null) rollbackPurchaseCounters(saleId, uuid, amount);
            Bukkit.getScheduler().runTask(plugin, () -> {
                refundPlayer(player, cost);
                callback.accept(new PurchaseResult(false, error + "，已退款", sale, amount, false));
            });
        });
    }

    private void rollbackPurchaseCounters(String saleId, UUID uuid, int amount) {
        if (amount <= 0) return;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            long now = now();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_limited_sale_items SET sold=MAX(0, sold-?), updated_at=? WHERE id=?")) {
                ps.setInt(1, amount);
                ps.setLong(2, now);
                ps.setString(3, saleId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_limited_sale_players SET purchased=MAX(0, purchased-?), updated_at=? WHERE sale_id=? AND player_uuid=?")) {
                ps.setInt(1, amount);
                ps.setLong(2, now);
                ps.setString(3, saleId);
                ps.setString(4, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("回滚限时盲盒次数失败: " + e.getMessage());
        }
    }

    private String validatePurchase(SaleItem sale, int quantity, int purchased) {
        if (sale == null) return "商品不存在";
        long now = now();
        if (!sale.enabled()) return "商品未启用";
        if (sale.startsAt() > 0 && now < sale.startsAt()) return "商品还没开售";
        if (sale.endsAt() > 0 && now > sale.endsAt()) return "商品已经结束";
        if (sale.soldOut()) return "商品已售罄";
        if (!sale.unlimitedStock() && sale.remainingStock() < quantity) {
            return "库存不足，剩余 " + sale.remainingStock();
        }
        if (!sale.unlimitedPerPlayer() && purchased + quantity > sale.perPlayerLimit()) {
            return "超过个人限购，已买 " + purchased + "/" + sale.perPlayerLimit();
        }
        if (sale.blindBoxSale()) {
            if (sale.blindBoxPoolId() == null || sale.blindBoxPoolId().isBlank()) return "限时盲盒未绑定盲盒池";
            Map<String, Object> pool = plugin.blindBoxManager().getPool(sale.blindBoxPoolId());
            if (pool == null) return "绑定的盲盒池不存在";
            if (!(pool.get("enabled") instanceof Boolean enabled) || !enabled) return "绑定的盲盒池未启用";
            return null;
        }
        if (sale.item() == null || sale.item().getType().isAir()) return "商品物品无效";
        return null;
    }

    private boolean giveItems(Player player, ItemStack unit, int quantity, String source) {
        boolean stored = false;
        for (int i = 0; i < quantity; i++) {
            ItemStack copy = unit.clone();
            if (copy.getAmount() <= 0) copy.setAmount(1);
            if (!player.isOnline()) {
                plugin.storageManager().storeItem(player.getUniqueId(), copy, source);
                stored = true;
                continue;
            }
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(copy);
            if (!overflow.isEmpty()) {
                stored = true;
                for (ItemStack left : overflow.values()) {
                    plugin.storageManager().storeItem(player.getUniqueId(), left, source);
                }
            }
        }
        return stored;
    }

    private ItemStack createFilledBox(SaleItem sale) {
        if (sale == null || !sale.boxPurchaseAvailable()) return null;
        ItemStack box = new ItemStack(Material.SHULKER_BOX, 1);
        ItemMeta rawMeta = box.getItemMeta();
        if (!(rawMeta instanceof BlockStateMeta blockMeta)
                || !(blockMeta.getBlockState() instanceof ShulkerBox shulker)) return null;
        try {
            for (int slot = 0; slot < BOX_UNITS; slot++) {
                shulker.getInventory().setItem(slot, sale.item().clone());
            }
            blockMeta.setBlockState(shulker);
            box.setItemMeta(blockMeta);
            return box;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("生成限时直售整盒失败: " + e.getMessage());
            return null;
        }
    }

    private boolean giveSingleItem(Player player, ItemStack item, String source) {
        if (!player.isOnline()) {
            plugin.storageManager().storeItem(player.getUniqueId(), item, source);
            return true;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (overflow.isEmpty()) return false;
        for (ItemStack left : overflow.values()) {
            plugin.storageManager().storeItem(player.getUniqueId(), left, source);
        }
        return true;
    }

    private boolean chargePlayer(Player player, double amount) {
        if (amount <= 0) return true;
        if (plugin.vaultHook() != null && plugin.vaultHook().isAvailable()) {
            if (!plugin.vaultHook().has(player, amount)) return false;
            return plugin.vaultHook().withdraw(player, amount);
        }
        return plugin.builtinEconomy() != null
                && plugin.builtinEconomy().withdraw(player.getUniqueId(), player.getName(), amount);
    }

    private void refundPlayer(Player player, double amount) {
        if (amount <= 0) return;
        if (plugin.vaultHook() != null && plugin.vaultHook().isAvailable()) {
            plugin.vaultHook().deposit(player, amount);
        } else if (plugin.builtinEconomy() != null) {
            plugin.builtinEconomy().deposit(player.getUniqueId(), player.getName(), amount);
        }
    }

    public String itemName(ItemStack item) {
        String legacy = legacyDisplayName(item);
        if (legacy != null && !legacy.isBlank()) return ChatColor.stripColor(legacy);
        return MaterialNames.get(item.getType().name());
    }

    private String legacyDisplayName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return "";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return meta.getDisplayName();
        return "";
    }

    private byte[] serialize(ItemStack item) {
        try {
            return item.serializeAsBytes();
        } catch (Exception e) {
            plugin.getLogger().warning("限时直售物品序列化失败，使用材质兜底: " + e.getMessage());
            return null;
        }
    }

    public List<String> describeItem(SaleItem sale, UUID viewer) {
        if (sale.blindBoxSale()) return describeBlindBoxSale(sale, viewer);
        List<String> lines = new ArrayList<>();
        ItemStack item = sale.item();
        int purchased = viewer != null ? getPurchased(viewer, sale.id()) : 0;
        lines.add("商品ID: " + sale.id());
        lines.add("名称: " + sale.name());
        lines.add("材质: " + item.getType().name());
        lines.add("数量: " + item.getAmount());
        lines.add("价格: " + formatMoney(sale.price()));
        lines.add("整盒购买: " + (sale.boxEnabled() ? "启用" : "关闭")
                + " / 盒价 " + formatMoney(sale.boxPrice())
                + " / 每盒 " + BOX_UNITS + " 份（共 " + (item.getAmount() * BOX_UNITS) + " 个）");
        lines.add("总量: " + stockText(sale.totalStock()) + " / 已售 " + sale.sold());
        lines.add("个人限购: " + limitText(sale.perPlayerLimit()) + " / 你已买 " + purchased);
        lines.add("开售: " + formatTime(sale.startsAt()));
        lines.add("下次刷新: " + nextRefreshText(sale));
        lines.add("刷新倒计时: " + refreshCountdownText(sale));
        lines.add("状态: " + statusText(sale));
        lines.add("");

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            lines.add("ItemMeta: 无");
        } else {
            lines.add("---- ItemMeta ----");
            if (meta.hasDisplayName()) lines.add("显示名: " + meta.getDisplayName());
            if (meta.hasLore() && meta.getLore() != null) {
                List<String> lore = meta.getLore();
                lines.add("Lore行数: " + lore.size());
                for (int i = 0; i < lore.size(); i++) lines.add("Lore[" + i + "]: " + lore.get(i));
            }
            if (!meta.getEnchants().isEmpty()) {
                lines.add("附魔:");
                for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) {
                    lines.add("  " + e.getKey().getKey().asString() + " " + e.getValue());
                }
            }
            if (meta.hasCustomModelData()) lines.add("CustomModelData: " + meta.getCustomModelData());
            if (!meta.getItemFlags().isEmpty()) {
                List<String> flags = new ArrayList<>();
                for (ItemFlag flag : meta.getItemFlags()) flags.add(flag.name());
                lines.add("ItemFlags: " + String.join(", ", flags));
            }
            Set<NamespacedKey> keys = meta.getPersistentDataContainer().getKeys();
            if (!keys.isEmpty()) {
                lines.add("PersistentDataContainer:");
                for (NamespacedKey key : keys) lines.add("  " + key.asString());
            }
            if (meta instanceof Damageable damageable) {
                lines.add("Damage: " + damageable.getDamage());
            }
            lines.add("Unbreakable: " + meta.isUnbreakable());
        }

        lines.add("");
        lines.add("---- Serialized ItemStack ----");
        appendWrapped(lines, String.valueOf(new LinkedHashMap<>(item.serialize())), 44);
        return lines;
    }

    private List<String> describeBlindBoxSale(SaleItem sale, UUID viewer) {
        List<String> lines = new ArrayList<>();
        int purchased = viewer != null ? getPurchased(viewer, sale.id()) : 0;
        Map<String, Object> pool = plugin.blindBoxManager().getPool(sale.blindBoxPoolId());
        lines.add("类型: 限时盲盒");
        lines.add("直售ID: " + sale.id());
        lines.add("名称: " + sale.name());
        lines.add("绑定池: " + sale.blindBoxPoolId());
        lines.add("价格: " + formatMoney(sale.price()));
        lines.add("总可抽: " + stockText(sale.totalStock()) + " / 已抽 " + sale.sold());
        lines.add("每人最多: " + limitText(sale.perPlayerLimit()) + " / 你已抽 " + purchased);
        lines.add("开售: " + formatTime(sale.startsAt()));
        lines.add("下次刷新: " + nextRefreshText(sale));
        lines.add("刷新倒计时: " + refreshCountdownText(sale));
        lines.add("状态: " + statusText(sale));
        lines.add("");
        if (pool == null) {
            lines.add("绑定的盲盒池不存在");
            return lines;
        }
        lines.add("---- 盲盒池 ----");
        lines.add("池名称: " + pool.get("name"));
        lines.add("池类型: " + pool.get("poolType"));
        lines.add("池原价: " + formatMoney(pool.get("price") instanceof Number n ? n.doubleValue() : 0));
        lines.add("保底规则: " + BlindBoxManager.pityRulesText(pool.get("pityRules"), pool.get("pityMax")));
        lines.add("");
        lines.add("---- 奖品 ----");
        List<Map<String, Object>> loot = plugin.blindBoxManager().listLoot(sale.blindBoxPoolId());
        if (loot.isEmpty()) {
            lines.add("暂无奖品");
        } else {
            for (Map<String, Object> l : loot) {
                lines.add(l.get("rarity") + " | " + l.get("itemMaterial")
                        + " x" + l.get("quantity") + " | weight " + l.get("weight"));
            }
        }
        return lines;
    }

    private void appendWrapped(List<String> lines, String text, int width) {
        if (text == null || text.isEmpty()) {
            lines.add("");
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + width);
            lines.add(text.substring(start, end));
            start = end;
        }
    }

    public String formatMoney(double amount) {
        return plugin.vaultHook() != null ? plugin.vaultHook().format(amount) : String.format("%.2f", amount);
    }

    public String stockText(int stock) {
        return stock < 0 ? "不限" : String.valueOf(stock);
    }

    public String limitText(int limit) {
        return limit < 0 ? "不限" : String.valueOf(limit);
    }

    public String formatTime(long epochSeconds) {
        if (epochSeconds <= 0) return "不限";
        return timeFormat.format(new Date(epochSeconds * 1000L));
    }

    public String nextRefreshText(SaleItem sale) {
        if (sale == null || sale.endsAt() <= 0) return "暂无自动刷新";
        return formatTime(sale.endsAt());
    }

    public String refreshCountdownText(SaleItem sale) {
        if (sale == null || sale.endsAt() <= 0) return "暂无自动刷新";
        long remain = sale.endsAt() - now();
        if (remain <= 0) return "已到刷新时间";
        return durationText(remain);
    }

    private String durationText(long seconds) {
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (days > 0) return days + "天" + hours + "小时";
        if (hours > 0) return hours + "小时" + minutes + "分钟";
        return Math.max(1, minutes) + "分钟";
    }

    public String statusText(SaleItem sale) {
        long now = now();
        if (!sale.enabled()) return "已停用";
        if (sale.soldOut()) return "已售罄";
        if (sale.startsAt() > 0 && now < sale.startsAt()) return "未开始";
        if (sale.endsAt() > 0 && now > sale.endsAt()) return "已结束";
        return "进行中";
    }

    public long now() {
        return System.currentTimeMillis() / 1000L;
    }
}
