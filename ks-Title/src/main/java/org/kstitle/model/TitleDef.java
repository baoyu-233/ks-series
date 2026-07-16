package org.kstitle.model;

/**
 * 称号定义。unlockType: PURCHASE(商店购买) / ADMIN_GRANT(仅管理员发放) / CONDITION(满足条件自动解锁)。
 * conditionType 目前实现 PERMISSION（权限节点），其余类型为预留扩展点。
 */
public record TitleDef(
    int id,
    String displayName,
    String description,
    String category,
    String rarity,
    double price,
    String unlockType,
    String conditionType,
    String conditionValue,
    boolean visible,
    boolean enabled,
    long createdAt
) {
    public boolean isPurchase() { return "PURCHASE".equals(unlockType); }
    public boolean isCondition() { return "CONDITION".equals(unlockType); }
}
