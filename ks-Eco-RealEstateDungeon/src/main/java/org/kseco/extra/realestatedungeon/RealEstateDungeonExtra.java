package org.kseco.extra.realestatedungeon;

import org.kseco.KsEco;
import org.kseco.extra.KsEcoExtraModule;

/**
 * ks-Eco 附属模块：副本与硬核房产系统。
 *
 * 解耦设计：卸载此模块 → 副本与 ks_re_plots 副本房产字段不再可用，
 * 但 ks_re_plots 主世界房产（instance_id=NULL）仍由 ks-Eco-RealEstate 独立维护。
 *
 * 与 ks-Eco-RealEstate 的集成：
 * - 共享 ks_re_plots 表（加 instance_id + property_function 两列）
 * - 副本销毁时清理 ks_re_plots WHERE instance_id=?
 */
public final class RealEstateDungeonExtra implements KsEcoExtraModule {

    private KsEco eco;
    private DungeonConfigManager configManager;
    private DungeonGridAllocator gridAllocator;
    private DungeonRpgBridge rpgBridge;
    private DungeonInstanceManager instanceManager;
    private DungeonPartyManager partyManager;
    private DungeonDeathHandler deathHandler;
    private DungeonWebHandler webHandler;
    private DungeonCommand dungeonCommand;

    public RealEstateDungeonExtra() {}

    @Override
    public String getId() { return "ks-eco-realestatedungeon"; }

    @Override
    public String getName() { return "副本与硬核房产系统"; }

    @Override
    public void onLoad(KsEco eco) {
        this.eco = eco;
        this.configManager = new DungeonConfigManager(eco);
        this.gridAllocator = new DungeonGridAllocator(eco, configManager);
        this.rpgBridge = new DungeonRpgBridge(eco);
        this.instanceManager = new DungeonInstanceManager(eco, configManager, gridAllocator, rpgBridge);
        this.partyManager = new DungeonPartyManager();
        this.deathHandler = new DungeonDeathHandler(eco, instanceManager, configManager);
        eco.getLogger().info("[副本系统] 模块已加载。");
    }

    @Override
    public void onEnable() {
        // 1) 配置 + 建表（副本表；ks_re_plots 的 instance_id/property_function 由 ks-Eco-RealEstate 维护）
        configManager.init();
        instanceManager.init();
        // 2) 注册死亡事件
        deathHandler.register();
        // 2.5) 启动监控任务：boss 存活检测（自动通关）+ 超时检测（自动结束）
        instanceManager.startMonitor();
        // 3) 注册 Web 路由（仅副本相关；房产端点已迁至 ks-Eco-RealEstate）
        this.webHandler = new DungeonWebHandler(eco, configManager, instanceManager,
                gridAllocator, deathHandler);
        eco.bridge().registerRoute("ks-eco-realestatedungeon",
                "/ks-Eco/api/realestate-dungeon", webHandler);
        // 4) 注册 /dungeon 命令（含组队/邀请/开本/复活子命令）
        this.dungeonCommand = new DungeonCommand(eco, instanceManager, gridAllocator, partyManager, deathHandler);
        try {
            var cmd = eco.getCommand("dungeon");
            if (cmd != null) cmd.setExecutor(dungeonCommand);
            else eco.getLogger().warning("[副本系统] 未找到 dungeon 命令定义（检查 plugin.yml）");
        } catch (Exception e) {
            eco.getLogger().warning("[副本系统] 注册命令失败: " + e.getMessage());
        }
        eco.getLogger().info("[副本系统] 模块已启用。");
    }

    @Override
    public void onDisable() {
        try {
            instanceManager.stopMonitor();
        } catch (Exception ignored) {}
        try {
            eco.bridge().unregisterRoute("ks-eco-realestatedungeon");
        } catch (Exception ignored) {}
        eco.getLogger().info("[副本系统] 模块已停用。");
    }

    // --- getters for EcoWebHandler reflection (if needed) ---
    public DungeonConfigManager configManager() { return configManager; }
    public DungeonInstanceManager instanceManager() { return instanceManager; }
    public DungeonGridAllocator gridAllocator() { return gridAllocator; }
    public DungeonDeathHandler deathHandler() { return deathHandler; }
}
