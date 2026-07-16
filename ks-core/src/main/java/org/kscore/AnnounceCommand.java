package org.kscore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * /announce 命令 — 在游戏内查看公告栏。
 * 显示最新的公告（按类别分组），带可点击链接跳转到 Web 公告栏。
 */
public final class AnnounceCommand implements CommandExecutor {

    private final AnnouncementManager announcementManager;
    private final KsConfig ksConfig;

    public AnnounceCommand(AnnouncementManager announcementManager, KsConfig ksConfig) {
        this.announcementManager = announcementManager;
        this.ksConfig = ksConfig;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[公告栏] 此命令仅限玩家使用，或访问 http://" + ksConfig.getPublicAddress() + ":" + ksConfig.getPort() + "/announce");
            return true;
        }

        List<Map<String, Object>> all = announcementManager.list(null);

        Component header = Component.text()
            .append(Component.text("═══════ 📢 城邦公告栏 ═══════", NamedTextColor.AQUA))
            .build();
        player.sendMessage(header);
        player.sendMessage(Component.empty());

        if (all.isEmpty()) {
            player.sendMessage(Component.text("  暂无公告", NamedTextColor.GRAY));
        } else {
            // 分组显示
            var voting = all.stream().filter(a -> "VOTING".equals(a.get("category"))).toList();
            var laws = all.stream().filter(a -> "LAW".equals(a.get("category"))).toList();
            var general = all.stream().filter(a -> !"VOTING".equals(a.get("category")) && !"LAW".equals(a.get("category"))).toList();

            if (!voting.isEmpty()) {
                player.sendMessage(Component.text("🏛 元老院 · 表决中:", NamedTextColor.GOLD, TextDecoration.BOLD));
                for (var a : voting) {
                    player.sendMessage(announceLine(a, NamedTextColor.GOLD));
                }
                player.sendMessage(Component.empty());
            }
            if (!laws.isEmpty()) {
                player.sendMessage(Component.text("📜 元老院 · 已颁布法案:", NamedTextColor.GREEN, TextDecoration.BOLD));
                for (var a : laws) {
                    player.sendMessage(announceLine(a, NamedTextColor.GREEN));
                }
                player.sendMessage(Component.empty());
            }
            if (!general.isEmpty()) {
                player.sendMessage(Component.text("📢 官方公告:", NamedTextColor.AQUA, TextDecoration.BOLD));
                for (var a : general) {
                    player.sendMessage(announceLine(a, NamedTextColor.AQUA));
                }
                player.sendMessage(Component.empty());
            }
        }

        // Web 链接
        String url = "http://" + ksConfig.getPublicAddress() + ":" + ksConfig.getPort() + "/announce";
        Component webLink = Component.text()
            .append(Component.text("🌐 完整公告栏: ", NamedTextColor.BLUE))
            .append(Component.text("[点击打开 Web 端]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url)))
            .build();
        player.sendMessage(webLink);
        player.sendMessage(Component.text("══════════════════════════════════", NamedTextColor.AQUA));

        return true;
    }

    private Component announceLine(Map<String, Object> a, NamedTextColor accentColor) {
        String title = str(a.get("title"), "?");
        String author = str(a.get("author"), "");
        String category = str(a.get("category"), "GENERAL");

        String emoji = switch (category) {
            case "VOTING" -> "🗳 ";
            case "LAW" -> "📜 ";
            case "SYSTEM" -> "⚙ ";
            default -> "📌 ";
        };

        return Component.text()
            .append(Component.text("  " + emoji, accentColor))
            .append(Component.text(title, NamedTextColor.WHITE))
            .append(Component.text(author.isEmpty() ? "" : " — " + author, NamedTextColor.GRAY))
            .build();
    }

    private static String str(Object o, String def) {
        return o != null ? o.toString() : def;
    }
}
