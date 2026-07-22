package org.kseries.instanceworld.api;

public record InstanceSnapshot(
        String instanceId,
        String namespace,
        String templateKey,
        String worldName,
        String gridId,
        int gridCenterX,
        int gridCenterZ,
        InstanceState state,
        InstanceBounds bounds,
        InstancePoint pasteCenter,
        InstancePoint spawn,
        String error
) { }
