package org.kseco.crossserver.transport;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Proxy-free database polling transport. SQL runs only on {@code databaseExecutor}; detached batches are then
 * delivered through {@code serverThreadDispatcher}. Cursor persistence happens afterwards on the database executor,
 * so a JDBC transaction never crosses the server-thread callback boundary.
 */
public final class DatabasePollingTransport implements CrossServerTransport {
    private final String localServerId;
    private final String consumerId;
    private final String cursorKey;
    private final DatabaseTransportStore store;
    private final Executor databaseExecutor;
    private final ScheduledExecutorService scheduler;
    private final ServerThreadDispatcher serverThreadDispatcher;
    private final int batchSize;
    private final TransportCapabilities capabilities;
    private final PollBackoffPolicy backoffPolicy;
    private final LongSupplier clock;
    private final DoubleSupplier jitter;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cycleInFlight = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduledPoll;
    private volatile CompletableFuture<Void> stopFuture = CompletableFuture.completedFuture(null);
    private volatile TransportBatchHandler handler;
    private volatile PollCursor cursor;
    private int consecutiveEmpty;
    private int consecutiveFailures;

    public DatabasePollingTransport(
            String localServerId,
            String consumerId,
            DatabaseTransportStore store,
            Executor databaseExecutor,
            ScheduledExecutorService scheduler,
            ServerThreadDispatcher serverThreadDispatcher,
            int batchSize,
            int maxPayloadBytes,
            PollBackoffPolicy backoffPolicy
    ) {
        this(localServerId, consumerId, store, databaseExecutor, scheduler, serverThreadDispatcher, batchSize,
                maxPayloadBytes, backoffPolicy, System::currentTimeMillis,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    public DatabasePollingTransport(
            String localServerId,
            String consumerId,
            DatabaseTransportStore store,
            Executor databaseExecutor,
            ScheduledExecutorService scheduler,
            ServerThreadDispatcher serverThreadDispatcher,
            int batchSize,
            int maxPayloadBytes,
            PollBackoffPolicy backoffPolicy,
            LongSupplier clock,
            DoubleSupplier jitter
    ) {
        this.localServerId = requireText(localServerId, "localServerId");
        this.consumerId = requireText(consumerId, "consumerId");
        this.cursorKey = cursorKey(this.localServerId, this.consumerId);
        this.store = Objects.requireNonNull(store, "store");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.serverThreadDispatcher = Objects.requireNonNull(serverThreadDispatcher, "serverThreadDispatcher");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
        this.capabilities = TransportCapabilities.databasePolling(maxPayloadBytes);
        this.backoffPolicy = Objects.requireNonNull(backoffPolicy, "backoffPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    @Override
    public String localServerId() {
        return localServerId;
    }

    @Override
    public TransportCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public CompletionStage<TransportPublishReceipt> publish(TransportEvent event) {
        Objects.requireNonNull(event, "event");
        CompletableFuture<TransportPublishReceipt> result = new CompletableFuture<>();
        if (!localServerId.equals(event.sourceServerId())) {
            result.completeExceptionally(new IllegalArgumentException(
                    "event sourceServerId must match this transport's localServerId"));
            return result;
        }
        if (event.payload().length > capabilities.maxPayloadBytes()) {
            result.completeExceptionally(new IllegalArgumentException("event payload exceeds transport limit"));
            return result;
        }
        try {
            databaseExecutor.execute(() -> {
                try {
                    result.complete(Objects.requireNonNull(store.publish(event), "store publish receipt"));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    @Override
    public void start(TransportBatchHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("database polling transport can only be started once");
        }
        this.handler = handler;
        this.stopFuture = new CompletableFuture<>();
        running.set(true);
        schedulePoll(0L);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public CompletionStage<Void> stop() {
        if (!started.get()) {
            return CompletableFuture.completedFuture(null);
        }
        running.set(false);
        ScheduledFuture<?> scheduled = scheduledPoll;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        completeStopIfIdle();
        return stopFuture;
    }

    private void schedulePoll(long delayMillis) {
        if (!running.get()) {
            completeStopIfIdle();
            return;
        }
        try {
            scheduledPoll = scheduler.schedule(this::beginPollCycle, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            running.set(false);
            reportFailure(rejected);
            completeStopIfIdle();
        }
    }

    private void beginPollCycle() {
        if (!running.get()) {
            completeStopIfIdle();
            return;
        }
        if (!cycleInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            databaseExecutor.execute(this::pollOnDatabaseExecutor);
        } catch (RejectedExecutionException rejected) {
            failCycle(rejected);
        }
    }

    private void pollOnDatabaseExecutor() {
        try {
            PollCursor expected = cursor;
            if (expected == null) {
                expected = Objects.requireNonNull(store.loadCursor(cursorKey), "store cursor");
                cursor = expected;
            }
            long now = requireNonNegativeTime(clock.getAsLong());
            PollRequest request = new PollRequest(cursorKey, localServerId, expected, batchSize, now);
            PollBatch batch = Objects.requireNonNull(store.poll(request), "store poll batch");
            validateBatch(request, batch);
            if (!running.get()) {
                finishStoppedCycle();
            } else if (batch.isEmpty() && batch.nextCursor().compareTo(expected) > 0) {
                commitBatch(expected, batch);
            } else if (batch.isEmpty()) {
                finishCycle(PollBackoffPolicy.Outcome.EMPTY, false);
            } else {
                dispatchBatch(batch, expected);
            }
        } catch (Throwable failure) {
            failCycle(failure);
        }
    }

    private void dispatchBatch(PollBatch batch, PollCursor expected) {
        try {
            serverThreadDispatcher.execute(() -> invokeHandler(batch, expected));
        } catch (Throwable failure) {
            failCycle(failure);
        }
    }

    private void invokeHandler(PollBatch batch, PollCursor expected) {
        if (!running.get()) {
            finishStoppedCycle();
            return;
        }

        CompletionStage<BatchDisposition> dispositionStage;
        try {
            dispositionStage = Objects.requireNonNull(handler.onBatch(batch), "batch handler stage");
        } catch (Throwable failure) {
            failCycle(failure);
            return;
        }
        dispositionStage.whenComplete((disposition, failure) -> {
            if (failure != null) {
                failCycle(unwrap(failure));
            } else if (!running.get()) {
                finishStoppedCycle();
            } else if (disposition == BatchDisposition.ACKNOWLEDGE) {
                commitBatch(expected, batch);
            } else if (disposition == BatchDisposition.RETRY) {
                finishCycle(PollBackoffPolicy.Outcome.FAILURE, false);
            } else {
                failCycle(new IllegalStateException("batch handler returned no disposition"));
            }
        });
    }

    private void commitBatch(PollCursor expected, PollBatch batch) {
        try {
            databaseExecutor.execute(() -> {
                try {
                    PollCursor next = batch.nextCursor();
                    if (!store.advanceCursor(cursorKey, expected, next)) {
                        cursor = Objects.requireNonNull(store.loadCursor(cursorKey), "store cursor after CAS conflict");
                        reportFailure(new IllegalStateException(
                                "cross-server cursor changed concurrently for " + cursorKey));
                        finishCycle(PollBackoffPolicy.Outcome.FAILURE, false);
                        return;
                    }
                    cursor = next;
                    finishCycle(PollBackoffPolicy.Outcome.ACTIVITY, batch.hasMore());
                } catch (Throwable failure) {
                    failCycle(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            failCycle(rejected);
        }
    }

    private void validateBatch(PollRequest request, PollBatch batch) {
        if (!request.after().equals(batch.requestedAfter())) {
            throw new IllegalArgumentException("poll batch cursor does not match its request");
        }
        if (batch.events().size() > request.limit()) {
            throw new IllegalArgumentException("poll batch exceeds its requested limit");
        }
        for (TransportEvent event : batch.events()) {
            if (!event.isBroadcast() && !localServerId.equals(event.targetServerId())) {
                throw new IllegalArgumentException("poll batch contains an event for another server");
            }
        }
    }

    private void failCycle(Throwable failure) {
        reportFailure(unwrap(failure));
        finishCycle(PollBackoffPolicy.Outcome.FAILURE, false);
    }

    private void reportFailure(Throwable failure) {
        TransportBatchHandler currentHandler = handler;
        if (currentHandler == null) {
            return;
        }
        try {
            serverThreadDispatcher.execute(() -> {
                try {
                    currentHandler.onTransportFailure(failure);
                } catch (Throwable ignored) {
                    // Failure reporting cannot be allowed to stop polling state cleanup.
                }
            });
        } catch (Throwable ignored) {
            // The dispatcher itself is the failing boundary; there is no safer callback target.
        }
    }

    private void finishCycle(PollBackoffPolicy.Outcome outcome, boolean immediate) {
        cycleInFlight.set(false);
        if (!running.get()) {
            completeStopIfIdle();
            return;
        }
        long delay = immediate ? 0L : recordOutcome(outcome);
        schedulePoll(delay);
    }

    private void finishStoppedCycle() {
        cycleInFlight.set(false);
        completeStopIfIdle();
    }

    private synchronized long recordOutcome(PollBackoffPolicy.Outcome outcome) {
        int count;
        switch (outcome) {
            case ACTIVITY -> {
                consecutiveEmpty = 0;
                consecutiveFailures = 0;
                count = 1;
            }
            case EMPTY -> {
                consecutiveEmpty++;
                consecutiveFailures = 0;
                count = consecutiveEmpty;
            }
            case FAILURE -> {
                consecutiveFailures++;
                consecutiveEmpty = 0;
                count = consecutiveFailures;
            }
            default -> throw new IllegalStateException("unhandled polling outcome " + outcome);
        }
        double jitterUnit = jitter.getAsDouble();
        if (!Double.isFinite(jitterUnit) || jitterUnit < 0.0d || jitterUnit > 1.0d) {
            jitterUnit = 0.5d;
        }
        return backoffPolicy.delayMillis(outcome, count, jitterUnit);
    }

    private void completeStopIfIdle() {
        if (!running.get() && !cycleInFlight.get()) {
            stopFuture.complete(null);
        }
    }

    private static long requireNonNegativeTime(long value) {
        if (value < 0L) {
            throw new IllegalStateException("clock returned a negative epoch millisecond value");
        }
        return value;
    }

    private static Throwable unwrap(Throwable failure) {
        if ((failure instanceof CompletionException) && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    static String cursorKey(String localServerId, String consumerId) {
        String key = requireText(localServerId, "localServerId") + "/" + requireText(consumerId, "consumerId");
        if (key.length() > 191) {
            throw new IllegalArgumentException("server-scoped consumer cursor key must be at most 191 characters");
        }
        return key;
    }
}
