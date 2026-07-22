package org.kseco.extra.realestate;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.kseco.KsEco;

import java.util.Map;
import java.util.UUID;

/**
 * 主世界地块领地保护：未受信任的玩家不能在别人地块范围内挖方块/放方块/开容器/互动方块；
 * 爆炸（TNT/凋零/重生锚等）波及地块范围内的方块也会被过滤掉。OP 不受限制。
 */
public final class PlotProtectionListener implements Listener {

    private final KsEco eco;
    private final RealEstateManager mgr;

    public PlotProtectionListener(KsEco eco, RealEstateManager mgr) {
        this.eco = eco;
        this.mgr = mgr;
    }

    private Map<String, Object> plotAt(Block block) {
        return mgr.findPlotAt(block.getWorld().getName(), block.getX(), block.getZ());
    }

    private void deny(Player player, Map<String, Object> plotOrHouse) {
        if ("cache-unavailable".equals(plotOrHouse.get("ownerId"))) {
            player.sendMessage("§c地产保护缓存暂时不可用，操作已被拦截。");
            return;
        }
        String ownerType = (String) plotOrHouse.get("ownerType");
        String who = RealEstateManager.OWNER_ENTERPRISE.equals(ownerType) ? "某个企业" : "别人";
        player.sendMessage("§c这是" + who + "的私有土地，你没有权限。");
    }

    /**
     * 两层权限判断：先看坐标是否落在某套登记房屋范围内（按房屋 owner/信任名单判断），
     * 没命中房屋再落回地块整体判断（地块所有者对院子/空地等未登记部分仍保有权限）。
     * @return null 表示放行（未落在任何地块/房屋内，或有权限）
     */
    private Map<String, Object> checkAccess(String world, int x, int y, int z, UUID actor, String kind) {
        if (!mgr.protectionCachesHealthy()) {
            // Fail closed while any protection cache is stale/unavailable.
            Map<String, Object> denied = new java.util.LinkedHashMap<>();
            denied.put("ownerType", RealEstateManager.OWNER_PLAYER);
            denied.put("ownerId", "cache-unavailable");
            return denied;
        }
        Map<String, Object> house = mgr.findHouseAt(world, x, y, z);
        if (house != null) {
            return mgr.canAccessHouse(house, actor, kind) ? null : house;
        }
        Map<String, Object> plot = mgr.findPlotAt(world, x, z);
        if (plot != null) {
            return mgr.canAccess(plot, actor, kind) ? null : plot;
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;
        Block block = event.getBlock();
        Map<String, Object> denied = checkAccess(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                player.getUniqueId(), "BUILD");
        if (denied != null) {
            event.setCancelled(true);
            deny(player, denied);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;
        Block block = event.getBlock();
        Map<String, Object> denied = checkAccess(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                player.getUniqueId(), "BUILD");
        if (denied != null) {
            event.setCancelled(true);
            deny(player, denied);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.isOp()) return;
        Object holder = event.getInventory().getHolder();
        if (!(holder instanceof Container) && !(holder instanceof DoubleChest)) return;
        Location loc = event.getInventory().getLocation();
        if (loc == null || loc.getWorld() == null) return;
        Map<String, Object> denied = checkAccess(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                player.getUniqueId(), "CONTAINER");
        if (denied != null) {
            event.setCancelled(true);
            deny(player, denied);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Player player = event.getPlayer();
        if (player.isOp()) return;
        if (!isProtectedInteractable(block.getType())) return;
        Map<String, Object> denied = checkAccess(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                player.getUniqueId(), "INTERACT");
        if (denied != null) {
            event.setCancelled(true);
            deny(player, denied);
        }
    }

    /** 只拦截"会改变世界状态"的右键方块互动：门/活板门/按钮/拉杆/床/重生锚/告示牌编辑/耕地等。容器另由 InventoryOpenEvent 处理。 */
    private boolean isProtectedInteractable(Material type) {
        return Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.BUTTONS.isTagged(type)
                || Tag.PRESSURE_PLATES.isTagged(type) || Tag.FENCE_GATES.isTagged(type)
                || type == Material.LEVER || type == Material.RESPAWN_ANCHOR
                || type.name().endsWith("_BED") || type.name().endsWith("_SIGN")
                || type == Material.FARMLAND || type == Material.FLOWER_POT
                || type == Material.JUKEBOX || type == Material.CAKE
                || type == Material.REPEATER || type == Material.COMPARATOR
                || type == Material.DAYLIGHT_DETECTOR || type == Material.LECTERN
                || type == Material.ANVIL || type == Material.GRINDSTONE
                || type == Material.CRAFTING_TABLE || type == Material.ENCHANTING_TABLE
                || type == Material.LOOM || type == Material.CARTOGRAPHY_TABLE
                || type == Material.STONECUTTER || type == Material.SMITHING_TABLE
                || type == Material.BEACON;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> plotAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> plotAt(b) != null);
    }
}
