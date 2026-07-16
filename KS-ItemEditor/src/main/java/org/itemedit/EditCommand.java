package org.itemedit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public final class EditCommand implements CommandExecutor {

    private final ItemEditor plugin;

    public EditCommand(ItemEditor plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        // /itemedit reload —— 控制台和玩家均可使用
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender);
        }

        // 其余子命令仅限玩家
        if (!(sender instanceof Player player)) {
            sender.sendMessage("该命令只能由玩家使用。用法: /itemedit reload");
            return true;
        }

        // /itemedit web —— 管理员网页编辑器入口
        if (args.length >= 1 && args[0].equalsIgnoreCase("web")) {
            return handleWeb(player);
        }

        // /itemedit —— 打开 GUI 编辑器
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(TextUtil.parse("&c请先把要编辑的物品拿在主手，再使用 /itemedit"));
            return true;
        }

        EditSession session = new EditSession(plugin, player.getUniqueId(),
                player.getInventory().getHeldItemSlot());
        plugin.sessions().put(player.getUniqueId(), session);
        new MainMenu(plugin, session).open();
        return true;
    }

    /**
     * /itemedit reload —— 重载配置文件（无需重启服务器）。
     * 重新读取 config.yml，重启 Web 服务器，刷新 ItemsAdder 检测状态。
     */
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("itemedit.admin")) {
            sender.sendMessage("§c你没有权限执行此操作。（需要 itemedit.admin）");
            return true;
        }
        try {
            plugin.reloadPlugin();
            sender.sendMessage("§aItemEditor 配置已重载。" +
                    (plugin.webServer() != null ? " Web 服务器已重启。" : ""));
            plugin.getLogger().info("配置已由 " + sender.getName() + " 重载。");
        } catch (Exception e) {
            sender.sendMessage("§c重载失败: " + e.getMessage());
            plugin.getLogger().warning("重载配置失败: " + e.getMessage());
            e.printStackTrace();
        }
        return true;
    }

    /**
     * 管理员网页编辑器入口 —— 同 /design 但带 admin 权限标记。
     */
    private boolean handleWeb(Player player) {
        String fullUrl = plugin.createWebUrl(player, true);
        if (fullUrl == null) {
            player.sendMessage(TextUtil.parse("&c网页编辑器未启动。请联系管理员检查 config.yml 中的 web-server 配置。"));
            return true;
        }

        net.kyori.adventure.text.Component msg = TextUtil.parse("\n&6▎ &e&l管理员网页物品设计器\n")
                .append(TextUtil.parse("&7  点击下方链接在浏览器中打开：\n"))
                .append(net.kyori.adventure.text.Component.text("  ▶ " + fullUrl + " ◀",
                        net.kyori.adventure.text.format.NamedTextColor.AQUA)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(fullUrl)))
                .append(TextUtil.parse("\n&7  链接有效期 &c10 分钟\n"))
                .append(TextUtil.parse("&a[管理员模式] &7附魔上限 32767、IA 模型、全部功能已解锁。\n"));

        player.sendMessage(msg);
        return true;
    }
}
