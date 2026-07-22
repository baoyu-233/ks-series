package org.kseco.crossserver.cache;

import java.util.Objects;
import java.util.UUID;

/**
 * Transport-neutral, immutable cache invalidation event.
 */
public record CacheInvalidationMessage(
        UUID eventId,
        CacheKey target,
        CacheVersionStamp version,
        long createdAtEpochMillis
) {
    public CacheInvalidationMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(version, "version");
        if (createdAtEpochMillis < 0L) {
            throw new IllegalArgumentException("createdAtEpochMillis must not be negative");
        }
    }

    public String namespace() {
        return target.namespace();
    }

    public String key() {
        return target.key();
    }
}
