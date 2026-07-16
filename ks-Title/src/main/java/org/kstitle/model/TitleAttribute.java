package org.kstitle.model;

/**
 * 称号属性/技能加成。buffType: POTION(药水效果,buffKey=PotionEffectType名,amount=等级)
 * / ATTRIBUTE(Bukkit Attribute,buffKey=Attribute名,amount=数值增量)
 * / MYTHICLIB_STAT(MythicLib 属性,buffKey=stat名,amount=数值，best-effort反射)。
 * extra 存 JSON（如 {"hide":true} 药水粒子隐藏）。
 */
public record TitleAttribute(int id, int titleId, String buffType, String buffKey, double amount, String extra) {
}
