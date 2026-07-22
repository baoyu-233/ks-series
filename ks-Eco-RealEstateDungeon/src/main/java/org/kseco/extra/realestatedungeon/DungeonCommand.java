package org.kseco.extra.realestatedungeon;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.kseco.KsEco;
import org.kseries.instanceworld.api.InstanceWorldApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /dungeon 副本指令（玩家端纯指令操作）。
 *
 * 子命令：
 *   /dungeon                  面板（模板/队伍/状态/网格）
 *   /dungeon invite <玩家>    队长邀请（受模板 min/max 人数限制，在 start 时校验）
 *   /dungeon accept           接受邀请入队
 *   /dungeon party            查看当前队伍
 *   /dungeon start <模板ID>   队长开本（校验队伍人数 ∈ [minPlayers,maxPlayers]，全队进入）
 *   /dungeon leave            离开副本 / 退出队伍
 *   /dungeon revive           在副本内付费复活
 *
 * 注：副本内房产已迁至 ks-Eco-RealEstate，本指令不再涉及房产。
 */
public final class DungeonCommand implements CommandExecutor {

    private final KsEco eco;
    private final DungeonInstanceManager instanceManager;
    private final InstanceWorldApi instanceWorld;
    private final DungeonPartyManager partyManager;
    private final DungeonDeathHandler deathHandler;

    public DungeonCommand(KsEco eco, DungeonInstanceManager instanceManager,
                          InstanceWorldApi instanceWorld, DungeonPartyManager partyManager,
                          DungeonDeathHandler deathHandler) {
        this.eco = eco;
        this.instanceManager = instanceManager;
        this.instanceWorld = instanceWorld;
        this.partyManager = partyManager;
        this.deathHandler = deathHandler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("[副本] 仅限玩家使用"); return true; }
        if (!player.hasPermission("kseco.admin") && !eco.featureGate().isOpen("dungeon")) {
            player.sendMessage("§c该功能暂未开放，敬请期待。");
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (args.length == 0) { showPanel(player, uuid); return true; }
        switch (args[0].toLowerCase()) {
            case "invite": return cmdInvite(player, uuid, args);
            case "accept": return cmdAccept(player, uuid);
            case "party":  return cmdParty(player, uuid);
            case "start":  return cmdStart(player, uuid, args);
            case "leave":  return cmdLeave(player, uuid);
            case "revive": return cmdRevive(player, uuid);
            default:
                player.sendMessage("§7用法: /dungeon [invite <玩家>|accept|party|start <模板ID>|leave|revive]");
                return true;
        }
    }

    private boolean cmdInvite(Player player, UUID uuid, String[] args) {
        if (args.length < 2) { player.sendMessage("§c用法: /dungeon invite <玩家>"); return true; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { player.sendMessage("§c玩家不在线: " + args[1]); return true; }
        String err = partyManager.invite(uuid, target.getUniqueId());
        if (err != null) { player.sendMessage("§c" + err); return true; }
        player.sendMessage("§a已邀请 §f" + target.getName() + " §a加入副本队伍（2 分钟内有效）");
        target.sendMessage("§6[副本] §f" + player.getName() + " §a邀请你组队，输入 §e/dungeon accept §a接受");
        return true;
    }

    private boolean cmdAccept(Player player, UUID uuid) {
        UUID leader = partyManager.accept(uuid);
        if (leader == null) { player.sendMessage("§c没有有效的副本组队邀请"); return true; }
        Player lp = Bukkit.getPlayer(leader);
        int size = partyManager.membersOf(leader).size();
        player.sendMessage("§a你已加入 §f" + (lp != null ? lp.getName() : leader) + " §a的副本队伍（当前 " + size + " 人）");
        if (lp != null) lp.sendMessage("§6[副本] §f" + player.getName() + " §a已加入队伍（当前 " + size + " 人）");
        return true;
    }

    private boolean cmdParty(Player player, UUID uuid) {
        UUID leader = partyManager.leaderOf(uuid);
        if (leader == null) { player.sendMessage("§7你当前没有队伍（单人开本将以 1 人计）"); return true; }
        List<UUID> members = partyManager.membersOf(leader);
        player.sendMessage("§6§l===== 副本队伍 (" + members.size() + " 人) =====");
        for (UUID m : members) {
            Player mp = Bukkit.getPlayer(m);
            String tag = m.equals(leader) ? " §7(队长)" : "";
            player.sendMessage("  §a- §f" + (mp != null ? mp.getName() : m.toString()) + tag);
        }
        return true;
    }

    private boolean cmdStart(Player player, UUID uuid, String[] args) {
        if (args.length < 2) { player.sendMessage("§c用法: /dungeon start <模板ID>（/dungeon 查看模板）"); return true; }
        if (instanceManager.getPlayerActiveInstance(uuid) != null) { player.sendMessage("§c你已在副本中，先 /dungeon leave"); return true; }
        String templateId = args[1];
        Map<String, Object> tpl = instanceManager.getTemplate(templateId);
        if (tpl == null) { player.sendMessage("§c模板不存在: " + templateId); return true; }
        int minP = ((Number) tpl.get("minPlayers")).intValue();
        int maxP = ((Number) tpl.get("maxPlayers")).intValue();
        double price = ((Number) tpl.get("ticketPrice")).doubleValue();

        UUID leader = partyManager.leaderOf(uuid);
        List<UUID> members;
        if (leader == null) {
            members = new ArrayList<>(List.of(uuid));   // 单人
        } else {
            if (!leader.equals(uuid)) { player.sendMessage("§c只有队长可以开本"); return true; }
            members = partyManager.membersOf(leader);
        }
        // 仅统计在线成员（离线成员无法被传送进副本）
        List<UUID> online = new ArrayList<>();
        for (UUID m : members) { Player mp = Bukkit.getPlayer(m); if (mp != null && mp.isOnline()) online.add(m); }
        int n = online.size();
        if (n < minP) { player.sendMessage("§c人数不足：该副本需至少 §e" + minP + " §c人（当前在线 " + n + "），用 /dungeon invite 拉人"); return true; }
        if (n > maxP) { player.sendMessage("§c人数超限：该副本最多 §e" + maxP + " §c人（当前 " + n + "）"); return true; }

        String denyMsg = instanceManager.checkPropertyKey(uuid, templateId);
        if (denyMsg != null) { player.sendMessage("§c" + denyMsg); return true; }

        player.sendMessage("§6[副本] §7开启中（门票 " + price + "，队伍 " + n + " 人）...");
        UUID partyLeader = leader;
        instanceManager.createInstanceForPartyAsync(templateId, uuid, player.getName(), online)
                .whenComplete((instanceId, failure) -> {
                    Player currentPlayer = Bukkit.getPlayer(uuid);
                    if (failure != null || instanceId == null) {
                        if (currentPlayer != null) currentPlayer.sendMessage(
                                "§c开本失败（余额不足/成员已有副本/模板或数据库异常）");
                        return;
                    }
                    if (partyLeader != null) partyManager.disband(partyLeader);
                });
        // 世界异步就绪后统一传送全队（见 DungeonInstanceManager.activateAndTeleportAll）
        return true;
    }

    private boolean cmdLeave(Player player, UUID uuid) {
        String active = instanceManager.getPlayerActiveInstance(uuid);
        if (active != null) {
            boolean ok = instanceManager.leaveInstance(active, uuid);
            player.sendMessage(ok ? "§a已离开副本" : "§c离开失败");
            return true;
        }
        if (partyManager.leave(uuid)) { player.sendMessage("§a已离开/解散副本队伍"); return true; }
        player.sendMessage("§7你不在副本，也不在队伍中");
        return true;
    }

    private boolean cmdRevive(Player player, UUID uuid) {
        String active = instanceManager.getPlayerActiveInstance(uuid);
        if (active == null) { player.sendMessage("§c你不在任何副本中"); return true; }
        player.sendMessage("§7正在确认付费复活，请勿重复提交……");
        deathHandler.reviveAsync(active, uuid).whenComplete((result, failure) ->
                instanceManager.runOnServerThread(() -> sendReviveResult(player, result, failure)));
        return true;
    }

    private void sendReviveResult(Player player, Double asyncResult, Throwable failure) {
        double result = failure == null && asyncResult != null ? asyncResult : DungeonDeathHandler.ERR_PERSIST;
        if (result < 0) {
            String msg = switch ((int) result) {
                case (int) DungeonDeathHandler.ERR_LIMIT -> "达到复活上限";
                case (int) DungeonDeathHandler.ERR_VAULT -> "Vault 不可用";
                case (int) DungeonDeathHandler.ERR_BALANCE -> "余额不足";
                case (int) DungeonDeathHandler.ERR_WITHDRAW -> "扣款失败";
                case (int) DungeonDeathHandler.ERR_INSTANCE -> "副本不存在、已结束或不属于你";
                case (int) DungeonDeathHandler.ERR_NOT_DEAD -> "当前状态不可复活";
                case (int) DungeonDeathHandler.ERR_PERSIST -> "复活记录失败，已尝试退款";
                case (int) DungeonDeathHandler.ERR_OFFLINE -> "玩家必须在线才能实际回场";
                default -> "复活失败";
            };
            player.sendMessage("§c复活失败: " + msg);
        } else {
            player.sendMessage("§a复活成功，扣费 §e" + result);
        }
    }

    private void showPanel(Player player, UUID uuid) {
        player.sendMessage("§6§l========== 副本大厅 ==========");
        List<Map<String, Object>> templates = instanceManager.listTemplates();
        player.sendMessage("§e▸ 可用副本模板 §7(" + templates.size() + " 个)");
        if (templates.isEmpty()) {
            player.sendMessage("  §7暂无副本，请联系管理员在后台配置");
        } else {
            for (Map<String, Object> t : templates) {
                player.sendMessage("  §a- §f" + t.get("name") + " §7(" + t.get("id") + ")");
                player.sendMessage("      §7门票 §e" + ((Number) t.get("ticketPrice")).doubleValue()
                        + " §7| 人数 §f" + ((Number) t.get("minPlayers")).intValue() + "-" + ((Number) t.get("maxPlayers")).intValue()
                        + " §7| 时限 §f" + ((Number) t.get("timeLimitMinutes")).intValue() + "分");
            }
        }
        UUID leader = partyManager.leaderOf(uuid);
        if (leader != null) {
            player.sendMessage("§e▸ 你的队伍: §f" + partyManager.membersOf(leader).size() + " 人"
                    + (leader.equals(uuid) ? " §7(你是队长)" : ""));
        }
        String active = instanceManager.getPlayerActiveInstance(uuid);
        player.sendMessage(active != null ? "§e▸ 当前副本: §f" + active : "§7你当前不在副本内");
        player.sendMessage("§7剩余网格: §f" + instanceWorld.freeGridCount());
        player.sendMessage("§7指令: §f/dungeon invite <玩家> | accept | party | start <模板> | leave | revive");
        player.sendMessage("§6§l==============================");
    }
}
