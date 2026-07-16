package org.kseco;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 潜影盒深度解析器。
 *
 * 核心功能：
 * 1. 检测物品是否为潜影盒
 * 2. 递归解析盒内所有物品（支持嵌套潜影盒）
 * 3. 计算盒内所有物品的总数量与总价值
 * 4. 最大递归深度限制（防恶意嵌套）
 *
 * 关键：绝不以单一物品处理潜影盒，必须深度遍历。
 */
public final class ShulkerBoxParser {

    private final KsEco plugin;

    public ShulkerBoxParser(KsEco plugin) {
        this.plugin = plugin;
    }

    // ---- 潜影盒材质判断 ----

    /** 所有潜影盒材质 */
    private static final Material[] SHULKER_BOXES = {
            Material.SHULKER_BOX,
            Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX, Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX, Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX
    };

    public static boolean isShulkerBox(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material type = item.getType();
        for (Material m : SHULKER_BOXES) {
            if (type == m) return true;
        }
        return false;
    }

    // ---- 解析入口 ----

    /**
     * 深度解析潜影盒内容。
     * @param item 潜影盒物品
     * @return 解析结果（物品列表、总数量、总价值）
     */
    public ShulkerContents parse(ItemStack item) {
        return parseRecursive(item, 0);
    }

    /**
     * 计算潜影盒及其内容物的总价值（供 PriceEngine 调用）。
     */
    public double calculateValue(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0.0;

        // 非潜影盒：按基础材质价格
        if (!isShulkerBox(item)) {
            String mat = item.getType().name();
            return plugin.priceEngine().getOfficialBuyPrice(mat) * item.getAmount();
        }

        // 潜影盒：空盒价值 + 内容物价值
        ShulkerContents contents = parse(item);
        double total = plugin.ecoConfig().getEmptyBoxValue();
        for (ItemEntry entry : contents.items) {
            total += plugin.priceEngine().getOfficialBuyPrice(entry.material) * entry.quantity;
        }
        return total;
    }

    // ---- 递归解析 ----

    private ShulkerContents parseRecursive(ItemStack item, int depth) {
        ShulkerContents result = new ShulkerContents();
        if (item == null || item.getType().isAir()) return result;

        int maxDepth = plugin.ecoConfig().getMaxRecursionDepth();

        if (!isShulkerBox(item)) {
            // 普通物品：直接计入
            result.items.add(new ItemEntry(
                    item.getType().name(),
                    item.getAmount(),
                    item.getItemMeta() != null && item.getItemMeta().hasDisplayName()
                            ? item.getItemMeta().getDisplayName() : null
            ));
            result.totalQuantity = item.getAmount();
            return result;
        }

        // 超过最大递归深度 → 视为不可打开的容器
        if (depth >= maxDepth) {
            result.items.add(new ItemEntry(item.getType().name(), 1, "§c[递归深度超限]"));
            result.totalQuantity = 1;
            return result;
        }

        // 打开潜影盒，遍历内部物品
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta bsm) {
            if (bsm.getBlockState() instanceof ShulkerBox shulker) {
                Inventory inv = shulker.getInventory();
                for (ItemStack slot : inv.getContents()) {
                    if (slot == null || slot.getType().isAir()) continue;

                    if (isShulkerBox(slot) && plugin.ecoConfig().isCountShulkerContents()) {
                        // 递归解析嵌套潜影盒
                        ShulkerContents nested = parseRecursive(slot, depth + 1);
                        result.items.addAll(nested.items);
                        result.totalQuantity += nested.totalQuantity;
                    } else {
                        result.items.add(new ItemEntry(
                                slot.getType().name(),
                                slot.getAmount(),
                                slot.getItemMeta() != null && slot.getItemMeta().hasDisplayName()
                                        ? slot.getItemMeta().getDisplayName() : null
                        ));
                        result.totalQuantity += slot.getAmount();
                    }
                }
            }
        }

        return result;
    }

    /**
     * 从潜影盒物品中提取内容物 ItemStack 列表（用于 GUI 显示/暂存箱提取）。
     */
    public List<ItemStack> extractContents(ItemStack shulkerItem) {
        List<ItemStack> contents = new ArrayList<>();
        if (!isShulkerBox(shulkerItem)) {
            if (shulkerItem != null && !shulkerItem.getType().isAir()) contents.add(shulkerItem.clone());
            return contents;
        }

        extractContentsRecursive(shulkerItem, 0, contents);
        return contents;
    }

    private void extractContentsRecursive(ItemStack shulkerItem, int depth, List<ItemStack> contents) {
        if (shulkerItem == null || !isShulkerBox(shulkerItem)
                || depth >= plugin.ecoConfig().getMaxRecursionDepth()) return;

        ItemMeta meta = shulkerItem.getItemMeta();
        if (meta instanceof BlockStateMeta bsm) {
            if (bsm.getBlockState() instanceof ShulkerBox shulker) {
                for (ItemStack slot : shulker.getInventory().getContents()) {
                    if (slot != null && !slot.getType().isAir()) {
                        if (isShulkerBox(slot) && plugin.ecoConfig().isCountShulkerContents()) {
                            extractContentsRecursive(slot, depth + 1, contents);
                        } else {
                            contents.add(slot.clone());
                        }
                    }
                }
            }
        }
    }

    /**
     * 从普通物品或潜影盒内容中安全扣除匹配物品。传入物品不会被修改；
     * 调用方必须使用 {@link RemovalResult#updatedSource()} 替换原物品后才算完成扣除。
     */
    public RemovalResult removeMatching(ItemStack source, Predicate<ItemStack> matcher, int requestedQuantity) {
        if (source == null || source.getType().isAir() || matcher == null || requestedQuantity <= 0) {
            return new RemovalResult(source == null ? null : source.clone(), List.of(), 0);
        }

        ItemStack updated = source.clone();
        List<ItemStack> removed = new ArrayList<>();
        if (!isShulkerBox(updated)) {
            if (!matcher.test(updated)) return new RemovalResult(updated, List.of(), 0);
            int take = Math.min(updated.getAmount(), requestedQuantity);
            ItemStack sold = updated.clone();
            sold.setAmount(take);
            removed.add(sold);
            int remaining = updated.getAmount() - take;
            if (remaining <= 0) updated = null;
            else updated.setAmount(remaining);
            return new RemovalResult(updated, List.copyOf(removed), take);
        }

        // Vanilla shulker boxes are unstackable. Refuse malformed stacked containers so
        // one serialized inventory can never be paid more than once.
        if (updated.getAmount() != 1) return new RemovalResult(updated, List.of(), 0);
        int count = removeFromShulker(updated, matcher, requestedQuantity, 0, removed);
        return new RemovalResult(updated, List.copyOf(removed), count);
    }

    public int countMatching(ItemStack source, Predicate<ItemStack> matcher) {
        return removeMatching(source, matcher, Integer.MAX_VALUE).removedQuantity();
    }

    public boolean hasContents(ItemStack source) {
        return isShulkerBox(source) && !extractContents(source).isEmpty();
    }

    private int removeFromShulker(ItemStack container, Predicate<ItemStack> matcher, int requested,
                                  int depth, List<ItemStack> removed) {
        if (requested <= 0 || depth >= plugin.ecoConfig().getMaxRecursionDepth()) return 0;
        ItemMeta rawMeta = container.getItemMeta();
        if (!(rawMeta instanceof BlockStateMeta blockMeta)
                || !(blockMeta.getBlockState() instanceof ShulkerBox shulker)) return 0;

        ItemStack[] slots = shulker.getInventory().getContents();
        int totalRemoved = 0;
        for (int i = 0; i < slots.length && totalRemoved < requested; i++) {
            ItemStack slot = slots[i];
            if (slot == null || slot.getType().isAir()) continue;
            int needed = requested - totalRemoved;

            if (isShulkerBox(slot) && plugin.ecoConfig().isCountShulkerContents()) {
                ItemStack nested = slot.clone();
                int nestedRemoved = removeFromShulker(nested, matcher, needed, depth + 1, removed);
                if (nestedRemoved > 0) {
                    slots[i] = nested;
                    totalRemoved += nestedRemoved;
                }
                continue;
            }
            if (!matcher.test(slot)) continue;

            int take = Math.min(slot.getAmount(), needed);
            ItemStack sold = slot.clone();
            sold.setAmount(take);
            removed.add(sold);
            int remaining = slot.getAmount() - take;
            if (remaining <= 0) slots[i] = null;
            else {
                ItemStack remainder = slot.clone();
                remainder.setAmount(remaining);
                slots[i] = remainder;
            }
            totalRemoved += take;
        }

        if (totalRemoved > 0) {
            shulker.getInventory().setContents(slots);
            blockMeta.setBlockState(shulker);
            container.setItemMeta(blockMeta);
        }
        return totalRemoved;
    }

    // ---- 数据类 ----

    public static class ShulkerContents {
        public final List<ItemEntry> items = new ArrayList<>();
        public int totalQuantity = 0;
    }

    public static class ItemEntry {
        public final String material;
        public final int quantity;
        public final String displayName;

        public ItemEntry(String material, int quantity, String displayName) {
            this.material = material;
            this.quantity = quantity;
            this.displayName = displayName;
        }
    }

    public record RemovalResult(ItemStack updatedSource, List<ItemStack> removedItems, int removedQuantity) {
        public boolean changed() {
            return removedQuantity > 0;
        }
    }
}
