package org.kseco;

import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.kseco.currency.CurrencyPersistenceCompatibility;
import org.kseco.database.BusinessSchemaDialect;
import org.kseco.database.DatabaseDialect;
import org.kseco.database.PortableSqlMutation;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class LimitedSaleManager {

    public static final String SALE_TYPE_ITEM = "ITEM";
    public static final String SALE_TYPE_BLINDBOX = "BLINDBOX";
    public static final String DEFAULT_CURRENCY_ID = ListingManager.DEFAULT_CURRENCY_ID;
    public static final int BOX_UNITS = 27;

    private final KsEco plugin;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private final Set<String> activePurchases = ConcurrentHashMap.newKeySet();

    public LimitedSaleManager(KsEco plugin) {
        this.plugin = plugin;
        createTables();
        plugin.scheduler().runGlobalLater(this::recoverSettlements, 40L);
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
            double boxPrice,
            String currencyId
    ) {
        public SaleItem(String id, String name, String itemMaterial, String displayName, ItemStack item,
                        double price, int totalStock, int sold, int perPlayerLimit, boolean enabled,
                        long startsAt, long endsAt, long createdAt, long updatedAt, String saleType,
                        String blindBoxPoolId, boolean boxEnabled, double boxPrice) {
            this(id, name, itemMaterial, displayName, item, price, totalStock, sold, perPlayerLimit,
                    enabled, startsAt, endsAt, createdAt, updatedAt, saleType, blindBoxPoolId,
                    boxEnabled, boxPrice, DEFAULT_CURRENCY_ID);
        }

        public SaleItem {
            currencyId = normalizeCurrencyId(currencyId);
        }

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

        public boolean cashCurrency() {
            return DEFAULT_CURRENCY_ID.equals(currencyId);
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
            String saleType, String blindBoxPoolId, boolean boxEnabled, double boxPrice,
            String currencyId
    ) {
        private SaleSnapshot {
            itemData = itemData == null ? null : itemData.clone();
            currencyId = normalizePersistedCurrencyId(currencyId);
        }

        boolean blindBoxSale() { return SALE_TYPE_BLINDBOX.equalsIgnoreCase(saleType); }
        boolean unlimitedStock() { return totalStock < 0; }
        boolean unlimitedPerPlayer() { return perPlayerLimit < 0; }
        int remainingStock() { return unlimitedStock() ? Integer.MAX_VALUE : Math.max(0, totalStock - sold); }
    }

    enum CashBackend { VAULT, BUILTIN }

    private record PurchasePreparation(String settlementId, SaleSnapshot sale, int purchased, String currencyId,
                                       double amount, CashBackend backend, String error) {}
    private record PurchaseCommit(String settlementId, SaleSnapshot sale, String currencyId, double amount,
                                  CashBackend backend, String error) {}

    static boolean executeDatabaseOrReject(AsyncWorkPool pool, Runnable task, Runnable onRejected) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(onRejected, "onRejected");
        try {
            pool.executeDatabase(task);
            return true;
        } catch (RejectedExecutionException exception) {
            onRejected.run();
            return false;
        }
    }

    static boolean executeDatabaseOrFallback(AsyncWorkPool pool, Runnable task,
                                             Consumer<Runnable> fallbackExecutor, Runnable onRejected) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(fallbackExecutor, "fallbackExecutor");
        Objects.requireNonNull(onRejected, "onRejected");
        try {
            pool.executeDatabase(task);
            return true;
        } catch (RejectedExecutionException primaryRejection) {
            try {
                fallbackExecutor.accept(task);
                return true;
            } catch (RuntimeException fallbackRejection) {
                primaryRejection.addSuppressed(fallbackRejection);
                onRejected.run();
                return false;
            }
        }
    }

    static boolean dispatchOrReject(Consumer<Runnable> dispatcher, Runnable task, Runnable onRejected) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(onRejected, "onRejected");
        try {
            dispatcher.accept(task);
            return true;
        } catch (RuntimeException rejection) {
            onRejected.run();
            return false;
        }
    }

    static <T> Consumer<T> completionClearingPurchase(
            Set<String> activePurchases,
            String operationKey,
            Consumer<T> callback
    ) {
        Objects.requireNonNull(activePurchases, "activePurchases");
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(callback, "callback");
        return result -> {
            activePurchases.remove(operationKey);
            callback.accept(result);
        };
    }

    private void createTables() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            initializeSchema(conn);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "限时直售表创建失败", e);
        }
    }

    static void initializeSchema(Connection connection) throws SQLException {
        initializeSchema(connection, BusinessSchemaDialect.detect(connection));
    }

    static void initializeSchema(Connection conn, DatabaseDialect dialect) throws SQLException {
        String number = BusinessSchemaDialect.floatingPointType(dialect);
        String binary = BusinessSchemaDialect.binaryType(dialect);
        String identity = BusinessSchemaDialect.identityPrimaryKey(dialect);
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS ks_limited_sale_items (id VARCHAR(128) PRIMARY KEY,name VARCHAR(128) NOT NULL,item_material VARCHAR(128) NOT NULL,item_data " + binary + ",display_name VARCHAR(256) DEFAULT '',price " + number + " NOT NULL DEFAULT 100,currency_id VARCHAR(64) NOT NULL DEFAULT 'CASH',total_stock INTEGER DEFAULT -1,sold INTEGER DEFAULT 0,per_player_limit INTEGER DEFAULT -1,enabled INTEGER DEFAULT 1,starts_at BIGINT DEFAULT 0,ends_at BIGINT DEFAULT 0,sale_type VARCHAR(32) NOT NULL DEFAULT 'ITEM',blindbox_pool_id VARCHAR(128) DEFAULT '',box_enabled INTEGER NOT NULL DEFAULT 0,box_price " + number + " NOT NULL DEFAULT 0,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS ks_limited_sale_players (sale_id VARCHAR(128) NOT NULL,player_uuid VARCHAR(36) NOT NULL,purchased INTEGER NOT NULL DEFAULT 0,updated_at BIGINT NOT NULL,PRIMARY KEY(sale_id,player_uuid))");
                s.execute("CREATE TABLE IF NOT EXISTS ks_limited_sale_log (id " + identity + ",sale_id VARCHAR(128) NOT NULL,player_uuid VARCHAR(36) NOT NULL,player_name VARCHAR(128) DEFAULT '',quantity INTEGER NOT NULL,price_each " + number + " NOT NULL,total_price " + number + " NOT NULL,currency_id VARCHAR(64) NOT NULL DEFAULT 'CASH',bought_at BIGINT NOT NULL)");
            }
            BusinessSchemaDialect.createIndexIfMissing(conn, "idx_ks_limited_sale_log_sale", "ks_limited_sale_log", "sale_id");
            BusinessSchemaDialect.createIndexIfMissing(conn, "idx_ks_limited_sale_log_player", "ks_limited_sale_log", "player_uuid");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "total_stock", "INTEGER DEFAULT -1");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "sold", "INTEGER DEFAULT 0");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "per_player_limit", "INTEGER DEFAULT -1");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "starts_at", "BIGINT DEFAULT 0");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "ends_at", "BIGINT DEFAULT 0");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "sale_type", "VARCHAR(32) NOT NULL DEFAULT 'ITEM'");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "blindbox_pool_id", "VARCHAR(128) DEFAULT ''");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "box_enabled", "INTEGER NOT NULL DEFAULT 0");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "box_price", number + " NOT NULL DEFAULT 0");
            LimitedSaleSettlementStore.initialize(conn);
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "updated_at", "BIGINT DEFAULT 0");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_items", "currency_id", "VARCHAR(64) NOT NULL DEFAULT 'CASH'");
            BusinessSchemaDialect.addColumnIfMissing(conn, "ks_limited_sale_log", "currency_id", "VARCHAR(64) NOT NULL DEFAULT 'CASH'");
            CurrencyPersistenceCompatibility.canonicalizeLegacyCashRows(conn, "ks_limited_sale_items");
            CurrencyPersistenceCompatibility.canonicalizeLegacyCashRows(conn, "ks_limited_sale_log");
    }

    static void incrementPurchased(Connection connection, String saleId, String playerUuid,
                                   int quantity, long updatedAt) throws SQLException {
        PortableSqlMutation.upsert(connection,
                "UPDATE ks_limited_sale_players SET purchased=purchased+?,updated_at=? WHERE sale_id=? AND player_uuid=?",
                ps -> { ps.setInt(1, quantity); ps.setLong(2, updatedAt); ps.setString(3, saleId); ps.setString(4, playerUuid); },
                "INSERT INTO ks_limited_sale_players (purchased,updated_at,sale_id,player_uuid) VALUES (?,?,?,?)",
                ps -> { ps.setInt(1, quantity); ps.setLong(2, updatedAt); ps.setString(3, saleId); ps.setString(4, playerUuid); });
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
                rs.getInt("box_enabled") != 0, rs.getDouble("box_price"),
                rs.getString("currency_id"));
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
                snapshot.boxPrice(), snapshot.currencyId()
        );
    }

    public String addSale(ItemStack sourceItem, String name, double price, int totalStock, int perPlayerLimit,
                           long startsAt, long endsAt) {
        return addSale(sourceItem, name, price, totalStock, perPlayerLimit, startsAt, endsAt,
                DEFAULT_CURRENCY_ID);
    }

    public String addSale(ItemStack sourceItem, String name, double price, int totalStock, int perPlayerLimit,
                          long startsAt, long endsAt, String currencyId) {
        if (sourceItem == null || sourceItem.getType().isAir()) return null;
        if (!isValidPrice(price)) return null;
        String normalizedCurrencyId;
        try {
            normalizedCurrencyId = normalizeCurrencyId(currencyId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
                    (id, name, item_material, item_data, display_name, price, currency_id, total_stock, sold,
                     per_player_limit, enabled, starts_at, ends_at, sale_type, blindbox_pool_id, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                ps.setString(1, id);
                ps.setString(2, finalName);
                ps.setString(3, item.getType().name());
                ps.setBytes(4, blob);
                ps.setString(5, legacyDisplayName(item));
                ps.setDouble(6, price);
                ps.setString(7, normalizedCurrencyId);
                ps.setInt(8, totalStock < 0 ? -1 : Math.max(0, totalStock));
                ps.setInt(9, 0);
                ps.setInt(10, perPlayerLimit < 0 ? -1 : Math.max(0, perPlayerLimit));
                ps.setInt(11, 1);
                ps.setLong(12, Math.max(0, startsAt));
                ps.setLong(13, Math.max(0, endsAt));
                ps.setString(14, SALE_TYPE_ITEM);
                ps.setString(15, "");
                ps.setLong(16, now);
                ps.setLong(17, now);
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
        return addBlindBoxSale(poolId, name, price, totalStock, perPlayerLimit, startsAt, endsAt,
                DEFAULT_CURRENCY_ID);
    }

    public String addBlindBoxSale(String poolId, String name, double price, int totalStock, int perPlayerLimit,
                                  long startsAt, long endsAt, String currencyId) {
        if (poolId == null || poolId.isBlank()) return null;
        String normalizedCurrencyId;
        try {
            normalizedCurrencyId = normalizeCurrencyId(currencyId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        Map<String, Object> pool = plugin.blindBoxManager().getPool(poolId.trim());
        if (pool == null) return null;
        String id = "bb_sale_" + UUID.randomUUID().toString().substring(0, 8);
        long now = now();
        String poolName = String.valueOf(pool.getOrDefault("name", poolId));
        String finalName = (name == null || name.isBlank()) ? poolName + " 限时盲盒" : name.trim();
        ItemStack icon = new ItemStack(Material.ENDER_CHEST, 1);
        double finalPrice = price > 0 ? price : (pool.get("price") instanceof Number n ? n.doubleValue() : 100.0);
        if (!isValidPrice(finalPrice)) return null;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO ks_limited_sale_items
                    (id, name, item_material, item_data, display_name, price, currency_id, total_stock, sold,
                     per_player_limit, enabled, starts_at, ends_at, sale_type, blindbox_pool_id, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                ps.setString(1, id);
                ps.setString(2, finalName);
                ps.setString(3, icon.getType().name());
                ps.setBytes(4, serialize(icon));
                ps.setString(5, finalName);
                ps.setDouble(6, finalPrice);
                ps.setString(7, normalizedCurrencyId);
                ps.setInt(8, totalStock < 0 ? -1 : Math.max(0, totalStock));
                ps.setInt(9, 0);
                ps.setInt(10, perPlayerLimit < 0 ? -1 : Math.max(0, perPlayerLimit));
                ps.setInt(11, 1);
                ps.setLong(12, Math.max(0, startsAt));
                ps.setLong(13, Math.max(0, endsAt));
                ps.setString(14, SALE_TYPE_BLINDBOX);
                ps.setString(15, poolId.trim());
                ps.setLong(16, now);
                ps.setLong(17, now);
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

    public boolean setCurrencyId(String id, String currencyId) {
        String normalizedCurrencyId;
        try {
            normalizedCurrencyId = normalizeCurrencyId(currencyId);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_limited_sale_items SET currency_id=?, updated_at=? WHERE id=?")) {
                ps.setString(1, normalizedCurrencyId);
                ps.setLong(2, now());
                ps.setString(3, id);
                return ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("更新限时直售货币失败: " + e.getMessage());
            return false;
        }
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
        if (!plugin.scheduler().isEntityThread(player)) {
            plugin.scheduler().runEntity(player,
                    () -> purchaseInternalAsync(player, saleId, qty, boxed, callback),
                    () -> callback.accept(new PurchaseResult(false, "玩家已离线，购买已取消", null, qty, false)));
            return;
        }
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        String operationKey = uuid + ":" + saleId;
        if (!activePurchases.add(operationKey)) {
            callback.accept(new PurchaseResult(false, "该商品正在结算，请稍候", null, boxed ? 1 : qty, false));
            return;
        }
        Consumer<PurchaseResult> completion = completionClearingPurchase(
                activePurchases, operationKey, callback);
        executeDatabaseOrReject(plugin.asyncWorkPool(), () -> {
            PurchasePreparation preparation = preparePurchase(uuid, playerName, saleId, qty, boxed);
            dispatchOrReject(task -> plugin.scheduler().runEntityOrGlobal(uuid, task), () -> {
                SaleItem sale = materialize(preparation.sale());
                if (preparation.error() != null) {
                    completion.accept(new PurchaseResult(false, preparation.error(), sale, boxed ? 1 : qty, false));
                    return;
                }
                if (!player.isOnline()) {
                    cancelReadySettlement(preparation.settlementId(), "player offline before charge");
                    completion.accept(new PurchaseResult(false, "玩家已离线，购买已取消", sale, boxed ? 1 : qty, false));
                    return;
                }
                if (boxed && (sale == null || !sale.boxPurchaseAvailable())) {
                    cancelReadySettlement(preparation.settlementId(), "box purchase no longer available");
                    completion.accept(new PurchaseResult(false, "该商品未开放整盒购买", sale, 1, false));
                    return;
                }
                if (!sale.blindBoxSale() && (sale.item() == null || sale.item().getType().isAir())) {
                    cancelReadySettlement(preparation.settlementId(), "sale item materialization failed");
                    completion.accept(new PurchaseResult(false, "商品物品无效", sale, boxed ? 1 : qty, false));
                    return;
                }
                double cost = preparation.amount();
                if (!Double.isFinite(cost) || cost <= 0 || cost > 1_000_000_000_000d) {
                    cancelReadySettlement(preparation.settlementId(), "invalid prepared amount");
                    completion.accept(new PurchaseResult(false, "商品价格无效", sale, boxed ? 1 : qty, false));
                    return;
                }
                if (!DEFAULT_CURRENCY_ID.equals(preparation.currencyId())) {
                    cancelReadySettlement(preparation.settlementId(), "unsupported currency " + preparation.currencyId());
                    completion.accept(new PurchaseResult(false,
                            "该商品使用专属货币 " + preparation.currencyId() + "，结算服务尚未接入",
                            sale, boxed ? 1 : qty, false));
                    return;
                }
                CashBackend backend = resolveCashBackend();
                if (backend == null) {
                    cancelReadySettlement(preparation.settlementId(), "cash backend unavailable");
                    completion.accept(new PurchaseResult(false, "经济系统不可用", sale, boxed ? 1 : qty, false));
                    return;
                }
                if (!claimSettlementCharge(preparation.settlementId(), backend)) {
                    completion.accept(new PurchaseResult(false, "无法认领购买扣款，请重试", sale,
                            boxed ? 1 : qty, false));
                    return;
                }
                final boolean charged;
                try {
                    charged = chargePlayer(player, cost, backend);
                } catch (Throwable uncertain) {
                    markSettlementReview(preparation.settlementId(), LimitedSaleSettlementStore.CHARGE_CLAIMED,
                            "charge outcome unknown: " + safeMessage(uncertain));
                    completion.accept(new PurchaseResult(false, "扣款结果未知，已转人工核对", sale,
                            boxed ? 1 : qty, false));
                    return;
                }
                if (!charged) {
                    transitionSettlement(preparation.settlementId(), LimitedSaleSettlementStore.CHARGE_CLAIMED,
                            LimitedSaleSettlementStore.COMPENSATED, "charge rejected");
                    completion.accept(new PurchaseResult(false, "余额不足，需要 " + formatMoney(cost), sale,
                            boxed ? 1 : qty, false));
                    return;
                }
                if (!transitionSettlement(preparation.settlementId(), LimitedSaleSettlementStore.CHARGE_CLAIMED,
                        LimitedSaleSettlementStore.CHARGED, "")) {
                    markSettlementReview(preparation.settlementId(), LimitedSaleSettlementStore.CHARGE_CLAIMED,
                            "charged but state finalization failed");
                    completion.accept(new PurchaseResult(false, "已扣款但结算状态异常，请联系管理员", sale,
                            boxed ? 1 : qty, false));
                    return;
                }
                executeDatabaseOrReject(plugin.asyncWorkPool(), () -> {
                    PurchaseCommit commit = commitPurchase(uuid, playerName, saleId, qty, boxed,
                            preparation.currencyId(), cost, backend, preparation.settlementId());
                    dispatchOrReject(task -> plugin.scheduler().runEntityOrGlobal(uuid, task), () -> {
                        SaleItem committedSale = materialize(commit.sale());
                        if (commit.error() != null) {
                            prepareSettlementRefund(commit.settlementId(), LimitedSaleSettlementStore.CHARGED,
                                    "purchase commit failed: " + commit.error());
                            boolean refunded = refundSettlement(player, commit.settlementId(),
                                    commit.amount(), commit.backend(), "commit failure");
                            completion.accept(new PurchaseResult(false,
                                    commit.error() + (refunded ? "，已退款" : "，退款失败请联系管理员"),
                                    committedSale, boxed ? 1 : qty, false));
                            return;
                        }
                        settleCommittedPurchase(player, committedSale, qty, boxed, commit.amount(),
                                commit.backend(), commit.settlementId(), completion);
                    }, () -> {
                        boolean compensated = commit.error() != null
                                ? prepareSettlementRefund(commit.settlementId(), LimitedSaleSettlementStore.CHARGED,
                                "server-thread failure handoff rejected")
                                : rollbackPurchaseCounters(commit.settlementId(), saleId, uuid, qty,
                                LimitedSaleSettlementStore.COUNTERS_COMMITTED);
                        if (!compensated) {
                            markSettlementReview(commit.settlementId(),
                                    commit.error() == null ? LimitedSaleSettlementStore.COUNTERS_COMMITTED
                                            : LimitedSaleSettlementStore.CHARGED,
                                    "server-thread delivery handoff failed and compensation failed");
                        }
                        activePurchases.remove(operationKey);
                        plugin.getLogger().severe("Limited sale delivery handoff rejected: player="
                                + uuid + " sale=" + saleId + "; settlement retained for recovery");
                    });
                }, () -> {
                    plugin.getLogger().warning("Limited sale commit queue rejected after charge: player="
                            + uuid + " sale=" + saleId + " backend=" + backend);
                    prepareSettlementRefund(preparation.settlementId(), LimitedSaleSettlementStore.CHARGED,
                            "commit queue rejected");
                    boolean refunded = refundSettlement(player, preparation.settlementId(),
                            cost, backend, "commit queue rejection");
                    completion.accept(new PurchaseResult(false,
                            "结算队列繁忙" + (refunded ? "，已退款" : "，退款失败请联系管理员"),
                            sale, boxed ? 1 : qty, false));
                });
            }, () -> {
                cancelReadySettlement(preparation.settlementId(), "server-thread charge handoff rejected");
                activePurchases.remove(operationKey);
                plugin.getLogger().warning("Limited sale charge handoff rejected: player="
                        + uuid + " sale=" + saleId);
            });
        }, () -> {
            plugin.getLogger().warning("Limited sale preparation queue rejected: player="
                    + uuid + " sale=" + saleId);
            completion.accept(new PurchaseResult(false,
                    "系统繁忙，请稍后重试", null, boxed ? 1 : qty, false));
        });
    }

    private PurchasePreparation preparePurchase(UUID uuid, String playerName, String saleId, int qty, boolean boxed) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return new PurchasePreparation(null, null, 0, DEFAULT_CURRENCY_ID, 0.0, null,
                    "数据库不可用");
            SaleSnapshot sale = getSaleSnapshot(conn, saleId);
            int purchased = sale == null ? 0 : getPurchased(conn, uuid, saleId);
            String error = validatePurchaseSnapshot(conn, sale, qty, purchased, boxed);
            String currencyId = sale == null ? DEFAULT_CURRENCY_ID : sale.currencyId();
            double amount = sale == null ? 0.0 : (boxed ? sale.boxPrice() : sale.price() * qty);
            if (error == null && (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000_000d)) {
                error = "商品价格无效";
            }
            String settlementId = null;
            if (error == null) {
                settlementId = "LS-" + UUID.randomUUID();
                LimitedSaleSettlementStore.insertReady(conn,
                        new LimitedSaleSettlementStore.Settlement(settlementId, uuid, playerName, saleId,
                                qty, boxed, amount, "", LimitedSaleSettlementStore.READY, ""), now());
            }
            return new PurchasePreparation(settlementId, sale, purchased, currencyId, amount, null, error);
        } catch (SQLException e) {
            plugin.getLogger().warning("准备限时直购购买失败: " + e.getMessage());
            return new PurchasePreparation(null, null, 0, DEFAULT_CURRENCY_ID, 0.0, null, "读取商品失败");
        }
    }

    private PurchaseCommit commitPurchase(UUID uuid, String playerName, String saleId, int qty,
                                           boolean boxed, String expectedCurrencyId, double expectedCost,
                                           CashBackend backend, String settlementId) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return new PurchaseCommit(settlementId, null, expectedCurrencyId, expectedCost, backend, "数据库不可用");
            conn.setAutoCommit(false);
            try {
                SaleSnapshot fresh = getSaleSnapshot(conn, saleId);
                int purchased = fresh == null ? 0 : getPurchased(conn, uuid, saleId);
                String error = validatePurchaseSnapshot(conn, fresh, qty, purchased, boxed);
                if (error != null) {
                    conn.rollback();
                    return new PurchaseCommit(settlementId, fresh, expectedCurrencyId, expectedCost, backend, error);
                }
                double freshCost = boxed ? fresh.boxPrice() : fresh.price() * qty;
                if (!fresh.currencyId().equals(expectedCurrencyId)) {
                    conn.rollback();
                    return new PurchaseCommit(settlementId, fresh, expectedCurrencyId, expectedCost, backend,
                            "商品计价货币已变化，请重新确认");
                }
                if (Double.compare(freshCost, expectedCost) != 0) {
                    conn.rollback();
                    return new PurchaseCommit(settlementId, fresh, expectedCurrencyId, expectedCost, backend,
                            "商品价格已变化，请重新确认");
                }
                long timestamp = now();
                if (fresh.unlimitedStock()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE ks_limited_sale_items SET sold=sold+?, updated_at=? WHERE id=?")) {
                        ps.setInt(1, qty);
                        ps.setLong(2, timestamp);
                        ps.setString(3, saleId);
                        if (ps.executeUpdate() != 1) throw new SQLException("商品库存更新失败");
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE ks_limited_sale_items SET sold=sold+?, updated_at=? "
                                    + "WHERE id=? AND total_stock>=0 AND sold+?<=total_stock")) {
                        ps.setInt(1, qty);
                        ps.setLong(2, timestamp);
                        ps.setString(3, saleId);
                        ps.setInt(4, qty);
                        if (ps.executeUpdate() != 1) {
                            conn.rollback();
                            return new PurchaseCommit(settlementId, fresh, expectedCurrencyId, expectedCost, backend,
                                    "库存不足，请重试");
                        }
                    }
                }
                incrementPurchased(conn, saleId, uuid.toString(), qty, timestamp);
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO ks_limited_sale_log
                        (sale_id, player_uuid, player_name, quantity, price_each, total_price, currency_id, bought_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """)) {
                    ps.setString(1, saleId);
                    ps.setString(2, uuid.toString());
                    ps.setString(3, playerName);
                    ps.setInt(4, qty);
                    ps.setDouble(5, expectedCost / qty);
                    ps.setDouble(6, expectedCost);
                    ps.setString(7, expectedCurrencyId);
                    ps.setLong(8, timestamp);
                    ps.executeUpdate();
                }
                if (!LimitedSaleSettlementStore.transition(conn, settlementId,
                        LimitedSaleSettlementStore.CHARGED,
                        LimitedSaleSettlementStore.COUNTERS_COMMITTED, "", timestamp)) {
                    throw new SQLException("limited-sale settlement state changed");
                }
                conn.commit();
                return new PurchaseCommit(settlementId, fresh, expectedCurrencyId, expectedCost, backend, null);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("限时直购购买事务失败: " + e.getMessage());
            return new PurchaseCommit(settlementId, null, expectedCurrencyId, expectedCost, backend, "购买失败");
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
                                         CashBackend backend, String settlementId,
                                         Consumer<PurchaseResult> callback) {
        if (!transitionSettlement(settlementId, LimitedSaleSettlementStore.COUNTERS_COMMITTED,
                LimitedSaleSettlementStore.DELIVERING, "")) {
            callback.accept(new PurchaseResult(false, "无法认领商品交付，已转人工核对",
                    sale, boxed ? 1 : qty, false));
            return;
        }
        if (sale == null) {
            rollbackAndRefund(player, null, qty, cost, backend, settlementId, "商品数据异常", callback);
            return;
        }
        if (sale.blindBoxSale()) {
            plugin.blindBoxManager().pullNoChargeBatchAsync(player.getUniqueId(), sale.blindBoxPoolId(), qty,
                    "LIMITED_BLINDBOX:" + sale.id(), results -> fulfillBlindBoxPurchaseAsync(
                            player, sale, qty, cost, backend, settlementId, results, callback));
            return;
        }
        if (boxed) {
            ItemStack box = createFilledBox(sale);
            if (box == null) {
                rollbackAndRefund(player, sale, qty, cost, backend, settlementId, "整盒生成失败", callback);
                return;
            }
            boolean stored = giveSingleItem(player, box, "LIMITED_SALE_BOX:" + sale.id());
            completeSettlementDelivery(settlementId);
            callback.accept(new PurchaseResult(true,
                    "整盒购买成功: " + sale.name() + " x" + BOX_UNITS + " 份，花费 " + formatMoney(cost)
                            + (stored ? "（背包已满，整盒已进暂存箱）" : ""), sale, 1, stored));
            return;
        }
        boolean stored = giveItems(player, sale.item(), qty, "LIMITED_SALE:" + sale.id());
        completeSettlementDelivery(settlementId);
        callback.accept(new PurchaseResult(true,
                "购买成功: " + sale.name() + " x" + qty + "，花费 " + formatMoney(cost)
                        + (stored ? "（背包满的部分已进暂存箱）" : ""), sale, qty, stored));
    }

    private void fulfillBlindBoxPurchaseAsync(Player player, SaleItem sale, int quantity, double paidCost,
                                               CashBackend backend, String settlementId,
                                               List<BlindBoxManager.PullResult> results,
                                               Consumer<PurchaseResult> callback) {
        if (results.size() != quantity || results.stream().anyMatch(result -> !result.success)) {
            String error = results.stream().filter(result -> !result.success).map(result -> result.error)
                    .filter(message -> message != null && !message.isBlank()).findFirst().orElse("未知错误");
            rollbackAndRefund(player, sale, quantity, paidCost, backend, settlementId,
                    "限时盲盒抽取失败: " + error, callback);
            return;
        }
        boolean stored = results.stream().anyMatch(result -> result.storedToBox);
        List<String> summary = new ArrayList<>(results.size());
        for (BlindBoxManager.PullResult result : results) {
            String itemName = result.itemDisplayName != null && !result.itemDisplayName.isBlank()
                    ? result.itemDisplayName : result.itemMaterial;
            summary.add(result.rarity + ":" + itemName);
        }
        completeSettlementDelivery(settlementId);
        callback.accept(new PurchaseResult(true,
                "限时盲盒抽取成功: " + sale.name() + " x" + quantity + "，花费 " + formatMoney(paidCost)
                        + "。结果: " + String.join(", ", summary)
                        + (stored ? "（背包满的部分已进暂存箱）" : ""), sale, quantity, stored));
    }

    private void rollbackAndRefund(Player player, SaleItem sale, int amount, double cost,
                                   CashBackend backend, String settlementId, String error,
                                   Consumer<PurchaseResult> callback) {
        String saleId = sale == null ? null : sale.id();
        UUID uuid = player.getUniqueId();
        Runnable rollbackTask = () -> {
            boolean rolledBack = saleId != null
                    && rollbackPurchaseCounters(settlementId, saleId, uuid, amount,
                    LimitedSaleSettlementStore.DELIVERING);
            dispatchOrReject(task -> plugin.scheduler().runEntityOrGlobal(uuid, task), () -> {
                        if (!rolledBack) {
                            callback.accept(new PurchaseResult(false,
                                    error + "，库存补偿失败，请联系管理员", sale, amount, false));
                            return;
                        }
                        finishRefund(player, sale, amount, cost, backend, settlementId, error, callback);
                    }, () -> {
                        activePurchases.remove(uuid + ":" + (saleId == null ? "" : saleId));
                        plugin.getLogger().severe("Limited sale refund handoff rejected after counters rollback: player="
                                + uuid + " sale=" + saleId + "; REFUND_READY retained for startup recovery");
                    });
        };
        executeDatabaseOrFallback(plugin.asyncWorkPool(), rollbackTask,
                task -> plugin.scheduler().runAsync(task), () -> {
            plugin.getLogger().severe("Limited sale rollback queue rejected after charge: player="
                    + uuid + " sale=" + saleId + " backend=" + backend
                    + "; emergency rollback scheduling also failed");
            Runnable finish = () -> callback.accept(new PurchaseResult(false,
                    error + "，库存补偿无法排队，请联系管理员", sale, amount, false));
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && plugin.scheduler().isEntityThread(online)) finish.run();
            else dispatchOrReject(task -> plugin.scheduler().runEntityOrGlobal(uuid, task), finish,
                    () -> activePurchases.remove(uuid + ":" + (saleId == null ? "" : saleId)));
        });
    }

    private void finishRefund(Player player, SaleItem sale, int amount, double cost,
                              CashBackend backend, String settlementId, String error,
                              Consumer<PurchaseResult> callback) {
        boolean refunded = refundSettlement(player.getUniqueId(), player.getName(), settlementId,
                cost, backend, "rollback");
        callback.accept(new PurchaseResult(false,
                error + (refunded ? "，已退款" : "，退款失败请联系管理员"),
                sale, amount, false));
    }

    private boolean refundPlayerSafely(Player player, double amount, CashBackend backend, String context) {
        try {
            return refundPlayer(player, amount, backend);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Limited sale refund threw after " + context + ": player=" + player.getUniqueId()
                            + " amount=" + amount + " backend=" + backend,
                    exception);
            return false;
        }
    }

    private void recoverSettlements() {
        if (!plugin.scheduler().isGlobalThread()) {
            dispatchOrReject(task -> plugin.scheduler().runGlobal(task), this::recoverSettlements,
                    () -> plugin.getLogger().severe("Limited-sale recovery handoff rejected"));
            return;
        }
        executeDatabaseOrReject(plugin.asyncWorkPool(), () -> {
            int review = 0;
            List<LimitedSaleSettlementStore.Settlement> refunds = new ArrayList<>();
            try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
                if (connection == null) return;
                review = LimitedSaleSettlementStore.markUnknownCallsForReview(connection, now());
            } catch (SQLException failure) {
                plugin.getLogger().warning("Limited-sale startup recovery failed: " + failure.getMessage());
                return;
            }
            List<LimitedSaleSettlementStore.Settlement> open;
            try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
                if (connection == null) return;
                open = LimitedSaleSettlementStore.open(connection);
            } catch (SQLException failure) {
                plugin.getLogger().warning("Limited-sale startup recovery load failed: " + failure.getMessage());
                return;
            }
            for (LimitedSaleSettlementStore.Settlement settlement : open) {
                switch (settlement.status()) {
                    case LimitedSaleSettlementStore.READY ->
                            transitionSettlement(settlement.id(), LimitedSaleSettlementStore.READY,
                                    LimitedSaleSettlementStore.COMPENSATED, "startup cancelled before charge");
                    case LimitedSaleSettlementStore.CHARGED -> {
                        if (prepareSettlementRefund(settlement.id(), LimitedSaleSettlementStore.CHARGED,
                                "startup refund before counters")) refunds.add(settlement);
                    }
                    case LimitedSaleSettlementStore.COUNTERS_COMMITTED -> {
                        if (rollbackPurchaseCounters(settlement.id(), settlement.saleId(),
                                settlement.playerUuid(), settlement.quantity(),
                                LimitedSaleSettlementStore.COUNTERS_COMMITTED)) refunds.add(settlement);
                    }
                    case LimitedSaleSettlementStore.REFUND_READY -> refunds.add(settlement);
                    default -> { }
                }
            }
            int finalReview = review;
            dispatchOrReject(task -> plugin.scheduler().runGlobal(task), () -> {
                if (finalReview > 0) {
                    plugin.getLogger().severe("Limited-sale external outcomes require manual review: " + finalReview);
                }
                for (LimitedSaleSettlementStore.Settlement settlement : refunds) {
                    CashBackend backend;
                    try {
                        backend = CashBackend.valueOf(settlement.backend());
                    } catch (IllegalArgumentException invalidBackend) {
                        markSettlementReview(settlement.id(), LimitedSaleSettlementStore.REFUND_READY,
                                "unknown refund backend " + settlement.backend());
                        continue;
                    }
                    refundSettlement(settlement.playerUuid(), settlement.playerName(), settlement.id(),
                            settlement.amount(), backend, "startup recovery");
                }
            }, () -> plugin.getLogger().severe(
                    "Limited-sale recovery refund handoff rejected; REFUND_READY rows retained"));
        }, () -> plugin.getLogger().severe("Limited-sale startup recovery queue rejected"));
    }

    private boolean claimSettlementCharge(String settlementId, CashBackend backend) {
        try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
            return connection != null && LimitedSaleSettlementStore.claimCharge(
                    connection, settlementId, backend.name(), now());
        } catch (SQLException failure) {
            plugin.getLogger().warning("Limited-sale charge claim failed: " + failure.getMessage());
            return false;
        }
    }

    private boolean transitionSettlement(String settlementId, String expected, String next, String error) {
        if (settlementId == null || settlementId.isBlank()) return false;
        try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
            return connection != null && LimitedSaleSettlementStore.transition(
                    connection, settlementId, expected, next, error, now());
        } catch (SQLException failure) {
            plugin.getLogger().warning("Limited-sale settlement transition failed: " + failure.getMessage());
            return false;
        }
    }

    private void cancelReadySettlement(String settlementId, String reason) {
        transitionSettlement(settlementId, LimitedSaleSettlementStore.READY,
                LimitedSaleSettlementStore.COMPENSATED, reason);
    }

    private void markSettlementReview(String settlementId, String expected, String error) {
        if (!transitionSettlement(settlementId, expected,
                LimitedSaleSettlementStore.REVIEW_REQUIRED, error)) {
            plugin.getLogger().severe("Limited-sale review state could not be persisted: "
                    + settlementId + " error=" + error);
        }
    }

    private boolean prepareSettlementRefund(String settlementId, String expected, String error) {
        return transitionSettlement(settlementId, expected,
                LimitedSaleSettlementStore.REFUND_READY, error);
    }

    private void completeSettlementDelivery(String settlementId) {
        if (!transitionSettlement(settlementId, LimitedSaleSettlementStore.DELIVERING,
                LimitedSaleSettlementStore.DELIVERED, "")) {
            plugin.getLogger().severe("Limited-sale delivery completed but state did not finalize: " + settlementId);
        }
    }

    private boolean refundSettlement(Player player, String settlementId, double amount,
                                     CashBackend backend, String context) {
        return refundSettlement(player.getUniqueId(), player.getName(), settlementId, amount, backend, context);
    }

    private boolean refundSettlement(UUID playerUuid, String playerName, String settlementId, double amount,
                                     CashBackend backend, String context) {
        if (!transitionSettlement(settlementId, LimitedSaleSettlementStore.REFUND_READY,
                LimitedSaleSettlementStore.REFUND_CLAIMED, "")) return false;
        final boolean refunded;
        try {
            refunded = refundPlayer(playerUuid, playerName, amount, backend);
        } catch (Throwable uncertain) {
            markSettlementReview(settlementId, LimitedSaleSettlementStore.REFUND_CLAIMED,
                    "refund outcome unknown after " + context + ": " + safeMessage(uncertain));
            return false;
        }
        if (refunded) {
            return transitionSettlement(settlementId, LimitedSaleSettlementStore.REFUND_CLAIMED,
                    LimitedSaleSettlementStore.COMPENSATED, "");
        }
        transitionSettlement(settlementId, LimitedSaleSettlementStore.REFUND_CLAIMED,
                LimitedSaleSettlementStore.REFUND_READY, "refund rejected after " + context);
        return false;
    }

    private static String safeMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null ? "unknown" : failure.getMessage();
    }

    private boolean rollbackPurchaseCounters(String settlementId, String saleId, UUID uuid, int amount,
                                             String expectedState) {
        if (amount <= 0) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            return rollbackPurchaseCounters(conn, settlementId, saleId, uuid, amount, expectedState, now());
        } catch (SQLException e) {
            plugin.getLogger().warning("回滚限时盲盒次数失败: " + e.getMessage());
            return false;
        }
    }

    static boolean rollbackPurchaseCounters(Connection conn, String settlementId, String saleId, UUID uuid,
                                            int amount, String expectedState, long now) throws SQLException {
        if (conn == null || amount <= 0) return false;
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_limited_sale_items SET sold=MAX(0, sold-?), updated_at=? WHERE id=?")) {
                ps.setInt(1, amount);
                ps.setLong(2, now);
                ps.setString(3, saleId);
                if (ps.executeUpdate() != 1) throw new SQLException("sale counter missing");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_limited_sale_players SET purchased=MAX(0, purchased-?), updated_at=? WHERE sale_id=? AND player_uuid=?")) {
                ps.setInt(1, amount);
                ps.setLong(2, now);
                ps.setString(3, saleId);
                ps.setString(4, uuid.toString());
                if (ps.executeUpdate() != 1) throw new SQLException("player counter missing");
            }
            if (!LimitedSaleSettlementStore.transition(conn, settlementId, expectedState,
                    LimitedSaleSettlementStore.REFUND_READY, "delivery rollback", now)) {
                throw new SQLException("settlement rollback state changed");
            }
            conn.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            conn.rollback();
            throw failure;
        } finally {
            conn.setAutoCommit(true);
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

    private CashBackend resolveCashBackend() {
        if (plugin.vaultHook() != null && plugin.vaultHook().isAvailable()) return CashBackend.VAULT;
        if (plugin.builtinEconomy() != null) return CashBackend.BUILTIN;
        return null;
    }

    private boolean chargePlayer(Player player, double amount, CashBackend backend) {
        if (amount <= 0) return true;
        if (backend == CashBackend.VAULT) {
            if (plugin.vaultHook() == null || !plugin.vaultHook().isAvailable()) return false;
            if (!plugin.vaultHook().has(player, amount)) return false;
            return plugin.vaultHook().withdraw(player, amount);
        }
        if (backend == CashBackend.BUILTIN) {
            return plugin.builtinEconomy() != null
                    && plugin.builtinEconomy().withdraw(player.getUniqueId(), player.getName(), amount);
        }
        return false;
    }

    private boolean refundPlayer(Player player, double amount, CashBackend backend) {
        return refundPlayer(player.getUniqueId(), player.getName(), amount, backend);
    }

    private boolean refundPlayer(UUID playerUuid, String playerName, double amount, CashBackend backend) {
        if (amount <= 0) return true;
        if (backend == CashBackend.VAULT) {
            if (plugin.vaultHook() == null || !plugin.vaultHook().isAvailable()) {
                plugin.getLogger().warning("限时直购退款失败: Vault 不可用, player="
                        + playerUuid + " amount=" + amount);
                return false;
            }
            boolean ok = plugin.vaultHook().deposit(Bukkit.getOfflinePlayer(playerUuid), amount);
            if (!ok) {
                plugin.getLogger().warning("限时直购 Vault 退款失败: player="
                        + playerUuid + " amount=" + amount);
            }
            return ok;
        }
        if (backend == CashBackend.BUILTIN) {
            if (plugin.builtinEconomy() == null) {
                plugin.getLogger().warning("限时直购退款失败: Builtin 经济不可用, player="
                        + playerUuid + " amount=" + amount);
                return false;
            }
            return plugin.builtinEconomy().deposit(playerUuid, playerName, amount);
        }
        plugin.getLogger().warning("限时直购退款失败: 未知后端, player="
                + playerUuid + " amount=" + amount);
        return false;
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

    static String normalizeCurrencyId(String currencyId) {
        return ListingManager.normalizeCurrencyId(currencyId);
    }

    static boolean isValidPrice(double price) {
        return Double.isFinite(price) && price > 0.0d && price <= 1_000_000_000_000d;
    }

    private static String normalizePersistedCurrencyId(String currencyId) {
        try {
            return normalizeCurrencyId(currencyId);
        } catch (IllegalArgumentException ignored) {
            return "INVALID";
        }
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
