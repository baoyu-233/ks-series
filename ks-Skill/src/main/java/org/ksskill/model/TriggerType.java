package org.ksskill.model;

import java.util.Locale;

/**
 * 技能触发时机。新增触发器时：在此加枚举 + 写对应的 Bukkit 监听器调 {@code SkillEngine.run(player, 该枚举, cause)}。
 */
public enum TriggerType {
    ON_DAMAGED,   // 玩家受到伤害
    ON_ATTACK,    // 玩家造成伤害
    ON_KILL,      // 玩家击杀生物
    ON_INTERVAL,  // 周期性(常驻光环)
    ON_SNEAK;     // 玩家按下潜行

    /** 配置字符串 → 枚举，非法/空返回 null（大小写不敏感）。 */
    public static TriggerType fromConfig(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
