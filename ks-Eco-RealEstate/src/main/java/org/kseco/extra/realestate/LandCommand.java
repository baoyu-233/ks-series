package org.kseco.extra.realestate;

import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.kseco.KsEco;
import org.kseco.extra.realestate.gui.PlotListMenu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class LandCommand implements CommandExecutor {

    private final KsEco eco;
    private final RealEstateManager mgr;
    private final LandPerkManager perks;

    public LandCommand(KsEco eco, RealEstateManager mgr, LandPerkManager perks) {
        this.eco = eco;
        this.mgr = mgr;
        this.perks = perks;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[地产] 仅限玩家使用");
            return true;
        }

        if (args.length > 0) {
            String sub = args[0].toLowerCase();
            if (sub.equals("perkdebug") || sub.equals("debugperk")) return handlePerkDebug(player);
            if (sub.equals("growtest") || sub.equals("testgrow")) return handleGrowTest(player);
            if (sub.equals("reloadcache") || sub.equals("perkreload")) return handleReloadCache(player);
        }

        if (!player.hasPermission("kseco.admin") && !eco.featureGate().isOpen("realestate")) {
            player.sendMessage("§c该功能暂未开放，敬请期待。");
            return true;
        }
        new PlotListMenu(eco, mgr).open(player);
        return true;
    }

    private boolean handlePerkDebug(Player player) {
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage("§c需要 kseco.admin");
            return true;
        }

        Block target = player.getTargetBlockExact(8);
        Block block = target != null ? target : player.getLocation().getBlock();
        String source = target != null ? "准星方块" : "脚下方块";
        String world = block.getWorld().getName();
        int x = block.getX();
        int z = block.getZ();
        Map<String, Object> plot = mgr.findPlotAt(world, x, z);
        boolean agri = perks.isBlockEligibleForZonePerk(world, x, z, RealEstateManager.ZONE_TYPE_AGRICULTURAL);
        boolean industry = perks.isBlockEligibleForZonePerk(world, x, z, RealEstateManager.ZONE_TYPE_INDUSTRIAL);
        List<Map<String, Object>> allPlots = mgr.cachedPlots();
        List<Map<String, Object>> worldPlots = new ArrayList<>();
        for (Map<String, Object> cached : allPlots) {
            if (world.equals(String.valueOf(cached.get("world")))) worldPlots.add(cached);
        }

        player.sendMessage("§6[地块福利调试] §f目标: §e" + source + " §f" + world + " " + x + "," + block.getY() + "," + z);
        player.sendMessage("§7缓存地块: §f当前世界 " + worldPlots.size() + " 块 / 全服 " + allPlots.size() + " 块");
        if (plot == null) {
            player.sendMessage("§c未命中已购买地块。通常是坐标不在地块内、世界名不一致，或缓存未刷新。");
            sendNearestPlots(player, worldPlots, x, z);
        } else {
            player.sendMessage("§7地块: §f" + plot.get("id")
                    + " §7类型: §f" + plot.get("zoneType")
                    + " §7所有者: §f" + plot.get("ownerType") + ":" + plot.get("ownerId"));
            player.sendMessage("§7范围: §f[" + plot.get("x1") + "," + plot.get("z1") + "]-["
                    + plot.get("x2") + "," + plot.get("z2") + "]");
            if (RealEstateManager.OWNER_ENTERPRISE.equals(plot.get("ownerType"))) {
                String enterpriseId = String.valueOf(plot.get("ownerId"));
                int level = eco.enterpriseLevelManager().getLevel(enterpriseId);
                double multiplier = eco.enterpriseLevelManager().getLandPerkMultiplier(level);
                player.sendMessage("§7企业等级: §f" + level + " §7地块福利倍率: §a×"
                        + String.format(java.util.Locale.ROOT, "%.2f", multiplier));
            }
        }

        player.sendMessage("§7农业福利命中: " + yesNo(agri) + " §7工业福利命中: " + yesNo(industry));
        player.sendMessage("§7全局收获额外产出: §f" + perks.getPerkValue("agri_harvest_yield_bonus_chance", 0.20)
                + " §7全局熔炉额外产出: §f" + perks.getPerkValue("industry_furnace_bonus_output_chance", 0.10));
        if (plot != null) {
            Map<String, Object> cfg = perks.getPlotPerkConfig(String.valueOf(plot.get("id")));
            player.sendMessage("§7单块配置: §f" + (Boolean.TRUE.equals(cfg.get("configured")) ? "YES" : "NO")
                    + " §7额外农业: §f" + cfg.get("agriEnabled")
                    + " §7额外工业: §f" + cfg.get("industryEnabled"));
            String harvest = agri ? String.valueOf(perks.getBlockPerkValue(world, x, z,
                    RealEstateManager.ZONE_TYPE_AGRICULTURAL, "agri_harvest_yield_bonus_chance", 0.20)) : "未命中";
            String furnace = industry ? String.valueOf(perks.getBlockPerkValue(world, x, z,
                    RealEstateManager.ZONE_TYPE_INDUSTRIAL, "industry_furnace_bonus_output_chance", 0.10)) : "未命中";
            player.sendMessage("§7当前方块实际收获额外产出: §f" + harvest + " §7实际熔炉额外产出: §f" + furnace);
        }

        Map<String, Object> runtime = perks.getRuntimeStats();
        player.sendMessage("§7TPS: §f" + runtime.get("currentTps")
                + " §7农业TPS保护: " + yesNo(Boolean.TRUE.equals(runtime.get("agriPausedByTps")))
                + " §7工业TPS保护: " + yesNo(Boolean.TRUE.equals(runtime.get("industryPausedByTps"))));
        return true;
    }

    private void sendNearestPlots(Player player, List<Map<String, Object>> worldPlots, int x, int z) {
        if (worldPlots.isEmpty()) {
            player.sendMessage("§7当前世界缓存里没有任何已购买地块。可以试 /land reloadcache 后再看。");
            return;
        }
        worldPlots.stream()
                .sorted(Comparator.comparingInt(p -> distanceSqToPlot(p, x, z)))
                .limit(3)
                .forEach(p -> player.sendMessage("§7最近地块: §f" + p.get("id")
                        + " §7类型: §f" + p.get("zoneType")
                        + " §7所有者: §f" + p.get("ownerType") + ":" + p.get("ownerId")
                        + " §7范围: §f[" + p.get("x1") + "," + p.get("z1") + "]-["
                        + p.get("x2") + "," + p.get("z2") + "]"
                        + " §7距离平方: §f" + distanceSqToPlot(p, x, z)));
    }

    private int distanceSqToPlot(Map<String, Object> plot, int x, int z) {
        int x1 = asInt(plot.get("x1")), x2 = asInt(plot.get("x2"));
        int z1 = asInt(plot.get("z1")), z2 = asInt(plot.get("z2"));
        int dx = x < x1 ? x1 - x : (x > x2 ? x - x2 : 0);
        int dz = z < z1 ? z1 - z : (z > z2 ? z - z2 : 0);
        return dx * dx + dz * dz;
    }

    private boolean handleReloadCache(Player player) {
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage("§c需要 kseco.admin");
            return true;
        }
        mgr.refreshPlotCache();
        mgr.refreshHouseCache();
        perks.refreshPlotPerkCache();
        player.sendMessage("§a已刷新房地产地块/房屋/福利缓存。");
        return true;
    }

    private boolean handleGrowTest(Player player) {
        if (!player.hasPermission("kseco.admin")) {
            player.sendMessage("§c需要 kseco.admin");
            return true;
        }

        Block block = player.getTargetBlockExact(8);
        if (block == null || !(block.getBlockData() instanceof Ageable ageable)) {
            player.sendMessage("§c请准星对着一个可成长作物方块，再执行 /land growtest");
            return true;
        }

        String world = block.getWorld().getName();
        if (!perks.isBlockEligibleForZonePerk(world, block.getX(), block.getZ(),
                RealEstateManager.ZONE_TYPE_AGRICULTURAL)) {
            player.sendMessage("§c这个作物没有命中农业地块福利。先用 /land perkdebug 看原因。");
            return true;
        }

        int before = ageable.getAge();
        if (before >= ageable.getMaximumAge()) {
            player.sendMessage("§e这个作物已经成熟。");
            return true;
        }

        int steps = perks.getBlockGrowthSteps(world, block.getX(), block.getZ());
        int after = Math.min(before + Math.max(1, steps), ageable.getMaximumAge());
        ageable.setAge(after);
        block.setBlockData(ageable, true);
        player.sendMessage("§a成长测试完成: §f" + before + " §7-> §f" + after + " §7(当前方块推进 " + steps + " 阶)");
        return true;
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private String yesNo(boolean value) {
        return value ? "§a是" : "§c否";
    }
}
