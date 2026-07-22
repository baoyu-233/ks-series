package org.kseries.instanceworld.api;

import org.bukkit.World;

import java.util.List;

/**
 * Server-thread-only prepared result. Consumers must not move the World reference
 * or derived Bukkit objects to worker threads.
 */
public record PreparedInstance(
        String instanceId,
        String namespace,
        String templateKey,
        World world,
        String gridId,
        int gridCenterX,
        int gridCenterZ,
        InstanceBounds bounds,
        InstancePoint pasteCenter,
        InstancePoint spawn,
        List<InstanceMarker> markers
) {
    public PreparedInstance {
        markers = List.copyOf(markers == null ? List.of() : markers);
    }
}
