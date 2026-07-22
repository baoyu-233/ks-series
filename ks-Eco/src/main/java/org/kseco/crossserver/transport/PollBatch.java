package org.kseco.crossserver.transport;

import java.util.List;
import java.util.Objects;

/**
 * Immutable ordered polling result. The next cursor may advance over expired or other-target events.
 */
public record PollBatch(
        PollCursor requestedAfter,
        List<TransportEvent> events,
        PollCursor nextCursor,
        boolean hasMore,
        long polledAtEpochMillis
) {
    public PollBatch {
        requestedAfter = Objects.requireNonNull(requestedAfter, "requestedAfter");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (polledAtEpochMillis < 0L) {
            throw new IllegalArgumentException("polledAtEpochMillis must be non-negative");
        }
        events.forEach(event -> Objects.requireNonNull(event, "events must not contain null"));
        if (nextCursor.compareTo(requestedAfter) < 0) {
            throw new IllegalArgumentException("nextCursor must not precede requestedAfter");
        }
        if (!events.isEmpty() && nextCursor.equals(requestedAfter)) {
            throw new IllegalArgumentException("a non-empty batch must advance the cursor");
        }
    }

    public static PollBatch empty(PollCursor requestedAfter, long polledAtEpochMillis) {
        return new PollBatch(requestedAfter, List.of(), requestedAfter, false, polledAtEpochMillis);
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

}
