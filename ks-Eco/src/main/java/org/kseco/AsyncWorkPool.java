package org.kseco;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Bounded worker pool for pure computation and database/audit work.
 * Bukkit, inventory, entity and Vault calls must remain on the server thread.
 */
public final class AsyncWorkPool {
    private static final int MIN_COMPUTE_QUEUE_CAPACITY = 128;
    private static final int DATABASE_QUEUE_CAPACITY = 4096;
    private static final long PRESSURE_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final Lane computeLane;
    private final Lane databaseLane;

    public AsyncWorkPool(int maxWorkers) {
        this(maxWorkers, Logger.getLogger(AsyncWorkPool.class.getName()));
    }

    public AsyncWorkPool(int maxWorkers, Logger logger) {
        int workers = Math.max(1, Math.min(maxWorkers, Runtime.getRuntime().availableProcessors()));
        int computeQueueCapacity = Math.max(MIN_COMPUTE_QUEUE_CAPACITY, workers * 64);
        Logger targetLogger = Objects.requireNonNull(logger, "logger");
        computeLane = new Lane("compute", workers, computeQueueCapacity, targetLogger);
        databaseLane = new Lane("database", 1, DATABASE_QUEUE_CAPACITY, targetLogger);
    }

    public void execute(Runnable task) {
        computeLane.execute(task);
    }

    public void executeDatabase(Runnable task) {
        databaseLane.execute(task);
    }

    public Metrics metrics() {
        return new Metrics(computeLane.metrics(), databaseLane.metrics());
    }

    public void shutdown() {
        computeLane.shutdown();
        computeLane.awaitTermination(5);
        databaseLane.shutdown();
        databaseLane.awaitTermination(10);
    }

    public record Metrics(LaneMetrics compute, LaneMetrics database) {}

    public record LaneMetrics(
            int workers,
            int active,
            int queued,
            int queueCapacity,
            long submitted,
            long completed,
            long rejected
    ) {}

    private static final class Lane {
        private final String name;
        private final Logger logger;
        private final ThreadPoolExecutor executor;
        private final AtomicLong submitted = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong lastPressureWarning = new AtomicLong();

        private Lane(String name, int workers, int queueCapacity, Logger logger) {
            this.name = name;
            this.logger = logger;
            this.executor = new ThreadPoolExecutor(
                    workers,
                    workers,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    threadFactory("ks-Eco-" + name + "-"),
                    new ThreadPoolExecutor.AbortPolicy()
            );
        }

        private void execute(Runnable task) {
            Objects.requireNonNull(task, "task");
            submitted.incrementAndGet();
            try {
                executor.execute(task);
                warnIfPressured();
            } catch (RejectedExecutionException exception) {
                rejected.incrementAndGet();
                logger.severe("[异步队列] " + name + " 任务被拒绝: " + metrics());
                throw exception;
            }
        }

        private void warnIfPressured() {
            int queued = executor.getQueue().size();
            int queueCapacity = queued + executor.getQueue().remainingCapacity();
            if (queued * 4 < queueCapacity * 3) return;

            long now = System.nanoTime();
            long previous = lastPressureWarning.get();
            if (now - previous < PRESSURE_WARNING_INTERVAL_NANOS
                    || !lastPressureWarning.compareAndSet(previous, now)) return;
            logger.warning("[异步队列] " + name + " 队列压力较高: " + metrics());
        }

        private LaneMetrics metrics() {
            int queued = executor.getQueue().size();
            int queueCapacity = queued + executor.getQueue().remainingCapacity();
            return new LaneMetrics(
                    executor.getCorePoolSize(),
                    executor.getActiveCount(),
                    queued,
                    queueCapacity,
                    submitted.get(),
                    executor.getCompletedTaskCount(),
                    rejected.get()
            );
        }

        private void shutdown() {
            executor.shutdown();
        }

        private void awaitTermination(int seconds) {
            try {
                if (!executor.awaitTermination(seconds, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        private static ThreadFactory threadFactory(String prefix) {
            AtomicInteger number = new AtomicInteger();
            return task -> {
                Thread thread = new Thread(task, prefix + number.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
        }
    }
}
