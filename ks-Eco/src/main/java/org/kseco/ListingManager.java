package org.kseco;

import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.*;

/**
 * 市场挂单管理器。
 * 所有方法均使用 try-with-resources 关闭 Connection，避免 SQLite WAL BUSY_SNAPSHOT。
 */
public final class ListingManager {

    private final KsEco plugin;

    public ListingManager(KsEco plugin) {
        this.plugin = plugin;
        createTable();
    }

    private void createTable() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_listings (
                        id TEXT PRIMARY KEY,
                        seller_uuid TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        item_data BLOB NOT NULL,
                        item_material TEXT NOT NULL,
                        item_signature TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        unit_price REAL NOT NULL,
                        total_price REAL NOT NULL,
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
        } catch (SQLException e) {
            plugin.getLogger().warning("创建挂单表失败: " + e.getMessage());
        }
    }

    // ==================== SELL 挂单（卖钱） ====================

    public Listing createListing(UUID sellerUuid, String sellerName, ItemStack item,
                                  int quantity, double unitPrice, String type) {
        return createListing(sellerUuid, sellerName, prepareListingItem(item), quantity, unitPrice, type);
    }

    public Listing createListing(UUID sellerUuid, String sellerName, PreparedListingItem item,
                                  int quantity, double unitPrice, String type) {
        return createListingInternal(sellerUuid, sellerName, item, quantity, unitPrice, type,
                "SELL", null, 0);
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
                "BARTER", wantedMaterial, wantedQuantity);
    }

    private Listing createListingInternal(UUID sellerUuid, String sellerName, PreparedListingItem item,
                                           int quantity, double unitPrice, String type,
                                           String listingMode, String wantedMaterial, int wantedQuantity) {
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
        double totalPrice = unitPrice * quantity;
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
                    "item_signature, quantity, unit_price, total_price, listing_type, listing_mode, " +
                    "wanted_material, wanted_quantity, created_at, expires_at, status) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, sellerUuid.toString());
                ps.setString(3, sellerName);
                ps.setBytes(4, itemData);
                ps.setString(5, material);
                ps.setString(6, signature);
                ps.setInt(7, quantity);
                ps.setDouble(8, unitPrice);
                ps.setDouble(9, totalPrice);
                ps.setString(10, type);
                ps.setString(11, listingMode);
                ps.setString(12, wantedMaterial);
                ps.setInt(13, wantedQuantity);
                ps.setLong(14, now);
                if (expires != null) ps.setLong(15, expires);
                else ps.setNull(15, Types.INTEGER);
                ps.setString(16, "ACTIVE");
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("创建挂单失败: " + e.getMessage());
            return null;
        }

        return new Listing(id, sellerUuid, sellerName, material, signature, itemData,
                quantity, unitPrice, totalPrice, listingMode, wantedMaterial, wantedQuantity, type, now,
                "ITEM", null);
    }

    // ==================== PROPERTY 挂单（卖商品房） ====================

    public Listing createPropertyListing(UUID sellerUuid, String sellerName, String houseId, double price) {
        if (houseId == null || houseId.isEmpty() || !Double.isFinite(price) || price <= 0) return null;
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ks_eco_listings (id, seller_uuid, seller_name, item_data, item_material, " +
                    "item_signature, quantity, unit_price, total_price, listing_type, listing_mode, " +
                    "wanted_material, wanted_quantity, created_at, expires_at, status, listing_asset_type, asset_ref) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, sellerUuid.toString());
                ps.setString(3, sellerName);
                ps.setBytes(4, new byte[0]);
                ps.setString(5, "AIR");
                ps.setString(6, "PROPERTY:" + houseId);
                ps.setInt(7, 1);
                ps.setDouble(8, price);
                ps.setDouble(9, price);
                ps.setString(10, "SELL");
                ps.setString(11, "SELL");
                ps.setNull(12, Types.VARCHAR);
                ps.setInt(13, 0);
                ps.setLong(14, now);
                ps.setNull(15, Types.INTEGER);
                ps.setString(16, "ACTIVE");
                ps.setString(17, "PROPERTY");
                ps.setString(18, houseId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("创建商品房挂单失败: " + e.getMessage());
            return null;
        }
        return new Listing(id, sellerUuid, sellerName, "AIR", "PROPERTY:" + houseId, new byte[0],
                1, price, price, "SELL", null, 0, "SELL", now, "PROPERTY", houseId);
    }

    public boolean hasActivePropertyListing(String houseId) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM ks_eco_listings WHERE status='ACTIVE' AND listing_asset_type='PROPERTY' AND asset_ref=?")) {
                ps.setString(1, houseId);
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
        List<Listing> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;

            StringBuilder sql = new StringBuilder(
                    "SELECT id, seller_uuid, seller_name, item_material, item_signature, item_data, " +
                    "quantity, unit_price, total_price, COALESCE(listing_mode,'SELL') as listing_mode, " +
                    "wanted_material, COALESCE(wanted_quantity,0) as wanted_quantity, " +
                    "listing_type, created_at, expires_at, " +
                    "COALESCE(listing_asset_type,'ITEM') as listing_asset_type, asset_ref " +
                    "FROM ks_eco_listings WHERE status='ACTIVE'");
            if (type != null && !type.isEmpty()) sql.append(" AND listing_type=?");
            if (listingMode != null && !listingMode.isEmpty()) sql.append(" AND COALESCE(listing_mode,'SELL')=?");
            sql.append(" ORDER BY unit_price ASC");

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
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
        List<Listing> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, seller_name, item_material, item_signature, item_data, " +
                    "quantity, unit_price, total_price, COALESCE(listing_mode,'SELL') as listing_mode, " +
                    "wanted_material, COALESCE(wanted_quantity,0) as wanted_quantity, " +
                    "listing_type, created_at, expires_at, " +
                    "COALESCE(listing_asset_type,'ITEM') as listing_asset_type, asset_ref " +
                    "FROM ks_eco_listings WHERE status='ACTIVE' ORDER BY created_at DESC LIMIT 200")) {
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
        List<Listing> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return result;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, seller_name, item_material, item_signature, item_data, " +
                    "quantity, unit_price, total_price, COALESCE(listing_mode,'SELL') as listing_mode, " +
                    "wanted_material, COALESCE(wanted_quantity,0) as wanted_quantity, " +
                    "listing_type, created_at, expires_at, " +
                    "COALESCE(listing_asset_type,'ITEM') as listing_asset_type, asset_ref " +
                    "FROM ks_eco_listings WHERE seller_uuid=? AND status='ACTIVE'")) {
                ps.setString(1, playerUuid.toString());
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
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, seller_uuid, seller_name, item_material, item_signature, item_data, " +
                    "quantity, unit_price, total_price, COALESCE(listing_mode,'SELL') as listing_mode, " +
                    "wanted_material, COALESCE(wanted_quantity,0) as wanted_quantity, " +
                    "listing_type, created_at, expires_at, " +
                    "COALESCE(listing_asset_type,'ITEM') as listing_asset_type, asset_ref " +
                    "FROM ks_eco_listings WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, listingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapListing(rs);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询挂单失败: " + e.getMessage());
        }
        return null;
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
                rs.getString("asset_ref")
        );
    }

    /** 取消挂单，并在同一事务内把剩余物品写入卖家暂存箱。 */
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
                                + "AND COALESCE(listing_asset_type,'ITEM')='ITEM'")) {
                    claim.setInt(1, quantity);
                    claim.setInt(2, quantity);
                    claim.setInt(3, quantity);
                    claim.setString(4, listing.id());
                    claim.setInt(5, quantity);
                    claim.setString(6, listing.sellerUuid().toString());
                    claim.setDouble(7, listing.unitPrice());
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
                    store.setString(6, "MARKET_PURCHASE:" + listing.id());
                    store.setLong(7, System.currentTimeMillis() / 1000);
                    store.executeUpdate();
                }
                conn.commit();
                return new PurchaseReservation(storageId, listing.id(), buyerUuid, quantity);
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
        if (reservation == null) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM ks_eco_storage WHERE id=? AND owner_uuid=?")) {
                    delete.setString(1, reservation.storageId());
                    delete.setString(2, reservation.buyerUuid().toString());
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

    public int activeListingCount() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) FROM ks_eco_listings WHERE status='ACTIVE'")) {
                return rs.next() ? rs.getInt(1) : 0;
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

    // ---- 数据类 ----

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
                                      UUID buyerUuid, int quantity) {}

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
            String assetRef
    ) {
        public boolean isBarter() { return "BARTER".equals(listingMode); }
        public boolean isProperty() { return "PROPERTY".equals(listingAssetType); }

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
