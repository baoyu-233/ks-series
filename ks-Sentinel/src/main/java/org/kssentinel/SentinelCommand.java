package org.kssentinel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /sentinel token   — 获取 Web 管理令牌（需 ks-core）
 * /sentinel log     — 控制台/游戏内兜底查看最近记录（无 ks-core 时仍可用）
 * /sentinel exclude — 管理排除规则
 */
public final class SentinelCommand implements CommandExecutor {

    private final KsSentinel plugin;

    SentinelCommand(KsSentinel plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6[ks-Sentinel] §7v" + plugin.getDescription().getVersion());
            sender.sendMessage("§e/sentinel token §7- 获取 Web 管理令牌");
            sender.sendMessage("§e/sentinel log [玩家] [条数] §7- 查看最近记录");
            sender.sendMessage("§e/sentinel exclude <add|remove|list> [指令前缀] §7- 管理排除规则");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "token" -> handleToken(sender);
            case "log" -> handleLog(sender, args);
            case "exclude" -> handleExclude(sender, args);
            default -> sender.sendMessage("§c未知子命令: /" + label + " " + sub);
        }
        return true;
    }

    private void handleToken(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c仅玩家可获取 token");
            return;
        }
        if (!player.hasPermission("kssentinel.admin")) {
            player.sendMessage("§c无权限");
            return;
        }
        try {
            var core = org.bukkit.Bukkit.getPluginManager().getPlugin("ks-core");
            if (core == null) {
                sender.sendMessage("§cks-core 未安装，无法生成 Web 链接，请使用 /sentinel log 查看");
                return;
            }
            var bridge = core.getClass().getMethod("bridge").invoke(core);
            var m = bridge.getClass().getMethod("createToken",
                    java.util.UUID.class, String.class, boolean.class);
            var session = m.invoke(bridge, player.getUniqueId(), player.getName(), true);
            String token = (String) session.getClass().getField("token").get(session);

            var ksConfig = core.getClass().getMethod("ksConfig").invoke(core);
            String host = (String) ksConfig.getClass().getMethod("getPublicAddress").invoke(ksConfig);
            int port = (int) ksConfig.getClass().getMethod("getPort").invoke(ksConfig);

            String webUrl = "http://" + host + ":" + port + "/ks-Sentinel/admin?token=" + token;

            player.sendMessage(Component.text()
                .append(Component.text("[ks-Sentinel] ", TextColor.color(0xFFAA00)))
                .append(Component.text("您的管理审计 Web 令牌:", TextColor.color(0x55FF55)))
                .build());
            player.sendMessage(Component.text()
                .append(Component.text(webUrl, TextColor.color(0x55FFFF), TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.OPEN_URL, webUrl)))
                .build());
            player.sendMessage(Component.text("(点击上方链接查看全服指令审计日志、管理高危/排除规则)",
                TextColor.color(0xAAAAAA), TextDecoration.ITALIC));
        } catch (Exception e) {
            sender.sendMessage("§cToken 生成失败: " + e.getMessage());
        }
    }

    private void handleLog(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kssentinel.admin")) {
            sender.sendMessage("§c无权限");
            return;
        }
        String executor = args.length > 1 ? args[1] : null;
        int limit = 20;
        if (args.length > 2) {
            try { limit = Math.max(1, Math.min(100, Integer.parseInt(args[2]))); }
            catch (NumberFormatException ignored) {}
        }
        List<Map<String, Object>> rows = plugin.queryLogs(null, executor, null, 0, 0, limit, 0);
        if (rows.isEmpty()) {
            sender.sendMessage("§7暂无记录");
            return;
        }
        sender.sendMessage("§6[ks-Sentinel] §7最近 " + rows.size() + " 条记录:");
        for (var row : rows) {
            String risk = String.valueOf(row.get("risk_level"));
            String color = switch (risk) {
                case "HIGH" -> "§c";
                case "MEDIUM" -> "§e";
                default -> "§7";
            };
            String target = row.get("target_name") != null ? " §7→§f " + row.get("target_name") : "";
            sender.sendMessage(color + "[" + risk + "] §f" + row.get("executor_name") + " §7/" + row.get("command") + target);
        }
    }

    private void handleExclude(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kssentinel.admin")) {
            sender.sendMessage("§c无权限");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /sentinel exclude <add|remove|list> [指令前缀]");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                var list = plugin.exclusionsSnapshot();
                if (list.isEmpty()) { sender.sendMessage("§7排除列表为空"); return; }
                sender.sendMessage("§6排除规则 (" + list.size() + "):");
                for (var ex : list) sender.sendMessage("§7#" + ex.id() + " §f" + ex.commandPrefix());
            }
            case "add" -> {
                if (args.length < 3) { sender.sendMessage("§c用法: /sentinel exclude add <指令前缀>"); return; }
                boolean ok = plugin.addExclusion(args[2], "added by " + sender.getName());
                sender.sendMessage(ok ? "§a已排除指令: " + args[2] : "§c添加失败");
            }
            case "remove" -> {
                if (args.length < 3) { sender.sendMessage("§c用法: /sentinel exclude remove <id>"); return; }
                try {
                    boolean ok = plugin.removeExclusion(Integer.parseInt(args[2]));
                    sender.sendMessage(ok ? "§a已删除排除规则" : "§c未找到该规则");
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cid 必须是数字（用 /sentinel exclude list 查看）");
                }
            }
            default -> sender.sendMessage("§c未知操作: " + action);
        }
    }
}
