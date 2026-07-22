package org.kseco.crossserver.transport;

/**
 * Stable database polling cursor backed by the transport store's monotonic publish sequence.
 */
public record PollCursor(long publishSequence)
        implements Comparable<PollCursor> {
    private static final PollCursor INITIAL = new PollCursor(0L);

    public PollCursor {
        if (publishSequence < 0L) throw new IllegalArgumentException("publishSequence must be non-negative");
    }

    public static PollCursor initial() {
        return INITIAL;
    }

    @Override
    public int compareTo(PollCursor other) {
        return Long.compare(publishSequence, java.util.Objects.requireNonNull(other, "other").publishSequence);
    }
}
