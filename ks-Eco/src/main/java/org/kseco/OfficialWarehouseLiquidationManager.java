package org.kseco;

import org.kseco.database.BusinessSchemaDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Finite, warehouse-backed liquidation lots.
 *
 * <p>Runtime APIs perform blocking JDBC work and belong on the database lane. Item bytes are immutable snapshots;
 * callers must return to the server thread before decoding them into Bukkit objects or settling CASH through Vault.
 */
public final class OfficialWarehouseLiquidationManager {

    public static final String CURRENCY_ID = "CASH";

    private static final String WAREHOUSE_AVAILABLE = "AVAILABLE";
    private static final String WAREHOUSE_LIQUIDATION = "LIQUIDATION";
    private static final String WAREHOUSE_CONSUMED = "CONSUMED";
    private static final String LOT_OPEN = "OPEN";
    private static final String LOT_EXHAUSTED = "EXHAUSTED";
    private static final String PURCHASE_RESERVED = "RESERVED";
    private static final String PURCHASE_PAID = "PAID";
    private static final String PURCHASE_DELIVERED = "DELIVERED";
    private static final String PURCHASE_RELEASED = "RELEASED";

    private final ConnectionFactory connections;
    private final Logger logger;

    public OfficialWarehouseLiquidationManager(KsEco plugin) {
        this(() -> plugin.ksCore().dataStore().getConnection(), plugin.getLogger());
    }

    OfficialWarehouseLiquidationManager(ConnectionFactory connections, Logger logger) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.logger = Objects.requireNonNull(logger, "logger");
        initializeSchema();
    }

    private void initializeSchema() {
        try (Connection connection = openConnection()) {
            var dialect = BusinessSchemaDialect.detect(connection);
            String binary = BusinessSchemaDialect.binaryType(dialect);
            String floatingPoint = BusinessSchemaDialect.floatingPointType(dialect);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_official_warehouse (
                        id VARCHAR(128) PRIMARY KEY,
                        item_data %s NOT NULL,
                        item_material VARCHAR(128) NOT NULL,
                        quantity INTEGER NOT NULL,
                        seller_uuid VARCHAR(36) NOT NULL,
                        seller_name VARCHAR(64) NOT NULL,
                        listing_id VARCHAR(128),
                        paid_price %s NOT NULL,
                        source VARCHAR(64) NOT NULL,
                        stored_at BIGINT NOT NULL,
                        disposition_state VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
                        disposition_version INTEGER NOT NULL DEFAULT 0
                    )
                    """.formatted(binary, floatingPoint));
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_official_liquidation_lots (
                        id VARCHAR(128) PRIMARY KEY,
                        create_operation_id VARCHAR(128) NOT NULL UNIQUE,
                        warehouse_id VARCHAR(128) NOT NULL UNIQUE,
                        item_data %s NOT NULL,
                        item_material VARCHAR(128) NOT NULL,
                        initial_quantity INTEGER NOT NULL,
                        remaining_quantity INTEGER NOT NULL,
                        cost_basis %s NOT NULL,
                        unit_price %s NOT NULL,
                        currency_id VARCHAR(32) NOT NULL DEFAULT 'CASH',
                        status VARCHAR(32) NOT NULL,
                        version INTEGER NOT NULL DEFAULT 0,
                        starts_at BIGINT NOT NULL DEFAULT 0,
                        ends_at BIGINT NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        CHECK(initial_quantity > 0),
                        CHECK(remaining_quantity >= 0),
                        CHECK(remaining_quantity <= initial_quantity),
                        CHECK(cost_basis >= 0),
                        CHECK(unit_price > 0),
                        CHECK(currency_id = 'CASH')
                    )
                    """.formatted(binary, floatingPoint, floatingPoint));
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS ks_eco_official_liquidation_purchases (
                        operation_id VARCHAR(128) PRIMARY KEY,
                        lot_id VARCHAR(128) NOT NULL,
                        buyer_uuid VARCHAR(36) NOT NULL,
                        item_data %s NOT NULL,
                        item_material VARCHAR(128) NOT NULL,
                        quantity INTEGER NOT NULL,
                        unit_price %s NOT NULL,
                        total_price %s NOT NULL,
                        currency_id VARCHAR(32) NOT NULL DEFAULT 'CASH',
                        status VARCHAR(32) NOT NULL,
                        version INTEGER NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        CHECK(quantity > 0),
                        CHECK(unit_price > 0),
                        CHECK(total_price > 0),
                        CHECK(currency_id = 'CASH')
                    )
                    """.formatted(binary, floatingPoint, floatingPoint));
            }
            BusinessSchemaDialect.addColumnIfMissing(connection, "ks_eco_official_warehouse",
                    "disposition_state", "VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE'");
            BusinessSchemaDialect.addColumnIfMissing(connection, "ks_eco_official_warehouse",
                    "disposition_version", "INTEGER NOT NULL DEFAULT 0");
            BusinessSchemaDialect.createIndexIfMissing(connection, "idx_eco_liquidation_lot_status",
                    "ks_eco_official_liquidation_lots", "status", "starts_at", "ends_at");
            BusinessSchemaDialect.createIndexIfMissing(connection, "idx_eco_liquidation_purchase_status",
                    "ks_eco_official_liquidation_purchases", "status", "updated_at");
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE ks_eco_official_warehouse SET disposition_state='AVAILABLE' "
                        + "WHERE disposition_state IS NULL OR TRIM(disposition_state)=''");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Official warehouse liquidation schema initialization failed", exception);
        }
    }

    public LotResult createLot(String operationId, String warehouseId, double unitPrice,
                               long startsAt, long endsAt) {
        String normalizedOperation = normalizeId(operationId);
        String normalizedWarehouse = normalizeId(warehouseId);
        if (normalizedOperation == null || normalizedWarehouse == null
                || !Double.isFinite(unitPrice) || unitPrice <= 0.0
                || startsAt < 0 || endsAt < 0 || (endsAt > 0 && endsAt <= startsAt)) {
            return LotResult.failure(Outcome.INVALID, "Invalid liquidation lot parameters");
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Lot existingOperation = findLotByCreateOperation(connection, normalizedOperation);
                if (existingOperation != null) {
                    connection.rollback();
                    return sameCreateRequest(existingOperation, normalizedWarehouse, unitPrice, startsAt, endsAt)
                            ? new LotResult(Outcome.REPLAYED, existingOperation, null)
                            : LotResult.failure(Outcome.CONFLICT, "Create operation was already used with different parameters");
                }

                Lot existingWarehouse = findLotByWarehouse(connection, normalizedWarehouse);
                if (existingWarehouse != null) {
                    connection.rollback();
                    return new LotResult(Outcome.CONFLICT, existingWarehouse,
                            "Warehouse row already belongs to a liquidation lot");
                }

                WarehouseRow warehouse = findWarehouse(connection, normalizedWarehouse);
                if (warehouse == null) {
                    connection.rollback();
                    return LotResult.failure(Outcome.NOT_FOUND, "Warehouse row not found");
                }
                if (!WAREHOUSE_AVAILABLE.equals(warehouse.dispositionState())) {
                    connection.rollback();
                    return LotResult.failure(Outcome.NOT_AVAILABLE, "Warehouse row is not available");
                }
                if (warehouse.quantity() <= 0 || warehouse.itemData().length == 0
                        || warehouse.itemMaterial().isBlank()
                        || !Double.isFinite(warehouse.paidPrice()) || warehouse.paidPrice() < 0.0) {
                    connection.rollback();
                    return LotResult.failure(Outcome.INVALID, "Warehouse row is not liquidatable");
                }

                long now = now();
                String lotId = "lot_" + UUID.randomUUID().toString().replace("-", "");
                try (PreparedStatement claim = connection.prepareStatement(
                        "UPDATE ks_eco_official_warehouse SET disposition_state=?,disposition_version=disposition_version+1 "
                                + "WHERE id=? AND disposition_state=? AND disposition_version=?")) {
                    claim.setString(1, WAREHOUSE_LIQUIDATION);
                    claim.setString(2, warehouse.id());
                    claim.setString(3, WAREHOUSE_AVAILABLE);
                    claim.setLong(4, warehouse.dispositionVersion());
                    if (claim.executeUpdate() != 1) {
                        connection.rollback();
                        return LotResult.failure(Outcome.CONFLICT, "Warehouse row changed while creating the lot");
                    }
                }

                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO ks_eco_official_liquidation_lots
                        (id,create_operation_id,warehouse_id,item_data,item_material,initial_quantity,
                         remaining_quantity,cost_basis,unit_price,currency_id,status,version,starts_at,ends_at,
                         created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,'CASH','OPEN',0,?,?,?,?)
                        """)) {
                    insert.setString(1, lotId);
                    insert.setString(2, normalizedOperation);
                    insert.setString(3, warehouse.id());
                    insert.setBytes(4, warehouse.itemData());
                    insert.setString(5, warehouse.itemMaterial());
                    insert.setInt(6, warehouse.quantity());
                    insert.setInt(7, warehouse.quantity());
                    insert.setDouble(8, warehouse.paidPrice());
                    insert.setDouble(9, unitPrice);
                    insert.setLong(10, startsAt);
                    insert.setLong(11, endsAt);
                    insert.setLong(12, now);
                    insert.setLong(13, now);
                    insert.executeUpdate();
                }
                connection.commit();
                return new LotResult(Outcome.CREATED, findLotById(lotId), null);
            } catch (SQLException exception) {
                rollback(connection);
                logger.warning("Create official liquidation lot failed: " + exception.getMessage());
                return LotResult.failure(Outcome.FAILED, "Database write failed");
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (SQLException exception) {
            logger.warning("Create official liquidation lot failed: " + exception.getMessage());
            return LotResult.failure(Outcome.FAILED, "Database unavailable");
        }
    }

    public PurchaseResult reservePurchase(String operationId, String lotId, UUID buyerUuid, int quantity) {
        String normalizedOperation = normalizeId(operationId);
        String normalizedLot = normalizeId(lotId);
        if (normalizedOperation == null || normalizedLot == null || buyerUuid == null || quantity <= 0) {
            return PurchaseResult.failure(Outcome.INVALID, "Invalid purchase reservation parameters");
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Purchase existing = findPurchase(connection, normalizedOperation);
                if (existing != null) {
                    connection.rollback();
                    return sameReservation(existing, normalizedLot, buyerUuid, quantity)
                            ? new PurchaseResult(Outcome.REPLAYED, existing, null)
                            : PurchaseResult.failure(Outcome.CONFLICT,
                                    "Purchase operation was already used with different parameters");
                }

                Lot lot = findLot(connection, normalizedLot);
                if (lot == null) {
                    connection.rollback();
                    return PurchaseResult.failure(Outcome.NOT_FOUND, "Liquidation lot not found");
                }
                long now = now();
                if (!lot.activeAt(now)) {
                    connection.rollback();
                    return PurchaseResult.failure(Outcome.NOT_AVAILABLE, "Liquidation lot is not open");
                }
                if (quantity > lot.remainingQuantity()) {
                    connection.rollback();
                    return PurchaseResult.failure(Outcome.INSUFFICIENT_STOCK, "Insufficient liquidation stock");
                }
                double totalPrice = lot.unitPrice() * quantity;
                if (!Double.isFinite(totalPrice) || totalPrice <= 0.0) {
                    connection.rollback();
                    return PurchaseResult.failure(Outcome.INVALID, "Invalid CASH total");
                }

                try (PreparedStatement reserve = connection.prepareStatement("""
                        UPDATE ks_eco_official_liquidation_lots
                        SET remaining_quantity=remaining_quantity-?,
                            status=CASE WHEN remaining_quantity=? THEN 'EXHAUSTED' ELSE 'OPEN' END,
                            version=version+1,updated_at=?
                        WHERE id=? AND status='OPEN' AND version=? AND remaining_quantity>=?
                        """)) {
                    reserve.setInt(1, quantity);
                    reserve.setInt(2, quantity);
                    reserve.setLong(3, now);
                    reserve.setString(4, lot.id());
                    reserve.setLong(5, lot.version());
                    reserve.setInt(6, quantity);
                    if (reserve.executeUpdate() != 1) {
                        connection.rollback();
                        return PurchaseResult.failure(Outcome.CONFLICT, "Liquidation stock changed during reservation");
                    }
                }

                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO ks_eco_official_liquidation_purchases
                        (operation_id,lot_id,buyer_uuid,item_data,item_material,quantity,unit_price,total_price,
                         currency_id,status,version,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,'CASH','RESERVED',0,?,?)
                        """)) {
                    insert.setString(1, normalizedOperation);
                    insert.setString(2, lot.id());
                    insert.setString(3, buyerUuid.toString());
                    insert.setBytes(4, lot.itemData());
                    insert.setString(5, lot.itemMaterial());
                    insert.setInt(6, quantity);
                    insert.setDouble(7, lot.unitPrice());
                    insert.setDouble(8, totalPrice);
                    insert.setLong(9, now);
                    insert.setLong(10, now);
                    insert.executeUpdate();
                }
                connection.commit();
                return new PurchaseResult(Outcome.RESERVED, findPurchase(normalizedOperation), null);
            } catch (SQLException exception) {
                rollback(connection);
                logger.warning("Reserve official liquidation purchase failed: " + exception.getMessage());
                return PurchaseResult.failure(Outcome.FAILED, "Database write failed");
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (SQLException exception) {
            logger.warning("Reserve official liquidation purchase failed: " + exception.getMessage());
            return PurchaseResult.failure(Outcome.FAILED, "Database unavailable");
        }
    }

    /** Marks a successful server-thread CASH withdrawal before item delivery. */
    public PurchaseResult markPaid(String operationId) {
        return transitionPurchase(operationId, PURCHASE_RESERVED, PURCHASE_PAID, Outcome.PAID);
    }

    /** Marks item delivery complete. Replayed calls are harmless and may finish warehouse consumption. */
    public PurchaseResult completeDelivery(String operationId) {
        String normalizedOperation = normalizeId(operationId);
        if (normalizedOperation == null) {
            return PurchaseResult.failure(Outcome.INVALID, "Invalid purchase operation ID");
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Purchase purchase = findPurchase(connection, normalizedOperation);
                if (purchase == null) {
                    connection.rollback();
                    return PurchaseResult.failure(Outcome.NOT_FOUND, "Purchase reservation not found");
                }
                if (PURCHASE_DELIVERED.equals(purchase.status())) {
                    finalizeConsumedWarehouse(connection, purchase.lotId());
                    connection.commit();
                    return new PurchaseResult(Outcome.REPLAYED, purchase, null);
                }
                if (!PURCHASE_PAID.equals(purchase.status())) {
                    connection.rollback();
                    return PurchaseResult.failure(Outcome.CONFLICT, "Purchase must be PAID before delivery");
                }
                long now = now();
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE ks_eco_official_liquidation_purchases "
                                + "SET status='DELIVERED',version=version+1,updated_at=? "
                                + "WHERE operation_id=? AND status='PAID' AND version=?")) {
                    update.setLong(1, now);
                    update.setString(2, purchase.operationId());
                    update.setLong(3, purchase.version());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return PurchaseResult.failure(Outcome.CONFLICT, "Purchase changed during delivery completion");
                    }
                }
                finalizeConsumedWarehouse(connection, purchase.lotId());
                connection.commit();
                return new PurchaseResult(Outcome.DELIVERED, findPurchase(normalizedOperation), null);
            } catch (SQLException exception) {
                rollback(connection);
                logger.warning("Complete official liquidation delivery failed: " + exception.getMessage());
                return PurchaseResult.failure(Outcome.FAILED, "Database write failed");
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (SQLException exception) {
            logger.warning("Complete official liquidation delivery failed: " + exception.getMessage());
            return PurchaseResult.failure(Outcome.FAILED, "Database unavailable");
        }
    }

    /** Releases stock after CASH withdrawal failed or was never attempted. */
    public PurchaseResult releaseUnpaid(String operationId) {
        return releasePurchase(operationId, PURCHASE_RESERVED);
    }

    /** Releases stock only after the caller has successfully refunded an already PAID purchase. */
    public PurchaseResult releaseAfterRefund(String operationId) {
        return releasePurchase(operationId, PURCHASE_PAID);
    }

    public Lot findLot(String lotId) {
        String normalized = normalizeId(lotId);
        if (normalized == null) return null;
        try (Connection connection = openConnection()) {
            return findLot(connection, normalized);
        } catch (SQLException exception) {
            logger.warning("Read official liquidation lot failed: " + exception.getMessage());
            return null;
        }
    }

    public Purchase findPurchase(String operationId) {
        String normalized = normalizeId(operationId);
        if (normalized == null) return null;
        try (Connection connection = openConnection()) {
            return findPurchase(connection, normalized);
        } catch (SQLException exception) {
            logger.warning("Read official liquidation purchase failed: " + exception.getMessage());
            return null;
        }
    }

    public List<Purchase> listRecoverablePurchases(int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        List<Purchase> purchases = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ks_eco_official_liquidation_purchases "
                        + "WHERE status IN ('RESERVED','PAID') ORDER BY updated_at,operation_id LIMIT ?")) {
            statement.setInt(1, safeLimit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) purchases.add(mapPurchase(result));
            }
        } catch (SQLException exception) {
            logger.warning("List recoverable official liquidation purchases failed: " + exception.getMessage());
        }
        return List.copyOf(purchases);
    }

    private PurchaseResult transitionPurchase(String operationId, String expectedStatus,
                                                String targetStatus, Outcome successOutcome) {
        String normalized = normalizeId(operationId);
        if (normalized == null) return PurchaseResult.failure(Outcome.INVALID, "Invalid purchase operation ID");
        try (Connection connection = openConnection()) {
            Purchase purchase = findPurchase(connection, normalized);
            if (purchase == null) return PurchaseResult.failure(Outcome.NOT_FOUND, "Purchase reservation not found");
            if (targetStatus.equals(purchase.status()) || PURCHASE_DELIVERED.equals(purchase.status())) {
                return new PurchaseResult(Outcome.REPLAYED, purchase, null);
            }
            if (!expectedStatus.equals(purchase.status())) {
                return PurchaseResult.failure(Outcome.CONFLICT,
                        "Purchase is " + purchase.status() + ", expected " + expectedStatus);
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ks_eco_official_liquidation_purchases SET status=?,version=version+1,updated_at=? "
                            + "WHERE operation_id=? AND status=? AND version=?")) {
                update.setString(1, targetStatus);
                update.setLong(2, now());
                update.setString(3, purchase.operationId());
                update.setString(4, expectedStatus);
                update.setLong(5, purchase.version());
                if (update.executeUpdate() != 1) {
                    return PurchaseResult.failure(Outcome.CONFLICT, "Purchase changed during transition");
                }
            }
            return new PurchaseResult(successOutcome, findPurchase(connection, normalized), null);
        } catch (SQLException exception) {
            logger.warning("Transition official liquidation purchase failed: " + exception.getMessage());
            return PurchaseResult.failure(Outcome.FAILED, "Database write failed");
        }
    }

    private PurchaseResult releasePurchase(String operationId, String expectedStatus) {
        String normalized = normalizeId(operationId);
        if (normalized == null) return PurchaseResult.failure(Outcome.INVALID, "Invalid purchase operation ID");
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Purchase purchase = findPurchase(connection, normalized);
                if (purchase == null) {
                    connection.rollback();
                    return PurchaseResult.failure(Outcome.NOT_FOUND, "Purchase reservation not found");
                }
                if (PURCHASE_RELEASED.equals(purchase.status())) {
                    connection.rollback();
                    return new PurchaseResult(Outcome.REPLAYED, purchase, null);
                }
                if (!expectedStatus.equals(purchase.status())) {
                    connection.rollback();
                    return PurchaseResult.failure(Outcome.CONFLICT,
                            "Purchase is " + purchase.status() + ", expected " + expectedStatus);
                }
                Lot lot = findLot(connection, purchase.lotId());
                if (lot == null) {
                    connection.rollback();
                    return PurchaseResult.failure(Outcome.NOT_FOUND, "Liquidation lot not found");
                }
                long now = now();
                try (PreparedStatement release = connection.prepareStatement(
                        "UPDATE ks_eco_official_liquidation_purchases "
                                + "SET status='RELEASED',version=version+1,updated_at=? "
                                + "WHERE operation_id=? AND status=? AND version=?")) {
                    release.setLong(1, now);
                    release.setString(2, purchase.operationId());
                    release.setString(3, expectedStatus);
                    release.setLong(4, purchase.version());
                    if (release.executeUpdate() != 1) {
                        connection.rollback();
                        return PurchaseResult.failure(Outcome.CONFLICT, "Purchase changed during release");
                    }
                }
                try (PreparedStatement restore = connection.prepareStatement("""
                        UPDATE ks_eco_official_liquidation_lots
                        SET remaining_quantity=remaining_quantity+?,status='OPEN',version=version+1,updated_at=?
                        WHERE id=? AND version=? AND status IN ('OPEN','EXHAUSTED')
                          AND remaining_quantity+?<=initial_quantity
                        """)) {
                    restore.setInt(1, purchase.quantity());
                    restore.setLong(2, now);
                    restore.setString(3, lot.id());
                    restore.setLong(4, lot.version());
                    restore.setInt(5, purchase.quantity());
                    if (restore.executeUpdate() != 1) {
                        connection.rollback();
                        return PurchaseResult.failure(Outcome.CONFLICT, "Liquidation stock changed during release");
                    }
                }
                connection.commit();
                return new PurchaseResult(Outcome.RELEASED, findPurchase(normalized), null);
            } catch (SQLException exception) {
                rollback(connection);
                logger.warning("Release official liquidation purchase failed: " + exception.getMessage());
                return PurchaseResult.failure(Outcome.FAILED, "Database write failed");
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (SQLException exception) {
            logger.warning("Release official liquidation purchase failed: " + exception.getMessage());
            return PurchaseResult.failure(Outcome.FAILED, "Database unavailable");
        }
    }

    private void finalizeConsumedWarehouse(Connection connection, String lotId) throws SQLException {
        Lot lot = findLot(connection, lotId);
        if (lot == null || lot.remainingQuantity() != 0) return;
        int pending;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM ks_eco_official_liquidation_purchases "
                        + "WHERE lot_id=? AND status IN ('RESERVED','PAID')")) {
            statement.setString(1, lotId);
            try (ResultSet result = statement.executeQuery()) {
                pending = result.next() ? result.getInt(1) : 0;
            }
        }
        if (pending != 0) return;
        try (PreparedStatement consume = connection.prepareStatement(
                "UPDATE ks_eco_official_warehouse "
                        + "SET disposition_state='CONSUMED',disposition_version=disposition_version+1 "
                        + "WHERE id=? AND disposition_state='LIQUIDATION'")) {
            consume.setString(1, lot.warehouseId());
            int changed = consume.executeUpdate();
            if (changed == 0 && !WAREHOUSE_CONSUMED.equals(warehouseState(connection, lot.warehouseId()))) {
                throw new SQLException("Warehouse disposition changed before liquidation completed");
            }
        }
    }

    private Lot findLotById(String lotId) throws SQLException {
        try (Connection connection = openConnection()) {
            return findLot(connection, lotId);
        }
    }

    private Lot findLotByCreateOperation(Connection connection, String operationId) throws SQLException {
        return queryLot(connection, "SELECT * FROM ks_eco_official_liquidation_lots WHERE create_operation_id=?",
                operationId);
    }

    private Lot findLotByWarehouse(Connection connection, String warehouseId) throws SQLException {
        return queryLot(connection, "SELECT * FROM ks_eco_official_liquidation_lots WHERE warehouse_id=?", warehouseId);
    }

    private Lot findLot(Connection connection, String lotId) throws SQLException {
        return queryLot(connection, "SELECT * FROM ks_eco_official_liquidation_lots WHERE id=?", lotId);
    }

    private Lot queryLot(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapLot(result) : null;
            }
        }
    }

    private Purchase findPurchase(Connection connection, String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ks_eco_official_liquidation_purchases WHERE operation_id=?")) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapPurchase(result) : null;
            }
        }
    }

    private WarehouseRow findWarehouse(Connection connection, String warehouseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,item_data,item_material,quantity,paid_price,disposition_state,disposition_version "
                        + "FROM ks_eco_official_warehouse WHERE id=?")) {
            statement.setString(1, warehouseId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new WarehouseRow(
                        result.getString("id"), result.getBytes("item_data"), result.getString("item_material"),
                        result.getInt("quantity"), result.getDouble("paid_price"),
                        result.getString("disposition_state"), result.getLong("disposition_version"));
            }
        }
    }

    private String warehouseState(Connection connection, String warehouseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT disposition_state FROM ks_eco_official_warehouse WHERE id=?")) {
            statement.setString(1, warehouseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : "";
            }
        }
    }

    private static Lot mapLot(ResultSet result) throws SQLException {
        return new Lot(
                result.getString("id"), result.getString("create_operation_id"),
                result.getString("warehouse_id"), result.getBytes("item_data"),
                result.getString("item_material"), result.getInt("initial_quantity"),
                result.getInt("remaining_quantity"), result.getDouble("cost_basis"),
                result.getDouble("unit_price"), result.getString("currency_id"),
                result.getString("status"), result.getLong("version"),
                result.getLong("starts_at"), result.getLong("ends_at"),
                result.getLong("created_at"), result.getLong("updated_at"));
    }

    private static Purchase mapPurchase(ResultSet result) throws SQLException {
        return new Purchase(
                result.getString("operation_id"), result.getString("lot_id"),
                UUID.fromString(result.getString("buyer_uuid")), result.getBytes("item_data"),
                result.getString("item_material"), result.getInt("quantity"),
                result.getDouble("unit_price"), result.getDouble("total_price"),
                result.getString("currency_id"), result.getString("status"),
                result.getLong("version"), result.getLong("created_at"), result.getLong("updated_at"));
    }

    private Connection openConnection() throws SQLException {
        Connection connection = connections.open();
        if (connection == null) throw new SQLException("Connection factory returned null");
        return connection;
    }

    private static boolean sameCreateRequest(Lot lot, String warehouseId, double unitPrice,
                                             long startsAt, long endsAt) {
        return lot.warehouseId().equals(warehouseId)
                && Double.compare(lot.unitPrice(), unitPrice) == 0
                && lot.startsAt() == startsAt && lot.endsAt() == endsAt;
    }

    private static boolean sameReservation(Purchase purchase, String lotId, UUID buyerUuid, int quantity) {
        return purchase.lotId().equals(lotId) && purchase.buyerUuid().equals(buyerUuid)
                && purchase.quantity() == quantity;
    }

    private static String normalizeId(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() || normalized.length() > 128 ? null : normalized;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    public enum Outcome {
        CREATED,
        RESERVED,
        PAID,
        DELIVERED,
        RELEASED,
        REPLAYED,
        NOT_FOUND,
        NOT_AVAILABLE,
        INSUFFICIENT_STOCK,
        CONFLICT,
        INVALID,
        FAILED
    }

    public record LotResult(Outcome outcome, Lot lot, String error) {
        public boolean success() {
            return outcome == Outcome.CREATED || outcome == Outcome.REPLAYED;
        }

        private static LotResult failure(Outcome outcome, String error) {
            return new LotResult(outcome, null, error);
        }
    }

    public record PurchaseResult(Outcome outcome, Purchase purchase, String error) {
        public boolean success() {
            return switch (outcome) {
                case RESERVED, PAID, DELIVERED, RELEASED, REPLAYED -> true;
                default -> false;
            };
        }

        private static PurchaseResult failure(Outcome outcome, String error) {
            return new PurchaseResult(outcome, null, error);
        }
    }

    public record Lot(
            String id,
            String createOperationId,
            String warehouseId,
            byte[] itemData,
            String itemMaterial,
            int initialQuantity,
            int remainingQuantity,
            double costBasis,
            double unitPrice,
            String currencyId,
            String status,
            long version,
            long startsAt,
            long endsAt,
            long createdAt,
            long updatedAt
    ) {
        public Lot {
            itemData = itemData == null ? new byte[0] : itemData.clone();
        }

        @Override
        public byte[] itemData() {
            return itemData.clone();
        }

        public boolean activeAt(long epochSeconds) {
            return LOT_OPEN.equals(status) && remainingQuantity > 0
                    && (startsAt == 0 || epochSeconds >= startsAt)
                    && (endsAt == 0 || epochSeconds < endsAt);
        }
    }

    public record Purchase(
            String operationId,
            String lotId,
            UUID buyerUuid,
            byte[] itemData,
            String itemMaterial,
            int quantity,
            double unitPrice,
            double totalPrice,
            String currencyId,
            String status,
            long version,
            long createdAt,
            long updatedAt
    ) {
        public Purchase {
            itemData = itemData == null ? new byte[0] : itemData.clone();
        }

        /**
         * Returns the immutable warehouse item snapshot. The authoritative delivery amount is {@link #quantity()};
         * after server-thread deserialization, callers must split/set that amount instead of giving the source stack.
         */
        @Override
        public byte[] itemData() {
            return itemData.clone();
        }
    }

    private record WarehouseRow(String id, byte[] itemData, String itemMaterial, int quantity,
                                double paidPrice, String dispositionState, long dispositionVersion) {
        private WarehouseRow {
            itemData = itemData == null ? new byte[0] : itemData.clone();
        }

        @Override
        public byte[] itemData() {
            return itemData.clone();
        }
    }
}
