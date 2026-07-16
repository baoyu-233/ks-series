package org.kshwp;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家区块探索追踪器。
 *
 * 监听玩家跨越区块边界，立即触发异步渲染 zoom=1 和 zoom=2 的图块写入磁盘缓存。
 * 这样即使区块被服务器卸载，地图也能从磁盘缓存显示已探索区域，不再变回红色"未探索"。
 *
 * 主线程开销：每次移动只做一次整数比较（>>4），跨区块才提交异步任务。
 */
public final class KsMapChunkListener implements Listener {

    private final KsHWP plugin;
    /** 每个玩家上次所在区块坐标（[cx, cz]） */
    private final Map<UUID, long[]> lastChunk = new ConcurrentHashMap<>();

    public KsMapChunkListener(KsHWP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        var from = event.getFrom();
        var to = event.getTo();
        int fromCX = from.getBlockX() >> 4;
        int fromCZ = from.getBlockZ() >> 4;
        int toCX = to.getBlockX() >> 4;
        int toCZ = to.getBlockZ() >> 4;
        if (fromCX == toCX && fromCZ == toCZ) return; // 未跨区块，直接返回
        enqueueRender(event.getPlayer(), toCX, toCZ);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        int toCX = event.getTo().getBlockX() >> 4;
        int toCZ = event.getTo().getBlockZ() >> 4;
        enqueueRender(event.getPlayer(), toCX, toCZ);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastChunk.remove(event.getPlayer().getUniqueId());
    }

    private void enqueueRender(Player player, int cx, int cz) {
        UUID uuid = player.getUniqueId();
        long[] last = lastChunk.get(uuid);
        if (last != null && last[0] == cx && last[1] == cz) return; // 重复事件去重
        lastChunk.put(uuid, new long[]{cx, cz});

        // zoom=1/2 强制重渲 + zoom=4/8 失效，全部在同一个异步任务里顺序执行。
        // 不能用 renderTileAsync：它缓存优先，2×2 组里已存过部分数据的 zoom=2
        // 图块会直接命中旧图，导致上层 zoom=4/8 用过期数据重新合成。
        plugin.mapRenderer().refreshChunkAsync(player.getWorld().getName(), cx, cz);
    }
}
