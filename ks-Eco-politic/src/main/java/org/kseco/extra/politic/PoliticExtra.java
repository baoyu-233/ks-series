package org.kseco.extra.politic;

import org.kseco.KsEco;
import org.kseco.extra.KsEcoExtraModule;

/**
 * 元老院与共和政治系统 — ks-Eco 附属模块。
 *
 * 接管宏观经济参数（税率、利率、土地）的调控权，
 * 通过元老院/执政官/骑士/保民官四阶身份 + 立法投票流程实现系统自我驱动。
 *
 * 解耦设计：卸载此模块 → 所有参数修改权回退给 OP/admin。
 */
public final class PoliticExtra implements KsEcoExtraModule {

    private KsEco eco;
    private PoliticManager politicManager;
    private ProposalManager proposalManager;
    private VoteManager voteManager;
    private ElectionEngine electionEngine;
    private PoliticWebHandler webHandler;
    private PoliticCommand politicCommand;

    // no-arg constructor — required by ExtraModuleLoader
    public PoliticExtra() {}

    @Override
    public String getId() { return "ks-eco-politic"; }

    @Override
    public String getName() { return "元老院与共和政治系统"; }

    @Override
    public void onLoad(KsEco eco) {
        this.eco = eco;
        this.politicManager = new PoliticManager(eco);
        this.voteManager = new VoteManager(eco, politicManager);
        this.proposalManager = new ProposalManager(eco, politicManager, voteManager);
        this.electionEngine = new ElectionEngine(eco, politicManager, proposalManager);
        eco.getLogger().info("[政治系统] 模块已加载。");
    }

    @Override
    public void onEnable() {
        politicManager.init();
        voteManager.init();
        proposalManager.init();
        electionEngine.init();
        // Register web handler via ks-core router
        this.webHandler = new PoliticWebHandler(eco, politicManager, proposalManager, voteManager, electionEngine);
        eco.bridge().registerRoute("ks-eco-politic", "/ks-Eco/politic", webHandler);
        // Register in-game command
        this.politicCommand = new PoliticCommand(politicManager, proposalManager, eco);
        try {
            var cmd = eco.getCommand("politic");
            if (cmd != null) cmd.setExecutor(politicCommand);
            else eco.getLogger().warning("[政治系统] 未找到 politic 命令定义（检查 plugin.yml）");
        } catch (Exception e) {
            eco.getLogger().warning("[政治系统] 注册命令失败: " + e.getMessage());
        }
        eco.getLogger().info("[政治系统] 模块已启用。");
    }

    @Override
    public void onDisable() {
        proposalManager.shutdown();
        electionEngine.shutdown();
        eco.bridge().unregisterRoute("ks-eco-politic");
        eco.getLogger().info("[政治系统] 模块已停用。");
    }

    @Override
    public void onCrossServerInvalidation(String namespace, String key) {
        if (!"politic".equals(namespace) || eco == null || politicManager == null) return;
        eco.asyncWorkPool().executeDatabase(politicManager::refreshSharedStateFromRemote);
    }

    // --- public getters for EcoWebHandler reflection ---
    public PoliticManager politicManager() { return politicManager; }
    public ProposalManager proposalManager() { return proposalManager; }
    public VoteManager voteManager() { return voteManager; }
    public ElectionEngine electionEngine() { return electionEngine; }
}
