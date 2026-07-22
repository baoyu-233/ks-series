package org.itemedit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * /design 命令 —— 玩家网页物品设计器入口。
 *
 * 用法：
 *   /design              — 生成临时 token，返回可点击的网页链接
 *   /design load <模板码> — 加载已保存的模板，应用到手持物品
 */
public final class DesignCommand implements CommandExecutor {

    private final ItemEditor plugin;
    private TemplateManager templateManager; // 由 ItemEditor 在 WebServer 启动后注入

    public DesignCommand(ItemEditor plugin) {
        this.plugin = plugin;
    }

    void setTemplateManager(TemplateManager tm) {
        this.templateManager = tm;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("该命令只能由玩家使用。");
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("load")) {
            if (!player.hasPermission("itemedit.admin")) {
                player.sendMessage(TextUtil.parse("&c你没有权限加载物品模板。（需要 itemedit.admin）"));
                return true;
            }
            return handleLoad(player, args[1]);
        }

        if (args.length == 0) {
            return handleOpen(player, false);
        }

        // 未知子命令
        player.sendMessage(TextUtil.parse("&e用法: /design 或 /design load <模板码>"));
        return true;
    }

    /**
     * 处理 /design —— 打开网页编辑器。
     */
    private boolean handleOpen(Player player, boolean isAdmin) {
        String fullUrl = plugin.createWebUrl(player, isAdmin);
        if (fullUrl == null) {
            player.sendMessage(TextUtil.parse("&c网页编辑器未启动。请联系管理员检查 config.yml 中的 web-server 配置。"));
            return true;
        }

        // 发送可点击的链接
        Component msg = TextUtil.parse("\n&6▎ &e&l网页物品设计器\n")
                .append(TextUtil.parse("&7  点击下方链接在浏览器中打开：\n"))
                .append(Component.text("  ▶ " + fullUrl + " ◀", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(fullUrl)))
                .append(TextUtil.parse("\n&7  链接有效期 &c10 分钟&7，过期后请重新输入指令。\n"));

        player.sendMessage(msg);

        if (isAdmin) {
            player.sendMessage(TextUtil.parse("&a[管理员模式] &7完整功能已解锁。"));
        }

        return true;
    }

    /**
     * 处理 /design load <模板码> —— 加载模板应用到手持物品。
     */
    private boolean handleLoad(Player player, String code) {
        if (templateManager == null) {
            player.sendMessage(TextUtil.parse("&c模板系统未初始化。"));
            return true;
        }

        TemplateManager.Template template = templateManager.load(code);
        if (template == null || template.item == null) {
            player.sendMessage(TextUtil.parse("&c模板不存在或已损坏: &f" + code));
            return true;
        }

        // ★ 管理员模板仅管理员可加载
        if (template.adminTemplate && !player.hasPermission("itemedit.admin")) {
            player.sendMessage(TextUtil.parse("&c该模板为管理员专属模板，玩家无法加载！"));
            return true;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean isNewItem = hand == null || hand.getType().isAir();

        ItemStack applied;
        if (isNewItem) {
            // 从模板创建新物品
            applied = ItemSerializer.fromItemData(template.item);
            if (applied != null) ItemSerializer.applyExtendedData(applied, template.item);
        } else {
            // 在现有物品上叠加模板的外观/词条，保留数量、耐久、容器内容与第三方数据
            applied = ItemSerializer.applyTemplatePreservingBody(hand, template.item);
        }

        if (applied == null) {
            player.sendMessage(TextUtil.parse("&c无法创建物品，模板数据可能有误。"));
            return true;
        }

        player.getInventory().setItemInMainHand(applied);
        player.sendMessage(TextUtil.parse("&a✅ 模板已加载: &e" + code + "\n"
                + "&7  作者: &f" + (template.createdBy != null ? template.createdBy.name : "未知")
                + (template.adminTemplate ? " &c[管理员模板]" : "")));

        // ★ 管理员加载后打开 GUI 预览/继续编辑
        if (player.hasPermission("itemedit.admin")) {
            EditSession session = new EditSession(plugin, player.getUniqueId(),
                    player.getInventory().getHeldItemSlot());
            plugin.sessions().put(player.getUniqueId(), session);
            new MainMenu(plugin, session).open();
        }
        return true;
    }
}
