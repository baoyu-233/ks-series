package org.ksskill;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.ksskill.model.SkillDef;
import org.ksskill.model.TriggerType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ON_INTERVAL 常驻扫描（每秒跑一次）：对每个 ON_INTERVAL 技能，遍历在线玩家，
 * 拥有且过了 interval-seconds 节流则施放。复用 CooldownTracker 做“每 N 秒一次”。
 * 无 ON_INTERVAL 技能时立即返回，开销可忽略。
 */
public final class IntervalTask implements Runnable {

    private final SkillRegistry registry;
    private final BindingResolver binding;
    private final CooldownTracker cooldown;

    public IntervalTask(SkillRegistry registry, BindingResolver binding, CooldownTracker cooldown) {
        this.registry = registry;
        this.binding = binding;
        this.cooldown = cooldown;
    }

    @Override
    public void run() {
        List<SkillDef> defs = registry.byTrigger(TriggerType.ON_INTERVAL);
        if (defs.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!PlayerEligibility.isEligible(p)) continue;
            for (SkillDef def : defs) {
                if (!binding.has(p, def)) continue;
                if (def.chance() < 1.0 && ThreadLocalRandom.current().nextDouble() >= def.chance()) continue;
                if (!cooldown.ready(p.getUniqueId(), def.id(), def.intervalSeconds())) continue;
                if (MythicCaster.cast(p, def.mythicSkill())) {
                    cooldown.mark(p.getUniqueId(), def.id());
                }
            }
        }
    }
}
