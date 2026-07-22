package org.kseries.instanceworld.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class InstanceLifecycleEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    public enum Phase {
        PREPARED,
        RELEASE_STARTED,
        RELEASE_COMPLETED,
        PREPARATION_FAILED
    }

    private final Phase phase;
    private final InstanceSnapshot instance;
    private final ReleaseCause releaseCause;

    public InstanceLifecycleEvent(Phase phase, InstanceSnapshot instance, ReleaseCause releaseCause) {
        this.phase = phase;
        this.instance = instance;
        this.releaseCause = releaseCause;
    }

    public Phase phase() { return phase; }

    public InstanceSnapshot instance() { return instance; }

    public ReleaseCause releaseCause() { return releaseCause; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
