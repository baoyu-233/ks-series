package org.kseco.crossserver.assets;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable location of an asset or snapshot in the federation. */
public record AssetSource(String nodeId, String worldId, String dimensionId) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.:/-]{1,128}");

    public AssetSource {
        nodeId = canonical(nodeId, "nodeId");
        worldId = canonical(worldId, "worldId");
        dimensionId = canonical(dimensionId, "dimensionId");
    }

    public String stableKey() {
        return nodeId + "/" + worldId + "/" + dimensionId;
    }

    static String canonical(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return normalized;
    }
}
