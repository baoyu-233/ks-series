package org.kseco.crossserver.transport;

import java.util.concurrent.CompletionStage;

/**
 * Proxy-independent cross-server event transport. Redis and plugin-message adapters can implement this contract
 * later without changing consumers.
 */
public interface CrossServerTransport extends AutoCloseable {
    String localServerId();

    TransportCapabilities capabilities();

    /** The returned stage completes on a transport thread, never as a Bukkit-thread guarantee. */
    CompletionStage<TransportPublishReceipt> publish(TransportEvent event);

    /** Starts immutable batch delivery through the configured server-thread dispatcher. */
    void start(TransportBatchHandler handler);

    boolean isRunning();

    /** Stops new deliveries and completes after any in-flight poll/delivery cycle has left the transport. */
    CompletionStage<Void> stop();

    @Override
    default void close() {
        stop();
    }
}
