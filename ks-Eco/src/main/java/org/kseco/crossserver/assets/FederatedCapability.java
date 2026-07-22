package org.kseco.crossserver.assets;

import java.util.Locale;

/** Operator-controlled cross-node capabilities. */
public enum FederatedCapability {
    MAP_VIEW("map-view"),
    PROPERTY_TRADE("property-trade"),
    ASSET_AGGREGATE("asset-aggregate"),
    TRANSFER("transfer");

    private final String configKey;

    FederatedCapability(String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return configKey;
    }

    public static FederatedCapability parse(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (FederatedCapability capability : values()) {
            if (capability.configKey.equals(normalized)) return capability;
        }
        throw new IllegalArgumentException("Unknown federated capability: " + value);
    }
}
