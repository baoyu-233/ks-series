package org.kseco.crossserver.assets;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, default-deny policy. Deny rules always win over allow rules. */
public final class FederatedAccessPolicy {
    private static final Set<String> RULE_KEYS = Set.of("enabled", "allow", "deny");
    private static final Set<String> AXIS_KEYS = Set.of("servers", "worlds", "dimensions");
    private final Map<FederatedCapability, Rule> rules;

    private FederatedAccessPolicy(Map<FederatedCapability, Rule> rules) {
        EnumMap<FederatedCapability, Rule> copy = new EnumMap<>(FederatedCapability.class);
        copy.putAll(rules);
        this.rules = Collections.unmodifiableMap(copy);
    }

    public static FederatedAccessPolicy denyAll() {
        return new FederatedAccessPolicy(Map.of());
    }

    public static FederatedAccessPolicy fromMap(Map<String, ?> raw) {
        Objects.requireNonNull(raw, "raw");
        EnumMap<FederatedCapability, Rule> parsed = new EnumMap<>(FederatedCapability.class);
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            FederatedCapability capability = FederatedCapability.parse(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> ruleMap)) {
                throw new IllegalArgumentException(capability.configKey() + " must be a map");
            }
            rejectUnknown(ruleMap, RULE_KEYS, capability.configKey());
            boolean enabled = booleanValue(ruleMap.get("enabled"), false, capability.configKey() + ".enabled");
            Axis allow = axis(ruleMap.get("allow"), capability.configKey() + ".allow");
            Axis deny = axis(ruleMap.get("deny"), capability.configKey() + ".deny");
            parsed.put(capability, new Rule(enabled, allow, deny));
        }
        return new FederatedAccessPolicy(parsed);
    }

    public Decision decide(FederatedCapability capability, AssetSource source) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(source, "source");
        Rule rule = rules.get(capability);
        if (rule == null || !rule.enabled()) return Decision.denied("capability-disabled");
        if (rule.deny().matchesAny(source)) return Decision.denied("denylist");
        if (!rule.allow().matchesAll(source)) return Decision.denied("not-allowlisted");
        return Decision.allowedDecision();
    }

    public Decision decideTransfer(AssetSource source, AssetSource destination) {
        Decision sourceDecision = decide(FederatedCapability.TRANSFER, source);
        if (!sourceDecision.allowed()) return sourceDecision;
        Decision destinationDecision = decide(FederatedCapability.TRANSFER, destination);
        return destinationDecision.allowed() ? Decision.allowedDecision()
                : Decision.denied("destination-" + destinationDecision.reason());
    }

    private static Axis axis(Object raw, String path) {
        if (raw == null) return Axis.empty();
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException(path + " must be a map");
        rejectUnknown(map, AXIS_KEYS, path);
        return new Axis(selectorSet(map.get("servers"), path + ".servers"),
                selectorSet(map.get("worlds"), path + ".worlds"),
                selectorSet(map.get("dimensions"), path + ".dimensions"));
    }

    private static Set<String> selectorSet(Object raw, String path) {
        if (raw == null) return Set.of();
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException(path + " must be a list");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text)) throw new IllegalArgumentException(path + " entries must be strings");
            String normalized = text.strip().toLowerCase(Locale.ROOT);
            if (!"*".equals(normalized)) normalized = AssetSource.canonical(normalized, path);
            result.add(normalized);
        }
        return Set.copyOf(result);
    }

    private static boolean booleanValue(Object raw, boolean fallback, String path) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean value) return value;
        throw new IllegalArgumentException(path + " must be boolean");
    }

    private static void rejectUnknown(Map<?, ?> map, Set<String> accepted, String path) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String text) || !accepted.contains(text)) {
                throw new IllegalArgumentException("Unknown key at " + path + ": " + key);
            }
        }
    }

    public record Decision(boolean allowed, String reason) {
        public Decision {
            Objects.requireNonNull(reason, "reason");
        }
        static Decision allowedDecision() { return new Decision(true, "allowed"); }
        static Decision denied(String reason) { return new Decision(false, reason); }
    }

    private record Rule(boolean enabled, Axis allow, Axis deny) { }

    private record Axis(Set<String> servers, Set<String> worlds, Set<String> dimensions) {
        private static Axis empty() { return new Axis(Set.of(), Set.of(), Set.of()); }

        private boolean matchesAny(AssetSource source) {
            return matches(servers, source.nodeId()) || matches(worlds, source.worldId())
                    || matches(dimensions, source.dimensionId());
        }

        private boolean matchesAll(AssetSource source) {
            return matches(servers, source.nodeId()) && matches(worlds, source.worldId())
                    && matches(dimensions, source.dimensionId());
        }

        private static boolean matches(Set<String> selectors, String value) {
            return selectors.contains("*") || selectors.contains(value);
        }
    }
}
