package org.itemedit;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 兑换券检测与扣除。
 *
 * 优先级：MythicMobs API → NBT 兜底 → Material 匹配
 */
public final class VoucherUtil {

    private VoucherUtil() {}

    /** 在玩家背包中查找兑换券，返回槽位索引，-1 表示未找到。 */
    public static int findVoucher(Player player, ConfigurationSection config) {
        String mode = config.getString("voucher.mode", "MYTHICMOBS").toUpperCase();
        int needed = config.getInt("voucher.amount", 1);
        ItemStack[] contents = player.getInventory().getContents();

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) continue;
            if (item.getAmount() < needed) continue;
            if (matches(item, config, mode)) return i;
        }
        return -1;
    }

    public static boolean hasVoucher(Player player, ConfigurationSection config) {
        return findVoucher(player, config) >= 0;
    }

    /** 扣除兑换券。调用前应先确认有券。 */
    public static boolean consumeVoucher(Player player, ConfigurationSection config) {
        int slot = findVoucher(player, config);
        if (slot < 0) return false;
        int needed = config.getInt("voucher.amount", 1);
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null) return false;
        int newAmount = item.getAmount() - needed;
        if (newAmount <= 0) {
            player.getInventory().setItem(slot, null);
        } else {
            item.setAmount(newAmount);
        }
        return true;
    }

    /** 人类可读的兑换券描述。 */
    public static String voucherDescription(ConfigurationSection config) {
        String mode = config.getString("voucher.mode", "MYTHICMOBS").toUpperCase();
        if ("MYTHICMOBS".equals(mode)) {
            return "MythicMobs:" + config.getString("voucher.mythicmobs-id", "?");
        }
        String name = config.getString("voucher.name-contains", "");
        String desc = config.getString("voucher.material", "?");
        if (!name.isEmpty()) desc += "(\"" + name + "\")";
        return desc;
    }

    // ---- 内部 ----

    private static boolean matches(ItemStack item, ConfigurationSection config, String mode) {
        if ("MYTHICMOBS".equals(mode)) {
            String mmId = config.getString("voucher.mythicmobs-id");
            if (mmId != null && !mmId.isEmpty()) {
                return MythicMobsHook.isMythicMobsItem(item, mmId);
            }
            // MM ID 为空时回退到 Material 匹配
            return matchesByMaterial(item, config);
        }
        return matchesByMaterial(item, config);
    }

    private static boolean matchesByMaterial(ItemStack item, ConfigurationSection config) {
        String matName = config.getString("voucher.material");
        if (matName == null) return false;
        Material material = Material.matchMaterial(matName);
        if (material == null || item.getType() != material) return false;

        String nameContains = config.getString("voucher.name-contains", "");
        if (!nameContains.isEmpty()) {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return false;
            if (!TextUtil.plain(meta.displayName()).contains(nameContains)) return false;
        }

        String loreContains = config.getString("voucher.lore-contains", "");
        if (!loreContains.isEmpty()) {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasLore()) return false;
            List<Component> loreLines = meta.lore();
            if (loreLines == null) return false;
            boolean found = false;
            for (Component line : loreLines) {
                if (TextUtil.plain(line).contains(loreContains)) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }
}
