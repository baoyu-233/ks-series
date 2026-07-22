package org.kseco.crossserver.assets;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fully validated hot-reloadable settings. Database identity/lifecycle remain restart-only elsewhere. */
public record FederatedAssetSettings(
        boolean enabled,
        long snapshotTtlMillis,
        long staleAfterMillis,
        long offlineAfterMillis,
        int fragmentBytes,
        int maxSnapshotBytes,
        FederatedAccessPolicy policy
) {
    private static final Set<String> KEYS = Set.of("enabled", "snapshot-ttl-seconds", "stale-after-seconds",
            "offline-after-seconds", "fragment-bytes", "max-snapshot-bytes", "policies");

    public FederatedAssetSettings {
        policy = Objects.requireNonNull(policy, "policy");
        if (snapshotTtlMillis < 1_000 || snapshotTtlMillis > 7L * 86_400_000
                || staleAfterMillis < 1_000 || staleAfterMillis > 7L * 86_400_000
                || offlineAfterMillis < 1_000 || offlineAfterMillis > 86_400_000
                || fragmentBytes < FederatedSnapshotCodec.MIN_FRAGMENT_BYTES
                || fragmentBytes > FederatedSnapshotCodec.MAX_FRAGMENT_BYTES
                || maxSnapshotBytes < 1_024 || maxSnapshotBytes > 64 * 1_048_576) {
            throw new IllegalArgumentException("Federated asset settings out of bounds");
        }
    }

    public static FederatedAssetSettings safeDefaults() {
        return new FederatedAssetSettings(false, 300_000, 300_000, 120_000, 262_144, 8 * 1_048_576,
                FederatedAccessPolicy.denyAll());
    }

    public static FederatedAssetSettings fromMap(Map<String, ?> raw) {
        Objects.requireNonNull(raw, "raw");
        for (String key : raw.keySet()) {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown federated-assets key: " + key);
        }
        boolean enabled = bool(raw.get("enabled"), false, "enabled");
        long ttl = seconds(raw.get("snapshot-ttl-seconds"), 300, "snapshot-ttl-seconds");
        long stale = seconds(raw.get("stale-after-seconds"), 300, "stale-after-seconds");
        long offline = seconds(raw.get("offline-after-seconds"), 120, "offline-after-seconds");
        int fragments = integer(raw.get("fragment-bytes"), 262_144, "fragment-bytes");
        int maxSnapshot = integer(raw.get("max-snapshot-bytes"), 8 * 1_048_576, "max-snapshot-bytes");
        FederatedAccessPolicy policy = FederatedAccessPolicy.fromMap(map(raw.get("policies"), "policies"));
        return new FederatedAssetSettings(enabled, Math.multiplyExact(ttl, 1_000),
                Math.multiplyExact(stale, 1_000), Math.multiplyExact(offline, 1_000), fragments, maxSnapshot, policy);
    }

    private static boolean bool(Object raw, boolean fallback, String path) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean value) return value;
        throw new IllegalArgumentException(path + " must be boolean");
    }

    private static long seconds(Object raw, long fallback, String path) {
        if (raw == null) return fallback;
        if (raw instanceof Number value) return value.longValue();
        throw new IllegalArgumentException(path + " must be numeric");
    }

    private static int integer(Object raw, int fallback, String path) {
        if (raw == null) return fallback;
        if (raw instanceof Number value) return value.intValue();
        throw new IllegalArgumentException(path + " must be numeric");
    }

    private static Map<String, ?> map(Object raw, String path) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> values)) throw new IllegalArgumentException(path + " must be a map");
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException(path + " keys must be strings");
            result.put(key, entry.getValue());
        }
        return Map.copyOf(result);
    }
}
