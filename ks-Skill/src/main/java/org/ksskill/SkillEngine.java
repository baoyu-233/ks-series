package org.ksskill;

import org.bukkit.entity.Player;
import org.ksskill.model.SkillDef;
import org.ksskill.model.TriggerType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 触发核心：给定玩家+触发时机，遍历该触发器下的技能条目，逐条判定
 * 伤害类型 → 绑定 → 概率 → 冷却，通过则施放 MythicMobs 技能并记冷却。
 */
public final class SkillEngine {

    private final SkillRegistry registry;
    private final BindingResolver binding;
    private final CooldownTracker cooldown;

    public SkillEngine(SkillRegistry registry, BindingResolver binding, CooldownTracker cooldown) {
        this.registry = registry;
        this.binding = binding;
        this.cooldown = cooldown;
    }

    /**
     * @param causeName 伤害类型名（DamageCause），非伤害触发传 null
     */
    public void run(Player player, TriggerType type, String causeName) {
        var defs = registry.byTrigger(type);
        if (defs.isEmpty()) return;
        for (SkillDef def : defs) {
            if (!def.matchesCause(causeName)) continue;
            if (!binding.has(player, def)) continue;
            if (def.chance() < 1.0 && ThreadLocalRandom.current().nextDouble() >= def.chance()) continue;
            if (!cooldown.ready(player.getUniqueId(), def.id(), def.cooldownSeconds())) continue;
            if (MythicCaster.cast(player, def.mythicSkill())) {
                cooldown.mark(player.getUniqueId(), def.id());
            }
        }
    }

    /** 调试用：无视概率与冷却，强制对玩家施放某技能。 */
    public boolean force(Player player, String skillId) {
        SkillDef def = registry.get(skillId);
        if (def == null) return false;
        return MythicCaster.cast(player, def.mythicSkill());
    }
}
