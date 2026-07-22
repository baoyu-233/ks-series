package org.kseco.crossserver.cache;

import java.util.Objects;
import java.util.UUID;

/**
 * Totally ordered hybrid logical-clock stamp for a cache mutation.
 *
 * <p>The node instance id prevents a process restart in the same millisecond
 * from producing a stamp equal to one emitted by its previous process.</p>
 */
public record CacheVersionStamp(
        long epochMillis,
        long logicalCounter,
        String originNodeId,
        UUID originInstanceId
) implements Comparable<CacheVersionStamp> {
    public CacheVersionStamp {
        if (epochMillis < 0L) {
            throw new IllegalArgumentException("epochMillis must not be negative");
        }
        if (logicalCounter < 0L) {
            throw new IllegalArgumentException("logicalCounter must not be negative");
        }
        Objects.requireNonNull(originNodeId, "originNodeId");
        if (originNodeId.isBlank()) {
            throw new IllegalArgumentException("originNodeId must not be blank");
        }
        Objects.requireNonNull(originInstanceId, "originInstanceId");
    }

    @Override
    public int compareTo(CacheVersionStamp other) {
        Objects.requireNonNull(other, "other");
        int comparison = Long.compare(epochMillis, other.epochMillis);
        if (comparison != 0) return comparison;
        comparison = Long.compare(logicalCounter, other.logicalCounter);
        if (comparison != 0) return comparison;
        comparison = originNodeId.compareTo(other.originNodeId);
        if (comparison != 0) return comparison;
        return originInstanceId.compareTo(other.originInstanceId);
    }
}
