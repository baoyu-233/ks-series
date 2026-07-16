package org.kseco;

import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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
            st.execute("CREATE TABLE IF NOT EXISTS ks_eco_official_warehouse (id TEXT PRIMARY KEY, item_data BLOB NOT NULL, item_material TEXT NOT NULL, quantity INTEGER NOT NULL, seller_uuid TEXT NOT NULL, seller_name TEXT NOT NULL, listing_id TEXT, paid_price REAL NOT NULL, source TEXT NOT NULL, stored_at INTEGER NOT NULL)");
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
                                + "(id,item_data,item_material,quantity,seller_uuid,seller_name,listing_id,paid_price,source,stored_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
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
                        "DELETE FROM ks_eco_official_warehouse WHERE id=? AND listing_id=?")) {
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

    public boolean delete(String id) {
        if (id == null || id.isBlank()) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM ks_eco_official_warehouse WHERE id=?")) {
            ps.setString(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    public record Acquisition(String warehouseId, String listingId, UUID sellerUuid,
                              String sellerName, String material, int quantity,
                              double unitPrice, double payment) {}
}
