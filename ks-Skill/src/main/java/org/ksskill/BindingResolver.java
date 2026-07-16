package org.ksskill;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.ksskill.model.SkillDef;

/**
 * 判定“玩家是否拥有某技能”——四种绑定来源满足任一即真：
 * 称号(PlayerTitle) / 权限(hasPermission) / 物品(手持·副手·护甲的 PDC 标记) / 指令授予(GrantStore)。
 */
public final class BindingResolver {

    private final TitleBuffCache titles;
    private final GrantStore grants;
    private final NamespacedKey markKey;

    public BindingResolver(Plugin plugin, TitleBuffCache titles, GrantStore grants) {
        this.titles = titles;
        this.grants = grants;
        this.markKey = new NamespacedKey(plugin, "mark");
    }

    public boolean has(Player p, SkillDef def) {
        // 0. 世界限制（不在允许世界则直接判定为不拥有，force 调试指令绕过此路径）
        if (!def.allowedInWorld(p.getWorld().getName())) return false;
        // 1. 称号
        if (!def.titles().isEmpty()) {
            Integer t = titles.current(p);
            if (t != null && def.titles().contains(t)) return true;
        }
        // 2. 权限
        for (String node : def.permissions()) {
            if (node != null && !node.isBlank() && p.hasPermission(node)) return true;
        }
        // 3. 物品（手持 + 副手 + 护甲）
        if (!def.itemMarks().isEmpty()) {
            if (matchItem(p.getInventory().getItemInMainHand(), def)) return true;
            if (matchItem(p.getInventory().getItemInOffHand(), def)) return true;
            for (ItemStack armor : p.getInventory().getArmorContents()) {
                if (matchItem(armor, def)) return true;
            }
        }
        // 4. 指令授予
        return grants.has(p.getUniqueId(), def.id());
    }

    private boolean matchItem(ItemStack item, SkillDef def) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String mark = meta.getPersistentDataContainer().get(markKey, PersistentDataType.STRING);
        return mark != null && def.itemMarks().contains(mark);
    }
}
