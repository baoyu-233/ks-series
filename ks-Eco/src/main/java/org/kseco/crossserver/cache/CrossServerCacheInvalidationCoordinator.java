package org.kseco.crossserver.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Coordinates cache invalidation across server nodes without depending on a
 * specific transport. Call {@link #invalidate(String, String)} for a local
 * mutation, publish the returned message, and pass received messages to
 * {@link #receive(CacheInvalidationMessage)}.
 */
public final class CrossServerCacheInvalidationCoordinator {
    private static final int DEFAULT_DEDUPLICATION_CAPACITY = 16_384;
    private static final Duration DEFAULT_DEDUPLICATION_RETENTION = Duration.ofMinutes(10);

    private final String nodeId;
    private final LongSupplier epochMillisClock;
    private final HybridLogicalClock versionClock;
    private final CacheInvalidationDeduplicator deduplicator;
    private final ConcurrentHashMap<CacheKey, VersionState> currentVersions = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private final ConcurrentHashMap<CacheKey, CopyOnWriteArrayList<CacheInvalidationListener>> keyListeners =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<CacheInvalidationListener>> namespaceListeners =
            new ConcurrentHashMap<>();

    public CrossServerCacheInvalidationCoordinator(String nodeId) {
        this(nodeId, DEFAULT_DEDUPLICATION_CAPACITY, DEFAULT_DEDUPLICATION_RETENTION);
    }

    public CrossServerCacheInvalidationCoordinator(
            String nodeId,
            int deduplicationCapacity,
            Duration deduplicationRetention
    ) {
        this(nodeId, UUID.randomUUID(), deduplicationCapacity, deduplicationRetention);
    }

    public CrossServerCacheInvalidationCoordinator(String nodeId, UUID nodeInstanceId) {
        this(nodeId, nodeInstanceId, DEFAULT_DEDUPLICATION_CAPACITY, DEFAULT_DEDUPLICATION_RETENTION);
    }

    public CrossServerCacheInvalidationCoordinator(
            String nodeId,
            UUID nodeInstanceId,
            int deduplicationCapacity,
            Duration deduplicationRetention
    ) {
        this(
                nodeId,
                nodeInstanceId,
                deduplicationCapacity,
                deduplicationRetention,
                System::currentTimeMillis,
                System::nanoTime
        );
    }

    CrossServerCacheInvalidationCoordinator(
            String nodeId,
            UUID nodeInstanceId,
            int deduplicationCapacity,
            Duration deduplicationRetention,
            LongSupplier epochMillisClock,
            LongSupplier nanoClock
    ) {
        this.nodeId = requireNodeId(nodeId);
        LongSupplier checkedEpochMillisClock = Objects.requireNonNull(epochMillisClock, "epochMillisClock");
        this.epochMillisClock = checkedEpochMillisClock;
        this.versionClock = new HybridLogicalClock(
                this.nodeId,
                Objects.requireNonNull(nodeInstanceId, "nodeInstanceId"),
                checkedEpochMillisClock
        );
        this.deduplicator = new CacheInvalidationDeduplicator(
                deduplicationCapacity,
                deduplicationRetention,
                Objects.requireNonNull(nanoClock, "nanoClock")
        );
    }

    public String nodeId() {
        return nodeId;
    }

    /**
     * Applies a local invalidation and returns the message that should be
     * published by the configured cross-server transport.
     */
    public LocalInvalidation invalidate(String namespace, String key) {
        return invalidate(new CacheKey(namespace, key));
    }

    public LocalInvalidation invalidate(CacheKey target) {
        Objects.requireNonNull(target, "target");
        CacheVersionStamp version = versionClock.next();
        CacheInvalidationMessage message = new CacheInvalidationMessage(
                UUID.randomUUID(),
                target,
                version,
                Math.max(0L, epochMillisClock.getAsLong())
        );
        return new LocalInvalidation(message, applyFirstObservation(message));
    }

    /**
     * Accepts a message received from any transport, including an echo of a
     * locally published event.
     */
    public ApplyResult receive(CacheInvalidationMessage message) {
        Objects.requireNonNull(message, "message");
        if (!deduplicator.markIfNew(message.eventId())) {
            CacheVersionStamp current = currentVersionValue(message.target());
            return new ApplyResult(ApplyStatus.DUPLICATE, current, current, 0, 0);
        }
        versionClock.observe(message.version());
        return applyObserved(message);
    }

    public VersionSnapshot snapshot(String namespace, String key) {
        return snapshot(new CacheKey(namespace, key));
    }

    public VersionSnapshot snapshot(CacheKey target) {
        Objects.requireNonNull(target, "target");
        VersionState state = currentVersions.get(target);
        return new VersionSnapshot(target, state == null ? null : state.version(), state == null ? 0L : state.revision());
    }

    public Optional<CacheVersionStamp> currentVersion(String namespace, String key) {
        return currentVersion(new CacheKey(namespace, key));
    }

    public Optional<CacheVersionStamp> currentVersion(CacheKey target) {
        Objects.requireNonNull(target, "target");
        return Optional.ofNullable(currentVersionValue(target));
    }

    /**
     * Checks whether a cache load raced with an invalidation.
     */
    public boolean isUnchanged(VersionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        VersionState current = currentVersions.get(snapshot.target());
        long currentRevision = current == null ? 0L : current.revision();
        return snapshot.revision() == currentRevision;
    }

    public CacheInvalidationSubscription subscribe(
            String namespace,
            String key,
            CacheInvalidationListener listener
    ) {
        return subscribe(new CacheKey(namespace, key), listener);
    }

    public CacheInvalidationSubscription subscribe(CacheKey target, CacheInvalidationListener listener) {
        Objects.requireNonNull(target, "target");
        CacheInvalidationListener checkedListener = Objects.requireNonNull(listener, "listener");
        CopyOnWriteArrayList<CacheInvalidationListener> listeners =
                keyListeners.computeIfAbsent(target, ignored -> new CopyOnWriteArrayList<>());
        listeners.add(checkedListener);
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (!open.compareAndSet(true, false)) return;
            listeners.remove(checkedListener);
            if (listeners.isEmpty()) {
                keyListeners.remove(target, listeners);
            }
        };
    }

    /**
     * Subscribes to every key invalidation in one namespace.
     */
    public CacheInvalidationSubscription subscribeNamespace(
            String namespace,
            CacheInvalidationListener listener
    ) {
        String checkedNamespace = new CacheKey(namespace, "validation-key").namespace();
        CacheInvalidationListener checkedListener = Objects.requireNonNull(listener, "listener");
        CopyOnWriteArrayList<CacheInvalidationListener> listeners =
                namespaceListeners.computeIfAbsent(checkedNamespace, ignored -> new CopyOnWriteArrayList<>());
        listeners.add(checkedListener);
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (!open.compareAndSet(true, false)) return;
            listeners.remove(checkedListener);
            if (listeners.isEmpty()) {
                namespaceListeners.remove(checkedNamespace, listeners);
            }
        };
    }

    public int trackedKeyCount() {
        return currentVersions.size();
    }

    public int deduplicationEntryCount() {
        return deduplicator.estimatedSize();
    }

    private ApplyResult applyFirstObservation(CacheInvalidationMessage message) {
        if (!deduplicator.markIfNew(message.eventId())) {
            throw new IllegalStateException("newly generated event id was already observed");
        }
        return applyObserved(message);
    }

    private ApplyResult applyObserved(CacheInvalidationMessage message) {
        VersionState previous = currentVersions.put(
                message.target(), new VersionState(message.version(), revision.incrementAndGet()));
        NotificationCounts notifications = notifyListeners(message);
        ApplyStatus status = notifications.failed() == 0 ? ApplyStatus.APPLIED : ApplyStatus.RETRY_REQUIRED;
        if (notifications.failed() > 0) deduplicator.forget(message.eventId());
        return new ApplyResult(
                status,
                previous == null ? null : previous.version(),
                message.version(),
                notifications.notified(),
                notifications.failed()
        );
    }

    private CacheVersionStamp currentVersionValue(CacheKey target) {
        VersionState state = currentVersions.get(target);
        return state == null ? null : state.version();
    }

    private NotificationCounts notifyListeners(CacheInvalidationMessage message) {
        AtomicInteger notified = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        notifyListeners(keyListeners.get(message.target()), message, notified, failed);
        notifyListeners(namespaceListeners.get(message.namespace()), message, notified, failed);
        return new NotificationCounts(notified.get(), failed.get());
    }

    private static void notifyListeners(
            CopyOnWriteArrayList<CacheInvalidationListener> listeners,
            CacheInvalidationMessage message,
            AtomicInteger notified,
            AtomicInteger failed
    ) {
        if (listeners == null) return;
        for (CacheInvalidationListener listener : listeners) {
            try {
                listener.onInvalidation(message);
                notified.incrementAndGet();
            } catch (RuntimeException ignored) {
                failed.incrementAndGet();
            }
        }
    }

    private static String requireNodeId(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        return nodeId;
    }

    @FunctionalInterface
    public interface CacheInvalidationListener {
        void onInvalidation(CacheInvalidationMessage message);
    }

    @FunctionalInterface
    public interface CacheInvalidationSubscription extends AutoCloseable {
        @Override
        void close();
    }

    public enum ApplyStatus {
        APPLIED,
        DUPLICATE,
        STALE,
        RETRY_REQUIRED
    }

    public record ApplyResult(
            ApplyStatus status,
            CacheVersionStamp previousVersion,
            CacheVersionStamp currentVersion,
            int notifiedListeners,
            int listenerFailures
    ) {
        public ApplyResult {
            Objects.requireNonNull(status, "status");
            if (notifiedListeners < 0 || listenerFailures < 0) {
                throw new IllegalArgumentException("listener counts must not be negative");
            }
        }

        public boolean applied() {
            return status == ApplyStatus.APPLIED || status == ApplyStatus.RETRY_REQUIRED;
        }
    }

    public record LocalInvalidation(CacheInvalidationMessage message, ApplyResult result) {
        public LocalInvalidation {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(result, "result");
            if (!result.applied()) {
                throw new IllegalArgumentException("local invalidation must be applied");
            }
        }
    }

    public record VersionSnapshot(CacheKey target, CacheVersionStamp version, long revision) {
        public VersionSnapshot {
            Objects.requireNonNull(target, "target");
            if (revision < 0L) throw new IllegalArgumentException("revision must not be negative");
        }
    }

    private record NotificationCounts(int notified, int failed) {}
    private record VersionState(CacheVersionStamp version, long revision) {}

    private static final class HybridLogicalClock {
        private final String nodeId;
        private final UUID nodeInstanceId;
        private final LongSupplier epochMillisClock;
        private long lastEpochMillis;
        private long logicalCounter;

        private HybridLogicalClock(String nodeId, UUID nodeInstanceId, LongSupplier epochMillisClock) {
            this.nodeId = nodeId;
            this.nodeInstanceId = nodeInstanceId;
            this.epochMillisClock = epochMillisClock;
        }

        private synchronized CacheVersionStamp next() {
            long now = Math.max(0L, epochMillisClock.getAsLong());
            if (now > lastEpochMillis) {
                lastEpochMillis = now;
                logicalCounter = 0L;
            } else if (logicalCounter == Long.MAX_VALUE) {
                if (lastEpochMillis == Long.MAX_VALUE) {
                    throw new IllegalStateException("hybrid logical clock exhausted");
                }
                lastEpochMillis++;
                logicalCounter = 0L;
            } else {
                logicalCounter++;
            }
            return new CacheVersionStamp(lastEpochMillis, logicalCounter, nodeId, nodeInstanceId);
        }

        private synchronized void observe(CacheVersionStamp remote) {
            if (remote.epochMillis() > lastEpochMillis) {
                lastEpochMillis = remote.epochMillis();
                logicalCounter = remote.logicalCounter();
            } else if (remote.epochMillis() == lastEpochMillis) {
                logicalCounter = Math.max(logicalCounter, remote.logicalCounter());
            }
        }
    }
}
