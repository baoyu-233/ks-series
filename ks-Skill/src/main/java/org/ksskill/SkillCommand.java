package org.ksskill;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.ksskill.model.SkillDef;

import java.util.Locale;
import java.util.Set;

/**
 * /ksskill list|grant|revoke|test|reload —— 管理员维护技能与指令授予。
 */
public final class SkillCommand implements CommandExecutor {

    private final KsSkill plugin;

    public SkillCommand(KsSkill plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("ksskill.admin")) {
            sender.sendMessage("§c无权限");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§6[ks-Skill] §7v" + plugin.getDescription().getVersion());
            sender.sendMessage("§e/ksskill list [玩家] §7- 列出技能条目 / 某玩家生效中的技能");
            sender.sendMessage("§e/ksskill grant <玩家> <技能ID> §7- 指令授予技能");
            sender.sendMessage("§e/ksskill revoke <玩家> <技能ID> §7- 撤销授予");
            sender.sendMessage("§e/ksskill test <玩家> <技能ID> §7- 无视概率/冷却强制施放(调试)");
            sender.sendMessage("§e/ksskill reload §7- 重载 skills.yml");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadSkills();
                sender.sendMessage("§a已重载 skills.yml");
            }
            case "list" -> handleList(sender, args);
            case "grant" -> handleGrant(sender, args, true);
            case "revoke" -> handleGrant(sender, args, false);
            case "test" -> handleTest(sender, args);
            default -> sender.sendMessage("§c未知子命令: " + args[0]);
        }
        return true;
    }

    private void handleList(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
            Set<String> granted = plugin.grants().skillsOf(op.getUniqueId());
            sender.sendMessage("§6" + args[1] + " 被指令授予的技能: §f"
                    + (granted.isEmpty() ? "无" : String.join(", ", granted)));
            Player online = op.getPlayer();
            if (online != null) {
                StringBuilder sb = new StringBuilder();
                for (SkillDef def : plugin.registry().all()) {
                    if (plugin.binding().has(online, def)) sb.append(def.id()).append(' ');
                }
                sender.sendMessage("§6当前生效中: §f" + (sb.length() == 0 ? "无" : sb.toString().trim()));
            } else {
                sender.sendMessage("§7(玩家不在线，仅显示指令授予部分)");
            }
            return;
        }
        var all = plugin.registry().all();
        sender.sendMessage("§6已配置技能 (" + all.size() + "):");
        for (SkillDef def : all) {
            sender.sendMessage("§7- §f" + def.id() + " §7[" + def.trigger() + "] chance="
                    + def.chance() + " cd=" + def.cooldownSeconds() + "s → §b" + def.mythicSkill());
        }
    }

    private void handleGrant(CommandSender sender, String[] args, boolean grant) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ksskill " + (grant ? "grant" : "revoke") + " <玩家> <技能ID>");
            return;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
        String skillId = args[2];
        if (plugin.registry().get(skillId) == null) {
            sender.sendMessage("§c不存在的技能ID: " + skillId + " §7(用 /ksskill list 查看)");
            return;
        }
        boolean ok = grant
                ? plugin.grants().grant(op.getUniqueId(), skillId, sender.getName())
                : plugin.grants().revoke(op.getUniqueId(), skillId);
        sender.sendMessage(ok
                ? "§a已" + (grant ? "授予" : "撤销") + " " + args[1] + " 的技能 " + skillId
                : "§c操作失败(或该玩家本就" + (grant ? "已有" : "无") + "此技能)");
    }

    private void handleTest(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /ksskill test <玩家> <技能ID>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§c玩家不在线: " + args[1]);
            return;
        }
        boolean ok = plugin.engine().force(target, args[2]);
        sender.sendMessage(ok
                ? "§a已对 " + args[1] + " 施放 " + args[2]
                : "§c施放失败(技能ID不存在 或 MythicMobs 不可用)");
    }
}
