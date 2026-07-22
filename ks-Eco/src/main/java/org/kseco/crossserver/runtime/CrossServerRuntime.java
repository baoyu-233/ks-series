package org.kseco.crossserver.runtime;

import org.kseco.crossserver.cache.CacheInvalidationMessage;
import org.kseco.crossserver.cache.CacheInvalidationWireAdapter;
import org.kseco.crossserver.cache.CrossServerCacheInvalidationCoordinator;
import org.kseco.crossserver.transport.BatchDisposition;
import org.kseco.crossserver.transport.CrossServerTransport;
import org.kseco.crossserver.transport.PollBatch;
import org.kseco.crossserver.transport.TransportBatchHandler;
import org.kseco.crossserver.transport.TransportEvent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Lifecycle owner for ks-Eco's database-backed cross-server cache bus.
 * Transport callbacks arrive on Paper's server thread; listeners must only
 * enqueue database work and must never block that thread.
 */
public final class CrossServerRuntime implements AutoCloseable {
    private static final long FAILURE_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30L);

    private final CrossServerTransport transport;
    private final CrossServerCacheInvalidationCoordinator coordinator;
    private final CacheInvalidationWireAdapter wireAdapter;
    private final ScheduledExecutorService scheduler;
    private final Duration retention;
    private final Consumer<Throwable> failureReporter;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean publishBroken = new AtomicBoolean();
    private final AtomicLong lastFailureLogNanos = new AtomicLong(Long.MIN_VALUE);
    private volatile CompletionStage<Void> stopStage = CompletableFuture.completedFuture(null);

    public CrossServerRuntime(
            CrossServerTransport transport,
            CrossServerCacheInvalidationCoordinator coordinator,
            CacheInvalidationWireAdapter wireAdapter,
            ScheduledExecutorService scheduler,
            Duration retention,
            Consumer<Throwable> failureReporter
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.wireAdapter = Objects.requireNonNull(wireAdapter, "wireAdapter");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isNegative()) throw new IllegalArgumentException("retention must not be negative");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("cross-server runtime is closed");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("cross-server runtime can only be started once");
        }
        transport.start(new RuntimeBatchHandler());
    }

    public boolean isRunning() {
        return started.get() && !closed.get() && transport.isRunning();
    }

    public boolean isHealthy() {
        return isRunning() && !publishBroken.get();
    }

    public CompletionStage<Void> invalidate(String namespace, String key) {
        if (!isRunning()) return CompletableFuture.completedFuture(null);
        var local = coordinator.invalidate(namespace, key);
        CompletableFuture<Void> result = new CompletableFuture<>();
        publishWithRetry(wireAdapter.toTransportEvent(local.message(), retention), 1, result);
        return result;
    }

    public CrossServerCacheInvalidationCoordinator.CacheInvalidationSubscription subscribeNamespace(
            String namespace,
            CrossServerCacheInvalidationCoordinator.CacheInvalidationListener listener
    ) {
        if (closed.get()) throw new IllegalStateException("cross-server runtime is closed");
        return coordinator.subscribeNamespace(namespace, listener);
    }

    public CompletionStage<Void> stop() {
        if (!closed.compareAndSet(false, true)) return stopStage;
        CompletionStage<Void> stopped = transport.stop();
        stopStage = stopped;
        stopped.whenComplete((ignored, failure) -> scheduler.shutdownNow());
        return stopped;
    }

    @Override
    public void close() {
        stop();
    }

    private BatchDisposition receive(PollBatch batch) {
        for (var event : batch.events()) {
            if (!CacheInvalidationWireAdapter.TOPIC.equals(event.topic())) continue;
            try {
                CacheInvalidationMessage message = wireAdapter.fromTransportEvent(event);
                if (coordinator.receive(message).status()
                        == CrossServerCacheInvalidationCoordinator.ApplyStatus.RETRY_REQUIRED) {
                    return BatchDisposition.RETRY;
                }
            } catch (RuntimeException failure) {
                reportFailure(failure);
                return BatchDisposition.RETRY;
            }
        }
        return BatchDisposition.ACKNOWLEDGE;
    }

    private void publishWithRetry(TransportEvent event, int attempt, CompletableFuture<Void> result) {
        transport.publish(event).whenComplete((ignored, failure) -> {
            if (failure == null) {
                publishBroken.set(false);
                result.complete(null);
                return;
            }
            if (closed.get() || attempt >= 5) {
                publishBroken.set(true);
                reportFailure(failure);
                result.completeExceptionally(failure);
                return;
            }
            long delayMillis = Math.min(5_000L, 100L << (attempt - 1));
            try {
                scheduler.schedule(() -> publishWithRetry(event, attempt + 1, result),
                        delayMillis, TimeUnit.MILLISECONDS);
            } catch (RuntimeException rejected) {
                rejected.addSuppressed(failure);
                reportFailure(rejected);
                result.completeExceptionally(rejected);
            }
        });
    }

    private void reportFailure(Throwable failure) {
        long now = System.nanoTime();
        long previous = lastFailureLogNanos.get();
        if (previous != Long.MIN_VALUE && now - previous < FAILURE_LOG_INTERVAL_NANOS) return;
        if (lastFailureLogNanos.compareAndSet(previous, now)) failureReporter.accept(failure);
    }

    private final class RuntimeBatchHandler implements TransportBatchHandler {
        @Override
        public CompletionStage<BatchDisposition> onBatch(PollBatch batch) {
            return CompletableFuture.completedFuture(receive(batch));
        }

        @Override
        public void onTransportFailure(Throwable failure) {
            reportFailure(failure);
        }
    }
}
