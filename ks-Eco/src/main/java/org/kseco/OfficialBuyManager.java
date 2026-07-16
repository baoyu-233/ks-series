package org.kseco;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Handles player sales to the configured official buyer. */
public final class OfficialBuyManager {

    private final KsEco plugin;
    private final PriceEngine priceEngine;

    public OfficialBuyManager(KsEco plugin, PriceEngine priceEngine) {
        this.plugin = plugin;
        this.priceEngine = priceEngine;
    }

    public Set<String> getAcceptedMaterials() {
        Set<String> materials = new LinkedHashSet<>();
        for (String material : priceEngine.getAllPrices().keySet()) {
            if (priceEngine.getOfficialBuyPrice(material) > 0) materials.add(material.toUpperCase());
        }
        return materials;
    }

    public double getPrice(String material) {
        return priceEngine.getOfficialBuyPrice(material);
    }

    public double calculateTotal(ItemStack... items) {
        double total = 0.0;
        for (ItemStack item : items) {
            total += extractAccepted(item, Integer.MAX_VALUE).totalValue();
        }
        return total;
    }

    public boolean buyFromPlayer(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage("§c主手没有物品。");
            return false;
        }
        return plugin.marketManager().sellToOfficial(player, hand);
    }

    /** 生成可收购物品的真实扣除计划；潜影盒本体和不收购的内容会保留。 */
    public OfficialExtraction extractAccepted(ItemStack source, int requestedQuantity) {
        ShulkerBoxParser.RemovalResult removal = plugin.shulkerBoxParser().removeMatching(source,
                item -> priceEngine.getOfficialBuyPrice(item.getType().name()) > 0,
                requestedQuantity);
        double value = 0.0;
        for (ItemStack item : removal.removedItems()) {
            value += priceEngine.getOfficialBuyPrice(item.getType().name()) * item.getAmount();
        }
        return new OfficialExtraction(removal.updatedSource(), removal.removedItems(),
                removal.removedQuantity(), value);
    }

    public SellAllPreview previewSellAll(Player player) {
        Map<String, SellAllLine> lines = new LinkedHashMap<>();
        double total = 0.0;
        int shulkerBoxes = 0;

        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) continue;

            OfficialExtraction extraction = extractAccepted(item, Integer.MAX_VALUE);
            if (!extraction.changed()) continue;
            if (ShulkerBoxParser.isShulkerBox(item)) shulkerBoxes++;
            for (ItemStack sold : extraction.removedItems()) {
                String material = sold.getType().name();
                double unitPrice = priceEngine.getOfficialBuyPrice(material);
                double lineTotal = unitPrice * sold.getAmount();
                lines.merge(material, new SellAllLine(material, sold.getAmount(), unitPrice, lineTotal),
                        (oldLine, newLine) -> new SellAllLine(material,
                                oldLine.quantity() + newLine.quantity(), unitPrice,
                                oldLine.lineTotal() + newLine.lineTotal()));
            }
            total += extraction.totalValue();
        }

        return new SellAllPreview(List.copyOf(lines.values()), total, shulkerBoxes);
    }

    /** 执行时重新解析当前背包；付款失败会逐槽恢复原始物品。 */
    public double executeSellAll(Player player) {
        List<SoldSlot> sales = new ArrayList<>();
        double totalValue = 0.0;
        int totalItems = 0;
        int shulkerBoxes = 0;

        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            OfficialExtraction extraction = extractAccepted(item, Integer.MAX_VALUE);
            if (!extraction.changed()) continue;
            sales.add(new SoldSlot(slot, item.clone(), extraction.updatedSource(), extraction.removedItems(),
                    extraction.totalValue()));
            totalValue += extraction.totalValue();
            totalItems += extraction.quantity();
            if (ShulkerBoxParser.isShulkerBox(item)) shulkerBoxes++;
        }

        if (totalValue <= 0) {
            player.sendMessage("§c背包中没有官方收购的物品。");
            return 0.0;
        }

        for (SoldSlot sale : sales) {
            ItemStack current = player.getInventory().getItem(sale.slot());
            if (!sameStack(current, sale.original())) {
                player.sendMessage("§c背包物品已变化，请重新打开出售确认界面。");
                return 0.0;
            }
        }
        for (SoldSlot sale : sales) {
            player.getInventory().setItem(sale.slot(), cloneOrNull(sale.updated()));
        }
        if (!plugin.vaultHook().deposit(player, totalValue)) {
            for (SoldSlot sale : sales) player.getInventory().setItem(sale.slot(), sale.original().clone());
            player.sendMessage("§c官方付款失败，背包物品已恢复。");
            return 0.0;
        }

        List<PriceEngine.TradeRecord> trades = new ArrayList<>();
        String sellerUuid = player.getUniqueId().toString();
        for (SoldSlot sale : sales) {
            for (ItemStack sold : sale.removed()) {
                double unitPrice = priceEngine.getOfficialBuyPrice(sold.getType().name());
                trades.add(new PriceEngine.TradeRecord(sold.getType().name(), sold.getAmount(),
                        unitPrice, "OFFICIAL", sellerUuid, "OFFICIAL_SELL"));
            }
        }
        if (!trades.isEmpty()) {
            List<PriceEngine.TradeRecord> completedTrades = List.copyOf(trades);
            plugin.asyncWorkPool().execute(() -> priceEngine.recordTrades(completedTrades));
        }
        player.updateInventory();
        player.sendMessage("§a官方批量收购完成：出售 " + totalItems + " 个物品，获得 "
                + plugin.vaultHook().format(totalValue)
                + (shulkerBoxes > 0 ? "§a；已保留 " + shulkerBoxes + " 个潜影盒及未收购内容" : ""));
        return totalValue;
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        if (left == null || right == null) return left == right;
        return left.getAmount() == right.getAmount() && left.isSimilar(right);
    }

    public record SellAllLine(String material, int quantity, double unitPrice, double lineTotal) {}

    public record SellAllPreview(List<SellAllLine> lines, double total, int shulkerBoxes) {}

    public record OfficialExtraction(ItemStack updatedSource, List<ItemStack> removedItems,
                                     int quantity, double totalValue) {
        public boolean changed() { return quantity > 0 && totalValue > 0; }
    }

    private record SoldSlot(int slot, ItemStack original, ItemStack updated,
                            List<ItemStack> removed, double value) {}
}
