package org.kseco;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded worker pool for pure computation and database/audit work.
 * Bukkit, inventory, entity and Vault calls must remain on the server thread.
 */
public final class AsyncWorkPool {
    private final ExecutorService executor;

    public AsyncWorkPool(int maxWorkers) {
        int workers = Math.max(1, Math.min(maxWorkers, Runtime.getRuntime().availableProcessors()));
        AtomicInteger number = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "ks-Eco-worker-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newFixedThreadPool(workers, factory);
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
