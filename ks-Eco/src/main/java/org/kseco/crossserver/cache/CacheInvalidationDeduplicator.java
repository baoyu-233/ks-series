package org.kseco.crossserver.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

/**
 * Thread-safe, bounded event-id deduplicator with monotonic-time retention.
 */
public final class CacheInvalidationDeduplicator {
    private final int maxEntries;
    private final long retentionNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<UUID, Long> seenAt = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SeenEvent> insertionOrder = new ConcurrentLinkedQueue<>();

    public CacheInvalidationDeduplicator(int maxEntries, Duration retention) {
        this(maxEntries, retention, System::nanoTime);
    }

    CacheInvalidationDeduplicator(int maxEntries, Duration retention, LongSupplier nanoClock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        Objects.requireNonNull(retention, "retention");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.maxEntries = maxEntries;
        this.retentionNanos = toPositiveNanos(retention);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * Atomically records an event id.
     *
     * @return {@code true} only for the first observation inside the retention window
     */
    public boolean markIfNew(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        long now = nanoClock.getAsLong();

        while (true) {
            Long previous = seenAt.get(eventId);
            if (previous != null && !isExpired(previous, now)) {
                evict(now);
                return false;
            }

            boolean recorded = previous == null
                    ? seenAt.putIfAbsent(eventId, now) == null
                    : seenAt.replace(eventId, previous, now);
            if (!recorded) continue;

            insertionOrder.add(new SeenEvent(eventId, now));
            evict(now);
            return true;
        }
    }

    public int estimatedSize() {
        return seenAt.size();
    }

    public void forget(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        seenAt.remove(eventId);
    }

    public void clear() {
        seenAt.clear();
        insertionOrder.clear();
    }

    private void evict(long now) {
        while (true) {
            SeenEvent oldest = insertionOrder.peek();
            if (oldest == null) return;
            if (seenAt.size() <= maxEntries && !isExpired(oldest.seenAtNanos(), now)) return;

            insertionOrder.poll();
            seenAt.remove(oldest.eventId(), oldest.seenAtNanos());
        }
    }

    private boolean isExpired(long recordedAt, long now) {
        return now - recordedAt >= retentionNanos;
    }

    private static long toPositiveNanos(Duration duration) {
        try {
            long nanos = duration.toNanos();
            return nanos > 0L ? nanos : 1L;
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private record SeenEvent(UUID eventId, long seenAtNanos) {}
}
