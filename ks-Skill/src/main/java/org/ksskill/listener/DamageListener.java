package org.ksskill.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.ksskill.SkillEngine;
import org.ksskill.model.TriggerType;

/**
 * ON_DAMAGED（受害者是玩家）与 ON_ATTACK（攻击者是玩家/其弹射物的射手）。
 * ignoreCancelled=true —— 只对真实生效的伤害触发。
 */
public final class DamageListener implements Listener {

    private final SkillEngine engine;

    public DamageListener(SkillEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        String cause = e.getCause().name();

        if (e.getEntity() instanceof Player victim) {
            engine.run(victim, TriggerType.ON_DAMAGED, cause);
        }
        Player attacker = resolveAttacker(e.getDamager());
        if (attacker != null) {
            engine.run(attacker, TriggerType.ON_ATTACK, cause);
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }
}
