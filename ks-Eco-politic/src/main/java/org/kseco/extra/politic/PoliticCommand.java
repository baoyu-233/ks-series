package org.kseco.extra.politic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.kseco.KsEco;

import java.util.List;
import java.util.UUID;

/**
 * /politic 命令 — 查询政治信息，并为 GUI 呼吁功能提供命令后备入口。
 *
 * 显示：执政官、保民官、自己的政治身份、最新法案公告。
 */
public final class PoliticCommand implements CommandExecutor {

    private final PoliticManager politicManager;
    private final ProposalManager proposalManager;
    private final KsEco eco;

    public PoliticCommand(PoliticManager politicManager, ProposalManager proposalManager, KsEco eco) {
        this.politicManager = politicManager;
        this.proposalManager = proposalManager;
        this.eco = eco;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[政治系统] 此命令仅限玩家使用");
            return true;
        }
        if (!player.hasPermission("kseco.admin") && !eco.featureGate().isOpen("politic")) {
            player.sendMessage("§c该功能暂未开放，敬请期待。");
            return true;
        }

        if (args.length > 0) {
            if ("appeal".equalsIgnoreCase(args[0]) || "呼吁".equals(args[0])) {
                if (args.length < 2) {
                    player.sendMessage("§e用法: /politic appeal <提案ID>");
                    return true;
                }
                ProposalManager.AppealResult result = proposalManager.appealForProposal(args[1], player);
                if (result.success()) player.sendMessage("§a" + result.message());
                else player.sendMessage("§c发起呼吁失败: " + result.error());
                return true;
            }
            if ("gui".equalsIgnoreCase(args[0])) {
                player.performCommand("eco gui");
                return true;
            }
            player.sendMessage("§e用法: /politic [gui|appeal <提案ID>]");
            return true;
        }

        UUID uuid = player.getUniqueId();

        // 构建信息面板
        Component header = Component.text()
            .append(Component.text("═══════ 🏛 元老院与共和政治系统 ═══════", NamedTextColor.GOLD))
            .build();

        player.sendMessage(header);
        player.sendMessage(Component.empty());

        // 执政官
        PoliticManager.Office consul = politicManager.getConsul();
        Component consulLine = Component.text()
            .append(Component.text("👑 执政官: ", NamedTextColor.YELLOW))
            .append(Component.text(consul != null ? consul.playerName : "空缺", NamedTextColor.WHITE, TextDecoration.BOLD))
            .build();
        player.sendMessage(consulLine);

        // 元老院
        List<PoliticManager.Office> senators = politicManager.getSenators();
        StringBuilder senatorNames = new StringBuilder();
        for (int i = 0; i < senators.size(); i++) {
            if (i > 0) senatorNames.append(", ");
            senatorNames.append(senators.get(i).playerName);
        }
        Component senatorLine = Component.text()
            .append(Component.text("📜 元老院 (" + senators.size() + " 席): ", NamedTextColor.GOLD))
            .append(Component.text(senatorNames.length() > 0 ? senatorNames.toString() : "无", NamedTextColor.WHITE))
            .build();
        player.sendMessage(senatorLine);

        // 保民官
        List<PoliticManager.Office> tribunes = politicManager.getTribunes();
        StringBuilder tribuneNames = new StringBuilder();
        for (int i = 0; i < tribunes.size(); i++) {
            if (i > 0) tribuneNames.append(", ");
            tribuneNames.append(tribunes.get(i).playerName);
        }
        Component tribuneLine = Component.text()
            .append(Component.text("🛡 保民官 (" + tribunes.size() + " 席): ", NamedTextColor.RED))
            .append(Component.text(tribuneNames.length() > 0 ? tribuneNames.toString() : "空缺", NamedTextColor.WHITE))
            .build();
        player.sendMessage(tribuneLine);

        // 骑士
        List<PoliticManager.Office> equestrians = politicManager.getEquestrians();
        StringBuilder eqNames = new StringBuilder();
        for (int i = 0; i < Math.min(equestrians.size(), 5); i++) {
            if (i > 0) eqNames.append(", ");
            eqNames.append(equestrians.get(i).playerName);
        }
        if (equestrians.size() > 5) eqNames.append(" ...");
        Component eqLine = Component.text()
            .append(Component.text("🐴 骑士阶级 (" + equestrians.size() + " 人): ", NamedTextColor.AQUA))
            .append(Component.text(eqNames.length() > 0 ? eqNames.toString() : "无", NamedTextColor.WHITE))
            .build();
        player.sendMessage(eqLine);

        player.sendMessage(Component.empty());

        // 自己的身份
        String myOffice = politicManager.getPlayerOffice(uuid);
        Component myLine;
        if (myOffice != null) {
            String display;
            NamedTextColor color = switch (myOffice) {
                case "CONSUL" -> NamedTextColor.GOLD;
                case "SENATOR" -> NamedTextColor.YELLOW;
                case "TRIBUNE" -> NamedTextColor.RED;
                case "EQUESTRIAN" -> NamedTextColor.AQUA;
                default -> NamedTextColor.WHITE;
            };
            display = switch (myOffice) {
                case "CONSUL" -> "👑 执政官";
                case "SENATOR" -> "📜 元老院议员";
                case "TRIBUNE" -> "🛡 平民保民官";
                case "EQUESTRIAN" -> "🐴 骑士阶级";
                default -> myOffice;
            };
            myLine = Component.text()
                .append(Component.text("你的身份: ", NamedTextColor.GREEN))
                .append(Component.text(display, color, TextDecoration.BOLD))
                .build();
        } else {
            myLine = Component.text()
                .append(Component.text("你的身份: ", NamedTextColor.GREEN))
                .append(Component.text("平民", NamedTextColor.GRAY))
                .build();
        }
        player.sendMessage(myLine);

        // 最新法案
        List<ProposalManager.Proposal> enacted = proposalManager.listProposals("ENACTED", null, 3);
        if (!enacted.isEmpty()) {
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("📋 最新生效法案:", NamedTextColor.GREEN));
            for (ProposalManager.Proposal prop : enacted) {
                player.sendMessage(Component.text("  · " + prop.title + " (通过于 " +
                    (prop.enactedAt > 0 ? new java.text.SimpleDateFormat("MM-dd HH:mm")
                        .format(new java.util.Date(prop.enactedAt * 1000)) : "刚刚") + ")",
                    NamedTextColor.GRAY));
            }
        }

        // Web 端链接（可点击）
        player.sendMessage(Component.empty());
        String webHost = eco.ksCore().ksConfig().getPublicAddress();
        int webPort = eco.ksCore().ksConfig().getPort();
        String politicUrl = "http://" + webHost + ":" + webPort + "/ks-Eco/politic";
        Component webLink = Component.text()
            .append(Component.text("🌐 政治仪表盘: ", NamedTextColor.BLUE))
            .append(Component.text("[点击打开 Web 端]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(politicUrl)))
            .build();
        player.sendMessage(webLink);

        Component annLink = Component.text()
            .append(Component.text("📢 城邦公告栏: ", NamedTextColor.BLUE))
            .append(Component.text("[点击查看立法动态]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl("http://" + webHost + ":" + webPort + "/announce")))
            .build();
        player.sendMessage(annLink);

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("══════════════════════════════════", NamedTextColor.GOLD));

        return true;
    }
}
