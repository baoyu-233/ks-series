package org.kssentinel;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.UUID;

/**
 * 监听全体玩家 + 控制台执行的指令，交给 RiskEvaluator 判定风险后入队写库。
 * MONITOR 优先级、不忽略已取消事件——即便指令被其他插件的权限检查拦截，
 * 尝试本身（如无权限玩家尝试 /op）也是审计需要记录的信号。
 */
public final class CommandAuditListener implements Listener {

    private final KsSentinel plugin;

    public CommandAuditListener(KsSentinel plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String full = event.getMessage().substring(1); // 去掉前导 '/'
        var loc = player.getLocation();
        record(full, player.getUniqueId().toString(), player.getName(), false,
            player.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onServerCommand(ServerCommandEvent event) {
        record(event.getCommand(), null, "CONSOLE", true, null, 0, 0, 0);
    }

    private void record(String full, String executorUuid, String executorName, boolean isConsole,
                         String world, double x, double y, double z) {
        var result = RiskEvaluator.evaluate(full, executorName,
            plugin.rulesSnapshot(), plugin.exclusionsSnapshot(), this::lookupOnlinePlayer);
        if (result.excluded()) return;

        plugin.enqueueLog(new KsSentinel.LogEntry(
            executorUuid, executorName, isConsole,
            full, result.baseCommand(),
            result.targetUuid() != null ? result.targetUuid().toString() : null,
            result.targetName(),
            result.riskLevel(),
            world, x, y, z,
            System.currentTimeMillis() / 1000
        ));
    }

    private UUID lookupOnlinePlayer(String name) {
        Player p = Bukkit.getPlayerExact(name);
        return p != null ? p.getUniqueId() : null;
    }
}
