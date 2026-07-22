package org.kseco.extra.politic;

import org.kseco.KsEco;
import org.kseco.scheduler.EcoScheduler;

import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 选举定时引擎。
 *
 * 每 N 分钟（可配置）自动检查：
 * 1. 执政官任期是否到期 → 元老院轮选
 * 2. 保民官任期是否到期 → 触发补位
 * 3. 骑士阶级排行刷新
 * 4. 全量互斥巡检
 */
public final class ElectionEngine {

    private final KsEco eco;
    private final PoliticManager politicManager;
    private final ProposalManager proposalManager; // reserved for future auto-proposals
    private EcoScheduler.TaskHandle task;
    private final AtomicBoolean tickRunning = new AtomicBoolean();

    public ElectionEngine(KsEco eco, PoliticManager politicManager, ProposalManager proposalManager) {
        this.eco = eco;
        this.politicManager = politicManager;
        this.proposalManager = proposalManager;
    }

    public void init() {
        int intervalMin = politicManager.getConfigInt("election_check_interval_min", 30);
        long intervalTicks = intervalMin * 60L * 20L;

        // The timer only queues database work. Bukkit identity/permission reads inside
        // the election flow are marshalled through EcoScheduler.
        task = eco.scheduler().runGlobalTimer(this::queueTick, 200L, intervalTicks);

        eco.getLogger().info("[政治系统] 选举定时器已启动（间隔 " + intervalMin + " 分钟）");
    }

    private void queueTick() {
        if (!tickRunning.compareAndSet(false, true)) return;
        try {
            eco.asyncWorkPool().executeDatabase(() -> {
                try {
                    tick();
                } finally {
                    tickRunning.set(false);
                }
            });
        } catch (RejectedExecutionException failure) {
            tickRunning.set(false);
            eco.getLogger().warning("[政治系统] 选举检查提交失败: " + failure.getMessage());
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 定时 tick — 异步执行，所有 DB 操作使用独立连接。
     */
    private void tick() {
        try {
            long now = System.currentTimeMillis() / 1000;

            // 1. 过期清理
            politicManager.cleanupExpired();

            // 2. 执政官：不再自动选举/轮换，到期由 cleanupExpired() 卸任后空缺，等待 admin 手动任命
            //    （/api/admin/consul/assign）

            // 3. 保民官全民选举周期：到期则计票就任并开启下一轮，没有进行中的选举则立即开启一轮
            String curElectionId = politicManager.getTribuneElectionId();
            long endsAt = politicManager.getTribuneElectionEndsAt();
            if (curElectionId == null || curElectionId.isEmpty()) {
                politicManager.startNewTribuneElection();
                eco.getLogger().info("[政治系统] 已开启首轮保民官选举");
            } else if (now >= endsAt) {
                var changes = politicManager.tallyAndAssignTribunes(curElectionId);
                politicManager.startNewTribuneElection();
                eco.getLogger().info("[政治系统] 保民官选举计票完成（" + changes.size() + " 项变更），已开启下一轮");
            }

            // 4. 骑士排行刷新
            politicManager.recomputeEquestrians();

            // 5. 全量互斥巡检
            politicManager.fullMutualExclusionAudit();

        } catch (Exception e) {
            eco.getLogger().warning("[政治系统] 选举定时 tick 异常: " + e.getMessage());
        }
    }

    /**
     * 手动触发选举（管理端 API 调用）。
     */
    public Map<String, Object> triggerElection(String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        long now = System.currentTimeMillis() / 1000;

        switch (type.toUpperCase()) {
            case "CONSUL" -> {
                result.put("error", "执政官改为后台直接任命，请使用「直接任命执政官」功能（POST /api/admin/consul/assign）");
            }
            case "TRIBUNE" -> {
                // 手动提前结束当前一轮投票并立即计票就任，然后开启下一轮
                String curId = politicManager.getTribuneElectionId();
                var changes = politicManager.tallyAndAssignTribunes(curId);
                politicManager.startNewTribuneElection();
                result.put("tribunes", politicManager.getTribunes().size());
                result.put("changes", changes.size());
                result.put("success", true);
            }
            case "EQUESTRIAN" -> {
                var changes = politicManager.recomputeEquestrians();
                result.put("changes", changes.size());
                result.put("success", true);
            }
            case "ALL" -> {
                triggerElection("CONSUL");
                triggerElection("TRIBUNE");
                triggerElection("EQUESTRIAN");
                result.put("success", true);
            }
            default -> result.put("error", "未知选举类型: " + type);
        }
        result.put("triggeredAt", now);
        return result;
    }

    /**
     * 选举状态信息。
     */
    public Map<String, Object> getElectionStatus() {
        Map<String, Object> s = new LinkedHashMap<>();
        long now = System.currentTimeMillis() / 1000;

        s.put("consul", politicManager.getConsul() != null ?
            politicManager.getConsul().toMap() : null);
        s.put("senators", politicManager.getSenators().stream().map(PoliticManager.Office::toMap).toList());
        s.put("tribunes", politicManager.getTribunes().stream().map(PoliticManager.Office::toMap).toList());
        s.put("equestrians", politicManager.getEquestrians().stream().map(PoliticManager.Office::toMap).toList());

        int intervalMin = politicManager.getConfigInt("election_check_interval_min", 30);
        s.put("nextCheckInMinutes", intervalMin);
        s.put("termDurationHours", politicManager.getConfigInt("term_duration_hours", 168));

        s.put("tribuneElectionId", politicManager.getTribuneElectionId());
        s.put("tribuneElectionStartedAt", politicManager.getTribuneElectionStartedAt());
        s.put("tribuneElectionEndsAt", politicManager.getTribuneElectionEndsAt());
        s.put("tribuneElectionIntervalHours", politicManager.getConfigInt("tribune_election_interval_hours", 24));

        // 下一次检查的大概时间
        s.put("timestamp", now);

        return s;
    }
}
