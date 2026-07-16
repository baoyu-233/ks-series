package org.ksinherit;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;

/**
 * /inherit 命令的 Tab 补全。
 */
public final class InheritTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>(List.of("open", "token"));
            if (sender.hasPermission("ksinherit.admin")) {
                list.add("slots");
                list.add("reload");
                list.add("testitem");
            }
            return filter(list, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("slots")
                && sender.hasPermission("ksinherit.admin")) {
            List<String> names = new ArrayList<>();
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
                if (p.getName() != null) names.add(p.getName());
            }
            return filter(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("slots")) {
            return filter(List.of("9", "18", "27", "36", "45", "54"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> list, String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String s : list) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(s);
        }
        return result;
    }
}
