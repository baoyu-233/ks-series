package org.ksskill.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.ksskill.CooldownTracker;
import org.ksskill.TitleBuffCache;

/** 玩家退出时清理其冷却与称号缓存，避免内存缓慢累积。 */
public final class PlayerStateListener implements Listener {

    private final CooldownTracker cooldown;
    private final TitleBuffCache titles;

    public PlayerStateListener(CooldownTracker cooldown, TitleBuffCache titles) {
        this.cooldown = cooldown;
        this.titles = titles;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cooldown.clear(e.getPlayer().getUniqueId());
        titles.invalidate(e.getPlayer().getUniqueId());
    }
}
