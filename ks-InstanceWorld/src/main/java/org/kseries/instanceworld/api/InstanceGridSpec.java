package org.kseries.instanceworld.api;

public record InstanceGridSpec(String worldName, int spacing, int maxGrids) {
    public InstanceGridSpec {
        worldName = requireId(worldName, "worldName");
        if (spacing < 128) throw new IllegalArgumentException("spacing must be at least 128");
        if (maxGrids < 1) throw new IllegalArgumentException("maxGrids must be positive");
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
