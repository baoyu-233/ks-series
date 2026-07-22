package org.kseco;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialWarehouseLiquidationManagerTest {

    @TempDir
    Path tempDirectory;

    private String jdbcUrl;
    private OfficialWarehouseLiquidationManager manager;

    @BeforeEach
    void setUp() throws Exception {
        jdbcUrl = "jdbc:sqlite:" + tempDirectory.resolve("liquidation.db").toAbsolutePath();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ks_eco_official_warehouse (
                        id TEXT PRIMARY KEY,
                        item_data BLOB NOT NULL,
                        item_material TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        seller_uuid TEXT NOT NULL,
                        seller_name TEXT NOT NULL,
                        listing_id TEXT,
                        paid_price REAL NOT NULL,
                        source TEXT NOT NULL,
                        stored_at INTEGER NOT NULL
                    )
                    """);
        }
        manager = new OfficialWarehouseLiquidationManager(this::connection, Logger.getAnonymousLogger());
    }

    @Test
    void createsExactlyOneFiniteLotPerWarehouseRow() throws Exception {
        insertWarehouse("warehouse-1", 12, 60.0, new byte[]{1, 2, 3});

        var created = manager.createLot("create:one", "warehouse-1", 7.5, 0, 0);
        var replay = manager.createLot("create:one", "warehouse-1", 7.5, 0, 0);
        var duplicateWarehouse = manager.createLot("create:two", "warehouse-1", 8.0, 0, 0);

        assertEquals(OfficialWarehouseLiquidationManager.Outcome.CREATED, created.outcome());
        assertEquals(OfficialWarehouseLiquidationManager.Outcome.REPLAYED, replay.outcome());
        assertEquals(created.lot().id(), replay.lot().id());
        assertEquals(OfficialWarehouseLiquidationManager.Outcome.CONFLICT, duplicateWarehouse.outcome());
        assertEquals(12, created.lot().initialQuantity());
        assertEquals(12, created.lot().remainingQuantity());
        assertEquals(60.0, created.lot().costBasis());
        assertEquals("CASH", created.lot().currencyId());
        assertArrayEquals(new byte[]{1, 2, 3}, created.lot().itemData());
        assertEquals(1, countRows("ks_eco_official_liquidation_lots"));
        assertEquals("LIQUIDATION", warehouseState("warehouse-1"));
    }

    @Test
    void refusesWarehouseRowsWhoseOfficialCashPayoutIsStillPending() throws Exception {
        insertWarehouse("warehouse-pending", 2, 10.0, new byte[]{3});
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE ks_eco_official_warehouse SET disposition_state='PENDING_SETTLEMENT' WHERE id=?")) {
            statement.setString(1, "warehouse-pending");
            statement.executeUpdate();
        }

        var result = manager.createLot("create:pending", "warehouse-pending", 6.0, 0, 0);

        assertEquals(OfficialWarehouseLiquidationManager.Outcome.NOT_AVAILABLE, result.outcome());
        assertEquals(0, countRows("ks_eco_official_liquidation_lots"));
        assertEquals("PENDING_SETTLEMENT", warehouseState("warehouse-pending"));
    }

    @Test
    void reservationReplayCannotDecrementOrCopyStockTwice() throws Exception {
        insertWarehouse("warehouse-2", 5, 25.0, new byte[]{4, 5, 6});
        var lot = manager.createLot("create:stock", "warehouse-2", 8.0, 0, 0).lot();
        UUID buyer = UUID.randomUUID();

        var first = manager.reservePurchase("purchase:one", lot.id(), buyer, 3);
        byte[] exposed = first.purchase().itemData();
        exposed[0] = 99;
        var replay = manager.reservePurchase("purchase:one", lot.id(), buyer, 3);
        var mismatch = manager.reservePurchase("purchase:one", lot.id(), buyer, 2);
        var oversell = manager.reservePurchase("purchase:two", lot.id(), buyer, 3);

        assertEquals(OfficialWarehouseLiquidationManager.Outcome.RESERVED, first.outcome());
        assertEquals(OfficialWarehouseLiquidationManager.Outcome.REPLAYED, replay.outcome());
        assertEquals(OfficialWarehouseLiquidationManager.Outcome.CONFLICT, mismatch.outcome());
        assertEquals(OfficialWarehouseLiquidationManager.Outcome.INSUFFICIENT_STOCK, oversell.outcome());
        assertEquals(2, manager.findLot(lot.id()).remainingQuantity());
        assertEquals(24.0, first.purchase().totalPrice());
        assertArrayEquals(new byte[]{4, 5, 6}, replay.purchase().itemData());
        assertEquals(1, countRows("ks_eco_official_liquidation_purchases"));
    }

    @Test
    void failedCashSettlementRestoresStockIdempotently() throws Exception {
        insertWarehouse("warehouse-3", 7, 35.0, new byte[]{7});
        var lot = manager.createLot("create:restore", "warehouse-3", 9.0, 0, 0).lot();
        UUID buyer = UUID.randomUUID();
        manager.reservePurchase("purchase:restore", lot.id(), buyer, 4);

        var released = manager.releaseUnpaid("purchase:restore");
        var replay = manager.releaseUnpaid("purchase:restore");

        assertEquals(OfficialWarehouseLiquidationManager.Outcome.RELEASED, released.outcome());
        assertEquals(OfficialWarehouseLiquidationManager.Outcome.REPLAYED, replay.outcome());
        assertEquals(7, manager.findLot(lot.id()).remainingQuantity());
        assertEquals("OPEN", manager.findLot(lot.id()).status());
        assertEquals("LIQUIDATION", warehouseState("warehouse-3"));
        assertTrue(manager.listRecoverablePurchases(10).isEmpty());
    }

    @Test
    void paidStockRequiresRefundBeforeRelease() throws Exception {
        insertWarehouse("warehouse-4", 4, 20.0, new byte[]{8});
        var lot = manager.createLot("create:refund", "warehouse-4", 9.0, 0, 0).lot();
        manager.reservePurchase("purchase:refund", lot.id(), UUID.randomUUID(), 4);
        assertEquals(OfficialWarehouseLiquidationManager.Outcome.PAID,
                manager.markPaid("purchase:refund").outcome());

        var unsafeRelease = manager.releaseUnpaid("purchase:refund");
        var refundedRelease = manager.releaseAfterRefund("purchase:refund");

        assertEquals(OfficialWarehouseLiquidationManager.Outcome.CONFLICT, unsafeRelease.outcome());
        assertEquals(OfficialWarehouseLiquidationManager.Outcome.RELEASED, refundedRelease.outcome());
        assertEquals(4, manager.findLot(lot.id()).remainingQuantity());
    }

    @Test
    void warehouseIsConsumedOnlyAfterEveryReservedUnitIsDelivered() throws Exception {
        insertWarehouse("warehouse-5", 5, 25.0, new byte[]{9});
        var lot = manager.createLot("create:complete", "warehouse-5", 10.0, 0, 0).lot();
        manager.reservePurchase("purchase:a", lot.id(), UUID.randomUUID(), 2);
        manager.reservePurchase("purchase:b", lot.id(), UUID.randomUUID(), 3);

        manager.markPaid("purchase:a");
        var firstDelivery = manager.completeDelivery("purchase:a");
        assertEquals(OfficialWarehouseLiquidationManager.Outcome.DELIVERED, firstDelivery.outcome());
        assertEquals("LIQUIDATION", warehouseState("warehouse-5"));
        assertEquals(1, manager.listRecoverablePurchases(10).size());

        manager.markPaid("purchase:b");
        var finalDelivery = manager.completeDelivery("purchase:b");
        var replay = manager.completeDelivery("purchase:b");

        assertEquals(OfficialWarehouseLiquidationManager.Outcome.DELIVERED, finalDelivery.outcome());
        assertEquals(OfficialWarehouseLiquidationManager.Outcome.REPLAYED, replay.outcome());
        assertEquals("CONSUMED", warehouseState("warehouse-5"));
        assertEquals(0, manager.findLot(lot.id()).remainingQuantity());
        assertEquals("EXHAUSTED", manager.findLot(lot.id()).status());
        assertFalse(manager.findPurchase("purchase:b").itemData().length == 0);
        assertTrue(manager.listRecoverablePurchases(10).isEmpty());
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void insertWarehouse(String id, int quantity, double paidPrice, byte[] itemData) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ks_eco_official_warehouse
                (id,item_data,item_material,quantity,seller_uuid,seller_name,listing_id,paid_price,source,stored_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, id);
            statement.setBytes(2, itemData);
            statement.setString(3, "IRON_INGOT");
            statement.setInt(4, quantity);
            statement.setString(5, UUID.randomUUID().toString());
            statement.setString(6, "seller");
            statement.setString(7, "listing-" + id);
            statement.setDouble(8, paidPrice);
            statement.setString(9, "MARKET_SWEEP");
            statement.setLong(10, System.currentTimeMillis() / 1000);
            statement.executeUpdate();
        }
    }

    private int countRows(String table) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private String warehouseState(String id) throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT disposition_state FROM ks_eco_official_warehouse WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }
}
