package org.kseco.crossserver.transport;

import java.util.Objects;

/** Immutable input for one database polling query. */
public record PollRequest(
        String consumerId,
        String localServerId,
        PollCursor after,
        int limit,
        long nowEpochMillis
) {
    public PollRequest {
        consumerId = requireText(consumerId, "consumerId");
        localServerId = requireText(localServerId, "localServerId");
        after = Objects.requireNonNull(after, "after");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (nowEpochMillis < 0L) {
            throw new IllegalArgumentException("nowEpochMillis must be non-negative");
        }
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
