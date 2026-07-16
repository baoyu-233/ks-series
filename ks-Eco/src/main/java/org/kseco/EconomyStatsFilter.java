package org.kseco;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class EconomyStatsFilter {

    private EconomyStatsFilter() {}

    public static boolean shouldExcludePlayer(KsEco plugin, OfflinePlayer player) {
        if (player == null) return true;
        UUID uuid = player.getUniqueId();
        String uuidText = uuid != null ? uuid.toString() : "";
        String name = player.getName();
        if (isSystemIdentity(uuidText, name)) return true;
        if (configuredUuidExclusions(plugin).contains(uuidText.toLowerCase(Locale.ROOT))) return true;
        if (name != null && configuredNameExclusions(plugin).contains(name.toLowerCase(Locale.ROOT))) return true;
        if (!plugin.getConfig().getBoolean("statistics.exclude-admin-player-balances", true)) return false;
        if (player.isOp()) return true;
        Player online = player.getPlayer();
        return online != null && (online.hasPermission("kseco.admin") || online.hasPermission("kscore.admin"));
    }

    public static boolean shouldExcludePlayer(KsEco plugin, String uuidText, String name) {
        if (isSystemIdentity(uuidText, name)) return true;
        if (configuredUuidExclusions(plugin).contains(safeLower(uuidText))) return true;
        if (configuredNameExclusions(plugin).contains(safeLower(name))) return true;
        try {
            return shouldExcludePlayer(plugin, Bukkit.getOfflinePlayer(UUID.fromString(uuidText)));
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    public static BalanceSum sumPlayerBalances(KsEco plugin, Connection conn,
                                                String table, String uuidColumn,
                                                String nameColumn, String balanceColumn) {
        String nameExpr = nameColumn == null || nameColumn.isBlank() ? "''" : nameColumn;
        String sql = "SELECT " + uuidColumn + " AS stat_uuid, "
                + nameExpr + " AS stat_name, "
                + balanceColumn + " AS stat_balance FROM " + table;
        double included = 0;
        double excluded = 0;
        int includedRows = 0;
        int excludedRows = 0;
        try (var s = conn.createStatement();
             var rs = s.executeQuery(sql)) {
            while (rs.next()) {
                String uuid = rs.getString("stat_uuid");
                String name = rs.getString("stat_name");
                double balance = rs.getDouble("stat_balance");
                if (shouldExcludePlayer(plugin, uuid, name)) {
                    excluded += balance;
                    excludedRows++;
                } else {
                    included += balance;
                    includedRows++;
                }
            }
        } catch (SQLException ignored) {
            return new BalanceSum(0, 0, 0, 0);
        }
        return new BalanceSum(included, excluded, includedRows, excludedRows);
    }

    public static double onlineVaultBalances(KsEco plugin) {
        double total = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!shouldExcludePlayer(plugin, player)) {
                total += plugin.vaultHook().getBalance(player);
            }
        }
        return total;
    }

    private static boolean isSystemIdentity(String uuidText, String name) {
        String uuid = uuidText == null ? "" : uuidText.trim();
        String n = name == null ? "" : name.trim();
        return uuid.isBlank()
                || uuid.equalsIgnoreCase("SYSTEM")
                || uuid.startsWith("00000000-")
                || n.equalsIgnoreCase("SYSTEM")
                || n.equalsIgnoreCase("CONSOLE");
    }

    private static Set<String> configuredUuidExclusions(KsEco plugin) {
        return plugin.getConfig().getStringList("statistics.excluded-player-uuids").stream()
                .map(EconomyStatsFilter::safeLower)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> configuredNameExclusions(KsEco plugin) {
        return plugin.getConfig().getStringList("statistics.excluded-player-names").stream()
                .map(EconomyStatsFilter::safeLower)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record BalanceSum(double included, double excluded, int includedRows, int excludedRows) {}
}
