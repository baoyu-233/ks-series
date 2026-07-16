package org.kseco.extra.realestate;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.kseco.KsEco;

import java.util.Map;

/**
 * /house wand|register|list|info —— 房屋登记：圈定已购地块内的建筑范围、登记产权。
 */
public final class HouseCommand implements CommandExecutor {

    private final KsEco eco;
    private final RealEstateManager mgr;
    private final HouseWandListener wand;

    public HouseCommand(KsEco eco, RealEstateManager mgr, HouseWandListener wand) {
        this.eco = eco;
        this.mgr = mgr;
        this.wand = wand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("[房屋] 仅限玩家使用"); return true; }
        if (!player.hasPermission("kseco.admin") && !eco.featureGate().isOpen("realestate")) {
            player.sendMessage("§c该功能暂未开放，敬请期待。");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§e用法: /house <wand|register|list|info>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "wand" -> {
                player.getInventory().addItem(wand.createWand());
                player.sendMessage("§a已给予房屋测量棒，左键/右键点方块设定两个角点。");
            }
            case "info" -> showSelection(player);
            case "list" -> showHouses(player);
            case "register" -> register(player, args);
            case "unregister" -> unregister(player, args);
            case "area" -> area(player, args);
            case "sell" -> sell(player, args);
            default -> player.sendMessage("§e用法: /house <wand|register|unregister|area|list|info|sell>");
        }
        return true;
    }

    private void showSelection(Player player) {
        RealEstateManager.Selection sel = mgr.getHouseSelection(player.getUniqueId());
        if (sel == null || sel.pos1 == null || sel.pos2 == null) {
            player.sendMessage("§c你还没有选好两个角点，先用 /house wand 拿测量棒圈定。");
            return;
        }
        player.sendMessage("§e当前选区 (" + sel.world + "): §f["
                + sel.pos1[0] + "," + sel.pos1[1] + "," + sel.pos1[2] + "] - ["
                + sel.pos2[0] + "," + sel.pos2[1] + "," + sel.pos2[2] + "]");
    }

    private void showHouses(Player player) {
        var houses = mgr.listAccessibleHouses(player.getUniqueId());
        if (houses.isEmpty()) {
            player.sendMessage("§7你还没有登记任何房屋。");
            return;
        }
        player.sendMessage("§e你能管理的房屋（" + houses.size() + "）:");
        for (Map<String, Object> h : houses) {
            player.sendMessage("§7- §f" + h.get("id") + " §7[" + h.get("name") + "] 地块:" + h.get("plotId")
                    + " 世界:" + h.get("world") + " 范围:[" + h.get("x1") + "," + h.get("y1") + "," + h.get("z1")
                    + "]-[" + h.get("x2") + "," + h.get("y2") + "," + h.get("z2") + "]");
        }
    }

    private void sell(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§e用法: /house sell <房屋ID> <价格>");
            return;
        }
        double price;
        try { price = Double.parseDouble(args[2]); } catch (NumberFormatException e) {
            player.sendMessage("§c价格格式不对。");
            return;
        }
        eco.marketManager().listHouseForSale(player, args[1], price);
    }

    private void register(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§e用法: /house register <地块ID> <房屋名称>");
            return;
        }
        String plotId = args[1];
        String name = String.join(" ", java.util.Arrays.asList(args).subList(2, args.length));
        RealEstateManager.Selection sel = mgr.getHouseSelection(player.getUniqueId());
        if (sel == null || sel.pos1 == null || sel.pos2 == null) {
            player.sendMessage("§c你还没有选好两个角点，先用 /house wand 拿测量棒圈定。");
            return;
        }
        RealEstateManager.HouseResult result = mgr.registerHouse(plotId, player.getUniqueId(), sel.world,
                sel.pos1[0], sel.pos1[1], sel.pos1[2], sel.pos2[0], sel.pos2[1], sel.pos2[2], name);
        if (result.error != null) {
            player.sendMessage("§c登记失败: " + result.error);
            return;
        }
        mgr.clearHouseSelection(player.getUniqueId());
        player.sendMessage("§a房屋登记成功！房屋ID: §f" + result.houseId);
    }

    private void unregister(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§e用法: /house unregister <房屋ID>");
            return;
        }
        RealEstateManager.UnregisterResult result = mgr.unregisterHouse(args[1], player.getUniqueId());
        if (!result.success) {
            player.sendMessage("§c退登记失败: " + result.error);
            return;
        }
        player.sendMessage("§a房屋已退登记，该区域容积率名额已释放。");
    }

    /** /house area [x1 y1 z1 x2 y2 z2] —— 无参数时用当前测量棒选区，查该范围所在地块/区域的容积率占用情况。 */
    private void area(Player player, String[] args) {
        String world;
        int x1, z1, x2, z2;
        if (args.length >= 7) {
            try {
                x1 = Integer.parseInt(args[1]);
                z1 = Integer.parseInt(args[3]);
                x2 = Integer.parseInt(args[4]);
                z2 = Integer.parseInt(args[6]);
            } catch (NumberFormatException e) {
                player.sendMessage("§c坐标格式不对。用法: /house area <x1> <y1> <z1> <x2> <y2> <z2>");
                return;
            }
            world = player.getWorld().getName();
        } else {
            RealEstateManager.Selection sel = mgr.getHouseSelection(player.getUniqueId());
            if (sel == null || sel.pos1 == null || sel.pos2 == null) {
                player.sendMessage("§c你还没有选好两个角点，先用 /house wand 圈定，或用 /house area <x1> <y1> <z1> <x2> <y2> <z2> 直接指定范围。");
                return;
            }
            world = sel.world;
            x1 = sel.pos1[0]; z1 = sel.pos1[2];
            x2 = sel.pos2[0]; z2 = sel.pos2[2];
        }
        RealEstateManager.AreaCheckResult result = mgr.checkArea(world, x1, z1, x2, z2);
        if (result.plots.isEmpty()) {
            player.sendMessage("§7该范围不在任何已购地块内（也可能是个空地，去 ksHWP 地图先买地）。");
            return;
        }
        player.sendMessage("§e该范围涉及 " + result.plots.size() + " 个地块:");
        for (Map<String, Object> p : result.plots) {
            player.sendMessage("§7- 地块:§f" + p.get("plotId") + " §7区域:§f" + p.get("zoneName") + "(" + p.get("zoneId") + ")");
            int maxPlots = (int) p.get("zoneMaxPlots");
            String cap = maxPlots > 0 ? p.get("zoneRegisteredHouses") + "/" + maxPlots : "不限";
            player.sendMessage("§7  区域容积率: §f" + cap + (Boolean.TRUE.equals(p.get("full")) ? " §c(已满)" : "")
                    + " §7| 本地块已登记房屋: §f" + p.get("housesInThisPlot"));
        }
    }
}
