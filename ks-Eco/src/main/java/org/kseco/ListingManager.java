package org.kseco;

import org.bukkit.inventory.ItemStack;
import org.kseco.currency.CurrencyPersistenceCompatibility;

import java.sql.*;
import java.util.*;

/**
 * 市场挂单管理器。
 * 所有方法均使用 try-with-resources 关闭 Connection，避免 SQLite WAL BUSY_SNAPSHOT。
 */
public final class ListingManager {

    public static final String DEFAULT_CURRENCY_ID = "CASH";
    private static final int MAX_CURRENCY_ID_LENGTH = 32;

    private final KsEco plugin;

    public ListingManager(KsEco plugin) {
        this.plugin = plugin;
        createTable();
        expireListings();
    }

    private void createTable() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_listings (
                        id VARCHAR(64) PRIMARY KEY,
                        seller_uuid TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        item_data BLOB NOT NULL,
                        item_material TEXT NOT NULL,
                        item_signature TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        unit_price REAL NOT NULL,
                        total_price REAL NOT NULL,
                        currency_id TEXT NOT NULL DEFAULT 'CASH',
                        listing_type TEXT NOT NULL DEFAULT 'SELL',
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER,
                        status TEXT DEFAULT 'ACTIVE'
                    )
                """);
            }
            // ALTER 兼容：物物交换扩展列
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE ks_eco_listings ADD COLUMN listing_mode TEXT DEFAULT 'SELL'");
            } catch (SQLException ignored) {}
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE ks_eco_listings ADD COLUMN wanted_material TEXT");
            } catch (SQLException ignored) {}
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE ks_eco_listings ADD COLUMN wanted_quantity INTEGER DEFAULT 0");
            } catch (SQLException ignored) {}
            // ALTER 兼容：商品房挂单扩展列
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE ks_eco_listings ADD COLUMN listing_asset_type TEXT DEFAULT 'ITEM'");
            } catch (SQLException ignored) {}
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE ks_eco_listings ADD COLUMN asset_ref TEXT DEFAULT NULL");
            } catch (SQLException ignored) {}
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE ks_eco_listings ADD COLUMN currency_id TEXT NOT NULL DEFAULT 'CASH'");
            } catch (SQLException ignored) {}
            CurrencyPersistenceCompatibility.canonicalizeLegacyCashRows(conn, "ks_eco_listings");
            initializePropertyListingClaims(conn);
            MarketPurchaseSettlementStore.initialize(conn);
        } catch (SQLException e) {
            plugin.getLogger().warning("创建挂单表失败: " + e.getMessage());
        }
    }

    static void initializePropertyListingClaims(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_active_property_listings (
                        house_id VARCHAR(128) PRIMARY KEY,
                        listing_id VARCHAR(64) NOT NULL UNIQUE
                    )
                    """);
            statement.execute("""
                    DELETE FROM ks_eco_active_property_listings
                    WHERE NOT EXISTS (
                        SELECT 1 FROM ks_eco_listings listing
                        WHERE listing.id=ks_eco_active_property_listings.listing_id
                          AND listing.listing_asset_type='PROPERTY'
                          AND listing.asset_ref=ks_eco_active_property_listings.house_id
                          AND listing.status IN ('ACTIVE','SETTLING')
                    )
                    """);
            statement.execute("""
                    INSERT INTO ks_eco_active_property_listings (house_id,listing_id)
                    SELECT listing.asset_ref,MIN(listing.id)
                    FROM ks_eco_listings listing
                    WHERE listing.listing_asset_type='PROPERTY'
                      AND listing.status IN ('ACTIVE','SETTLING')
                      AND listing.asset_ref IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM ks_eco_active_property_listings claim
                          WHERE claim.house_id=listing.asset_ref
                      )
                    GROUP BY listing.asset_ref
                    """);
        }
    }

    // ==================== SELL 挂单（卖钱） ====================

    public Listing createListing(UUID sellerUuid, String sellerName, ItemStack item,
                                  int quantity, double unitPrice, String type) {
        return createListing(sellerUuid, sellerName, prepareListingItem(item), quantity, unitPrice, type,
                DEFAULT_CURRENCY_ID);
    }

    public Listing createListing(UUID sellerUuid, String sellerName, PreparedListingItem item,
                                  int quantity, double unitPrice, String type) {
        return createListing(sellerUuid, sellerName, item, quantity, unitPrice, type, DEFAULT_CURRENCY_ID);
    }

    public Listing createListing(UUID sellerUuid, String sellerName, ItemStack item,
                                  int quantity, double unitPrice, String type, String currencyId) {
        return createListing(sellerUuid, sellerName, prepareListingItem(item), quantity, unitPrice, type,
                currencyId);
    }

    public Listing createListing(UUID sellerUuid, String sellerName, PreparedListingItem item,
                                  int quantity, double unitPrice, String type, String currencyId) {
        return createListingInternal(sellerUuid, sellerName, item, quantity, unitPrice, type,
                "SELL", null, 0, currencyId);
    }

    /** Captures ItemStack state before database work moves to a worker. */
    public PreparedListingItem prepareListingItem(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return null;
        ItemStack template = item.clone();
        template.setAmount(1);
        try {
            return new PreparedListingItem(template.getType().name(), buildSignature(template),
                    template.serializeAsBytes());
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Could not prepare listing item: " + exception.getMessage());
            return null;
        }
    }

    public Listing createBarterListing(UUID sellerUuid, String sellerName, ItemStack offerItem,
                                        int offerQuantity, String wantedMaterial, int wantedQuantity) {
        return createListingInternal(sellerUuid, sellerName, prepareListingItem(offerItem), offerQuantity, 0.0, "SELL",
                "BARTER", wantedMaterial, wantedQuantity, DEFAULT_CURRENCY_ID);
    }

    private Listing createListingInternal(UUID sellerUuid, String sellerName, PreparedListingItem item,
                                           int quantity, double unitPrice, String type,
                                           String listingMode, String wantedMaterial, int wantedQuantity,
                                           String currencyId) {
        if (item == null || item.material().isBlank() || item.itemData().length == 0) {
            plugin.getLogger().warning("createListing: 拒绝空/无效物品 (seller=" + sellerName + ")");
            return null;
        }
        if (quantity <= 0 || !Double.isFinite(unitPrice) || unitPrice < 0
                || (!"BARTER".equals(listingMode) && unitPrice <= 0)) {
            plugin.getLogger().warning("createListing: rejected invalid quantity or price for " + sellerName);
            return null;
        }
        String id = UUID.randomUUID().toString();
        String material = item.material();
        String signature = item.signature();
        String normalizedCurrencyId;
        try {
            normalizedCurrencyId = normalizeCurrencyId(currencyId);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("createListing: rejected invalid currency id for " + sellerName);
            return null;
        }
        double totalPrice = unitPrice * quantity;
        if (!Double.isFinite(totalPrice)) {
            plugin.getLogger().warning("createListing: rejected non-finite total price for " + sellerName);
            return null;
        }
        byte[] itemData = item.itemData();
        long now = System.currentTimeMillis() / 1000;
        Long expires = null;
        int expireHours = plugin.ecoConfig().getListingExpireHours();
        if (expireHours > 0) {
            expires = now + expireHours * 3600L;
        }

        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_eco_listings (id, seller_uuid, seller_name, item_data, item_material, " +
                    "item_signature, quantity, unit_price, total_price, currency_id, listing_type, listing_mode, " +
                    "wanted_material, wanted_quantity, created_at, expires_at, status) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, sellerUuid.toString());
                ps.setString(3, sellerName);
                ps.setBytes(4, itemData);
                ps.setString(5, material);
                ps.setString(6, signature);
                ps.setInt(7, quantity);
                ps.setDouble(8, unitPrice);
                ps.setDouble(9, totalPrice);
                ps.setString(10, normalizedCurrencyId);
                ps.setString(11, type);
                ps.setString(12, listingMode);
                ps.setString(13, wantedMaterial);
                ps.setInt(14, wantedQuantity);
                ps.setLong(15, now);
                if (expires != null) ps.setLong(16, expires);
                else ps.setNull(16, Types.INTEGER);
                ps.setString(17, "ACTIVE");
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("创建挂单失败: " + e.getMessage());
            return null;
        }

        return new Listing(id, sellerUuid, sellerName, material, signature, itemData,
                quantity, unitPrice, totalPrice, listingMode, wantedMaterial, wantedQuantity, type, now,
                "ITEM", null, normalizedCurrencyId);
    }

    // ==================== PROPERTY 挂单（卖商品房） ====================

    public Listing createPropertyListing(UUID sellerUuid, String sellerName, String houseId, double price) {
        if (houseId == null || houseId.isEmpty() || !Double.isFinite(price) || price <= 0) return null;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            return createPropertyListingAtomically(conn, sellerUuid, sellerName, houseId, price);
        } catch (SQLException e) {
            plugin.getLogger().warning("创建商品房挂单失败: " + e.getMessage());
            return null;
        }
    }

    static Listing createPropertyListingAtomically(Connection connection, UUID sellerUuid, String sellerName,
                                                    String houseId, double price) throws SQLException {
        if (houseId == null || houseId.isEmpty() || !Double.isFinite(price) || price <= 0) return null;
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        boolean previousAutoCommit = connection.getAutoCommit();
        if (previousAutoCommit) connection.setAutoCommit(false);
        try {
            try (PreparedStatement cleanup = connection.prepareStatement("""
                    DELETE FROM ks_eco_active_property_listings
                    WHERE house_id=? AND NOT EXISTS (
                        SELECT 1 FROM ks_eco_listings listing
                        WHERE listing.id=ks_eco_active_property_listings.listing_id
                          AND listing.listing_asset_type='PROPERTY'
                          AND listing.asset_ref=ks_eco_active_property_listings.house_id
                          AND listing.status IN ('ACTIVE','SETTLING')
                    )
                    """)) {
                cleanup.setString(1, houseId);
                cleanup.executeUpdate();
            }
            // Keep the no-op cleanup first: on SQLite it acquires the writer lock before any reads,
            // preserving the single-winner property-listing contract under concurrent requests.
            if (optionalTableExists(connection, "ks_re_houses") && optionalTableExists(connection, "ks_re_plots")) {
                try (PreparedStatement property = connection.prepareStatement("""
                        SELECT p.status FROM ks_re_houses h JOIN ks_re_plots p ON p.id=h.plot_id WHERE h.id=?
                        """)) {
                    property.setString(1, houseId);
                    try (ResultSet row = property.executeQuery()) {
                        if (!row.next() || !"PURCHASED".equals(row.getString(1))) {
                            connection.rollback();
                            return null;
                        }
                    }
                }
            }
            if (optionalTableExists(connection, "ks_bank_player_collateral")) {
                try (PreparedStatement collateral = connection.prepareStatement("""
                        SELECT 1 FROM ks_bank_player_collateral
                        WHERE asset_type='HOUSE' AND asset_ref=? AND status IN ('RESERVED','LOCKED','SEIZED')
                        """)) {
                    collateral.setString(1, houseId);
                    if (collateral.executeQuery().next()) {
                        connection.rollback();
                        return null;
                    }
                }
            }
            int claimed;
            try (PreparedStatement claim = connection.prepareStatement("""
                    INSERT INTO ks_eco_active_property_listings (house_id,listing_id)
                    SELECT ?,?
                    WHERE NOT EXISTS (
                        SELECT 1 FROM ks_eco_listings
                        WHERE listing_asset_type='PROPERTY' AND asset_ref=?
                          AND status IN ('ACTIVE','SETTLING')
                    )
                    """)) {
                claim.setString(1, houseId);
                claim.setString(2, id);
                claim.setString(3, houseId);
                claimed = claim.executeUpdate();
            }
            if (claimed != 1) {
                connection.rollback();
                return null;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ks_eco_listings (id, seller_uuid, seller_name, item_data, item_material, " +
                    "item_signature, quantity, unit_price, total_price, currency_id, listing_type, listing_mode, " +
                    "wanted_material, wanted_quantity, created_at, expires_at, status, listing_asset_type, asset_ref) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, sellerUuid.toString());
                ps.setString(3, sellerName);
                ps.setBytes(4, new byte[0]);
                ps.setString(5, "AIR");
                ps.setString(6, "PROPERTY:" + houseId);
                ps.setInt(7, 1);
                ps.setDouble(8, price);
                ps.setDouble(9, price);
                ps.setString(10, DEFAULT_CURRENCY_ID);
                ps.setString(11, "SELL");
                ps.setString(12, "SELL");
                ps.setNull(13, Types.VARCHAR);
                ps.setInt(14, 0);
                ps.setLong(15, now);
                ps.setNull(16, Types.INTEGER);
                ps.setString(17, "ACTIVE");
                ps.setString(18, "PROPERTY");
                ps.setString(19, houseId);
                ps.executeUpdate();
            }
            connection.commit();
            return new Listing(id, sellerUuid, sellerName, "AIR", "PROPERTY:" + houseId, new byte[0],
                    1, price, price, "SELL", null, 0, "SELL", now, "PROPERTY", houseId,
                    DEFAULT_CURRENCY_ID);
        } catch (SQLException exception) {
            connection.rollback();
            if (isConstraintViolation(exception)) return null;
            throw exception;
        } finally {
            if (previousAutoCommit) connection.setAutoCommit(true);
        }
    }

    private static boolean isConstraintViolation(SQLException exception) {
        String sqlState = exception.getSQLState();
        return (sqlState != null && sqlState.startsWith("23")) || exception.getErrorCode() == 19;
    }

    private static boolean optionalTableExists(Connection connection, String table) throws SQLException {
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))) {
            try (ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(),
                    candidate, new String[]{"TABLE"})) {
                if (rows.next()) return true;
            }
        }
        return false;
    }

    public boolean hasActivePropertyListing(String houseId) {
        expireListings();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_eco_listings WHERE status='ACTIVE' AND listing_asset_type='PROPERTY' "
                            + "AND asset_ref=? AND (expires_at IS NULL OR expires_at>?)")) {
                ps.setString(1, houseId);
                ps.setLong(2, System.currentTimeMillis() / 1000);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public List<Listing> getActiveListings(String type) {
        return getActiveListings(type, null);
    }

    public List<Listing> getActiveListings(String type, String listingMode) {
        return getActiveListings(type, listingMode, DEFAULT_CURRENCY_ID);
    }

    public List<Listing> getActiveListings(String type, String listingMode, String currencyId) {
        expireListings();
        List<Listing> result = new ArrayList<>();
        String normalizedCurrencyId;
        try {
            normalizedCurrencyId = normalizeCurrencyId(currencyId);
        } catch (IllegalArgumentException ignored) {
            return result;
        }
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;

            StringBuilder sql = new StringBuilder(
                    "SELECT id, seller_uuid, seller_name, item_material, item_signature, item_data, " +
                    "quantity, unit_price, total_price, COALESCE(listing_mode,'SELL') as listing_mode, " +
                    "wanted_material, COALESCE(wanted_quantity,0) as wanted_quantity, " +
                    "listing_type, created_at, expires_at, " +
                    "COALESCE(listing_asset_type,'ITEM') as listing_asset_type, asset_ref, " +
                    "COALESCE(NULLIF(TRIM(currency_id),''),'CASH') as currency_id " +
                     "FROM ks_eco_listings WHERE status='ACTIVE' "
                            + "AND COALESCE(NULLIF(TRIM(currency_id),''),'CASH')=? "
                            + "AND (expires_at IS NULL OR expires_at>?)");
            if (type != null && !type.isEmpty()) sql.append(" AND listing_type=?");
            if (listingMode != null && !listingMode.isEmpty()) sql.append(" AND COALESCE(listing_mode,'SELL')=?");
            sql.append(" ORDER BY unit_price ASC");

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setString(idx++, normalizedCurrencyId);
                ps.setLong(idx++, System.currentTimeMillis() / 1000);
                if (type != null && !type.isEmpty()) ps.setString(idx++, type);
                if (listingMode != null && !listingMode.isEmpty()) ps.setString(idx++, listingMode);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapListing(rs));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询挂单失败: " + e.getMessage());
        }
        return result;
    }

    /** 管理员查询所有活跃挂单（含卖家信息），用于强制撤单管理。 */
    public List<Listing> getAllActiveListings() {
        expireListings();
        List<Listing> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, seller_name, item_material, item_signature, item_data, " +
                    "quantity, unit_price, total_price, COALESCE(listing_mode,'SELL') as listing_mode, " +
                    "wanted_material, COALESCE(wanted_quantity,0) as wanted_quantity, " +
                    "listing_type, created_at, expires_at, " +
                    "COALESCE(listing_asset_type,'ITEM') as listing_asset_type, asset_ref, " +
                    "COALESCE(NULLIF(TRIM(currency_id),''),'CASH') as currency_id " +
                    "FROM ks_eco_listings WHERE status='ACTIVE' "
                            + "AND (expires_at IS NULL OR expires_at>?) ORDER BY created_at DESC LIMIT 200")) {
                ps.setLong(1, System.currentTimeMillis() / 1000);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(mapListing(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询全部挂单失败: " + e.getMessage());
        }
        return result;
    }

    public List<Listing> getPlayerListings(UUID playerUuid) {
        expireListings();
        List<Listing> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, seller_name, item_material, item_signature, item_data, " +
                    "quantity, unit_price, total_price, COALESCE(listing_mode,'SELL') as listing_mode, " +
                    "wanted_material, COALESCE(wanted_quantity,0) as wanted_quantity, " +
                    "listing_type, created_at, expires_at, " +
                    "COALESCE(listing_asset_type,'ITEM') as listing_asset_type, asset_ref, " +
                    "COALESCE(NULLIF(TRIM(currency_id),''),'CASH') as currency_id " +
                    "FROM ks_eco_listings WHERE seller_uuid=? AND status='ACTIVE' "
                            + "AND (expires_at IS NULL OR expires_at>?)")) {
                ps.setString(1, playerUuid.toString());
                ps.setLong(2, System.currentTimeMillis() / 1000);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(mapListing(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询玩家挂单失败: " + e.getMessage());
        }
        return result;
    }

    public Listing getListing(String listingId) {
        return getListing(listingId, DEFAULT_CURRENCY_ID);
    }

    public Listing getListing(String listingId, String currencyId) {
        expireListings();
        String normalizedCurrencyId;
        try {
            normalizedCurrencyId = normalizeCurrencyId(currencyId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, seller_name, item_material, item_signature, item_data, " +
                    "quantity, unit_price, total_price, COALESCE(listing_mode,'SELL') as listing_mode, " +
                    "wanted_material, COALESCE(wanted_quantity,0) as wanted_quantity, " +
                    "listing_type, created_at, expires_at, " +
                    "COALESCE(listing_asset_type,'ITEM') as listing_asset_type, asset_ref, " +
                    "COALESCE(NULLIF(TRIM(currency_id),''),'CASH') as currency_id " +
                    "FROM ks_eco_listings WHERE id=? AND status='ACTIVE' "
                            + "AND COALESCE(NULLIF(TRIM(currency_id),''),'CASH')=? "
                            + "AND (expires_at IS NULL OR expires_at>?)")) {
                ps.setString(1, listingId);
                ps.setString(2, normalizedCurrencyId);
                ps.setLong(3, System.currentTimeMillis() / 1000);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapListing(rs);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询挂单失败: " + e.getMessage());
        }
        return null;
    }

    /** Loads a listing snapshot regardless of ACTIVE/FILLED state for settlement recovery only. */
    public Listing getListingForSettlement(String listingId) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, seller_name, item_material, item_signature, item_data, "
                            + "quantity, unit_price, total_price, COALESCE(listing_mode,'SELL') as listing_mode, "
                            + "wanted_material, COALESCE(wanted_quantity,0) as wanted_quantity, "
                            + "listing_type, created_at, expires_at, "
                            + "COALESCE(listing_asset_type,'ITEM') as listing_asset_type, asset_ref, "
                            + "COALESCE(NULLIF(TRIM(currency_id),''),'CASH') as currency_id "
                            + "FROM ks_eco_listings WHERE id=?")) {
                ps.setString(1, listingId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapListing(rs) : null;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询结算挂单失败: " + e.getMessage());
            return null;
        }
    }

    private Listing mapListing(ResultSet rs) throws SQLException {
        return new Listing(
                rs.getString("id"),
                UUID.fromString(rs.getString("seller_uuid")),
                rs.getString("seller_name"),
                rs.getString("item_material"),
                rs.getString("item_signature"),
                rs.getBytes("item_data"),
                rs.getInt("quantity"),
                rs.getDouble("unit_price"),
                rs.getDouble("total_price"),
                rs.getString("listing_mode"),
                rs.getString("wanted_material"),
                rs.getInt("wanted_quantity"),
                rs.getString("listing_type"),
                rs.getLong("created_at"),
                rs.getString("listing_asset_type"),
                rs.getString("asset_ref"),
                normalizePersistedCurrencyId(rs.getString("currency_id"))
        );
    }

    /** 取消挂单，并在同一事务内把剩余物品写入卖家暂存箱。 */
    private void expireListings() {
        try (Connection connection = plugin.ksCore().dataStore().getConnection()) {
            if (connection == null) return;
            int expired = expireListings(connection, System.currentTimeMillis() / 1000);
            if (expired > 0) {
                plugin.getLogger().info("Expired " + expired + " market listing(s) and returned remaining items");
            }
        } catch (SQLException failure) {
            plugin.getLogger().warning("Expire market listings failed: " + failure.getMessage());
        }
    }

    static int expireListings(Connection connection, long now) throws SQLException {
        boolean manageTransaction = connection.getAutoCommit();
        if (manageTransaction) connection.setAutoCommit(false);
        int expired = 0;
        try {
            List<ExpiredListing> candidates = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id,seller_uuid,item_data,item_material,quantity,"
                            + "COALESCE(listing_asset_type,'ITEM') FROM ks_eco_listings "
                            + "WHERE status='ACTIVE' AND expires_at IS NOT NULL AND expires_at<=? ORDER BY id")) {
                select.setLong(1, now);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        candidates.add(new ExpiredListing(rows.getString(1), rows.getString(2), rows.getBytes(3),
                                rows.getString(4), rows.getInt(5), rows.getString(6)));
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ks_eco_listings SET status='EXPIRED' WHERE id=? AND status='ACTIVE' "
                            + "AND expires_at IS NOT NULL AND expires_at<=?");
                 PreparedStatement store = connection.prepareStatement(
                         "INSERT INTO ks_eco_storage "
                                 + "(id,owner_uuid,item_data,item_material,quantity,source,stored_at) "
                                 + "VALUES (?,?,?,?,?,?,?)")) {
                for (ExpiredListing candidate : candidates) {
                    update.setString(1, candidate.id());
                    update.setLong(2, now);
                    if (update.executeUpdate() != 1) continue;
                    if (!"PROPERTY".equals(candidate.assetType()) && candidate.itemData() != null
                            && candidate.itemData().length > 0 && candidate.quantity() > 0) {
                        store.setString(1, UUID.randomUUID().toString());
                        store.setString(2, candidate.sellerUuid());
                        store.setBytes(3, candidate.itemData());
                        store.setString(4, candidate.material());
                        store.setInt(5, candidate.quantity());
                        store.setString(6, "LISTING_EXPIRED:" + candidate.id());
                        store.setLong(7, now);
                        if (store.executeUpdate() != 1) throw new SQLException("Expired listing return was not stored");
                    }
                    expired++;
                }
            }
            if (manageTransaction) connection.commit();
            return expired;
        } catch (SQLException | RuntimeException failure) {
            if (manageTransaction) connection.rollback();
            throw failure;
        } finally {
            if (manageTransaction) connection.setAutoCommit(true);
        }
    }

    public boolean cancelListing(String listingId, UUID requesterUuid) {
        return cancelAndReturn(listingId, requesterUuid);
    }

    /**
     * 管理员强制销毁挂单（物品不退回，直接标记 CANCELLED）。
     */
    public boolean destroyListingAdmin(String listingId) {
        return markCancelled(listingId);
    }

    /**
     * 管理员强制撤单（绕过卖家身份校验）。
     * 物品退回卖家暂存箱；商品房直接标记取消（产权不变动）。
     */
    public boolean cancelListingAdmin(String listingId) {
        return cancelAndReturn(listingId, null);
    }

    private boolean cancelAndReturn(String listingId, UUID expectedSeller) {
        expireListings();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                String sellerUuid;
                String assetType;
                String material;
                byte[] itemData;
                int quantity;
                try (PreparedStatement select = conn.prepareStatement(
                        "SELECT seller_uuid, item_data, item_material, quantity, " +
                                "COALESCE(listing_asset_type,'ITEM') AS listing_asset_type " +
                                "FROM ks_eco_listings WHERE id=? AND status='ACTIVE'")) {
                    select.setString(1, listingId);
                    try (ResultSet rs = select.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return false;
                        }
                        sellerUuid = rs.getString("seller_uuid");
                        if (expectedSeller != null && !sellerUuid.equals(expectedSeller.toString())) {
                            conn.rollback();
                            return false;
                        }
                        assetType = rs.getString("listing_asset_type");
                        material = rs.getString("item_material");
                        itemData = rs.getBytes("item_data");
                        quantity = rs.getInt("quantity");
                    }
                }
                try (PreparedStatement update = conn.prepareStatement(
                        "UPDATE ks_eco_listings SET status='CANCELLED' WHERE id=? AND status='ACTIVE'")) {
                    update.setString(1, listingId);
                    if (update.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                if (!"PROPERTY".equals(assetType) && itemData != null && itemData.length > 0 && quantity > 0) {
                    try (PreparedStatement store = conn.prepareStatement(
                            "INSERT INTO ks_eco_storage " +
                                    "(id, owner_uuid, item_data, item_material, quantity, source, stored_at) " +
                                    "VALUES (?,?,?,?,?,'LISTING_RETURN',?)")) {
                        store.setString(1, UUID.randomUUID().toString());
                        store.setString(2, sellerUuid);
                        store.setBytes(3, itemData);
                        store.setString(4, material);
                        store.setInt(5, quantity);
                        store.setLong(6, System.currentTimeMillis() / 1000);
                        store.executeUpdate();
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("取消挂单失败: " + e.getMessage());
            return false;
        }
    }

    private boolean markCancelled(String listingId) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_eco_listings SET status='CANCELLED' WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, listingId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("标记挂单取消失败: " + e.getMessage());
            return false;
        }
    }

    public boolean fillListing(String listingId, UUID buyerUuid, int quantity) {
        if (quantity <= 0) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_eco_listings SET quantity=quantity-?, total_price=unit_price*(quantity-?), " +
                    "status=CASE WHEN quantity-?<=0 THEN 'FILLED' ELSE 'ACTIVE' END " +
                    "WHERE id=? AND status='ACTIVE' AND quantity>=?")) {
                ps.setInt(1, quantity);
                ps.setInt(2, quantity);
                ps.setInt(3, quantity);
                ps.setString(4, listingId);
                ps.setInt(5, quantity);
                int updated = ps.executeUpdate();
                return updated > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("填充挂单失败: " + e.getMessage());
            return false;
        }
    }

    public boolean restoreFilledListing(String listingId, int quantity) {
        if (quantity <= 0) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ks_eco_listings SET quantity=quantity+?, " +
                            "total_price=unit_price*(quantity+?), status='ACTIVE' " +
                            "WHERE id=? AND status IN ('ACTIVE','FILLED')")) {
                ps.setInt(1, quantity);
                ps.setInt(2, quantity);
                ps.setString(3, listingId);
                return ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("恢复挂单失败: " + listingId + " - " + e.getMessage());
            return false;
        }
    }

    /** Atomically reserves listing stock and writes the buyer's storage delivery. Worker-only. */
    public PurchaseReservation reservePurchase(Listing listing, UUID buyerUuid, int quantity) {
        return reservePurchase(listing, buyerUuid, quantity, null);
    }

    /** Atomically reserves stock and records a hidden delivery for a durable market settlement. */
    public PurchaseReservation reservePurchase(Listing listing, UUID buyerUuid, int quantity, String settlementId) {
        if (listing == null || buyerUuid == null || quantity <= 0 || listing.isProperty()
                || listing.isBarter() || listing.quantity() < quantity) return null;
        String storageId = UUID.randomUUID().toString();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement claim = conn.prepareStatement(
                        "UPDATE ks_eco_listings SET quantity=quantity-?,total_price=unit_price*(quantity-?),"
                                + "status=CASE WHEN quantity-?<=0 THEN 'FILLED' ELSE 'ACTIVE' END "
                                + "WHERE id=? AND status='ACTIVE' AND quantity>=? AND seller_uuid=? "
                                + "AND unit_price=? AND COALESCE(listing_mode,'SELL')='SELL' "
                                + "AND COALESCE(NULLIF(TRIM(currency_id),''),'CASH')=? "
                                + "AND (expires_at IS NULL OR expires_at>?) "
                                + "AND COALESCE(listing_asset_type,'ITEM')='ITEM'")) {
                    claim.setInt(1, quantity);
                    claim.setInt(2, quantity);
                    claim.setInt(3, quantity);
                    claim.setString(4, listing.id());
                    claim.setInt(5, quantity);
                    claim.setString(6, listing.sellerUuid().toString());
                    claim.setDouble(7, listing.unitPrice());
                    claim.setString(8, listing.currencyId());
                    claim.setLong(9, System.currentTimeMillis() / 1000);
                    if (claim.executeUpdate() != 1) {
                        conn.rollback();
                        return null;
                    }
                }
                try (PreparedStatement store = conn.prepareStatement(
                        "INSERT INTO ks_eco_storage "
                                + "(id,owner_uuid,item_data,item_material,quantity,source,stored_at) "
                                + "VALUES (?,?,?,?,?,?,?)")) {
                    store.setString(1, storageId);
                    store.setString(2, buyerUuid.toString());
                    store.setBytes(3, listing.itemData());
                    store.setString(4, listing.itemMaterial());
                    store.setInt(5, quantity);
                    store.setString(6, settlementId == null
                            ? "MARKET_PURCHASE:" + listing.id()
                            : "MARKET_PENDING:" + settlementId + ":" + listing.id());
                    store.setLong(7, System.currentTimeMillis() / 1000);
                    store.executeUpdate();
                }
                if (settlementId != null) {
                    try (PreparedStatement settlement = conn.prepareStatement(
                            "UPDATE ks_eco_market_settlements SET storage_id=?,status=?,last_error='',updated_at=? "
                                    + "WHERE id=? AND status=?")) {
                        settlement.setString(1, storageId);
                        settlement.setString(2, MarketPurchaseSettlementStore.RESERVED);
                        settlement.setLong(3, System.currentTimeMillis() / 1000);
                        settlement.setString(4, settlementId);
                        settlement.setString(5, MarketPurchaseSettlementStore.BUYER_CHARGED);
                        if (settlement.executeUpdate() != 1) {
                            conn.rollback();
                            return null;
                        }
                    }
                }
                conn.commit();
                return new PurchaseReservation(storageId, listing.id(), buyerUuid, quantity,
                        listing.currencyId(), listing.unitPrice() * quantity);
            } catch (SQLException exception) {
                conn.rollback();
                throw exception;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Market purchase reservation failed for " + listing.id()
                    + ": " + exception.getMessage());
            return null;
        }
    }

    /** Atomically removes an undelivered reservation and returns its stock. Worker-only. */
    public boolean rollbackPurchase(PurchaseReservation reservation) {
        return rollbackPurchase(reservation, null, null);
    }

    public boolean rollbackPurchase(PurchaseReservation reservation, String settlementId, String expectedState) {
        if (reservation == null) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM ks_eco_storage WHERE id=? AND owner_uuid=?"
                                + (settlementId == null ? "" : " AND source=?"))) {
                    delete.setString(1, reservation.storageId());
                    delete.setString(2, reservation.buyerUuid().toString());
                    if (settlementId != null) {
                        delete.setString(3, "MARKET_PENDING:" + settlementId + ":" + reservation.listingId());
                    }
                    if (delete.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                try (PreparedStatement restore = conn.prepareStatement(
                        "UPDATE ks_eco_listings SET quantity=quantity+?,"
                                + "total_price=unit_price*(quantity+?),status='ACTIVE' "
                                + "WHERE id=? AND status IN ('ACTIVE','FILLED')")) {
                    restore.setInt(1, reservation.quantity());
                    restore.setInt(2, reservation.quantity());
                    restore.setString(3, reservation.listingId());
                    if (restore.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                if (settlementId != null) {
                    try (PreparedStatement settlement = conn.prepareStatement(
                            "UPDATE ks_eco_market_settlements SET storage_id=NULL,status=?,last_error='',updated_at=? "
                                    + "WHERE id=? AND status=?")) {
                        settlement.setString(1, MarketPurchaseSettlementStore.REFUND_READY);
                        settlement.setLong(2, System.currentTimeMillis() / 1000);
                        settlement.setString(3, settlementId);
                        settlement.setString(4, expectedState);
                        if (settlement.executeUpdate() != 1) {
                            conn.rollback();
                            return false;
                        }
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException exception) {
                conn.rollback();
                throw exception;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Market purchase rollback failed for "
                    + reservation.listingId() + ": " + exception.getMessage());
            return false;
        }
    }

    public boolean finalizePurchase(PurchaseReservation reservation, String settlementId) {
        if (reservation == null || settlementId == null || settlementId.isBlank()) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delivery = conn.prepareStatement(
                        "UPDATE ks_eco_storage SET source=? WHERE id=? AND owner_uuid=? AND source=?")) {
                    delivery.setString(1, "MARKET_PURCHASE:" + reservation.listingId());
                    delivery.setString(2, reservation.storageId());
                    delivery.setString(3, reservation.buyerUuid().toString());
                    delivery.setString(4, "MARKET_PENDING:" + settlementId + ":" + reservation.listingId());
                    if (delivery.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                try (PreparedStatement settlement = conn.prepareStatement(
                        "UPDATE ks_eco_market_settlements SET status=?,last_error='',updated_at=? "
                                + "WHERE id=? AND status=? AND storage_id=?")) {
                    settlement.setString(1, MarketPurchaseSettlementStore.FINALIZED);
                    settlement.setLong(2, System.currentTimeMillis() / 1000);
                    settlement.setString(3, settlementId);
                    settlement.setString(4, MarketPurchaseSettlementStore.SELLER_PAYOUT_CLAIMED);
                    settlement.setString(5, reservation.storageId());
                    if (settlement.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException exception) {
                conn.rollback();
                throw exception;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Market purchase finalization failed for "
                    + reservation.listingId() + ": " + exception.getMessage());
            return false;
        }
    }

    public int activeListingCount() {
        expireListings();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (PreparedStatement statement = conn.prepareStatement(
                    "SELECT COUNT(*) FROM ks_eco_listings WHERE status='ACTIVE' "
                            + "AND (expires_at IS NULL OR expires_at>?)")) {
                statement.setLong(1, System.currentTimeMillis() / 1000);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    // ---- 工具方法 ----

    private String buildSignature(ItemStack item) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.getType().name());
        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            if (meta.hasEnchants()) {
                sb.append("|ench:");
                meta.getEnchants().forEach((ench, level) ->
                        sb.append(ench.getKey().getKey()).append(":").append(level).append(","));
            }
            if (meta.hasDisplayName()) {
                sb.append("|name:").append(meta.getDisplayName().hashCode());
            }
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    static String normalizeCurrencyId(String currencyId) {
        String normalized = currencyId == null ? "" : currencyId.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return DEFAULT_CURRENCY_ID;
        if (normalized.length() > MAX_CURRENCY_ID_LENGTH
                || !normalized.matches("[A-Z][A-Z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid currency id");
        }
        return normalized;
    }

    private static String normalizePersistedCurrencyId(String currencyId) {
        try {
            return normalizeCurrencyId(currencyId);
        } catch (IllegalArgumentException ignored) {
            return "INVALID";
        }
    }

    // ---- 数据类 ----

    private record ExpiredListing(String id, String sellerUuid, byte[] itemData, String material,
                                  int quantity, String assetType) { }

    public record PreparedListingItem(String material, String signature, byte[] itemData) {
        public PreparedListingItem {
            material = material == null ? "" : material;
            signature = signature == null ? "" : signature;
            itemData = itemData == null ? new byte[0] : itemData.clone();
        }

        @Override
        public byte[] itemData() {
            return itemData.clone();
        }
    }

    public record PurchaseReservation(String storageId, String listingId,
                                      UUID buyerUuid, int quantity,
                                      String currencyId, double amount) {
        public PurchaseReservation(String storageId, String listingId, UUID buyerUuid, int quantity) {
            this(storageId, listingId, buyerUuid, quantity, DEFAULT_CURRENCY_ID, 0.0);
        }

        public PurchaseReservation {
            currencyId = normalizeCurrencyId(currencyId);
        }
    }

    public record Listing(
            String id,
            UUID sellerUuid,
            String sellerName,
            String itemMaterial,
            String itemSignature,
            byte[] itemData,
            int quantity,
            double unitPrice,
            double totalPrice,
            String listingMode,
            String wantedMaterial,
            int wantedQuantity,
            String listingType,
            long createdAt,
            String listingAssetType,
            String assetRef,
            String currencyId
    ) {
        public Listing(String id, UUID sellerUuid, String sellerName, String itemMaterial,
                       String itemSignature, byte[] itemData, int quantity, double unitPrice,
                       double totalPrice, String listingMode, String wantedMaterial, int wantedQuantity,
                       String listingType, long createdAt, String listingAssetType, String assetRef) {
            this(id, sellerUuid, sellerName, itemMaterial, itemSignature, itemData, quantity, unitPrice,
                    totalPrice, listingMode, wantedMaterial, wantedQuantity, listingType, createdAt,
                    listingAssetType, assetRef, DEFAULT_CURRENCY_ID);
        }

        public Listing {
            currencyId = normalizeCurrencyId(currencyId);
        }

        public boolean isBarter() { return "BARTER".equals(listingMode); }
        public boolean isProperty() { return "PROPERTY".equals(listingAssetType); }
        public boolean isCashCurrency() { return DEFAULT_CURRENCY_ID.equals(currencyId); }

        public ItemStack toItemStack() {
            if (itemData == null || itemData.length == 0) {
                return new ItemStack(org.bukkit.Material.valueOf(itemMaterial));
            }
            ItemStack template = ItemStack.deserializeBytes(itemData);
            template.setAmount(1);
            return template;
        }

        /** The database keeps this column for reporting, but active listings use remaining stock. */
        public double totalPrice() { return unitPrice * quantity; }

        public double currentTotalPrice() { return totalPrice(); }
    }
}
