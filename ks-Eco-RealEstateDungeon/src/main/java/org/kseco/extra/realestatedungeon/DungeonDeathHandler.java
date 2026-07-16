package org.kseco.extra.realestatedungeon;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.kseco.KsEco;

import java.util.UUID;

/**
 * 副本死亡处理。
 *
 * 行为：
 * - 玩家在副本内死亡 → 保留背包 + 等级（不掉落）
 * - 记录到 ks_dungeon_participants (status=DEAD, died_at=now)
 * - 写入 ks_dungeon_log
 * - 复活走 Web API /revive，由 DungeonInstanceManager.recordRevive() 扣费
 */
public final class DungeonDeathHandler implements Listener {

    private final KsEco eco;
    private final DungeonInstanceManager instanceManager;
    private final DungeonConfigManager configManager;

    public DungeonDeathHandler(KsEco eco, DungeonInstanceManager instanceManager,
                                DungeonConfigManager configManager) {
        this.eco = eco;
        this.instanceManager = instanceManager;
        this.configManager = configManager;
    }

    public void register() {
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, eco);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerUuid = player.getUniqueId();
        String instanceId = instanceManager.getPlayerActiveInstance(playerUuid);
        if (instanceId == null) {
            // 查 DB（玩家可能掉线后重连）
            return;
        }
        // 阻止默认死亡掉落
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDeathMessage(null);
        event.setNewTotalExp(0);
        event.setNewLevel(0);
        event.setNewExp(0);

        instanceManager.recordDeath(instanceId, playerUuid);

        // 计算下次复活费用并提示
        int currentCount = instanceManager.getCurrentReviveCount(instanceId, playerUuid);
        double cost = calculateCost(currentCount);
        player.sendMessage("§c[副本] §f你在副本中死亡！");
        player.sendMessage("§7  下次复活费用: §e" + cost + " §7(第 " + (currentCount + 1) + " 次)");
        player.sendMessage("§7  通过 Web API /api/realestate-dungeon/revive 复活");
    }

    public double calculateCost(int currentReviveCount) {
        int n = currentReviveCount + 1;  // 即将是第几次
        DungeonConfigManager.ConfigSnapshot s = configManager.snapshot();
        if (n > s.maxRevivesPerPlayer) return -1;  // 达到上限
        return s.reviveBaseCost * Math.pow(s.reviveExponent, n - 1);
    }

    /**
     * 玩家主动请求复活（被 WebHandler 调用）。
     * @return 0=成功；-1=达到上限；其他正数=扣费金额（失败时返回的费用为参考）
     */
    public double revive(String instanceId, UUID playerUuid) {
        int currentCount = instanceManager.getCurrentReviveCount(instanceId, playerUuid);
        double cost = calculateCost(currentCount);
        if (cost < 0) return -1;  // 达到上限
        // 扣款
        if (!eco.vaultHook().isAvailable()) return -2;
        var p = org.bukkit.Bukkit.getOfflinePlayer(playerUuid);
        if (!eco.vaultHook().has(p, cost)) return -3;  // 余额不足
        if (!eco.vaultHook().withdraw(p, cost)) return -4;
        // 记录
        instanceManager.recordRevive(instanceId, playerUuid, currentCount + 1, cost, cost);
        // 通知
        Player online = org.bukkit.Bukkit.getPlayer(playerUuid);
        if (online != null && online.isOnline()) {
            online.sendMessage("§a[副本] §f你已复活！扣费 §e" + cost);
        }
        return cost;
    }
}
