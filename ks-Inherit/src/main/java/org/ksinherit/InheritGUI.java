package org.ksinherit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * 物品继承 GUI — 箱子界面，玩家放入物品后关闭时自动保存。
 *
 * <p>布局：
 *   - 前 N 格：可用槽位（N = 玩家可用槽位数）
 *   - 其余格：灰色玻璃板锁定（不可交互）
 *   - 最后一行：操作按钮（保存 / 清空 / 关闭）
 */
public final class InheritGUI implements InventoryHolder {

    private static final int TOTAL_SLOTS = 54; // 6 行
    private static final int SAVE_SLOT = 49;   // 中间：保存并关闭
    private static final int CLEAR_SLOT = 48;  // 左侧：清空所有物品
    private static final int CLOSE_SLOT = 50;  // 右侧：关闭（不保存自动保存）

    private final KsInherit plugin;
    private final Player player;
    private final Inventory inv;
    private final int maxSlots;

    InheritGUI(KsInherit plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.maxSlots = plugin.getMaxSlots(player);

        String title = "§8物品继承 §7- §f" + maxSlots + "格可用";
        if (title.length() > 32) title = title.substring(0, 32);
        this.inv = Bukkit.createInventory(this, TOTAL_SLOTS, title);

        buildUI();
    }

    /** 构建界面：锁定格 + 加载已保存物品 + 按钮 */
    private void buildUI() {
        // 锁定格：灰色玻璃板填充不可用槽位
        ItemStack locked = lockedPane();
        for (int i = maxSlots; i < TOTAL_SLOTS; i++) {
            inv.setItem(i, locked);
        }

        // 加载已保存的物品
        Map<Integer, ItemStack> saved = plugin.loadItems(player);
        for (var entry : saved.entrySet()) {
            int slot = entry.getKey();
            if (slot >= 0 && slot < maxSlots) {
                inv.setItem(slot, entry.getValue());
            }
        }

        // 底部操作按钮
        inv.setItem(SAVE_SLOT, saveButton());
        inv.setItem(CLEAR_SLOT, clearButton());
        inv.setItem(CLOSE_SLOT, closeButton());
    }

    public void open() {
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
    }

    // --------------- 按钮 ---------------

    private ItemStack lockedPane() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§8§l🔒 已锁定");
        meta.setLore(List.of("§7该槽位不可用，请联系管理员升级", "§7当前可用: §f" + maxSlots + "格"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack saveButton() {
        ItemStack item = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§a§l💾 保存物品");
        meta.setLore(List.of("§7点击保存所有已放入的物品",
            "§7§o关闭 GUI 时也会自动保存"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack clearButton() {
        ItemStack item = new ItemStack(Material.RED_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§c§l🗑 清空全部");
        meta.setLore(List.of("§7点击清空你的所有存储物品", "§c§o此操作不可撤销！"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack closeButton() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e§l✖ 关闭");
        meta.setLore(List.of("§7关闭 GUI（物品会自动保存）"));
        item.setItemMeta(meta);
        return item;
    }

    // --------------- InventoryHolder ---------------

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }

    // --------------- 操作 ---------------

    /** 点击事件处理，返回 true 表示取消默认行为 */
    boolean handleClick(int rawSlot, boolean isShiftClick) {
        // 只处理 GUI 内点击
        if (rawSlot < 0 || rawSlot >= TOTAL_SLOTS) {
            // 玩家自己的背包：允许操作
            return false;
        }

        // 按钮
        if (rawSlot == SAVE_SLOT) {
            saveAll();
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
            return true;
        }
        if (rawSlot == CLEAR_SLOT) {
            clearAll();
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
            return true;
        }
        if (rawSlot == CLOSE_SLOT) {
            saveAll();
            player.closeInventory();
            return true;
        }

        // 锁定格：拒绝操作
        if (rawSlot >= maxSlots) {
            player.sendMessage("§c🔒 该槽位不可用（你的可用槽位: " + maxSlots + "），请联系管理员升级");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);
            return true;
        }

        // Shift 点击也做潜影盒检测
        if (isShiftClick) {
            ItemStack cursor = player.getInventory().getItem(0); // 不太准确，这里在事件层做
        }

        return false; // 允许物品放入
    }

    /** 检查物品并拒绝潜影盒，返回 true 表示放行 */
    boolean allowItem(ItemStack item) {
        if (item != null && KsInherit.isShulkerBox(item.getType())) {
            player.sendMessage("§c📦 不允许存入潜影盒！");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);
            return false;
        }
        return true;
    }

    /** 保存所有槽位物品 */
    void saveAll() {
        int saved = 0;
        int locked = 0;
        for (int i = 0; i < maxSlots; i++) {
            // 跳过 GUI 操作按钮
            if (i == SAVE_SLOT || i == CLEAR_SLOT || i == CLOSE_SLOT) continue;
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR
                    && item.getType() != Material.GRAY_STAINED_GLASS_PANE) {
                if (plugin.saveItem(player, i, item.clone())) {
                    saved++;
                } else {
                    // 该槽位已被审批/发放锁定，拒绝覆盖；把物品还给玩家背包，避免随 GUI 关闭丢失
                    locked++;
                    var leftover = player.getInventory().addItem(item.clone());
                    for (ItemStack is : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), is);
                    }
                    inv.setItem(i, null);
                }
            } else {
                // 空槽位：删除旧记录（已审批/已发放的记录不会被删除）
                plugin.deleteItem(player, i);
            }
        }
        if (saved > 0) {
            player.sendMessage("§a💾 Saved " + saved + " item(s)");
        }
        if (locked > 0) {
            player.sendMessage("§e⚠ " + locked + " 个槽位已被管理员审批/发放锁定，物品已退回你的背包");
        }
    }

    /** 清空所有物品 */
    void clearAll() {
        plugin.clearItems(player.getUniqueId());
        for (int i = 0; i < maxSlots; i++) {
            inv.clear(i);
        }
        player.sendMessage("§c🗑 所有物品已清空");
    }
}
