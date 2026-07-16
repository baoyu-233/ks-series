package org.itemedit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * /refine 命令 —— 玩家武器精炼入口。
 *
 * 流程：
 * 1. 检查主手是否有物品
 * 2. 检查玩家是否有兑换券
 * 3. 从手中移除物品，创建 RefineSession
 * 4. 打开 RefineMenu GUI
 */
public final class RefineCommand implements CommandExecutor {

    private final ItemEditor plugin;

    public RefineCommand(ItemEditor plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("该命令只能由玩家使用。");
            return true;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(TextUtil.parse("&c请先把要精炼的武器拿在主手，再使用 /refine"));
            return true;
        }

        ConfigurationSection refineConfig = plugin.getConfig().getConfigurationSection("refine");
        if (refineConfig == null) {
            player.sendMessage(TextUtil.parse("&c精炼系统配置缺失，请联系管理员。"));
            return true;
        }

        // 检查兑换券
        if (!VoucherUtil.hasVoucher(player, refineConfig)) {
            player.sendMessage(TextUtil.parse("&c你没有足够的兑换券！需要: &f"
                    + VoucherUtil.voucherDescription(refineConfig)
                    + " &cx" + refineConfig.getInt("voucher.amount", 1)));
            return true;
        }

        // 创建精炼会话（自动从手中取出物品）
        RefineSession session = new RefineSession(plugin, player);
        plugin.refineSessions().put(player.getUniqueId(), session);

        // 打开精炼 GUI
        new RefineMenu(plugin, session, refineConfig).open();
        return true;
    }
}
