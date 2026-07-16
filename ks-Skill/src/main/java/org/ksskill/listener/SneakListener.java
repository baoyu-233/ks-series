package org.ksskill.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.ksskill.SkillEngine;
import org.ksskill.model.TriggerType;

/** ON_SNEAK：玩家按下潜行（非松开）时触发。 */
public final class SneakListener implements Listener {

    private final SkillEngine engine;

    public SneakListener(SkillEngine engine) {
        this.engine = engine;
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking()) {
            engine.run(e.getPlayer(), TriggerType.ON_SNEAK, null);
        }
    }
}
