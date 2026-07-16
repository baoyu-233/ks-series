package org.kstitle;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.kstitle.model.TitleDef;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 称号 GUI：分页展示可见/已持有称号，点击佩戴/摘下/购买。 */
public final class TitleMenu {

    private static final int PAGE_SIZE = 45; // 前5行放称号，第6行放翻页/关闭
    public static final int NAV_PREV = 45;
    public static final int NAV_CLOSE = 49;
    public static final int NAV_NEXT = 53;

    private TitleMenu() {}

    public static void open(KsTitle plugin, Player player, int page) {
        List<TitleDef> visible = plugin.titleManager().listDefs().stream()
            .filter(d -> d.enabled() && (d.visible() || plugin.titleManager().hasOwnership(player.getUniqueId(), d.id())))
            .collect(Collectors.toList());

        int totalPages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int clampedPage = Math.max(0, Math.min(page, totalPages - 1));
        int from = clampedPage * PAGE_SIZE;
        int to = Math.min(visible.size(), from + PAGE_SIZE);
        List<TitleDef> pageDefs = visible.subList(from, to);
        List<Integer> pageIds = new ArrayList<>();
        for (TitleDef d : pageDefs) pageIds.add(d.id());

        TitleMenuHolder holder = new TitleMenuHolder(player.getUniqueId(), clampedPage, pageIds);
        Inventory inv = Bukkit.createInventory(holder, 54,
            LegacyText.colorize("&8称号 &7(" + (clampedPage + 1) + "/" + totalPages + ")"));
        holder.setInventory(inv);

        Integer equipped = plugin.titleManager().getEquipped(player.getUniqueId());
        for (int i = 0; i < pageDefs.size(); i++) {
            inv.setItem(i, buildItem(plugin, player, pageDefs.get(i), equipped));
        }

        if (clampedPage > 0) inv.setItem(NAV_PREV, navItem(Material.ARROW, "&e← 上一页"));
        if (clampedPage < totalPages - 1) inv.setItem(NAV_NEXT, navItem(Material.ARROW, "&e下一页 →"));
        inv.setItem(NAV_CLOSE, navItem(Material.BARRIER, "&c关闭"));

        player.openInventory(inv);
    }

    private static ItemStack navItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(LegacyText.colorize(name));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack buildItem(KsTitle plugin, Player player, TitleDef def, Integer equipped) {
        boolean owned = plugin.titleManager().hasOwnership(player.getUniqueId(), def.id());
        boolean isEquipped = equipped != null && equipped == def.id();

        Material material = switch (def.rarity() == null ? "" : def.rarity()) {
            case "RARE" -> Material.BOOK;
            case "EPIC" -> Material.NAME_TAG;
            case "LEGENDARY" -> Material.NETHER_STAR;
            default -> Material.PAPER;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(LegacyText.colorize(def.displayName()));

        List<String> lore = new ArrayList<>();
        if (def.description() != null && !def.description().isBlank()) {
            lore.add(LegacyText.colorize(def.description()));
            lore.add("");
        }
        if (isEquipped) {
            lore.add("§a✔ 已佩戴 §7(点击摘下)");
            appendExpiry(plugin, player, def, lore);
        } else if (owned) {
            lore.add("§b已持有 §7(点击佩戴)");
            appendExpiry(plugin, player, def, lore);
        } else if (def.isPurchase()) {
            lore.add("§e价格: " + plugin.vaultHook().format(def.price()));
            lore.add("§7点击购买");
        } else if (def.isCondition()) {
            lore.add("§7解锁条件: " + describeCondition(def));
        } else {
            lore.add("§8仅限管理员发放");
        }
        meta.setLore(lore);
        if (isEquipped) {
            var glow = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
            if (glow != null) {
                meta.addEnchant(glow, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private static void appendExpiry(KsTitle plugin, Player player, TitleDef def, List<String> lore) {
        long expiresAt = plugin.titleManager().getOwnershipExpiresAt(player.getUniqueId(), def.id());
        if (expiresAt > 0L) {
            lore.add(LegacyText.colorize("&7到期: &f" + DurationParser.formatExpiry(expiresAt)));
        }
    }

    private static String describeCondition(TitleDef def) {
        if ("PERMISSION".equals(def.conditionType())) {
            return "拥有权限 " + def.conditionValue();
        }
        return def.conditionType() + " " + def.conditionValue();
    }
}
