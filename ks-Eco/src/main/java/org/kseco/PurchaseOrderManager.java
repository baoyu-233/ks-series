package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Escrow-backed player buy orders. A quantity of -1 represents an unlimited order. */
public final class PurchaseOrderManager {
    private static final double MAX_UNIT_PRICE = 1_000_000_000_000d;
    private static final double MAX_ESCROW = 1_000_000_000_000_000d;

    private final KsEco plugin;
    private final Map<String, Consumer<OperationResult>> operationCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Consumer<FulfillmentResult>> fulfillmentCallbacks = new ConcurrentHashMap<>();

    public PurchaseOrderManager(KsEco plugin) {
        this.plugin = plugin;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS ks_eco_purchase_orders (id TEXT PRIMARY KEY, buyer_uuid TEXT NOT NULL, buyer_name TEXT NOT NULL, item_material TEXT NOT NULL, item_signature TEXT, item_data BLOB NOT NULL, exact_nbt INTEGER NOT NULL DEFAULT 0, unit_price REAL NOT NULL, remaining INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE', created_at INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS ks_eco_purchase_order_pending_items (id TEXT PRIMARY KEY, order_id TEXT NOT NULL, buyer_uuid TEXT NOT NULL, item_data BLOB NOT NULL, item_material TEXT NOT NULL, quantity INTEGER NOT NULL, source TEXT NOT NULL, stored_at INTEGER NOT NULL)");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Purchase-order table initialization failed: " + e.getMessage());
        }
    }

    /** Snapshots Bukkit state and withdraws escrow on the server thread, then inserts the order on the DB lane. */
    public void createAsync(Player buyer, ItemStack template, boolean exactNbt, double unitPrice, int quantity,
                            Consumer<OperationResult> callback) {
        requirePrimaryThread();
        if (buyer == null || template == null || template.getType().isAir()
                || !Double.isFinite(unitPrice) || unitPrice <= 0 || unitPrice > MAX_UNIT_PRICE || quantity == 0) {
            callback.accept(OperationResult.fail("求购参数无效"));
            return;
        }

        int remaining = quantity < 0 ? -1 : quantity;
        double escrow = remaining < 0 ? 0 : unitPrice * remaining;
        if (!Double.isFinite(escrow) || escrow > MAX_ESCROW) {
            callback.accept(OperationResult.fail("预存金额无效或过大"));
            return;
        }

        ItemStack normalized = template.clone();
        normalized.setAmount(1);
        CreateRequest request;
        try {
            request = new CreateRequest(
                    UUID.randomUUID().toString(),
                    buyer.getUniqueId(),
                    buyer.getName(),
                    normalized.getType().name(),
                    fingerprint(normalized),
                    normalized.serializeAsBytes(),
                    exactNbt,
                    unitPrice,
                    remaining,
                    escrow,
                    System.currentTimeMillis() / 1000
            );
        } catch (RuntimeException e) {
            callback.accept(OperationResult.fail("目标物品无法保存"));
            return;
        }

        if (escrow > 0 && (!plugin.vaultHook().has(buyer, escrow)
                || !plugin.vaultHook().withdraw(buyer, escrow))) {
            callback.accept(OperationResult.fail("余额不足，无法预存求购款"));
            return;
        }

        String callbackId = UUID.randomUUID().toString();
        operationCallbacks.put(callbackId, callback);
        try {
            plugin.asyncWorkPool().executeDatabase(() -> {
                boolean inserted = insertOrder(request);
                runOnMain(() -> {
                    boolean refunded = true;
                    if (!inserted && request.escrow() > 0) {
                        refunded = refund(request.buyerUuid(), request.escrow(), "创建求购单数据库写入失败");
                    }
                    completeOperation(callbackId, inserted
                            ? OperationResult.ok("求购单已创建")
                            : OperationResult.fail(refunded
                                    ? "创建失败，预存款已退回"
                                    : "创建失败且退款失败，请联系管理员"));
                });
            });
        } catch (RejectedExecutionException rejected) {
            operationCallbacks.remove(callbackId);
            boolean refunded = request.escrow() <= 0
                    || refund(request.buyerUuid(), request.escrow(), "创建求购单队列已满");
            callback.accept(OperationResult.fail(refunded
                    ? "系统繁忙，请稍后重试"
                    : "系统繁忙且预存款退款失败，请联系管理员"));
        }
    }

    public List<OrderSnapshot> loadActiveOrderSnapshots() {
        List<OrderSnapshot> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM ks_eco_purchase_orders WHERE status='ACTIVE' AND remaining<>0 ORDER BY created_at DESC LIMIT 200");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    result.add(mapSnapshot(rs));
                } catch (RuntimeException | SQLException invalidRow) {
                    plugin.getLogger().warning("跳过损坏的求购单 " + rs.getString("id") + ": " + invalidRow.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Read purchase orders failed: " + e.getMessage());
        }
        return result;
    }

    public List<Order> materializeOrders(List<OrderSnapshot> snapshots) {
        requirePrimaryThread();
        List<Order> result = new ArrayList<>(snapshots.size());
        for (OrderSnapshot snapshot : snapshots) {
            try {
                result.add(new Order(
                        snapshot.id(),
                        UUID.fromString(snapshot.buyerUuid()),
                        snapshot.buyerName(),
                        org.bukkit.Material.valueOf(snapshot.itemMaterial()),
                        snapshot.signature(),
                        ItemStack.deserializeBytes(snapshot.itemData()),
                        snapshot.exactNbt(),
                        snapshot.unitPrice(),
                        snapshot.remaining()
                ));
            } catch (RuntimeException invalidRow) {
                plugin.getLogger().warning("跳过损坏的求购单 " + snapshot.id() + ": " + invalidRow.getMessage());
            }
        }
        return List.copyOf(result);
    }

    public int availableQuantity(Order order, ItemStack hand) {
        requirePrimaryThread();
        if (order == null || hand == null || hand.getType().isAir()) return 0;
        int available;
        if (matches(order, hand)) {
            available = hand.getAmount();
        } else if (ShulkerBoxParser.isShulkerBox(hand)) {
            available = plugin.shulkerBoxParser().countMatching(hand, item -> matches(order, item));
        } else {
            available = 0;
        }
        if (order.remaining() >= 0) available = Math.min(available, order.remaining());
        return Math.max(0, available);
    }

    /**
     * Builds the inventory-removal and serialized storage snapshots on the server thread. The database lane then
     * reserves finite quantity and inserts all buyer storage rows in one transaction before main-thread settlement.
     */
    public void fulfillAsync(Player seller, Order order, ItemStack hand, int requestedQuantity,
                             Consumer<FulfillmentResult> callback) {
        requirePrimaryThread();
        if (seller == null || order == null || hand == null || hand.getType().isAir()) {
            callback.accept(FulfillmentResult.fail("请先在主手持有符合求购条件的物品"));
            return;
        }

        int available = availableQuantity(order, hand);
        int quantity = Math.min(available, Math.max(1, requestedQuantity));
        if (quantity <= 0) {
            callback.accept(FulfillmentResult.fail(ShulkerBoxParser.isShulkerBox(hand)
                    ? "主手潜影盒内没有符合条件的物品" : "主手物品与求购条件不匹配"));
            return;
        }

        ShulkerBoxParser.RemovalResult removal;
        if (matches(order, hand)) {
            ItemStack sold = hand.clone();
            sold.setAmount(quantity);
            ItemStack remainder = hand.getAmount() > quantity ? hand.clone() : null;
            if (remainder != null) remainder.setAmount(hand.getAmount() - quantity);
            removal = new ShulkerBoxParser.RemovalResult(remainder, List.of(sold), quantity);
        } else {
            removal = plugin.shulkerBoxParser().removeMatching(hand, item -> matches(order, item), quantity);
        }
        if (removal.removedQuantity() != quantity) {
            callback.accept(FulfillmentResult.fail("物品内容已变化，请重试"));
            return;
        }

        double payment = quantity * order.unitPrice();
        if (!Double.isFinite(payment) || payment <= 0) {
            callback.accept(FulfillmentResult.fail("结算金额无效"));
            return;
        }

        ItemStack originalHand = hand.clone();
        ItemStack updatedHand = removal.updatedSource() == null ? null : removal.updatedSource().clone();
        List<StoredStack> storedStacks = new ArrayList<>(removal.removedItems().size());
        byte[] originalHandData;
        byte[] updatedHandData;
        try {
            originalHandData = originalHand.serializeAsBytes();
            updatedHandData = updatedHand == null ? null : updatedHand.serializeAsBytes();
            for (ItemStack sold : removal.removedItems()) {
                storedStacks.add(new StoredStack(
                        UUID.randomUUID().toString(),
                        sold.serializeAsBytes(),
                        sold.getType().name(),
                        sold.getAmount()
                ));
            }
        } catch (RuntimeException e) {
            callback.accept(FulfillmentResult.fail("出售物品无法保存"));
            return;
        }

        FulfillmentRequest request = new FulfillmentRequest(
                seller.getUniqueId(), order.id(), order.buyerUuid(), quantity, payment,
                order.remaining() < 0, originalHandData, updatedHandData, List.copyOf(storedStacks)
        );
        String callbackId = UUID.randomUUID().toString();
        fulfillmentCallbacks.put(callbackId, callback);
        try {
            plugin.asyncWorkPool().executeDatabase(() -> {
                ReservationResult reservation = reserveAndStore(request);
                runOnMain(() -> settleReservation(request, reservation, callbackId));
            });
        } catch (RejectedExecutionException rejected) {
            fulfillmentCallbacks.remove(callbackId);
            callback.accept(FulfillmentResult.fail("系统繁忙，请稍后重试"));
        }
    }

    /** Cancels on the database lane and performs the Vault refund only after returning to the server thread. */
    public void cancelAsync(Player buyer, Order order, Consumer<OperationResult> callback) {
        requirePrimaryThread();
        if (buyer == null || order == null || !buyer.getUniqueId().equals(order.buyerUuid())) {
            callback.accept(OperationResult.fail("你不是该求购单的发布者"));
            return;
        }
        UUID buyerUuid = buyer.getUniqueId();
        String callbackId = UUID.randomUUID().toString();
        operationCallbacks.put(callbackId, callback);
        try {
            plugin.asyncWorkPool().executeDatabase(() -> {
                CancelReservation cancelled = cancelAndGetRemaining(order.id(), buyerUuid);
                runOnMain(() -> settleCancellation(order.id(), buyerUuid, cancelled, callbackId));
            });
        } catch (RejectedExecutionException rejected) {
            operationCallbacks.remove(callbackId);
            callback.accept(OperationResult.fail("系统繁忙，请稍后重试"));
        }
    }

    private boolean insertOrder(CreateRequest request) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO ks_eco_purchase_orders (id,buyer_uuid,buyer_name,item_material,item_signature,item_data,exact_nbt,unit_price,remaining,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, request.id());
            ps.setString(2, request.buyerUuid().toString());
            ps.setString(3, request.buyerName());
            ps.setString(4, request.itemMaterial());
            ps.setString(5, request.signature());
            ps.setBytes(6, request.itemData());
            ps.setInt(7, request.exactNbt() ? 1 : 0);
            ps.setDouble(8, request.unitPrice());
            ps.setInt(9, request.remaining());
            ps.setLong(10, request.createdAt());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("Create purchase order failed: " + e.getMessage());
            return false;
        }
    }

    private ReservationResult reserveAndStore(FulfillmentRequest request) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return ReservationResult.fail("数据库不可用");
            conn.setAutoCommit(false);
            try {
                if (request.unlimited()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT 1 FROM ks_eco_purchase_orders WHERE id=? AND status='ACTIVE' AND remaining=-1")) {
                        ps.setString(1, request.orderId());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                conn.rollback();
                                return ReservationResult.fail("该求购单已结束");
                            }
                        }
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE ks_eco_purchase_orders SET remaining=remaining-? WHERE id=? AND status='ACTIVE' AND remaining>=?")) {
                        ps.setInt(1, request.quantity());
                        ps.setString(2, request.orderId());
                        ps.setInt(3, request.quantity());
                        if (ps.executeUpdate() != 1) {
                            conn.rollback();
                            return ReservationResult.fail("求购数量已变化或订单已结束");
                        }
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_eco_purchase_order_pending_items (id,order_id,buyer_uuid,item_data,item_material,quantity,source,stored_at) VALUES (?,?,?,?,?,?,?,?)")) {
                    long now = System.currentTimeMillis() / 1000;
                    for (StoredStack stack : request.storedStacks()) {
                        ps.setString(1, stack.id());
                        ps.setString(2, request.orderId());
                        ps.setString(3, request.buyerUuid().toString());
                        ps.setBytes(4, stack.itemData());
                        ps.setString(5, stack.material());
                        ps.setInt(6, stack.quantity());
                        ps.setString(7, "PURCHASE_ORDER:" + request.orderId());
                        ps.setLong(8, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                return ReservationResult.ok(request.storedStacks().stream().map(StoredStack::id).toList());
            } catch (SQLException e) {
                rollback(conn);
                plugin.getLogger().warning("Reserve purchase-order fulfillment failed: " + e.getMessage());
                return ReservationResult.fail("买家暂存箱写入失败，交易未执行");
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Reserve purchase-order fulfillment failed: " + e.getMessage());
            return ReservationResult.fail("数据库不可用");
        }
    }

    private void settleReservation(FulfillmentRequest request, ReservationResult reservation, String callbackId) {
        requirePrimaryThread();
        if (!reservation.success()) {
            completeFulfillment(callbackId, FulfillmentResult.fail(reservation.error()));
            return;
        }

        Player seller = Bukkit.getPlayer(request.sellerUuid());
        if (seller == null || !seller.isOnline()) {
            compensateAsync(request, reservation.storageIds(), false,
                    FulfillmentResult.fail("玩家已离线，交易已取消"), callbackId);
            return;
        }
        ItemStack originalHand;
        ItemStack updatedHand;
        try {
            originalHand = ItemStack.deserializeBytes(request.originalHandData());
            updatedHand = request.updatedHandData() == null
                    ? null : ItemStack.deserializeBytes(request.updatedHandData());
        } catch (RuntimeException invalidSnapshot) {
            compensateAsync(request, reservation.storageIds(), false,
                    FulfillmentResult.fail("结算物品快照损坏，交易已取消"), callbackId);
            return;
        }
        ItemStack currentHand = seller.getInventory().getItemInMainHand();
        if (!sameStack(currentHand, originalHand)) {
            compensateAsync(request, reservation.storageIds(), false,
                    FulfillmentResult.fail("主手物品已变化，交易已取消"), callbackId);
            return;
        }

        boolean buyerCharged = false;
        if (request.unlimited()) {
            OfflinePlayer buyer = Bukkit.getOfflinePlayer(request.buyerUuid());
            if (!plugin.vaultHook().has(buyer, request.payment())
                    || !plugin.vaultHook().withdraw(buyer, request.payment())) {
                compensateAsync(request, reservation.storageIds(), false,
                        FulfillmentResult.fail("求购方余额不足"), callbackId);
                return;
            }
            buyerCharged = true;
        }

        seller.getInventory().setItemInMainHand(updatedHand == null ? null : updatedHand.clone());
        if (!plugin.vaultHook().deposit(seller, request.payment())) {
            seller.getInventory().setItemInMainHand(originalHand.clone());
            seller.updateInventory();
            compensateAsync(request, reservation.storageIds(), buyerCharged,
                    FulfillmentResult.fail("卖家收款失败，物品已恢复"), callbackId);
            return;
        }
        seller.updateInventory();
        FulfillmentResult success = new FulfillmentResult(true,
                "成交 " + request.quantity() + " 个，收入 " + plugin.vaultHook().format(request.payment()),
                request.quantity(), request.payment());
        finalizeReservationAsync(request, reservation.storageIds(), success, callbackId);
    }

    private void finalizeReservationAsync(FulfillmentRequest request, List<String> pendingIds,
                                          FulfillmentResult success, String callbackId) {
        try {
            plugin.asyncWorkPool().executeDatabase(() -> {
                boolean finalized = finalizeReservation(request, pendingIds);
                runOnMain(() -> {
                    if (!finalized) {
                        plugin.getLogger().severe("Purchase-order delivery finalization requires administrator review: order="
                                + request.orderId() + " seller=" + request.sellerUuid());
                        completeFulfillment(callbackId, FulfillmentResult.fail(
                                "卖家已收款，但买家物品入账失败，请联系管理员"));
                        return;
                    }
                    completeFulfillment(callbackId, success);
                });
            });
        } catch (RejectedExecutionException rejected) {
            plugin.getLogger().severe("Purchase-order delivery finalization queue rejected: order=" + request.orderId());
            completeFulfillment(callbackId,
                    FulfillmentResult.fail("卖家已收款，但买家物品入账无法排队，请联系管理员"));
        }
    }

    private boolean finalizeReservation(FulfillmentRequest request, List<String> pendingIds) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return false;
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO ks_eco_storage (id,owner_uuid,item_data,item_material,quantity,source,stored_at) "
                                + "SELECT id,buyer_uuid,item_data,item_material,quantity,source,stored_at "
                                + "FROM ks_eco_purchase_order_pending_items WHERE id=? AND order_id=? AND buyer_uuid=?")) {
                    for (String id : pendingIds) {
                        insert.setString(1, id);
                        insert.setString(2, request.orderId());
                        insert.setString(3, request.buyerUuid().toString());
                        if (insert.executeUpdate() != 1) throw new SQLException("Missing pending delivery " + id);
                    }
                }
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM ks_eco_purchase_order_pending_items WHERE id=? AND order_id=? AND buyer_uuid=?")) {
                    for (String id : pendingIds) {
                        delete.setString(1, id);
                        delete.setString(2, request.orderId());
                        delete.setString(3, request.buyerUuid().toString());
                        if (delete.executeUpdate() != 1) throw new SQLException("Failed to clear pending delivery " + id);
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                rollback(conn);
                plugin.getLogger().warning("Finalize purchase-order delivery failed: " + e.getMessage());
                return false;
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Finalize purchase-order delivery failed: " + e.getMessage());
            return false;
        }
    }

    private void compensateAsync(FulfillmentRequest request, List<String> storageIds, boolean buyerCharged,
                                 FulfillmentResult failure, String callbackId) {
        try {
            plugin.asyncWorkPool().executeDatabase(() -> {
                CompensationResult compensation = compensateReservation(request, storageIds, buyerCharged);
                runOnMain(() -> {
                    boolean refunded = true;
                    if (compensation.success() && compensation.refundBuyer()) {
                        refunded = refund(request.buyerUuid(), request.payment(), "求购成交补偿");
                    }
                    if (!compensation.success() || !refunded) {
                        plugin.getLogger().severe("Purchase-order compensation requires administrator review: order="
                                + request.orderId() + " seller=" + request.sellerUuid());
                        completeFulfillment(callbackId,
                                FulfillmentResult.fail(failure.message() + "；自动补偿失败，请联系管理员"));
                        return;
                    }
                    completeFulfillment(callbackId, failure);
                });
            });
        } catch (RejectedExecutionException rejected) {
            plugin.getLogger().severe("Purchase-order compensation queue rejected: order=" + request.orderId());
            completeFulfillment(callbackId,
                    FulfillmentResult.fail(failure.message() + "；自动补偿排队失败，请联系管理员"));
        }
    }

    private CompensationResult compensateReservation(FulfillmentRequest request, List<String> storageIds,
                                                       boolean buyerCharged) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return CompensationResult.failed();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM ks_eco_purchase_order_pending_items WHERE id=? AND order_id=? AND buyer_uuid=?")) {
                    for (String id : storageIds) {
                        ps.setString(1, id);
                        ps.setString(2, request.orderId());
                        ps.setString(3, request.buyerUuid().toString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                boolean restored = request.unlimited();
                if (!request.unlimited()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE ks_eco_purchase_orders SET remaining=remaining+? WHERE id=? AND status='ACTIVE'")) {
                        ps.setInt(1, request.quantity());
                        ps.setString(2, request.orderId());
                        restored = ps.executeUpdate() == 1;
                    }
                }
                conn.commit();
                return new CompensationResult(true,
                        request.unlimited() ? buyerCharged : !restored);
            } catch (SQLException e) {
                rollback(conn);
                plugin.getLogger().warning("Compensate purchase-order fulfillment failed: " + e.getMessage());
                return CompensationResult.failed();
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Compensate purchase-order fulfillment failed: " + e.getMessage());
            return CompensationResult.failed();
        }
    }

    private CancelReservation cancelAndGetRemaining(String id, UUID buyer) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return CancelReservation.failed();
            conn.setAutoCommit(false);
            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT remaining,unit_price FROM ks_eco_purchase_orders WHERE id=? AND buyer_uuid=? AND status='ACTIVE'")) {
                select.setString(1, id);
                select.setString(2, buyer.toString());
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return CancelReservation.failed();
                    }
                    int remaining = rs.getInt(1);
                    double unitPrice = rs.getDouble(2);
                    try (PreparedStatement update = conn.prepareStatement(
                            "UPDATE ks_eco_purchase_orders SET status='CANCELLED' WHERE id=? AND status='ACTIVE'")) {
                        update.setString(1, id);
                        if (update.executeUpdate() != 1) {
                            conn.rollback();
                            return CancelReservation.failed();
                        }
                    }
                    conn.commit();
                    return new CancelReservation(true, Math.max(0, remaining), unitPrice);
                }
            } catch (SQLException e) {
                rollback(conn);
                plugin.getLogger().warning("Cancel purchase order failed: " + e.getMessage());
                return CancelReservation.failed();
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Cancel purchase order failed: " + e.getMessage());
            return CancelReservation.failed();
        }
    }

    private void settleCancellation(String orderId, UUID buyerUuid, CancelReservation cancelled,
                                    String callbackId) {
        requirePrimaryThread();
        if (!cancelled.success()) {
            completeOperation(callbackId, OperationResult.fail("撤销失败，求购单可能已经结束"));
            return;
        }
        double refundAmount = cancelled.remaining() * cancelled.unitPrice();
        if (refundAmount <= 0 || refund(buyerUuid, refundAmount, "撤销求购单")) {
            completeOperation(callbackId, OperationResult.ok("求购单已撤销，剩余预存款已退回"));
            return;
        }

        try {
            plugin.asyncWorkPool().executeDatabase(() -> {
                boolean restored = reactivateCancelledOrder(orderId, buyerUuid);
                runOnMain(() -> completeOperation(callbackId, restored
                        ? OperationResult.fail("退款失败，求购单已恢复为进行中")
                        : OperationResult.fail("退款与订单恢复均失败，请联系管理员")));
            });
        } catch (RejectedExecutionException rejected) {
            plugin.getLogger().severe("Purchase-order cancellation rollback queue rejected: order=" + orderId);
            completeOperation(callbackId, OperationResult.fail("退款失败且恢复操作无法排队，请联系管理员"));
        }
    }

    private void completeOperation(String callbackId, OperationResult result) {
        requirePrimaryThread();
        Consumer<OperationResult> callback = operationCallbacks.remove(callbackId);
        if (callback != null) callback.accept(result);
    }

    private void completeFulfillment(String callbackId, FulfillmentResult result) {
        requirePrimaryThread();
        Consumer<FulfillmentResult> callback = fulfillmentCallbacks.remove(callbackId);
        if (callback != null) callback.accept(result);
    }

    private boolean reactivateCancelledOrder(String orderId, UUID buyerUuid) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE ks_eco_purchase_orders SET status='ACTIVE' WHERE id=? AND buyer_uuid=? AND status='CANCELLED'")) {
            ps.setString(1, orderId);
            ps.setString(2, buyerUuid.toString());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("Restore cancelled purchase order failed: " + e.getMessage());
            return false;
        }
    }

    private boolean matches(Order order, ItemStack item) {
        if (item == null || item.getType() != order.material()) return false;
        return !order.exactNbt() || fingerprint(item).equals(order.signature());
    }

    private OrderSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        return new OrderSnapshot(
                rs.getString("id"),
                rs.getString("buyer_uuid"),
                rs.getString("buyer_name"),
                rs.getString("item_material"),
                rs.getString("item_signature"),
                rs.getBytes("item_data"),
                rs.getInt("exact_nbt") == 1,
                rs.getDouble("unit_price"),
                rs.getInt("remaining")
        );
    }

    public static String fingerprint(ItemStack item) {
        try {
            ItemStack normalized = item.clone();
            normalized.setAmount(1);
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(normalized.serializeAsBytes()));
        } catch (Exception e) {
            return "";
        }
    }

    private boolean refund(UUID playerUuid, double amount, String reason) {
        if (amount <= 0) return true;
        if (!Double.isFinite(amount)) {
            plugin.getLogger().severe(reason + " rejected invalid refund: player=" + playerUuid + " amount=" + amount);
            return false;
        }
        boolean refunded = plugin.vaultHook().deposit(Bukkit.getOfflinePlayer(playerUuid), amount);
        if (!refunded) {
            plugin.getLogger().severe(reason + " refund failed: player=" + playerUuid + " amount=" + amount);
        }
        return refunded;
    }

    private void runOnMain(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        if (left == null || left.getType().isAir()) return right == null || right.getType().isAir();
        if (right == null || right.getType().isAir()) return false;
        return left.getAmount() == right.getAmount() && left.isSimilar(right);
    }

    private static void rollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Purchase-order Bukkit operations must run on the server thread");
        }
    }

    public record Order(String id, UUID buyerUuid, String buyerName, org.bukkit.Material material, String signature,
                        ItemStack template, boolean exactNbt, double unitPrice, int remaining) {
    }

    public record OrderSnapshot(
            String id,
            String buyerUuid,
            String buyerName,
            String itemMaterial,
            String signature,
            byte[] itemData,
            boolean exactNbt,
            double unitPrice,
            int remaining
    ) {
        public OrderSnapshot {
            itemData = itemData == null ? new byte[0] : itemData.clone();
        }

        @Override
        public byte[] itemData() {
            return itemData.clone();
        }
    }

    public record OperationResult(boolean success, String message) {
        public static OperationResult ok(String message) {
            return new OperationResult(true, message);
        }

        public static OperationResult fail(String message) {
            return new OperationResult(false, message);
        }
    }

    public record FulfillmentResult(boolean success, String message, int quantity, double payment) {
        public static FulfillmentResult fail(String message) {
            return new FulfillmentResult(false, message, 0, 0.0);
        }
    }

    private record CreateRequest(
            String id,
            UUID buyerUuid,
            String buyerName,
            String itemMaterial,
            String signature,
            byte[] itemData,
            boolean exactNbt,
            double unitPrice,
            int remaining,
            double escrow,
            long createdAt
    ) {
        private CreateRequest {
            itemData = itemData.clone();
        }

        @Override
        public byte[] itemData() {
            return itemData.clone();
        }
    }

    private record StoredStack(String id, byte[] itemData, String material, int quantity) {
        private StoredStack {
            itemData = itemData.clone();
        }

        @Override
        public byte[] itemData() {
            return itemData.clone();
        }
    }

    private record FulfillmentRequest(
            UUID sellerUuid,
            String orderId,
            UUID buyerUuid,
            int quantity,
            double payment,
            boolean unlimited,
            byte[] originalHandData,
            byte[] updatedHandData,
            List<StoredStack> storedStacks
    ) {
        private FulfillmentRequest {
            originalHandData = originalHandData.clone();
            updatedHandData = updatedHandData == null ? null : updatedHandData.clone();
            storedStacks = List.copyOf(storedStacks);
        }

        @Override
        public byte[] originalHandData() {
            return originalHandData.clone();
        }

        @Override
        public byte[] updatedHandData() {
            return updatedHandData == null ? null : updatedHandData.clone();
        }
    }

    private record ReservationResult(boolean success, List<String> storageIds, String error) {
        private static ReservationResult ok(List<String> storageIds) {
            return new ReservationResult(true, List.copyOf(storageIds), null);
        }

        private static ReservationResult fail(String error) {
            return new ReservationResult(false, List.of(), error);
        }
    }

    private record CompensationResult(boolean success, boolean refundBuyer) {
        private static CompensationResult failed() {
            return new CompensationResult(false, false);
        }
    }

    private record CancelReservation(boolean success, int remaining, double unitPrice) {
        private static CancelReservation failed() {
            return new CancelReservation(false, 0, 0.0);
        }
    }
}
