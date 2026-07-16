package org.ksskill.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一条技能条目（skills.yml 里的一项）。不可变。
 *
 * @param id              技能内部ID（skills.yml 的 key）
 * @param display         展示名（含颜色码）
 * @param trigger         触发时机
 * @param chance          触发概率 [0,1]
 * @param cooldownSeconds 内部冷却（秒），0 = 无冷却
 * @param intervalSeconds ON_INTERVAL 的施放间隔（秒）
 * @param mythicSkill     命中后施放的 MythicMobs 技能名
 * @param damageCauses    仅对这些伤害类型生效（空 = 任意）；DamageCause 名，大写
 * @param titles          绑定：佩戴这些称号ID之一即拥有
 * @param permissions     绑定：拥有这些权限节点之一即拥有
 * @param itemMarks       绑定：手持/穿戴带这些标记之一的物品即拥有
 * @param worlds          世界限制：仅在这些世界生效（空 = 所有世界）；世界名区分大小写
 */
public record SkillDef(
        String id,
        String display,
        TriggerType trigger,
        double chance,
        long cooldownSeconds,
        long intervalSeconds,
        String mythicSkill,
        Set<String> damageCauses,
        Set<Integer> titles,
        List<String> permissions,
        Set<String> itemMarks,
        Set<String> worlds
) {
    /** 伤害类型过滤：未配置 damageCauses 则任意通过；否则要求 causeName 命中。 */
    public boolean matchesCause(String causeName) {
        if (damageCauses.isEmpty()) return true;
        return causeName != null && damageCauses.contains(causeName.toUpperCase(Locale.ROOT));
    }

    /** 世界过滤：未配置 worlds 则任意世界通过；否则要求当前世界在白名单内。 */
    public boolean allowedInWorld(String worldName) {
        if (worlds.isEmpty()) return true;
        return worldName != null && worlds.contains(worldName);
    }
}
