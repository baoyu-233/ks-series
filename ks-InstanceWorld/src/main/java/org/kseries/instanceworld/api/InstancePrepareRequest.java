package org.kseries.instanceworld.api;

import java.time.Duration;

public record InstancePrepareRequest(
        String namespace,
        String templateKey,
        String schematicName,
        InstanceGridSpec grid,
        int pasteY,
        int arenaRadius,
        Duration timeout
) {
    public InstancePrepareRequest {
        namespace = requireId(namespace, "namespace");
        templateKey = requireId(templateKey, "templateKey");
        schematicName = requireSchematicName(schematicName);
        if (grid == null) throw new IllegalArgumentException("grid is required");
        if (arenaRadius < 16) throw new IllegalArgumentException("arenaRadius must be at least 16");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        String trimmed = value.trim();
        if (!trimmed.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return trimmed;
    }

    private static String requireSchematicName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("schematicName is required");
        String trimmed = value.trim().replace('\\', '/');
        if (trimmed.startsWith("/") || trimmed.contains("//") || trimmed.contains("..")
                || !trimmed.matches("[A-Za-z0-9_.:/-]+")) {
            throw new IllegalArgumentException("schematicName contains unsupported path segments");
        }
        return trimmed;
    }
}
