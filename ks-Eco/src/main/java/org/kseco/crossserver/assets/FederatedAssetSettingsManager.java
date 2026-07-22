package org.kseco.crossserver.assets;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Atomic parse-validate-swap holder for all reloadable federation settings. */
public final class FederatedAssetSettingsManager {
    private final AtomicReference<FederatedAssetSettings> active =
            new AtomicReference<>(FederatedAssetSettings.safeDefaults());
    private final AtomicReference<Map<String, ?>> activeConfiguration = new AtomicReference<>(Map.of());
    private final AtomicLong generation = new AtomicLong();

    public FederatedAssetSettings current() { return active.get(); }
    public long generation() { return generation.get(); }
    public Map<String, ?> currentConfiguration() { return activeConfiguration.get(); }

    public long reload(Map<String, ?> candidate) {
        Map<String, ?> immutable = immutableMap(Objects.requireNonNull(candidate, "candidate"));
        FederatedAssetSettings parsed = FederatedAssetSettings.fromMap(immutable);
        active.set(parsed);
        activeConfiguration.set(immutable);
        return generation.incrementAndGet();
    }

    private static Map<String, ?> immutableMap(Map<String, ?> values) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) copy.put(entry.getKey(), immutableValue(entry.getValue()));
        return Map.copyOf(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Configuration keys must be strings");
                copy.put(key, immutableValue(entry.getValue()));
            }
            return Map.copyOf(copy);
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(FederatedAssetSettingsManager::immutableValue).toList();
        }
        return value;
    }
}
