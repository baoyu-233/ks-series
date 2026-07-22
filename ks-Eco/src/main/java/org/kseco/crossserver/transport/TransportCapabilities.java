package org.kseco.crossserver.transport;

/** Describes adapter behavior without exposing an adapter-specific dependency. */
public record TransportCapabilities(
        boolean durable,
        boolean supportsDirectDelivery,
        boolean supportsBroadcast,
        boolean supportsReplay,
        int maxPayloadBytes
) {
    public TransportCapabilities {
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
    }

    public static TransportCapabilities databasePolling(int maxPayloadBytes) {
        return new TransportCapabilities(true, true, true, true, maxPayloadBytes);
    }
}
