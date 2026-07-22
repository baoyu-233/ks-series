package org.kseco.extra.tax;

import org.kseco.KsEco;
import org.kseco.extra.KsEcoExtraModule;

/**
 * 税法与宏观调控系统 — Extra 模块入口。
 *
 * 功能：
 * - 税收征收（拦截所有经济活动的交易税）
 * - 动态税率（Web 管理面板调整税率）
 * - 阶梯式税率（不同规模企业不同税率）
 * - 惩罚机制（逃税漏税罚金）
 * - "罚金税"（对违反契约行为的额外税率）
 */
public final class TaxExtra implements KsEcoExtraModule {

    private KsEco eco;
    private TaxManager taxManager;
    private TaxRateManager taxRateManager;
    private PenaltyManager penaltyManager;

    @Override
    public String getId() { return "ks-eco-tax"; }

    @Override
    public String getName() { return "税法与宏观调控系统"; }

    @Override
    public void onLoad(KsEco eco) {
        this.eco = eco;
        this.taxRateManager = new TaxRateManager(eco);
        this.taxManager = new TaxManager(eco, taxRateManager);
        this.penaltyManager = new PenaltyManager(eco);
        eco.getLogger().info("[税法系统] 模块已加载。");
    }

    @Override
    public void onEnable() {
        taxManager.init();
        taxRateManager.init();
        penaltyManager.init();
        eco.getLogger().info("[税法系统] 模块已启用。");
    }

    @Override
    public void onDisable() {
        if (taxRateManager != null) taxRateManager.shutdown();
        eco.getLogger().info("[税法系统] 模块已停用。");
    }

    public TaxManager taxManager() { return taxManager; }
    public TaxRateManager taxRateManager() { return taxRateManager; }
    public PenaltyManager penaltyManager() { return penaltyManager; }
}
