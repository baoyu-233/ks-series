package org.kseco.extra.realestatedungeon;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.kseco.KsEco;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 副本死亡处理。
 *
 * 行为：
 * - 玩家在副本内死亡 → 保留背包 + 等级（不掉落）
 * - 记录到 ks_dungeon_participants (status=DEAD, died_at=now)
 * - 写入 ks_dungeon_log
 * - 复活走 Web API /revive 或 /dungeon revive，先校验状态再扣费，落库失败会退款
 */
public final class DungeonDeathHandler implements Listener {

    public static final double ERR_LIMIT = -1;
    public static final double ERR_VAULT = -2;
    public static final double ERR_BALANCE = -3;
    public static final double ERR_WITHDRAW = -4;
    public static final double ERR_INSTANCE = -5;
    public static final double ERR_NOT_DEAD = -6;
    public static final double ERR_PERSIST = -7;
    public static final double ERR_OFFLINE = -8;

    private final KsEco eco;
    private final DungeonInstanceManager instanceManager;
    private final DungeonConfigManager configManager;
    private final Set<UUID> deadPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> reviveInFlight = ConcurrentHashMap.newKeySet();

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
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDeathMessage(null);
        event.setNewTotalExp(0);
        event.setNewLevel(0);
        event.setNewExp(0);

        deadPlayers.add(playerUuid);
        player.sendMessage("§c[副本] §f你在副本中死亡！");
        player.sendMessage("§7  正在确认阵亡记录，重生后可使用 §f/dungeon revive §7付费回场。");
        instanceManager.recordDeathAsync(instanceId, playerUuid).whenComplete((currentCount, failure) ->
                eco.scheduler().runPlayer(playerUuid, () -> {
                    if (failure != null || currentCount == null || currentCount < 0) {
                        deadPlayers.remove(playerUuid);
                        player.sendMessage("§c[副本] 阵亡记录未能确认，付费复活已关闭；请联系管理员。");
                        return;
                    }
                    double cost = calculateCost(currentCount);
                    if (cost < 0) {
                        player.sendMessage("§7  已达到本局复活上限。");
                    } else {
                        player.sendMessage("§7  下次复活费用: §e" + cost + " §7(第 " + (currentCount + 1) + " 次)");
                        player.sendMessage("§7  使用 §f/dungeon revive §7或 Web API /api/realestate-dungeon/revive 复活");
                    }
                }));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String instanceId = instanceManager.getPlayerActiveInstance(player.getUniqueId());
        if (instanceId == null) return;
        if (!deadPlayers.contains(player.getUniqueId())) return;
        var mainWorld = org.bukkit.Bukkit.getWorld("world");
        if (mainWorld != null) event.setRespawnLocation(mainWorld.getSpawnLocation());
        eco.scheduler().runEntityLater(player, () -> {
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            player.sendMessage("§6[副本] §c你已离开战斗区域；完成付费复活后才会返回检查点。");
        }, () -> { }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        eco.scheduler().runEntityLater(player,
                () -> instanceManager.recoverPlayerOnJoin(player), () -> { }, 20L);
    }

    public double calculateCost(int currentReviveCount) {
        int n = currentReviveCount + 1;
        DungeonConfigManager.ConfigSnapshot s = configManager.snapshot();
        if (n > s.maxRevivesPerPlayer) return ERR_LIMIT;
        return s.reviveBaseCost * Math.pow(s.reviveExponent, n - 1);
    }

    /**
     * 异步执行付费复活。SQL 始终在数据库工作池，Vault、玩家状态与传送始终在服务器线程。
     * 正数或 0 表示已确认付款并实际回场；负数为错误码。
     */
    public CompletableFuture<Double> reviveAsync(String instanceId, UUID playerUuid) {
        CompletableFuture<Double> result = new CompletableFuture<>();
        eco.scheduler().runPlayer(playerUuid, ignored -> startRevive(instanceId, playerUuid, result),
                () -> result.complete(ERR_OFFLINE));
        return result;
    }

    private void startRevive(String instanceId, UUID playerUuid, CompletableFuture<Double> result) {
        if (instanceId == null || instanceId.isBlank() || playerUuid == null) {
            result.complete(ERR_INSTANCE);
            return;
        }
        String active = instanceManager.getPlayerActiveInstance(playerUuid);
        Player online = org.bukkit.Bukkit.getPlayer(playerUuid);
        if (!instanceId.equals(active) || online == null || !online.isOnline()) {
            result.complete(online == null || !online.isOnline() ? ERR_OFFLINE : ERR_INSTANCE);
            return;
        }
        if (!instanceManager.isInstanceReady(instanceId)) {
            result.complete(ERR_INSTANCE);
            return;
        }
        String requestKey = instanceId + ':' + playerUuid;
        if (!reviveInFlight.add(requestKey)) {
            result.complete(ERR_NOT_DEAD);
            return;
        }
        DungeonConfigManager.ConfigSnapshot config = configManager.snapshot();
        instanceManager.submitReviveSql(connection -> {
            DungeonReviveStore.Candidate candidate = DungeonReviveStore.candidate(connection, instanceId, playerUuid);
            if (candidate == null) return new Preparation(ERR_INSTANCE, null);
            if (!DungeonInstanceManager.STATUS_ACTIVE.equals(candidate.instanceStatus())) {
                return new Preparation(ERR_INSTANCE, null);
            }
            if (!"DEAD".equals(candidate.participantStatus())) {
                return new Preparation(ERR_NOT_DEAD, null);
            }
            int nextCount = candidate.reviveCount() + 1;
            if (nextCount > config.maxRevivesPerPlayer) return new Preparation(ERR_LIMIT, null);
            double cost = config.reviveBaseCost * Math.pow(config.reviveExponent, nextCount - 1);
            if (!Double.isFinite(cost) || cost < 0) return new Preparation(ERR_PERSIST, null);
            DungeonReviveStore.Revival revival = DungeonReviveStore.reserve(connection, instanceId,
                    playerUuid, candidate.reviveCount(), nextCount, cost, System.currentTimeMillis() / 1000);
            return revival == null ? new Preparation(ERR_NOT_DEAD, null) : new Preparation(cost, revival);
        }).whenComplete((preparation, failure) -> eco.scheduler().runPlayer(playerUuid, () -> {
            if (failure != null || preparation == null) {
                finish(requestKey, result, ERR_PERSIST);
            } else if (preparation.revival() == null) {
                finish(requestKey, result, preparation.result());
            } else {
                claimCharge(requestKey, online, preparation, result);
            }
        }));
    }

    private void claimCharge(String requestKey, Player player, Preparation preparation,
                             CompletableFuture<Double> result) {
        DungeonReviveStore.Revival revival = preparation.revival();
        instanceManager.submitReviveSql(connection -> DungeonReviveStore.claimCharge(
                connection, revival, System.currentTimeMillis() / 1000)).whenComplete((claimed, failure) ->
                eco.scheduler().runPlayer(revival.playerUuid(), () -> {
                    if (failure != null || !Boolean.TRUE.equals(claimed)) {
                        instanceManager.submitReviveSql(connection -> DungeonReviveStore.cancelUnclaimedCharge(
                                connection, revival, "charge claim failed", System.currentTimeMillis() / 1000));
                        finish(requestKey, result, ERR_PERSIST);
                        return;
                    }
                    withdrawCharge(requestKey, player, preparation, result);
                }));
    }

    private void withdrawCharge(String requestKey, Player player, Preparation preparation,
                                CompletableFuture<Double> result) {
        DungeonReviveStore.Revival revival = preparation.revival();
        if (eco.vaultHook().directBuiltinActive()) {
            instanceManager.submitReviveSql(connection -> {
                if (!eco.vaultHook().hasDirect(revival.playerUuid(), preparation.result())) return ERR_BALANCE;
                return eco.vaultHook().withdrawDirect(revival.playerUuid(), player.getName(), preparation.result())
                        ? preparation.result() : ERR_WITHDRAW;
            }).whenComplete((charged, failure) -> eco.scheduler().runPlayer(revival.playerUuid(), ignored -> {
                if (failure != null || charged == null) {
                    instanceManager.submitReviveSql(connection -> DungeonReviveStore.markReview(connection, revival,
                            DungeonReviveStore.CHARGE_CLAIMED, "Built-in withdrawal outcome unknown",
                            System.currentTimeMillis() / 1000));
                    finish(requestKey, result, ERR_PERSIST);
                } else if (charged < 0) {
                    rejectCharge(requestKey, revival, charged, "built-in wallet rejected withdrawal", result);
                } else {
                    confirmPaidCharge(requestKey, player, preparation, result);
                }
            }, () -> finish(requestKey, result, ERR_OFFLINE)));
            return;
        }
        var account = org.bukkit.Bukkit.getOfflinePlayer(revival.playerUuid());
        if (!eco.vaultHook().isAvailable()) {
            rejectCharge(requestKey, revival, ERR_VAULT, "Vault unavailable", result);
            return;
        }
        try {
            if (!eco.vaultHook().has(account, preparation.result())) {
                rejectCharge(requestKey, revival, ERR_BALANCE, "insufficient balance", result);
                return;
            }
            if (!eco.vaultHook().withdraw(account, preparation.result())) {
                rejectCharge(requestKey, revival, ERR_WITHDRAW, "Vault rejected withdrawal", result);
                return;
            }
        } catch (Throwable uncertain) {
            instanceManager.submitReviveSql(connection -> DungeonReviveStore.markReview(connection, revival,
                    DungeonReviveStore.CHARGE_CLAIMED, "Vault withdrawal outcome unknown: " + messageOf(uncertain),
                    System.currentTimeMillis() / 1000));
            finish(requestKey, result, ERR_PERSIST);
            return;
        }

        confirmPaidCharge(requestKey, player, preparation, result);
    }

    private void confirmPaidCharge(String requestKey, Player player, Preparation preparation,
                                   CompletableFuture<Double> result) {
        DungeonReviveStore.Revival revival = preparation.revival();
        instanceManager.submitReviveSql(connection -> DungeonReviveStore.confirmPaid(
                connection, revival, System.currentTimeMillis() / 1000)).whenComplete((confirmed, failure) ->
                eco.scheduler().runPlayer(revival.playerUuid(), ignored -> {
                    if (failure != null || !Boolean.TRUE.equals(confirmed)) {
                        instanceManager.requestReviveRefund(revival, DungeonReviveStore.CHARGE_CLAIMED,
                                "paid charge could not be confirmed");
                        finish(requestKey, result, ERR_PERSIST);
                        return;
                    }
                    returnPaidPlayer(requestKey, player, preparation, result);
                }, () -> finish(requestKey, result, ERR_OFFLINE)));
    }

    private void rejectCharge(String requestKey, DungeonReviveStore.Revival revival,
                              double errorCode, String detail, CompletableFuture<Double> result) {
        instanceManager.submitReviveSql(connection -> DungeonReviveStore.rejectCharge(
                connection, revival, detail, System.currentTimeMillis() / 1000)).whenComplete((ignored, failure) ->
                eco.scheduler().runPlayer(revival.playerUuid(),
                        ignoredPlayer -> finish(requestKey, result, errorCode),
                        () -> finish(requestKey, result, errorCode)));
    }

    private void returnPaidPlayer(String requestKey, Player player, Preparation preparation,
                                  CompletableFuture<Double> result) {
        DungeonReviveStore.Revival revival = preparation.revival();
        if (!player.isOnline() || !revival.instanceId().equals(
                instanceManager.getPlayerActiveInstance(revival.playerUuid()))
                || !instanceManager.isInstanceReady(revival.instanceId())) {
            instanceManager.requestReviveRefund(revival, DungeonReviveStore.PAID_PENDING,
                    "instance world or player unavailable before return");
            finish(requestKey, result, ERR_PERSIST);
            return;
        }
        instanceManager.returnPlayerToInstanceAsync(revival.instanceId(), player).thenAccept(returned -> {
            if (!returned) {
                instanceManager.requestReviveRefund(revival, DungeonReviveStore.PAID_PENDING,
                        "instance teleport failed before return");
                finish(requestKey, result, ERR_PERSIST);
                return;
            }
            deadPlayers.remove(revival.playerUuid());
            instanceManager.submitReviveSql(connection -> DungeonReviveStore.completeReturn(
                    connection, revival, System.currentTimeMillis() / 1000)).whenComplete((completed, failure) ->
                    eco.scheduler().runPlayer(revival.playerUuid(), ignored -> {
                        if (failure != null || !Boolean.TRUE.equals(completed)) {
                            eco.getLogger().severe("[副本系统] 玩家已回场但完成状态未落库，保留 PAID_PENDING 自动恢复: "
                                    + revival.id());
                            eco.scheduler().runEntityLater(player,
                                    () -> instanceManager.resumePendingRevives(revival.instanceId()),
                                    () -> { }, 20L);
                        }
                        finish(requestKey, result, preparation.result());
                    }, () -> finish(requestKey, result, preparation.result())));
        });
    }

    private void finish(String requestKey, CompletableFuture<Double> result, double value) {
        reviveInFlight.remove(requestKey);
        result.complete(value);
    }

    private static String messageOf(Throwable failure) {
        if (failure == null) return "unknown failure";
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private record Preparation(double result, DungeonReviveStore.Revival revival) { }
}
