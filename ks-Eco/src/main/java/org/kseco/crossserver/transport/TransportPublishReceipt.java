package org.kseco.crossserver.transport;

import java.util.Objects;

/** Immutable result of publishing an idempotently keyed event. */
public record TransportPublishReceipt(
        String eventId,
        Disposition disposition,
        long acceptedAtEpochMillis
) {
    public enum Disposition {
        PUBLISHED,
        ALREADY_PRESENT
    }

    public TransportPublishReceipt {
        Objects.requireNonNull(eventId, "eventId");
        disposition = Objects.requireNonNull(disposition, "disposition");
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (acceptedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("acceptedAtEpochMillis must be non-negative");
        }
    }
}
