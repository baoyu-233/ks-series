package org.kseco;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public final class MajorOrderCommand implements CommandExecutor {

    private static final String C = "\u00A7";

    private final KsEco plugin;

    public MajorOrderCommand(KsEco plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        List<Map<String, Object>> orders = plugin.majorOrderManager().listOrders(false);
        if (args.length > 0 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("kseco.admin")) {
            sendDebug(sender, orders);
            return true;
        }

        sender.sendMessage(C + "6[MO] " + C + "e当前主任务");
        if (orders.isEmpty()) {
            sender.sendMessage(C + "7暂无主任务。");
            return true;
        }
        for (Map<String, Object> order : orders) {
            double pct = ((Number) order.get("progressPct")).doubleValue();
            double cur = num(order.get("currentValue"));
            double target = num(order.get("targetValue"));
            sender.sendMessage(C + "e" + order.get("title") + " " + C + "7["
                    + order.get("status") + "/" + order.get("metricType") + "]");
            sender.sendMessage(progressBar(pct) + " " + C + "f" + format(cur)
                    + C + "7/" + C + "f" + format(target)
                    + " " + C + "7(" + Math.round(pct * 100) + "%)");
            Object desc = order.get("description");
            if (desc != null && !String.valueOf(desc).isBlank()) {
                sender.sendMessage(C + "8" + desc);
            }
        }
        return true;
    }

    private void sendDebug(CommandSender sender, List<Map<String, Object>> orders) {
        sender.sendMessage(C + "6[MO Debug] " + C + "e活动任务");
        for (Map<String, Object> order : orders) {
            sender.sendMessage(C + "7" + order.get("id") + " " + C + "f" + order.get("title")
                    + " " + C + "7type=" + C + "f" + order.get("metricType")
                    + " " + C + "7cur=" + C + "f" + format(num(order.get("currentValue")))
                    + " " + C + "7target=" + C + "f" + format(num(order.get("targetValue")))
                    + " " + C + "7status=" + C + "f" + order.get("status"));
        }
        Map<String, Object> m = plugin.majorOrderManager().moneySupplyBreakdown();
        sender.sendMessage(C + "7MoneySupply provider=" + C + "f" + m.get("vaultProvider")
                + " " + C + "7total=" + C + "f" + format(num(m.get("total")))
                + " " + C + "7wallet=" + C + "f" + format(num(m.get("wallet")))
                + " " + C + "7bank=" + C + "f" + format(num(m.get("bank")))
                + " " + C + "7corp=" + C + "f" + format(num(m.get("corporate"))));
        sender.sendMessage(C + "7Excluded admin wallet=" + C + "f" + format(num(m.get("builtinExcluded")))
                + " " + C + "7rows=" + C + "f" + format(num(m.get("builtinExcludedRows")))
                + " " + C + "7admin bank=" + C + "f" + format(num(m.get("bankExcluded")))
                + " " + C + "7rows=" + C + "f" + format(num(m.get("bankExcludedRows")))
                + " " + C + "7snapshotM2=" + C + "f" + format(num(m.get("snapshotM2"))));
    }

    private String progressBar(double pct) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, pct)) * 20);
        return C + "a" + "|".repeat(filled) + C + "8" + "|".repeat(20 - filled);
    }

    private String format(double value) {
        if (value >= 1_000_000_000_000.0) return String.format("%.2ft", value / 1_000_000_000_000.0);
        if (value >= 1_000_000_000.0) return String.format("%.2fb", value / 1_000_000_000.0);
        if (value >= 1_000_000.0) return String.format("%.2fm", value / 1_000_000.0);
        if (value >= 1_000.0) return String.format("%.1fk", value / 1_000.0);
        return String.format("%.0f", value);
    }

    private double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
