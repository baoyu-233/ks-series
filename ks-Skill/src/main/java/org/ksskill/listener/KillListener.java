package org.ksskill.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.ksskill.SkillEngine;
import org.ksskill.model.TriggerType;

/** ON_KILL：玩家击杀生物时触发。 */
public final class KillListener implements Listener {

    private final SkillEngine engine;

    public KillListener(SkillEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDeath(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer != null) {
            engine.run(killer, TriggerType.ON_KILL, null);
        }
    }
}
