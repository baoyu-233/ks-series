package org.kseco.crossserver;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Result of atomically claiming an inbox idempotency key. */
public record InboxClaim(
        UUID eventId,
        String consumerId,
        Status status,
        String leaseOwnerId,
        Instant leaseUntil,
        int attemptCount
) {
    public InboxClaim {
        eventId = Objects.requireNonNull(eventId, "eventId");
        consumerId = CrossServerValidation.identifier(consumerId, "consumerId", 128);
        status = Objects.requireNonNull(status, "status");
        if (status == Status.CLAIMED) {
            leaseOwnerId = CrossServerValidation.identifier(leaseOwnerId, "leaseOwnerId", 192);
            leaseUntil = CrossServerValidation.instant(leaseUntil, "leaseUntil");
            if (attemptCount < 1) {
                throw new IllegalArgumentException("attemptCount must be at least 1 for a claimed event");
            }
        } else if (leaseOwnerId != null || leaseUntil != null) {
            throw new IllegalArgumentException("non-claimed inbox results must not expose a lease");
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
    }

    public static InboxClaim claimed(
            UUID eventId,
            String consumerId,
            String leaseOwnerId,
            Instant leaseUntil,
            int attemptCount
    ) {
        return new InboxClaim(eventId, consumerId, Status.CLAIMED, leaseOwnerId, leaseUntil, attemptCount);
    }

    public static InboxClaim alreadyProcessed(UUID eventId, String consumerId, int attemptCount) {
        return new InboxClaim(eventId, consumerId, Status.ALREADY_PROCESSED, null, null, attemptCount);
    }

    public static InboxClaim busy(UUID eventId, String consumerId, int attemptCount) {
        return new InboxClaim(eventId, consumerId, Status.BUSY, null, null, attemptCount);
    }

    public enum Status {
        CLAIMED,
        ALREADY_PROCESSED,
        BUSY
    }
}
