package org.kseries.instanceworld.api;

public record GridSnapshot(
        String gridId,
        String worldName,
        int centerX,
        int centerZ,
        String status,
        long occupiedSince,
        long lastUsedAt
) { }
