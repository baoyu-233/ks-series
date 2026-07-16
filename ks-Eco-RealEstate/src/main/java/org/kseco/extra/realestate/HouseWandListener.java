package org.kseco.extra.realestate;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.kseco.KsEco;

/**
 * 房屋测量棒：左键点方块记 pos1，右键点方块记 pos2，配合 /house register 登记房屋范围。
 * 用 PersistentDataContainer 标记区分普通木棒，不依赖 WorldEdit/FAWE。
 */
public final class HouseWandListener implements Listener {

    private static final String WAND_KEY = "ks_house_wand";

    private final KsEco eco;
    private final RealEstateManager mgr;
    private final NamespacedKey wandKey;

    public HouseWandListener(KsEco eco, RealEstateManager mgr) {
        this.eco = eco;
        this.mgr = mgr;
        this.wandKey = new NamespacedKey(eco, WAND_KEY);
    }

    public ItemStack createWand() {
        ItemStack item = new ItemStack(Material.STICK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§e房屋测量棒"));
            meta.lore(java.util.List.of(
                    Component.text("§7左键点方块 = 设第一个角点"),
                    Component.text("§7右键点方块 = 设第二个角点"),
                    Component.text("§7选好后用 /house register <地块ID> <名称>")
            ));
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isWand(event.getItem())) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            mgr.setHouseSelectionPoint(player.getUniqueId(), block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ(), 1);
            player.sendMessage("§a已设第一个角点: §f" + block.getX() + "," + block.getY() + "," + block.getZ());
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            mgr.setHouseSelectionPoint(player.getUniqueId(), block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ(), 2);
            player.sendMessage("§a已设第二个角点: §f" + block.getX() + "," + block.getY() + "," + block.getZ());
        }
    }
}
