package org.kseco.crossserver.assets;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable asset projection produced by one node. canonicalKey identifies replicas of the same logical asset. */
public record FederatedAsset(
        String canonicalKey,
        String sourceAssetId,
        String assetType,
        String ownerKey,
        long quantity,
        long valueMinor,
        long revision,
        long updatedAt,
        AssetSource source
) {
    private static final Pattern TYPE = Pattern.compile("[A-Z0-9_:-]{1,64}");

    public FederatedAsset {
        canonicalKey = bounded(canonicalKey, "canonicalKey", 191).toLowerCase(Locale.ROOT);
        sourceAssetId = bounded(sourceAssetId, "sourceAssetId", 191);
        assetType = bounded(assetType, "assetType", 64).toUpperCase(Locale.ROOT);
        ownerKey = bounded(ownerKey, "ownerKey", 191).toLowerCase(Locale.ROOT);
        source = Objects.requireNonNull(source, "source");
        if (!TYPE.matcher(assetType).matches()) throw new IllegalArgumentException("Invalid assetType: " + assetType);
        if (quantity < 0 || valueMinor < 0 || revision < 0 || updatedAt < 0) {
            throw new IllegalArgumentException("Asset numeric values must be non-negative");
        }
    }

    private static String bounded(String value, String name, int max) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return normalized;
    }
}
