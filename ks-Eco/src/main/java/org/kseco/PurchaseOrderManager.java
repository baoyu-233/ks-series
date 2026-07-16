package org.kseco;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.security.MessageDigest;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Escrow-backed player buy orders. A quantity of -1 represents an unlimited order. */
public final class PurchaseOrderManager {
    private final KsEco plugin;

    public PurchaseOrderManager(KsEco plugin) {
        this.plugin = plugin;
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            if (conn == null) return;
            try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ks_eco_purchase_orders (id TEXT PRIMARY KEY, buyer_uuid TEXT NOT NULL, buyer_name TEXT NOT NULL, item_material TEXT NOT NULL, item_signature TEXT, item_data BLOB NOT NULL, exact_nbt INTEGER NOT NULL DEFAULT 0, unit_price REAL NOT NULL, remaining INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE', created_at INTEGER NOT NULL)");
            }
        } catch (SQLException e) { plugin.getLogger().warning("Purchase-order table initialization failed: " + e.getMessage()); }
    }

    public boolean create(Player buyer, ItemStack template, boolean exactNbt, double unitPrice, int quantity) {
        if (template == null || template.getType().isAir() || !Double.isFinite(unitPrice)
                || unitPrice <= 0 || unitPrice > 1_000_000_000_000d || quantity == 0) return false;
        int remaining = quantity < 0 ? -1 : quantity;
        double escrow = remaining < 0 ? 0 : unitPrice * remaining;
        ItemStack normalized = template.clone(); normalized.setAmount(1);
        byte[] itemData;
        String signature;
        try {
            itemData = normalized.serializeAsBytes();
            signature = fingerprint(normalized);
        } catch (RuntimeException e) {
            return false;
        }
        if (!Double.isFinite(escrow) || escrow > 1_000_000_000_000_000d) return false;
        if (escrow > 0 && (!plugin.vaultHook().has(buyer, escrow) || !plugin.vaultHook().withdraw(buyer, escrow))) return false;
        try (Connection conn = plugin.ksCore().dataStore().getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ks_eco_purchase_orders (id,buyer_uuid,buyer_name,item_material,item_signature,item_data,exact_nbt,unit_price,remaining,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString()); ps.setString(2, buyer.getUniqueId().toString()); ps.setString(3, buyer.getName());
            ps.setString(4, normalized.getType().name()); ps.setString(5, signature); ps.setBytes(6, itemData);
            ps.setInt(7, exactNbt ? 1 : 0); ps.setDouble(8, unitPrice); ps.setInt(9, remaining); ps.setLong(10, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (escrow > 0) plugin.vaultHook().deposit(buyer, escrow);
            plugin.getLogger().warning("Create purchase order failed: " + e.getMessage());
            return false;
        }
    }

    public List<Order> activeOrders() {
        List<Order> result = new ArrayList<>();
        try (Connection conn = plugin.ksCore().dataStore().getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM ks_eco_purchase_orders WHERE status='ACTIVE' AND remaining<>0 ORDER BY created_at DESC LIMIT 200"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    result.add(map(rs));
                } catch (RuntimeException | SQLException invalidRow) {
                    plugin.getLogger().warning("跳过损坏的求购单 " + rs.getString("id") + ": " + invalidRow.getMessage());
                }
            }
        } catch (Exception e) { plugin.getLogger().warning("Read purchase orders failed: " + e.getMessage()); }
        return result;
    }

    public boolean fulfill(Player seller, Order order, ItemStack hand) {
        return fulfill(seller, order, hand, Integer.MAX_VALUE).success();
    }

    public int availableQuantity(Order order, ItemStack hand) {
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

    public FulfillmentResult fulfill(Player seller, Order order, ItemStack hand, int requestedQuantity) {
        if (seller == null || order == null || hand == null || hand.getType().isAir()) {
            return FulfillmentResult.fail("请先在主手持有符合求购条件的物品");
        }
        int available = availableQuantity(order, hand);
        int quantity = Math.min(available, Math.max(1, requestedQuantity));
        if (quantity <= 0) return FulfillmentResult.fail(ShulkerBoxParser.isShulkerBox(hand)
                ? "主手潜影盒内没有符合条件的物品" : "主手物品与求购条件不匹配");

        ShulkerBoxParser.RemovalResult removal;
        if (matches(order, hand)) {
            ItemStack sold = hand.clone();
            sold.setAmount(quantity);
            ItemStack remainder = hand.getAmount() > quantity ? hand.clone() : null;
            if (remainder != null) remainder.setAmount(hand.getAmount() - quantity);
            removal = new ShulkerBoxParser.RemovalResult(remainder, List.of(sold), quantity);
        } else {
            removal = plugin.shulkerBoxParser().removeMatching(
                    hand, item -> matches(order, item), quantity);
        }
        if (removal.removedQuantity() != quantity) return FulfillmentResult.fail("物品内容已变化，请重试");

        double payment = quantity * order.unitPrice();
        if (!Double.isFinite(payment) || payment <= 0) return FulfillmentResult.fail("结算金额无效");
        boolean unlimited = order.remaining() < 0;
        if (order.remaining() < 0) {
            if (!isActiveUnlimited(order.id())) return FulfillmentResult.fail("该求购单已结束");
            var buyer = org.bukkit.Bukkit.getOfflinePlayer(order.buyerUuid());
            if (!plugin.vaultHook().has(buyer, payment) || !plugin.vaultHook().withdraw(buyer, payment)) {
                return FulfillmentResult.fail("求购方余额不足");
            }
        } else if (!reserveFulfillment(order.id(), quantity)) {
            return FulfillmentResult.fail("求购数量已变化或订单已结束");
        }

        List<String> storedIds = new ArrayList<>();
        for (ItemStack sold : removal.removedItems()) {
            String storageId = plugin.storageManager().storeItemNow(order.buyerUuid(), sold,
                    "PURCHASE_ORDER:" + order.id());
            if (storageId == null) {
                rollbackStored(order.buyerUuid(), storedIds);
                releaseReservation(order, quantity, payment, unlimited);
                return FulfillmentResult.fail("买家暂存箱写入失败，交易未执行");
            }
            storedIds.add(storageId);
        }

        ItemStack original = hand.clone();
        seller.getInventory().setItemInMainHand(removal.updatedSource() == null
                ? null : removal.updatedSource().clone());
        if (!plugin.vaultHook().deposit(seller, payment)) {
            seller.getInventory().setItemInMainHand(original);
            rollbackStored(order.buyerUuid(), storedIds);
            releaseReservation(order, quantity, payment, unlimited);
            return FulfillmentResult.fail("卖家收款失败，物品已恢复");
        }
        seller.updateInventory();
        return new FulfillmentResult(true, "成交 " + quantity + " 个，收入 "
                + plugin.vaultHook().format(payment), quantity, payment);
    }

    public boolean cancel(Player buyer, Order order) {
        if (!buyer.getUniqueId().equals(order.buyerUuid())) return false;
        int remaining = cancelAndGetRemaining(order.id(), buyer.getUniqueId());
        if (remaining < 0) return false;
        if (remaining > 0) plugin.vaultHook().deposit(buyer, remaining * order.unitPrice());
        return true;
    }

    private boolean reserveFulfillment(String id, int quantity) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE ks_eco_purchase_orders SET remaining=remaining-? WHERE id=? AND status='ACTIVE' AND remaining>=?")) {
            ps.setInt(1, quantity); ps.setString(2, id); ps.setInt(3, quantity); return ps.executeUpdate() == 1;
        } catch (SQLException e) { return false; }
    }

    private boolean restoreRemaining(String id, int quantity) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE ks_eco_purchase_orders SET remaining=remaining+? WHERE id=? AND status='ACTIVE'")) {
            ps.setInt(1, quantity); ps.setString(2, id); return ps.executeUpdate() == 1;
        } catch (SQLException ignored) { return false; }
    }

    private boolean isActiveUnlimited(String id) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM ks_eco_purchase_orders WHERE id=? AND status='ACTIVE' AND remaining=-1")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    private void rollbackStored(UUID buyerUuid, List<String> storedIds) {
        for (String id : storedIds) plugin.storageManager().deleteStoredItem(buyerUuid, id);
    }

    private void releaseReservation(Order order, int quantity, double payment, boolean unlimited) {
        if (unlimited) {
            plugin.vaultHook().deposit(org.bukkit.Bukkit.getOfflinePlayer(order.buyerUuid()), payment);
            return;
        }
        if (!restoreRemaining(order.id(), quantity)) {
            // A concurrent cancellation already refunded the visible remainder. Return the
            // reserved slice directly so the buyer is not charged for a failed fulfillment.
            plugin.vaultHook().deposit(org.bukkit.Bukkit.getOfflinePlayer(order.buyerUuid()), payment);
        }
    }

    private int cancelAndGetRemaining(String id, UUID buyer) {
        try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement select = conn.prepareStatement("SELECT remaining FROM ks_eco_purchase_orders WHERE id=? AND buyer_uuid=? AND status='ACTIVE'")) {
                select.setString(1, id); select.setString(2, buyer.toString());
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return -1; }
                    int remaining = rs.getInt(1);
                    try (PreparedStatement update = conn.prepareStatement("UPDATE ks_eco_purchase_orders SET status='CANCELLED' WHERE id=? AND status='ACTIVE'")) {
                        update.setString(1, id); if (update.executeUpdate() != 1) { conn.rollback(); return -1; }
                    }
                    conn.commit(); return Math.max(0, remaining);
                }
            }
        } catch (SQLException e) { return -1; }
    }

    private boolean matches(Order order, ItemStack item) {
        if (item == null || item.getType() != order.material()) return false;
        return !order.exactNbt() || fingerprint(item).equals(order.signature());
    }

    private Order map(ResultSet rs) throws SQLException {
        return new Order(rs.getString("id"), UUID.fromString(rs.getString("buyer_uuid")), rs.getString("buyer_name"),
                org.bukkit.Material.valueOf(rs.getString("item_material")), rs.getString("item_signature"),
                ItemStack.deserializeBytes(rs.getBytes("item_data")), rs.getInt("exact_nbt") == 1, rs.getDouble("unit_price"), rs.getInt("remaining"));
    }

    public static String fingerprint(ItemStack item) {
        try {
            ItemStack normalized = item.clone(); normalized.setAmount(1);
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(normalized.serializeAsBytes()));
        } catch (Exception e) { return ""; }
    }

    public record Order(String id, UUID buyerUuid, String buyerName, org.bukkit.Material material, String signature,
                        ItemStack template, boolean exactNbt, double unitPrice, int remaining) {}

    public record FulfillmentResult(boolean success, String message, int quantity, double payment) {
        public static FulfillmentResult fail(String message) {
            return new FulfillmentResult(false, message, 0, 0.0);
        }
    }
}
