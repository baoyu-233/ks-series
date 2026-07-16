package org.kseco.extra.enterprise;

import org.kseco.KsEco;
import org.kseco.extra.KsEcoExtraModule;

/**
 * 现代企业与招投标系统 — Extra 模块入口。
 *
 * 功能：
 * - 企业注册（区域企业/合伙办企，上缴注册资本）
 * - 官方注资企业/国有企业（注册资本隐匿）
 * - 招投标系统（官方/企业发布项目）
 * - 资质校验（注册资本 ≥ 标的 75%）
 * - 分包与拼包（大项目分包，多小企业拼包）
 * - 预付款（可自定义）+ 中途违约金
 * - GUI 端（企业信息、招投标大厅）+ Web 端（考勤/工资/权限）
 */
public final class EnterpriseExtra implements KsEcoExtraModule {

    private KsEco eco;
    private EnterpriseManager enterpriseManager;
    private EnterpriseAccessProviderImpl enterpriseAccessProvider;
    private BiddingManager biddingManager;
    private QualificationChecker qualificationChecker;

    @Override
    public String getId() { return "ks-eco-enterprise"; }

    @Override
    public String getName() { return "现代企业与招投标系统"; }

    @Override
    public void onLoad(KsEco eco) {
        this.eco = eco;
        this.enterpriseManager = new EnterpriseManager(eco);
        this.enterpriseAccessProvider = new EnterpriseAccessProviderImpl(eco);
        this.biddingManager = new BiddingManager(eco, enterpriseManager);
        this.qualificationChecker = new QualificationChecker(enterpriseManager);
        eco.getLogger().info("[企业系统] 模块已加载。");
    }

    @Override
    public void onEnable() {
        enterpriseManager.init();
        enterpriseAccessProvider.init();
        eco.registerEnterpriseAccessProvider(enterpriseAccessProvider);
        biddingManager.init();
        eco.getLogger().info("[企业系统] 模块已启用。");
    }

    @Override
    public void onDisable() {
        eco.getLogger().info("[企业系统] 模块已停用。");
    }

    public EnterpriseManager enterpriseManager() { return enterpriseManager; }
    public EnterpriseAccessProviderImpl enterpriseAccessProvider() { return enterpriseAccessProvider; }
    public BiddingManager biddingManager() { return biddingManager; }
    public QualificationChecker qualificationChecker() { return qualificationChecker; }
}
