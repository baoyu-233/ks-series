package org.kseco.crossserver;

import java.time.Instant;
import java.util.Objects;

/** A leased outbox row. Only the matching owner may publish, retry, or complete it. */
public record OutboxClaim(
        CrossServerEventEnvelope event,
        String leaseOwnerId,
        Instant leaseUntil,
        int attemptCount
) {
    public OutboxClaim {
        event = Objects.requireNonNull(event, "event");
        leaseOwnerId = CrossServerValidation.identifier(leaseOwnerId, "leaseOwnerId", 192);
        leaseUntil = CrossServerValidation.instant(leaseUntil, "leaseUntil");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be at least 1");
        }
    }
}
