package org.kseries.rpg;

import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.kseries.rpg.api.RpgProgressionApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RpgCommand implements CommandExecutor, TabCompleter {
    private final KsRpg plugin;

    RpgCommand(KsRpg plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || isCatalog(args[0])) {
            sendCatalog(sender);
            return true;
        }
        if (isReload(args[0])) {
            if (!sender.hasPermission("ksrpg.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有使用此命令的权限。");
                return true;
            }
            if (!plugin.reloadCatalog()) {
                sender.sendMessage(ChatColor.RED + "战斗内容配置存在错误，已保留上一份有效配置。请查看控制台。");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "ks-RPG 配方与战斗内容已热重载。");
            return true;
        }
        if (isProof(args[0])) return handleProof(sender, args);
        if (isGate(args[0])) return handleGate(sender, args);
        if (!isExchange(args[0])) return false;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家可以兑换材料。");
            return true;
        }
        if (!sender.hasPermission("ksrpg.use")) {
            sender.sendMessage(ChatColor.RED + "你没有使用此命令的权限。");
            return true;
        }
        if (args.length < 2) {
            sendCatalog(sender);
            return true;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "兑换次数必须是整数。");
                return true;
            }
        }
        var recipe = plugin.catalog().exchange(args[1]);
        if (recipe.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "未知配方。请使用 /ksrpg 目录 查看可用配方。");
            return true;
        }
        MaterialExchangeService.Result result = plugin.exchangeService().exchange(player, recipe.get(), amount);
        switch (result) {
            case SUCCESS -> sender.sendMessage(ChatColor.GREEN + "兑换完成：" + recipe.get().display()
                    + (amount == 1 ? "" : " x" + amount));
            case MISSING_INPUTS -> sender.sendMessage(ChatColor.RED + "所需材料不足，未扣除任何物品。");
            case INVENTORY_FULL -> sender.sendMessage(ChatColor.RED + "背包没有足够空间，未扣除任何物品。");
            case MMOITEMS_UNAVAILABLE -> sender.sendMessage(ChatColor.RED + "MMOItems 不可用，或兑换产物不存在。");
            case OUTPUT_INVALID -> sender.sendMessage(ChatColor.RED + "此配方的产物数量配置无效。");
        }
        return true;
    }

    private boolean handleProof(CommandSender sender, String[] args) {
        if (args.length == 1 || isList(args[1])) {
            if (args.length == 1 && sender instanceof Player player) {
                sendProofs(sender, player);
                return true;
            }
            if (!sender.hasPermission("ksrpg.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有查看其他玩家凭证的权限。");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "用法：/ksrpg 凭证 查看 <玩家>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "目标玩家必须在线。");
                return true;
            }
            sendProofs(sender, target);
            return true;
        }

        if (!sender.hasPermission("ksrpg.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有管理战斗凭证的权限。");
            return true;
        }
        if (!isGrant(args[1]) && !isRevoke(args[1])) {
            sender.sendMessage(ChatColor.RED + "用法：/ksrpg 凭证 <授予|撤销> <玩家> <凭证ID>");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "用法：/ksrpg 凭证 <授予|撤销> <玩家> <凭证ID>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "目标玩家必须在线。");
            return true;
        }
        RpgProgressionApi.ProofMutation result = isGrant(args[1])
                ? plugin.progression().grantProof(target, args[3])
                : plugin.progression().revokeProof(target, args[3]);
        String display = plugin.progression().proof(args[3]).map(RpgProgressionApi.ProofDefinition::display)
                .orElse(args[3]);
        switch (result) {
            case GRANTED -> sender.sendMessage(ChatColor.GREEN + "已向 " + target.getName() + " 授予凭证：" + display);
            case ALREADY_GRANTED -> sender.sendMessage(ChatColor.YELLOW + target.getName() + " 已拥有凭证：" + display);
            case REVOKED -> sender.sendMessage(ChatColor.GREEN + "已撤销 " + target.getName() + " 的凭证：" + display);
            case NOT_PRESENT -> sender.sendMessage(ChatColor.YELLOW + target.getName() + " 未拥有凭证：" + display);
            case UNKNOWN_PROOF -> sender.sendMessage(ChatColor.RED + "未在配置中声明的凭证 ID：" + args[3]);
            case INVALID_PROOF_ID -> sender.sendMessage(ChatColor.RED + "凭证 ID 只能包含小写字母、数字、点、下划线和连字符。");
        }
        return true;
    }

    private boolean handleGate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家可以检查自己的进度门槛。");
            return true;
        }
        if (!sender.hasPermission("ksrpg.use")) {
            sender.sendMessage(ChatColor.RED + "你没有使用此命令的权限。");
            return true;
        }
        int idIndex = args.length > 1 && isCheck(args[1]) ? 2 : 1;
        if (args.length <= idIndex) {
            sender.sendMessage(ChatColor.RED + "用法：/ksrpg 门槛 检查 <门槛ID>");
            return true;
        }
        RpgProgressionApi.GateCheck result = plugin.progression().checkGate(player, args[idIndex]);
        if (!result.exists()) {
            sender.sendMessage(ChatColor.RED + "未在配置中声明的门槛 ID：" + args[idIndex]);
            return true;
        }
        String display = plugin.progression().gate(result.gateId())
                .map(RpgProgressionApi.GateDefinition::display).orElse(result.gateId());
        if (result.satisfied()) {
            sender.sendMessage(ChatColor.GREEN + "已满足门槛：" + display);
            return true;
        }
        String missing = result.missingProofs().stream().map(this::proofDisplay).reduce((a, b) -> a + "、" + b)
                .orElse("未知凭证");
        sender.sendMessage(ChatColor.RED + "尚未满足门槛：" + display + ChatColor.GRAY + "（缺少：" + missing + "）");
        return true;
    }

    private void sendProofs(CommandSender sender, Player player) {
        var proofs = plugin.progression().proofs(player);
        sender.sendMessage(ChatColor.AQUA + "[ks-RPG] " + player.getName() + " 的战斗凭证");
        if (proofs.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + " - 暂无战斗凭证");
            return;
        }
        for (String proofId : proofs) {
            sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + proofDisplay(proofId)
                    + ChatColor.DARK_GRAY + " (" + proofId + ")");
        }
    }

    private String proofDisplay(String proofId) {
        return plugin.progression().proof(proofId).map(RpgProgressionApi.ProofDefinition::display).orElse(proofId);
    }

    private void sendCatalog(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "[ks-RPG] 材料兑换");
        for (Catalog.Exchange exchange : plugin.catalog().exchanges()) {
            sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + exchange.id() + ChatColor.DARK_GRAY
                    + ": " + ChatColor.YELLOW + exchange.display());
        }
        sender.sendMessage(ChatColor.DARK_GRAY + "用法：/ksrpg 兑换 <配方ID> [次数]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return prefix(args[0], List.of("catalog", "exchange", "reload", "proof", "gate",
                "目录", "兑换", "重载", "凭证", "门槛"));
        if (args.length == 2 && isExchange(args[0])) {
            return prefix(args[1], plugin.catalog().exchanges().stream().map(Catalog.Exchange::id).toList());
        }
        if (args.length == 2 && isProof(args[0])) {
            return prefix(args[1], List.of("list", "grant", "revoke", "查看", "授予", "撤销"));
        }
        if (args.length == 3 && isProof(args[0]) && (isGrant(args[1]) || isRevoke(args[1]) || isList(args[1]))) {
            return prefix(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args.length == 4 && isProof(args[0]) && (isGrant(args[1]) || isRevoke(args[1]))) {
            return prefix(args[3], plugin.progression().proofDefinitions().stream()
                    .map(RpgProgressionApi.ProofDefinition::id).toList());
        }
        if (args.length == 2 && isGate(args[0])) {
            return prefix(args[1], List.of("check", "检查"));
        }
        if (args.length == 3 && isGate(args[0]) && isCheck(args[1])) {
            return prefix(args[2], plugin.progression().gateDefinitions().stream()
                    .map(RpgProgressionApi.GateDefinition::id).toList());
        }
        return List.of();
    }

    private List<String> prefix(String input, List<String> choices) {
        String normalized = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String choice : choices) if (choice.toLowerCase(Locale.ROOT).startsWith(normalized)) matches.add(choice);
        return matches;
    }

    private boolean isCatalog(String value) {
        return value.equalsIgnoreCase("catalog") || value.equals("目录");
    }

    private boolean isExchange(String value) {
        return value.equalsIgnoreCase("exchange") || value.equals("兑换");
    }

    private boolean isReload(String value) {
        return value.equalsIgnoreCase("reload") || value.equals("重载");
    }

    private boolean isProof(String value) {
        return value.equalsIgnoreCase("proof") || value.equals("凭证");
    }

    private boolean isGate(String value) {
        return value.equalsIgnoreCase("gate") || value.equals("门槛");
    }

    private boolean isList(String value) {
        return value.equalsIgnoreCase("list") || value.equals("查看");
    }

    private boolean isGrant(String value) {
        return value.equalsIgnoreCase("grant") || value.equals("授予");
    }

    private boolean isRevoke(String value) {
        return value.equalsIgnoreCase("revoke") || value.equals("撤销");
    }

    private boolean isCheck(String value) {
        return value.equalsIgnoreCase("check") || value.equals("检查");
    }
}
