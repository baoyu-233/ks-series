package org.kseco;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Distance-priced player-to-player item delivery. Bukkit inventory and Vault
 * operations stay on the server thread; only audit writes use AsyncWorkPool.
 */
public final class TransportManager {
    private final KsEco plugin;

    public TransportManager(KsEco plugin) {
        this.plugin = plugin;
    }

    public Quote quote(Player sender, Player target) {
        boolean crossWorld = !sender.getWorld().equals(target.getWorld());
        double distance = crossWorld ? 0.0 : sender.getLocation().distance(target.getLocation());
        double freeDistance = Math.max(0.0, plugin.getConfig().getDouble("transport.free-distance", 8.0));
        if (!crossWorld && distance <= freeDistance) {
            return new Quote(distance, false, 0.0, true);
        }

        double base = plugin.getConfig().getDouble("transport.base-fee", 10.0);
        double perBlock = plugin.getConfig().getDouble("transport.per-block-fee", 0.05);
        double crossWorldFee = plugin.getConfig().getDouble("transport.cross-world-surcharge", 500.0);
        double minimum = plugin.getConfig().getDouble("transport.minimum-fee", 1.0);
        double maximum = plugin.getConfig().getDouble("transport.maximum-fee", 0.0);
        double fee = Math.max(minimum, base + distance * perBlock + (crossWorld ? crossWorldFee : 0.0));
        if (maximum > 0) fee = Math.min(fee, maximum);
        return new Quote(distance, crossWorld, Math.max(0, fee), false);
    }

    /** Fast path for /trade send: deliver a specified amount from the main hand. */
    public boolean sendHeldItem(Player sender, Player target, int requestedAmount) {
        ItemStack held = sender.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            sender.sendMessage("§c请先把物品拿在主手。");
            return false;
        }
        int amount = requestedAmount <= 0 ? held.getAmount() : requestedAmount;
        if (amount > held.getAmount()) {
            sender.sendMessage("§c主手物品数量不足。");
            return false;
        }
        ItemStack selected = held.clone();
        selected.setAmount(amount);
        return sendBatch(sender, target, Map.of(sender.getInventory().getHeldItemSlot(), selected)).success();
    }

    /**
     * Deliver selected inventory slots as one shipment. Each value is a snapshot
     * of the selected stack; it is revalidated immediately before deduction.
     */
    public DeliveryResult sendBatch(Player sender, Player target, Map<Integer, ItemStack> selections) {
        if (!plugin.getConfig().getBoolean("transport.enabled", true)) {
            sender.sendMessage("§c物品物流当前未启用。");
            return DeliveryResult.failed("delivery disabled");
        }
        if (!target.isOnline()) {
            sender.sendMessage("§c收件玩家已离线。");
            return DeliveryResult.failed("recipient offline");
        }
        if (selections == null || selections.isEmpty()) {
            sender.sendMessage("§c请先选择至少一组物品。");
            return DeliveryResult.failed("no selected items");
        }

        Map<Integer, ItemStack> checked = new LinkedHashMap<>();
        for (Map.Entry<Integer, ItemStack> entry : selections.entrySet()) {
            int slot = entry.getKey();
            ItemStack expected = entry.getValue();
            ItemStack actual = sender.getInventory().getItem(slot);
            if (slot < 0 || slot >= 36 || expected == null || expected.getType().isAir()
                    || expected.getAmount() <= 0 || actual == null || actual.getType().isAir()
                    || !actual.isSimilar(expected) || actual.getAmount() < expected.getAmount()) {
                sender.sendMessage("§c背包物品已变化，请重新打开物流界面选择。");
                return DeliveryResult.failed("inventory changed");
            }
            checked.put(slot, expected.clone());
        }

        Quote quote = quote(sender, target);
        if (quote.fee() > 0 && !plugin.vaultHook().has(sender, quote.fee())) {
            sender.sendMessage("§c余额不足，物流费为 " + plugin.vaultHook().format(quote.fee()) + "。");
            return DeliveryResult.failed("insufficient funds");
        }
        if (quote.fee() > 0 && !plugin.vaultHook().withdraw(sender, quote.fee())) {
            sender.sendMessage("§c物流费扣除失败。");
            return DeliveryResult.failed("withdraw failed");
        }

        List<TransportLog> auditRows = new ArrayList<>(checked.size());
        try {
            for (Map.Entry<Integer, ItemStack> entry : checked.entrySet()) {
                ItemStack actual = sender.getInventory().getItem(entry.getKey());
                int remaining = actual.getAmount() - entry.getValue().getAmount();
                if (remaining == 0) sender.getInventory().setItem(entry.getKey(), null);
                else actual.setAmount(remaining);

                ItemStack delivery = entry.getValue().clone();
                Map<Integer, ItemStack> leftovers = target.getInventory().addItem(delivery);
                for (ItemStack leftover : leftovers.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover);
                }
                auditRows.add(new TransportLog(delivery.getType().name(), delivery.getAmount()));
            }
        } catch (RuntimeException e) {
            if (quote.fee() > 0) plugin.vaultHook().deposit(sender, quote.fee());
            sender.sendMessage("§c物流发送失败，已退还物流费。");
            plugin.getLogger().warning("Delivery failed: " + e.getMessage());
            return DeliveryResult.failed("delivery failed");
        }

        recordBatchAsync(sender, target, auditRows, quote);

        int itemCount = checked.values().stream().mapToInt(ItemStack::getAmount).sum();
        String feeText = quote.free() ? "免费" : plugin.vaultHook().format(quote.fee());
        sender.sendMessage("§a已发送 " + checked.size() + " 组、" + itemCount + " 个物品给 " + target.getName()
                + "，物流费 " + feeText + "。");
        target.sendMessage("§a收到来自 " + sender.getName() + " 的物流：" + checked.size() + " 组、" + itemCount + " 个物品。");
        return new DeliveryResult(true, checked.size(), itemCount, quote, "");
    }

    private void recordBatchAsync(Player sender, Player target, List<TransportLog> rows, Quote quote) {
        if (rows.isEmpty()) return;
        String senderUuid = sender.getUniqueId().toString();
        String targetUuid = target.getUniqueId().toString();
        String senderWorld = sender.getWorld().getName();
        String targetWorld = target.getWorld().getName();
        long now = System.currentTimeMillis() / 1000;
        plugin.asyncWorkPool().execute(() -> {
            try (Connection conn = plugin.ksCore().dataStore().getConnection()) {
                if (conn == null) return;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ks_eco_transport_log (sender_uuid,target_uuid,item_material,quantity,source_world,target_world,distance,cross_world,fee,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    for (TransportLog row : rows) {
                        ps.setString(1, senderUuid);
                        ps.setString(2, targetUuid);
                        ps.setString(3, row.material());
                        ps.setInt(4, row.quantity());
                        ps.setString(5, senderWorld);
                        ps.setString(6, targetWorld);
                        ps.setDouble(7, quote.distance());
                        ps.setInt(8, quote.crossWorld() ? 1 : 0);
                        ps.setDouble(9, quote.fee());
                        ps.setLong(10, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("记录物流流水失败: " + e.getMessage());
            }
        });
    }

    public record Quote(double distance, boolean crossWorld, double fee, boolean free) {}

    private record TransportLog(String material, int quantity) {}

    public record DeliveryResult(boolean success, int stacks, int items, Quote quote, String error) {
        static DeliveryResult failed(String error) {
            return new DeliveryResult(false, 0, 0, null, error);
        }
    }
}
