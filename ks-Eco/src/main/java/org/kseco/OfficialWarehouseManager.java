package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.kseco.database.PortableSqlMutation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable extended storage for items acquired by the official market buyer. */
public final class OfficialWarehouseManager {
    private final KsEco plugin;

    public OfficialWarehouseManager(KsEco plugin) {
        this.plugin = plugin;
        createTable();
    }

    private void createTable() {
        try (Connection conn = plugin.ksCore().dataStore().getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ks_eco_official_warehouse (id VARCHAR(64) PRIMARY KEY, item_data BLOB NOT NULL, item_material TEXT NOT NULL, quantity INTEGER NOT NULL, seller_uuid TEXT NOT NULL, seller_name TEXT NOT NULL, listing_id TEXT, paid_price REAL NOT NULL, source TEXT NOT NULL, stored_at INTEGER NOT NULL, disposition_state TEXT NOT NULL DEFAULT 'AVAILABLE', disposition_version INTEGER NOT NULL DEFAULT 0)");
            try {
                st.execute("ALTER TABLE ks_eco_official_warehouse ADD COLUMN disposition_state TEXT NOT NULL DEFAULT 'AVAILABLE'");
            } catch (SQLException ignored) {
            }
            try {
                st.execute("ALTER TABLE ks_eco_official_warehouse ADD COLUMN disposition_version INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Official warehouse initialization failed: " + e.getMessage());
        }
    }

    public String store(ItemStack item, UUID sellerUuid, String sellerName, String listingId, double paidPrice, String source) {
        if (item == null || item.getType().isAir() || sellerUuid == null) return null;
        ItemStack snapshot = item.clone();
        String id = UUID.randomUUID().toString();
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO ks_eco_official_warehouse (id,item_data,item_material,quantity,seller_uuid,seller_name,listing_id,paid_price,source,stored_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setBytes(2, snapshot.serializeAsBytes());
            ps.setString(3, snapshot.getType().name());
            ps.setInt(4, snapshot.getAmount());
            ps.setString(5, sellerUuid.toString());
            ps.setString(6, sellerName == null ? "?" : sellerName);
            ps.setString(7, listingId);
            ps.setDouble(8, paidPrice);
            ps.setString(9, source);
            ps.setLong(10, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
            return id;
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().severe("Official warehouse storage failed for listing " + listingId + ": " + e.getMessage());
            return null;
        }
    }

    /** Atomically claims the full active listing and stores its immutable item bytes. Worker-only. */
    public Acquisition claimListing(ListingManager.Listing listing, double protectedUnitPrice) {
        if (listing == null || listing.isProperty() || listing.isBarter() || listing.quantity() <= 0
                || !Double.isFinite(protectedUnitPrice) || protectedUnitPrice <= 0.0
                || listing.unitPrice() >= protectedUnitPrice) return null;
        double payment = listing.totalPrice();
        if (!Double.isFinite(payment) || payment <= 0.0) return null;

        String warehouseId = UUID.randomUUID().toString();
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement claim = conn.prepareStatement(
                        "UPDATE ks_eco_listings SET quantity=0,total_price=0,status='FILLED' "
                                + "WHERE id=? AND status='ACTIVE' AND quantity=? AND unit_price=? "
                                + "AND seller_uuid=? AND COALESCE(listing_mode,'SELL')='SELL' "
                                + "AND COALESCE(listing_asset_type,'ITEM')='ITEM'")) {
                    claim.setString(1, listing.id());
                    claim.setInt(2, listing.quantity());
                    claim.setDouble(3, listing.unitPrice());
                    claim.setString(4, listing.sellerUuid().toString());
                    if (claim.executeUpdate() != 1) {
                        conn.rollback();
                        return null;
                    }
                }
                try (PreparedStatement store = conn.prepareStatement(
                        "INSERT INTO ks_eco_official_warehouse "
                                + "(id,item_data,item_material,quantity,seller_uuid,seller_name,listing_id,paid_price,source,stored_at,disposition_state) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING_SETTLEMENT')")) {
                    store.setString(1, warehouseId);
                    store.setBytes(2, listing.itemData());
                    store.setString(3, listing.itemMaterial());
                    store.setInt(4, listing.quantity());
                    store.setString(5, listing.sellerUuid().toString());
                    store.setString(6, listing.sellerName() == null ? "?" : listing.sellerName());
                    store.setString(7, listing.id());
                    store.setDouble(8, payment);
                    store.setString(9, "MARKET_SWEEP");
                    store.setLong(10, System.currentTimeMillis() / 1000);
                    store.executeUpdate();
                }
                conn.commit();
                return new Acquisition(warehouseId, listing.id(), listing.sellerUuid(),
                        listing.sellerName(), listing.itemMaterial(), listing.quantity(),
                        listing.unitPrice(), payment);
            } catch (SQLException exception) {
                conn.rollback();
                throw exception;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Official listing claim failed for " + listing.id()
                    + ": " + exception.getMessage());
            return null;
        }
    }

    /** Removes a failed acquisition and restores exactly the quantity claimed by it. Worker-only. */
    public boolean rollbackAcquisition(Acquisition acquisition) {
        if (acquisition == null) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM ks_eco_official_warehouse WHERE id=? AND listing_id=? "
                                + "AND disposition_state='PENDING_SETTLEMENT'")) {
                    delete.setString(1, acquisition.warehouseId());
                    delete.setString(2, acquisition.listingId());
                    if (delete.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                try (PreparedStatement restore = conn.prepareStatement(
                        "UPDATE ks_eco_listings SET quantity=?,total_price=unit_price*?,status='ACTIVE' "
                                + "WHERE id=? AND status='FILLED' AND quantity=0")) {
                    restore.setInt(1, acquisition.quantity());
                    restore.setInt(2, acquisition.quantity());
                    restore.setString(3, acquisition.listingId());
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
            plugin.getLogger().severe("Official acquisition rollback failed for "
                    + acquisition.listingId() + ": " + exception.getMessage());
            return false;
        }
    }

    /** Opens a newly acquired row for liquidation only after the external CASH payout succeeded. Worker-only. */
    public boolean markAcquisitionPaid(Acquisition acquisition) {
        if (acquisition == null) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE ks_eco_official_warehouse "
                             + "SET disposition_state='AVAILABLE',disposition_version=disposition_version+1 "
                             + "WHERE id=? AND listing_id=? AND disposition_state='PENDING_SETTLEMENT'")) {
            ps.setString(1, acquisition.warehouseId());
            ps.setString(2, acquisition.listingId());
            if (ps.executeUpdate() == 1) return true;
            try (PreparedStatement existing = conn.prepareStatement(
                    "SELECT disposition_state FROM ks_eco_official_warehouse WHERE id=? AND listing_id=?")) {
                existing.setString(1, acquisition.warehouseId());
                existing.setString(2, acquisition.listingId());
                try (ResultSet rs = existing.executeQuery()) {
                    return rs.next() && "AVAILABLE".equals(rs.getString(1));
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Official acquisition settlement finalization failed for "
                    + acquisition.listingId() + ": " + exception.getMessage());
            return false;
        }
    }

    public boolean delete(String id) {
        if (id == null || id.isBlank()) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM ks_eco_official_warehouse WHERE id=? AND disposition_state='AVAILABLE'")) {
            ps.setString(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Worker-only immutable page read for the administrator inventory GUI. */
    public WarehousePage loadPage(int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(45, limit));
        List<WarehouseSnapshot> rows = new ArrayList<>();
        int total = 0;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return new WarehousePage(List.of(), 0);
            try (Statement count = conn.createStatement();
                 ResultSet rs = count.executeQuery("SELECT COUNT(*) FROM ks_eco_official_warehouse")) {
                if (rs.next()) total = rs.getInt(1);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id,item_data,item_material,quantity,seller_uuid,seller_name,listing_id,paid_price,source,stored_at "
                            + "FROM ks_eco_official_warehouse ORDER BY stored_at DESC,id LIMIT ? OFFSET ?")) {
                ps.setInt(1, safeLimit);
                ps.setInt(2, safeOffset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(mapSnapshot(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Official warehouse page load failed: " + e.getMessage());
        }
        return new WarehousePage(List.copyOf(rows), total);
    }

    /** Server-thread-only ItemStack materialization. */
    public WarehouseItem materialize(WarehouseSnapshot snapshot) {
        if (snapshot == null) return null;
        ItemStack item = null;
        try {
            if (snapshot.itemData().length > 0) item = ItemStack.deserializeBytes(snapshot.itemData());
        } catch (RuntimeException ignored) {
        }
        if (item == null) {
            try {
                Material material = Material.valueOf(snapshot.itemMaterial());
                item = new ItemStack(material, Math.max(1, Math.min(snapshot.quantity(), material.getMaxStackSize())));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return new WarehouseItem(snapshot, item);
    }

    /** Worker-only atomic claim. The caller must restore the snapshot if main-thread delivery cannot complete. */
    public WarehouseSnapshot claim(String id) {
        if (id == null || id.isBlank()) return null;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return null;
            conn.setAutoCommit(false);
            try {
                WarehouseSnapshot snapshot;
                long dispositionVersion;
                try (PreparedStatement select = conn.prepareStatement(
                        "SELECT id,item_data,item_material,quantity,seller_uuid,seller_name,listing_id,paid_price,source,stored_at "
                                + ",disposition_version FROM ks_eco_official_warehouse "
                                + "WHERE id=? AND disposition_state='AVAILABLE'")) {
                    select.setString(1, id);
                    try (ResultSet rs = select.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return null;
                        }
                        snapshot = mapSnapshot(rs);
                        dispositionVersion = rs.getLong("disposition_version");
                    }
                }
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM ks_eco_official_warehouse WHERE id=? AND disposition_state='AVAILABLE' "
                                + "AND disposition_version=?")) {
                    delete.setString(1, id);
                    delete.setLong(2, dispositionVersion);
                    if (delete.executeUpdate() != 1) {
                        conn.rollback();
                        return null;
                    }
                }
                conn.commit();
                return snapshot;
            } catch (SQLException e) {
                conn.rollback();
                plugin.getLogger().warning("Official warehouse claim failed: " + e.getMessage());
                return null;
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Official warehouse claim failed: " + e.getMessage());
            return null;
        }
    }

    /** Worker-only idempotent restoration for a claimed row that could not be delivered. */
    public boolean restore(WarehouseSnapshot snapshot) {
        if (snapshot == null) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            PortableSqlMutation.insertIfAbsent(conn,
                    "SELECT 1 FROM ks_eco_official_warehouse WHERE id=?",
                    exists -> exists.setString(1, snapshot.id()),
                    "INSERT INTO ks_eco_official_warehouse "
                            + "(id,item_data,item_material,quantity,seller_uuid,seller_name,listing_id,paid_price,source,stored_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)", insert -> {
                        insert.setString(1, snapshot.id());
                        insert.setBytes(2, snapshot.itemData());
                        insert.setString(3, snapshot.itemMaterial());
                        insert.setInt(4, snapshot.quantity());
                        insert.setString(5, snapshot.sellerUuid());
                        insert.setString(6, snapshot.sellerName());
                        insert.setString(7, snapshot.listingId());
                        insert.setDouble(8, snapshot.paidPrice());
                        insert.setString(9, snapshot.source());
                        insert.setLong(10, snapshot.storedAt());
                    });
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Official warehouse restore failed for " + snapshot.id() + ": " + e.getMessage());
            return false;
        }
    }

    private boolean exists(String id) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ks_eco_official_warehouse WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private static WarehouseSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        return new WarehouseSnapshot(
                rs.getString("id"), rs.getBytes("item_data"), rs.getString("item_material"),
                rs.getInt("quantity"), rs.getString("seller_uuid"), rs.getString("seller_name"),
                rs.getString("listing_id"), rs.getDouble("paid_price"), rs.getString("source"),
                rs.getLong("stored_at")
        );
    }

    public record Acquisition(String warehouseId, String listingId, UUID sellerUuid,
                              String sellerName, String material, int quantity,
                              double unitPrice, double payment) {}

    public record WarehousePage(List<WarehouseSnapshot> rows, int total) {
        public WarehousePage {
            rows = List.copyOf(rows);
        }
    }

    public record WarehouseSnapshot(String id, byte[] itemData, String itemMaterial, int quantity,
                                    String sellerUuid, String sellerName, String listingId,
                                    double paidPrice, String source, long storedAt) {
        public WarehouseSnapshot {
            itemData = itemData == null ? new byte[0] : itemData.clone();
        }

        @Override
        public byte[] itemData() {
            return itemData.clone();
        }
    }

    public record WarehouseItem(WarehouseSnapshot snapshot, ItemStack item) {}
}
