package org.kseco.crossserver.transport;

import java.util.Map;
import java.util.Objects;
import java.util.Arrays;

/**
 * Immutable, transport-neutral cross-server event. The payload must contain serialized data only.
 */
public record TransportEvent(
        String eventId,
        String topic,
        String sourceServerId,
        String targetServerId,
        long occurredAtEpochMillis,
        long availableAtEpochMillis,
        long expiresAtEpochMillis,
        String contentType,
        byte[] payload,
        Map<String, String> headers
) {
    public static final String BROADCAST_TARGET = "*";

    public TransportEvent {
        eventId = requireText(eventId, "eventId");
        topic = requireText(topic, "topic");
        sourceServerId = requireText(sourceServerId, "sourceServerId");
        targetServerId = requireText(targetServerId, "targetServerId");
        contentType = requireText(contentType, "contentType");
        if (occurredAtEpochMillis < 0L) {
            throw new IllegalArgumentException("occurredAtEpochMillis must be non-negative");
        }
        if (availableAtEpochMillis < occurredAtEpochMillis) {
            throw new IllegalArgumentException("availableAtEpochMillis must not precede occurredAtEpochMillis");
        }
        if (expiresAtEpochMillis != 0L && expiresAtEpochMillis < availableAtEpochMillis) {
            throw new IllegalArgumentException("expiresAtEpochMillis must be zero or not precede availability");
        }
        payload = Objects.requireNonNull(payload, "payload").clone();
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TransportEvent event)) return false;
        return occurredAtEpochMillis == event.occurredAtEpochMillis
                && availableAtEpochMillis == event.availableAtEpochMillis
                && expiresAtEpochMillis == event.expiresAtEpochMillis
                && eventId.equals(event.eventId)
                && topic.equals(event.topic)
                && sourceServerId.equals(event.sourceServerId)
                && targetServerId.equals(event.targetServerId)
                && contentType.equals(event.contentType)
                && Arrays.equals(payload, event.payload)
                && headers.equals(event.headers);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(eventId, topic, sourceServerId, targetServerId,
                occurredAtEpochMillis, availableAtEpochMillis, expiresAtEpochMillis, contentType, headers);
        return 31 * result + Arrays.hashCode(payload);
    }

    public boolean isBroadcast() {
        return BROADCAST_TARGET.equals(targetServerId);
    }

    public boolean isExpired(long nowEpochMillis) {
        return expiresAtEpochMillis != 0L && expiresAtEpochMillis <= nowEpochMillis;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
