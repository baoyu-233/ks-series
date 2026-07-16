package org.kseco.extra.enterprise;

/**
 * 资质校验器。
 * 检查企业是否满足投标/承接项目的资质要求。
 *
 * 核心规则：
 * - 投标企业注册资本不得低于项目标的 75%
 * - 拼包企业的联合注册资本也必须满足此要求
 */
public final class QualificationChecker {

    private final EnterpriseManager enterpriseManager;

    public QualificationChecker(EnterpriseManager enterpriseManager) {
        this.enterpriseManager = enterpriseManager;
    }

    /**
     * 检查单个企业是否满足项目资质要求。
     */
    public boolean checkEnterprise(String enterpriseId, double projectBudget) {
        var ent = enterpriseManager.getEnterprise(enterpriseId);
        if (ent == null) return false;
        return ent.registeredCapital() >= projectBudget * 0.75;
    }

    /**
     * 检查大项目分包可行性 — 分包企业各自满足其承担的子项目金额。
     */
    public boolean checkSubcontract(String enterpriseId, double subProjectBudget) {
        return checkEnterprise(enterpriseId, subProjectBudget);
    }
}
