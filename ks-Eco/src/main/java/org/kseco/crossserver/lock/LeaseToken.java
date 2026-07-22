package org.kseco.crossserver.lock;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable proof of one lease generation.
 *
 * <p>The fencing token is monotonically increasing for a resource. A protected
 * database write must reject a token lower than or equal to the token already
 * recorded for the same idempotency key.</p>
 */
public record LeaseToken(
        String resourceKey,
        String ownerId,
        UUID leaseId,
        long fencingToken,
        Instant acquiredAt,
        Instant leaseUntil
) {
    public LeaseToken {
        resourceKey = requireIdentifier(resourceKey, "resourceKey");
        ownerId = requireIdentifier(ownerId, "ownerId");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (fencingToken <= 0L) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        if (!leaseUntil.isAfter(acquiredAt)) {
            throw new IllegalArgumentException("leaseUntil must be after acquiredAt");
        }
    }

    public boolean sameLease(LeaseToken other) {
        return other != null
                && resourceKey.equals(other.resourceKey)
                && ownerId.equals(other.ownerId)
                && leaseId.equals(other.leaseId)
                && fencingToken == other.fencingToken;
    }

    /** Local fast-fail only. Authoritative checks always use database time. */
    public boolean isLocallyExpired(Clock clock) {
        return !leaseUntil.isAfter(Instant.now(Objects.requireNonNull(clock, "clock")));
    }

    static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > 191) {
            throw new IllegalArgumentException(name + " must be at most 191 characters");
        }
        return normalized;
    }
}
